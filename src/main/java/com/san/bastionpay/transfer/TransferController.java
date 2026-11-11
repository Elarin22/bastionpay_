package com.san.bastionpay.transfer;

import com.san.bastionpay.auth.AuthMember;
import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.member.TierService;
import com.san.bastionpay.core.exception.ErrorCode;
import com.san.bastionpay.openbanking.OpenBankingService;
import com.san.bastionpay.openbanking.OpenBankingService.AccountBalance;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 이체 화면: 입력 → 확인 → 실행. 실행 단계는 확인 화면의 값을 믿지 않고 서버가 다시 검증·조회한다.
 * 출금 계좌는 연결한 내 계좌 중 은행에서 이체를 허용한 계좌만 고를 수 있다.
 */
@Slf4j
@Controller
public class TransferController {

    /** 화면에 보이는 은행 이름과 은행 코드. 가짜 은행이 제공하는 은행만 둔다. */
    static final Map<String, String> BANKS = new LinkedHashMap<>();

    static {
        BANKS.put("한빛은행", "901");
        BANKS.put("새솔은행", "902");
    }

    private final OpenBankingService openBanking;
    private final TransferService transfers;
    private final TierService tiers;

    public TransferController(OpenBankingService openBanking, TransferService transfers, TierService tiers) {
        this.openBanking = openBanking;
        this.transfers = transfers;
        this.tiers = tiers;
    }

    @GetMapping("/transfer")
    public String form(@AuthenticationPrincipal AuthMember member, @RequestParam(required = false) String from, Model model) {
        List<AccountBalance> eligible = eligible(member, model);
        if (eligible == null) return "app/transfer";
        String selected = eligible.stream().anyMatch(r -> r.account().fintechUseNum().equals(from))
                ? from : eligible.get(0).account().fintechUseNum();
        return view(model, new TransferForm(selected, "", "", null, ""), "form");
    }

    @PostMapping("/transfer/confirm")
    public String confirm(@AuthenticationPrincipal AuthMember member, @Valid @ModelAttribute("transferForm") TransferForm form,
                          BindingResult binding, Model model) {
        List<AccountBalance> eligible = eligible(member, model);
        if (eligible == null) return "app/transfer";
        if (binding.hasErrors()) {
            model.addAttribute("error", firstMessage(binding));
            return view(model, form, "form");
        }
        AccountBalance from = find(eligible, form.fromAccountId());
        if (from == null) return formError(model, form, "출금 계좌를 목록에서 선택해 주세요.");
        if (!BANKS.containsKey(form.toBank())) return formError(model, form, "받는 은행을 목록에서 선택해 주세요.");
        if (form.toAccountDigits().length() < 8 || form.toAccountDigits().length() > 20) return formError(model, form, "받는 계좌번호는 8~20자리 숫자로 입력해 주세요.");
        var limit = tiers.limitOf(member.id());
        if (limit.isPresent() && form.amount() > limit.getAsLong()) {
            return formError(model, form, "1회 이체 한도(" + String.format("%,d", limit.getAsLong()) + "원)를 넘었습니다. 한도는 회원 등급에 따라 다르고, 이체를 이용하면 등급이 올라갑니다.");
        }
        if (from.balance() != null && form.amount() > from.balance()) return formError(model, form, "출금 계좌의 잔액이 부족합니다.");

        model.addAttribute("requestId", UUID.randomUUID().toString());
        model.addAttribute("balanceAfter", from.balance() == null ? null : from.balance() - form.amount());
        return view(model, form, "confirm");
    }

    /** 확인 화면에서 "내용 수정하기": 입력한 값을 그대로 채워 입력 화면으로 되돌린다. */
    @PostMapping("/transfer/edit")
    public String edit(@AuthenticationPrincipal AuthMember member, @ModelAttribute("transferForm") TransferForm form, Model model) {
        if (eligible(member, model) == null) return "app/transfer";
        return view(model, form, "form");
    }

    @PostMapping("/transfer/execute")
    public String execute(@AuthenticationPrincipal AuthMember member, @Valid @ModelAttribute("transferForm") TransferForm form,
                          BindingResult binding, @RequestParam String requestId, Model model) {
        List<AccountBalance> eligible = eligible(member, model);
        if (eligible == null) return "app/transfer";
        if (binding.hasErrors() || find(eligible, form.fromAccountId()) == null || !BANKS.containsKey(form.toBank())) {
            return formError(model, form, "입력 내용을 다시 확인해 주세요.");
        }
        try {
            TransferService.Outcome outcome = transfers.transfer(member.id(), requestId, form.fromAccountId(), BANKS.get(form.toBank()),
                    form.toAccountDigits(), form.amount(), form.memo());
            model.addAttribute("outcome", outcome);
            model.addAttribute("requestId", outcome.requestId());
            model.addAttribute("toHolder", outcome.toHolderMasked());
            return switch (outcome.status()) {
                case COMPLETED -> {
                    model.addAttribute("balanceAfter", currentBalance(member, form.fromAccountId()));
                    yield view(model, form, "done");
                }
                case REFUNDED -> view(model, form, "refunded");
                default -> view(model, form, "review");   // 결과 확인 중(또는 이미 처리 중인 요청)
            };
        } catch (BusinessException e) {
            log.warn("transfer rejected memberId={} code={} detail={}", member.id(), e.getErrorCode().code(), e.getMessage());
            return formError(model, form, e.getErrorCode().message());
        }
    }

    /** 이체할 수 있는 상태인지 확인하고, 이체 가능한 계좌 목록을 돌려준다. 이체할 수 없으면 사유를 모델에 담고 null. */
    private List<AccountBalance> eligible(AuthMember member, Model model) {
        model.addAttribute("activeNav", "transfer");
        model.addAttribute("hideSampleNote", true);
        model.addAttribute("configured", openBanking.isConfigured());
        model.addAttribute("banks", BANKS.keySet());
        model.addAttribute("tierStatus", tiers.status(member.id()));   // 내 등급과 1회 이체 한도(DB 기준)
        boolean linked = openBanking.isLinked(member.id());
        model.addAttribute("linked", linked);
        if (!linked) {
            model.addAttribute("stage", "empty");
            return null;
        }
        if (!openBanking.canTransfer()) {
            model.addAttribute("stage", "unavailable");
            return null;
        }
        try {
            List<AccountBalance> eligible = openBanking.accountsWithBalance(member.id()).stream()
                    .filter(r -> r.account().transferAgree()).toList();
            if (eligible.isEmpty()) {
                model.addAttribute("stage", "noaccount");
                return null;
            }
            model.addAttribute("rows", eligible);
            return eligible;
        } catch (BusinessException e) {
            log.warn("transfer page failed memberId={} code={} detail={}", member.id(), e.getErrorCode().code(), e.getMessage());
            if (e.getErrorCode() == ErrorCode.OPENBANKING_NOT_LINKED) {
                model.addAttribute("linked", false);
                model.addAttribute("stage", "empty");
            } else {
                model.addAttribute("error", e.getErrorCode().message());
                model.addAttribute("stage", "unavailable");
            }
            return null;
        }
    }

    private String view(Model model, TransferForm form, String stage) {
        model.addAttribute("stage", stage);
        model.addAttribute("transferForm", form);
        @SuppressWarnings("unchecked")
        List<AccountBalance> rows = (List<AccountBalance>) model.getAttribute("rows");
        if (rows != null) {
            AccountBalance from = find(rows, form.fromAccountId());
            if (from != null) model.addAttribute("from", from.account());
        }
        if (form.toAccount() != null) model.addAttribute("maskedTo", mask(form.toAccountDigits()));
        return "app/transfer";
    }

    private String formError(Model model, TransferForm form, String message) {
        model.addAttribute("error", message);
        return view(model, form, "form");
    }

    private long currentBalance(AuthMember member, String fintechUseNum) {
        try {
            return openBanking.currentBalance(member.id(), fintechUseNum);
        } catch (BusinessException e) {
            log.warn("balance after transfer failed memberId={} code={}", member.id(), e.getErrorCode().code());
            return -1;   // 화면에서 숨긴다
        }
    }

    private static AccountBalance find(List<AccountBalance> rows, String fintechUseNum) {
        return rows.stream().filter(r -> r.account().fintechUseNum().equals(fintechUseNum)).findFirst().orElse(null);
    }

    /** 숫자 변환 실패(예: 금액에 쉼표·문자) 같은 기본 메시지는 사용자 문구로 바꾼다. */
    private static String firstMessage(BindingResult binding) {
        var error = binding.getAllErrors().get(0);
        if ("typeMismatch".equals(error.getCode())) return "이체 금액은 숫자만 입력해 주세요. (쉼표 없이)";
        return error.getDefaultMessage();
    }

    /** 받는 계좌번호는 화면에 뒤 4자리만 보여 준다. */
    static String mask(String digits) {
        return digits.length() <= 4 ? digits : "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }
}
