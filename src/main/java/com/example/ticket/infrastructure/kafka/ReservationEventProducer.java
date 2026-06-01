package com.example.ticket.infrastructure.kafka;

import com.example.ticket.domain.event.ReservationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String TOPIC = "reservation-events";

    /**
     * 예약 이벤트를 Kafka로 비동기 발행
     */
    public void publish(ReservationEvent event) {
        String key = String.valueOf(event.getSeatId());
        String message;
        try {
            message = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("이벤트 직렬화 실패 - Event: {}, Error: {}", event.getEventType(), e.getMessage());
            return;
        }

        CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(TOPIC, key, message);

        // 비동기 콜백 처리
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("✅ 이벤트 발행 성공 - Topic: {}, Partition: {}, Offset: {}, Event: {}",
                        TOPIC,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        event.getEventType());
            } else {
                log.error("❌ 이벤트 발행 실패 - Event: {}, Error: {}",
                        event.getEventType(),
                        ex.getMessage());
            }
        });
    }

    /**
     * 동기 방식 이벤트 발행 (중요한 이벤트, 발행 확인 필요 시)
     */
    public void publishSync(ReservationEvent event) throws Exception {
        String key = String.valueOf(event.getSeatId());
        String message = objectMapper.writeValueAsString(event);

        SendResult<String, String> result = kafkaTemplate.send(TOPIC, key, message).get();
        log.info("🔒 동기 이벤트 발행 완료 - Offset: {}", result.getRecordMetadata().offset());
    }
}
