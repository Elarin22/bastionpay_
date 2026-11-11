package com.san.bastionpay.audit;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 로그인 시도에 쓴 이메일을 서버 비밀키로 HMAC-SHA256 해서 계정 식별값을 만든다.
 * 같은 이메일은 항상 같은 값이라 "같은 계정에 대한 실패 횟수"를 셀 수 있고, 키가 없으면 이메일을 되짚을 수 없다.
 * 없는 계정에 대한 시도도 같은 방식으로 값이 나온다(계정 존재 여부를 드러내지 않는다).
 */
@Component
public class LoginSubjectHasher {

    private final byte[] key;

    public LoginSubjectHasher(SecurityProperties props) {
        String secret = props.subjectHashKey();
        if (secret == null || secret.isBlank()) throw new IllegalStateException("app.security.subject-hash-key 가 필요합니다.");
        // 다른 용도의 키와 섞이지 않게 용도 이름을 붙여서 쓴다
        this.key = ("login-subject:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
