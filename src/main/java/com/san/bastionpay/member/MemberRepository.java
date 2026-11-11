package com.san.bastionpay.member;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    /** 마지막 이용이 오래된(등급 변경 이후로도 오래된) 상위 등급 회원. 강등 후보를 좁히는 용도이고 판정은 서비스가 한다. */
    @Query("select m from Member m where m.tier <> :floor and m.lastActiveAt < :cutoff")
    java.util.List<Member> findDemotionCandidates(@Param("floor") MemberTier floor, @Param("cutoff") java.time.LocalDateTime cutoff);

    /** 마지막 이용 시각을 갱신한다. 최근에 이미 갱신했으면(threshold 이후) 건드리지 않는다. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update Member m set m.lastActiveAt = :now where m.id = :id and m.lastActiveAt < :threshold")
    int touchActivity(@Param("id") Long id, @Param("now") java.time.LocalDateTime now, @Param("threshold") java.time.LocalDateTime threshold);

    /** 위 검색에 등급 조건을 더한 것(tier 를 비우면 전체). */
    @Query("select m from Member m where "
            + "(:q is null or lower(m.nickname) like lower(concat('%', :q, '%')) escape '!' "
            + "or lower(m.email) like lower(concat('%', :q, '%')) escape '!') "
            + "and (:role is null or m.role = :role) and (:status is null or m.status = :status) and (:tier is null or m.tier = :tier) order by m.id")
    Page<Member> searchWithTier(@Param("q") String q, @Param("role") MemberRole role, @Param("status") MemberStatus status,
                                @Param("tier") MemberTier tier, Pageable pageable);

    long countByRoleAndStatus(MemberRole role, MemberStatus status);

    /**
     * 관리자 화면 검색. q 는 닉네임·이메일 부분 일치(이스케이프한 값을 넘긴다), role·status 는 비우면 전체.
     */
    @Query("select m from Member m where "
            + "(:q is null or lower(m.nickname) like lower(concat('%', :q, '%')) escape '!' "
            + "or lower(m.email) like lower(concat('%', :q, '%')) escape '!') "
            + "and (:role is null or m.role = :role) and (:status is null or m.status = :status) order by m.id")
    Page<Member> search(@Param("q") String q, @Param("role") MemberRole role, @Param("status") MemberStatus status, Pageable pageable);
}
