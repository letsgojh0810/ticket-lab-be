package com.example.ticket.infrastructure.redis.pubsub;

import com.example.ticket.infrastructure.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatStatusSubscriber implements MessageListener {

    private final SseEmitterRegistry sseEmitterRegistry;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody());
        log.info("Redis Pub/Sub 수신 → SSE 브로드캐스트: {}", payload);
        sseEmitterRegistry.broadcast(payload);
    }
}
