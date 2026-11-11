package com.san.mockbank.api;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** 여러 API 가 함께 쓰는 요청 값 형식 검사. 틀리면 INVALID_PARAMETER 를 던진다. */
public final class ApiRules {

    private static final Pattern FINTECH_USE_NUM = Pattern.compile("^\\d{24}$");
    private static final Pattern AMOUNT = Pattern.compile("^[1-9]\\d{0,11}$");
    private static final DateTimeFormatter TRAN_DTIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private ApiRules() {
    }

    /** 거래고유번호: 이용기관코드(10자리) + 'U' + 영문 대문자·숫자 9자리. */
    public static void tranId(ApiPrincipal principal, String bankTranId) {
        String orgCode = principal.client().getOrgCode();
        if (bankTranId == null || !bankTranId.matches("^" + Pattern.quote(orgCode) + "U[A-Z0-9]{9}$")) {
            throw new ApiException(RspCode.INVALID_PARAMETER, "bank_tran_id 형식 또는 이용기관코드 불일치");
        }
    }

    public static void fintechUseNum(String value) {
        if (value == null || !FINTECH_USE_NUM.matcher(value).matches()) throw new ApiException(RspCode.INVALID_PARAMETER, "fintech_use_num 형식");
    }

    public static void tranDtime(String value) {
        try {
            if (value == null || value.length() != 14) throw new DateTimeParseException("len", "", 0);
            LocalDateTime.parse(value, TRAN_DTIME);
        } catch (DateTimeParseException e) {
            throw new ApiException(RspCode.INVALID_PARAMETER, "tran_dtime 형식");
        }
    }

    /** 계좌번호로 수취 계좌를 가리킬 때: 은행 코드 3자리 + 계좌번호 숫자 8~20자리(하이픈 없음). */
    public static void bankAccount(String bankCodeStd, String accountNum) {
        if (bankCodeStd == null || !bankCodeStd.matches("^\\d{3}$")) throw new ApiException(RspCode.INVALID_PARAMETER, "bank_code_std 형식");
        if (accountNum == null || !accountNum.matches("^\\d{8,20}$")) throw new ApiException(RspCode.INVALID_PARAMETER, "account_num 형식");
    }

    /** 이체 금액: 1 이상의 정수 문자열(최대 12자리). */
    public static long amount(String value) {
        if (value == null || !AMOUNT.matcher(value).matches()) throw new ApiException(RspCode.INVALID_PARAMETER, "tran_amt 형식");
        return Long.parseLong(value);
    }

    public static String text(String value, int maxLength, String name) {
        if (value == null) return null;
        if (value.length() > maxLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(RspCode.INVALID_PARAMETER, name + " 형식");
        }
        return value;
    }
}
