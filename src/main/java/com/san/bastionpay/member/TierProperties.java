package com.san.bastionpay.member;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.OptionalLong;

/**
 * 회원 등급 정책.
 * <ul>
 *   <li>승급: 누적 이체 금액(완료된 이체의 합)이 기준 이상이 되면 자동으로 오른다</li>
 *   <li>강등: 앱을 inactivityMonths 개월 이용하지 않으면 한 단계씩 자동으로 내려간다</li>
 *   <li>1회 이체 한도: 등급마다 다르다. VVIP 는 제한이 없다</li>
 * </ul>
 *
 * @param vipThreshold     VIP 가 되는 누적 이체 금액(원)
 * @param vvipThreshold    VVIP 가 되는 누적 이체 금액(원)
 * @param standardLimit    STANDARD 의 1회 이체 한도(원)
 * @param vipLimit         VIP 의 1회 이체 한도(원)
 * @param inactivityMonths 이만큼 이용하지 않으면 한 단계 강등
 */
@ConfigurationProperties(prefix = "app.tier")
public record TierProperties(long vipThreshold, long vvipThreshold, long standardLimit, long vipLimit, int inactivityMonths) {

    public TierProperties {
        if (vipThreshold < 1 || vvipThreshold <= vipThreshold) throw new IllegalArgumentException("app.tier: vvip-threshold 는 vip-threshold 보다 커야 합니다.");
        if (standardLimit < 1 || vipLimit < standardLimit) throw new IllegalArgumentException("app.tier: vip-limit 는 standard-limit 이상이어야 합니다.");
        if (inactivityMonths < 1) throw new IllegalArgumentException("app.tier.inactivity-months 는 1 이상이어야 합니다.");
    }

    /** 이 등급의 1회 이체 한도. 비어 있으면 제한 없음(VVIP). */
    public OptionalLong limitFor(MemberTier tier) {
        return switch (tier) {
            case STANDARD -> OptionalLong.of(standardLimit);
            case VIP -> OptionalLong.of(vipLimit);
            case VVIP -> OptionalLong.empty();
        };
    }

    /** 누적 이체 금액으로 정해지는 등급(올라가기만 하는 계산이라 현재 등급과 비교해서 쓴다). */
    public MemberTier tierFor(long cumulative) {
        if (cumulative >= vvipThreshold) return MemberTier.VVIP;
        if (cumulative >= vipThreshold) return MemberTier.VIP;
        return MemberTier.STANDARD;
    }

    /** 다음 등급에 필요한 누적 금액. 이미 최고 등급이면 비어 있다. */
    public OptionalLong thresholdOfNext(MemberTier tier) {
        return switch (tier) {
            case STANDARD -> OptionalLong.of(vipThreshold);
            case VIP -> OptionalLong.of(vvipThreshold);
            case VVIP -> OptionalLong.empty();
        };
    }
}
