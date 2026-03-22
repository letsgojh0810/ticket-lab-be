package com.example.ticket.domain.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long reservationId;

    private String transactionKey;

    private Long amount;

    private String cardType;

    private String cardNo;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private LocalDateTime createdAt;

    public static Payment create(Long reservationId, String transactionKey, Long amount, String cardType, String cardNo) {
        Payment payment = new Payment();
        payment.reservationId = reservationId;
        payment.transactionKey = transactionKey;
        payment.amount = amount;
        payment.cardType = cardType;
        payment.cardNo = cardNo;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = LocalDateTime.now();
        return payment;
    }

    public void success() {
        this.status = PaymentStatus.SUCCESS;
    }

    public void fail() {
        this.status = PaymentStatus.FAILED;
    }
}
