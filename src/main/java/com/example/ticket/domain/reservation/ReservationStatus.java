package com.example.ticket.domain.reservation;

public enum ReservationStatus {
    HELD,       // 선점됨 (결제 대기)
    CONFIRMED,  // 예약 확정
    CANCELLED   // 취소
}
