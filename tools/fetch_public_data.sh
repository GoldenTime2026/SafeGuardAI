#!/usr/bin/env bash
# Refreshes the TAAS accident-hotspot seeds in app/src/main/assets/public with real data.
# The service key is read from local.properties (DATA_GO_KR_KEY=...) or the environment and is
# never printed or written to the repo.
#   ./tools/fetch_public_data.sh [searchYearCd=2024] [siDo=48 (경상남도)] [guGun=]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="${DATA_GO_KR_KEY:-$(grep -E '^DATA_GO_KR_KEY=' "$ROOT/local.properties" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r\n ')}"
: "${KEY:?set DATA_GO_KR_KEY in local.properties or the environment}"
YEAR="${1:-2024}"; SIDO="${2:-48}"; GUGUN="${3:-}"
OUT="$ROOT/app/src/main/assets/public"
# URL-encode the key with curl itself; the dummy request is expected to fail to connect.
ENC="$( (curl -Gso /dev/null -w '%{url_effective}' --data-urlencode "k=$KEY" http://127.0.0.1:9/ || true) | sed 's/.*k=//')"

fetch() { # <endpoint> <out-file> <dataset label>
  local tmp; tmp="$(mktemp)"
  curl -fsS "https://apis.data.go.kr/B552061/$1?serviceKey=$ENC&searchYearCd=$YEAR&siDo=$SIDO&guGun=$GUGUN&type=json&numOfRows=1000&pageNo=1" -o "$tmp"
  if ! grep -q '"resultCode":"00"' "$tmp"; then
    echo "skip $2: $(head -c 120 "$tmp")"; rm -f "$tmp"; return
  fi
  # Keep the raw API shape (items.item); TaasHotspotAdapter reads it directly. Prepend provenance.
  printf '{"_meta":{"demo":false,"dataset":"%s","searchYearCd":%s,"siDo":"%s","fetched":"%s"},' "$3" "$YEAR" "$SIDO" "$(date +%F)" > "$OUT/$2"
  tail -c +2 "$tmp" >> "$OUT/$2"
  rm -f "$tmp"
  echo "wrote $2 ($(grep -o '"afos_fid"' "$OUT/$2" | wc -l | tr -d ' ') points)"
}

fetch "schoolzoneChild/getRestSchoolzoneChild" taas_child_schoolzone.json "한국도로교통공단_어린이보호구역 내 어린이 교통사고 다발지역 (data.go.kr 15058311)"
fetch "frequentzoneTmzon/getRestFrequentzoneTmzon" taas_holiday.json "한국도로교통공단_연휴기간별 교통사고 다발지역 (data.go.kr 15058759)"
fetch "frequentzoneChild/getRestFrequentzoneChild" taas_child_pedestrian.json "한국도로교통공단_보행어린이 교통사고 다발지역 (data.go.kr 15058925)"
