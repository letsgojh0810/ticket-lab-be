package com.example.ticket.infrastructure.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SseEmitterRegistry {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public void addEmitter(String id, SseEmitter emitter) {
        emitters.put(id, emitter);
        log.info("SSE 클라이언트 연결: {} (현재 {}명)", id, emitters.size());
    }

    public void removeEmitter(String id) {
        emitters.remove(id);
        log.info("SSE 클라이언트 해제: {} (현재 {}명)", id, emitters.size());
    }

    public void broadcast(String data) {
        emitters.forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name("seat-update").data(data));
            } catch (IOException e) {
                log.warn("SSE 전송 실패, 연결 제거: {}", id);
                emitters.remove(id);
            }
        });
    }
}
