package com.san.bastionpay.app;

import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import com.san.bastionpay.openbanking.OpenBankingClient.Account;
import com.san.bastionpay.openbanking.OpenBankingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * 연결한 계좌의 목록·상세·거래내역. 오픈뱅킹(가짜 은행) 응답을 그대로 보여 주며, 연결 전에는 연결 안내를 보여 준다.
 * 계좌는 핀테크이용번호로 가리키지만 서비스가 본인 계좌 목록에 있는지 먼저 확인하므로 다른 사람의 번호로는 조회되지 않는다.
 */
@Slf4j
@Controller
public class AccountPagesController {

    private static final List<Integer> PERIOD_DAYS = List.of(7, 30, 90);
    private static final List<String> KINDS = List.of("all", "in", "out");

    private final OpenBankingService openBanking;

    public AccountPagesController(OpenBankingService openBanking) {
        this.openBanking = openBanking;
    }

    @GetMapping("/accounts")
    public String accounts(@AuthenticationPrincipal AuthMember member, Model model) {
        if (!prepare(member, model, "accounts")) return "app/accounts";
        try {
            var rows = openBanking.accountsWithBalance(member.id());
            model.addAttribute("rows", rows);
            boolean allKnown = !rows.isEmpty() && rows.stream().allMatch(r -> r.balance() != null);
            model.addAttribute("total", allKnown ? rows.stream().mapToLong(r -> r.balance()).sum() : null);
            model.addAttribute("balanceMissing", rows.stream().anyMatch(r -> r.balance() == null));
        } catch (BusinessException e) {
            handle(member, model, e);
        }
        return "app/accounts";
    }

    @GetMapping("/accounts/{fintechUseNum:\\d{24}}")
    public String accountDetail(@AuthenticationPrincipal AuthMember member, @PathVariable String fintechUseNum,
                                @RequestParam(defaultValue = "30") int days, @RequestParam(defaultValue = "all") String kind,
                                @RequestParam(defaultValue = "1") int page, Model model) {
        if (!prepare(member, model, "accounts")) return "app/account-detail";
        int period = PERIOD_DAYS.contains(days) ? days : 30;
        String filter = KINDS.contains(kind) ? kind : "all";
        model.addAttribute("days", period);
        model.addAttribute("kind", filter);
        try {
            var history = openBanking.history(member.id(), fintechUseNum, period, filter, Math.max(page, 1));
            model.addAttribute("history", history);
            model.addAttribute("pager", Pager.of(history.page(), history.totalPages(), n -> UriComponentsBuilder
                    .fromPath("/accounts/{fin}").queryParam("days", period).queryParam("kind", filter).queryParam("page", n)
                    .buildAndExpand(fintechUseNum).encode().toUriString()));
        } catch (BusinessException e) {
            // 남의 계좌·없는 계좌는 404 화면으로 보낸다. 그 밖의 오류는 화면 안에서 안내한다
            if (e.getErrorCode() == ErrorCode.ACCOUNT_NOT_FOUND) throw e;
            handle(member, model, e);
        }
        return "app/account-detail";
    }

    /** 연결한 계좌 전체(또는 한 계좌)의 거래내역. */
    @GetMapping("/transactions")
    public String transactions(@AuthenticationPrincipal AuthMember member, @RequestParam(defaultValue = "all") String account,
                               @RequestParam(defaultValue = "30") int days, @RequestParam(defaultValue = "all") String kind,
                               @RequestParam(defaultValue = "1") int page, Model model) {
        if (!prepare(member, model, "transactions")) return "app/transactions";
        int period = PERIOD_DAYS.contains(days) ? days : 30;
        String filter = KINDS.contains(kind) ? kind : "all";
        model.addAttribute("days", period);
        model.addAttribute("kind", filter);
        try {
            List<Account> accounts = openBanking.accountList(member.id());
            model.addAttribute("accounts", accounts);
            // 목록에 없는 값(남의 번호 포함)은 전체로 취급한다
            String selected = accounts.stream().anyMatch(a -> a.fintechUseNum().equals(account)) ? account : "all";
            model.addAttribute("account", selected);
            var result = openBanking.allTransactions(member.id(), "all".equals(selected) ? null : selected, period, filter);
            // 여러 계좌의 거래는 은행에서 이미 최신순으로 모아 왔으므로(최대 100건) 10건씩 잘라서 보여 준다
            int size = OpenBankingService.PAGE_SIZE;
            int totalPages = Math.max(1, (result.rows().size() + size - 1) / size);
            int current = Math.min(Math.max(page, 1), totalPages);
            model.addAttribute("result", result);
            model.addAttribute("pageRows", result.rows().subList((current - 1) * size, Math.min(current * size, result.rows().size())));
            model.addAttribute("pager", Pager.of(current, totalPages, n -> UriComponentsBuilder.fromPath("/transactions")
                    .queryParam("account", selected).queryParam("days", period).queryParam("kind", filter).queryParam("page", n)
                    .build().encode().toUriString()));
        } catch (BusinessException e) {
            handle(member, model, e);
        }
        return "app/transactions";
    }

    /** 공통 모델을 채우고, 연결되어 있지 않으면 false 를 돌려준다(화면은 연결 안내를 보여 준다). */
    private boolean prepare(AuthMember member, Model model, String activeNav) {
        model.addAttribute("activeNav", activeNav);
        model.addAttribute("hideSampleNote", true);
        model.addAttribute("configured", openBanking.isConfigured());
        boolean linked = openBanking.isLinked(member.id());
        model.addAttribute("linked", linked);
        return linked;
    }

    private void handle(AuthMember member, Model model, BusinessException e) {
        log.warn("account page failed memberId={} code={} detail={}", member.id(), e.getErrorCode().code(), e.getMessage());
        if (e.getErrorCode() == ErrorCode.OPENBANKING_NOT_LINKED) {
            model.addAttribute("linked", false);   // 토큰이 만료됐거나 읽을 수 없으면 다시 연결하도록 안내한다
        } else {
            model.addAttribute("error", e.getErrorCode().message());
        }
    }
}
