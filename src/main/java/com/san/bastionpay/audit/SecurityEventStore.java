package com.san.bastionpay.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보안 이벤트 DB 저장. 호출한 업무 트랜잭션이 롤백돼도 이벤트는 남아야 하므로 별도 트랜잭션으로 저장한다.
 */
@Component
public class SecurityEventStore {

    private final SecurityEventRepository repository;
    private final SecurityProperties props;

    public SecurityEventStore(SecurityEventRepository repository, SecurityProperties props) {
        this.repository = repository;
        this.props = props;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(SecurityEvent event) {
        if (!props.eventDbEnabled()) return;
        repository.save(new SecurityEventEntity(event));
    }
}
