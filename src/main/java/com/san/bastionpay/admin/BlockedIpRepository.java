package com.san.bastionpay.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlockedIpRepository extends JpaRepository<BlockedIp, String> {

    /** IP·사유·차단자 부분 일치 검색(q 는 이스케이프한 값, 비우면 전체). 최근에 차단한 것부터. */
    @Query("select b from BlockedIp b where :q is null or b.ip like concat('%', :q, '%') escape '!' "
            + "or lower(b.reason) like lower(concat('%', :q, '%')) escape '!' "
            + "or lower(b.blockedBy) like lower(concat('%', :q, '%')) escape '!' order by b.blockedAt desc, b.ip")
    Page<BlockedIp> search(@Param("q") String q, Pageable pageable);
}
