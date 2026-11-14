#!/usr/bin/env bash
# Downloads 한국도로공사 전국졸음쉼터표준데이터 (data.go.kr 15028203, CSV, no key needed)
# and writes app/src/main/assets/public/rest_areas.json.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
meta=$(curl -s 'https://www.data.go.kr/tcs/dss/selectFileDataDownload.do' \
  --data 'publicDataPk=15028203&publicDataDetailPk=uddi:546c12c2-8e32-48a0-ab3f-eacbae99de20&fileDetailSn=1')
file_id=$(echo "$meta" | grep -o '"atchFileId":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -L "https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=${file_id}&fileDetailSn=1" -o "$tmp/rest.csv"
python3 - "$tmp/rest.csv" app/src/main/assets/public/rest_areas.json <<'PY'
import csv, json, sys
rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8-sig")))
points = []
for r in rows:
    try:
        lat, lon = float(r["위도"]), float(r["경도"])
    except ValueError:
        continue
    points.append({
        "name": r["졸음쉼터명"].strip(),
        "road": r["도로노선명"].strip(),
        "direction": r["도로노선방향"].strip(),
        "lat": round(lat, 6),
        "lon": round(lon, 6),
        "toilet": r["화장실유무"].strip() == "Y",
    })
dates = sorted({r["데이터기준일자"] for r in rows if r.get("데이터기준일자")})
out = {
    "_meta": {
        "source": "한국도로공사 전국졸음쉼터표준데이터 (공공데이터포털 15028203)",
        "license": "이용허락범위 제한 없음",
        "baseDate": dates[-1] if dates else "",
        "count": len(points),
    },
    "points": points,
}
json.dump(out, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"{len(points)} rest areas, base date {out['_meta']['baseDate']}")
PY
