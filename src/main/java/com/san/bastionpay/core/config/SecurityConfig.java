package com.san.bastionpay.core.config;

import com.san.bastionpay.auth.AuthCookies;
import com.san.bastionpay.auth.JwtAuthenticationFilter;
import com.san.bastionpay.auth.RefreshTokenService;
import com.san.bastionpay.core.security.CustomAccessDeniedHandler;
import com.san.bastionpay.core.security.CustomAuthenticationEntryPoint;
import com.san.bastionpay.auth.JwtService;
import com.san.bastionpay.auth.LoginSessionService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Access Token 쿠키 인증 필터(Refresh Rotation 포함)까지 연결한 구성. 미인증(401)·권한 부족(403) 핸들러를 연결한 구성.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // @PreAuthorize 사용
public class SecurityConfig {

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    /** dev 프로파일에서만 에러 페이지 미리보기 경로를 열어 둔다. */
    @Bean
    @Order(1)
    @Profile("dev")
    SecurityFilterChain devPreviewFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/dev/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** 자체 JWT 인증을 쓰므로, Boot 가 임시 비밀번호를 로그에 찍는 기본 사용자를 만들지 않게 막는다. */
    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("not supported");
        };
    }

    /**
     * 등급끼리만 포함 관계를 둔다: VVIP 는 VIP 와 STANDARD 기능을 모두 쓸 수 있다.
     * ADMIN(권한)은 등급과 분리되어 있어 어떤 등급도 상속하지 않는다.
     * 사용: @PreAuthorize("hasAuthority('TIER_VIP')")
     */
    @Bean
    RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("TIER_VVIP > TIER_VIP\nTIER_VIP > TIER_STANDARD");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                            RefreshTokenService refreshTokens, AuthCookies cookies,
                                            CustomAuthenticationEntryPoint entryPoint,
                                            CustomAccessDeniedHandler accessDeniedHandler,
                                            LoginSessionService loginSessions) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 에러 디스패치를 막으면 에러 페이지 자체가 401/403으로 가려진다
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/", "/login", "/logout", "/signup", "/signup/**", "/error",
                                "/css/**", "/js/**", "/img/**", "/fonts/**", "/favicon.ico").permitAll()
                        // 은행에서 넘어오는 요청이라 로그인 쿠키(Strict)가 없다. state 로 직접 검증한다
                        .requestMatchers("/openbanking/callback").permitAll()
                        // 서버 간 호출 API: 사람 로그인 대신 X-API-Key 를 컨트롤러(AdminApiAuth)가 직접 검증한다. 키가 없으면 모두 401
                        .requestMatchers("/api/admin/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")   // 보안 대시보드·티켓·IP 차단 등 관리자 전용
                        .anyRequest().authenticated())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)   // POST /logout 은 AuthController 가 토큰 쿠키를 지운다
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, refreshTokens, cookies, loginSessions), AuthorizationFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()).ignoringRequestMatchers("/api/admin/**"))   // API 는 쿠키 인증이 아니라 키 인증이라 CSRF 대상이 아니다
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));
        return http.build();
    }
}
