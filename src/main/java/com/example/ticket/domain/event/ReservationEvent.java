package com.example.ticket.domain.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ReservationEvent {
    private Long reservationId;
    private Long userId;
    private Long seatId;
    private String seatNumber;
    private LocalDateTime reservedAt;
    private EventType eventType;

    public enum EventType {
        RESERVATION_SUCCESS,  // 예약 성공
        RESERVATION_FAILED,   // 예약 실패
        RESERVATION_CANCELLED // 예약 취소
    }

    // 예약 성공 이벤트 생성 팩토리 메서드
    public static ReservationEvent success(Long reservationId, Long userId, Long seatId, String seatNumber) {
        return new ReservationEvent(
                reservationId,
                userId,
                seatId,
                seatNumber,
                LocalDateTime.now(),
                EventType.RESERVATION_SUCCESS
        );
    }

    // 예약 실패 이벤트 생성 팩토리 메서드
    public static ReservationEvent failed(Long userId, Long seatId, String seatNumber) {
        return new ReservationEvent(
                null,
                userId,
                seatId,
                seatNumber,
                LocalDateTime.now(),
                EventType.RESERVATION_FAILED
        );
    }

    // 예약 실패 이벤트 생성 팩토리 메서드
    public static ReservationEvent cancelled(Long userId, Long seatId, String seatNumber) {
        return new ReservationEvent(
                null,
                userId,
                seatId,
                seatNumber,
                LocalDateTime.now(),
                EventType.RESERVATION_CANCELLED
        );
    }

}
