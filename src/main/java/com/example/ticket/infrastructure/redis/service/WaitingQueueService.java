package com.example.ticket.infrastructure.redis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitingQueueService {
    private final RedisTemplate<String, String> redisTemplate;
    private static final String QUEUE_KEY = "ticket:waiting:queue";
    private static final String ACTIVE_KEY_PREFIX = "ticket:active:users:";
    // 활성 유저 수 집계용 ZSET (score = 만료 시각 epoch ms). 키스페이스 SCAN 없이 개수를 센다.
    private static final String ACTIVE_EXPIRY_KEY = "ticket:active:expiry";
    private static final int ACTIVE_USER_TTL_MINUTES = 5; // 5분 TTL

    // 대기열 등록 및 순번 확인
    // 이미 대기 중이면 기존 점수(최초 진입 시각)를 유지한다. 덮어쓰면 조회할 때마다 맨 뒤로 밀린다.
    public Long registerAndGetRank(Long userId) {
        String userIdStr = userId.toString();
        redisTemplate.opsForZSet().addIfAbsent(QUEUE_KEY, userIdStr, (double) System.currentTimeMillis());
        Long rank = redisTemplate.opsForZSet().rank(QUEUE_KEY, userIdStr);
        return (rank != null) ? rank + 1 : -1L;
    }

    // 입장 허가 여부 확인 (개별 키 존재 여부)
    public boolean isAllowed(Long userId) {
        String key = ACTIVE_KEY_PREFIX + userId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    // Active User로 등록 (TTL 5분)
    public void addActiveUser(Long userId) {
        String key = ACTIVE_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(key, "activated", ACTIVE_USER_TTL_MINUTES, TimeUnit.MINUTES);
        long expireAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(ACTIVE_USER_TTL_MINUTES);
        redisTemplate.opsForZSet().add(ACTIVE_EXPIRY_KEY, userId.toString(), (double) expireAt);
        log.debug("✅ Active User 추가: userId={}, TTL={}분", userId, ACTIVE_USER_TTL_MINUTES);
    }

    // 입장 권한 반납 (결제 완료/실패 시 호출)
    public void removeActiveUser(Long userId) {
        String key = ACTIVE_KEY_PREFIX + userId;
        redisTemplate.delete(key);
        redisTemplate.opsForZSet().remove(ACTIVE_EXPIRY_KEY, userId.toString());
        // 대기열에서도 제거 (중복 방지)
        redisTemplate.opsForZSet().remove(QUEUE_KEY, userId.toString());
        log.debug("🔴 Active User 제거: userId={}", userId);
    }

    // 현재 Active User 수 조회 — 만료 시각이 아직 지나지 않은 항목 수 (ZCOUNT, 읽기 전용, 키스페이스 SCAN 없음)
    public Long getActiveUserCount() {
        Long count = redisTemplate.opsForZSet()
                .count(ACTIVE_EXPIRY_KEY, (double) System.currentTimeMillis(), Double.POSITIVE_INFINITY);
        return (count != null) ? count : 0L;
    }

    // 만료 시각이 지난 활성 유저 항목 정리 (TTL로 키는 이미 사라졌고, 집계용 ZSET만 남은 것)
    public void purgeExpiredActiveUsers() {
        redisTemplate.opsForZSet().removeRangeByScore(ACTIVE_EXPIRY_KEY, 0, (double) System.currentTimeMillis());
    }
}