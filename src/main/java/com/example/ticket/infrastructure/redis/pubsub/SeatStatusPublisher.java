package com.example.ticket.infrastructure.redis.pubsub;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SeatStatusPublisher {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String CHANNEL = "seat-status";

    public void publish(Long seatId, String seatNumber, String status) {
        String message = String.format(
            "{\"seatId\":%d,\"seatNumber\":\"%s\",\"status\":\"%s\"}",
            seatId, seatNumber, status
        );
        redisTemplate.convertAndSend(CHANNEL, message);
    }
}
