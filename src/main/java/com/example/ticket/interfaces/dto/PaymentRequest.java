package com.example.ticket.interfaces.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PaymentRequest {
    private Long reservationId;
    private String cardType;   // "SAMSUNG", "KB", "HYUNDAI"
    private String cardNo;     // "xxxx-xxxx-xxxx-xxxx"
    private Long amount;
}
