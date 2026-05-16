package io.minishop.inventory.service

import io.minishop.inventory.domain.Inventory
import io.minishop.inventory.domain.InventoryReservation
import io.minishop.inventory.exception.OutOfStockException
import io.minishop.inventory.exception.ProductNotFoundException
import io.minishop.inventory.exception.ReservationNotFoundException
import io.minishop.inventory.kafka.InventoryEventPublisher
import io.minishop.inventory.kafka.dto.InventoryEvent
import io.minishop.inventory.repository.InventoryRepository
import io.minishop.inventory.repository.InventoryReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/**
 * 락 → 트랜잭션 순서로 진입한다. (반대로 트랜잭션 → 락 순서면 락을 기다리는 동안 DB 커넥션을
 * 그대로 쥐고 있어 커넥션 풀이 빨리 마름.)
 *
 * 멱등성 (같은 요청이 두 번 와도 한 번 처리한 결과와 같음):
 * - reserve: (orderId, productId) 가 이미 RESERVED 또는 RELEASED 면 그 reservation 을 그대로 반환.
 *   같은 키로 두 번 호출돼도 재고가 두 번 차감되지 않는다.
 * - release: 같은 키가 이미 RELEASED 면 아무것도 안 함. 한 번 더 와도 재고가 두 번 복구되지 않는다.
 */
@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository,
    private val reservationRepository: InventoryReservationRepository,
    private val lockService: DistributedLockService,
    transactionTemplate: TransactionTemplate,
    private val eventPublisher: InventoryEventPublisher,
) {
    private val tx: TransactionTemplate = transactionTemplate

    fun reserve(productId: Long, orderId: Long, quantity: Int): ReservationOutcome {
        val outcome = lockService.withLock("product:$productId") {
            tx.execute { reserveInTx(productId, orderId, quantity) }!!
        }
        eventPublisher.publish(InventoryEvent.reserved(outcome.reservation, outcome.idempotent))
        return outcome
    }

    fun release(productId: Long, orderId: Long): ReservationOutcome {
        val outcome = lockService.withLock("product:$productId") {
            tx.execute { releaseInTx(productId, orderId) }!!
        }
        eventPublisher.publish(InventoryEvent.released(outcome.reservation, outcome.idempotent))
        return outcome
    }

    @Transactional(readOnly = true)
    fun getByProductId(productId: Long): Inventory =
        inventoryRepository.findByProductId(productId)
            .orElseThrow { ProductNotFoundException(productId) }

    private fun reserveInTx(productId: Long, orderId: Long, quantity: Int): ReservationOutcome {
        val existing = reservationRepository.findByOrderIdAndProductId(orderId, productId)
        if (existing.isPresent) {
            val r = existing.get()
            log.info("Reserve is idempotent: order={} product={} status={}", orderId, productId, r.status)
            return ReservationOutcome(r, true)
        }

        val inventory = inventoryRepository.findByProductId(productId)
            .orElseThrow { ProductNotFoundException(productId) }

        if (!inventory.canReserve(quantity)) {
            throw OutOfStockException(productId, quantity, inventory.availableQuantity ?: 0)
        }
        inventory.reserve(quantity)

        val reservation = reservationRepository.save(
            InventoryReservation.reserve(productId, orderId, quantity),
        )
        return ReservationOutcome(reservation, false)
    }

    private fun releaseInTx(productId: Long, orderId: Long): ReservationOutcome {
        val reservation = reservationRepository.findByOrderIdAndProductId(orderId, productId)
            .orElseThrow { ReservationNotFoundException(orderId, productId) }

        if (reservation.isReleased()) {
            log.info("Release is idempotent: order={} product={} (already RELEASED)", orderId, productId)
            return ReservationOutcome(reservation, true)
        }

        val inventory = inventoryRepository.findByProductId(productId)
            .orElseThrow { ProductNotFoundException(productId) }
        inventory.release(reservation.quantity ?: 0)
        reservation.release()
        return ReservationOutcome(reservation, false)
    }

    /**
     * Java record → Kotlin `@JvmRecord data class` 로 변환. Java 호출자 (e.g.
     * InventoryController) 의 `outcome.reservation()` / `outcome.idempotent()` 호출
     * 시그니처 그대로 유지.
     */
    @JvmRecord
    data class ReservationOutcome(val reservation: InventoryReservation, val idempotent: Boolean)

    companion object {
        private val log = LoggerFactory.getLogger(InventoryService::class.java)
    }
}
