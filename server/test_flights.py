import io
import json
import unittest
from urllib.parse import parse_qs, urlparse

from flights import FlightInfo, delay_minutes


def kac_flight(fln, dest, std, etd="", status="", line="국내", gate="3"):
    return {"airFln": fln, "airlineKorean": "항공", "arrivedKor": dest, "std": std, "etd": etd, "rmkKor": status,
            "line": line, "gate": gate}


class FakeApis:
    """Answers like the two operators' APIs, by path; records the calls."""

    def __init__(self):
        self.calls = []
        self.kac_status = [
            kac_flight("LJ301", "제주", "1520", "1550", "지연"),
            kac_flight("KE1215", "제주", "1530"),
            kac_flight("OZ8957", "광주", "1535"),
            kac_flight("7C112", "부산", "1700", status="결항"),
        ]
        self.icn = [
            {"flightId": "KE705", "airline": "대한항공", "airport": "도쿄/나리타", "scheduleDateTime": "1855",
             "estimatedDateTime": "1916", "remark": "", "typeOfFlight": "I", "gatenumber": "250", "terminalId": "P03",
             "chkinrange": "E1-E7"},
            {"flightId": "DL188Y", "airline": "델타항공", "airport": "애틀랜타", "scheduleDateTime": "0005",
             "estimatedDateTime": "2320", "remark": "출발", "typeOfFlight": "I", "gatenumber": "", "terminalId": "P03",
             "chkinrange": ""},
        ]

    gateway_error = False

    def __call__(self, url, timeout=None):
        self.calls.append(url)
        parsed = urlparse(url)
        q = {k: v[0] for k, v in parse_qs(parsed.query).items()}
        path = parsed.path
        if self.gateway_error or int(q.get("numOfRows", "0")) > 100 and "B551178" in path:
            return io.BytesIO(json.dumps({"OpenAPI_ServiceResponse": {"cmmMsgHeader": {"errMsg": "HTTP_ERROR", "returnReasonCode": "04"}}}).encode())
        if path.endswith("/flight-status/info"):
            page, rows = int(q["pageNo"]), int(q["numOfRows"])
            items = self.kac_status[(page - 1) * rows: page * rows]
            body = {"totalCount": len(self.kac_status), "items": {"item": items}}
            return io.BytesIO(json.dumps({"response": {"header": {"resultCode": "00"}, "body": body}}).encode())
        elif path.endswith("/flight-search/info"):
            items = [f for f in self.kac_status if f["airFln"] == q["schFln"]]
        elif path.endswith("/parking-realtime-status/info"):
            items = [
                {"aprKor": "김포국제공항", "parkingAirportCodeName": "국내선 제1주차장", "parkingFullSpace": 2279, "parkingIstay": 2279},
                {"aprKor": "김포국제공항", "parkingAirportCodeName": "국내선 제2주차장", "parkingFullSpace": 1733, "parkingIstay": 1378},
                {"aprKor": "제주국제공항", "parkingAirportCodeName": "P1", "parkingFullSpace": 1000, "parkingIstay": 10},
            ]
        elif path.endswith("/airport-process-time/v1"):
            items = [{"IATA_APCD": "GMP", "OPR_STS_CD": "1", "STY_TCT_AVG_A": 300.0, "STY_TCT_AVG_B": 420.0}]
        elif path.endswith("/getPassengerDeparturesOdp"):
            items = [f for f in self.icn if "flight_id" not in q or f["flightId"] == q["flight_id"]]
            return io.BytesIO(json.dumps({"response": {"header": {"resultCode": "00"}, "body": {"items": items}}}).encode())
        elif path.endswith("/getPassgrAnncmt"):
            items = [{"atime": f"{h:02d}_{h + 1:02d}", "t1dgsum1": "100", "t2dgsum2": str(1000 if h == 18 else 300)} for h in range(24)]
        else:
            raise AssertionError(path)
        return io.BytesIO(json.dumps({"response": {"header": {"resultCode": "00"}, "body": {"items": {"item": items}}}}).encode())


class FakeClock:
    def __init__(self):
        self.now = 1_000.0

    def __call__(self):
        return self.now


class FlightInfoTest(unittest.TestCase):
    def setUp(self):
        self.apis = FakeApis()
        self.clock = FakeClock()
        self.info = FlightInfo("key", opener=self.apis, clock=self.clock)

    def test_delays_are_minutes_on_a_clock_and_yesterday_is_not_a_delay(self):
        self.assertEqual(30, delay_minutes("1520", "1550"))
        self.assertEqual(15, delay_minutes("2350", "0005"))
        # Moved 45 minutes earlier, across midnight.
        self.assertEqual(-45, delay_minutes("0005", "2320"))
        self.assertIsNone(delay_minutes("1200", "2100"))
        self.assertIsNone(delay_minutes("1200", ""))

    def test_a_flight_number_at_gimpo_with_parking_and_queue(self):
        plan = self.info.plan("GMP", flight="lj 301")
        f = plan["flight"]
        self.assertEqual(("LJ301", 30, "D", False), (f["flightId"], f["delayMin"], f["line"], f["cancelled"]))
        self.assertEqual(["국내선 제1주차장", "국내선 제2주차장"], [p["name"] for p in plan["parking"]])
        self.assertEqual([0, 355], [p["free"] for p in plan["parking"]])
        self.assertEqual(12, plan["waitMin"])

    def test_a_spoken_time_and_destination_find_the_flight_there(self):
        plan = self.info.plan("GMP", minute=15 * 60 + 35, words="광주 세 시 반")
        self.assertEqual("OZ8957", plan["flight"]["flightId"])

    def test_several_flights_close_to_the_time_are_not_guessed(self):
        # LJ301 at 15:20 and KE1215 at 15:30 to 제주: "세 시 반 제주" could be either.
        plan = self.info.plan("GMP", minute=15 * 60 + 25, words="세 시 반 제주")
        self.assertIsNone(plan["flight"])
        self.assertEqual(2, plan["candidates"])

    def test_a_time_alone_is_trusted_only_when_one_flight_fits(self):
        self.assertIsNone(self.info.plan("GMP", minute=15 * 60 + 30, words="세 시 반")["flight"])
        self.assertEqual("7C112", self.info.plan("GMP", minute=17 * 60, words="다섯 시")["flight"]["flightId"])

    def test_a_named_destination_at_another_time_is_not_guessed(self):
        self.assertIsNone(self.info.plan("GMP", minute=9 * 60, words="아홉 시 제주")["flight"])

    def test_cancelled_flights_say_so(self):
        self.assertTrue(self.info.plan("GMP", flight="7C112")["flight"]["cancelled"])

    def test_incheon_gives_terminal_counters_and_a_busy_hour(self):
        plan = self.info.plan("ICN", flight="KE705", hour=18)
        f = plan["flight"]
        self.assertEqual(("T2", "E1-E7", 21, "I"), (f["terminal"], f["counters"], f["delayMin"], f["line"]))
        self.assertTrue(plan["busy"])
        self.assertFalse(self.info.plan("ICN", flight="KE705", hour=10)["busy"])
        self.assertEqual([], plan["parking"])

    def test_answers_are_cached_for_two_minutes(self):
        self.info.plan("GMP", flight="LJ301")
        calls = len(self.apis.calls)
        self.info.plan("GMP", flight="LJ301")
        self.assertEqual(calls, len(self.apis.calls))
        self.clock.now += 121
        self.info.plan("GMP", flight="LJ301")
        self.assertGreater(len(self.apis.calls), calls)

    def test_long_lists_are_read_page_by_page(self):
        self.apis.kac_status = [kac_flight(f"XX{n}", "제주", f"{6 + n // 60:02d}{n % 60:02d}") for n in range(230)]
        self.assertEqual(230, len(self.info._departures("GMP")))
        self.assertEqual(3, sum("flight-status" in c for c in self.apis.calls))

    def test_a_gateway_error_is_an_error_not_an_empty_list(self):
        self.apis.gateway_error = True
        with self.assertRaises(RuntimeError):
            self.info.plan("GMP", minute=15 * 60, words="제주")

    def test_unknown_airport_is_rejected(self):
        with self.assertRaises(ValueError):
            self.info.plan("XXX", flight="KE1")


if __name__ == "__main__":
    unittest.main()
