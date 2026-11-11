package com.san.bastionpay.openbanking;

import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import com.san.bastionpay.openbanking.OpenBankingClient.Account;
import com.san.bastionpay.openbanking.OpenBankingClient.Balance;
import com.san.bastionpay.openbanking.OpenBankingClient.Token;
import com.san.bastionpay.openbanking.OpenBankingClient.Transaction;
import com.san.bastionpay.openbanking.OpenBankingClient.TransactionPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 오픈뱅킹 사용자 인증(3-legged OAuth)과 조회.
 * <ul>
 *   <li>state 는 서버에 해시로 저장하고 브라우저 쿠키와 쿼리 값이 모두 맞아야 쓴다(한 번 쓰면 폐기).</li>
 *   <li>access token 은 암호화해서 저장하고, 외부 API 호출은 DB 트랜잭션 밖에서 한다.</li>
 *   <li>잔액 조회는 본인 계좌 목록에 있는 핀테크이용번호만 허용한다(다른 사람 번호로 조회 방지).</li>
 * </ul>
 */
@Slf4j
@Service
public class OpenBankingService {

    /** 조회와 이체. 이체는 은행 화면에서 계좌마다 따로 허용해야 하므로 요청해도 허용하지 않은 계좌는 이체할 수 없다. */
    static final String SCOPE = "login inquiry transfer";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 계좌 거래내역 한 페이지의 건수. */
    public static final int PAGE_SIZE = 10;
    static final int ALL_LIMIT = 100;
    private static final String STATE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OpenBankingProperties props;
    private final OpenBankingClient client;
    private final OpenBankingLinkRepository links;
    private final OpenBankingAuthStateRepository states;
    private final TokenCipher cipher;
    private final SecurityEventRecorder events;

    public OpenBankingService(OpenBankingProperties props, OpenBankingClient client, OpenBankingLinkRepository links,
                              OpenBankingAuthStateRepository states, TokenCipher cipher, SecurityEventRecorder events) {
        this.events = events;
        this.props = props;
        this.client = client;
        this.links = links;
        this.states = states;
        this.cipher = cipher;
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    public boolean canQueryBalance() {
        return props.canQueryBalance();
    }

    /** 인증 시작: state 를 만들어 저장하고 은행 인증 화면 주소를 돌려준다. */
    public Authorization begin(long memberId) {
        return begin(memberId, props.redirectUri());
    }

    /** redirectUri 는 허용 목록에서 고른 값이어야 한다({@link RedirectUris}). */
    public Authorization begin(long memberId, String redirectUri) {
        requireConfigured();
        String state = randomState();
        states.save(new OpenBankingAuthState(sha256(state), memberId, LocalDateTime.now().plus(props.stateTtl())));
        String url = UriComponentsBuilder.fromUriString(props.baseUrl())
                .path("/oauth/2.0/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", props.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .queryParam("auth_type", "0")
                .build().encode().toUriString();
        return new Authorization(url, state);
    }

    /** 콜백 처리: state 를 검증하고 코드를 토큰으로 교환해 저장한다. 연결된 회원 id 를 돌려준다. */
    public long complete(String stateParam, String stateCookie, String code) {
        return complete(stateParam, stateCookie, code, props.redirectUri());
    }

    /** 토큰 교환의 redirect_uri 는 인증을 시작할 때 보낸 값과 같아야 한다. */
    public long complete(String stateParam, String stateCookie, String code, String redirectUri) {
        requireConfigured();
        if (isBlank(stateParam) || isBlank(stateCookie) || isBlank(code)
                || !MessageDigest.isEqual(stateParam.getBytes(StandardCharsets.UTF_8), stateCookie.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.OPENBANKING_AUTH_FAILED, "state 불일치 또는 누락");
        }
        String hash = sha256(stateParam);
        OpenBankingAuthState saved = states.findByStateHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPENBANKING_AUTH_FAILED, "알 수 없는 state"));
        // 동시에 같은 state 로 들어와도 한 요청만 통과한다
        if (states.consume(hash) != 1 || saved.isExpired(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.OPENBANKING_AUTH_FAILED, "사용됐거나 만료된 state");
        }

        Token token = client.exchangeCode(code, redirectUri);
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(token.expiresInSeconds());
        String encrypted = cipher.encrypt(token.accessToken());
        String scope = isBlank(token.scope()) ? SCOPE : token.scope();
        // 이미 다른 회원에게 연결된 은행 고객이면 연결하지 않는다(같은 계좌를 여러 계정이 쓰는 것을 막는다)
        OpenBankingLink holder = links.findByUserSeqNo(token.userSeqNo()).orElse(null);
        if (holder != null && !holder.getMemberId().equals(saved.getMemberId())) {
            events.record(SecurityEvent.of(SecurityEventType.OPENBANKING_LINK_REJECTED, EventOutcome.FAILURE)
                    .memberId(saved.getMemberId()).reasonCode("ALREADY_LINKED"));
            throw new BusinessException(ErrorCode.OPENBANKING_ALREADY_LINKED);
        }
        OpenBankingLink link = links.findByMemberId(saved.getMemberId()).orElse(null);
        try {
            if (link == null) {
                links.save(new OpenBankingLink(saved.getMemberId(), token.userSeqNo(), encrypted, scope, expiresAt));
            } else {
                link.renew(token.userSeqNo(), encrypted, scope, expiresAt);
                links.save(link);
            }
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 동시에 다른 회원이 같은 고객을 연결한 경우(유일 제약)
            events.record(SecurityEvent.of(SecurityEventType.OPENBANKING_LINK_REJECTED, EventOutcome.FAILURE)
                    .memberId(saved.getMemberId()).reasonCode("ALREADY_LINKED"));
            throw new BusinessException(ErrorCode.OPENBANKING_ALREADY_LINKED);
        }
        events.record(SecurityEvent.of(SecurityEventType.OPENBANKING_LINKED, EventOutcome.SUCCESS).memberId(saved.getMemberId()));
        return saved.getMemberId();
    }

    /** 연결되어 있고 토큰이 유효한지. */
    public boolean isLinked(long memberId) {
        return links.findByMemberId(memberId).filter(l -> !l.isExpired(LocalDateTime.now())).isPresent();
    }

    public List<Account> accounts(long memberId) {
        OpenBankingLink link = activeLink(memberId);
        return client.accounts(cipher.decrypt(link.getAccessTokenEnc()), link.getUserSeqNo());
    }

    /**
     * 대시보드용 합계. 잔액조회를 쓸 수 없으면(이용기관코드 없음) 계좌 수만 돌려준다.
     * 계좌 하나의 잔액 조회가 실패해도 나머지로 합계를 만들고 실패 수를 알려 준다.
     */
    public Summary summary(long memberId) {
        OpenBankingLink link = activeLink(memberId);
        String accessToken = cipher.decrypt(link.getAccessTokenEnc());
        List<Account> accounts = client.accounts(accessToken, link.getUserSeqNo());
        if (!props.canQueryBalance()) {
            return new Summary(accounts.size(), null, 0);
        }
        long total = 0;
        int failed = 0;
        for (Account account : accounts) {
            try {
                total += client.balance(accessToken, account.fintechUseNum()).balanceAmt();
            } catch (BusinessException e) {
                failed++;
                log.warn("openbanking balance failed memberId={} code={} detail={}", memberId, e.getErrorCode().code(), e.getMessage());
            }
        }
        return new Summary(accounts.size(), total, failed);
    }

    /** 계좌 목록과 계좌별 잔액. 잔액을 조회할 수 없거나 실패한 계좌의 balance 는 null 이다. */
    public List<AccountBalance> accountsWithBalance(long memberId) {
        Session session = session(memberId);
        return session.accounts().stream().map(account -> {
            if (!props.canQueryBalance()) return new AccountBalance(account, null);
            try {
                return new AccountBalance(account, client.balance(session.accessToken(), account.fintechUseNum()).balanceAmt());
            } catch (BusinessException e) {
                log.warn("openbanking balance failed memberId={} code={} detail={}", memberId, e.getErrorCode().code(), e.getMessage());
                return new AccountBalance(account, null);
            }
        }).toList();
    }

    /** 계좌 하나의 거래내역(최근 days 일, 페이지 단위). 본인 계좌가 아니면 ACCOUNT_NOT_FOUND. */
    public AccountHistory history(long memberId, String fintechUseNum, int days, String kind, int page) {
        requireOrgCode();
        Session session = session(memberId);
        Account account = ownedAccount(session, fintechUseNum);
        LocalDate to = LocalDate.now(KST);
        LocalDate from = to.minusDays(days - 1L);
        int wanted = Math.max(page, 1);
        TransactionPage result = client.transactions(session.accessToken(), account.fintechUseNum(), from, to, inquiryType(kind), wanted, PAGE_SIZE);
        if (result.items().isEmpty() && wanted > 1 && result.totalCount() > 0) {
            // 범위를 벗어난 페이지(예: 주소를 고쳐 쓴 경우)는 마지막 페이지를 보여 준다
            wanted = (result.totalCount() + PAGE_SIZE - 1) / PAGE_SIZE;
            result = client.transactions(session.accessToken(), account.fintechUseNum(), from, to, inquiryType(kind), wanted, PAGE_SIZE);
        }
        return new AccountHistory(account, result.balanceAmt(), result.items(), wanted, result.hasNext(), result.totalCount());
    }

    /**
     * 여러 계좌의 거래를 모아 최신순으로 돌려준다(최대 {@value #ALL_LIMIT}건). accountFilter 가 있으면 그 계좌만.
     * 한 계좌의 조회가 실패해도 나머지로 목록을 만들고 실패한 계좌 수를 알려 준다.
     */
    public Transactions allTransactions(long memberId, String accountFilter, int days, String kind) {
        requireOrgCode();
        Session session = session(memberId);
        List<Account> targets = accountFilter == null
                ? session.accounts()
                : List.of(ownedAccount(session, accountFilter));
        LocalDate to = LocalDate.now(KST);
        LocalDate from = to.minusDays(days - 1L);
        List<TransactionRow> rows = new ArrayList<>();
        boolean truncated = false;
        int failed = 0;
        for (Account account : targets) {
            try {
                TransactionPage page = client.transactions(session.accessToken(), account.fintechUseNum(), from, to,
                        inquiryType(kind), 1, ALL_LIMIT);
                String label = account.bankName() + " " + account.alias();
                page.items().forEach(t -> rows.add(new TransactionRow(label, t)));
                truncated |= page.hasNext();
            } catch (BusinessException e) {
                failed++;
                log.warn("openbanking transactions failed memberId={} code={} detail={}", memberId, e.getErrorCode().code(), e.getMessage());
            }
        }
        rows.sort(Comparator.comparing((TransactionRow r) -> r.tx().at()).reversed());
        if (rows.size() > ALL_LIMIT) {
            truncated = true;
        }
        return new Transactions(rows.size() > ALL_LIMIT ? List.copyOf(rows.subList(0, ALL_LIMIT)) : List.copyOf(rows), truncated, failed);
    }

    /** 이체 설정(이용기관코드·암호문구)이 있는지. */
    public boolean canTransfer() {
        return props.canTransfer();
    }

    /**
     * 출금이체에 쓸 사용자 토큰과 출금 계좌. 내 계좌 목록에 있고 은행에서 이체를 허용한 계좌만 통과한다.
     * 허용하지 않은 계좌는 TRANSFER_NOT_PERMITTED, 내 계좌가 아니면 ACCOUNT_NOT_FOUND 이다.
     */
    public TransferSource transferSource(long memberId, String fintechUseNum) {
        if (!props.canTransfer()) {
            throw new BusinessException(ErrorCode.OPENBANKING_NOT_CONFIGURED, "이용기관코드 또는 입금이체 암호문구 없음");
        }
        Session session = session(memberId);
        Account account = ownedAccount(session, fintechUseNum);
        if (!account.transferAgree()) {
            throw new BusinessException(ErrorCode.TRANSFER_NOT_PERMITTED, "이체를 허용하지 않은 계좌");
        }
        return new TransferSource(session.accessToken(), account);
    }

    /** 현재 잔액(이체 완료 화면용). */
    public long currentBalance(long memberId, String fintechUseNum) {
        Session session = session(memberId);
        return client.balance(session.accessToken(), ownedAccount(session, fintechUseNum).fintechUseNum()).balanceAmt();
    }

    public List<Account> accountList(long memberId) {
        return session(memberId).accounts();
    }

    public void disconnect(long memberId) {
        links.deleteByMemberId(memberId);
        events.record(SecurityEvent.of(SecurityEventType.OPENBANKING_UNLINKED, EventOutcome.SUCCESS).memberId(memberId));
    }

    @Scheduled(cron = "0 15 4 * * *")
    public void purgeExpiredStates() {
        int removed = states.deleteExpired(LocalDateTime.now());
        if (removed > 0) log.debug("만료된 오픈뱅킹 state {}건 삭제", removed);
    }

    /** 유효한 연결의 토큰과 계좌 목록. 이후 조회는 이 목록에 있는 계좌만 허용한다. */
    private Session session(long memberId) {
        OpenBankingLink link = activeLink(memberId);
        String accessToken = cipher.decrypt(link.getAccessTokenEnc());
        return new Session(accessToken, client.accounts(accessToken, link.getUserSeqNo()));
    }

    private static Account ownedAccount(Session session, String fintechUseNum) {
        return session.accounts().stream().filter(a -> a.fintechUseNum() != null && a.fintechUseNum().equals(fintechUseNum))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "본인 계좌 목록에 없는 핀테크이용번호"));
    }

    private void requireOrgCode() {
        if (!props.canQueryBalance()) {
            throw new BusinessException(ErrorCode.OPENBANKING_NOT_CONFIGURED, "이용기관코드(OPENBANKING_ORG_CODE) 없음");
        }
    }

    /** 화면의 구분(all/in/out)을 API 의 inquiry_type(A/I/O)으로 바꾼다. 알 수 없는 값은 전체. */
    static String inquiryType(String kind) {
        return switch (kind == null ? "" : kind) {
            case "in" -> "I";
            case "out" -> "O";
            default -> "A";
        };
    }

    private OpenBankingLink activeLink(long memberId) {
        OpenBankingLink link = links.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPENBANKING_NOT_LINKED));
        if (link.isExpired(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.OPENBANKING_NOT_LINKED, "토큰 만료");
        }
        return link;
    }

    private void requireConfigured() {
        if (!props.isConfigured()) {
            throw new BusinessException(ErrorCode.OPENBANKING_NOT_CONFIGURED, "client-id/client-secret 없음");
        }
    }

    private static String randomState() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) sb.append(STATE_CHARS.charAt(RANDOM.nextInt(STATE_CHARS.length())));
        return sb.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    /** url 은 은행으로 보낼 주소, state 는 브라우저 쿠키에 담을 원문. */
    public record Authorization(String url, String state) {
    }

    /** totalBalance 가 null 이면 잔액을 조회하지 않은 것이다. failedCount 는 잔액을 못 가져온 계좌 수. */
    public record Summary(int accountCount, Long totalBalance, int failedCount) {
    }

    private record Session(String accessToken, List<Account> accounts) {
    }

    public record AccountBalance(Account account, Long balance) {
    }

    /** 한 계좌의 거래내역 한 페이지. balance 는 조회 시점의 잔액이다. */
    public record AccountHistory(Account account, long balance, List<Transaction> transactions, int page, boolean hasNext, int totalCount) {

        public int totalPages() {
            return Math.max(1, (totalCount + PAGE_SIZE - 1) / PAGE_SIZE);
        }
    }

    public record TransactionRow(String accountLabel, Transaction tx) {
    }

    /** 여러 계좌의 거래 모음. truncated 는 더 있는데 잘렸다는 뜻이고 failedAccounts 는 조회하지 못한 계좌 수다. */
    public record Transactions(List<TransactionRow> rows, boolean truncated, int failedAccounts) {
    }

    /** 출금이체에 필요한 값. accessToken 원문이 들어 있어 로그에 남기지 않는다. */
    public record TransferSource(String accessToken, Account account) {
        @Override
        public String toString() {
            return "TransferSource[account=" + account.maskedNumber() + "]";
        }
    }
}
