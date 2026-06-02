package com.example.ticket.application;

import com.example.ticket.domain.payment.Payment;
import com.example.ticket.domain.payment.PaymentRepository;
import com.example.ticket.domain.payment.PaymentStatus;
import com.example.ticket.domain.reservation.Reservation;
import com.example.ticket.domain.reservation.ReservationStatus;
import com.example.ticket.domain.reservation.ReservationService;
import com.example.ticket.domain.seat.SeatRepository;
import com.example.ticket.domain.seat.SeatStatus;
import com.example.ticket.domain.user.User;
import com.example.ticket.domain.user.UserRepository;
import com.example.ticket.infrastructure.redis.service.SeatCacheService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

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
    public String requestPayment(Long reservationId, String cardType, String cardNo, Long amount, String userEmail) {
        Reservation reservation = reservationService.findById(reservationId);
        if (reservation.getStatus() != ReservationStatus.HELD) {
            throw new IllegalStateException("결제 가능한 상태의 예약이 아닙니다. 현재 상태: " + reservation.getStatus());
        }

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));
        if (!reservation.getUserId().equals(user.getId())) {
            throw new IllegalStateException("본인의 예약만 결제할 수 있습니다.");
        }

        PgResponse pgResponse = pgWebClient.post()
                .uri("/api/v1/payments")
                .header("X-USER-ID", String.valueOf(user.getId()))
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

        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.warn("이미 처리된 콜백 - 멱등 처리. transactionKey={}, 상태={}", transactionKey, payment.getStatus());
            return;
        }

        Reservation reservation = reservationService.findById(payment.getReservationId());
        Long seatId = reservation.getSeatId();
        Long userId = reservation.getUserId();
        String seatNumber = seatRepository.findById(seatId)
                .map(seat -> seat.getSeatNumber())
                .orElse("UNKNOWN");

        boolean success = "SUCCESS".equalsIgnoreCase(status);
        if (success) {
            onPaymentSuccess(payment, reservation, seatId);
        } else {
            onPaymentFailure(payment, reservation, seatId);
        }

        // DB 커밋 후 Kafka/SSE/큐 후처리 — PaymentEventListener가 AFTER_COMMIT에 처리
        eventPublisher.publishEvent(new PaymentResultEvent(success, reservation.getId(), userId, seatId, seatNumber));
    }

    private void onPaymentSuccess(Payment payment, Reservation reservation, Long seatId) {
        payment.success();
        reservationService.confirm(reservation.getId());
        seatCacheService.updateSeatStatus(seatId, SeatStatus.CONFIRMED.name(), 0);
        log.info("결제 성공 DB 처리 완료. reservationId={}", reservation.getId());
    }

    private void onPaymentFailure(Payment payment, Reservation reservation, Long seatId) {
        payment.fail();
        reservation.cancel();
        reservationService.releaseSeat(seatId);
        seatCacheService.deleteSeatStatus(seatId);
        log.info("결제 실패 DB 처리 완료. reservationId={}", reservation.getId());
    }
}
