package com.san.bastionpay.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 차단된 IP(blocked_ip). 시각은 UTC. */
@Entity
@Table(name = "blocked_ip")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BlockedIp {

    @Id
    @Column(length = 45)
    private String ip;

    @Column(length = 200)
    private String reason;

    @Column(name = "blocked_by", nullable = false, length = 50)
    private String blockedBy;

    @Column(name = "blocked_at", nullable = false)
    private LocalDateTime blockedAt;

    public BlockedIp(String ip, String reason, String blockedBy, LocalDateTime blockedAt) {
        this.ip = ip;
        this.reason = reason;
        this.blockedBy = blockedBy;
        this.blockedAt = blockedAt;
    }
}
