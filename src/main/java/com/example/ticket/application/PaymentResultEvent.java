package com.example.ticket.application;

public record PaymentResultEvent(
        boolean success,
        Long reservationId,
        Long userId,
        Long seatId,
        String seatNumber
) {}
