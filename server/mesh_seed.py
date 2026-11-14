"""Demo data for the Safety Mesh dashboard: a year of anonymous cell counts from virtual cars in 경남.

The rows are marked demo=1 and the dashboard says so; nothing here is real driving data.
    python server/mesh_seed.py            # writes server/mesh.sqlite (the server's default database)
    python server/mesh_seed.py --reset    # removes the demo rows first
Run it with the server stopped, or restart the server afterwards.

The pattern is made to look like what the cars would report, not measured:
  - the 남해선 between 창원 and 김해: hazard-light stops and hard braking at junctions, every day;
  - slips at a few curves, mostly on rainy days, and many more on icy ones;
  - ice on two bridges and a mountain pass, only on cold winter days;
  - heavy rain over a band of cells on summer storm days, light rain more often.
"""

import argparse
import datetime
import os
import random

from safeguard_server import MeshStore

HERE = os.path.dirname(os.path.abspath(__file__))

# 남해선 (Namhae Expressway) between 창원 and 김해, roughly: the scenarios drive along this line.
HIGHWAY = [(35.2700, 128.6000), (35.2600, 128.6600), (35.2493, 128.7038), (35.2493, 128.7590), (35.2208, 128.8703)]
STOPS = [(35.2493, 128.7150), (35.2560, 128.6800), (35.2330, 128.8200)]
JUNCTIONS = [(35.2650, 128.6300), (35.2493, 128.7350), (35.2280, 128.8450)]
CURVES = [(35.2530, 128.6950), (35.2470, 128.7700), (35.2380, 128.8050), (35.2620, 128.6550)]
ICE = [(35.2600, 128.6400), (35.2395, 128.7240), (35.2850, 128.6650)]
CRASHES = [(35.2493, 128.7350), (35.2400, 128.7900)]
# Merges and blind junctions where collision warnings pile up; a stretch with worn lane paint.
NEAR_MISSES = [(35.2493, 128.7340), (35.2650, 128.6310), (35.2300, 128.8350)]
FADED_LANES = [(35.2493, 128.7450), (35.2493, 128.7500)]


def along(points, t):
    """A point at fraction t (0..1) along the polyline."""
    seg = min(int(t * (len(points) - 1)), len(points) - 2)
    local = t * (len(points) - 1) - seg
    (a1, o1), (a2, o2) = points[seg], points[seg + 1]
    return a1 + (a2 - a1) * local, o1 + (o2 - o1) * local


def poisson(rng, mean):
    # Knuth: fine for the small means used here.
    limit, k, p = pow(2.718281828, -mean), 0, 1.0
    while True:
        p *= rng.random()
        if p <= limit:
            return k
        k += 1


def seed(store, today, rng):
    rows = 0

    def put(day, lat, lon, kind, cars):
        nonlocal rows
        if cars > 0:
            store.add_demo(day, lat, lon, kind, cars + poisson(rng, cars * 0.3), cars)
            rows += 1

    for back in range(1, 366):
        date = today - datetime.timedelta(days=back)
        day = date.isoformat()
        summer = date.month in (7, 8, 9)
        winter = date.month in (12, 1, 2)
        rainy = rng.random() < (0.35 if summer else 0.18)
        storm = rainy and summer and rng.random() < 0.35
        icy = winter and rng.random() < 0.3
        weekday = date.weekday() < 5

        for lat, lon in STOPS:
            put(day, lat, lon, "STOPPED_VEHICLE", poisson(rng, 0.5 if rainy else 0.3))
        for lat, lon in JUNCTIONS:
            put(day, lat, lon, "HARD_BRAKING", poisson(rng, 1.2 if weekday else 0.6))
        for lat, lon in CURVES:
            put(day, lat, lon, "SLIP", poisson(rng, 2.5 if icy else 0.8 if rainy else 0.05))
        if icy:
            for lat, lon in ICE:
                put(day, lat, lon, "ICE", poisson(rng, 2.0))
        for lat, lon in CRASHES:
            put(day, lat, lon, "CRASH", 1 if rng.random() < 0.02 else 0)
        if rainy:
            kind = "HEAVY_RAIN" if storm else "RAIN"
            for _ in range(rng.randint(6, 16)):
                lat, lon = along(HIGHWAY, rng.random())
                put(day, lat + rng.uniform(-0.01, 0.01), lon, kind, rng.randint(1, 5))

    # The driver-assist layers draw from their own generator so adding them left the older layers as they were.
    adas = random.Random(f"adas-{today.isoformat()}")
    for back in range(1, 366):
        date = today - datetime.timedelta(days=back)
        weekday = date.weekday() < 5
        for lat, lon in NEAR_MISSES:
            n = poisson(adas, 1.5 if weekday else 0.7)
            if n > 0:
                store.add_demo(date.isoformat(), lat, lon, "NEAR_MISS", n + poisson(adas, n * 0.3), n)
                rows += 1
        for lat, lon in FADED_LANES:
            n = poisson(adas, 0.6)
            if n > 0:
                store.add_demo(date.isoformat(), lat, lon, "LANE_FADE", n, n)
                rows += 1
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--db", default=os.path.join(HERE, "mesh.sqlite"))
    parser.add_argument("--reset", action="store_true", help="delete earlier demo rows first")
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    store = MeshStore(args.db)
    if args.reset:
        store.db.execute("DELETE FROM daily WHERE demo = 1")
        store.db.commit()
    rows = seed(store, datetime.date.today(), random.Random(args.seed))
    print(f"wrote {rows} demo rows to {args.db}")


if __name__ == "__main__":
    main()
