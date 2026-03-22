package com.example.ticket.interfaces.controller;

import com.example.ticket.application.PaymentFacade;
import com.example.ticket.interfaces.dto.PaymentCallbackRequest;
import com.example.ticket.interfaces.dto.PaymentRequest;
import com.example.ticket.interfaces.dto.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentFacade paymentFacade;

    /**
     * 결제 요청 (인증된 사용자)
     * POST /api/v1/payments/request
     */
    @PostMapping("/request")
    public ResponseEntity<PaymentResponse> requestPayment(@RequestBody PaymentRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String userId = authentication.getName(); // email or username

        String transactionKey = paymentFacade.requestPayment(
                request.getReservationId(),
                request.getCardType(),
                request.getCardNo(),
                request.getAmount(),
                userId
        );

        return ResponseEntity.ok(new PaymentResponse(transactionKey));
    }

    /**
     * PG 콜백 수신 (인증 불필요)
     * POST /api/v1/payments/callback
     */
    @PostMapping("/callback")
    public ResponseEntity<Void> handleCallback(@RequestBody PaymentCallbackRequest request) {
        log.info("PG 콜백 수신. transactionKey={}, status={}", request.getTransactionKey(), request.getStatus());
        paymentFacade.handleCallback(request.getTransactionKey(), request.getStatus());
        return ResponseEntity.ok().build();
    }
}
