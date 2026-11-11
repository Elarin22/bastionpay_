package com.san.mockbank;

import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 테스트에서 은행 휴대폰 인증(번호 입력 → 인증번호 입력)을 통과시킨다. 개발용 화면에 보이는 인증번호를 읽어 쓴다. */
final class PhoneLogin {

    /** 시드 더미 고객의 휴대폰 번호. */
    static final Map<String, String> PHONES = Map.of(
            "test@test.com", "01000000002", "vip@test.com", "01000000003", "vvip@test.com", "01000000004", "inst-bastionpay", "01000000001");

    private static final Pattern DEV_CODE = Pattern.compile("id=\"dev-code\">(\\d{6})<");

    private PhoneLogin() {
    }

    /** 번호를 보내고 개발용 인증번호를 돌려준다. 등록되지 않은 번호이면 null. */
    static String requestCode(MockMvc mvc, MockHttpSession session, String phone) throws Exception {
        mvc.perform(post("/oauth/2.0/authorize/phone").session(session).with(csrf()).param("phone", phone))
                .andExpect(status().isFound()).andExpect(header().string("Location", "/oauth/2.0/authorize/code"));
        return readCode(mvc, session);
    }

    static String readCode(MockMvc mvc, MockHttpSession session) throws Exception {
        String page = mvc.perform(get("/oauth/2.0/authorize/code").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = DEV_CODE.matcher(page);
        return m.find() ? m.group(1) : null;
    }

    /** 휴대폰 인증을 끝까지 진행한다(동의 화면으로 이동할 때까지). */
    static void login(MockMvc mvc, org.springframework.jdbc.core.JdbcTemplate jdbc, MockHttpSession session, String loginIdOrPhone) throws Exception {
        String phone = PHONES.getOrDefault(loginIdOrPhone, loginIdOrPhone);
        // 같은 번호의 재발송 간격 제한에 걸리지 않게, 이 테스트 트랜잭션 안에서만 이전 기록을 지운다(롤백된다)
        jdbc.update("delete from sms_verification where phone = ?", phone);
        String code = requestCode(mvc, session, phone);
        assertThat(code).as("개발용 인증번호가 화면에 보여야 한다").isNotNull();
        MvcResult done = mvc.perform(post("/oauth/2.0/authorize/code").session(session).with(csrf()).param("code", code))
                .andExpect(status().isFound()).andReturn();
        assertThat(done.getResponse().getHeader("Location")).isEqualTo("/oauth/2.0/authorize/consent");
    }
}
