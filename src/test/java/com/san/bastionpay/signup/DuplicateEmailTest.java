package com.san.bastionpay.signup;

import com.san.bastionpay.core.mail.MailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 이미 가입했거나 이미 인증을 마친 이메일은 다시 인증할 수 없다. 메일 발송은 대체하고 DB 는 실제로 쓴다(롤백). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DuplicateEmailTest {

    @Autowired SignupService signup;
    @Autowired SignupRequestRepository requests;

    @Autowired MockMvc mvc;
    @MockitoBean MailService mail;

    @Test
    void anEmailThatIsAlreadyAMemberCannotStartVerification() {
        long before = requests.count();

        assertThatThrownBy(() -> signup.start(null, "test@test.com")).isInstanceOf(SignupException.class).hasMessageContaining("이미 가입된 이메일");
        assertThatThrownBy(() -> signup.start(null, "  TEST@Test.com ")).as("대소문자·공백이 달라도 같은 이메일").isInstanceOf(SignupException.class);

        verify(mail, never()).sendVerificationCode(anyString(), anyString(), any());   // 인증 메일을 보내지 않는다
        assertThat(requests.count()).as("가입 요청도 만들지 않는다").isEqualTo(before);
    }

    @Test
    void anEmailAlreadyVerifiedInAnotherSignupCannotBeVerifiedAgain() {
        String first = signup.start(null, "someone-new@example.test");
        signup.verifyCode(first, lastCode());

        assertThat(signup.find(first).orElseThrow().isEmailVerified()).isTrue();
        // 다른 브라우저(쿠키 없음)에서 같은 이메일로 다시 시작하려 해도 막힌다
        assertThatThrownBy(() -> signup.start(null, "Someone-New@example.test"))
                .isInstanceOf(SignupException.class).hasMessageContaining("이미 이 이메일로 인증을 마치고");
        verify(mail, times(1)).sendVerificationCode(anyString(), anyString(), any());
    }

    @Test
    void twoBrowsersThatBothGotACodeCannotBothVerify() {
        // 인증 전에는 같은 이메일로 둘 다 코드를 받을 수 있다(아직 아무도 인증하지 않았다)
        String a = signup.start(null, "race@example.test");
        String codeA = lastCode();
        String b = signup.start(null, "race@example.test");
        String codeB = lastCode();

        signup.verifyCode(a, codeA);
        assertThatThrownBy(() -> signup.verifyCode(b, codeB)).isInstanceOf(SignupException.class).hasMessageContaining("이미 이 이메일로 인증을 마치고");
        assertThat(signup.find(b).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void aNormalSignupStillWorks() {
        String token = signup.start(null, "fresh-person@example.test");
        signup.verifyCode(token, lastCode());

        assertThat(signup.find(token).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void signingUpWithAMemberEmailGoesStraightToTheLoginPage() throws Exception {
        mvc.perform(post("/signup/email").param("email", "test@test.com").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"));   // 이메일은 주소에 싣지 않는다

        String html = mvc.perform(get("/login").param("registered", "")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("이미 가입된 이메일입니다. 로그인해 주세요.");
        verify(mail, never()).sendVerificationCode(anyString(), anyString(), any());
    }

    private String lastCode() {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail, org.mockito.Mockito.atLeastOnce()).sendVerificationCode(anyString(), code.capture(), any(Duration.class));
        return code.getValue();
    }
}
