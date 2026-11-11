package com.san.mockbank.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.san.mockbank.account.BankAccount;
import com.san.mockbank.account.InOutType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.List;

/** 사용자 토큰(Bearer)으로 호출하는 계좌 API. 경로와 필드 이름은 오픈뱅킹 v2.0 형식을 따른다. */
@RestController
public class AccountApiController {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private final BearerAuthenticator auth;
    private final AccountApiService service;

    public AccountApiController(BearerAuthenticator auth, AccountApiService service) {
        this.auth = auth;
        this.service = service;
    }

    @GetMapping("/v2.0/account/list")
    public ResponseEntity<AccountListResponse> accountList(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                           @RequestParam(name = "user_seq_no") String userSeqNo,
                                                           @RequestParam(name = "include_cancel_yn", defaultValue = "N") String includeCancelYn,
                                                           @RequestParam(name = "sort_order", defaultValue = "D") String sortOrder) {
        ApiPrincipal principal = auth.authenticate(authorization);
        List<AccountApiService.ConsentedAccount> list = service.consentedAccounts(principal, userSeqNo, includeCancelYn, sortOrder);
        List<AccountItem> items = list.stream().map(c -> new AccountItem(c.account().getFintechUseNum(), c.account().getAccountAlias(), c.account().getBankCodeStd(),
                c.account().getBankName(), c.account().getAccountNumMasked(), c.account().getAccountHolderName(),
                c.account().getAccountType(), yn(c.consent().canInquire()), yn(c.consent().canTransfer()))).toList();
        return noStore(new AccountListResponse(RspCode.SUCCESS.code(), "", userSeqNo, String.valueOf(items.size()), items));
    }

    @GetMapping("/v2.0/account/balance/fin_num")
    public ResponseEntity<BalanceResponse> balance(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                   @RequestParam(name = "bank_tran_id") String bankTranId,
                                                   @RequestParam(name = "fintech_use_num") String fintechUseNum,
                                                   @RequestParam(name = "tran_dtime") String tranDtime) {
        ApiPrincipal principal = auth.authenticate(authorization);
        BankAccount a = service.balance(principal, bankTranId, fintechUseNum, tranDtime);
        return noStore(new BalanceResponse(RspCode.SUCCESS.code(), "", bankTranId, a.getBankCodeStd(), a.getBankName(),
                a.getFintechUseNum(), String.valueOf(a.getBalanceAmt()), String.valueOf(a.getBalanceAmt()), a.getAccountType()));
    }

    @GetMapping("/v2.0/account/transaction_list/fin_num")
    public ResponseEntity<TransactionListResponse> transactionList(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "bank_tran_id") String bankTranId,
            @RequestParam(name = "fintech_use_num") String fintechUseNum,
            @RequestParam(name = "tran_dtime") String tranDtime,
            @RequestParam(name = "inquiry_type", defaultValue = "A") String inquiryType,
            @RequestParam(name = "from_date") String fromDate,
            @RequestParam(name = "to_date") String toDate,
            @RequestParam(name = "sort_order", defaultValue = "D") String sortOrder,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        ApiPrincipal principal = auth.authenticate(authorization);
        AccountApiService.TransactionPage result = service.transactions(principal, bankTranId, fintechUseNum, tranDtime,
                inquiryType, fromDate, toDate, sortOrder, page, pageSize);
        var account = result.account();
        List<TransactionItem> items = result.page().getContent().stream().map(t -> new TransactionItem(
                t.getTranAt().format(DATE), t.getTranAt().format(TIME), t.getInoutType() == InOutType.IN ? "입금" : "출금",
                t.getTranType(), t.getPrintContent(), String.valueOf(t.getTranAmt()), String.valueOf(t.getAfterBalanceAmt()),
                t.getBranchName())).toList();
        return noStore(new TransactionListResponse(RspCode.SUCCESS.code(), "", bankTranId, account.getBankName(),
                account.getFintechUseNum(), String.valueOf(account.getBalanceAmt()), String.valueOf(result.page().getTotalElements()),
                page, pageSize, result.page().hasNext(), items));
    }

    private static String yn(boolean value) {
        return value ? "Y" : "N";
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    public record AccountListResponse(
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("user_seq_no") String userSeqNo,
            @JsonProperty("res_cnt") String resCnt,
            @JsonProperty("res_list") List<AccountItem> resList) {
    }

    public record AccountItem(
            @JsonProperty("fintech_use_num") String fintechUseNum,
            @JsonProperty("account_alias") String accountAlias,
            @JsonProperty("bank_code_std") String bankCodeStd,
            @JsonProperty("bank_name") String bankName,
            @JsonProperty("account_num_masked") String accountNumMasked,
            @JsonProperty("account_holder_name") String accountHolderName,
            @JsonProperty("account_type") String accountType,
            @JsonProperty("inquiry_agree_yn") String inquiryAgreeYn,
            @JsonProperty("transfer_agree_yn") String transferAgreeYn) {
    }

    public record BalanceResponse(
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("bank_tran_id") String bankTranId,
            @JsonProperty("bank_code_std") String bankCodeStd,
            @JsonProperty("bank_name") String bankName,
            @JsonProperty("fintech_use_num") String fintechUseNum,
            @JsonProperty("balance_amt") String balanceAmt,
            @JsonProperty("available_amt") String availableAmt,
            @JsonProperty("account_type") String accountType) {
    }

    public record TransactionListResponse(
            @JsonProperty("rsp_code") String rspCode,
            @JsonProperty("rsp_message") String rspMessage,
            @JsonProperty("bank_tran_id") String bankTranId,
            @JsonProperty("bank_name") String bankName,
            @JsonProperty("fintech_use_num") String fintechUseNum,
            @JsonProperty("balance_amt") String balanceAmt,
            @JsonProperty("total_cnt") String totalCnt,
            @JsonProperty("page") int page,
            @JsonProperty("page_size") int pageSize,
            @JsonProperty("has_next") boolean hasNext,
            @JsonProperty("res_list") List<TransactionItem> resList) {
    }

    public record TransactionItem(
            @JsonProperty("tran_date") String tranDate,
            @JsonProperty("tran_time") String tranTime,
            @JsonProperty("inout_type") String inoutType,
            @JsonProperty("tran_type") String tranType,
            @JsonProperty("print_content") String printContent,
            @JsonProperty("tran_amt") String tranAmt,
            @JsonProperty("after_balance_amt") String afterBalanceAmt,
            @JsonProperty("branch_name") String branchName) {
    }
}
