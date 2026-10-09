package com.llmgateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Điều phối suy luận Qwen duy nhất (Shared Single-Flight Mutex) trên toàn hệ thống.
 * Đảm bảo tại một thời điểm chỉ có DUY NHẤT 1 lượt suy luận gửi tới llama-server trên Samsung Note 10+,
 * tránh xung đột tài nguyên CPU/RAM giữa News Worker và Forecast Worker/Service.
 */
@Component
public class QwenInferenceCoordinator {

    private static final Logger log = LoggerFactory.getLogger(QwenInferenceCoordinator.class);

    private final ReentrantLock lock = new ReentrantLock(true); // Fair lock
    private volatile String currentHolder = null;
    private volatile long lockAcquiredAt = 0L;

    public boolean tryAcquire(String caller, long timeout, TimeUnit unit) throws InterruptedException {
        boolean acquired = lock.tryLock(timeout, unit);
        if (acquired) {
            this.currentHolder = caller;
            this.lockAcquiredAt = System.currentTimeMillis();
            log.info("QwenInferenceCoordinator: ĐÃ CẤP KHÓA SUY LUẬN cho '{}'", caller);
        } else {
            log.warn("QwenInferenceCoordinator: '{}' không thể lấy khóa trong {} {}, khóa đang giữ bởi '{}' ({} ms)",
                    caller, timeout, unit, currentHolder, (System.currentTimeMillis() - lockAcquiredAt));
        }
        return acquired;
    }

    public void release(String caller) {
        if (lock.isHeldByCurrentThread()) {
            this.currentHolder = null;
            this.lockAcquiredAt = 0L;
            lock.unlock();
            log.info("QwenInferenceCoordinator: '{}' ĐÃ GIẢI PHÓNG KHÓA SUY LUẬN", caller);
        }
    }

    public <T> T executeWithLock(String caller, Duration waitTimeout, Callable<T> task) throws Exception {
        boolean acquired = tryAcquire(caller, waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!acquired) {
            throw new IllegalStateException("Hệ thống suy luận Qwen đang bận xử lý tác vụ khác ('" + currentHolder + "'). Vui lòng thử lại sau.");
        }
        try {
            return task.call();
        } finally {
            release(caller);
        }
    }

    public boolean isLocked() {
        return lock.isLocked();
    }

    public String getCurrentHolder() {
        return currentHolder;
    }
}
