"""Demo: other SafeGuard cars ahead on the 남해선 (Busan-bound) report what they found.

Run after starting the server, then play the `shared_hazards` scenario on the emulator:
    python server/virtual_cars.py
The emulator car drives east from 35.249324,128.6985 at 100 km/h and should hear, in order:
  - "1킬로미터 앞에서 차량 2대가 급정거했어요. 정체 꼬리에 주의하세요."   (two cars braked hard, 1.5 km ahead)
  - "…앞에 사고 차량이 있어요. 속도를 줄이세요."                       (a car crashed, 3.3 km ahead)
  - "…앞에 폭우가 내리고 있어요. …"                                   (two cars' wipers at full speed, ~4.5 km ahead)
and nothing about the crash reported by a car on the opposite carriageway.
"""

import json
import sys
import urllib.request

SERVER = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8787"
LAT = 35.249324

REPORTS = [
    # Two different cars brake hard at the same spot within a minute: the tail of a sudden jam.
    {"type": "HARD_BRAKING", "lat": LAT, "lon": 128.7150, "heading": 90, "device": "virtual-car-a", "source": "VEHICLE"},
    {"type": "HARD_BRAKING", "lat": LAT, "lon": 128.7152, "heading": 90, "device": "virtual-car-b", "source": "VEHICLE"},
    # A car further ahead deploys its airbags; its app shares the crash before asking the driver anything.
    {"type": "CRASH", "lat": LAT, "lon": 128.7350, "heading": 90, "device": "virtual-car-c", "source": "VEHICLE"},
    # Two cars further on switch their wipers to full speed: heavy rain ahead, said about 3 km before.
    {"type": "HEAVY_RAIN", "lat": LAT, "lon": 128.7480, "heading": 90, "device": "virtual-car-e", "source": "VEHICLE"},
    {"type": "HEAVY_RAIN", "lat": LAT, "lon": 128.7550, "heading": 90, "device": "virtual-car-f", "source": "VEHICLE"},
    # A crash on the Suncheon-bound carriageway (heading west): cars going east must not be warned.
    {"type": "CRASH", "lat": LAT + 0.0003, "lon": 128.7250, "heading": 270, "device": "virtual-car-d", "source": "VEHICLE"},
]


def post(path, body):
    request = urllib.request.Request(
        SERVER + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}, method="POST",
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.loads(response.read())


if __name__ == "__main__":
    for report in REPORTS:
        hazard = post("/v1/reports", report)["hazard"]
        print(f"{report['device']}: {report['type']} heading {report['heading']} -> {hazard['cars']} car(s), confirmed={hazard['confirmed']}")
