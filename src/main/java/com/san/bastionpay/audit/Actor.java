package com.san.bastionpay.audit;

/**
 * 보안 대응(잠금·차단·티켓·권한 회수)을 한 주체. 감사 기록에 누가 했는지 남기고, 티켓·차단 목록의 작성자 칸에도 쓴다.
 *
 * @param type     ADMIN(관리자가 화면에서) / API(API 키로 서버가 호출) / SYSTEM
 * @param memberId ADMIN 이면 그 관리자의 회원 id, 아니면 null
 * @param label    화면에 보이는 이름(관리자 닉네임, "api" 등). 50자 이내
 */
public record Actor(AuditLog.ActorType type, Long memberId, String label) {

    public Actor {
        label = label == null || label.isBlank() ? type.name().toLowerCase() : label.length() > 50 ? label.substring(0, 50) : label;
    }

    public static Actor admin(long memberId, String nickname) {
        return new Actor(AuditLog.ActorType.ADMIN, memberId, nickname);
    }

    public static Actor api() {
        return new Actor(AuditLog.ActorType.API, null, "api");
    }

    public static Actor system() {
        return new Actor(AuditLog.ActorType.SYSTEM, null, "system");
    }
}
