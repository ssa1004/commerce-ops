package io.minishop.inventory.service;

import io.minishop.inventory.domain.Inventory;
import io.minishop.inventory.domain.InventoryReservation;
import io.minishop.inventory.exception.OutOfStockException;
import io.minishop.inventory.exception.ProductNotFoundException;
import io.minishop.inventory.exception.ReservationNotFoundException;
import io.minishop.inventory.repository.InventoryRepository;
import io.minishop.inventory.repository.InventoryReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * 락 → 트랜잭션 순서로 진입한다. (트랜잭션 → 락 순서로 두면 락 대기 동안 DB 커넥션을 잡고 있게 되어 풀 고갈 위험.)
 *
 * 멱등성:
 * - reserve: (orderId, productId)가 이미 RESERVED 또는 RELEASED면 그 reservation을 그대로 반환한다.
 *   같은 키로 두 번 호출돼도 재고가 두 번 차감되지 않는다.
 * - release: 동일 키가 RELEASED면 no-op. 한 번 더 와도 재고가 두 번 복구되지 않는다.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final DistributedLockService lockService;
    private final TransactionTemplate tx;

    public InventoryService(InventoryRepository inventoryRepository,
                            InventoryReservationRepository reservationRepository,
                            DistributedLockService lockService,
                            TransactionTemplate transactionTemplate) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.lockService = lockService;
        this.tx = transactionTemplate;
    }

    public ReservationOutcome reserve(Long productId, Long orderId, Integer quantity) {
        return lockService.withLock("product:" + productId,
                () -> tx.execute(s -> reserveInTx(productId, orderId, quantity)));
    }

    public ReservationOutcome release(Long productId, Long orderId) {
        return lockService.withLock("product:" + productId,
                () -> tx.execute(s -> releaseInTx(productId, orderId)));
    }

    @Transactional(readOnly = true)
    public Inventory getByProductId(Long productId) {
        return inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    private ReservationOutcome reserveInTx(Long productId, Long orderId, Integer quantity) {
        Optional<InventoryReservation> existing = reservationRepository.findByOrderIdAndProductId(orderId, productId);
        if (existing.isPresent()) {
            InventoryReservation r = existing.get();
            log.info("Reserve is idempotent: order={} product={} status={}", orderId, productId, r.getStatus());
            return new ReservationOutcome(r, true);
        }

        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (!inventory.canReserve(quantity)) {
            throw new OutOfStockException(productId, quantity, inventory.getAvailableQuantity());
        }
        inventory.reserve(quantity);

        InventoryReservation reservation = reservationRepository.save(
                InventoryReservation.reserve(productId, orderId, quantity)
        );
        return new ReservationOutcome(reservation, false);
    }

    private ReservationOutcome releaseInTx(Long productId, Long orderId) {
        InventoryReservation reservation = reservationRepository.findByOrderIdAndProductId(orderId, productId)
                .orElseThrow(() -> new ReservationNotFoundException(orderId, productId));

        if (reservation.isReleased()) {
            log.info("Release is idempotent: order={} product={} (already RELEASED)", orderId, productId);
            return new ReservationOutcome(reservation, true);
        }

        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        inventory.release(reservation.getQuantity());
        reservation.release();
        return new ReservationOutcome(reservation, false);
    }

    public record ReservationOutcome(InventoryReservation reservation, boolean idempotent) {}
}
