package com.san.mockbank.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.san.mockbank.transfer.TransferService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 이체 API. 출금이체는 사용자 토큰(scope transfer), 입금이체는 이용기관 토큰(scope oob)으로 호출한다.
 * 금액은 문자열 정수로 주고받고, 본문은 JSON 이다.
 */
@RestController
public class TransferController {

    private final BearerAuthenticator auth;
    private final TransferService service;

    public TransferController(BearerAuthenticator auth, TransferService service) {
        this.auth = auth;
        this.service = service;
    }

    @PostMapping(value = "/v2.0/transfer/withdraw/fin_num", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WithdrawResponse> withdraw(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                     @RequestBody WithdrawRequest request) {
        ApiPrincipal principal = auth.authenticate(authorization);
        var result = service.withdraw(principal, new TransferService.WithdrawCommand(request.bankTranId(), request.tranDtime(),
                request.fintechUseNum(), request.tranAmt(), request.printContent()));
        return noStore(new WithdrawResponse(RspCode.SUCCESS.code(), "", result.bankTranId(), result.fintechUseNum(),
                String.valueOf(result.amount()), String.valueOf(result.balanceAfter())));
    }

    @PostMapping(value = "/v2.0/transfer/deposit/fin_num", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DepositResponse> deposit(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                   @RequestBody DepositRequest request) {
        return doDeposit(authorization, request, false);
    }

    /** 수취 계좌를 은행 코드와 계좌번호로 가리키는 입금이체. 처리 규칙은 fin_num 방식과 같다. */
    @PostMapping(value = "/v2.0/transfer/deposit/acnt_num", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DepositResponse> depositByAccountNumber(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                                  @RequestBody DepositRequest request) {
        return doDeposit(authorization, request, true);
    }

    private ResponseEntity<DepositResponse> doDeposit(String authorization, DepositRequest request, boolean byAccountNumber) {
        ApiPrincipal principal = auth.authenticate(authorization);
        List<TransferService.ItemCommand> items = request.reqList() == null ? null : request.reqList().stream()
                .map(i -> i == null ? null : new TransferService.ItemCommand(i.tranNo(), i.fintechUseNum(), i.bankCodeStd(), i.accountNum(),
                        i.printContent(), i.tranAmt())).toList();
        var result = service.deposit(principal, new TransferService.DepositCommand(request.bankTranId(), request.tranDtime(),
                request.wdPassPhrase(), request.wdPrintContent(), request.reqCnt(), items), byAccountNumber);
        List<DepositItemResponse> list = result.items().stream().map(i -> new DepositItemResponse(i.tranNo(), i.fintechUseNum(),
                i.bankCodeStd(), i.accountNumMasked(), String.valueOf(i.amount()), i.code().code(), i.code().message(), i.holderName())).toList();
        return noStore(new DepositResponse(result.code().code(), result.code().message(), result.bankTranId(), String.valueOf(list.size()), list));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    public record WithdrawRequest(
            @JsonProperty("bank_tran_id") String bankTranId,
            @JsonProperty("tran_dtime") String tranDtime,
            @JsonProperty("fintech_use_num") String fintechUseNum,
            @JsonProperty("tran_amt") String tranAmt,
            @JsonProperty("print_content") String printContent) {
    }

    public record WithdrawResponse(
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("bank_tran_id") String bankTranId,
            @JsonProperty("fintech_use_num") String fintechUseNum,
            @JsonProperty("tran_amt") String tranAmt,
            @JsonProperty("balance_amt") String balanceAmt) {
    }

    public record DepositRequest(
            @JsonProperty("bank_tran_id") String bankTranId,
            @JsonProperty("tran_dtime") String tranDtime,
            @JsonProperty("wd_pass_phrase") String wdPassPhrase,
            @JsonProperty("wd_print_content") String wdPrintContent,
            @JsonProperty("req_cnt") Integer reqCnt,
            @JsonProperty("req_list") List<DepositItemRequest> reqList) {
        /** 암호문구가 로그에 찍히지 않게 한다. */
        @Override
        public String toString() {
            return "DepositRequest[bankTranId=" + bankTranId + ", reqCnt=" + reqCnt + "]";
        }
    }

    public record DepositItemRequest(
            @JsonProperty("tran_no") Integer tranNo,
            @JsonProperty("fintech_use_num") String fintechUseNum,
            @JsonProperty("bank_code_std") String bankCodeStd,
            @JsonProperty("account_num") String accountNum,
            @JsonProperty("print_content") String printContent,
            @JsonProperty("tran_amt") String tranAmt) {
    }

    public record DepositResponse(
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("bank_tran_id") String bankTranId,
            @JsonProperty("res_cnt") String resCnt,
            @JsonProperty("res_list") List<DepositItemResponse> resList) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DepositItemResponse(
            @JsonProperty("tran_no") int tranNo,
            @JsonProperty("fintech_use_num") String fintechUseNum,
            @JsonProperty("bank_code_std") String bankCodeStd,
            @JsonProperty("account_num_masked") String accountNumMasked,
            @JsonProperty("tran_amt") String tranAmt,
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("account_holder_name") String accountHolderName) {
    }
}
