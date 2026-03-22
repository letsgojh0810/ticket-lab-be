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
import com.example.ticket.infrastructure.redis.service.SeatCacheService;
import com.example.ticket.infrastructure.redis.service.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /**
     * PG에 결제 요청 후 Payment(PENDING) 저장, transactionKey 반환
     */
    @Transactional
    public String requestPayment(Long reservationId, String cardType, String cardNo, Long amount, String userId) {
        // 1. Reservation 조회 및 HELD 상태 확인
        Reservation reservation = reservationService.findById(reservationId);
        if (reservation.getStatus() != ReservationStatus.HELD) {
            throw new IllegalStateException("결제 가능한 상태의 예약이 아닙니다. 현재 상태: " + reservation.getStatus());
        }

        // 2. PG 호출 (동기)
        Map<?, ?> pgResponse = pgWebClient.post()
                .uri("/api/v1/payments")
                .header("X-USER-ID", userId)
                .bodyValue(Map.of(
                        "orderId", "ORDER-" + reservationId,
                        "cardType", cardType,
                        "cardNo", cardNo,
                        "amount", amount,
                        "callbackUrl", "http://localhost:8080/api/v1/payments/callback"
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        // PG 응답 구조: { "meta": {...}, "data": { "transactionKey": "...", ... } }
        if (pgResponse == null || !pgResponse.containsKey("data")) {
            throw new IllegalStateException("PG 응답이 올바르지 않습니다.");
        }

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) pgResponse.get("data");
        String transactionKey = (String) data.get("transactionKey");

        // 3. Payment PENDING 저장
        Payment payment = Payment.create(reservationId, transactionKey, amount, cardType, cardNo);
        paymentRepository.save(payment);

        log.info("결제 요청 완료. reservationId={}, transactionKey={}", reservationId, transactionKey);
        return transactionKey;
    }

    /**
     * PG 콜백 처리: 결제 성공/실패에 따라 예약 확정 또는 취소
     */
    @Transactional
    public void handleCallback(String transactionKey, String status) {
        // 1. Payment 조회
        Payment payment = paymentRepository.findByTransactionKey(transactionKey)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 transactionKey: " + transactionKey));

        Long reservationId = payment.getReservationId();
        Reservation reservation = reservationService.findById(reservationId);
        Long seatId = reservation.getSeatId();
        Long userId = reservation.getUserId();

        // 좌석 정보 조회 (이벤트 발행용)
        String seatNumber = seatRepository.findById(seatId)
                .map(seat -> seat.getSeatNumber())
                .orElse("UNKNOWN");

        if ("SUCCESS".equalsIgnoreCase(status)) {
            // 2-A. 결제 성공
            payment.success();
            reservationService.confirm(reservationId);
            seatCacheService.updateSeatStatus(seatId, SeatStatus.CONFIRMED.name(), 0);
            eventProducer.publish(ReservationEvent.success(reservationId, userId, seatId, seatNumber));
            waitingQueueService.removeActiveUser(userId);

            log.info("결제 성공 처리 완료. reservationId={}, transactionKey={}", reservationId, transactionKey);

        } else {
            // 2-B. 결제 실패
            payment.fail();
            reservation.cancel();
            seatCacheService.deleteSeatStatus(seatId);
            eventProducer.publish(ReservationEvent.failed(userId, seatId, seatNumber));
            waitingQueueService.removeActiveUser(userId);

            log.info("결제 실패 처리 완료. reservationId={}, transactionKey={}", reservationId, transactionKey);
        }
    }
}
