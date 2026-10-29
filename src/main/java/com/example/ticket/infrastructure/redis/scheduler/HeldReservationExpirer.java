package com.example.ticket.infrastructure.redis.scheduler;

import com.example.ticket.domain.event.ReservationEvent;
import com.example.ticket.domain.reservation.Reservation;
import com.example.ticket.domain.reservation.ReservationRepository;
import com.example.ticket.domain.reservation.ReservationService;
import com.example.ticket.domain.reservation.ReservationStatus;
import com.example.ticket.domain.seat.Seat;
import com.example.ticket.domain.seat.SeatStatus;
import com.example.ticket.infrastructure.kafka.ReservationEventProducer;
import com.example.ticket.infrastructure.redis.pubsub.SeatStatusPublisher;
import com.example.ticket.infrastructure.redis.service.SeatCacheService;
import com.example.ticket.infrastructure.redis.service.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 결제 없이 TTL이 지난 HELD 예약을 만료시켜 좌석 락을 풀고 Active 슬롯을 반납한다.
 * TTL은 reservation.held-ttl-seconds 로 조정한다. (기본 5분)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HeldReservationExpirer {

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final SeatCacheService seatCacheService;
    private final SeatStatusPublisher seatStatusPublisher;
    private final WaitingQueueService waitingQueueService;
    private final ReservationEventProducer reservationEventProducer;

    @Value("${reservation.held-ttl-seconds:300}")
    private long heldTtlSeconds;

    @Scheduled(fixedDelay = 1000)
    public void expireHeldReservations() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(heldTtlSeconds);
        for (Reservation reservation : reservationRepository.findByStatusAndReservedAtBefore(ReservationStatus.HELD, cutoff)) {
            Seat seat = reservationService.expireHeld(reservation.getId());
            if (seat == null) {
                continue; // 그 사이 결제로 확정/취소됨
            }
            seatCacheService.deleteSeatStatus(seat.getId());
            waitingQueueService.removeActiveUser(reservation.getUserId());
            seatStatusPublisher.publish(seat.getId(), seat.getSeatNumber(), SeatStatus.AVAILABLE.name());
            reservationEventProducer.publish(
                    ReservationEvent.cancelled(reservation.getUserId(), seat.getId(), seat.getSeatNumber()));
            log.info("HELD TTL 만료 해제. reservationId={}, seatId={}, userId={}",
                    reservation.getId(), seat.getId(), reservation.getUserId());
        }
    }
}
