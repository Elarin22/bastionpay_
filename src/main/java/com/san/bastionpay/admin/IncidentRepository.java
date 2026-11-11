package com.san.bastionpay.admin;

import com.san.bastionpay.audit.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findBySrcIpAndStatus(String srcIp, Incident.Status status);

    /** 같은 IP 에서 마지막으로 종료된 티켓. 새 티켓이 이미 처리한 사건을 다시 흡수하지 않게 한다. */
    Optional<Incident> findFirstBySrcIpAndStatusAndClosedAtIsNotNullOrderByClosedAtDesc(String srcIp, Incident.Status status);

    @Query("select i from Incident i where (:status is null or i.status = :status) and (:severity is null or i.severity = :severity) "
            + "and (:ip is null or i.srcIp like concat('%', :ip, '%') escape '!') order by i.updatedAt desc, i.id desc")
    Page<Incident> search(@Param("status") Incident.Status status, @Param("severity") Severity severity, @Param("ip") String ip, Pageable pageable);

    @Query("select i.status as key, count(i) as cnt from Incident i group by i.status")
    List<KeyCount> countByStatus();

    @Query("select i.severity as key, count(i) as cnt from Incident i where i.status = 'OPEN' group by i.severity")
    List<KeyCount> openBySeverity();

    interface KeyCount {
        Object getKey();

        long getCnt();
    }
}
