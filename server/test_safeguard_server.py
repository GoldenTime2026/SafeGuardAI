import hashlib
import hmac
import io
import json
import unittest

from safeguard_server import (DisasterFeed, GuardianOutbox, HazardStore, MeshStore, SolapiSender, TelltaleStats,
                              landmark, mesh_csv, mesh_geojson)


class FakeClock:
    def __init__(self):
        self.now = 1_000.0

    def __call__(self):
        return self.now


def report(kind, device, lat=35.2493, lon=128.7200, heading=90.0):
    return {"type": kind, "lat": lat, "lon": lon, "heading": heading, "device": device, "source": "VEHICLE"}


class HazardStoreTest(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.store = HazardStore(clock=self.clock)

    def test_a_crash_is_shared_at_once(self):
        self.store.add(report("CRASH", "a"))
        hazards = self.store.near(35.2493, 128.7000, 5000)
        self.assertEqual(1, len(hazards))
        self.assertTrue(hazards[0]["confirmed"])

    def test_hard_braking_needs_two_different_cars(self):
        self.store.add(report("HARD_BRAKING", "a"))
        self.store.add(report("HARD_BRAKING", "a", lon=128.7201))
        self.assertEqual([], self.store.near(35.2493, 128.7, 5000))
        self.store.add(report("HARD_BRAKING", "b", lon=128.7202))
        hazards = self.store.near(35.2493, 128.7, 5000)
        self.assertEqual(2, hazards[0]["cars"])

    def test_opposite_direction_is_a_different_hazard(self):
        self.store.add(report("HARD_BRAKING", "a", heading=90))
        self.store.add(report("HARD_BRAKING", "b", heading=270))
        self.assertEqual([], self.store.near(35.2493, 128.7, 5000))

    def test_hazards_expire(self):
        self.store.add(report("HARD_BRAKING", "a"))
        self.store.add(report("HARD_BRAKING", "b"))
        self.clock.now += 6 * 60
        self.assertEqual([], self.store.near(35.2493, 128.7, 5000))

    def test_far_hazards_are_not_returned(self):
        self.store.add(report("CRASH", "a", lat=37.5))
        self.assertEqual([], self.store.near(35.2493, 128.7, 5000))

    def test_rate_limit_per_device(self):
        for i in range(10):
            self.store.add(report("DEBRIS", "a", lon=128.7 + i * 0.01))
        with self.assertRaises(PermissionError):
            self.store.add(report("DEBRIS", "a"))

    def test_rain_from_two_cars_a_kilometre_apart_is_confirmed(self):
        self.store.add(report("RAIN", "a", lon=128.7200))
        self.assertEqual([], self.store.near(35.2493, 128.7, 5000))
        self.store.add(report("RAIN", "b", lon=128.7310))
        hazards = self.store.near(35.2493, 128.7, 5000)
        self.assertEqual(1, len(hazards))
        self.assertEqual(2, hazards[0]["cars"])

    def test_near_misses_need_three_cars(self):
        for device in ("a", "b"):
            self.store.add(report("NEAR_MISS", device))
        self.assertEqual([], self.store.near(35.2493, 128.7, 5000))
        self.store.add(report("NEAR_MISS", "c"))
        self.assertEqual(["NEAR_MISS"], [h["type"] for h in self.store.near(35.2493, 128.7, 5000)])

    def test_faded_lanes_are_kept_for_the_map_but_never_sent_to_cars(self):
        for device in ("a", "b", "c", "d"):
            hazard = self.store.add(report("LANE_FADE", device))
        self.assertTrue(hazard["confirmed"])
        self.assertEqual([], self.store.near(35.2493, 128.7, 5000))

    def test_unknown_type_is_rejected(self):
        with self.assertRaises(ValueError):
            self.store.add(report("UFO", "a"))


class FakeSender:
    def __init__(self, ok=True, error=None):
        self.ok, self.error, self.sent = ok, error, []

    def send(self, to, text):
        if self.error:
            raise self.error
        self.sent.append((to, text))
        return self.ok, "test"


class FakeSolapi:
    """Answers the send call with a message id, then each result query with the next queued message."""

    def __init__(self, *results):
        self.results, self.requests = list(results), []

    def __call__(self, request, timeout):
        self.requests.append(request)
        if request.get_method() == "POST":
            body = {"messageId": "M1", "statusCode": "2000"}
        else:
            body = {"messageList": {"M1": self.results.pop(0)}}
        return FakeResponse(json.dumps(body))


class FakeResponse(io.BytesIO):
    def __init__(self, text):
        super().__init__(text.encode("utf-8"))
        self.status = 200


class GuardianOutboxTest(unittest.TestCase):
    def test_without_a_sender_the_alert_is_recorded_only(self):
        outbox = GuardianOutbox()
        delivered, note = outbox.send("사고 위치", "01012345678")
        self.assertFalse(delivered)
        self.assertIn("미설정", note)
        self.assertEqual("010-****-5678", outbox.sent[0]["to"])

    def test_a_configured_sender_delivers(self):
        sender = FakeSender()
        delivered, note = GuardianOutbox(sender).send("사고 위치", "01012345678")
        self.assertTrue(delivered)
        self.assertEqual([("01012345678", "사고 위치")], sender.sent)

    def test_no_number_or_a_failing_provider_is_not_delivered(self):
        self.assertFalse(GuardianOutbox(FakeSender()).send("사고 위치", None)[0])
        self.assertFalse(GuardianOutbox(FakeSender()).send("사고 위치", "02123")[0])
        delivered, note = GuardianOutbox(FakeSender(error=OSError("down"))).send("사고 위치", "01012345678")
        self.assertFalse(delivered)
        self.assertIn("실패", note)

    def test_a_carrier_refusal_or_an_unknown_result_keeps_the_fallback(self):
        delivered, note = GuardianOutbox(FakeSender(ok=False)).send("사고 위치", "01012345678")
        self.assertFalse(delivered)
        self.assertIn("대신 보내", note)
        delivered, note = GuardianOutbox(FakeSender(ok=None)).send("사고 위치", "01012345678")
        self.assertFalse(delivered)
        self.assertIn("확인 안 됨", note)

    def test_solapi_waits_for_the_carrier_result(self):
        opener = FakeSolapi({"status": "SENDING", "statusCode": "3000"}, {"status": "COMPLETE", "statusCode": "4000"})
        sender = SolapiSender("key", "secret", "01000000000", opener=opener, sleep=lambda s: None)
        self.assertEqual((True, "4000"), sender.send("01012345678", "사고 위치"))
        self.assertTrue(opener.requests[-1].full_url.endswith("messageId=M1"))

    def test_solapi_reports_a_refusal_as_not_delivered(self):
        refused = {"status": "COMPLETE", "statusCode": "3113", "reason": "번호도용문자차단서비스에 가입된 발신번호 사용"}
        sender = SolapiSender("key", "secret", "01000000000", opener=FakeSolapi(refused), sleep=lambda s: None)
        delivered, reason = sender.send("01012345678", "사고 위치")
        self.assertFalse(delivered)
        self.assertIn("3113", reason)

    def test_solapi_gives_up_waiting_without_claiming_delivery(self):
        pending = [{"status": "SENDING", "statusCode": "3000"}] * 20
        sender = SolapiSender("key", "secret", "01000000000", opener=FakeSolapi(*pending), sleep=lambda s: None)
        self.assertIsNone(sender.send("01012345678", "사고 위치")[0])

    def test_solapi_signature_is_hmac_of_date_and_salt(self):
        sender = SolapiSender("key", "secret", "0212345678")
        header = sender.auth_header("2026-10-27T00:00:00Z", "abc")
        expected = hmac.new(b"secret", b"2026-10-27T00:00:00Zabc", hashlib.sha256).hexdigest()
        self.assertEqual(f"HMAC-SHA256 apiKey=key, date=2026-10-27T00:00:00Z, salt=abc, signature={expected}", header)

    def test_solapi_is_configured_only_with_all_three_values(self):
        self.assertIsNone(SolapiSender.from_env({"SOLAPI_API_KEY": "k", "SOLAPI_API_SECRET": "s"}))
        self.assertIsNotNone(SolapiSender.from_env({"SOLAPI_API_KEY": "k", "SOLAPI_API_SECRET": "s", "SOLAPI_SENDER": "1"}))


class TelltaleStatsTest(unittest.TestCase):
    def test_counts_by_model(self):
        stats = TelltaleStats()
        for t in ["TPMS", "TPMS", "ENGINE_OIL"]:
            stats.add("현대 2024년식", t)
        stats.add("기아 2022년식", "ABS")
        self.assertEqual([{"telltale": "TPMS", "count": 2}, {"telltale": "ENGINE_OIL", "count": 1}],
                         stats.top("현대 2024년식"))
        with self.assertRaises(ValueError):
            stats.add("", "TPMS")


class MeshStoreTest(unittest.TestCase):
    LAT, LON = 35.2493, 128.7150

    def setUp(self):
        self.clock = FakeClock()
        self.clock.now = 1_793_000_000.0  # 2026-10-26, mid-afternoon in Korea
        self.mesh = MeshStore(clock=self.clock)

    def test_a_cell_is_published_only_after_three_different_cars(self):
        self.mesh.record("SLIP", self.LAT, self.LON, "car-a")
        self.mesh.record("SLIP", self.LAT, self.LON, "car-a")
        self.mesh.record("SLIP", self.LAT + 0.0005, self.LON, "car-b")
        self.assertEqual([], self.mesh.cells())
        self.mesh.record("SLIP", self.LAT, self.LON + 0.0005, "car-c")
        cell = self.mesh.cells()[0]
        self.assertEqual({"events": 4, "cars": 3}, {k: cell["types"]["SLIP"][k] for k in ("events", "cars")})
        self.assertFalse(cell["demo"])

    def test_past_days_keep_counts_but_no_device_hashes(self):
        for car in ("a", "b", "c"):
            self.mesh.record("ICE", self.LAT, self.LON, car)
        self.clock.now += 86_400
        self.mesh.record("ICE", self.LAT, self.LON, "a")
        raw_days = {d for (d,) in self.mesh.db.execute("SELECT day FROM raw")}
        self.assertEqual({self.mesh._day()}, raw_days)
        self.assertEqual(4, self.mesh.cells()[0]["types"]["ICE"]["cars"])  # car-days: a counts again today

    def test_period_and_type_filters(self):
        for car in ("a", "b", "c"):
            self.mesh.record("HEAVY_RAIN", self.LAT, self.LON, car)
            self.mesh.record("HARD_BRAKING", self.LAT, self.LON, car)
        self.assertEqual({"HEAVY_RAIN"}, set(self.mesh.cells(types={"HEAVY_RAIN"})[0]["types"]))
        self.clock.now += 40 * 86_400
        self.assertEqual([], self.mesh.cells(days=30))
        self.assertEqual(1, len(self.mesh.cells(days=90)))

    def test_demo_rows_are_flagged_and_exported(self):
        self.mesh.add_demo(self.mesh._day(), self.LAT, self.LON, "STOPPED_VEHICLE", 5, 4)
        cells = self.mesh.cells()
        self.assertTrue(cells[0]["demo"])
        feature = mesh_geojson(cells)["features"][0]
        self.assertEqual("Polygon", feature["geometry"]["type"])
        self.assertEqual(4, feature["properties"]["cars"])
        jinrye = {"name": "진례", "road": "남해선", "lat": 35.249324, "lon": 128.758979}
        csv = mesh_csv(cells, [jinrye]).splitlines()
        self.assertEqual("cell,lat,lon,near,type,events,car_days,last_day,demo", csv[0])
        self.assertIn('"진례 졸음쉼터(남해선) 서쪽 4.1km",STOPPED_VEHICLE,5,4,' + self.mesh._day() + ",1", csv[1])

    def test_landmarks_name_the_nearest_rest_area_only_when_close(self):
        jinrye = {"name": "진례", "road": "남해선", "lat": 35.249324, "lon": 128.758979}
        self.assertEqual("", landmark(35.9, 128.7, [jinrye]))
        self.assertEqual("", landmark(35.2, 128.7, []))


def disaster_row(sn, created, regions, text="13시부로 김해시 호우주의보 발효. [김해시]"):
    return {"SN": sn, "CRT_DT": created, "RCPTN_RGN_NM": regions, "EMRG_STEP_NM": "안전안내", "DST_SE_NM": "호우",
            "MSG_CN": text}


class FakeSafetydata:
    """Serves queued pages of the disaster SMS API and records the URLs asked for."""

    def __init__(self, *pages, code="00"):
        self.pages, self.code, self.urls = list(pages), code, []

    def __call__(self, url, timeout):
        self.urls.append(url)
        body = self.pages.pop(0) if self.pages else []
        return FakeResponse(json.dumps({"header": {"resultCode": self.code}, "body": body}))


class DisasterFeedTest(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.clock.now = 1_786_854_600.0  # 2026-08-16 13:30 KST

    def test_regions_are_split_and_the_text_is_kept_word_for_word(self):
        rows = [disaster_row(266640, "2026/08/16 13:05:32", "경상남도 김해시 ,경상남도 창원시 의창구 ")]
        feed = DisasterFeed("key", opener=FakeSafetydata(rows), clock=self.clock)
        self.assertEqual(1, feed.fetch())
        message = feed.after(0)[0]
        self.assertEqual(["경상남도 김해시", "경상남도 창원시 의창구"], message["regions"])
        self.assertEqual("13시부로 김해시 호우주의보 발효. [김해시]", message["text"])
        self.assertEqual(1_786_853_132_000, message["createdAt"])  # 13:05:32 KST

    def test_cars_get_only_newer_and_fresh_messages(self):
        rows = [disaster_row(1, "2026/08/16 10:00:00", "경상남도 "), disaster_row(2, "2026/08/16 13:00:00", "경상남도 "),
                disaster_row(3, "2026/08/16 13:20:00", "경상남도 ")]
        feed = DisasterFeed("key", opener=FakeSafetydata(rows), clock=self.clock)
        feed.fetch()
        self.assertEqual([2, 3], [m["sn"] for m in feed.after(0)])  # 10:00 is older than three hours
        self.assertEqual([3], [m["sn"] for m in feed.after(2)])
        self.assertEqual(0, feed.fetch())  # the same message again is not new

    def test_pages_are_followed_and_the_date_starts_an_hour_back(self):
        full = [disaster_row(i, "2026/08/16 13:00:00", "경상남도 ") for i in range(DisasterFeed.PAGE_ROWS)]
        opener = FakeSafetydata(full, [disaster_row(5000, "2026/08/16 13:10:00", "경상남도 ")])
        self.clock.now = 1_786_807_800.0  # 2026-08-16 00:30 KST: three hours back is still the 15th
        DisasterFeed("key", opener=opener, clock=self.clock).fetch()
        self.assertEqual(2, len(opener.urls))
        self.assertIn("crtDt=20260815", opener.urls[0])
        self.assertIn("pageNo=2", opener.urls[1])

    def test_an_api_error_is_raised_not_taken_as_no_messages(self):
        with self.assertRaises(IOError):
            DisasterFeed("key", opener=FakeSafetydata([], code="30"), clock=self.clock).fetch()

    def test_configured_only_with_a_key(self):
        self.assertIsNone(DisasterFeed.from_env({}))
        self.assertIsNotNone(DisasterFeed.from_env({"SAFETYDATA_KEY": "k"}))


if __name__ == "__main__":
    unittest.main()
