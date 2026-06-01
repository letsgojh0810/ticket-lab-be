package com.example.ticket.infrastructure.redis.pubsub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatStatusPublisher {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String CHANNEL = "seat-status";

    public void publish(Long seatId, String seatNumber, String status) {
        try {
            String message = objectMapper.writeValueAsString(
                    Map.of("seatId", seatId, "seatNumber", seatNumber, "status", status)
            );
            redisTemplate.convertAndSend(CHANNEL, message);
        } catch (JsonProcessingException e) {
            log.warn("SSE 메시지 직렬화 실패. seatId={}: {}", seatId, e.getMessage());
        }
    }
}
