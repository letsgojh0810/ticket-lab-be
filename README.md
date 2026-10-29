# 🎫 Ticket-Lab: 대규모 동시성 제어 티켓 예매 시스템

> 분산 환경에서의 동시성 제어와 이벤트 기반 아키텍처를 적용한 실시간 티켓 예매 플랫폼

[![Java](https://img.shields.io/badge/Java-17-red.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-7.0-red.svg)](https://redis.io/)
[![Kafka](https://img.shields.io/badge/Kafka-3.5-black.svg)](https://kafka.apache.org/)

---

## 📋 목차
- [프로젝트 개요](#-프로젝트-개요)
- [핵심 기술 스택](#-핵심-기술-스택)
- [아키텍처](#-아키텍처)
- [동시성 제어 전략](#-동시성-제어-전략)
- [대기열 시스템](#-대기열-시스템)
- [이벤트 기반 아키텍처](#-이벤트-기반-아키텍처)
- [고민사항](#-고민사항)
- [API 문서](#-api-문서)
- [FAQ](#-faq)

---

## 🎯 프로젝트 개요

수만 명이 동시에 접속하는 티켓 예매 시스템에서 정해진 좌석만 정확히 판매하고 빠른 응답 속도를 유지하며 안정적으로 서비스하는 것을 목표했다.

해당 프로젝트는 도메인 특성을 고려하여 가장 중요한 목표를 다음 3가지로 정했다.

1. 데이터 정합성(+트랜잭션 일관성)
2. 멱등성 보장
3. 동시성 제어

### 핵심 과제

| 문제 | 해결 방법                    | 성과 |
|------|--------------------------|------|
| **동시성 이슈** | Redisson 분산 락 + Redis 캐싱 | 중복 예매 방지 |
| **DB 과부하** | 락 → 캐시 → DB| DB 부하 감소 |
| **트래픽 제어** | Redis ZSet 활용| Active User 100명 제한 |
| **응답 속도** | Kafka 비동기 이벤트 처리 Kafka| 응답시간 향상 및 결합도 제거 |

---

## 🛠 핵심 기술 스택

### Backend
- **Java 17**
- **Spring Boot 3**
- **Spring Data JPA**
- **MySQL 8.0**

### Infrastructure
- **Redis + Redisson** - 분산 락, 캐싱, 대기열
- **Apache Kafka** - 이벤트 기반 비동기 처리
- **Docker Compose** - 로컬 개발 환경

### Monitoring
- **Prometheus** - 메트릭 수집
- **Grafana** - 시각화

### AI
- **Gemini**
- **Claude Code** - 리팩토링 및 테스트
---

## 🏗 아키텍처

### 시스템 아키텍처
<img src="static/img/Arch.png" alt="시스템 아키텍처">

> Click Image 

#### 1. **Facade 패턴**
복잡한 인프라 계층(Redis, Redisson, Kafka)을 Application Layer에서 추상화하여 도메인 로직과 분리

#### 2. **Rich Domain Model**
비즈니스 로직을 엔티티 내부에 캡슐화 (예: `Seat.reserve()`)

#### 3. **Event-Driven Architecture**
Kafka를 통한 비동기 이벤트 처리로 메인 플로우와 부가 작업 분리

---

## 🔒 동시성 제어 전략



**Redisson**
- Pub/Sub 기반 대기
- RLock 인터페이스로 간편한 분산 락 구현
- 공정성 옵션 지원

---

## 🎫 대기열 시스템


### 주요 기능

**1. 대기열 관리**
- Redis SortedSet(ZSet)으로 FIFO 순서 보장
- 타임스탬프 기반 score로 공정한 순서 처리

**2. Active User 제한**
- 개별 키 + TTL 5분 방식
- 브라우저 종료 시 자동 정리

**3. Scheduler**
- 1초마다 100명씩 Active User로 이동
- MAX_ACTIVE_USER 100명 한도 체크

---

## 📨 이벤트 기반 아키텍처

### 문제: 동기 처리의 한계

```java
public String reserve() {
    // ... 예매 로직 ...
    emailService.sendConfirmation();
    smsService.sendNotification();
    statisticsService.update();
    return "SUCCESS";
}
```

**문제점:**
- 외부API(SMTP, 카카오톡 등) 서버 장애 시 예매도 실패

### 해결: Kafka 이벤트 기반

```java
// ✅ After: 비동기 이벤트 발행
public String reserve() {
    // ... 예매 로직 ...
    kafkaProducer.publish(event); // 밀리초 단위
    return "SUCCESS"; // 즉시 응답!
}

// 별도 Consumer에서 비동기 처리
@KafkaListener(topics = "reservation-events")
public void consume(ReservationEvent event) {
    emailService.send();    // 비동기
    smsService.send();       // 비동기
    statisticsService.update(); // 비동기
}
```

### Kafka 설정

**Producer:**
- `acks=all` - 모든 복제본 확인 (신뢰성 최우선)
- 재시도 3회
- Key: seatId (같은 좌석 이벤트 순서 보장)

**Consumer:**
- 수동 커밋 (At-least-once 보장)
- 처리 실패 시 자동 재시도
- Consumer Group: `ticket-reservation-group`

---


## 📖 API 문서

### 대기열 API

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/v1/queue/enter?userId={id}` | 대기열 진입 |
| GET | `/api/v1/queue/status?userId={id}` | 상태 확인 |
| DELETE | `/api/v1/queue?userId={id}` | 대기열 이탈 |

### 예매 API

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/v1/reservations/reserve` | 예매 진행 |
| POST | `/api/v1/reservations/cancel` | 예매 취소 |

---

## 🧪 부하 테스트

테스트는 **Claude Code**에 부하 테스트용 Agents를 설정하여 진행하였습니다.

### 1. 테스트 목표

* **동시성 제어**: 1,000명의 유저가 한정된 100개의 좌석을 두고 경쟁하는 상황 시뮬레이션
* **트래픽 밀집**: 시간에 따른 Score를 부여하여 1,000명의 트래픽이 한꺼번에 몰리는 상황 부여
* **프로세스 완결성**: 좌석 선점 후 결제까지 완료되어야 성공으로 처리하며, 이탈 시 좌석 점유 해제 검증
* **데이터 정합성**: 테스트 종료 후 DB 및 Redis의 상태가 실제 예매 결과와 일치하는지 Grafana로 최종 체크

### 2. 테스트 환경 및 시나리오

#### 📋 테스트 설정

| 항목 | 값 |
| --- | --- |
| **총 좌석 수** | 100개 |
| **총 유저 수** | 1,000명 |
| **Max Active Users** | 100명 → 200명 (순차 증가) |
| **결제 성공률** | 80% (사용자 변심/이탈 가정) |
| **결제 소요 시간** | 10초 |

#### 📈 예상 결과 (Metrics)

| 메트릭 | 예상 값 | 비고 |
| --- | --- | --- |
| **reservation_success** | 100 | 전 좌석 매진 |
| **reservation_failed** | 900 | 매진으로 인한 대기/실패 |
| **payment_success** | ~125 | 결제 시도 중 최종 성공 확률 반영 |
| **payment_failed** | ~25 | 결제 과정 중 이탈/실패 |

---

### 3. 테스트 진행 과정

**1️⃣ 대기열 인입 및 스케줄링**
테스트 시작과 동시에 **Redis Zset** 대기열에 1000명의 유저가 들어갔고, 큐 스케줄러를 통해 초당 100명씩 Active 상태로 전환되었다.

**2️⃣ 좌석 선점 및 캐싱**
선점이 되었거나, 예매이 완료된 좌석에 대해서는 seat의 상태를 캐싱하여 저장해두어서 다른 유저가 접근하지 못하게 했다.

<table>
<tr>
<td align="center"><b>Redis 좌석 상태 (Seat Status)</b></td>
<td align="center"><b>Redis 대기열 (Waiting Queue)</b></td>
</tr>
<tr>
<td align="center"><img src="static/img/test1.png" width="400" alt="redis seat status"></td>
<td align="center"><img src="static/img/test4.png" width="400" alt="redis waiting queue"></td>
</tr>
</table>

**3️⃣ 실시간 모니터링 (Grafana)**
대기열에서 성공적으로 전환된 유저들의 예매 성공/실패 지표를 실시간으로 모니터링했고, 시간이 지남에 따라 Success 지표가 안정적으로 상승하는 것을 확인했다.

<p align="center">
<img src="static/img/test2.png" width="49%" alt="grafana">
<img src="static/img/test5.png" width="49%" alt="grafana increasing">


<sub><b>그라파나 대시보드: 예매 성공 지표의 점진적 상승 확인</b></sub>
</p>

**4️⃣ Redis 캐싱 확인**
자리 선점 및 예매이 완료된 좌석들이 정상적으로 Redis 캐시에 기록되는 것을 확인했다.

<p align="center">
<img src="static/img/test6.png" width="600" alt="seat 캐싱">
</p>

<sub><b>CONFIMRED : 예매이 완료된 좌석 (다른유저 좌석 접근 불가)</b></sub>

---

### 4. 트러블슈팅: 1차 테스트 실패

#### 문제 발생

1차 테스트 결과, 예상했던 성공 100건에 못 미치는 **79건**에서 멈추는 현상이 발생했다. 또한 모든 유저가 Active 큐에 진입하지 못하고 고작 270명만 진입하는 문제가 발생했다.

<p align="center">
<img src="static/img/test7.png" width="600" alt="실패 결과">
</p>

> **원인 분석**
> * **스케줄러**: Score 상위 유저부터 Active로 전환 (userId 7, 55, 45...)
> * **테스트 코드**: userId 1번부터 순차적으로 요청을 보냄
> * **결과**: 아직 Active 권한을 얻지 못한 유저들이 요청을 보내면서 403 Forbidden 에러 발생. 결과적으로 270명만 정상 진입함.
>  .
> 

#### ✅ 해결 방법

각 유저가 자신의 상태가 **Active**가 될 때까지 대기한 후, Active 권한을 획득했을 때 예매을 시도하도록 테스트 코드를 수정했다. 즉, 모든 유저가 대기 후 모두 Active 큐에 들어갈 수 있게 설정했다.

---

### 5. 최종 테스트 결과 - 성공 

코드 수정 후 재테스트를 진행한 결과, 모든 지표가 예상치에 부합하며 성공적으로 종료되었다.

<p align="center">
<img src="static/img/2test1.png" width="49%" alt="재테스트1">
<img src="static/img/2test2.png" width="49%" alt="재테스트2">
</p>

* **최종 상태**: 티켓 100개 전량 매진
* **유저 흐름**: 대기 → 선점 → 예매 프로세스가 유기적으로 작동함을 확인

<p align="center">
<img src="static/img/2test3.png" width="49%" alt="재테스트 성공">
<img src="static/img/2test4.png" width="49%" alt="성공 로그">
</p>

#### 💾 DB 정합성 확인

테스트 후, DB 내의 좌석 상태와 예매 내역이 불일치 없이 정확하게 반영됨을 확인하였따.

<table>
<tr>
<td align="center" width="50%">
<img src="static/img/seat.png" width="95%" alt="좌석 DB">
<sub><b>좌석(Seat) DB 결과</b></sub>
</td>
<td align="center" width="50%">
<img src="static/img/reservation.png" width="95%" alt="예매 DB">
<sub><b>예매(Reservation) DB 결과</b></sub>
</td>
</tr>
</table>

### 6. 테스트 후기

* **Redis를 이용한 DB 부하 분산 효과**
1,000명의 유저가 동시에 몰렸음에도 불구하고, 좌석 상태를 Redis 캐싱으로 처리하여 DB의 부하를 최소화할 수 있었다.

<br />

* **예외 상황에 대한 전략**
결제 중 이탈(20%) 시나리오를 통해 좌석 점유가 무한정 유지되지 않고 정상적으로 해제되어 다음 유저에게 기회가 돌아가는 것을 확인했다. 다만, 테스트를 위해 임의로 넣은 수치인 만큼 실제 환경에서의 TTL 전략과, 페이지 이탈 시의 전략에 대한 고민이 필요하다.

---

### 7. 트러블슈팅: 좌석 1만 개 / 결제 포함 재테스트 중 발견한 문제

> 3월 "DB를 상태 원천으로" 리팩토링(`eed0f66`) 이후 코드로 다시 테스트하면서 발견한 문제들이다. 좌석을 1만 개로 늘리고, 결제는 별도 모의 PG(결제 성공 80% / 실패 20%)로 진행했다. 유저 2천~3천 명으로 먼저 검증했고, 1만 석 / 2만 명 본 테스트는 아직 돌리지 않았다.
> (위 6번의 "이탈 시 좌석 점유가 정상 해제"는 DB를 원천으로 바꾼 뒤에는 성립하지 않았다. 아래 5번 참고)

#### 1) 중복 예약 (OSIV)
- **증상**: 1,000명 / 100석 테스트에서 예약이 103~110건, 같은 좌석에 예약이 2건인 좌석이 3~9개 (4회 재현)
- **원인**: `spring.jpa.open-in-view` 기본값(켜짐)이라 한 요청 동안 영속성 컨텍스트가 유지된다. 락을 잡기 전에 `findById`로 읽은 `Seat`이 남아 있어서, 락 획득 후 "DB 재확인"이 DB를 다시 읽지 않고 `AVAILABLE` 객체를 그대로 반환했다. `validateSeatNotTaken`과 `Seat.select()` 검사가 둘 다 통과됨. 1월 코드는 락 안에서 Redis 캐시로 판정했는데, 3월에 DB 재확인으로 바뀌면서 드러났다.
- **해결**: `spring.jpa.open-in-view=false`
- **검증**: 꺼진 상태에서 1,000명 테스트 3회 모두 예약 정확히 100건, 중복 0

#### 2) 대기열 순번이 조회할 때마다 맨 뒤로 밀림
- **증상**: 활성 500명 / 유저 2,000명 테스트에서 500건 처리 후 멈춤. 활성 500명이 전부 "폴링하지 않는 유저"였다.
- **원인**: `registerAndGetRank`가 `ZADD`로 점수를 현재 시각으로 덮어쓰는데, `status` 조회가 이 메서드를 호출한다. 실험 결과 대기 500번째 유저가 `status`를 1번 조회하자 999번째가 되었다. 자주 조회하는 사람일수록 승격되지 못한다. (1차 테스트 실패와 같은 유형)
- **해결**: `ZADD NX`(`addIfAbsent`)로 최초 진입 시각을 유지
- **검증**: 활성 500명이 가득 찬 상태에서 한 유저가 5번 조회해도 점수·순번 불변, 자리가 1개 나자 가장 먼저 온 유저가 입장

#### 3) 활성 유저 수 조회가 Redis 전체 SCAN
- **원인**: `getActiveUserCount()`가 `SCAN`으로 키스페이스 전체를 순회하고, `status` 요청마다 호출된다. SCAN은 중복 반환이 가능해서 개수가 과대 집계될 수도 있다. (실제 영향 크기는 측정하지 못함)
- **해결**: 만료 시각을 점수로 둔 ZSET(`ticket:active:expiry`)과 `ZCOUNT`로 집계, 스케줄러 틱마다 만료분 정리
- **검증**: 상태 응답의 `activeUserCount`가 정확(500), 테스트 종료 후 0

#### 4) 성공·결제 지표가 항상 0
- **원인**: `reservation_success`, `payment_success`, `payment_failed` 카운터를 올리는 코드가 없었다. (언제부터인지는 확인하지 못함)
- **해결**: 선점 성공 시 `reservation_success`, 결제 콜백 커밋 후(`AFTER_COMMIT`) `payment_success`/`payment_failed` 증가
- **검증**: 2,000명 테스트에서 `reservation_success` 2,000 / `payment_success` 1,600 / `payment_failed` 400

#### 5) HELD 좌석에 TTL이 없음
- **증상**: 결제 없이 이탈하거나 결제 요청이 실패하면 좌석(`SELECTED`)과 예약(`HELD`)이 풀리지 않고, 활성 슬롯도 5분간 묶인다.
- **원인**: 5분 TTL은 Redis 좌석 캐시 키에만 있고, 판단 기준인 DB 상태에는 만료가 없다. `@Scheduled`도 `QueueScheduler` 하나뿐이었다.
- **해결**: `HeldReservationExpirer` 추가. 1초마다 TTL이 지난 `HELD`를 만료시킨다. (예약 `CANCELLED`, 좌석 `AVAILABLE`, 캐시 삭제, 활성 슬롯 반납, SSE·Kafka 발행) TTL은 `reservation.held-ttl-seconds`, 기본 300초.
- **검증**: TTL을 5초로 낮춘 별도 인스턴스에서 선점 후 9초 뒤 좌석 `AVAILABLE` / 예약 `CANCELLED` / 활성 슬롯 반납 확인. 기본 300초가 실제로 5분 뒤에 동작하는지는 기다려 보지 않았다.
- **남은 한계**: 결제가 진행 중인(`PENDING`) 예약인지는 확인하지 않고 만료시킨다. 늦게 온 성공 콜백이 `confirm()`에서 예외를 던질 수 있다.

#### 6) 결제 요청이 실패하면 슬롯이 TTL까지 묶임
- **증상**: 동시 결제 요청 중 일부가 500. 모의 PG에 도달한 건 87건뿐이었고 나머지 113건은 `HELD`로 남아, 활성 슬롯 200개 중 113개를 묶어서 대기열이 멈췄다.
- **원인**: PG 호출이 실패하면 콜백이 오지 않아 예약과 활성 슬롯이 정리되지 않는다. PG 호출(`WebClient.block()`)에 타임아웃도 없다. 이번에는 모의 PG(파이썬 소켓 서버의 연결 대기열 5, HTTP/1.0)가 동시 연결을 받지 못한 것으로 추정한다. (앱 스택트레이스는 보지 못했고, 대기열을 키운 뒤에는 재현되지 않았다.)
- **대응**: 테스트에서는 결제 요청 실패 시 바로 세션을 반납하게 했다. 앱 쪽 PG 타임아웃과 실패 처리는 미해결.

#### 7) 테스트 코드에서 얻은 교훈
- 서버는 대기열 점수 순서대로 승격하므로 테스트도 같은 순서로 유저를 배정해야 한다. 승격된 유저를 아무도 폴링하지 않으면 슬롯이 교착된다. (1차 테스트 실패와 같은 유형)
- 좌석 목록이 1만 건이라(단건 약 46ms, 약 667KB) 유저마다 매번 조회하면 부하가 크다. 1초에 한 번만 받아 공유했다.
- 결제에 실패한 유저는 PG 콜백으로 세션이 반납된 뒤 대기열에 다시 진입해 재시도해야 한다. 안 하면 수요 2,000명에서 확정이 1,600에 멈춘다.
- 매진 검증은 수요가 공급보다 많아야 의미가 있다.

| 조건 | 결과 |
|---|---|
| 유저 2,000명 / 좌석 1만 개, 결제 실패자 재시도 | 확정 2,000 (유저 전원), 결제실패 취소 499, 중복 0, 결제 성공률 80.03% |
| 유저 3,000명 / 좌석 1,000개 (나머지 판매 처리) | 1,000석 전량 `CONFIRMED`, 선점 1,249 → 결제 성공 1,000 (80.06%), 실패로 풀린 249석은 뒤 유저가 다시 확보, 중복 0 |

#### 8) 아직 해결하지 못한 문제 (코드 리뷰에서 지적, 일부는 재현하지 못함)
- 결제 콜백이 인증·서명 없이 열려 있고 `transactionKey`가 사용자에게 응답으로 돌아간다 → 위조로 결제 없이 확정 가능
- 예약·대기열 API가 `userId`를 요청값으로 신뢰한다
- 상태 전이 가드 부재 (`Reservation.cancel()`이 `CONFIRMED`를 허용, `Seat.release()`가 상태를 보지 않음) → 늦게 온 실패 콜백이 결제 완료 좌석을 풀 수 있다
- 콜백·취소·만료가 락이나 버전 없이 같은 행을 변경한다
- 락 점유 2초(leaseTime) 안에 커밋이 끝나지 않으면 중복 선점이 가능하다 (재현하지 못함). `reservation(seat_id)` 유니크 제약도 없다.
- PG 호출 타임아웃 없음, 금액 검증 없음, `cardNo` 평문 저장
- 인덱스 부재 (`reservation(status, reserved_at)`, `payments.transaction_key` 등), 좌석 목록 페이징·캐시 없음
- 서버가 2대일 때 대기열 승격과 만료 스케줄러가 원자적이지 않다


---

## 🤔 고민사항

### 1. Redis TTL 시간 설정 근거

- 결제시간을 얼만큼 줘야할까?

```java
redisTemplate.opsForValue().set(key, "SELECTED", 5, TimeUnit.MINUTES);
```
- 결제 시뮬레이션 10초 + 여유 시간
- 너무 짧으면 정상 예매도 만료 or 심각하면 중복결제 가능
- 너무 길면 실패한 락이 오래 남아서 기회비용 날림

### 2. Active User를 Set → 개별 키로 변경

**AS-IS:**
```redis
SADD ticket:active:users 100 101 102
# TTL 설정 불가 → 메모리 누수
```

**TO-BE**
```redis
SET ticket:active:users:100 "1" EX 300
SET ticket:active:users:101 "1" EX 300
# 각 키마다 TTL 자동 적용
```

**효과:**
- 브라우저 종료 시 5분 후 자동 제거
- Scheduler 장애와 무관하게 동작
- Redis 기본 기능만 활용 (간단)

**다만, 키가 너무 많이 생겼을 때 문제가 있을 수 있는지?**


### 3. 휘발성인 Redis는 어떤식으로 관리해야할까?
1. DB에 직접 다 저장하기에는 데이터가 너무 많이 생길 것 같음
2. 뭔가 정상적으로 이벤트가 모두 종료된 후라면, 휘발되어도 크게 문제가 되지 않을것 같긴함...
   
### 4. 공연/스포츠 마다 미리 트래픽을 예상하는 방법?

- 과거 데이터 분석
- 예상이 힘들 때는? 그냥 조금이라도 예상되면 무조건 여유롭게 준비하는게 나을까?

### 5. 개인적인 생각 : 어떤일이 있어도 Redis만큼은 죽어선 안된다.
- 고가용성 관련 대비 - 단일 노드 X (트래픽 분산)
- APP과 Redis 사이 Load Balancer 활용 (HAProxy, ALB 등)
- 페일오버 전략 - 레플리케이션

### 6. Reservation에 실패/취소 이력까지 같이 두는 게 맞을까? (리팩토링 후보)

**현재:** `Reservation`이 `HELD → CONFIRMED | CANCELLED`. 결제실패·사용자 취소·TTL 만료가 모두 `CANCELLED` 행으로 남는다. (2,000명 부하 테스트에서 확정 2,000건에 취소 499건)

**문제 후보**
- 취소된 행이 쌓여서 모든 조회·만료 스캔에 `status` 조건이 필요함
- 좌석당 활성 예약은 하나여야 하는데 취소 행 때문에 `seat_id` 유니크 제약을 못 건다 → 중복 선점 방어가 락/앱 로직에만 있음
- 같은 유저가 취소 후 같은 좌석을 다시 잡으면 `findBySeatIdAndUserId`(Optional)가 행 2개로 500 날 수 있음 (코드 리뷰 지적, 재현은 안 해봄)
- 결제실패 / 사용자취소 / 만료가 한 값(`CANCELLED`)에 섞여서 원인 구분이 안 됨
- 좌석(`Seat.status`)과 예약이 같은 사실(누가 이 자리를 잡았나)을 두 곳에 기록 → 어긋날 수 있음

**방향 (미결정)**
- `reservation` : 살아 있는 것만 (`HELD`, `CONFIRMED`), `seat_id` UNIQUE
- `reservation_log` : 끝난 것 (결제실패 / 사용자취소 / 만료) + 시각·사유
- 이동은 한 트랜잭션 (로그 INSERT + 예약 DELETE + 좌석 해제), 3개 경로 공통 처리
- `payments.reservation_id`가 삭제된 행을 가리키게 되므로 좌석·유저를 복사해 두거나 로그를 참조해야 함

**할 일:** 현재 코드로 대규모 부하 테스트 기준값을 먼저 잡고, 개선 후 같은 테스트로 비교
  