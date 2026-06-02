package com.example.ticket.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PaymentRequest {
    @NotNull(message = "reservationId는 필수입니다.")
    private Long reservationId;
    @NotBlank(message = "cardType은 필수입니다.")
    private String cardType;   // "SAMSUNG", "KB", "HYUNDAI"
    @NotBlank(message = "cardNo는 필수입니다.")
    private String cardNo;     // "xxxx-xxxx-xxxx-xxxx"
    @NotNull @Positive(message = "amount는 양수여야 합니다.")
    private Long amount;
}
