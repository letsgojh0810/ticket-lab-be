"""모의 PG (ticket-lab-be 결제 연동 계약을 흉내 낸다).

POST /api/v1/payments  ->  {"data": {"transactionKey": "..."}}
DELAY초 뒤 요청 본문의 callbackUrl 로 {"transactionKey", "status"} 를 보낸다.
cardNo 로 결과를 정한다: 0000-... => FAILURE, 9999-... => 콜백 없음(타임아웃), 그 외 => SUCCESS
GET / 은 지금까지 처리한 요청 수를 돌려준다.

사용법: python load-test/pg_mock.py [delay초=3] [port=8082]
"""
import json
import sys
import threading
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DELAY = float(sys.argv[1]) if len(sys.argv) > 1 else 3.0
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8082
lock = threading.Lock()
stats = {"requests": 0, "cb_success": 0, "cb_failure": 0, "cb_skipped": 0, "cb_error": 0}


def callback(url, key, status):
    body = json.dumps({"transactionKey": key, "status": status}).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    try:
        urllib.request.urlopen(req, timeout=30).read()
        with lock:
            stats["cb_success" if status == "SUCCESS" else "cb_failure"] += 1
    except Exception as e:
        with lock:
            stats["cb_error"] += 1
        print("callback error", key, e, flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # keep-alive. HTTP/1.0 이면 풀링된 연결이 끊겨 클라이언트(WebClient) 오류가 난다

    def log_message(self, *args):
        pass

    def _send(self, code, obj):
        raw = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self._send(200, dict(stats, delay=DELAY))

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        data = json.loads(self.rfile.read(n) or b"{}")
        key = str(uuid.uuid4())
        card = str(data.get("cardNo", ""))
        with lock:
            stats["requests"] += 1
        if card.startswith("9999"):
            with lock:
                stats["cb_skipped"] += 1
        else:
            status = "FAILURE" if card.startswith("0000") else "SUCCESS"
            timer = threading.Timer(DELAY, callback, (data["callbackUrl"], key, status))
            timer.daemon = True
            timer.start()
        self._send(200, {"data": {"transactionKey": key}})


class Server(ThreadingHTTPServer):
    request_queue_size = 4096  # 기본값 5라서 동시 결제 요청이 몰리면 연결이 밀려 앱 쪽 결제 요청이 500으로 실패했다
    daemon_threads = True


if __name__ == "__main__":
    print(f"mock PG on :{PORT} delay={DELAY}s", flush=True)
    Server(("0.0.0.0", PORT), Handler).serve_forever()
