package com.example.ticket.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MetricsConfig {

    private final Counter reservationSuccessCounter;
    private final Counter reservationFailedCounter;
    private final Counter paymentSuccessCounter;
    private final Counter paymentFailedCounter;
    private final Counter lockTimeoutCounter;
    private final Timer reservationTimer;
    private final Timer lockAcquisitionTimer;
    private final Timer paymentTimer;
    private final AtomicInteger activeReservations;

    public MetricsConfig(MeterRegistry registry, RedisTemplate<String, String> redisTemplate) {
        // 예약 성공/실패 카운터
        this.reservationSuccessCounter = Counter.builder("reservation.success.total")
                .description("Total successful reservations")
                .register(registry);

        this.reservationFailedCounter = Counter.builder("reservation.failed.total")
                .description("Total failed reservations")
                .register(registry);

        // 결제 성공/실패 카운터
        this.paymentSuccessCounter = Counter.builder("payment.success.total")
                .description("Total successful payments")
                .register(registry);

        this.paymentFailedCounter = Counter.builder("payment.failed.total")
                .description("Total failed payments")
                .register(registry);

        // 락 타임아웃 카운터
        this.lockTimeoutCounter = Counter.builder("lock.timeout.total")
                .description("Total lock acquisition timeouts")
                .register(registry);

        // 예약 처리 시간 타이머
        this.reservationTimer = Timer.builder("reservation.duration")
                .description("Time taken for reservation process")
                .register(registry);

        // 락 획득 시간 타이머
        this.lockAcquisitionTimer = Timer.builder("lock.acquisition.duration")
                .description("Time taken to acquire distributed lock")
                .register(registry);

        // 결제 처리 시간 타이머
        this.paymentTimer = Timer.builder("payment.duration")
                .description("Time taken for payment process")
                .register(registry);

        // 현재 진행 중인 예약 수
        this.activeReservations = new AtomicInteger(0);
        Gauge.builder("reservation.active.count", activeReservations, AtomicInteger::get)
                .description("Currently active reservations")
                .register(registry);

        // 대기열 길이 게이지
        Gauge.builder("queue.waiting.size", () -> {
            Long size = redisTemplate.opsForZSet().size("ticket:waiting:queue");
            return size != null ? size : 0;
        }).description("Current waiting queue size").register(registry);
    }

    public void incrementActiveReservations() {
        activeReservations.incrementAndGet();
    }

    public void decrementActiveReservations() {
        activeReservations.decrementAndGet();
    }

    public void recordReservationFailure() {
        reservationFailedCounter.increment();
    }

    public void recordLockTimeout() {
        lockTimeoutCounter.increment();
    }

    public void stopReservationTimer(Timer.Sample sample) {
        sample.stop(reservationTimer);
    }

    public void stopLockAcquisitionTimer(Timer.Sample sample) {
        sample.stop(lockAcquisitionTimer);
    }
}
