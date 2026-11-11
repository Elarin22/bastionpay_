package com.san.bastionpay.openbanking;

import com.san.bastionpay.core.exception.BusinessException;
import com.san.bastionpay.core.exception.ErrorCode;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * DB 에 저장하는 오픈뱅킹 access token 을 AES-256-GCM 으로 암호화한다.
 * 키는 설정 값에서 용도 접두어를 붙여 SHA-256 으로 만들어, JWT 서명 키와 같은 바이트를 그대로 쓰지 않는다.
 */
@Component
public class TokenCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public TokenCipher(OpenBankingProperties props) {
        if (props.tokenSecret() == null || props.tokenSecret().isBlank()) {
            throw new IllegalStateException("app.openbanking.token-secret 이 비어 있습니다.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("bastionpay:openbanking-token:v1:" + props.tokenSecret()).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("토큰 암호화 실패", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] encrypted = new byte[buf.remaining()];
            buf.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            // 키가 바뀌었거나 값이 손상된 경우. 사용자는 다시 연결하면 된다
            throw new BusinessException(ErrorCode.OPENBANKING_NOT_LINKED, "토큰 복호화 실패: " + e.getClass().getSimpleName());
        }
    }
}
