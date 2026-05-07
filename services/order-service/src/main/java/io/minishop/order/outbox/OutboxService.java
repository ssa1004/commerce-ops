package io.minishop.order.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 호출자의 트랜잭션에 합류해 outbox 테이블에 이벤트를 기록한다.
     * Order 변경과 같은 트랜잭션 안에서 호출되어야 두 변경이 원자적 (둘 다 커밋되거나 둘 다 롤백)
     * 이다. {@code Propagation.MANDATORY} 는 "반드시 호출자 트랜잭션이 있어야 한다 — 없으면 예외"
     * 라는 뜻으로, 실수로 트랜잭션 밖에서 호출되는 것을 방지한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String aggregateType, Long aggregateId, String eventType, String topic, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            repository.save(OutboxEvent.pending(aggregateType, aggregateId, eventType, topic, json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, e);
        }
    }
}
