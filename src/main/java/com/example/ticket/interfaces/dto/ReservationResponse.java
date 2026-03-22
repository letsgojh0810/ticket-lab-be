package com.example.ticket.interfaces.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Builder
public class ReservationResponse {
    private boolean success;
    private String message;
    private Long rank;
    private Long reservationId;

    public static ReservationResponse ok(String message) {
        return new ReservationResponse(true, message, null, null);
    }

    public static ReservationResponse reserved(Long reservationId) {
        return new ReservationResponse(true, "좌석 선점 완료", null, reservationId);
    }

    public static ReservationResponse waiting(Long rank) {
        return new ReservationResponse(false, "현재 대기 중입니다.", rank, null);
    }

    public static ReservationResponse fail(String message) {
        return new ReservationResponse(false, message, null, null);
    }
}
