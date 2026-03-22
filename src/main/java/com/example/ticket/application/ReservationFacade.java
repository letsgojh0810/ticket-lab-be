package com.example.ticket.application;

import com.example.ticket.domain.reservation.Reservation;
import com.example.ticket.domain.reservation.ReservationService;
import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatRepository;
import com.example.ticket.domain.seat.SeatStatus;
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

    private static final String LOCK_KEY = "lock:seat:";

    /**
     * 좌석 선점 후 reservationId 반환 (결제는 PaymentFacade에서 별도 처리)
     */
    public Long reserve(Long seatId, Long userId) {
        Timer.Sample reservationSample = Timer.start();
        metricsConfig.incrementActiveReservations();

        // [STEP 1] Active User 확인 (대기열을 통과한 사용자만 예약 가능)
        if (!waitingQueueService.isAllowed(userId)) {
            metricsConfig.decrementActiveReservations();
            throw new IllegalStateException("대기열 진입이 필요합니다. /api/v1/queue/enter를 먼저 호출하세요.");
        }

        RLock lock = redissonClient.getLock(LOCK_KEY + seatId);

        try {
            // [STEP 2] 좌석 기본 정보 조회
            Seat seat = seatRepository.findById(seatId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 좌석입니다."));

            // [STEP 3] 분산 락 획득 (1초 대기, 2초 점유)
            Timer.Sample lockSample = Timer.start();
            if (!lock.tryLock(1, 2, TimeUnit.SECONDS)) {
                lockSample.stop(metricsConfig.getLockAcquisitionTimer());
                metricsConfig.getLockTimeoutCounter().increment();
                metricsConfig.getReservationFailedCounter().increment();
                metricsConfig.decrementActiveReservations();
                throw new IllegalStateException("현재 접속자가 많아 처리에 실패했습니다. 잠시 후 다시 시도해 주세요.");
            }
            lockSample.stop(metricsConfig.getLockAcquisitionTimer());

            try {
                // [STEP 4] 이선좌 필터링 - 레디스 캐시 조회
                String currentStatus = seatCacheService.getSeatStatus(seatId);

                if (SeatStatus.SELECTED.name().equals(currentStatus)) {
                    throw new IllegalStateException("현재 다른 사용자가 결제 진행 중입니다.");
                }
                if (SeatStatus.CONFIRMED.name().equals(currentStatus)) {
                    throw new IllegalStateException("이미 판매가 완료된 좌석입니다.");
                }

                // [STEP 5] 레디스 임시 선점 (5분간 SELECTED 상태 유지)
                seatCacheService.updateSeatStatus(seatId, SeatStatus.SELECTED.name(), 5);

                // [STEP 6] DB에 HELD 상태 Reservation 저장
                Reservation reservation = reservationService.hold(seatId, userId);

                // [STEP 7] 좌석 선점 상태 브로드캐스트
                seatStatusPublisher.publish(seatId, seat.getSeatNumber(), "SELECTED");

                log.info("좌석 {} 선점 완료. reservationId={}, userId={}", seatId, reservation.getId(), userId);

                metricsConfig.decrementActiveReservations();
                reservationSample.stop(metricsConfig.getReservationTimer());

                return reservation.getId();

            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metricsConfig.getReservationFailedCounter().increment();
            metricsConfig.decrementActiveReservations();
            throw new IllegalStateException("시스템 오류가 발생했습니다.");
        } catch (IllegalStateException e) {
            metricsConfig.getReservationFailedCounter().increment();
            metricsConfig.decrementActiveReservations();
            throw e;
        } catch (Exception e) {
            log.error("예약 과정 중 에러 발생: ", e);
            seatCacheService.deleteSeatStatus(seatId);
            metricsConfig.getReservationFailedCounter().increment();
            metricsConfig.decrementActiveReservations();
            throw new IllegalStateException(e.getMessage());
        }
    }
}
