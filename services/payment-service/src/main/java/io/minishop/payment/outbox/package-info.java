/**
 * Phase 3 Step 3a 준비 자리 — payment-service 의 transactional outbox.
 *
 * <p>현재 {@link io.minishop.payment.kafka.PaymentEventPublisher} 는 트랜잭션 커밋 직후
 * {@code KafkaTemplate} 으로 직접 발행한다 (ADR-009 의 best-effort 경로). 그 윈도우에서
 * 프로세스가 죽으면 "DB 는 SUCCESS 인데 이벤트는 못 갔다" 가 드물게 가능하다.
 *
 * <p>후속 PR 의 격상 단계는 다음과 같다:
 * <ol>
 *   <li>{@code PaymentService.processPayment()} 를 같은 트랜잭션 안에서
 *       {@code outboxRepository.save(OutboxEvent.pending(...))} 로 바꾼다.</li>
 *   <li>order-service 의 {@code OutboxPoller} 와 동일한 패턴 (FOR UPDATE SKIP LOCKED + 송신 timeout
 *       + max attempts) 을 payment-service 에도 둔다 — 코드 재사용을 위해 추후 모듈화 검토.</li>
 *   <li>{@link io.minishop.payment.kafka.PaymentEventPublisher} 에서 KafkaTemplate 직접 호출 경로를 제거.</li>
 * </ol>
 *
 * <p>이 단계에서는 스키마 (V2__create_outbox.sql) 와 엔티티 매핑만 미리 둔다 — 어떤 코드 경로도
 * INSERT 하지 않으므로 테이블은 빈 상태로 유지되고, ddl-auto=validate 가 컬럼 타입 표류를 잡는 안전망 역할을 한다.
 */
package io.minishop.payment.outbox;
