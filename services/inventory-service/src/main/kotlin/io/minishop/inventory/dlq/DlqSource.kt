package io.minishop.inventory.dlq

/**
 * inventory-service 에서 DLQ (Dead Letter Queue — 처리 실패 메시지가 격리되는 별도 토픽/저장소) 로
 * 격리될 수 있는 source 의 분류.
 *
 * - RESERVE_FAILED — `/inventories/reserve` 의 reserve 처리 실패 (Redisson 분산락 timeout,
 *   JPA `@Version` 충돌 후 모두 retry 소진 등).
 * - RELEASE_FAILED — `/inventories/release` 의 release 처리 실패.
 * - KAFKA_CONSUME — Kafka consumer (다른 서비스 이벤트 처리) 의 실패가 적재.
 * - OUTBOX — inventory.events 발행 outbox 의 영구 실패 행 (status=FAILED).
 *
 * bid-ask-marketplace ADR-0028 의 source 분리 패턴과 동일 — 한 번에 한 source 만 bulk 처리.
 */
enum class DlqSource {
    RESERVE_FAILED,
    RELEASE_FAILED,
    KAFKA_CONSUME,
    OUTBOX,
}
