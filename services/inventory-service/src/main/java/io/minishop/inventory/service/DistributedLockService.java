package io.minishop.inventory.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.minishop.inventory.config.InventoryProperties;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    private final RedissonClient redisson;
    private final InventoryProperties props;
    private final MeterRegistry meterRegistry;

    public DistributedLockService(RedissonClient redisson, InventoryProperties props, MeterRegistry meterRegistry) {
        this.redisson = redisson;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

    public <T> T withLock(String key, Supplier<T> action) {
        String fullKey = props.keyPrefix() + ":" + key;
        RLock lock = redisson.getLock(fullKey);

        Timer.Sample acquireSample = Timer.start(meterRegistry);
        boolean acquired;
        try {
            acquired = lock.tryLock(props.waitMillis(), props.leaseMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordOutcome(acquireSample, "interrupted");
            throw new LockAcquisitionException("Interrupted while acquiring lock for " + fullKey, e);
        }

        if (!acquired) {
            recordOutcome(acquireSample, "timeout");
            throw new LockAcquisitionException("Lock acquisition timed out for " + fullKey);
        }
        recordOutcome(acquireSample, "acquired");

        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            } else {
                log.warn("Lock {} was no longer held when releasing — likely lease expired", fullKey);
            }
        }
    }

    private void recordOutcome(Timer.Sample sample, String outcome) {
        sample.stop(meterRegistry.timer("inventory.lock.acquire", Tags.of("outcome", outcome)));
    }

    public static class LockAcquisitionException extends RuntimeException {
        public LockAcquisitionException(String message) { super(message); }
        public LockAcquisitionException(String message, Throwable cause) { super(message, cause); }
    }
}
