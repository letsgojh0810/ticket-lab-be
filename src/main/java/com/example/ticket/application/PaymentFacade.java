package com.example.ticket.application;

import com.example.ticket.domain.event.ReservationEvent;
import com.example.ticket.domain.payment.Payment;
import com.example.ticket.domain.payment.PaymentRepository;
import com.example.ticket.domain.reservation.Reservation;
import com.example.ticket.domain.reservation.ReservationStatus;
import com.example.ticket.domain.reservation.ReservationService;
import com.example.ticket.domain.seat.SeatRepository;
import com.example.ticket.domain.seat.SeatStatus;
import com.example.ticket.infrastructure.kafka.ReservationEventProducer;
import com.example.ticket.infrastructure.redis.pubsub.SeatStatusPublisher;
import com.example.ticket.infrastructure.redis.service.SeatCacheService;
import com.example.ticket.infrastructure.redis.service.WaitingQueueService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFacade {

    @Qualifier("pgWebClient")
    private final WebClient pgWebClient;

    private final PaymentRepository paymentRepository;
    private final ReservationService reservationService;
    private final SeatRepository seatRepository;
    private final SeatCacheService seatCacheService;
    private final WaitingQueueService waitingQueueService;
    private final ReservationEventProducer eventProducer;
    private final SeatStatusPublisher seatStatusPublisher;

    @Value("${payment.callback-url}")
    private String callbackUrl;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PgResponse {
        private PgData data;

        @Getter
        @NoArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class PgData {
            private String transactionKey;
        }
    }

    /**
     * PG에 결제 요청 후 Payment(PENDING) 저장, transactionKey 반환
     */
    @Transactional
    public String requestPayment(Long reservationId, String cardType, String cardNo, Long amount, String userId) {
        Reservation reservation = reservationService.findById(reservationId);
        if (reservation.getStatus() != ReservationStatus.HELD) {
            throw new IllegalStateException("결제 가능한 상태의 예약이 아닙니다. 현재 상태: " + reservation.getStatus());
        }

        PgResponse pgResponse = pgWebClient.post()
                .uri("/api/v1/payments")
                .header("X-USER-ID", userId)
                .bodyValue(Map.of(
                        "orderId", "ORDER-" + reservationId,
                        "cardType", cardType,
                        "cardNo", cardNo,
                        "amount", amount,
                        "callbackUrl", callbackUrl
                ))
                .retrieve()
                .bodyToMono(PgResponse.class)
                .block();

        if (pgResponse == null || pgResponse.getData() == null || pgResponse.getData().getTransactionKey() == null) {
            throw new IllegalStateException("PG 응답이 올바르지 않습니다.");
        }

        String transactionKey = pgResponse.getData().getTransactionKey();
        paymentRepository.save(Payment.create(reservationId, transactionKey, amount, cardType, cardNo));

        log.info("결제 요청 완료. reservationId={}, transactionKey={}", reservationId, transactionKey);
        return transactionKey;
    }

    /**
     * PG 콜백 처리: 결제 성공/실패에 따라 예약 확정 또는 취소
     */
    @Transactional
    public void handleCallback(String transactionKey, String status) {
        Payment payment = paymentRepository.findByTransactionKey(transactionKey)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 transactionKey: " + transactionKey));

        Reservation reservation = reservationService.findById(payment.getReservationId());
        Long seatId = reservation.getSeatId();
        Long userId = reservation.getUserId();
        String seatNumber = seatRepository.findById(seatId)
                .map(seat -> seat.getSeatNumber())
                .orElse("UNKNOWN");

        if ("SUCCESS".equalsIgnoreCase(status)) {
            onPaymentSuccess(payment, reservation, seatId, userId, seatNumber);
        } else {
            onPaymentFailure(payment, reservation, seatId, userId, seatNumber);
        }
    }

    private void onPaymentSuccess(Payment payment, Reservation reservation, Long seatId, Long userId, String seatNumber) {
        payment.success();
        reservationService.confirm(reservation.getId());
        seatCacheService.updateSeatStatus(seatId, SeatStatus.CONFIRMED.name(), 0);
        eventProducer.publish(ReservationEvent.success(reservation.getId(), userId, seatId, seatNumber));
        waitingQueueService.removeActiveUser(userId);
        seatStatusPublisher.publish(seatId, seatNumber, SeatStatus.CONFIRMED.name());
        log.info("결제 성공 처리 완료. reservationId={}", reservation.getId());
    }

    private void onPaymentFailure(Payment payment, Reservation reservation, Long seatId, Long userId, String seatNumber) {
        payment.fail();
        reservation.cancel();
        reservationService.releaseSeat(seatId);
        seatCacheService.deleteSeatStatus(seatId);
        eventProducer.publish(ReservationEvent.failed(userId, seatId, seatNumber));
        waitingQueueService.removeActiveUser(userId);
        seatStatusPublisher.publish(seatId, seatNumber, SeatStatus.AVAILABLE.name());
        log.info("결제 실패 처리 완료. reservationId={}", reservation.getId());
    }
}
