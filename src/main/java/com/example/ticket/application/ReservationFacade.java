package com.example.ticket.application;

import com.example.ticket.domain.event.ReservationEvent;
import com.example.ticket.domain.reservation.Reservation;
import com.example.ticket.domain.reservation.ReservationService;
import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import com.example.ticket.domain.seat.SeatStatus;
import com.example.ticket.infrastructure.kafka.ReservationEventProducer;
import com.example.ticket.infrastructure.redis.pubsub.SeatStatusPublisher;
import com.example.ticket.infrastructure.redis.service.SeatCacheService;
import com.example.ticket.infrastructure.redis.service.WaitingQueueService;
import com.example.ticket.config.MetricsConfig;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationFacade {

    private final RedissonClient redissonClient;
    private final ReservationService reservationService;
    private final SeatRepository seatRepository;
    private final WaitingQueueService waitingQueueService;
    private final SeatCacheService seatCacheService;
    private final MetricsConfig metricsConfig;
    private final SeatStatusPublisher seatStatusPublisher;
    private final ReservationEventProducer reservationEventProducer;

    private static final String LOCK_KEY = "lock:seat:";

    /**
     * 좌석 선점 후 reservationId 반환 (결제는 PaymentFacade에서 별도 처리)
     *
     * DB가 상태의 원천입니다. Redis는 분산 락(Redisson)과 선택적 캐시 용도로만 사용합니다.
     */
    public Long reserve(Long seatId, Long userId) {
        Timer.Sample reservationSample = Timer.start();
        metricsConfig.incrementActiveReservations();

        try {
            // [STEP 1] Active User 확인 (대기열을 통과한 사용자만 예약 가능)
            if (!waitingQueueService.isAllowed(userId)) {
                throw new IllegalStateException("대기열 진입이 필요합니다. /api/v1/queue/enter를 먼저 호출하세요.");
            }

            // [STEP 2] 좌석 존재 여부 사전 확인
            seatRepository.findById(seatId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));

            RLock lock = redissonClient.getLock(LOCK_KEY + seatId);

            // [STEP 3] 분산 락 획득 (1초 대기, 2초 점유)
            Timer.Sample lockSample = Timer.start();
            if (!lock.tryLock(1, 2, TimeUnit.SECONDS)) {
                metricsConfig.stopLockAcquisitionTimer(lockSample);
                metricsConfig.recordLockTimeout();
                metricsConfig.recordReservationFailure();
                throw new IllegalStateException("현재 접속자가 많아 처리에 실패했습니다. 잠시 후 다시 시도해 주세요.");
            }
            metricsConfig.stopLockAcquisitionTimer(lockSample);

            try {
                // [STEP 4] 락 획득 후 DB에서 최신 좌석 상태 확인 (DB가 원천)
                Seat seat = seatRepository.findById(seatId)
                        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));
                validateSeatNotTaken(seat);

                // [STEP 5] DB 상태 변경 (AVAILABLE → SELECTED) + Reservation HELD 저장
                Reservation reservation = reservationService.hold(seatId, userId);

                // [STEP 6] Redis 캐시 업데이트 (선택적 fast-check 용도; 실패해도 DB가 정합성 보장)
                try {
                    seatCacheService.updateSeatStatus(seatId, SeatStatus.SELECTED.name(), 5);
                } catch (Exception cacheEx) {
                    log.warn("Redis 캐시 업데이트 실패 (무시됨). seatId={}: {}", seatId, cacheEx.getMessage());
                }

                // [STEP 7] 좌석 선점 상태 브로드캐스트
                seatStatusPublisher.publish(seatId, seat.getSeatNumber(), SeatStatus.SELECTED.name());
                log.info("좌석 {} 선점 완료. reservationId={}, userId={}", seatId, reservation.getId(), userId);
                metricsConfig.stopReservationTimer(reservationSample);

                return reservation.getId();

            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metricsConfig.recordReservationFailure();
            throw new IllegalStateException("시스템 오류가 발생했습니다.");
        } catch (IllegalStateException e) {
            metricsConfig.recordReservationFailure();
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("예약 과정 중 에러 발생: ", e);
            metricsConfig.recordReservationFailure();
            throw new IllegalStateException(e.getMessage());
        } finally {
            metricsConfig.decrementActiveReservations();
        }
    }

    /**
     * 예약 취소: 좌석 해제 + Redis 삭제 + Kafka 발행 + SSE 브로드캐스트
     */
    public void cancel(Long seatId, Long userId) {
        Seat seat = reservationService.cancel(seatId, userId);
        seatCacheService.deleteSeatStatus(seatId);
        reservationEventProducer.publish(ReservationEvent.cancelled(userId, seatId, seat.getSeatNumber()));
        seatStatusPublisher.publish(seatId, seat.getSeatNumber(), SeatStatus.AVAILABLE.name());
    }

    private void validateSeatNotTaken(Seat seat) {
        if (seat.getStatus() == SeatStatus.SELECTED) {
            throw new IllegalStateException("현재 다른 사용자가 결제 진행 중입니다.");
        }
        if (seat.getStatus() == SeatStatus.CONFIRMED) {
            throw new IllegalStateException("이미 판매가 완료된 좌석입니다.");
        }
    }
}
