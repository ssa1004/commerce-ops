package io.minishop.order.dlq

import java.time.Instant

/**
 * DLQ 한 건의 in-memory 모델. 백엔드 (Kafka DLQ 토픽 / outbox FAILED row / saga error log 등) 가
 * 무엇이든 같은 형태로 노출 — 관리 콘솔 UI 가 source 마다 다른 DTO 를 그리지 않도록.
 *
 * 필드 의미:
 * - [messageId] — DLQ 내부의 유일 키. source 별로 형식이 다르다 (outbox 는 row id, kafka 는
 *   `{topic}:{partition}:{offset}`). 콘솔에서 그대로 표시.
 * - [source] — 1 차 분류 ([DlqSource]).
 * - [topic] — Kafka source 의 원 topic (예: `order.events`). source 가 SAGA / OUTBOX 면 가상 토픽
 *   문자열 (예: `internal:saga` / `internal:outbox`).
 * - [orderId] — order-service 특유 차원. inbox/outbox/saga 등 도메인 키와 묶이면 채워지고,
 *   알 수 없으면 null.
 * - [customerId] — 콘솔의 부가 차원 (해당 사용자가 보고하는 사고와 매칭 용도). 평문이 아닌
 *   surrogate. PII 마스킹은 audit 로그 / 화면 출력 단에서 별도 처리.
 * - [errorType] — exception 분류 (예: `KAFKA_SEND_TIMEOUT` / `SAGA_TRANSITION_REJECTED`).
 *   필터링 정렬에 사용. 자유 문자열이 아니라 정해진 카탈로그여야 통계 차원이 안정.
 * - [errorMessage] — 사람이 읽는 에러 메시지의 짧은 요약. 전체 stack trace 는 별도 [payload].
 * - [payload] — 원 메시지 본문 (직렬화 문자열). bulk replay 시 그대로 다시 발행.
 * - [headers] — Kafka 헤더 등 부수 정보 (`x-idempotency-key`, `traceparent`, `attempt` 등).
 * - [firstFailedAt] / [lastFailedAt] / [attempts] — 운영자가 "오래 묵은 사고 / 자주 실패하는
 *   사고" 를 즉시 판단할 수 있도록 세 가지를 같이 노출.
 *
 * 모든 필드는 *불변* (Kotlin data class) — 콘솔이 같은 객체를 여러 군데서 봐도 race 없음.
 */
data class DlqMessage(
    val messageId: String,
    val source: DlqSource,
    val topic: String,
    val orderId: Long?,
    val customerId: Long?,
    val errorType: String,
    val errorMessage: String,
    val payload: String,
    val headers: Map<String, String>,
    val firstFailedAt: Instant,
    val lastFailedAt: Instant,
    val attempts: Int,
)
