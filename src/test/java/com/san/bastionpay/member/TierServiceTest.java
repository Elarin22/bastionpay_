package com.san.bastionpay.member;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원 등급: 누적 이체 금액으로 자동 승급, 오래 이용하지 않으면 한 단계씩 자동 강등, 등급별 1회 이체 한도.
 * 로컬 MySQL 과 db/seed/member.sql 의 더미 회원이 필요하고, 바꾼 데이터는 롤백된다. 이체 기록은 DB 에 직접 넣는다.
 */
@SpringBootTest
@Transactional
class TierServiceTest {

    @Autowired TierService tiers;
    @Autowired TierProperties props;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private Member member;
    private int seq;

    @BeforeEach
    void setUp() {
        member = members.findByEmail("test@test.com").orElseThrow();
        resetTo(MemberTier.STANDARD, LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(1));
        jdbc.update("delete from transfer_request where member_id = ?", member.getId());
    }

    /** 등급과 이용 시각·누적 기준 시각을 직접 정한다(JDBC 로 바꾸므로 JPA 캐시를 비운다). */
    private void resetTo(MemberTier tier, LocalDateTime lastActive, LocalDateTime baseline) {
        em.flush();
        jdbc.update("update member set tier = ?, last_active_at = ?, tier_baseline_at = ?, tier_changed_at = null where id = ?",
                tier.name(), lastActive, baseline, member.getId());
        em.clear();
    }

    private void transfer(long amount, String status) {
        em.flush();
        seq++;
        jdbc.update("insert into transfer_request (member_id, request_id, from_fintech_use_num, from_label, to_bank_code, to_account_masked, amount, status, created_at, updated_at) "
                        + "values (?, ?, '199900000000000000000002', '한빛은행 월급 통장', '901', '200-***-9012', ?, ?, ?, ?)",
                member.getId(), String.format("00000000-0000-0000-0000-%012d", seq), amount, status, LocalDateTime.now(), LocalDateTime.now());
        em.clear();
    }

    private void completedTransfer(long amount) {
        transfer(amount, "COMPLETED");
    }

    private Member reload() {
        em.flush();
        em.clear();
        return members.findById(member.getId()).orElseThrow();
    }

    // ---- 한도 ----

    @Test
    void perTransferLimitDependsOnTheTier() {
        assertThat(tiers.limitFor(MemberTier.STANDARD)).hasValue(2_000_000L);
        assertThat(tiers.limitFor(MemberTier.VIP)).hasValue(10_000_000L);
        assertThat(tiers.limitFor(MemberTier.VVIP)).isEmpty();   // 제한 없음
        assertThat(tiers.limitOf(member.getId())).hasValue(2_000_000L);
        resetTo(MemberTier.VVIP, LocalDateTime.now(), LocalDateTime.now());
        assertThat(tiers.limitOf(member.getId())).isEmpty();
    }

    // ---- 승급 ----

    @Test
    void completedTransfersAddUpAndPromoteInOrder() {
        completedTransfer(props.vipThreshold() - 1);
        tiers.onTransferCompleted(member.getId());
        assertThat(reload().getTier()).isEqualTo(MemberTier.STANDARD);   // 기준 직전까지는 그대로

        completedTransfer(1);
        tiers.onTransferCompleted(member.getId());
        assertThat(reload().getTier()).isEqualTo(MemberTier.VIP);
        assertThat(tiers.limitOf(member.getId())).hasValue(10_000_000L);   // 승급하면 한도도 바로 오른다

        completedTransfer(props.vvipThreshold() - props.vipThreshold());
        tiers.onTransferCompleted(member.getId());
        assertThat(reload().getTier()).isEqualTo(MemberTier.VVIP);
        assertThat(tiers.limitOf(member.getId())).isEmpty();
    }

    @Test
    void onlyCompletedTransfersCountAndAPromotionNeverLowersTheTier() {
        transfer(props.vvipThreshold(), "REFUNDED");
        tiers.onTransferCompleted(member.getId());
        assertThat(reload().getTier()).as("환불·실패한 이체는 누적에 들어가지 않는다").isEqualTo(MemberTier.STANDARD);

        resetTo(MemberTier.VVIP, LocalDateTime.now(), LocalDateTime.now().minusDays(1));
        tiers.onTransferCompleted(member.getId());
        assertThat(reload().getTier()).as("누적이 적어도 승급 계산이 등급을 내리지는 않는다").isEqualTo(MemberTier.VVIP);
    }

    @Test
    void statusShowsProgressTowardsTheNextTier() {
        completedTransfer(3_000_000);

        var status = tiers.status(member.getId());

        assertThat(status.tier()).isEqualTo(MemberTier.STANDARD);
        assertThat(status.cumulative()).isEqualTo(3_000_000);
        assertThat(status.next()).isEqualTo(MemberTier.VIP);
        assertThat(status.remainingToNext()).isEqualTo(props.vipThreshold() - 3_000_000);
        resetTo(MemberTier.VVIP, LocalDateTime.now(), LocalDateTime.now().minusDays(1));
        assertThat(tiers.status(member.getId()).nextThreshold()).isEmpty();   // 최고 등급
    }

    // ---- 강등 ----

    @Test
    void notUsingTheAppForThreeMonthsLowersTheTierByOneStepAndRestartsTheCount() {
        LocalDateTime now = LocalDateTime.now();
        resetTo(MemberTier.VVIP, now.minusMonths(3).minusDays(1), now.minusMonths(5));
        completedTransfer(1_000);   // 기준 시각(5개월 전) 이후의 이체

        tiers.demoteInactive();

        Member after = reload();
        assertThat(after.getTier()).as("석 달 이용하지 않으면 한 단계").isEqualTo(MemberTier.VIP);
        assertThat(after.getTierBaselineAt()).as("누적은 강등된 시각부터 다시 센다").isAfter(now.minusMinutes(1));
        assertThat(tiers.status(member.getId()).cumulative()).isZero();
    }

    @Test
    void anActiveMemberIsNotDemotedAndTheStandardTierIsTheFloor() {
        LocalDateTime now = LocalDateTime.now();
        resetTo(MemberTier.VIP, now.minusMonths(2), now.minusMonths(5));
        tiers.demoteInactive();
        assertThat(reload().getTier()).as("석 달이 안 지났다").isEqualTo(MemberTier.VIP);

        resetTo(MemberTier.STANDARD, now.minusYears(2), now.minusYears(2));
        tiers.demoteInactive();
        assertThat(reload().getTier()).isEqualTo(MemberTier.STANDARD);
    }

    @Test
    void longerAbsenceLowersMoreStepsAndAnotherThreeMonthsLowersTheNext() {
        LocalDateTime now = LocalDateTime.now();
        resetTo(MemberTier.VVIP, now.minusMonths(7), now.minusMonths(8));
        tiers.demoteInactive();
        assertThat(reload().getTier()).as("7개월 = 석 달 × 2").isEqualTo(MemberTier.STANDARD);

        // 한 단계만 내려간 뒤에는 그 시각부터 다시 석 달을 센다
        resetTo(MemberTier.VVIP, now.minusMonths(4), now.minusMonths(8));
        tiers.demoteInactive();
        assertThat(reload().getTier()).isEqualTo(MemberTier.VIP);
        tiers.demoteInactive();
        assertThat(reload().getTier()).as("방금 내렸으므로 같은 날 또 내려가지 않는다").isEqualTo(MemberTier.VIP);
    }

    @Test
    void loggingInAfterALongAbsenceStillDemotesBeforeRefreshingTheActivityTime() {
        LocalDateTime now = LocalDateTime.now();
        resetTo(MemberTier.VIP, now.minusMonths(4), now.minusMonths(6));

        tiers.recordLogin(member);

        Member after = reload();
        assertThat(after.getTier()).as("로그인이 이용 시각을 새로 쓰기 전에 강등을 먼저 본다").isEqualTo(MemberTier.STANDARD);
        assertThat(after.getLastActiveAt()).isAfter(now.minusMinutes(1));
    }

    @Test
    void usingTheAppRefreshesTheActivityTimeAtMostOncePerDay() {
        LocalDateTime now = LocalDateTime.now();
        resetTo(MemberTier.VIP, now.minusHours(2), now.minusDays(1));
        tiers.recordActivity(member.getId());
        assertThat(reload().getLastActiveAt()).as("하루 안에는 다시 쓰지 않는다").isBefore(now.minusHours(1));

        resetTo(MemberTier.VIP, now.minusDays(2), now.minusDays(3));
        tiers.recordActivity(member.getId());
        assertThat(reload().getLastActiveAt()).isAfter(now.minusMinutes(1));
    }
}
