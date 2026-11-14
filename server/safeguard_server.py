"""SafeGuard AI hazard-sharing server (round-2 prototype).

Cars report what they found (voice reports, hard braking, slips, crashes); cars behind ask for the hazards
around them and the app's existing hazard rule warns about the ones ahead in the same direction.
The same reports, kept only as anonymous per-cell daily counts, feed the Safety Mesh dashboard (/mesh) for road
operators and weather services. With SAFETYDATA_KEY set it also relays the 행정안전부 disaster SMS feed, which
the car filters to its own area and reads out.

Python standard library only, so it runs anywhere (this PC for the demo, a cloud VM later).
    python server/safeguard_server.py                 # http://127.0.0.1:8787, this PC only
    adb reverse tcp:8787 tcp:8787                     # lets the emulator reach it as 127.0.0.1:8787

Privacy (Pleos security guide, "data leaving the vehicle"): reports carry no VIN, account or name, only an
anonymous device token the app rotates daily; positions are kept in memory and expire within hours.
"""

import argparse
import datetime
import hashlib
import hmac
import json
import math
import os
import sqlite3
import ssl
import sys
import threading
import time
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from flights import FlightInfo, minute_of

# How long a hazard stays on the map, and how many different cars must report it before others are warned.
# A person seeing debris or a car with deployed airbags is enough on their own; slips and hard braking are
# noisy (one driver brakes hard for many reasons), so they need several cars.
RULES = {
    "CRASH": {"ttl_s": 60 * 60, "min_cars": 1},
    "ACCIDENT": {"ttl_s": 45 * 60, "min_cars": 1},
    "STOPPED_VEHICLE": {"ttl_s": 30 * 60, "min_cars": 1},
    "DEBRIS": {"ttl_s": 30 * 60, "min_cars": 1},
    "POTHOLE": {"ttl_s": 24 * 60 * 60, "min_cars": 1},
    "OTHER": {"ttl_s": 30 * 60, "min_cars": 1},
    "ICE": {"ttl_s": 2 * 60 * 60, "min_cars": 2},
    "SLIP": {"ttl_s": 2 * 60 * 60, "min_cars": 3},
    # Several cars braking hard at one spot within minutes: the tail of a sudden jam.
    "HARD_BRAKING": {"ttl_s": 5 * 60, "min_cars": 2},
    # Wipers switched on by cars ahead: rain on the road before it reaches this car. Rain covers kilometres,
    # so reports cluster over a wider area; two cars keep one driver's washer spray from counting as rain.
    "RAIN": {"ttl_s": 20 * 60, "min_cars": 2, "cluster_m": 2_000.0},
    "HEAVY_RAIN": {"ttl_s": 20 * 60, "min_cars": 2, "cluster_m": 2_000.0},
    # The forward-collision or emergency-lane-keeping light warning at the same spot in several cars: where a
    # crash is likely before one happens.
    "NEAR_MISS": {"ttl_s": 24 * 60 * 60, "min_cars": 3},
    # Lane following dropping out on a dry road: worn lane paint, for the road operator's map only. Nothing a
    # driver can act on, so it is never handed to cars ("warn": False).
    "LANE_FADE": {"ttl_s": 7 * 24 * 60 * 60, "min_cars": 3, "warn": False},
}
CLUSTER_RADIUS_M = 200.0
SAME_DIRECTION_DEG = 45.0
MAX_REPORTS_PER_DEVICE_PER_MIN = 10
MAX_RADIUS_M = 20_000.0


def distance_m(lat1, lon1, lat2, lon2):
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def heading_delta(a, b):
    if a is None or b is None:
        return 0.0
    d = abs(a - b) % 360
    return 360 - d if d > 180 else d


class HazardStore:
    """Clusters reports of the same type, place and direction into one hazard."""

    def __init__(self, clock=time.time):
        self.clock = clock
        self.hazards = {}
        self.recent = {}  # device -> report times, for rate limiting
        self.lock = threading.Lock()

    def add(self, report):
        kind = report["type"]
        if kind not in RULES:
            raise ValueError(f"unknown type {kind}")
        now = self.clock()
        with self.lock:
            times = [t for t in self.recent.get(report["device"], []) if now - t < 60]
            if len(times) >= MAX_REPORTS_PER_DEVICE_PER_MIN:
                raise PermissionError("too many reports")
            self.recent[report["device"]] = times + [now]
            self._expire(now)
            hazard = next(
                (h for h in self.hazards.values()
                 if h["type"] == kind
                 and distance_m(h["lat"], h["lon"], report["lat"], report["lon"]) <= RULES[kind].get("cluster_m", CLUSTER_RADIUS_M)
                 and heading_delta(h["heading"], report.get("heading")) <= SAME_DIRECTION_DEG),
                None,
            )
            if hazard is None:
                hazard = {
                    "id": uuid.uuid4().hex[:12], "type": kind, "lat": report["lat"], "lon": report["lon"],
                    "heading": report.get("heading"), "devices": set(), "sources": set(), "firstAt": now,
                }
                self.hazards[hazard["id"]] = hazard
            hazard["devices"].add(report["device"])
            hazard["sources"].add(report.get("source", "VEHICLE"))
            hazard["lastAt"] = now
            hazard["expiresAt"] = now + RULES[kind]["ttl_s"]
            return self._public(hazard)

    def near(self, lat, lon, radius_m):
        now = self.clock()
        with self.lock:
            self._expire(now)
            found = [
                self._public(h) for h in self.hazards.values()
                if distance_m(lat, lon, h["lat"], h["lon"]) <= radius_m
            ]
        return [h for h in found if h["confirmed"] and RULES[h["type"]].get("warn", True)]

    def _expire(self, now):
        for key in [k for k, h in self.hazards.items() if h["expiresAt"] < now]:
            del self.hazards[key]

    @staticmethod
    def _public(h):
        cars = len(h["devices"])
        return {
            "id": h["id"], "type": h["type"], "lat": h["lat"], "lon": h["lon"], "heading": h["heading"],
            "cars": cars, "confirmed": cars >= RULES[h["type"]]["min_cars"],
            "sources": sorted(h["sources"]), "updatedAt": int(h["lastAt"] * 1000), "expiresAt": int(h["expiresAt"] * 1000),
        }


# Safety Mesh cells: about 500 m square around 35°N (one degree of longitude is ~91 km there).
MESH_CELL_LAT = 0.0045
MESH_CELL_LON = 0.0055
# A cell is published for a type only when this many different car-days reported it there.
MESH_MIN_CARS = 3


class MeshStore:
    """Safety Mesh: the cars' hazard reports kept as anonymous counts per ~500 m cell and day.

    What road operators (도로공사, 지자체) and weather services get is "how many different cars slipped, met ice,
    stopped with hazard lights or drove through heavy rain in this cell", never a car or a track:
      - today's reports keep only a hash of the device token with a salt that lives in memory for one day;
      - past days are rolled up into (day, cell, type, events, cars) and the hashes are deleted;
      - a cell is published only once MESH_MIN_CARS different car-days reported that type there.
    Rows written by the demo seeder carry demo=1 so the dashboard can say so.
    """

    def __init__(self, path=":memory:", clock=time.time):
        self.clock = clock
        self.lock = threading.Lock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS raw (day TEXT, cell TEXT, type TEXT, car TEXT);
            CREATE TABLE IF NOT EXISTS daily (
                day TEXT, cell TEXT, type TEXT, events INTEGER, cars INTEGER, demo INTEGER DEFAULT 0,
                PRIMARY KEY (day, cell, type, demo));
        """)
        self.salts = {}

    @staticmethod
    def cell_of(lat, lon):
        return f"{math.floor(lat / MESH_CELL_LAT)}:{math.floor(lon / MESH_CELL_LON)}"

    @staticmethod
    def bounds(cell):
        i, j = (int(v) for v in cell.split(":"))
        return i * MESH_CELL_LAT, j * MESH_CELL_LON, (i + 1) * MESH_CELL_LAT, (j + 1) * MESH_CELL_LON

    def _day(self, t=None):
        return time.strftime("%Y-%m-%d", time.localtime(self.clock() if t is None else t))

    def record(self, kind, lat, lon, device):
        day = self._day()
        salt = self.salts.setdefault(day, uuid.uuid4().hex)
        car = hashlib.sha256((salt + device).encode()).hexdigest()[:16]
        with self.lock:
            self.salts = {day: salt}  # yesterday's salt is gone: its hashes can no longer be linked to a device
            self.db.execute("INSERT INTO raw VALUES (?, ?, ?, ?)", (day, self.cell_of(lat, lon), kind, car))
            self._roll_up(day)
            self.db.commit()

    def _roll_up(self, today):
        old = self.db.execute(
            "SELECT day, cell, type, COUNT(*), COUNT(DISTINCT car) FROM raw WHERE day < ? GROUP BY day, cell, type", (today,)
        ).fetchall()
        for day, cell, kind, events, cars in old:
            self._add_daily(day, cell, kind, events, cars, 0)
        self.db.execute("DELETE FROM raw WHERE day < ?", (today,))

    def _add_daily(self, day, cell, kind, events, cars, demo):
        self.db.execute("""
            INSERT INTO daily VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (day, cell, type, demo) DO UPDATE SET events = events + excluded.events, cars = cars + excluded.cars
        """, (day, cell, kind, events, cars, demo))

    def add_demo(self, day, lat, lon, kind, events, cars):
        """For the demo seeder only: a past day's counts, marked as demo data."""
        with self.lock:
            self._add_daily(day, self.cell_of(lat, lon), kind, events, cars, 1)
            self.db.commit()

    def cells(self, types=None, days=30, min_cars=MESH_MIN_CARS):
        """Published cells as {cell, lat, lon, bounds, types: {type: {events, cars, lastDay}}, demo}."""
        since = self._day(self.clock() - (days - 1) * 86_400)
        with self.lock:
            rows = self.db.execute("""
                SELECT cell, type, SUM(events), SUM(cars), MAX(day), MAX(demo) FROM (
                    SELECT day, cell, type, events, cars, demo FROM daily WHERE day >= ?
                    UNION ALL
                    SELECT day, cell, type, COUNT(*), COUNT(DISTINCT car), 0 FROM raw WHERE day >= ? GROUP BY day, cell, type
                ) GROUP BY cell, type
            """, (since, since)).fetchall()
        found = {}
        for cell, kind, events, cars, last_day, demo in rows:
            if (types and kind not in types) or cars < min_cars:
                continue
            south, west, north, east = self.bounds(cell)
            entry = found.setdefault(cell, {
                "cell": cell, "lat": round((south + north) / 2, 5), "lon": round((west + east) / 2, 5),
                "bounds": [round(v, 5) for v in (south, west, north, east)], "types": {}, "demo": False,
            })
            entry["types"][kind] = {"events": events, "cars": cars, "lastDay": last_day}
            entry["demo"] = entry["demo"] or bool(demo)
        return sorted(found.values(), key=lambda c: -sum(t["cars"] for t in c["types"].values()))


REST_AREAS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "public", "rest_areas.json")
LANDMARK_RADIUS_M = 15_000.0


def load_rest_areas(path=REST_AREAS):
    """The app's 졸음쉼터 list (한국도로공사 data), so a road operator reads a place instead of coordinates."""
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)["points"]
    except (OSError, ValueError, KeyError):
        return []


def landmark(lat, lon, rest_areas):
    """'진례 졸음쉼터(남해선) 서쪽 3.2km', or '' when none is near."""
    best = min(((distance_m(p["lat"], p["lon"], lat, lon), p) for p in rest_areas), default=None, key=lambda dp: dp[0])
    if best is None or best[0] > LANDMARK_RADIUS_M:
        return ""
    d, p = best
    bearing = math.degrees(math.atan2(
        math.sin(math.radians(lon - p["lon"])) * math.cos(math.radians(lat)),
        math.cos(math.radians(p["lat"])) * math.sin(math.radians(lat))
        - math.sin(math.radians(p["lat"])) * math.cos(math.radians(lat)) * math.cos(math.radians(lon - p["lon"])),
    )) % 360
    side = ["북", "북동", "동", "남동", "남", "남서", "서", "북서"][int(bearing / 45 + 0.5) % 8]
    return f"{p['name']} 졸음쉼터({p['road']}) {side}쪽 {d / 1000:.1f}km"


def mesh_geojson(cells, rest_areas=()):
    features = []
    for c in cells:
        south, west, north, east = c["bounds"]
        features.append({
            "type": "Feature",
            "geometry": {"type": "Polygon", "coordinates": [[[west, south], [east, south], [east, north], [west, north], [west, south]]]},
            "properties": {
                "cell": c["cell"], "lat": c["lat"], "lon": c["lon"], "types": c["types"], "demo": c["demo"],
                "cars": sum(t["cars"] for t in c["types"].values()), "near": landmark(c["lat"], c["lon"], rest_areas),
            },
        })
    return {"type": "FeatureCollection", "features": features}


def mesh_csv(cells, rest_areas=()):
    lines = ["cell,lat,lon,near,type,events,car_days,last_day,demo"]
    for c in cells:
        near = landmark(c["lat"], c["lon"], rest_areas)
        for kind, t in sorted(c["types"].items()):
            lines.append(f"{c['cell']},{c['lat']},{c['lon']},\"{near}\",{kind},{t['events']},{t['cars']},{t['lastDay']},{int(c['demo'])}")
    return "\n".join(lines) + "\n"


def mask_phone(phone):
    """'010-****-5678' for logs: the full number is used for sending only."""
    return f"{phone[:3]}-****-{phone[-4:]}" if len(phone) >= 8 else "****"


def valid_phone(phone):
    return phone.isdigit() and len(phone) in (10, 11) and phone.startswith("01")


class SolapiSender:
    """Sends an SMS/LMS through SOLAPI (https://solapi.com) with its HMAC-SHA256 API key auth.

    Configured from the environment so no key is ever in the repository:
        SOLAPI_API_KEY, SOLAPI_API_SECRET, SOLAPI_SENDER (a sender number registered with SOLAPI)
    """

    URL = "https://api.solapi.com/messages/v4/send"
    LIST_URL = "https://api.solapi.com/messages/v4/list"
    DELIVERED = "4000"  # SOLAPI's "수신 완료"; any other final code is a refusal (e.g. 3113, 번호도용문자차단)
    # Acceptance is not delivery: the carrier answers a few seconds later, and the car keeps its fallback
    # (show the text for someone to send) unless the phone has the message within this window.
    RESULT_WAIT_S = 12
    RESULT_POLL_S = 1.5

    def __init__(self, api_key, api_secret, sender, opener=None, sleep=time.sleep):
        self.api_key, self.api_secret, self.sender = api_key, api_secret, sender
        self.opener = opener or urllib.request.urlopen
        self.sleep = sleep

    @classmethod
    def from_env(cls, env=os.environ):
        keys = [env.get(k, "") for k in ("SOLAPI_API_KEY", "SOLAPI_API_SECRET", "SOLAPI_SENDER")]
        return cls(*keys) if all(keys) else None

    def auth_header(self, date, salt):
        signature = hmac.new(self.api_secret.encode(), (date + salt).encode(), hashlib.sha256).hexdigest()
        return f"HMAC-SHA256 apiKey={self.api_key}, date={date}, salt={salt}, signature={signature}"

    def _call(self, url, body=None):
        date = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        headers = {"Authorization": self.auth_header(date, uuid.uuid4().hex)}
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=body, method="POST" if body is not None else "GET", headers=headers)
        with self.opener(request, timeout=10) as response:
            return json.loads(response.read().decode("utf-8") or "{}")

    def send(self, to, text):
        """Returns (delivered, reason): True once the phone has it, False if refused, None if still unknown."""
        body = json.dumps({"message": {"to": to, "from": self.sender, "text": text}}).encode("utf-8")
        message_id = self._call(self.URL, body).get("messageId")
        if not message_id:
            return None, "no message id"
        waited = 0.0
        while waited < self.RESULT_WAIT_S:
            self.sleep(self.RESULT_POLL_S)
            waited += self.RESULT_POLL_S
            found = self._call(f"{self.LIST_URL}?messageId={message_id}").get("messageList") or {}
            message = next(iter(found.values()), {})
            if message.get("status") in ("COMPLETE", "FAILED"):
                code = message.get("statusCode", "")
                return code == self.DELIVERED, f"{code} {message.get('reason') or ''}".strip()
        return None, "no carrier result yet"


class GuardianOutbox:
    """Guardian alerts after a crash. Sent by SMS when a sender is configured; otherwise recorded only."""

    def __init__(self, sender=None):
        self.sender = sender
        self.sent = []

    def send(self, message, to=None):
        """Returns (delivered, note) where note explains a non-delivery for the car's crash screen."""
        entry = {"message": message, "to": mask_phone(to) if to else None, "at": int(time.time() * 1000)}
        self.sent.append(entry)
        if not to or not valid_phone(to):
            print(f"[guardian] no valid number, recorded: {message}", flush=True)
            return False, "보호자 번호 없음 · 서버에 기록만 됨"
        if self.sender is None:
            print(f"[guardian] no SMS sender configured, recorded for {entry['to']}: {message}", flush=True)
            return False, "문자 서비스 미설정 · 서버에 기록만 됨"
        try:
            delivered, reason = self.sender.send(to, message)
        except Exception as e:  # network or provider error: the car shows the message for someone to send
            print(f"[guardian] SMS to {entry['to']} failed: {e}", flush=True)
            return False, "문자 발송 실패 · 화면의 문자를 대신 보내 주세요"
        outcome = {True: "delivered", False: "refused", None: "unconfirmed"}[delivered]
        print(f"[guardian] SMS to {entry['to']}: {outcome} ({reason})", flush=True)
        if delivered:
            return True, ""
        if delivered is None:
            return False, "문자 수신 확인 안 됨 · 화면의 문자를 대신 보내 주세요"
        return False, "문자 전달 실패 · 화면의 문자를 대신 보내 주세요"


class TelltaleStats:
    """Warning lights counted by model ('현대 2024년식'), anonymously: the start of model-level statistics
    (plan 7) that Hyundai/Kia fleet data would replace. Kept in memory like the hazards."""

    def __init__(self):
        self.lock = threading.Lock()
        self.counts = {}

    def add(self, model, telltale):
        if not model or not telltale or len(model) > 40 or len(telltale) > 40:
            raise ValueError("model and telltale are required")
        with self.lock:
            per_model = self.counts.setdefault(model, {})
            per_model[telltale] = per_model.get(telltale, 0) + 1

    def top(self, model, limit=5):
        with self.lock:
            per_model = dict(self.counts.get(model, {}))
        ranked = sorted(per_model.items(), key=lambda kv: (-kv[1], kv[0]))[:limit]
        return [{"telltale": t, "count": c} for t, c in ranked]


KST = datetime.timezone(datetime.timedelta(hours=9))


class DisasterFeed:
    """행정안전부 긴급재난문자 (재난안전데이터공유플랫폼 DSSP-IF-00247), polled for the whole country.

    Cars ask only for messages newer than the last one they have (`after`) and match the regions on the car,
    so no car tells the server where it is. The text is passed on unchanged: the data is 공공누리 제4유형
    (attribution, no commercial use, no modification), so the app reads it out word for word with its source.

    Configured from the environment like SOLAPI: SAFETYDATA_KEY.
    """

    URL = "https://www.safetydata.go.kr/V2/api/DSSP-IF-00247"
    # A 2-minute poll is 720 calls a day, inside the 1,000-a-day development quota.
    POLL_S = 120
    PAGE_ROWS = 1000
    KEEP_S = 24 * 60 * 60
    # A car that has just started gets the last three hours, not the whole day: weather warnings stay in force
    # that long, and the car itself drops other kinds after an hour.
    FRESH_S = 3 * 60 * 60

    def __init__(self, key, opener=None, clock=time.time):
        self.key, self.clock = key, clock
        self.opener = opener or urllib.request.urlopen
        self.lock = threading.Lock()
        self.messages = {}

    @classmethod
    def from_env(cls, env=os.environ):
        key = env.get("SAFETYDATA_KEY", "")
        return cls(key) if key else None

    @staticmethod
    def parse(row):
        created = datetime.datetime.strptime(row["CRT_DT"], "%Y/%m/%d %H:%M:%S").replace(tzinfo=KST)
        regions = [r.strip() for r in (row.get("RCPTN_RGN_NM") or "").split(",") if r.strip()]
        return {
            "sn": int(row["SN"]),
            "createdAt": int(created.timestamp() * 1000),
            "regions": regions,
            "step": row.get("EMRG_STEP_NM") or "",
            "kind": row.get("DST_SE_NM") or "",
            "text": row.get("MSG_CN") or "",
        }

    def fetch(self):
        """One poll: everything since [FRESH_S] ago (so the hours after midnight still see late yesterday)."""
        since = datetime.datetime.fromtimestamp(self.clock() - self.FRESH_S, KST).strftime("%Y%m%d")
        rows, page = [], 1
        while True:
            url = (f"{self.URL}?serviceKey={self.key}&returnType=json&pageNo={page}"
                   f"&numOfRows={self.PAGE_ROWS}&crtDt={since}")
            with self.opener(url, timeout=30) as response:
                body = json.loads(response.read().decode("utf-8"))
            if (body.get("header") or {}).get("resultCode") != "00":
                raise IOError(f"safetydata: {body.get('header')}")
            batch = body.get("body") or []
            rows += batch
            if len(batch) < self.PAGE_ROWS:
                break
            page += 1
        added = 0
        now_ms = self.clock() * 1000
        with self.lock:
            for row in rows:
                message = self.parse(row)
                if message["sn"] not in self.messages:
                    added += 1
                self.messages[message["sn"]] = message
            for sn in [sn for sn, m in self.messages.items() if now_ms - m["createdAt"] > self.KEEP_S * 1000]:
                del self.messages[sn]
        return added

    def after(self, sn):
        cutoff = self.clock() * 1000 - self.FRESH_S * 1000
        with self.lock:
            fresh = [m for m in self.messages.values() if m["sn"] > sn and m["createdAt"] >= cutoff]
        return sorted(fresh, key=lambda m: m["sn"])

    def run(self):
        while True:
            try:
                added = self.fetch()
                if added:
                    print(f"[disaster] {added} new message(s), {len(self.messages)} kept", flush=True)
            except Exception as e:  # the API or the network is down: keep what we have and try again
                print(f"[disaster] poll failed: {e}", flush=True)
            time.sleep(self.POLL_S)


DASHBOARD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mesh_dashboard.html")


def make_handler(store, outbox, stats=None, mesh=None, disasters=None, flights=None):
    stats = stats or TelltaleStats()
    mesh = mesh or MeshStore()
    rest_areas = load_rest_areas()

    class Handler(BaseHTTPRequestHandler):
        def _json(self, code, body):
            self._send(code, json.dumps(body, ensure_ascii=False), "application/json; charset=utf-8")

        def _send(self, code, text, content_type):
            data = text.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _mesh_cells(self, query):
            q = parse_qs(query)
            types = {t for t in q.get("types", [""])[0].split(",") if t} or None
            try:
                days = max(1, min(int(q.get("days", ["30"])[0]), 366))
            except ValueError:
                days = 30
            return mesh.cells(types, days)

        def _body(self):
            length = int(self.headers.get("Content-Length") or 0)
            return json.loads(self.rfile.read(length) or b"{}")

        def do_GET(self):
            url = urlparse(self.path)
            if url.path == "/v1/health":
                return self._json(200, {"ok": True, "hazards": len(store.hazards)})
            if url.path == "/v1/hazards":
                q = parse_qs(url.query)
                try:
                    lat, lon = float(q["lat"][0]), float(q["lon"][0])
                    radius = min(float(q.get("radius", ["5000"])[0]), MAX_RADIUS_M)
                except (KeyError, ValueError):
                    return self._json(400, {"error": "lat and lon are required"})
                return self._json(200, {"hazards": store.near(lat, lon, radius)})
            if url.path == "/v1/telltale-stats":
                model = parse_qs(url.query).get("model", [""])[0]
                return self._json(200, {"model": model, "top": stats.top(model)})
            if url.path == "/v1/disaster-messages":
                try:
                    after = int(parse_qs(url.query).get("after", ["0"])[0])
                except ValueError:
                    return self._json(400, {"error": "after must be a message number"})
                if disasters is None:
                    return self._json(200, {"configured": False, "messages": []})
                return self._json(200, {"configured": True, "source": "행정안전부 긴급재난문자", "messages": disasters.after(after)})
            if url.path == "/v1/flight":
                # ?airport=GMP&flight=KE1234, or &time=1530&words=세 시 반 제주; hour = the car's local hour.
                q = parse_qs(url.query)
                if flights is None:
                    return self._json(200, {"configured": False})
                try:
                    plan = flights.plan(
                        q.get("airport", [""])[0],
                        flight=q.get("flight", [None])[0],
                        minute=minute_of(q["time"][0]) if "time" in q else None,
                        words=q.get("words", [""])[0],
                        hour=int(q["hour"][0]) if "hour" in q else None,
                    )
                except ValueError as e:
                    return self._json(400, {"error": str(e)})
                except Exception as e:  # noqa: BLE001 - the operators' APIs fail now and then
                    print(f"[flight] lookup failed: {e}", flush=True)
                    return self._json(502, {"error": "airport data unavailable"})
                found = plan["flight"]
                print(f"[flight] {plan['airport']} -> {found['flightId'] + ' ' + found['status'] if found else 'not found'}", flush=True)
                return self._json(200, dict(plan, configured=True))
            if url.path == "/v1/mesh/cells":
                return self._json(200, mesh_geojson(self._mesh_cells(url.query), rest_areas))
            if url.path == "/v1/mesh/cells.csv":
                # The BOM lets Excel open the Korean landmark names correctly.
                return self._send(200, "﻿" + mesh_csv(self._mesh_cells(url.query), rest_areas), "text/csv; charset=utf-8")
            if url.path in ("/mesh", "/mesh/"):
                with open(DASHBOARD, encoding="utf-8") as f:
                    return self._send(200, f.read(), "text/html; charset=utf-8")
            self._json(404, {"error": "not found"})

        def do_POST(self):
            url = urlparse(self.path)
            try:
                body = self._body()
            except json.JSONDecodeError:
                return self._json(400, {"error": "invalid JSON"})
            if url.path == "/v1/reports":
                try:
                    report = {
                        "type": str(body["type"]), "lat": float(body["lat"]), "lon": float(body["lon"]),
                        "heading": None if body.get("heading") is None else float(body["heading"]),
                        "device": str(body["device"])[:64], "source": str(body.get("source", "VEHICLE")),
                    }
                    hazard = store.add(report)
                    mesh.record(report["type"], report["lat"], report["lon"], report["device"])
                except (KeyError, TypeError, ValueError) as e:
                    return self._json(400, {"error": str(e)})
                except PermissionError as e:
                    return self._json(429, {"error": str(e)})
                print(f"[report] {report['type']} from {report['device'][:8]} -> {hazard['cars']} car(s), confirmed={hazard['confirmed']}", flush=True)
                return self._json(201, {"hazard": hazard})
            if url.path == "/v1/guardian-alerts":
                to = "".join(ch for ch in str(body.get("to") or "") if ch.isdigit()) or None
                delivered, note = outbox.send(str(body.get("message", ""))[:500], to)
                return self._json(202, {"delivered": delivered, "note": note})
            if url.path == "/v1/telltales":
                try:
                    stats.add(str(body.get("model", "")), str(body.get("telltale", "")))
                except ValueError as e:
                    return self._json(400, {"error": str(e)})
                return self._json(201, {"ok": True})
            self._json(404, {"error": "not found"})

        def log_message(self, fmt, *args):
            pass

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--host", default="127.0.0.1", help="127.0.0.1 keeps it on this PC (default)")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--cert", help="TLS certificate (PEM) for HTTPS, as the Pleos security guide requires on a real car")
    parser.add_argument("--key", help="TLS private key (PEM) for --cert")
    parser.add_argument("--mesh-db", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "mesh.sqlite"),
                        help="Safety Mesh cell counts (anonymous); ':memory:' keeps nothing")
    args = parser.parse_args()
    # Windows consoles default to the legacy code page; the logs hold Korean text.
    sys.stdout.reconfigure(encoding="utf-8")
    sender = SolapiSender.from_env()
    mesh = MeshStore(args.mesh_db)
    disasters = DisasterFeed.from_env()
    flights = FlightInfo.from_env()
    if disasters:
        threading.Thread(target=disasters.run, daemon=True).start()
    server = ThreadingHTTPServer((args.host, args.port),
                                 make_handler(HazardStore(), GuardianOutbox(sender), mesh=mesh, disasters=disasters,
                                              flights=flights))
    scheme = "http"
    if args.cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.cert, args.key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        scheme = "https"
    print(f"SafeGuard server on {scheme}://{args.host}:{args.port}"
          f" (guardian SMS: {'SOLAPI' if sender else 'not configured, recorded only'};"
          f" disaster SMS feed: {'on' if disasters else 'off, set SAFETYDATA_KEY'};"
          f" airport data: {'on' if flights else 'off, set DATA_GO_KR_KEY'};"
          f" Safety Mesh dashboard: {scheme}://{args.host}:{args.port}/mesh)", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
