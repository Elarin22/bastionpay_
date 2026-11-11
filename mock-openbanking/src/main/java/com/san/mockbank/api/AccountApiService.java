package com.san.mockbank.api;

import com.san.mockbank.account.AccountConsent;
import com.san.mockbank.account.AccountConsentRepository;
import com.san.mockbank.account.AccountStatus;
import com.san.mockbank.account.BankAccount;
import com.san.mockbank.account.BankAccountRepository;
import com.san.mockbank.account.BankTransaction;
import com.san.mockbank.account.BankTransactionRepository;
import com.san.mockbank.account.InOutType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.ResolverStyle;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 계좌 조회 규칙.
 * - 이용기관이 고객에게 동의받은 계좌만 조회된다. 동의받지 않았거나 남의 계좌이거나 없는 계좌는 같은 오류로 응답해 존재 여부를 드러내지 않는다.
 * - 거래고유번호는 이용기관코드(10자리) + 'U' + 영문 대문자·숫자 9자리이고, 이용기관별로 한 번만 쓸 수 있다.
 */
@Service
public class AccountApiService {

    private static final Pattern USER_SEQ_NO = Pattern.compile("^\\d{10}$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    static final int MAX_RANGE_DAYS = 92;
    static final int MAX_PAGE_SIZE = 100;

    private final BankAccountRepository accounts;
    private final AccountConsentRepository consents;
    private final BankTransactionRepository transactions;
    private final CallRecorder recorder;

    public AccountApiService(BankAccountRepository accounts, AccountConsentRepository consents,
                             BankTransactionRepository transactions, CallRecorder recorder) {
        this.accounts = accounts;
        this.consents = consents;
        this.transactions = transactions;
        this.recorder = recorder;
    }

    /** 이 이용기관에 조회를 허용한 계좌만. include_cancel_yn=Y 이면 해지 계좌도 포함한다. */
    @Transactional(readOnly = true)
    public List<ConsentedAccount> consentedAccounts(ApiPrincipal principal, String userSeqNo, String includeCancelYn, String sortOrder) {
        var customer = principal.requireCustomer();
        principal.requireAnyScope("login", "inquiry");
        if (userSeqNo == null || !USER_SEQ_NO.matcher(userSeqNo).matches()) throw new ApiException(RspCode.INVALID_PARAMETER, "user_seq_no 형식");
        if (!"Y".equals(includeCancelYn) && !"N".equals(includeCancelYn)) throw new ApiException(RspCode.INVALID_PARAMETER, "include_cancel_yn");
        if (!"D".equals(sortOrder) && !"A".equals(sortOrder)) throw new ApiException(RspCode.INVALID_PARAMETER, "sort_order");
        if (!customer.getUserSeqNo().equals(userSeqNo)) {
            throw new ApiException(RspCode.USER_MISMATCH, "토큰의 사용자와 다른 user_seq_no 요청");
        }
        boolean includeCancel = "Y".equals(includeCancelYn);
        List<ConsentedAccount> result = accounts.findByCustomerIdOrderByIdAsc(customer.getId()).stream()
                .filter(a -> includeCancel || a.getStatus() == AccountStatus.ACTIVE)
                .map(a -> consents.findByClientPkAndAccountId(principal.client().getId(), a.getId())
                        .filter(AccountConsent::canInquire).map(c -> new ConsentedAccount(a, c)).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        return "D".equals(sortOrder) ? result.reversed() : result;
    }

    /** 이용기관이 동의받은 계좌와 동의 범위. */
    public record ConsentedAccount(BankAccount account, AccountConsent consent) {
    }

    /** 잔액조회. 형식이 맞는 요청은 성공·실패를 api_call_log 에 남기고, 같은 거래고유번호의 재사용을 막는다. */
    @Transactional(noRollbackFor = ApiException.class)
    public BankAccount balance(ApiPrincipal principal, String bankTranId, String fintechUseNum, String tranDtime) {
        principal.requireCustomer();
        principal.requireAnyScope("inquiry");
        return inquire(principal, "balance", bankTranId, fintechUseNum, tranDtime, account -> account);
    }

    /**
     * 거래내역조회. 기간은 from_date~to_date(양끝 포함, 최대 {@value #MAX_RANGE_DAYS}일), 입출금 구분은 A(전체)·I(입금)·O(출금),
     * 정렬은 D(최신순)·A(오래된순). 페이지는 1부터 시작한다.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TransactionPage transactions(ApiPrincipal principal, String bankTranId, String fintechUseNum, String tranDtime,
                                        String inquiryType, String fromDate, String toDate, String sortOrder, int page, int pageSize) {
        principal.requireCustomer();
        principal.requireAnyScope("inquiry");
        // 형식이 틀린 요청은 거래고유번호를 소모하지 않도록 기록 전에 모두 검증한다
        Set<InOutType> types = switch (inquiryType == null ? "" : inquiryType) {
            case "A" -> Set.of(InOutType.IN, InOutType.OUT);
            case "I" -> Set.of(InOutType.IN);
            case "O" -> Set.of(InOutType.OUT);
            default -> throw new ApiException(RspCode.INVALID_PARAMETER, "inquiry_type");
        };
        if (!"D".equals(sortOrder) && !"A".equals(sortOrder)) throw new ApiException(RspCode.INVALID_PARAMETER, "sort_order");
        LocalDate from = parseDate(fromDate, "from_date");
        LocalDate to = parseDate(toDate, "to_date");
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new ApiException(RspCode.INVALID_PARAMETER, "조회 기간이 올바르지 않음");
        }
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) throw new ApiException(RspCode.INVALID_PARAMETER, "page/page_size");

        Sort.Direction direction = "D".equals(sortOrder) ? Sort.Direction.DESC : Sort.Direction.ASC;
        PageRequest pageable = PageRequest.of(page - 1, pageSize, Sort.by(direction, "tranAt").and(Sort.by(direction, "id")));
        return inquire(principal, "transaction_list", bankTranId, fintechUseNum, tranDtime, account -> new TransactionPage(account,
                transactions.search(account.getId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay(), types, pageable)));
    }

    /** 거래내역 한 페이지와 그 계좌. */
    public record TransactionPage(BankAccount account, Page<BankTransaction> page) {
    }

    /**
     * 잔액·거래내역 공통 절차: 거래고유번호·핀테크이용번호·조회 시각 검증 → 재사용 확인 → 동의받은 본인 계좌 확인 → 처리 → 호출 기록.
     */
    private <T> T inquire(ApiPrincipal principal, String api, String bankTranId, String fintechUseNum, String tranDtime,
                          Function<BankAccount, T> action) {
        var customer = principal.requireCustomer();
        ApiRules.tranId(principal, bankTranId);
        ApiRules.fintechUseNum(fintechUseNum);
        ApiRules.tranDtime(tranDtime);

        Long clientPk = principal.client().getId();
        if (recorder.isUsed(clientPk, bankTranId)) {
            throw new ApiException(RspCode.DUPLICATE_TRAN_ID, "재사용된 거래고유번호");
        }
        try {
            BankAccount account = accounts.findByFintechUseNum(fintechUseNum)
                    .filter(a -> a.getCustomerId().equals(customer.getId()))
                    .filter(a -> a.getStatus() == AccountStatus.ACTIVE)
                    .filter(a -> consents.findByClientPkAndAccountId(clientPk, a.getId()).filter(AccountConsent::canInquire).isPresent())
                    .orElseThrow(() -> new ApiException(RspCode.ACCOUNT_NOT_AVAILABLE, "없거나 남의 계좌이거나 동의받지 않은 계좌"));
            T result = action.apply(account);
            recorder.record(clientPk, api, bankTranId, customer.getId(), RspCode.SUCCESS);
            return result;
        } catch (ApiException e) {
            recorder.record(clientPk, api, bankTranId, customer.getId(), e.getRspCode());
            throw e;
        }
    }

    private static LocalDate parseDate(String value, String name) {
        try {
            if (value == null || value.length() != 8) throw new DateTimeParseException("len", "", 0);
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            throw new ApiException(RspCode.INVALID_PARAMETER, name + " 형식");
        }
    }
}
