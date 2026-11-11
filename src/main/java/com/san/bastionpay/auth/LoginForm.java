package com.san.bastionpay.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginForm(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 64) String password) {

    /** 비밀번호가 로그에 남지 않도록 기본 toString 을 막는다. */
    @Override
    public String toString() {
        return "LoginForm[]";
    }
}
