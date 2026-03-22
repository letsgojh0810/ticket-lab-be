package com.example.ticket.interfaces.controller;

import com.example.ticket.application.ReservationFacade;
import com.example.ticket.domain.reservation.ReservationService;
import com.example.ticket.interfaces.dto.ReservationRequest;
import com.example.ticket.interfaces.dto.ReservationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationFacade reservationFacade;
    private final ReservationService reservationService;

    /**
     * 좌석 선점 (Active User만 호출 가능)
     * POST /api/v1/reservations/reserve
     *
     * 사전 조건: /api/v1/queue/enter를 통해 대기열 진입 필수
     * 응답: reservationId (결제 요청 시 사용)
     */
    @PostMapping("/reserve")
    public ResponseEntity<ReservationResponse> reserve(@RequestBody ReservationRequest request) {
        try {
            Long reservationId = reservationFacade.reserve(request.getSeatId(), request.getUserId());
            return ResponseEntity.ok(ReservationResponse.reserved(reservationId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ReservationResponse.fail(e.getMessage()));
        }
    }

    // 취소하기
    @PostMapping("/cancel")
    public ResponseEntity<ReservationResponse> cancel(@RequestBody ReservationRequest request) {
        String result = reservationService.cancel(request.getSeatId(), request.getUserId());
        return ResponseEntity.ok(ReservationResponse.ok(result));
    }
}
