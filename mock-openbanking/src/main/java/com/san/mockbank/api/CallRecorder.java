package com.san.mockbank.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** API 호출 기록. (이용기관, 거래고유번호)가 유일해서 같은 번호의 재사용을 막는 근거가 된다. */
@Component
public class CallRecorder {

    private final ApiCallLogRepository callLogs;

    public CallRecorder(ApiCallLogRepository callLogs) {
        this.callLogs = callLogs;
    }

    public boolean isUsed(Long clientPk, String bankTranId) {
        return callLogs.existsByClientPkAndBankTranId(clientPk, bankTranId);
    }

    public void record(Long clientPk, String api, String bankTranId, Long customerId, RspCode code) {
        try {
            callLogs.saveAndFlush(new ApiCallLog(clientPk, api, bankTranId, customerId, code.code()));
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 거래고유번호가 들어온 경우: 나중에 들어온 쪽이 중복이다
            throw new ApiException(RspCode.DUPLICATE_TRAN_ID, "동시 요청으로 중복 감지");
        }
    }
}
