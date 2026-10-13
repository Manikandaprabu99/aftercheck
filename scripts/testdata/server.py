"""Minimal stand-in for Chess.com's Published-Data API (same paths and JSON shapes) for local tests.

Supports ETag / If-None-Match (304) and an optional 429 on the first archive request (RATE_LIMIT=1).
"""
import hashlib, http.server, json, os, sys

DATA = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
HOST = os.environ.get("PUBLIC_HOST", "http://10.0.2.2:%d" % PORT)
USER = "aftercheck_tester"
LOG = []
rate_limited_once = {"done": os.environ.get("RATE_LIMIT") != "1"}


class Handler(http.server.BaseHTTPRequestHandler):
    def send_json(self, obj, status=200):
        body = json.dumps(obj).encode()
        etag = '"%s"' % hashlib.sha1(body).hexdigest()[:16]
        if self.headers.get("If-None-Match") == etag:
            self.send_response(304); self.send_header("ETag", etag); self.end_headers(); return 304
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("ETag", etag)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        return status

    def do_GET(self):
        p = self.path.split("?")[0].rstrip("/").lower()
        months = json.load(open(os.path.join(DATA, "months.json")))
        code = None
        if p == f"/pub/player/{USER}":
            code = self.send_json({"username": USER, "name": "Test Player", "joined": 1600000000, "country": "https://api.chess.com/pub/country/IN", "player_id": 1})
        elif p == f"/pub/player/{USER}/games/archives":
            if not rate_limited_once["done"]:
                rate_limited_once["done"] = True
                self.send_response(429); self.send_header("Retry-After", "1"); self.end_headers(); code = 429
            else:
                code = self.send_json({"archives": [f"{HOST}/pub/player/{USER}/games/{m.replace('-', '/')}" for m in months]})
        elif p.startswith(f"/pub/player/{USER}/games/"):
            ym = p.split("/games/")[1].replace("/", "-")
            path = os.path.join(DATA, ym + ".json")
            if os.path.exists(path):
                code = self.send_json(json.load(open(path)))
            else:
                code = self.send_json({"games": []})
        elif p.startswith("/pub/player/"):
            self.send_response(404); self.end_headers(); code = 404
        else:
            self.send_response(404); self.end_headers(); code = 404
        print("GET", self.path, code, "inm=" + str(self.headers.get("If-None-Match")), flush=True)

    def log_message(self, *a):
        pass


http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
