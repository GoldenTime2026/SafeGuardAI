"""Flight, parking and queue information for the on-time coach, from the two airport operators' open APIs.

- 인천국제공항공사 (ICN): 여객편 운항현황(다국어) and 승객예고-출·입국장별 (departure-hall crowd forecast).
- 한국공항공사 (the other 14 airports): 실시간 항공기 운항정보 검색/조회, 전국공항 실시간 주차정보, 공항 소요시간.

The car asks for one airport and either a flight number or a departure time with the words the driver said
("세 시 반 제주"); the server finds the flight and adds what the coach needs on the way there. One 공공데이터포털
key (DATA_GO_KR_KEY) covers all of them. Answers are cached for two minutes: the Incheon development quota is
1,000 calls a day and its flight list comes whole (about 1,200 flights) whatever page size is asked for.
"""

import json
import os
import threading
import time
import urllib.parse
import urllib.request

KAC = "https://apis.data.go.kr/B551178"
IIAC = "https://apis.data.go.kr/B551177"

# 한국공항공사 airports by IATA code → the name its parking feed uses.
KAC_AIRPORTS = {
    "GMP": "김포", "PUS": "김해", "CJU": "제주", "TAE": "대구", "CJJ": "청주", "KWJ": "광주", "MWX": "무안",
    "RSU": "여수", "USN": "울산", "KPO": "포항", "HIN": "사천", "KUV": "군산", "WJU": "원주", "YNY": "양양",
}
# The process-time feed is split in two: v1 for 김포·제주, v2 for 김해·대구·청주.
PROCESS_V1 = {"GMP", "CJU"}
PROCESS_V2 = {"PUS", "TAE", "CJJ"}
# Incheon terminal codes: P01 Terminal 1, P02 the concourse (check-in at Terminal 1), P03 Terminal 2.
ICN_TERMINALS = {"P01": "T1", "P02": "T1", "P03": "T2"}

CACHE_S = 120
# 한국공항공사 answers HTTP_ERROR (not a short page) when asked for more than 100 rows.
KAC_PAGE_ROWS = 100
KAC_MAX_PAGES = 6
# A changed time more than six hours from the scheduled one is the previous day's flight, not a delay.
MAX_DELAY_MIN = 6 * 60
# Matching a spoken time: flights scheduled within this many minutes of it.
TIME_WINDOW_MIN = 60
# ... and the one meant must stand alone within this many minutes.
UNIQUE_MIN = 10
# The hour counts as busy at this share of the day's busiest hour in the crowd forecast.
BUSY_SHARE = 0.8


def minute_of(hhmm):
    try:
        return int(hhmm[:2]) * 60 + int(hhmm[2:4])
    except (TypeError, ValueError, IndexError):
        return None


def delay_minutes(scheduled, estimated):
    """Minutes the changed time is after the scheduled one, on a 24-hour clock; None when it isn't a delay."""
    s, e = minute_of(scheduled), minute_of(estimated)
    if s is None or e is None:
        return None
    d = (e - s + 12 * 60) % (24 * 60) - 12 * 60
    return d if abs(d) <= MAX_DELAY_MIN else None


def normalize_flight(text):
    return "".join(ch for ch in (text or "").upper() if ch.isalnum())


class FlightInfo:
    def __init__(self, key, opener=None, clock=time.time):
        self.key, self.clock = key, clock
        self.opener = opener or urllib.request.urlopen
        self.cache = {}
        self.lock = threading.Lock()

    @classmethod
    def from_env(cls, env=os.environ):
        key = env.get("DATA_GO_KR_KEY", "")
        return cls(key) if key else None

    # ------------------------------------------------------------------ fetching

    def _get(self, url, params):
        query = urllib.parse.urlencode(dict(params, serviceKey=self.key, type="json"))
        full = f"{url}?{query}"
        now = self.clock()
        with self.lock:
            hit = self.cache.get(full)
            if hit and now - hit[0] < CACHE_S:
                return hit[1]
        with self.opener(full, timeout=20) as response:
            data = json.loads(response.read().decode("utf-8"))
        # The gateway's own errors (bad key, quota, a page too large) come in a different envelope.
        gateway = data.get("OpenAPI_ServiceResponse", {}).get("cmmMsgHeader")
        if gateway:
            raise RuntimeError(f"{url}: {gateway.get('returnReasonCode')} {gateway.get('errMsg')}")
        header = data.get("response", {}).get("header", {})
        if header.get("resultCode") not in ("00", None):
            raise RuntimeError(f"{url}: {header.get('resultCode')} {header.get('resultMsg')}")
        with self.lock:
            self.cache[full] = (now, data)
        return data

    @staticmethod
    def _items(data):
        items = data.get("response", {}).get("body", {}).get("items") or []
        if isinstance(items, dict):
            items = items.get("item") or []
        return items if isinstance(items, list) else [items]

    # ------------------------------------------------------------------ flights

    @staticmethod
    def _kac_flight(item):
        delay = delay_minutes(item.get("std"), item.get("etd"))
        status = item.get("rmkKor") or ""
        return {
            "flightId": item.get("airFln"), "airline": item.get("airlineKorean"),
            "dest": item.get("arrivedKor"), "scheduled": item.get("std"),
            "estimated": item.get("etd") if delay is not None else None, "delayMin": delay,
            "status": status, "cancelled": "결항" in status,
            "line": "I" if item.get("line") == "국제" else "D",
            "gate": item.get("gate") or None, "terminal": None, "counters": None,
        }

    @staticmethod
    def _icn_flight(item):
        delay = delay_minutes(item.get("scheduleDateTime"), item.get("estimatedDateTime"))
        status = item.get("remark") or ""
        return {
            "flightId": item.get("flightId"), "airline": item.get("airline"),
            "dest": item.get("airport"), "scheduled": item.get("scheduleDateTime"),
            "estimated": item.get("estimatedDateTime") if delay is not None else None, "delayMin": delay,
            "status": status, "cancelled": "결항" in status,
            "line": "I" if item.get("typeOfFlight") == "I" else "D",
            "gate": item.get("gatenumber") or None,
            "terminal": ICN_TERMINALS.get(item.get("terminalId")),
            "counters": item.get("chkinrange") or None,
        }

    def _departures(self, airport):
        if airport == "ICN":
            data = self._get(f"{IIAC}/StatusOfPassengerFlightsOdp/getPassengerDeparturesOdp",
                             {"numOfRows": "2000", "pageNo": "1", "lang": "K"})
            return [self._icn_flight(i) for i in self._items(data)]
        flights = []
        for page in range(1, KAC_MAX_PAGES + 1):
            data = self._get(f"{KAC}/flight-status/info",
                             {"schAirCode": airport, "schIOType": "O", "numOfRows": str(KAC_PAGE_ROWS), "pageNo": str(page)})
            items = self._items(data)
            flights += [self._kac_flight(i) for i in items]
            total = int(data.get("response", {}).get("body", {}).get("totalCount") or 0)
            if len(items) < KAC_PAGE_ROWS or len(flights) >= total:
                break
        return flights

    def _by_number(self, airport, flight):
        wanted = normalize_flight(flight)
        if airport == "ICN":
            data = self._get(f"{IIAC}/StatusOfPassengerFlightsOdp/getPassengerDeparturesOdp",
                             {"numOfRows": "50", "pageNo": "1", "lang": "K", "flight_id": wanted})
            flights = [self._icn_flight(i) for i in self._items(data)]
        else:
            data = self._get(f"{KAC}/flight-search/info",
                             {"schFln": wanted, "schAirCode": airport, "schIOType": "O", "numOfRows": "10", "pageNo": "1"})
            flights = [self._kac_flight(i) for i in self._items(data)]
        return next((f for f in flights if normalize_flight(f["flightId"]) == wanted), None)

    @staticmethod
    def match_spoken(flights, minute, words):
        """The flight the driver means by a time and the words they used ("세 시 반 제주", "오후 3시 오사카")."""
        words = words or ""

        def named(f):
            return any(part and part in words for part in (f.get("dest") or "").replace(" ", "").split("/"))

        def off(f):
            s = minute_of(f["scheduled"])
            return abs((s - minute + 12 * 60) % (24 * 60) - 12 * 60) if s is not None else 9999

        near = [f for f in flights if off(f) <= TIME_WINDOW_MIN]
        named_near = [f for f in near if named(f)]
        pool = named_near if named_near else ([] if any(named(f) for f in flights) else near)
        # 김포→제주 has a flight every few minutes: the nearest of many is a guess, and a guessed flight's delay
        # would mislead. Only a flight that stands alone within ten minutes of the time is taken; otherwise the
        # car asks for the flight number. The count says how many were close.
        close = sorted((f for f in pool if off(f) <= UNIQUE_MIN), key=off)
        alone = len(close) == 1 or (len(close) > 1 and off(close[1]) - off(close[0]) >= UNIQUE_MIN)
        return (close[0] if close and alone else None), len(pool)

    # ------------------------------------------------------------------ on the way

    def parking(self, airport):
        name = KAC_AIRPORTS.get(airport)
        if not name:
            return []
        data = self._get(f"{KAC}/parking-realtime-status/info", {"numOfRows": str(KAC_PAGE_ROWS), "pageNo": "1"})
        lots = []
        for i in self._items(data):
            if name not in (i.get("aprKor") or ""):
                continue
            total, parked = int(i.get("parkingFullSpace") or 0), int(i.get("parkingIstay") or 0)
            if total > 0:
                lots.append({"name": i.get("parkingAirportCodeName"), "total": total, "parked": parked,
                             "free": max(total - parked, 0)})
        return lots

    def wait_minutes(self, airport):
        """Check-in to security cleared, averaged now (the feed's sections 1 and 2); None when it isn't measured."""
        if airport in PROCESS_V1:
            path = "v1"
        elif airport in PROCESS_V2:
            path = "v2"
        else:
            return None
        data = self._get(f"{KAC}/airport-process-time/{path}", {"numOfRows": "10", "pageNo": "1"})
        for i in self._items(data):
            if i.get("IATA_APCD") == airport and str(i.get("OPR_STS_CD")) == "1":
                seconds = float(i.get("STY_TCT_AVG_A") or 0) + float(i.get("STY_TCT_AVG_B") or 0)
                return round(seconds / 60)
        return None

    def busy_hour(self, terminal, hour):
        """Incheon only: whether this hour's forecast departures for the terminal near the day's peak."""
        if terminal not in ("T1", "T2"):
            return None
        data = self._get(f"{IIAC}/passgrAnncmt/getPassgrAnncmt", {"numOfRows": "30", "pageNo": "1", "selectdate": "0"})
        field = "t1dgsum1" if terminal == "T1" else "t2dgsum2"
        counts = {}
        for i in self._items(data):
            try:
                counts[int(str(i.get("atime", "")).split("_")[0])] = float(i.get(field) or 0)
            except ValueError:
                continue
        peak = max(counts.values(), default=0)
        if peak <= 0 or hour not in counts:
            return None
        return counts[hour] >= BUSY_SHARE * peak

    # ------------------------------------------------------------------ the answer

    def plan(self, airport, flight=None, minute=None, words=None, hour=None):
        airport = (airport or "").upper()
        if airport != "ICN" and airport not in KAC_AIRPORTS:
            raise ValueError(f"unknown airport {airport}")
        found, candidates = None, 0
        if flight:
            found = self._by_number(airport, flight)
            candidates = 1 if found else 0
        if found is None and minute is not None:
            found, candidates = self.match_spoken(self._departures(airport), minute, words)
        result = {"airport": airport, "flight": found, "candidates": candidates, "parking": [], "waitMin": None, "busy": None}
        # The extras are best effort: a failing feed must not lose the flight.
        if airport == "ICN":
            if found and found.get("terminal") and hour is not None:
                try:
                    result["busy"] = self.busy_hour(found["terminal"], hour)
                except Exception as e:  # noqa: BLE001 - logged, the plan still goes out
                    print(f"[flight] crowd forecast failed: {e}", flush=True)
        else:
            for name, call in (("parking", lambda: self.parking(airport)), ("waitMin", lambda: self.wait_minutes(airport))):
                try:
                    result[name] = call()
                except Exception as e:  # noqa: BLE001
                    print(f"[flight] {name} failed: {e}", flush=True)
        return result
