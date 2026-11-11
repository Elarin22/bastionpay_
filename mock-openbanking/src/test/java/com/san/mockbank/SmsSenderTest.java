package com.san.mockbank;

import com.san.mockbank.customer.BankCustomer;
import com.san.mockbank.customer.BankCustomerRepository;
import com.san.mockbank.phone.ConsoleSmsSender;
import com.san.mockbank.phone.PhoneAuthException;
import com.san.mockbank.phone.PhoneAuthProperties;
import com.san.mockbank.phone.PhoneAuthService;
import com.san.mockbank.phone.SmsSendException;
import com.san.mockbank.phone.SmsSender;
import com.san.mockbank.phone.SmsVerification;
import com.san.mockbank.phone.SmsVerificationRepository;
import java.time.Duration;
import java.util.Optional;
import com.san.mockbank.phone.PhoneNumbers;
import com.san.mockbank.phone.SmsProperties;
import com.san.mockbank.phone.SolapiSmsSender;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 문자 발송 설정과 번호 처리. 실제 솔라피 서버는 호출하지 않는다. */
class SmsSenderTest {

    @Test
    void realSenderRefusesToStartWithoutKeysOrSenderNumber() {
        assertThatThrownBy(() -> new SolapiSmsSender(new SmsProperties(true, "", "", "", false))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SolapiSmsSender(new SmsProperties(true, "key", "secret", "", false))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dummyNumbersNeverReachTheRealSmsProvider() {
        // 가짜 키로 만들어도, 더미 번호(0100000xxxx)는 발송 요청 전에 건너뛰므로 네트워크를 쓰지 않는다
        SolapiSmsSender sender = new SolapiSmsSender(new SmsProperties(true, "key", "secret", "01011112222", false));

        assertThatCode(() -> sender.send("01000000002", "인증번호 123456")).doesNotThrowAnyException();
        assertThat(sender.revealsCode()).isFalse();
    }

    @Test
    void consoleSenderRevealsCodeAndRealSenderDoesNot() {
        assertThat(new ConsoleSmsSender().revealsCode()).isTrue();
    }

    @Test
    void propertiesNeverPrintKeys() {
        assertThat(new SmsProperties(true, "my-key", "my-secret", "01011112222", false).toString()).doesNotContain("my-key", "my-secret", "01011112222");
    }

    @Test
    void phoneNumbersAreNormalizedAndMasked() {
        assertThat(PhoneNumbers.normalize("010-1234-5678")).isEqualTo("01012345678");
        assertThat(PhoneNumbers.normalize(" 010 1234 5678 ")).isEqualTo("01012345678");
        assertThat(PhoneNumbers.normalize("0111234567")).isEqualTo("0111234567");
        for (String bad : new String[]{null, "", "abc", "02-1234-5678", "1012345678", "010123456789012", "010-12"}) {
            assertThat(PhoneNumbers.normalize(bad)).as(String.valueOf(bad)).isNull();
        }
        assertThat(PhoneNumbers.mask("01012345678")).isEqualTo("010-****-5678");
        assertThat(PhoneNumbers.mask("0111234567")).isEqualTo("011-****-4567");
        assertThat(PhoneNumbers.mask(null)).isEqualTo("***");
        assertThat(PhoneNumbers.isDummy("01000000002")).isTrue();
        assertThat(PhoneNumbers.isDummy("01012345678")).isFalse();
    }

    @Test
    void failedSendLeavesNoVerificationBehindAndShowsFriendlyMessage() {
        SmsVerificationRepository verifications = mock(SmsVerificationRepository.class);
        BankCustomerRepository customers = mock(BankCustomerRepository.class);
        SmsSender sender = mock(SmsSender.class);
        BankCustomer customer = mock(BankCustomer.class);
        when(customer.isActive()).thenReturn(true);
        when(customers.findByPhone("01012345678")).thenReturn(Optional.of(customer));
        when(verifications.findFirstByPhoneOrderByIdDesc("01012345678")).thenReturn(Optional.empty());
        when(verifications.save(any(SmsVerification.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new SmsSendException("문자 발송 실패: SolapiUnknownException")).when(sender).send(any(), any());
        PhoneAuthService service = new PhoneAuthService(verifications, customers, sender,
                new PhoneAuthProperties(Duration.ofMinutes(3), 5, Duration.ofSeconds(60), 5),
                new SmsProperties(false, "", "", "", true));

        assertThatThrownBy(() -> service.start("010-1234-5678"))
                .isInstanceOf(PhoneAuthException.class)
                .hasMessageContaining("문자를 보내지 못했습니다")
                .hasMessageNotContaining("Solapi");
        // 보내지 못한 인증번호는 지워서 재발송 간격·횟수 제한에 걸리지 않는다
        verify(verifications).delete(any(SmsVerification.class));
    }
}
