package com.san.bastionpay.signup;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 단계별 입력 폼. 검증 메시지는 화면에 그대로 보인다. */
public final class SignupForms {

    /** 영문·숫자·특수문자를 모두 포함한 8~64자 (공백·한글 제외, 출력 가능한 ASCII 만). */
    public static final String PASSWORD_PATTERN =
            "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!-/:-@\\[-`{-~])[!-~]{8,64}$";

    private SignupForms() {
    }

    public record EmailForm(
            @NotBlank(message = "이메일을 입력해 주세요.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 254, message = "이메일이 너무 깁니다.")
            String email) {
    }

    public record CodeForm(
            @Pattern(regexp = "^\\d{6}$", message = "인증 코드 6자리를 입력해 주세요.")
            String code) {
    }

    public record ProfileForm(
            @NotBlank(message = "이름을 입력해 주세요.")
            @Size(min = 2, max = 30, message = "이름은 2~30자로 입력해 주세요.")
            @Pattern(regexp = "^[가-힣A-Za-z]+( [가-힣A-Za-z]+)*$", message = "이름은 한글·영문만 입력할 수 있습니다.")
            String name,

            @NotBlank(message = "닉네임을 입력해 주세요.")
            @Size(min = 2, max = 12, message = "닉네임은 2~12자로 입력해 주세요.")
            @Pattern(regexp = "^[가-힣A-Za-z0-9_]+$", message = "닉네임은 한글·영문·숫자·밑줄(_)만 쓸 수 있습니다.")
            String nickname) {
    }

    public record PasswordForm(
            @NotBlank(message = "비밀번호를 입력해 주세요.")
            @Pattern(regexp = PASSWORD_PATTERN, message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해 8자 이상이어야 합니다.")
            String password,

            @NotBlank(message = "비밀번호를 한 번 더 입력해 주세요.")
            String passwordConfirm) {

        /** 비밀번호가 로그에 남지 않도록 기본 toString 을 막는다. */
        @Override
        public String toString() {
            return "PasswordForm[]";
        }
    }
}
