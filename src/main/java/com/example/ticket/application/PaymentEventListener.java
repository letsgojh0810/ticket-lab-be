package com.example.ticket.application;

import com.example.ticket.domain.event.ReservationEvent;
import com.example.ticket.domain.seat.SeatStatus;
import com.example.ticket.infrastructure.kafka.ReservationEventProducer;
import com.example.ticket.infrastructure.redis.pubsub.SeatStatusPublisher;
import com.example.ticket.infrastructure.redis.service.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final ReservationEventProducer eventProducer;
    private final SeatStatusPublisher seatStatusPublisher;
    private final WaitingQueueService waitingQueueService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentResult(PaymentResultEvent event) {
        if (event.success()) {
            eventProducer.publish(ReservationEvent.success(
                    event.reservationId(), event.userId(), event.seatId(), event.seatNumber()));
            waitingQueueService.removeActiveUser(event.userId());
            seatStatusPublisher.publish(event.seatId(), event.seatNumber(), SeatStatus.CONFIRMED.name());
            log.info("결제 성공 후처리 완료. reservationId={}", event.reservationId());
        } else {
            eventProducer.publish(ReservationEvent.failed(event.userId(), event.seatId(), event.seatNumber()));
            waitingQueueService.removeActiveUser(event.userId());
            seatStatusPublisher.publish(event.seatId(), event.seatNumber(), SeatStatus.AVAILABLE.name());
            log.info("결제 실패 후처리 완료. reservationId={}", event.reservationId());
        }
    }
}
