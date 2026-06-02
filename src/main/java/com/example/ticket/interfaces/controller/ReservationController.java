package com.example.ticket.interfaces.controller;

import com.example.ticket.application.ReservationFacade;
import com.example.ticket.interfaces.dto.ReservationRequest;
import com.example.ticket.interfaces.dto.ReservationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationFacade reservationFacade;

    @PostMapping("/reserve")
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReservationRequest request) {
        Long reservationId = reservationFacade.reserve(request.getSeatId(), request.getUserId());
        return ResponseEntity.ok(ReservationResponse.reserved(reservationId));
    }

    @PostMapping("/cancel")
    public ResponseEntity<ReservationResponse> cancel(
            @Valid @RequestBody ReservationRequest request,
            Authentication authentication) {
        reservationFacade.cancel(request.getSeatId(), authentication.getName());
        return ResponseEntity.ok(ReservationResponse.ok("취소가 완료되었습니다."));
    }
}
