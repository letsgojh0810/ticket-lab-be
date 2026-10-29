"""ticket-lab-be 부하 테스트 드라이버: 가짜 유저 N명이 대기열 -> 좌석 선점 -> 결제까지 진행한다.

유저마다 실제 계정(signup/login)으로 진행해야 결제의 '본인 예약' 검사를 통과한다.
흐름: 계정 준비 -> 전원 대기열 진입 -> 서버 대기열 순서대로 READY 폴링 -> AVAILABLE 좌석 선점
      -> 선점 성공자 시나리오 배정 (k번째 당첨자: k%5 != 0 이면 결제 성공, 아니면 결제실패)
         pay_ok   : 결제 요청, 모의 PG가 SUCCESS 콜백
         pay_fail : 결제 요청, 모의 PG가 FAILURE 콜백 (앱이 좌석 해제)
      -> 결제에 실패한 유저는 워커를 놓아 주고, 콜백이 세션을 반납할 시간이 지난 뒤 작업 큐 맨 뒤로 다시 들어가 재시도
      -> 선점 실패자: 선점 중인 좌석이 남아 있으면 PATIENCE_SEC 동안 기다렸다가 풀린 좌석을 다시 시도,
         완전 매진이면 슬롯 반납 후 종료
사용법: python load-test/loadtest.py <users> <workers> <settle초>   (환경변수: PORT, POLL_SEC, PG_DELAY)
예: POLL_SEC=3 PG_DELAY=3 python load-test/loadtest.py 20000 800 10
주의: 서버 대기열 순서(Redis)를 `docker exec ticket-redis redis-cli`로 읽는다. 컨테이너 이름은 docker-compose.yml 기준.
"""
import http.client
import json
import os
import random
import subprocess
import sys
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

HOST, PORT = "localhost", int(os.environ.get("PORT", "8080"))
USERS = int(sys.argv[1]) if len(sys.argv) > 1 else 1000
WORKERS = int(sys.argv[2]) if len(sys.argv) > 2 else 300
SETTLE = float(sys.argv[3]) if len(sys.argv) > 3 else 8
MAX_WAIT_SEC = 1500
POLL_SEC = float(os.environ.get("POLL_SEC", "1.0"))
SNAP_SEC = 1.0          # 좌석 목록 스냅샷 갱신 주기 (전체 유저 공유)
MAX_TRY_PER_ROUND = 20  # 스냅샷 한 번당 한 유저가 시도하는 좌석 수 상한
PATIENCE_SEC = 120
MAX_ATTEMPTS = 8
PG_DELAY = float(os.environ.get("PG_DELAY", "3"))  # 모의 PG 콜백 지연과 같게 맞춘다
CARD = {"pay_ok": "1111-1111-1111-1111", "pay_fail": "0000-0000-0000-0000"}

snap_lock = threading.Lock()
snap = {"t": 0.0, "seats": None}
local = threading.local()
lock = threading.Lock()
stats = Counter()
reserve_lat = []
winner_seq = [0]
stage = {}  # uid -> 현재 단계 (감시용)

EXECUTOR = None
DEADLINE = [0.0]
TOTAL = [0]
T1 = [0.0]
done = [0]


def conn():
    c = getattr(local, "c", None)
    if c is None:
        c = http.client.HTTPConnection(HOST, PORT, timeout=60)
        local.c = c
    return c


def call(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body) if body is not None else None
    for _ in range(2):
        try:
            c = conn()
            c.request(method, path, body=data, headers=headers)
            r = c.getresponse()
            raw = r.read()
            try:
                return r.status, json.loads(raw) if raw else None
            except ValueError:
                return r.status, None
        except Exception:
            local.c = None
    return 0, None


class User:
    def __init__(self, i):
        self.i, self.email, self.uid, self.token = i, f"lt{i}@test.com", None, None

    def login(self):
        s, j = call("POST", "/api/v1/auth/login", {"email": self.email, "password": "pw1234"})
        if s == 200 and j:
            self.uid, self.token = j["userId"], j["accessToken"]
        return s

    def req(self, method, path, body=None):
        s, j = call(method, path, body, self.token)
        if s in (401, 403) and not (j and j.get("message")):
            self.login()
            s, j = call(method, path, body, self.token)
        return s, j


def prepare(u):
    call("POST", "/api/v1/auth/signup", {"email": u.email, "password": "pw1234"})
    s = u.login()
    with lock:
        stats["prep_ok" if s == 200 else f"prep_fail_{s}"] += 1
    return u


def enter(u):
    s, _ = u.req("POST", f"/api/v1/queue/enter?userId={u.uid}")
    with lock:
        stats["enter_ok" if s == 200 else f"enter_http_{s}"] += 1


def pay(u, rid, scenario):
    s, j = u.req("POST", "/api/v1/payments/request",
                 {"reservationId": rid, "cardType": "CREDIT", "cardNo": CARD[scenario], "amount": 10000})
    with lock:
        stats[f"pay_request_{scenario}_{'ok' if s == 200 else s}"] += 1
        if s != 200:
            if stats["pay_err_sample"] == 0:
                print("  [결제 요청 오류 샘플]", s, j, flush=True)
            stats["pay_err_sample"] += 1
    if s != 200:
        # 결제 요청이 실패한 유저는 콜백이 오지 않으므로 바로 세션(활성 슬롯)을 반납한다
        u.req("DELETE", f"/api/v1/queue?userId={u.uid}")
        with lock:
            stats["slot_released_after_pay_error"] += 1
    return s == 200


def snapshot(u):
    """전체 좌석 목록을 SNAP_SEC 초에 한 번만 조회해 모든 유저가 공유한다."""
    with snap_lock:
        if snap["seats"] is not None and time.time() - snap["t"] < SNAP_SEC:
            return snap["seats"]
        s, seats = u.req("GET", "/api/v1/seats")
        if s == 200 and isinstance(seats, list):
            snap["seats"], snap["t"] = seats, time.time()
        return snap["seats"]


def finish(u):
    with lock:
        done[0] += 1
        if done[0] % 500 == 0:
            print(f"  ... 처리 {done[0]}/{TOTAL[0]} 경과 {time.time() - T1[0]:.0f}s 선점성공 {stats['reserve_success']}", flush=True)


def flow(u, attempt=0):
    """한 번 시도하고, 결제에 실패했으면 워커를 놓아 준 뒤 PG 콜백이 세션을 반납할 시간을 기다렸다가
    작업 큐 맨 뒤에 다시 넣는다. 워커를 붙잡은 채 줄이 오기를 기다리면, 줄 앞쪽에서 입장한 유저를
    맡을 워커가 없어져 교착된다. (작업 큐 맨 뒤 = 서버 대기열 맨 뒤)"""
    try:
        if attempt > 0:
            u.req("POST", f"/api/v1/queue/enter?userId={u.uid}")
            with lock:
                stats["retry_enter"] += 1
        if try_once(u, DEADLINE[0]):
            if attempt + 1 < MAX_ATTEMPTS:
                timer = threading.Timer(PG_DELAY + 2.0, lambda: EXECUTOR.submit(flow, u, attempt + 1))
                timer.daemon = True
                timer.start()
                return
            with lock:
                stats["gave_up_after_max_attempts"] += 1
    except Exception as e:  # 드라이버 오류로 유저가 영영 끝나지 않으면 메인이 멈추므로 기록하고 종료 처리한다
        with lock:
            stats["driver_exception"] += 1
            if stats["driver_exception"] == 1:
                print("  [드라이버 예외 샘플]", repr(e), flush=True)
    finish(u)


def try_once(u, deadline):
    """한 번의 시도. 결제가 실패해 재시도가 필요하면 True."""
    stage[u.uid] = "poll"
    ready = False
    while time.time() < deadline:
        s, j = u.req("GET", f"/api/v1/queue/status?userId={u.uid}")
        if s == 200 and j and j.get("status") == "READY":
            ready = True
            break
        time.sleep(POLL_SEC + random.random() * 0.3)
    if not ready:
        with lock:
            stats["never_ready"] += 1
        return
    stage[u.uid] = "seat"
    won, rid, lost_active = False, None, False
    patience_end = time.time() + PATIENCE_SEC
    while not won and not lost_active:
        seats = snapshot(u)
        if seats is None:
            break
        cands = [x["seatId"] for x in seats if x.get("status") == "AVAILABLE"]
        if not cands:
            # 매진이 아니라 선점 중(SELECTED)인 좌석이 있으면 풀릴 수 있으니 기다린다
            if any(x.get("status") == "SELECTED" for x in seats) and time.time() < patience_end:
                time.sleep(SNAP_SEC + random.random() * 0.5)
                continue
            break
        for sid in random.sample(cands, min(MAX_TRY_PER_ROUND, len(cands))):
            t0 = time.time()
            s, j = u.req("POST", "/api/v1/reservations/reserve", {"seatId": sid, "userId": u.uid})
            dt = time.time() - t0
            msg = (j or {}).get("message", "")
            with lock:
                reserve_lat.append(dt)
                if s == 200 and j and j.get("success"):
                    stats["reserve_success"] += 1
                    won, rid = True, j.get("reservationId")
                elif "접속자가 많아" in msg:
                    stats["reserve_lock_timeout"] += 1
                elif "결제 진행 중" in msg:
                    stats["reserve_already_selected"] += 1
                elif "판매가 완료" in msg:
                    stats["reserve_already_sold"] += 1
                elif "대기열 진입" in msg:
                    stats["reserve_not_active"] += 1
                    lost_active = True
                else:
                    stats[f"reserve_other_{s}"] += 1
            if won or lost_active:
                break
    if not won:
        stage[u.uid] = "out"
        with lock:
            stats["user_lost_active_slot" if lost_active else "user_no_seat"] += 1
        u.req("DELETE", f"/api/v1/queue?userId={u.uid}")
        return
    with lock:
        winner_seq[0] += 1
        k = winner_seq[0]
    scenario = "pay_ok" if k % 5 != 0 else "pay_fail"  # 당첨자 5명 중 1명은 PG 결제실패 (정확히 80%)
    with lock:
        stats[f"scenario_{scenario}"] += 1
    stage[u.uid] = "pay"
    pay_ok = pay(u, rid, scenario)
    stage[u.uid] = "done"
    return scenario == "pay_fail" or not pay_ok


def queue_order(users):
    """서버가 입장시키는 실제 순서(Redis ZSET)대로 유저를 정렬한다.
    이미 승격돼 ZSET에 없는 유저가 가장 앞이고, 나머지는 ZSET 순서다.
    드라이버 배정 순서가 서버 순서와 다르면 승격된 유저를 아무도 폴링하지 않아 슬롯이 교착된다."""
    out = subprocess.run(["docker", "exec", "ticket-redis", "redis-cli", "ZRANGE", "ticket:waiting:queue", "0", "-1"],
                         capture_output=True, text=True).stdout.split()
    in_zset = set(out)
    by_id = {str(u.uid): u for u in users}
    promoted = [u for u in users if str(u.uid) not in in_zset]
    return promoted + [by_id[x] for x in out if x in by_id]


def pct(a, p):
    if not a:
        return 0.0
    a = sorted(a)
    return a[min(len(a) - 1, int(len(a) * p))]


def main():
    global EXECUTOR
    t_start = time.time()
    print(f"[start] users={USERS} workers={WORKERS}", flush=True)
    users = [User(i) for i in range(1, USERS + 1)]
    with ThreadPoolExecutor(max_workers=60) as ex:
        list(ex.map(prepare, users))
    t_prep = time.time() - t_start
    print(f"[phase 0] 계정 준비 {t_prep:.1f}s (ok={stats['prep_ok']})", flush=True)
    users = [u for u in users if u.token]

    t1 = time.time()
    with ThreadPoolExecutor(max_workers=200) as ex:
        list(ex.map(enter, users))
    t_enter = time.time() - t1
    print(f"[phase A] {len(users)}명 대기열 진입 {t_enter:.1f}s", flush=True)

    DEADLINE[0] = time.time() + MAX_WAIT_SEC
    TOTAL[0] = len(users)
    T1[0] = t1

    def watchdog():
        while done[0] < len(users):
            time.sleep(5)
            ctr = Counter(stage.values())
            act = subprocess.run(["docker", "exec", "ticket-redis", "redis-cli", "--scan", "--pattern", "ticket:active:users:*"],
                                 capture_output=True, text=True).stdout.split()
            act_ids = {int(k.rsplit(":", 1)[1]) for k in act}
            act_stage = Counter(stage.get(i, "미배정(워커없음)") for i in act_ids)
            print(f"  [감시 {time.time() - t1:.0f}s] 전체단계={dict(ctr)} 활성={len(act_ids)} 활성의 단계={dict(act_stage)}", flush=True)

    threading.Thread(target=watchdog, daemon=True).start()

    EXECUTOR = ThreadPoolExecutor(max_workers=WORKERS)
    for u in queue_order(users):
        EXECUTOR.submit(flow, u)
    while done[0] < len(users):  # 재시도는 타이머가 나중에 작업을 다시 넣으므로 전원이 끝날 때까지 기다린다
        time.sleep(1)
    EXECUTOR.shutdown(wait=True)
    t_flow = time.time() - t1
    print(f"[phase B] 완료 {t_flow:.1f}s, 콜백 정산 {SETTLE}s 대기", flush=True)
    time.sleep(SETTLE)

    print("\n===== 결과 =====")
    print(f"phase0 {t_prep:.1f}s / 진입 {t_enter:.1f}s / 흐름 {t_flow:.1f}s")
    for k in sorted(stats):
        if k not in ("pay_err_sample",):
            print(f"{k}: {stats[k]}")
    print(f"reserve 호출 수: {len(reserve_lat)}  p50={pct(reserve_lat,.5)*1000:.0f}ms p95={pct(reserve_lat,.95)*1000:.0f}ms p99={pct(reserve_lat,.99)*1000:.0f}ms max={max(reserve_lat or [0])*1000:.0f}ms")


if __name__ == "__main__":
    main()
