-- 부하 테스트용 좌석 시드: 앱이 만드는 1~100번에 더해 101~10000번을 추가한다. (총 1만 개)
-- 사용: docker exec -i ticket-mysql mysql -uroot -ppassword --default-character-set=utf8mb4 < load-test/seed-seats.sql
-- 주의: 앱은 좌석이 한 건이라도 있으면 시드를 건너뛰므로, 먼저 앱을 한 번 띄워 1~100번을 만들어 둔다.
SET SESSION cte_max_recursion_depth = 20000;

INSERT INTO ticket_db.seat (seat_number, status)
WITH RECURSIVE n AS (
    SELECT 101 AS i
    UNION ALL
    SELECT i + 1 FROM n WHERE i < 10000
)
SELECT CONCAT(i, '번 좌석'), 'AVAILABLE' FROM n;
