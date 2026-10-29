# 부하 테스트 (좌석 1만 개 / 유저 2만 명)

가짜 유저 N명이 **대기열 → 좌석 선점 → 결제**까지 진행하는 테스트 도구 모음이다. 루트의 `load-test.sh`(1,000명, JWT 미지원)를 대신한다.

| 파일 | 역할 |
|---|---|
| `loadtest.py` | 드라이버. 유저 계정을 만들고 대기열 진입, 좌석 선점, 결제, 결제 실패 시 재시도까지 한다 |
| `pg_mock.py` | 모의 PG. 결제 요청을 받고 3초 뒤 서버의 콜백 주소로 성공/실패를 알려 준다 |
| `seed-seats.sql` | 좌석을 1만 개로 늘리는 시드 |

## 실행 순서

1. **인프라**: `docker compose up -d` (MySQL, Redis, Kafka, Prometheus, Grafana)
2. **서버**: IntelliJ에서 Active profiles에 `loadtest`를 넣어 실행한다. (Hikari 풀 50, SQL 로그 끔, 스케줄러 4스레드, JWT 1시간)
   - 적용 확인: `curl localhost:8080/actuator/metrics/hikaricp.connections.max` 값이 `50`
3. **좌석 시드**: 앱을 한 번 띄워 1~100번을 만든 뒤 한 번만 실행한다.
   `docker exec -i ticket-mysql mysql -uroot -ppassword --default-character-set=utf8mb4 < load-test/seed-seats.sql`
4. **모의 PG**: `python load-test/pg_mock.py 3 8082` (콜백 지연 3초, 포트 8082)
5. **초기화** (매 실행 전): 예약·결제·유저를 비우고 좌석을 되돌린다.
   ```sql
   DELETE FROM reservation; DELETE FROM payments; DELETE FROM users;
   ALTER TABLE reservation AUTO_INCREMENT=1; ALTER TABLE payments AUTO_INCREMENT=1; ALTER TABLE users AUTO_INCREMENT=1;
   UPDATE seat SET status='AVAILABLE';
   ```
   `docker exec ticket-redis redis-cli FLUSHALL`
6. **드라이버**: `POLL_SEC=3 PG_DELAY=3 python load-test/loadtest.py 20000 800 10`
   - 인자는 `유저 수`, `워커 수`, `종료 후 콜백 정산 대기(초)`
   - 워커 수는 활성 유저 상한(현재 500)보다 크게 잡는다.

## 시나리오

- 유저마다 가입하고 로그인한 뒤 전원이 대기열에 들어간다. 서버가 입장시키는 순서(Redis)대로 `READY`를 폴링한다.
- 좌석 목록은 1초에 한 번만 받아서 모두가 공유하고, `AVAILABLE` 좌석을 골라 선점한다.
- 선점 성공자 5명 중 1명은 모의 PG가 결제를 실패시킨다. (결제 성공 80%)
- 결제에 실패한 유저는 줄 맨 뒤로 다시 들어가 새로 시도한다. 풀린 좌석은 뒤에서 기다리던 유저가 가져간다.
- 이탈과 TTL 만료는 이 테스트에서 다루지 않는다.

## 합격 기준 (실행 후 DB에서)

```sql
SELECT status, COUNT(*) FROM seat GROUP BY status;                       -- CONFIRMED 10000, AVAILABLE/SELECTED 0
SELECT status, COUNT(*) FROM reservation GROUP BY status;                -- CONFIRMED 10000 (HELD 0)
SELECT COUNT(*) FROM (SELECT seat_id FROM reservation
  WHERE status IN ('HELD','CONFIRMED') GROUP BY seat_id HAVING COUNT(*) > 1) t;   -- 중복 예약 0
```

## 알려진 한계

- 서버 1대 기준이다. 서버가 2대일 때의 대기열 승격 경합은 확인하지 않았다.
- 모의 PG는 파이썬 스레드 서버라서 매우 큰 동시 결제 부하에서는 PG 쪽이 먼저 느려질 수 있다. (연결 대기열은 4096으로 키워 둠)
- 계정 2만 개의 가입·로그인(BCrypt)에 2분 넘게 걸린다. 이 시간은 서버 성능이 아니라 준비 비용이다.
- 이 폴더의 시드 SQL은 같은 좌석을 두 번 넣지 않도록 한 번만 실행해야 한다.
