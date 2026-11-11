package com.san.mockbank.phone;

import java.util.regex.Pattern;

/** 휴대폰 번호 다루기. 저장·비교는 숫자만(하이픈 없음), 화면과 로그에는 가려서 보여 준다. */
public final class PhoneNumbers {

    private static final Pattern MOBILE = Pattern.compile("^01[016789]\\d{7,8}$");

    private PhoneNumbers() {
    }

    /** 숫자만 남긴다. 형식이 맞지 않으면 null. */
    public static String normalize(String input) {
        if (input == null) return null;
        String digits = input.replaceAll("[\\s-]", "");
        return MOBILE.matcher(digits).matches() ? digits : null;
    }

    /** 010-****-5678 처럼 가운데를 가린다. */
    public static String mask(String digits) {
        if (digits == null || digits.length() < 8) return "***";
        String last4 = digits.substring(digits.length() - 4);
        return digits.substring(0, 3) + "-****-" + last4;
    }

    /** 개발용 더미 번호(010-0000-xxxx): 실제로는 쓰이지 않는 번호라 문자를 보내지 않는다. */
    public static boolean isDummy(String digits) {
        return digits != null && digits.startsWith("0100000");
    }
}
