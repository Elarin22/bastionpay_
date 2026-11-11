package com.san.bastionpay.auth;

import com.san.bastionpay.member.Member;
import com.san.bastionpay.member.MemberTier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 권한(ADMIN)과 등급(VIP/VVIP)이 서로 독립적으로 동작하는지 검증한다. 로컬 MySQL 이 떠 있어야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
class AccessControlTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwtService;

    private static RequestPostProcessor as(String role, String tier) {
        AuthMember member = new AuthMember(1L, "tester", role, tier);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(member, null, member.authorities()));
    }

    // ---------- /admin/** : ADMIN 권한만 ----------

    @Test
    void anonymousIsSentToLoginFromAdminArea() throws Exception {
        mvc.perform(get("/admin/test-ping").accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound()).andExpect(header().string("Location", "/login?required"));
    }

    @Test
    void highestTierUserCannotEnterAdminArea() throws Exception {
        mvc.perform(get("/admin/test-ping").with(as("USER", "VVIP"))).andExpect(status().isForbidden());
    }

    @Test
    void adminCanEnterAdminArea() throws Exception {
        mvc.perform(get("/admin/test-ping").with(as("ADMIN", "STANDARD")))
                .andExpect(status().isOk()).andExpect(content().string("admin-ok"));
    }

    // ---------- 등급: 상위 등급은 하위 기능 포함 ----------

    @Test
    void standardUserReachesOnlyStandardFeatures() throws Exception {
        mvc.perform(get("/test-tier/standard").with(as("USER", "STANDARD"))).andExpect(status().isOk());
        mvc.perform(get("/test-tier/vip").with(as("USER", "STANDARD"))).andExpect(status().isForbidden());
        mvc.perform(get("/test-tier/vvip").with(as("USER", "STANDARD"))).andExpect(status().isForbidden());
    }

    @Test
    void vipIncludesStandardButNotVvip() throws Exception {
        mvc.perform(get("/test-tier/standard").with(as("USER", "VIP"))).andExpect(status().isOk());
        mvc.perform(get("/test-tier/vip").with(as("USER", "VIP"))).andExpect(status().isOk());
        mvc.perform(get("/test-tier/vvip").with(as("USER", "VIP"))).andExpect(status().isForbidden());
    }

    @Test
    void vvipIncludesEverything() throws Exception {
        mvc.perform(get("/test-tier/standard").with(as("USER", "VVIP"))).andExpect(status().isOk());
        mvc.perform(get("/test-tier/vip").with(as("USER", "VVIP"))).andExpect(status().isOk());
        mvc.perform(get("/test-tier/vvip").with(as("USER", "VVIP"))).andExpect(status().isOk());
    }

    // ---------- ADMIN 은 등급을 상속하지 않는다 ----------

    @Test
    void adminDoesNotInheritTierFeatures() throws Exception {
        mvc.perform(get("/test-tier/vip").with(as("ADMIN", "STANDARD"))).andExpect(status().isForbidden());
        mvc.perform(get("/test-tier/vip").with(as("ADMIN", "VIP"))).andExpect(status().isOk());
    }

    // ---------- 토큰에 권한·등급이 실린다 ----------

    @Test
    void accessTokenCarriesRoleAndTier() {
        Member member = new Member("a@example.com", "홍길동", "nick", "hash");
        ReflectionTestUtils.setField(member, "id", 7L);
        member.changeTier(MemberTier.VVIP);

        AuthMember parsed = jwtService.parse(jwtService.issueAccessToken(member)).orElseThrow();

        assertThat(parsed.id()).isEqualTo(7L);
        assertThat(parsed.role()).isEqualTo("USER");
        assertThat(parsed.tier()).isEqualTo("VVIP");
        assertThat(parsed.authorities()).extracting(Object::toString).containsExactlyInAnyOrder("ROLE_USER", "TIER_VVIP");
    }
}
