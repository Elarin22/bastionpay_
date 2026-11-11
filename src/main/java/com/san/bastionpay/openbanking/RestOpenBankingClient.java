package com.san.bastionpay.openbanking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import com.san.bastionpay.core.util.LogSanitizer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 오픈뱅킹 API 를 호출한다: 토큰 발급, 등록계좌조회, 잔액조회, 거래내역조회, 출금·입금이체.
 * 응답 본문과 요청 본문(client_secret 포함)은 로그에 남기지 않는다.
 */
@Component
public class RestOpenBankingClient implements OpenBankingClient {

    private static final String SUCCESS = "A0000";
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final DateTimeFormatter TRAN_DTIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_TIME = TRAN_DTIME;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OpenBankingProperties props;
    private final RestClient http;

    public RestOpenBankingClient(OpenBankingProperties props, RestClient openBankingRestClient) {
        this.props = props;
        this.http = openBankingRestClient;
    }

    @Override
    public Token exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        TokenResponse r = call("token", () -> http.post().uri("/oauth/2.0/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(TokenResponse.class));
        if (r == null || isBlank(r.accessToken()) || isBlank(r.userSeqNo())) {
            // 토큰 오류는 rsp_code 없이 error 필드로 내려오기도 한다
            throw new BusinessException(ErrorCode.OPENBANKING_AUTH_FAILED,
                    "token 응답에 access_token 없음: " + describe(r == null ? null : r.error(), r == null ? null : r.rspCode()));
        }
        return new Token(r.accessToken(), r.expiresIn() == null ? 0 : r.expiresIn(), r.scope(), r.userSeqNo());
    }

    @Override
    public List<Account> accounts(String accessToken, String userSeqNo) {
        AccountListResponse r = call("account/list", () -> http.get()
                .uri(u -> u.path("/v2.0/account/list")
                        .queryParam("user_seq_no", userSeqNo)
                        .queryParam("include_cancel_yn", "N")
                        .queryParam("sort_order", "D").build())
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve().body(AccountListResponse.class));
        requireSuccess("account/list", r == null ? null : r.rspCode(), r == null ? null : r.rspMessage());
        if (r.resList() == null) return List.of();
        return r.resList().stream()
                .map(a -> new Account(a.fintechUseNum(), a.accountAlias(), a.bankName(), a.accountNumMasked(), a.accountHolderName(),
                        "Y".equals(a.transferAgreeYn())))
                .toList();
    }

    @Override
    public TransactionPage transactions(String accessToken, String fintechUseNum, LocalDate from, LocalDate to,
                                        String inquiryType, int page, int pageSize) {
        String bankTranId = newBankTranId();
        String tranDtime = LocalDateTime.now(KST).format(TRAN_DTIME);
        TransactionListResponse r = call("account/transaction_list", () -> http.get()
                .uri(u -> u.path("/v2.0/account/transaction_list/fin_num")
                        .queryParam("bank_tran_id", bankTranId)
                        .queryParam("fintech_use_num", fintechUseNum)
                        .queryParam("tran_dtime", tranDtime)
                        .queryParam("inquiry_type", inquiryType)
                        .queryParam("from_date", from.format(DATE))
                        .queryParam("to_date", to.format(DATE))
                        .queryParam("sort_order", "D")
                        .queryParam("page", page)
                        .queryParam("page_size", pageSize).build())
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve().body(TransactionListResponse.class));
        requireSuccess("account/transaction_list", r == null ? null : r.rspCode(), r == null ? null : r.rspMessage());
        List<Transaction> items = r.resList() == null ? List.of() : r.resList().stream().map(t -> new Transaction(
                parseDateTime(t.tranDate(), t.tranTime()), "입금".equals(t.inoutType()), t.tranType(), t.printContent(),
                toLong(t.tranAmt()), toLong(t.afterBalanceAmt()))).toList();
        return new TransactionPage(items, (int) toLong(r.totalCnt()), Boolean.TRUE.equals(r.hasNext()), toLong(r.balanceAmt()));
    }

    @Override
    public String clientToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("scope", "oob");
        TokenResponse r = transferCall("token(client_credentials)", () -> http.post().uri("/oauth/2.0/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(TokenResponse.class));
        if (r == null || isBlank(r.accessToken())) {
            throw new BusinessException(ErrorCode.OPENBANKING_AUTH_FAILED, "이용기관 토큰 발급 실패: " + describe(r == null ? null : r.error(), null));
        }
        return r.accessToken();
    }

    @Override
    public WithdrawResult withdraw(String accessToken, String bankTranId, String fintechUseNum, long amount, String printContent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bank_tran_id", bankTranId);
        body.put("tran_dtime", LocalDateTime.now(KST).format(TRAN_DTIME));
        body.put("fintech_use_num", fintechUseNum);
        body.put("tran_amt", String.valueOf(amount));
        if (printContent != null) body.put("print_content", printContent);
        WithdrawResponse r = transferCall("transfer/withdraw", () -> http.post().uri("/v2.0/transfer/withdraw/fin_num")
                .contentType(MediaType.APPLICATION_JSON).headers(h -> h.setBearerAuth(accessToken)).body(body)
                .retrieve().body(WithdrawResponse.class));
        if (r == null) throw new TransferUnknownException("transfer/withdraw 응답 없음");
        requireTransferSuccess("transfer/withdraw", r.rspCode(), r.rspMessage());
        return new WithdrawResult(toLong(r.balanceAmt()));
    }

    @Override
    public DepositResult deposit(String orgToken, String bankTranId, Receiver receiver, long amount, String printContent) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tran_no", 1);
        String path;
        if (receiver instanceof Receiver.ByAccount a) {
            path = "/v2.0/transfer/deposit/acnt_num";
            item.put("bank_code_std", a.bankCodeStd());
            item.put("account_num", a.accountNum());
        } else {
            path = "/v2.0/transfer/deposit/fin_num";
            item.put("fintech_use_num", ((Receiver.ByFintechUseNum) receiver).fintechUseNum());
        }
        if (printContent != null) item.put("print_content", printContent);
        item.put("tran_amt", String.valueOf(amount));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bank_tran_id", bankTranId);
        body.put("tran_dtime", LocalDateTime.now(KST).format(TRAN_DTIME));
        body.put("wd_pass_phrase", props.transferPassPhrase());
        body.put("wd_print_content", "BastionPay");
        body.put("req_cnt", 1);
        body.put("req_list", List.of(item));
        DepositResponse r = transferCall("transfer/deposit", () -> http.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).headers(h -> h.setBearerAuth(orgToken)).body(body)
                .retrieve().body(DepositResponse.class));
        if (r == null) throw new TransferUnknownException("transfer/deposit 응답 없음");
        if (r.resList() == null || r.resList().size() != 1) {
            // 요청 전체가 거절된 경우(암호문구·형식 오류 등)는 항목 없이 rsp_code 만 온다
            requireTransferSuccess("transfer/deposit", r.rspCode(), r.rspMessage());
            throw new TransferUnknownException("transfer/deposit 응답 형식 오류");
        }
        DepositItemResponse itemResult = r.resList().get(0);
        requireTransferSuccess("transfer/deposit", itemResult.rspCode(), itemResult.rspMessage());
        return new DepositResult(itemResult.accountHolderName());
    }

    /**
     * 이체 호출. 은행이 거절한 것이 확실한 경우(4xx)는 BusinessException, 결과를 알 수 없는 경우(연결·시간 초과, 5xx)는
     * TransferUnknownException 으로 구분한다. 요청·응답 본문(암호문구 포함)은 로그에 남기지 않는다.
     */
    private <T> T transferCall(String api, Call<T> call) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status >= 500) throw new TransferUnknownException(api + " HTTP " + status);
            if (status == 401) throw new BusinessException(ErrorCode.OPENBANKING_NOT_LINKED, api + " HTTP 401 (토큰 만료·철회)");
            if (status == 403) throw new BusinessException(ErrorCode.TRANSFER_NOT_PERMITTED, api + " HTTP 403 (권한 범위 없음)");
            throw new BusinessException(ErrorCode.OPENBANKING_API_ERROR, api + " HTTP " + status + " " + LogSanitizer.clean(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            throw new TransferUnknownException(api + " 결과 불명: " + e.getClass().getSimpleName());
        }
    }

    /** 은행이 돌려준 rsp_code 를 사용자에게 보일 수 있는 오류로 바꾼다. */
    private static void requireTransferSuccess(String api, String rspCode, String rspMessage) {
        if (SUCCESS.equals(rspCode)) return;
        String detail = api + " rsp_code=" + LogSanitizer.clean(rspCode) + " " + LogSanitizer.clean(rspMessage);
        ErrorCode code = switch (rspCode == null ? "" : rspCode) {
            case "A0002" -> ErrorCode.DUPLICATE_TRANSFER;
            case "A0004", "A0008" -> ErrorCode.TRANSFER_NOT_PERMITTED;
            case "A0005" -> ErrorCode.ACCOUNT_NOT_FOUND;
            case "A0010" -> ErrorCode.INSUFFICIENT_BALANCE;
            case "A0011" -> ErrorCode.TRANSFER_LIMIT_EXCEEDED;
            default -> ErrorCode.OPENBANKING_API_ERROR;
        };
        throw new BusinessException(code, detail);
    }

    @Override
    public Balance balance(String accessToken, String fintechUseNum) {
        String bankTranId = newBankTranId();
        String tranDtime = LocalDateTime.now(KST).format(TRAN_DTIME);
        BalanceResponse r = call("account/balance", () -> http.get()
                .uri(u -> u.path("/v2.0/account/balance/fin_num")
                        .queryParam("bank_tran_id", bankTranId)
                        .queryParam("fintech_use_num", fintechUseNum)
                        .queryParam("tran_dtime", tranDtime).build())
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve().body(BalanceResponse.class));
        requireSuccess("account/balance", r == null ? null : r.rspCode(), r == null ? null : r.rspMessage());
        return new Balance(r.bankName(), toLong(r.balanceAmt()), toLong(r.availableAmt()));
    }

    // ---- 내부 ----

    private interface Call<T> {
        T run();
    }

    /** 네트워크·HTTP 오류를 도메인 예외로 바꾼다. 상세는 서버 로그 전용이다. */
    private <T> T call(String api, Call<T> call) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            throw new BusinessException(ErrorCode.OPENBANKING_API_ERROR,
                    api + " HTTP " + e.getStatusCode().value() + " " + LogSanitizer.clean(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.OPENBANKING_API_ERROR, api + " 호출 실패: " + e.getClass().getSimpleName());
        }
    }

    private static void requireSuccess(String api, String rspCode, String rspMessage) {
        if (!SUCCESS.equals(rspCode)) {
            throw new BusinessException(ErrorCode.OPENBANKING_API_ERROR,
                    api + " rsp_code=" + LogSanitizer.clean(rspCode) + " " + LogSanitizer.clean(rspMessage));
        }
    }

    private static String describe(String error, String rspCode) {
        return "error=" + LogSanitizer.clean(error) + " rsp_code=" + LogSanitizer.clean(rspCode);
    }

    @Override
    public String newBankTranId() {
        return props.orgCode() + "U" + randomAlnum(9);
    }

    private static LocalDateTime parseDateTime(String date, String time) {
        try {
            return LocalDateTime.parse(date + time, DATE_TIME);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.OPENBANKING_API_ERROR, "거래 일시 형식 오류");
        }
    }

    private static String randomAlnum(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        return sb.toString();
    }

    private static long toLong(String amount) {
        try {
            return amount == null ? 0 : Long.parseLong(amount.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.OPENBANKING_API_ERROR, "금액 형식 오류");
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    // ---- 응답 형식 (알 수 없는 필드는 무시) ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("scope") String scope,
            @JsonProperty("user_seq_no") String userSeqNo,
            @JsonProperty("error") String error,
            @JsonProperty("rsp_code") String rspCode) {
        @Override
        public String toString() {
            return "TokenResponse[error=" + error + ", rspCode=" + rspCode + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountListResponse(
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("res_list") List<AccountItem> resList) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountItem(
            @JsonProperty("fintech_use_num") String fintechUseNum,
            @JsonProperty("account_alias") String accountAlias,
            @JsonProperty("bank_name") String bankName,
            @JsonProperty("account_num_masked") String accountNumMasked,
            @JsonProperty("account_holder_name") String accountHolderName,
            @JsonProperty("transfer_agree_yn") String transferAgreeYn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalanceResponse(
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("bank_name") String bankName,
            @JsonProperty("balance_amt") String balanceAmt,
            @JsonProperty("available_amt") String availableAmt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionListResponse(
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("balance_amt") String balanceAmt,
            @JsonProperty("total_cnt") String totalCnt,
            @JsonProperty("has_next") Boolean hasNext,
            @JsonProperty("res_list") List<TransactionItem> resList) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionItem(
            @JsonProperty("tran_date") String tranDate,
            @JsonProperty("tran_time") String tranTime,
            @JsonProperty("inout_type") String inoutType,
            @JsonProperty("tran_type") String tranType,
            @JsonProperty("print_content") String printContent,
            @JsonProperty("tran_amt") String tranAmt,
            @JsonProperty("after_balance_amt") String afterBalanceAmt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WithdrawResponse(
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("balance_amt") String balanceAmt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DepositResponse(
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("res_list") List<DepositItemResponse> resList) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DepositItemResponse(
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("account_holder_name") String accountHolderName) {
    }
}
