package com.san.bastionpay.transfer;

import com.san.bastionpay.audit.AccountRefs;
import com.san.bastionpay.member.TierService;
import com.san.bastionpay.audit.EventOutcome;
import com.san.bastionpay.audit.SecurityEvent;
import com.san.bastionpay.audit.SecurityEventRecorder;
import com.san.bastionpay.audit.SecurityEventType;
import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import com.san.bastionpay.openbanking.OpenBankingClient;
import com.san.bastionpay.openbanking.OpenBankingClient.Receiver;
import com.san.bastionpay.openbanking.OpenBankingService;
import com.san.bastionpay.openbanking.TransferUnknownException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 내 계좌에서 다른 계좌로 이체한다. 은행에는 두 번 요청한다.
 * <ol>
 *   <li>출금이체: 내 계좌 → BastionPay 약정계좌</li>
 *   <li>입금이체: 약정계좌 → 받는 계좌</li>
 * </ol>
 * 두 요청은 서로 다른 시스템에서 일어나서 하나의 DB 트랜잭션으로 묶을 수 없다. 그래서
 * <ul>
 *   <li>요청 id(멱등키)로 같은 요청이 두 번 실행되지 않게 하고,</li>
 *   <li>단계마다 상태를 DB 에 먼저 기록하고 은행을 호출한다(외부 호출 중에는 DB 트랜잭션을 잡고 있지 않는다),</li>
 *   <li>입금이 확실히 거절되면 출금액을 내 계좌로 돌려주고(환불),</li>
 *   <li>결과를 알 수 없는 경우(시간 초과 등)는 돈이 움직였을 수 있으므로 재시도·환불을 하지 않고 사람이 확인하도록 남긴다.</li>
 * </ul>
 */
@Slf4j
@Service
public class TransferService {

    /** 금액의 절대 상한(은행 API 의 12자리). 등급별 한도는 TierProperties 가 정한다. */
    public static final long MAX_AMOUNT = 999_999_999_999L;
    private static final Pattern REQUEST_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern BANK_CODE = Pattern.compile("^\\d{3}$");
    private static final Pattern ACCOUNT_NUM = Pattern.compile("^\\d{8,20}$");
    private static final String WITHDRAW_CONTENT = "BastionPay 이체";
    private static final String REFUND_CONTENT = "BastionPay 이체 취소";

    private final OpenBankingService openBanking;
    private final OpenBankingClient client;
    private final TransferRequestRepository requests;
    private final SecurityEventRecorder events;
    private final TierService tiers;

    public TransferService(OpenBankingService openBanking, OpenBankingClient client, TransferRequestRepository requests,
                           SecurityEventRecorder events, TierService tiers) {
        this.events = events;
        this.tiers = tiers;
        this.openBanking = openBanking;
        this.client = client;
        this.requests = requests;
    }

    /**
     * 이체를 실행한다. 은행이 확실히 거절한 경우(잔액 부족, 허용되지 않은 계좌 등)는 BusinessException 을 던지고,
     * 그 밖의 결과(완료, 환불, 확인 필요, 이미 접수된 요청)는 Outcome 으로 돌려준다.
     *
     * @param toAccountNum 받는 계좌번호(숫자만)
     */
    public Outcome transfer(long memberId, String requestId, String fromFintechUseNum, String toBankCode, String toAccountNum,
                            long amount, String memo) {
        validate(requestId, toBankCode, toAccountNum, amount);

        // 같은 요청 id 가 이미 있으면 은행을 다시 호출하지 않고 기존 결과를 돌려준다
        TransferRequest existing = requests.findByMemberIdAndRequestId(memberId, requestId).orElse(null);
        if (existing != null) return replay(existing);
        checkLimit(memberId, amount);   // 이미 접수한 요청의 재전송은 위에서 끝나므로, 등급이 바뀌어도 접수된 이체의 결과 조회는 막히지 않는다

        OpenBankingService.TransferSource source = openBanking.transferSource(memberId, fromFintechUseNum);
        String label = source.account().bankName() + " " + source.account().alias();
        TransferRequest request;
        try {
            request = requests.saveAndFlush(new TransferRequest(memberId, requestId, fromFintechUseNum, label, toBankCode,
                    mask(toAccountNum), amount, blankToNull(memo)));
        } catch (DataIntegrityViolationException e) {
            // 같은 요청이 동시에 두 번 들어온 경우: 먼저 들어온 쪽이 처리 중이다
            return replay(requests.findByMemberIdAndRequestId(memberId, requestId).orElseThrow(() -> e));
        }
        record(SecurityEventType.TRANSFER_REQUESTED, EventOutcome.SUCCESS, request, null);

        // 1. 출금이체
        String withdrawTranId = client.newBankTranId();
        request.startWithdraw(withdrawTranId);
        requests.save(request);
        try {
            client.withdraw(source.accessToken(), withdrawTranId, fromFintechUseNum, amount, WITHDRAW_CONTENT);
        } catch (TransferUnknownException e) {
            return needsReview(request, "WITHDRAW_UNKNOWN", e);
        } catch (BusinessException e) {
            request.failed(e.getErrorCode().code());
            requests.save(request);
            record(SecurityEventType.TRANSFER_FAILED, EventOutcome.FAILURE, request, e.getErrorCode().code());
            throw e;
        }
        request.withdrawn();
        requests.save(request);

        // 2. 입금이체. 이용기관 토큰을 얻지 못하면 입금도 환불도 할 수 없으므로 사람이 확인해야 한다
        String orgToken;
        try {
            orgToken = client.clientToken();
        } catch (BusinessException e) {
            return needsReview(request, "ORG_TOKEN_FAILED", e);
        }
        String depositTranId = client.newBankTranId();
        request.startDeposit(depositTranId);
        requests.save(request);
        try {
            var result = client.deposit(orgToken, depositTranId, new Receiver.ByAccount(toBankCode, toAccountNum), amount, depositContent(memo));
            request.completed(result.holderName());
            requests.save(request);
            record(SecurityEventType.TRANSFER_COMPLETED, EventOutcome.SUCCESS, request, null);
            promoteIfEarned(memberId);
            return Outcome.of(request, null);
        } catch (TransferUnknownException e) {
            // 입금이 됐는지 모른다. 환불하면 이중으로 돌려줄 수 있어서 하지 않는다
            return needsReview(request, "DEPOSIT_UNKNOWN", e);
        } catch (BusinessException depositFailure) {
            return refund(request, orgToken, fromFintechUseNum, depositFailure);
        }
    }

    /** 입금이 확실히 거절되었으므로 출금액을 내 계좌로 돌려준다. 돌려주지 못하면 사람이 확인해야 한다. */
    private Outcome refund(TransferRequest request, String orgToken, String fromFintechUseNum, BusinessException depositFailure) {
        String refundTranId = client.newBankTranId();
        request.startRefund(refundTranId);
        requests.save(request);
        try {
            client.deposit(orgToken, refundTranId, new Receiver.ByFintechUseNum(fromFintechUseNum), request.getAmount(), REFUND_CONTENT);
        } catch (BusinessException refundFailure) {
            record(SecurityEventType.TRANSFER_REFUND_FAILED, EventOutcome.REVIEW, request, refundFailure.getErrorCode().code());
            request.needsReview("REFUND_FAILED");
            requests.save(request);
            return Outcome.of(request, depositFailure.getErrorCode().message());
        }
        request.refunded(depositFailure.getErrorCode().code());
        requests.save(request);
        record(SecurityEventType.TRANSFER_REFUNDED, EventOutcome.FAILURE, request, depositFailure.getErrorCode().code());
        return Outcome.of(request, reasonForReceiver(depositFailure.getErrorCode()));
    }

    private Outcome needsReview(TransferRequest request, String code, BusinessException cause) {
        request.needsReview(code);
        requests.save(request);
        record(SecurityEventType.TRANSFER_NEEDS_REVIEW, EventOutcome.REVIEW, request, code);
        return Outcome.of(request, null);
    }

    /** 이체 이벤트. 계좌번호 원문 대신 핀테크이용번호에서 만든 식별자만 남기고, 은행 응답의 상세 메시지는 남기지 않는다. */
    private void record(SecurityEventType type, EventOutcome outcome, TransferRequest request, String reasonCode) {
        events.record(SecurityEvent.of(type, outcome)
                .memberId(request.getMemberId())
                .transferId(request.getId())
                .accountRef(AccountRefs.fromFintechUseNum(request.getFromFintechUseNum()))
                .amountKrw(request.getAmount())
                .reasonCode(reasonCode));
    }

    /** 이미 접수된 요청의 현재 결과. 은행은 다시 호출하지 않는다. */
    private Outcome replay(TransferRequest request) {
        record(SecurityEventType.TRANSFER_REPLAY, EventOutcome.DENIED, request, "DUPLICATE_REQUEST");
        if (request.getStatus() == TransferStatus.FAILED) {
            throw new BusinessException(ErrorCode.DUPLICATE_TRANSFER, "이미 거절된 요청 재전송 transferId=" + request.getId());
        }
        return Outcome.of(request, null).asReplay();
    }

    private static void validate(String requestId, String toBankCode, String toAccountNum, long amount) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) throw new BusinessException(ErrorCode.INVALID_INPUT, "requestId 형식");
        if (toBankCode == null || !BANK_CODE.matcher(toBankCode).matches()) throw new BusinessException(ErrorCode.INVALID_INPUT, "받는 은행 코드");
        if (toAccountNum == null || !ACCOUNT_NUM.matcher(toAccountNum).matches()) throw new BusinessException(ErrorCode.INVALID_INPUT, "받는 계좌번호");
        if (amount < 1) throw new BusinessException(ErrorCode.INVALID_INPUT, "금액");
        if (amount > MAX_AMOUNT) throw new BusinessException(ErrorCode.TRANSFER_LIMIT_EXCEEDED, "금액 상한 초과 " + amount);
    }

    /** 등급별 1회 이체 한도. 토큰이 아니라 DB 의 등급을 본다. */
    private void checkLimit(long memberId, long amount) {
        var limit = tiers.limitOf(memberId);
        if (limit.isPresent() && amount > limit.getAsLong()) {
            throw new BusinessException(ErrorCode.TRANSFER_LIMIT_EXCEEDED, "등급 한도 초과 " + amount + " > " + limit.getAsLong());
        }
    }

    /** 완료된 이체가 쌓여 승급 기준을 넘었으면 등급을 올린다. 승급 처리가 실패해도 완료된 이체는 그대로다. */
    private void promoteIfEarned(long memberId) {
        try {
            tiers.onTransferCompleted(memberId);
        } catch (RuntimeException e) {
            log.error("등급 승급 확인 실패 memberId={}", memberId, e);
        }
    }

    private static String reasonForReceiver(ErrorCode code) {
        return code == ErrorCode.ACCOUNT_NOT_FOUND ? "받는 계좌를 찾을 수 없습니다." : code.message();
    }

    private static String depositContent(String memo) {
        return memo == null || memo.isBlank() ? WITHDRAW_CONTENT : memo;
    }

    /** 앞 3자리와 뒤 4자리만 남긴다. */
    static String mask(String accountNum) {
        return accountNum.length() <= 7 ? "***" : accountNum.substring(0, 3) + "-***-" + accountNum.substring(accountNum.length() - 4);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /** message 는 사용자에게 보여 줄 보충 설명(환불 사유 등)이다. replayed 는 이미 접수되어 있던 요청이라는 뜻이다. */
    public record Outcome(TransferStatus status, String requestId, long amount, String fromLabel, String toBankCode,
                          String toAccountMasked, String toHolderMasked, String message, boolean replayed, String fromFintechUseNum) {

        static Outcome of(TransferRequest r, String message) {
            return new Outcome(r.getStatus(), r.getRequestId(), r.getAmount(), r.getFromLabel(), r.getToBankCode(),
                    r.getToAccountMasked(), r.getToHolderMasked(), message, false, r.getFromFintechUseNum());
        }

        Outcome asReplay() {
            return new Outcome(status, requestId, amount, fromLabel, toBankCode, toAccountMasked, toHolderMasked, message, true, fromFintechUseNum);
        }
    }
}
