package com.san.bastionpay.auth;

import com.san.bastionpay.member.Member;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * 인증된 요청의 사용자. 토큰 클레임에서 복원하므로 DB 조회 없이 쓸 수 있는 값만 담는다.
 * 권한(role)과 등급(tier)은 별개이다. 권한은 ROLE_*, 등급은 TIER_* 로 부여한다.
 */
public record AuthMember(long id, String nickname, String role, String tier) {

    public static AuthMember from(Member member) {
        return new AuthMember(member.getId(), member.getNickname(), member.getRole().name(), member.getTier().name());
    }

    public List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role), new SimpleGrantedAuthority("TIER_" + tier));
    }
}
