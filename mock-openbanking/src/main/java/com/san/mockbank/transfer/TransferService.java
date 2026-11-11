package com.san.mockbank.transfer;

import com.san.mockbank.account.AccountConsent;
import com.san.mockbank.account.AccountConsentRepository;
import com.san.mockbank.account.BankAccount;
import com.san.mockbank.account.BankAccountRepository;
import com.san.mockbank.account.BankTransaction;
import com.san.mockbank.account.BankTransactionRepository;
import com.san.mockbank.account.InOutType;
import com.san.mockbank.api.ApiException;
import com.san.mockbank.api.ApiPrincipal;
import com.san.mockbank.api.ApiRules;
import com.san.mockbank.api.CallRecorder;
import com.san.mockbank.api.RspCode;
import com.san.mockbank.core.MockbankProperties;
import com.san.mockbank.customer.BankCustomer;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 이체 처리. 돈이 움직이는 부분이라 규칙을 엄격하게 둔다.
 * <ul>
 *   <li>한 요청은 한 DB 트랜잭션이다. 이체에 쓰는 계좌는 항상 id 순서대로 행 잠금(FOR UPDATE)을 걸어
 *       같은 계좌에서 동시에 출금해도 잔액이 음수가 되거나 이중으로 나가지 않고 교착도 생기지 않는다.</li>
 *   <li>검증은 잔액을 바꾸기 전에 모두 끝낸다. 실패한 이체는 잔액과 거래내역에 흔적을 남기지 않는다.</li>
 *   <li>같은 거래고유번호는 한 번만 처리한다(이용기관별). 동시에 들어온 같은 번호는 DB 유일 제약이 막는다.</li>
 *   <li>한도(이체 한 건 최대 금액)와 잔액은 서버가 정한다. 요청 값을 믿지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
public class TransferService {

    private static final int MAX_ITEMS = 100;

    private final BankAccountRepository accounts;
    private final AccountConsentRepository consents;
    private final BankTransactionRepository transactions;
    private final BankTransferRepository transfers;
    private final BankTransferItemRepository items;
    private final CallRecorder recorder;
    private final PasswordEncoder encoder;
    private final MockbankProperties props;
    private final EntityManager entityManager;

    public TransferService(BankAccountRepository accounts, AccountConsentRepository consents,
                           BankTransactionRepository transactions, BankTransferRepository transfers,
                           BankTransferItemRepository items, CallRecorder recorder, PasswordEncoder encoder,
                           MockbankProperties props, EntityManager entityManager) {
        this.accounts = accounts;
        this.consents = consents;
        this.transactions = transactions;
        this.transfers = transfers;
        this.items = items;
        this.recorder = recorder;
        this.encoder = encoder;
        this.props = props;
        this.entityManager = entityManager;
    }

    /** 출금이체: 고객 계좌 → 이용기관 약정계좌. 고객 토큰(scope transfer)과 그 계좌의 이체 동의가 필요하다. */
    @Transactional(noRollbackFor = ApiException.class)
    public WithdrawResult withdraw(ApiPrincipal principal, WithdrawCommand cmd) {
        BankCustomer customer = principal.requireCustomer();
        principal.requireAnyScope("transfer");
        // 형식 검사는 기록 전에 끝낸다(틀린 요청이 거래고유번호를 소모하지 않게)
        ApiRules.tranId(principal, cmd.bankTranId());
        ApiRules.tranDtime(cmd.tranDtime());
        ApiRules.fintechUseNum(cmd.fintechUseNum());
        long amount = ApiRules.amount(cmd.tranAmt());
        String content = ApiRules.text(cmd.printContent(), 20, "print_content");

        Long clientPk = principal.client().getId();
        if (recorder.isUsed(clientPk, cmd.bankTranId())) throw new ApiException(RspCode.DUPLICATE_TRAN_ID, "재사용된 거래고유번호");
        BankTransfer transfer = null;
        try {
            Long escrowId = principal.client().getEscrowAccountId();
            if (escrowId == null) throw new ApiException(RspCode.ACCOUNT_NOT_AVAILABLE, "약정계좌 없음");

            // 먼저 잠그지 않고 대상이 내 계좌인지 확인한다. 남의 계좌·없는 계좌·동의 없는 계좌는 같은 오류로 응답한다
            BankAccount target = accounts.findByFintechUseNum(cmd.fintechUseNum())
                    .filter(a -> a.getCustomerId().equals(customer.getId())).filter(BankAccount::isActive)
                    .orElseThrow(() -> new ApiException(RspCode.ACCOUNT_NOT_AVAILABLE, "없거나 남의 계좌"));
            AccountConsent consent = consents.findByClientPkAndAccountId(clientPk, target.getId()).filter(AccountConsent::canInquire)
                    .orElseThrow(() -> new ApiException(RspCode.ACCOUNT_NOT_AVAILABLE, "동의받지 않은 계좌"));
            if (!consent.canTransfer()) throw new ApiException(RspCode.TRANSFER_NOT_PERMITTED, "이체 동의 없음");
            if (target.getId().equals(escrowId)) throw new ApiException(RspCode.ACCOUNT_NOT_AVAILABLE, "약정계좌와 같은 계좌");
            if (amount > props.transferMaxAmount()) throw new ApiException(RspCode.LIMIT_EXCEEDED, "한도 초과 " + amount);
            // 잠금 전에 읽은 사본은 버린다. 남겨 두면 잠금 조회가 가져온 최신 행과 버전이 달라 충돌로 처리된다
            entityManager.detach(target);

            // 같은 요청이 동시에 두 번 들어오면 여기서 유일 제약이 한쪽을 막는다(트랜잭션 전체 롤백)
            transfer = transfers.saveAndFlush(new BankTransfer(clientPk, cmd.bankTranId(), TransferKind.WITHDRAW,
                    customer.getId(), 1, amount, TransferStatus.FAILED, RspCode.TRANSFER_FAILED.code()));

            Map<Long, BankAccount> locked = lock(List.of(target.getId(), escrowId));
            BankAccount from = locked.get(target.getId());
            BankAccount escrow = locked.get(escrowId);
            if (escrow == null || !escrow.isActive() || !from.isActive()) throw new ApiException(RspCode.ACCOUNT_NOT_AVAILABLE, "계좌 상태");
            // 잠근 뒤의 잔액으로 다시 확인한다(그 사이 다른 요청이 출금했을 수 있다)
            if (from.getBalanceAmt() < amount) throw new ApiException(RspCode.INSUFFICIENT_BALANCE, "잔액 " + from.getBalanceAmt() + " < " + amount);

            from.withdraw(amount);
            escrow.deposit(amount);
            BankTransferItem item = items.save(new BankTransferItem(transfer.getId(), 1, from.getId(), escrow.getId(), amount, content,
                    BankTransferItem.ItemStatus.COMPLETED, RspCode.SUCCESS.code()));
            LocalDateTime now = LocalDateTime.now();
            transactions.save(new BankTransaction(from.getId(), now, InOutType.OUT, "출금이체",
                    content != null ? content : principal.client().getName(), amount, from.getBalanceAmt(), null, cmd.bankTranId(), item.getId()));
            transactions.save(new BankTransaction(escrow.getId(), now, InOutType.IN, "출금이체",
                    maskName(customer.getName()), amount, escrow.getBalanceAmt(), null, cmd.bankTranId(), item.getId()));
            transfer.complete(TransferStatus.COMPLETED, RspCode.SUCCESS.code());
            recorder.record(clientPk, "withdraw", cmd.bankTranId(), customer.getId(), RspCode.SUCCESS);
            log.info("transfer withdraw completed transferId={} amount={}", transfer.getId(), amount);
            return new WithdrawResult(cmd.bankTranId(), from.getFintechUseNum(), amount, from.getBalanceAmt());
        } catch (ApiException e) {
            // 검증 실패는 잔액을 바꾸기 전에 던지므로 되돌릴 것이 없다. 이체 요청 기록의 결과 코드와 호출 기록만 남긴다
            if (transfer != null) transfer.complete(TransferStatus.FAILED, e.getRspCode().code());
            if (e.getRspCode() != RspCode.DUPLICATE_TRAN_ID) {
                recorder.record(clientPk, "withdraw", cmd.bankTranId(), customer.getId(), e.getRspCode());
            }
            throw e;
        }
    }

    /**
     * 입금이체: 이용기관 약정계좌 → 수취 계좌 여러 건. 이용기관 토큰(scope oob)과 입금이체 암호문구가 필요하다.
     * 항목마다 성공·실패가 나뉠 수 있고(부분 성공), 실패한 항목은 잔액을 바꾸지 않는다.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public DepositResult deposit(ApiPrincipal principal, DepositCommand cmd, boolean byAccountNumber) {
        if (principal.customer() != null) throw new ApiException(RspCode.INSUFFICIENT_SCOPE, "사용자 토큰으로 입금이체 호출");
        principal.requireAnyScope("oob");
        ApiRules.tranId(principal, cmd.bankTranId());
        ApiRules.tranDtime(cmd.tranDtime());
        String wdContent = ApiRules.text(cmd.wdPrintContent(), 20, "wd_print_content");
        if (cmd.reqCnt() == null || cmd.items() == null || cmd.reqCnt() < 1 || cmd.reqCnt() > MAX_ITEMS || cmd.reqCnt() != cmd.items().size()) {
            throw new ApiException(RspCode.INVALID_PARAMETER, "req_cnt/req_list");
        }
        long total = 0;
        for (int i = 0; i < cmd.items().size(); i++) {
            ItemCommand item = cmd.items().get(i);
            if (item == null || item.tranNo() == null || item.tranNo() != i + 1) throw new ApiException(RspCode.INVALID_PARAMETER, "tran_no 는 1부터 차례로");
            // 핀테크이용번호로 보내는 API 와 은행 코드·계좌번호로 보내는 API 는 수취 계좌를 가리키는 방식만 다르다
            if (byAccountNumber) {
                if (item.fintechUseNum() != null) throw new ApiException(RspCode.INVALID_PARAMETER, "fintech_use_num 은 이 API 에서 쓰지 않음");
                ApiRules.bankAccount(item.bankCodeStd(), item.accountNum());
            } else {
                if (item.bankCodeStd() != null || item.accountNum() != null) throw new ApiException(RspCode.INVALID_PARAMETER, "계좌번호는 이 API 에서 쓰지 않음");
                ApiRules.fintechUseNum(item.fintechUseNum());
            }
            total += ApiRules.amount(item.tranAmt());
            ApiRules.text(item.printContent(), 20, "print_content");
        }
        String hash = principal.client().getTransferPassPhraseHash();
        if (cmd.passPhrase() == null || hash == null || !encoder.matches(cmd.passPhrase(), hash)) {
            log.warn("transfer deposit: 암호문구 불일치 clientId={}", principal.client().getClientId());
            throw new ApiException(RspCode.INVALID_PASS_PHRASE, "암호문구 불일치");
        }

        Long clientPk = principal.client().getId();
        if (recorder.isUsed(clientPk, cmd.bankTranId())) throw new ApiException(RspCode.DUPLICATE_TRAN_ID, "재사용된 거래고유번호");
        BankTransfer transfer = null;
        try {
            Long escrowId = principal.client().getEscrowAccountId();
            if (escrowId == null) throw new ApiException(RspCode.ACCOUNT_NOT_AVAILABLE, "약정계좌 없음");

            Map<Integer, BankAccount> found = new HashMap<>();
            Set<Long> ids = new LinkedHashSet<>(List.of(escrowId));
            for (ItemCommand item : cmd.items()) {
                var account = byAccountNumber
                        ? accounts.findByBankCodeStdAndAccountNum(item.bankCodeStd(), item.accountNum())
                        : accounts.findByFintechUseNum(item.fintechUseNum());
                account.ifPresent(a -> {
                    found.put(item.tranNo(), a);
                    ids.add(a.getId());
                    entityManager.detach(a);   // 잠금 전 사본은 버린다(잠금 조회의 최신 행과 버전 충돌 방지)
                });
            }
            transfer = transfers.saveAndFlush(new BankTransfer(clientPk, cmd.bankTranId(), TransferKind.DEPOSIT, null,
                    cmd.items().size(), total, TransferStatus.FAILED, RspCode.TRANSFER_FAILED.code()));

            Map<Long, BankAccount> locked = lock(ids);
            BankAccount escrow = locked.get(escrowId);
            if (escrow == null || !escrow.isActive()) throw new ApiException(RspCode.ACCOUNT_NOT_AVAILABLE, "약정계좌 상태");

            List<ItemResult> results = new ArrayList<>();
            int completed = 0;
            LocalDateTime now = LocalDateTime.now();
            for (ItemCommand item : cmd.items()) {
                long amount = Long.parseLong(item.tranAmt());
                BankAccount candidate = found.get(item.tranNo());
                BankAccount receiver = candidate == null ? null : locked.get(candidate.getId());
                RspCode code = RspCode.SUCCESS;
                if (receiver == null || !receiver.isActive() || receiver.getId().equals(escrowId)) code = RspCode.ACCOUNT_NOT_AVAILABLE;
                else if (amount > props.transferMaxAmount()) code = RspCode.LIMIT_EXCEEDED;
                else if (escrow.getBalanceAmt() < amount) code = RspCode.INSUFFICIENT_BALANCE;

                String printContent = ApiRules.text(item.printContent(), 20, "print_content");
                BankTransferItem saved = items.save(new BankTransferItem(transfer.getId(), item.tranNo(), escrow.getId(),
                        receiver == null ? null : receiver.getId(), amount, printContent,
                        code == RspCode.SUCCESS ? BankTransferItem.ItemStatus.COMPLETED : BankTransferItem.ItemStatus.FAILED, code.code()));
                if (code == RspCode.SUCCESS) {
                    escrow.withdraw(amount);
                    receiver.deposit(amount);
                    transactions.save(new BankTransaction(escrow.getId(), now, InOutType.OUT, "입금이체",
                            wdContent != null ? wdContent : maskName(receiver.getAccountHolderName()), amount, escrow.getBalanceAmt(), null,
                            cmd.bankTranId(), saved.getId()));
                    transactions.save(new BankTransaction(receiver.getId(), now, InOutType.IN, "입금이체",
                            printContent != null ? printContent : principal.client().getName(), amount, receiver.getBalanceAmt(), null,
                            cmd.bankTranId(), saved.getId()));
                    completed++;
                }
                results.add(new ItemResult(item.tranNo(), item.fintechUseNum(), item.bankCodeStd(),
                        receiver == null || code != RspCode.SUCCESS ? null : receiver.getAccountNumMasked(), amount, code,
                        code == RspCode.SUCCESS ? maskName(receiver.getAccountHolderName()) : null));
            }
            RspCode overall = completed == results.size() ? RspCode.SUCCESS : completed == 0 ? RspCode.TRANSFER_FAILED : RspCode.PARTIAL_SUCCESS;
            transfer.complete(completed == results.size() ? TransferStatus.COMPLETED : completed == 0 ? TransferStatus.FAILED : TransferStatus.PARTIAL,
                    overall.code());
            recorder.record(clientPk, "deposit", cmd.bankTranId(), null, overall);
            log.info("transfer deposit finished transferId={} completed={}/{}", transfer.getId(), completed, results.size());
            return new DepositResult(cmd.bankTranId(), overall, results);
        } catch (ApiException e) {
            if (transfer != null) transfer.complete(TransferStatus.FAILED, e.getRspCode().code());
            if (e.getRspCode() != RspCode.DUPLICATE_TRAN_ID) {
                recorder.record(clientPk, "deposit", cmd.bankTranId(), null, e.getRspCode());
            }
            throw e;
        }
    }

    /** id 순서로 잠근 계좌들. 찾지 못한 id 는 맵에 없다. */
    private Map<Long, BankAccount> lock(Collection<Long> ids) {
        Map<Long, BankAccount> map = new HashMap<>();
        for (BankAccount account : accounts.lockAllByIdIn(ids)) map.put(account.getId(), account);
        return map;
    }

    /** 홍길동 → 홍*동. 두 글자는 홍*, 한 글자는 그대로 둔다. */
    static String maskName(String name) {
        if (name == null || name.length() <= 1) return name;
        if (name.length() == 2) return name.charAt(0) + "*";
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    public record WithdrawCommand(String bankTranId, String tranDtime, String fintechUseNum, String tranAmt, String printContent) {
    }

    public record DepositCommand(String bankTranId, String tranDtime, String passPhrase, String wdPrintContent, Integer reqCnt,
                                 List<ItemCommand> items) {
        /** passPhrase 가 로그에 찍히지 않게 한다. */
        @Override
        public String toString() {
            return "DepositCommand[bankTranId=" + bankTranId + ", reqCnt=" + reqCnt + "]";
        }
    }

    public record ItemCommand(Integer tranNo, String fintechUseNum, String bankCodeStd, String accountNum, String printContent, String tranAmt) {
    }

    public record WithdrawResult(String bankTranId, String fintechUseNum, long amount, long balanceAfter) {
    }

    public record DepositResult(String bankTranId, RspCode code, List<ItemResult> items) {
    }

    /** holderName 은 성공한 항목에만 있고 마스킹되어 있다. */
    public record ItemResult(int tranNo, String fintechUseNum, String bankCodeStd, String accountNumMasked, long amount, RspCode code, String holderName) {
    }
}
