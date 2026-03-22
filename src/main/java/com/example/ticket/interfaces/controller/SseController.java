package com.example.ticket.interfaces.controller;

import com.example.ticket.infrastructure.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/sse")
public class SseController {

    private final SseEmitterRegistry registry;

    @GetMapping(value = "/seats", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        String id = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        registry.addEmitter(id, emitter);
        emitter.onCompletion(() -> registry.removeEmitter(id));
        emitter.onTimeout(() -> registry.removeEmitter(id));
        emitter.onError(e -> registry.removeEmitter(id));

        try {
            emitter.send(SseEmitter.event().name("connect").data("connected"));
        } catch (IOException e) {
            registry.removeEmitter(id);
        }

        return emitter;
    }
}
