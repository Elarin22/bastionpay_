package com.san.bastionpay.openbanking;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 오픈뱅킹 API 호출. 서비스는 이 인터페이스에만 의존해 테스트에서 대체할 수 있다. */
public interface OpenBankingClient {

    /** 인증 코드를 access token 으로 교환한다. */
    Token exchangeCode(String code, String redirectUri);

    /** 사용자에게 등록된 계좌 목록. */
    List<Account> accounts(String accessToken, String userSeqNo);

    /** 핀테크이용번호로 잔액 조회. */
    Balance balance(String accessToken, String fintechUseNum);

    /**
     * 거래내역 한 페이지. inquiryType 은 A(전체)·I(입금)·O(출금), page 는 1부터 시작한다.
     * 기간은 from~to 양끝을 포함한다.
     */
    TransactionPage transactions(String accessToken, String fintechUseNum, LocalDate from, LocalDate to,
                                 String inquiryType, int page, int pageSize);

    /** 요청마다 새로 만드는 거래고유번호(이용기관코드 + 'U' + 9자리). 이체는 호출 전에 저장해 두려고 따로 만든다. */
    String newBankTranId();

    /** 이용기관 토큰(client_credentials, scope oob). 입금이체에 쓴다. */
    String clientToken();

    /**
     * 출금이체: 사용자 계좌에서 이용기관 약정계좌로. 은행이 거절하면 BusinessException(구체적 ErrorCode),
     * 결과를 알 수 없으면(시간 초과·서버 오류) TransferUnknownException 을 던진다.
     */
    WithdrawResult withdraw(String accessToken, String bankTranId, String fintechUseNum, long amount, String printContent);

    /** 입금이체(한 건): 약정계좌에서 수취 계좌로. 실패 처리는 withdraw 와 같다. */
    DepositResult deposit(String orgToken, String bankTranId, Receiver receiver, long amount, String printContent);

    /** 수취 계좌를 가리키는 방법: 핀테크이용번호(환불에 쓴다) 또는 은행 코드와 계좌번호. */
    sealed interface Receiver {
        record ByFintechUseNum(String fintechUseNum) implements Receiver {
        }

        record ByAccount(String bankCodeStd, String accountNum) implements Receiver {
            @Override
            public String toString() {
                return "ByAccount[bankCodeStd=" + bankCodeStd + "]";
            }
        }
    }

    record WithdrawResult(long balanceAfter) {
    }

    /** holderName 은 은행이 마스킹해서 돌려준 수취인 이름이다. */
    record DepositResult(String holderName) {
    }

    /** toString 에 토큰이 찍히지 않게 한다. */
    record Token(String accessToken, long expiresInSeconds, String scope, String userSeqNo) {
        @Override
        public String toString() {
            return "Token[scope=" + scope + ", expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    /** transferAgree 는 이 계좌에 대해 이체를 허용했는지(출금이체 가능 여부)이다. */
    record Account(String fintechUseNum, String alias, String bankName, String maskedNumber, String holderName, boolean transferAgree) {
        public Account(String fintechUseNum, String alias, String bankName, String maskedNumber, String holderName) {
            this(fintechUseNum, alias, bankName, maskedNumber, holderName, false);
        }
    }

    record Transaction(LocalDateTime at, boolean deposit, String type, String content, long amount, long balanceAfter) {
    }

    /** balanceAmt 는 조회 시점의 계좌 잔액이다. */
    record TransactionPage(List<Transaction> items, int totalCount, boolean hasNext, long balanceAmt) {
    }

    record Balance(String bankName, long balanceAmt, long availableAmt) {
    }
}
