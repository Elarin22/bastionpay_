package com.san.bastionpay.member;

/** 서비스 이용 등급. 권한(MemberRole)과 별개이며, 상위 등급은 하위 등급 기능을 모두 쓸 수 있다(SecurityConfig 의 RoleHierarchy). */
public enum MemberTier {
    STANDARD, VIP, VVIP
}
