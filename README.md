# SafeGuard AI

**차가 먼저 아는 위험을, 운전자에게 먼저 알려주는 안전 코파일럿** — Pleos Connect 차량용 앱 (현대자동차그룹 차량용 앱 개발 공모전 출품작)

와이퍼·경고등·핸들 조작 같은 차량 신호로 악천후, 노면 위험, 정체, 졸음, 차량 이상, 사고 상황을 감지하고, 음성·앰비언트 라이트·공조 제어로 대응합니다.

## 기능

| # | 기능 | 하는 일 |
|---|---|---|
| ① | 악천후 안전 코치 | 와이퍼·안개등·결빙 경고등으로 노면 상태를 판단해 감속 기준(×0.8 / ×0.5)과 과속을 안내하고, 폭우·안개·야간 비에는 제상·외기·미러 열선을 켠 뒤 맑아지면 원래대로 되돌림 |
| ② | 위험지도 | TAAS 사고다발지역(어린이·연휴, 경남 2024)과 결빙 지점, 앞차들의 빙판 신호로 만든 지점, 비상등 정차 차량을 300 m·1 km 앞에서 안내. 등하교 시간·연휴·영하·비가 겹치면 등급을 올림 |
| — | 출발 브리핑 | 시동 후 정차 중 한 번, 날씨·경고등·주변 주의 지점을 한두 문장으로 |
| ③ | 정체 브리핑 | 정체 거리·원인·속도·소요시간을 한 문장으로 알리고 "우회할까요?" → "응"이면 재탐색. 정체 중 내기순환, 긴 정체에는 휴식 모드 제안 |
| ④ | 졸음 케어 | 졸음 경고등·연속 주행시간(보조: 핸들 피로)으로 휴식 권유 → 각성 공조 프로그램 → 졸음쉼터 경유지 추가 → 쉼터에서 파워냅(등받이·조명·음성 깨우기) |
| ⑤ | 경고등 가이드 | 경고등 19종을 심각도별로 분류. 주행 중엔 한 문장, 정차 후엔 조치 단계 카드와 블루핸즈 경로 안내 |
| ⑥ | 사고 골든타임 케어 | 에어백 전개 시 다른 안내를 모두 멈추고 "괜찮으세요?"로 의식 확인, 119에 말할 위치(주소·졸음쉼터 기준·방향·좌표)와 대피 요령 안내, 등록한 보호자에게 문자(서버에 문자 서비스가 설정된 경우) |
| 재난 | 재난문자 음성 브리핑 | 폰 재난문자는 발송 순간 그 지역에 있던 폰에만 갑니다. ① **진입**: 발송 뒤 그 지역에 들어온 차에도 원문을 읽어 줌(기상 특보 3시간, 그 외 1시간) ② **경로 앞 예고**: 목적지·경유지 시군구의 문자를 "약 12분 뒤 지나갈 곳"으로 미리 낭독, 진입 때는 짧게 확인 ③ **차량 신호 결합**: 호우 문자 지역에서 와이퍼 최고·앞차 폭우 공유 → 지하차도·하천변 회피와 권장 속도, 대설·한파+영하, 강풍+80km/h 이상, 안개+안개 판단. 원문은 고치지 않고(공공누리 4유형) 출처 표시. 서버는 전국 새 문자만 주고 지역 비교는 차 안에서 하므로 위치가 서버로 가지 않음 |
| ⑥-2 | 비상 정차 케어 | 순정 사고 서비스(에어백 전개 시에만 동작)의 공백. 비상등을 켜고 제한속도 80km/h 이상 도로에 30초 이상 멈추면(에어백 미전개, 졸음쉼터 제외) 2차 사고 대피 안내, 신고 위치와 고속도로 긴급견인 1588-2504 표시, "응"이라고 하면 보호자 문자 |
| ①+ | 센서 가림 안내 | 비·눈·안개 속에서 전방 충돌방지·차로 유지 보조 경고등이 노랑으로 바뀌면 "폭우로 전방 센서가 가려진 것 같아요. 직접 조향하고 앞차와 거리를 넓히세요". 다시 켜지면 알림. 맑은 날 계속 꺼져 있으면 센서 확인 권유. 순정은 경고등만 켜고 이유를 말하지 않음 |
| — | 주차 세이프 브리핑 | 주차(P단)하는 순간, 이 지역 호우·홍수·태풍 재난문자나 폭우면 지하주차장·하천 옆을 피하라고, 대설이면 와이퍼를 세우라고, 한파·영하 10도 이하에 EV 배터리 30% 이하면 충전을, 비 오는데 창문·선루프가 열려 있으면 닫으라고 한 번에 안내 |
| — | 제시간 코치 | 내비 목적지가 공항·KTX/SRT역이면 "몇 시 비행기예요?" → "세 시 반 제주"나 "대한항공 1215"를 서버가 인천국제공항공사·한국공항공사 실시간 데이터에서 찾아 **실제 출발 시각(지연·앞당김)·국내선/국제선·결항·인천 터미널(다른 터미널로 가면 경고)·주차장 만차·수속 대기시간·출국장 혼잡**을 반영. 같은 시각에 여러 편이면 편명을 한 번 더 묻고, 이미 떠난 편이면 편명 확인을 요청. 주행 중 5분마다 다시 조회해 출발 시각이 바뀌면 안내. 데이터가 없으면(서버 없음·기차역) 도착 예정 + 수속 마감(국제선 60분·국내선 30분 전) + 주차·이동(공항 20분·역 10분)으로 여유 시간 계산. 여유면 "서두르지 않으셔도 돼요", 그런데도 과속하면 "속도를 줄여도 늦지 않아요", 늦으면 "시속 20km 더 내도 약 N분 빨라질 뿐" (순항 속도 기준, 정체 중엔 숫자를 말하지 않음) |
| — | 업무 운행기록부 | (기본 꺼짐) 출발·주차 때 계기판 누적 거리와 내비 주소를 기록하고 주차 때 "업무로 기록할까요?" → 국세청 「업무용승용차 운행기록부」 열 순서 CSV. 설정 화면에서 월 업무 비율, 미분류 수정 |
| — | 아차사고·차선 흐림 (Safety Mesh) | 전방 충돌·긴급 차로 유지 경고(경고등 깜빡임·빨강)를 아차사고로, 맑은 날 고속 주행 중 차로 유지 보조가 꺼지는 것을 차선 흐림으로 익명 공유. 서로 다른 차 3대 이상일 때만 확정. 아차사고 구간은 뒤차에 안내, 차선 흐림은 도로관리청 지도에만 |
| — | 세이프가드 스코어 | 급정거·과속(악천후엔 권장 속도 기준)·급조작·안전벨트·심야 운전·위험 안내 뒤 감속 여부로 0~100점. 주차할 때 점수와 함께 **돈으로 환산**: 법정 제한속도를 10km/h 넘게 3초 이상 달린 구간마다 단속됐다면 낼 과태료(승용차, 법제처 생활법령 2026-09-15 기준 4·7·10·13만 원, 어린이보호구역 08~20시 7·10·13·16만 원), 이번 달 평균이 보험 안전운전 할인 기준(현대해상 커넥티드카 특약: 월 70점 이상 1년 9달 → 5%)을 넘는지와 설정한 보험료 기준 할인액. 보험사 점수와는 다를 수 있음을 설정에 표시 |
| — | 정비 알림 | 누적 주행거리로 엔진오일·타이어 위치 교환·에어컨 필터·브레이크액 주기를 계산해 정차 중 하루 한 번 안내. 주행 중 엔진오일·브레이크액 부족 경고. 차대번호로 제조사·연식 표시 |
| — | 크루즈 확인 | 크루즈 설정 속도가 스쿨존(30 km/h)이나 악천후 권장 속도보다 높으면 안내 (설정 변경은 운전자가) |
| — | 스쿨존 회피 경로 | 평일 등하교 시간에 경로상 어린이보호구역이 있으면 "피해 가는 길로 바꿀까요?" → "응"이면 내비 경로 옵션 변경 |
| — | Gleo 음성 명령 | "세이프가드, 나 졸려" / "앞에 낙하물 있어"(위험 제보) / "브리핑해 줘" |
| — | 차량 간 위험 공유 (2차) | 급정거·사고·빙판·제보를 서버로 공유하고, 여러 차가 확인한 같은 방향 위험만 뒤차에 안내. 차종별 경고등 통계 수집 |

정차 중 화면의 **설정** 버튼으로 보호자 연락처, 정비 기록, 자동차보험료(점수 환산용)를 입력하고, **듣고 싶은 안내를 기능별로 켜고 끕니다**(날씨·사고다발지역·출발 브리핑·정체·스쿨존 경로·졸음·경고등·정비·재난문자·운전 점수·위험 자동 공유·주차 안내·제시간 도착·업무 운행기록부(기본 꺼짐)). 업무 운행기록부의 이번 달 요약과 국세청 서식 CSV 저장도 여기서 합니다. 사고·비상 정차 케어, 센서 가림 안내, 위급한 경고, 앞차가 확인한 위험은 끌 수 없습니다. 주행 중에는 열리지 않습니다.

## 구조

```
입력: VehicleGateway(Pleos SDK) · CarProperties(AAOS Car API) · LocationSource(NaviHelper) · ScenarioPlayer
        ▼
SignalHub → VehicleSnapshot
        ▼
RiskEngine (신호가 바뀔 때 + 1초마다) → 기능별 RiskRule → RiskEvent
        ▼
Arbiter (한 번에 하나, 우선순위 끼어들기, 쿨다운·TTL)
        ▼
WarningOutput → VoiceChannel(TTS) · AmbientChannel(조명) · VoiceConfirm(STT 응답)
```

위험 우선순위(낮을수록 먼저): 사고 0 → 긴급 경고 10 → 전방 위험 20 → 졸음 30 → 사고다발지역 35 → 날씨 40 → 정비 45 → 교통 50 → 정보 90

```
app/src/main/java/com/safeguard/ai/
├─ SafeGuardCore.kt     신호 → 규칙 → 중재 → 출력 연결
├─ SafeGuardService.kt  내비 뒤에서도 계속 도는 포그라운드 서비스
├─ MainActivity.kt      주행 화면 / ProbeActivity.kt SDK 검증
├─ core/      차량·위치 신호        ├─ risk/    엔진·중재기
├─ output/    음성·조명·음성 응답     ├─ features/ weather, hazard, briefing, traffic, drowsy, telltale, crash, share, score, maintenance
├─ data/publicdata/  공공데이터 어댑터  ├─ integration/ Gleo   └─ scenario/ 시연 재생기
├─ SettingsActivity.kt  정차 중 설정(보호자 연락처·정비 기록)
app/src/main/assets/  public/(공공데이터) · scenarios/(시연 17개) · schema.json(Gleo 명령)
server/   위험 공유 서버(Python 표준 라이브러리)와 가상 차량 데모
tools/    공공데이터 갱신 스크립트
```

## 실행 방법

### 요구 사항
- Android Studio (또는 Android SDK + JDK 17)
- Android SDK Platform 34
- Pleos Connect Emulator v2.1 (API 34 system image)
  - SDK Manager → SDK Update Sites에 추가: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
- (공유 서버) Python 3

### 빌드
```bash
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

릴리스 빌드(`./gradlew assembleRelease`)는 서명 키 위치를 `local.properties`에서 읽습니다. 키 파일과 비밀번호는 저장소에 넣지 않습니다.
```properties
release.storeFile=C:/Android/keys/safeguard-release.jks
release.storePassword=...
release.keyAlias=safeguard
release.keyPassword=...
```

### 설치·실행
```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant --user 10 com.safeguard.ai pleos.car.permission.FUSED_LOCATION
adb shell am start --user 10 -n com.safeguard.ai/.MainActivity
```

### 시연 시나리오
주행 화면 아래 버튼으로 실행하거나, 녹화할 때는 adb로 실행합니다.
```bash
adb shell am start-foreground-service --user 10 -n com.safeguard.ai/.SafeGuardService --es scenario weather_demo
adb shell am start-foreground-service --user 10 -n com.safeguard.ai/.SafeGuardService --es scenario stop
```

| 시나리오 | 내용 |
|---|---|
| `weather_demo` | ① 비 시작 → 과속 경고 → 폭우 시야 확보 → 비 오는 스쿨존 → 비 그침 |
| `school_rain_hotspot` | ② 등하교 시간 + 비 + 실제 어린이 사고다발지역(창원 반송중 부근) |
| `bridge_ice_crowd` | ② 앞차 3대의 빙판 신호로 생긴 지점을 뒤차에 경고 |
| `departure_briefing` | 비 오는 아침 출발 브리핑 |
| `congestion_reroute` | ③ 사고 정체 브리핑 → 우회 → 내기순환 → 휴식 모드 |
| `drowsy_care` | ④ 휴식 권유 → 각성 → 졸음쉼터 경유 → 파워냅 |
| `telltale_guide` | ⑤ 타이어 공기압 → 엔진 과열 → 정차 후 조치 카드 |
| `crash_care` / `crash_care_ok` | ⑥ 응답 없음 / 운전자 응답 |
| `disaster_sms` | 재난문자: 창원 출발 → 창원 산사태 문자 낭독 → 목적지 김해 호우주의보 "경로 앞, 약 12분 뒤" 예고 → 김해 진입 확인 → 폭우+앞차 폭우 공유 → 지하차도·하천변 회피 안내 (2026-08-16 실제 원문, 시연 재생 표시). 앞차 공유를 보이려면 시작 전 서버에 `HEAVY_RAIN` 2대 제보 |
| `hazard_stop` | ⑥-2 에어백 없이 고속도로 비상 정차 → 대피 안내 → "응" → 보호자 문자 |
| `stage1_priority` / `stage1_interrupt` | 우선순위·끼어들기 |
| `voice_commands` | Gleo 음성 명령용 주행 상태 |
| `shared_hazards` | 차량 간 공유 (아래 서버 필요) |
| `safety_score` | 과속·급정거·위험 안내 뒤 감속 → 정차 시 세이프가드 스코어 안내 |
| `maintenance_cruise` | 정차 중 엔진오일 교체 알림 → 크루즈 80으로 스쿨존·폭우 → 엔진오일 부족 |
| `school_zone_route` | 등하교 시간 스쿨존 회피 경로 제안 → 응 |
| `pt_demo` | PT 통합 시연 약 5분: 폭우 → 앞 비상등 차량 → 정체 우회 → 졸음·파워냅 → 사고 |
| `sensor_blockage` | 비 → 폭우 → 보조 경고등 노랑 → 센서 가림 안내 → 복구 |
| `parking_briefing` | 김해 호우주의보(2026-08-16 실제 원문) 속 주행 → 폭우 속 주차 → 침수 회피·조수석 창문 안내 |
| `airport_ontime` | 인천공항 제1터미널 길 → "대한항공 705" → (2026-10-30 실제 응답 기록) 21분 지연·제2터미널 경고 → 과속 시 진정 → 정체로 빠듯 → 늦음 |
| `airport_live` | (확인용) 김포공항 → "진에어 6301" → 서버가 한국공항공사 실시간 API 조회. 결과는 그날 운항에 따라 다름 |
| `airport_tomorrow` | (확인용) 밤 11시 김포공항 → "대한항공 1059" → 오늘 편은 지났으므로 내일 편으로 보고 시간표 시각으로 계산("내일 오전 9시 15분 출발로 계산했어요"). 편명은 그날 운항에 따라 바꿔야 할 수 있음 |
| `logbook` | 31,250km 출발 → 12.4km 주행 → 주차 → "업무로 기록할까요?" → 응 (시연 중에만 기록 켬) |

시나리오를 시작할 때마다 이미 안내한 내용과 날씨 상태를 초기화하므로, 같은 시나리오를 연달아 돌려도 똑같이 재생됩니다. 공유 서버는 받은 위험을 20분~1시간 기억하므로, `pt_demo` 리허설 전에는 서버를 새로 켜거나(남해선 같은 구간의 공유 위험이 섞임) 서버 없이 돌립니다.

### Gleo 음성 명령 (에뮬레이터에서 Gleo 없이)
```bash
adb shell am broadcast --user 10 -p com.safeguard.ai -a WAKE_ME_UP
adb shell am broadcast --user 10 -p com.safeguard.ai -a REPORT_HAZARD --es ACTION_PARAM '{"hazardType":"DEBRIS"}'
adb shell am broadcast --user 10 -p com.safeguard.ai -a SAFETY_BRIEFING
```

### 위험 공유 서버 (2차 시연)
```bash
python server/safeguard_server.py          # http://127.0.0.1:8787
adb reverse tcp:8787 tcp:8787
# 앱에서 shared_hazards 시나리오 실행 후
python server/virtual_cars.py
cd server && python -m unittest test_safeguard_server
```
서버 주소는 `local.properties`의 `safeguard.server`로 바꿀 수 있습니다(기본 `http://127.0.0.1:8787`). 서버가 꺼져 있어도 앱의 차량 기능은 그대로 동작하고, 화면 위쪽에 "위험 공유 오프라인"으로 표시됩니다.

**보호자 문자**: 아래 환경변수가 있으면 사고 시 [SOLAPI](https://solapi.com)로 문자를 보냅니다. 없으면 서버에 기록만 하고, 차 화면에 문자 내용을 띄웁니다. 로그에는 번호를 가려서(`010-****-5678`) 남깁니다.
SOLAPI의 접수는 수신이 아니므로, 서버는 통신사 결과를 최대 12초 기다려 **수신 완료(4000)일 때만** 차에 "보냄"으로 답합니다. 거부(예: 3113 번호도용문자차단 가입 번호)나 결과 미확인이면 차는 "화면의 문자를 대신 보내 주세요"로 안내합니다.
```bash
export SOLAPI_API_KEY=... SOLAPI_API_SECRET=... SOLAPI_SENDER=01012345678   # 발신번호는 SOLAPI에 등록된 번호
```
- 발신번호가 통신사 **번호도용 문자차단 서비스**에 가입돼 있으면 모든 문자가 3113으로 거부됩니다(해지 필요).
- API Key에 허용 IP 제한이 있으면 403이 납니다. 발표장처럼 IP가 바뀌는 곳에서는 `0.0.0.0/0`을 허용하고, 끝나면 지웁니다.

**재난문자**: 재난안전데이터공유플랫폼(safetydata.go.kr)의 `행정안전부_긴급재난문자` API 인증키가 있으면 서버가 2분마다 전국 문자를 받아 둡니다(하루 약 720회, 개발 계정 한도 1,000회). 공공누리 제4유형(출처 표시·변경 금지)이라 앱은 원문을 고치지 않고 "행정안전부 … 문자예요"로 출처를 밝혀 읽습니다.
```bash
export SAFETYDATA_KEY=...   # 신청 시 IP는 *.*.*.*(전체) 또는 서버 IP, 호출 횟수 0(최대)
```

**HTTPS**: 실차는 Pleos 보안 가이드에 따라 HTTPS(TLS 1.2 이상)를 써야 합니다.
```bash
python server/safeguard_server.py --host 0.0.0.0 --port 443 --cert fullchain.pem --key privkey.pem
```

| API | 용도 |
|---|---|
| `POST /v1/reports`, `GET /v1/hazards` | 위험 제보·주변 위험 조회 |
| `POST /v1/guardian-alerts` | 보호자 문자 (`message`, `to`) |
| `POST /v1/telltales`, `GET /v1/telltale-stats?model=` | 차종별 경고등 통계 (차종 이름과 경고등 종류만 전송) |
| `GET /v1/mesh/cells?types=&days=`, `GET /v1/mesh/cells.csv` | Safety Mesh: 위험 격자 GeoJSON·CSV (도로관리청·기상 보조용) |
| `GET /v1/disaster-messages?after=` | 재난문자: 마지막으로 받은 번호 이후 최근 3시간의 새 문자 (위치를 보내지 않음) |
| `GET /mesh` | Safety Mesh 대시보드 (지도·점검 우선순위·CSV) |
| `GET /v1/flight?airport=GMP&flight=KE1215` 또는 `&time=1530&words=세 시 반 제주&hour=15` | 제시간 코치: 항공편(지연·결항·게이트·터미널·카운터), 주차장 남은 자리, 수속 대기, 인천 출국장 혼잡 |

제보 유형: `CRASH` `ACCIDENT` `STOPPED_VEHICLE` `DEBRIS` `POTHOLE` `OTHER` `ICE` `SLIP` `HARD_BRAKING` `RAIN` `HEAVY_RAIN` `NEAR_MISS`(서로 다른 차 3대, 24시간) `LANE_FADE`(3대, 7일, 차에는 보내지 않고 지도에만)

**공항 데이터**: `DATA_GO_KR_KEY`(공공데이터포털 인증키 하나)가 있으면 켜집니다. 활용신청: 인천국제공항공사 여객편 운항현황(다국어)·승객예고-출·입국장별, 한국공항공사 실시간 항공기 운항정보 검색·조회, 전국공항 실시간 주차정보, 공항 소요시간 정보. 응답은 2분간 캐시합니다(인천 개발계정 하루 1,000건). 한국공항공사 목록은 한 번에 100건까지라 페이지를 넘겨 읽고, 인천 운항현황은 항상 전체(약 1,200편)를 줍니다. 터미널 코드 P01=제1, P02=탑승동(체크인 제1), P03=제2. **두 운항 API 모두 오늘 운항만 줍니다**(날짜 파라미터 없음, 2026-10-31 확인). 그래서 오늘 편이 이미 지났고 12시간 안에 다시 오면(밤 11시에 내일 06:05 편) 내일 편으로 보고 시간표 시각으로만 계산하며, 지연·결항 확인은 자정이 지난 뒤 시작합니다.
```bash
export DATA_GO_KR_KEY=...   # local.properties와 같은 키
cd server && python -m unittest test_flights
```

**Safety Mesh (B2G/B2B)**: 차량 제보를 약 500m 격자·일 단위 개수로만 `server/mesh.sqlite`에 남깁니다. 당일 기기 토큰은 메모리에만 있는 소금(salt)으로 해시해 서로 다른 차를 세고, 날이 바뀌면 해시를 지우고 개수만 남깁니다. **서로 다른 차량·일 3 이상**인 격자만 공개합니다. 대시보드는 가장 가까운 졸음쉼터 기준으로 위치를 보여 줍니다.
```bash
python server/mesh_seed.py --reset   # 시연용 가상 1년치(경남 남해선 일대, demo 표시) — 서버를 끈 상태에서
python server/safeguard_server.py    # http://127.0.0.1:8787/mesh
```

### 테스트
```bash
./gradlew testDebugUnitTest
```

## 공공데이터
- TAAS 사고다발지역(한국도로교통공단, 경남 2024): 어린이 보호구역·어린이 보행자·연휴
- 졸음쉼터(한국도로공사 15028203, 253곳)
- 결빙 지점, 블루핸즈 위치는 시연용 데이터
- 갱신: `tools/fetch_public_data.sh`, `tools/fetch_rest_areas.sh` — 키는 `local.properties`의 `DATA_GO_KR_KEY`에서 읽으며 저장소와 앱에 들어가지 않습니다.

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1 (Android 14 Automotive), RAM 6GB, 1920×1080, WHPX (PC RAM 20GB) |
| 차량 시뮬레이션 | Pleos Connect Virtual Console (`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088) |
| SDK | Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3, TextToSpeech 3.3.1, AAOS Car API |
| 빌드 | Gradle 8.9, AGP 8.5.2, Kotlin 1.9.25, JDK 17, minSdk 28 |

### SDK 검증 앱 (ProbeActivity)
V1~V15 항목별 결과를 화면과 `SGPROBE` 로그로 보여 줍니다.
```bash
adb shell am start --user 10 -n com.safeguard.ai/.ProbeActivity --es probe V9   # V2 제상, V7 음성, V8 네트워크, V9 조명, V13 등받이
adb logcat -s SGPROBE:I
```

## APK 위치
공모전 제출용 릴리스 APK: [`apk/GoldenTime2026_SafeGuardAI_release.apk`](apk/GoldenTime2026_SafeGuardAI_release.apk) (release 빌드, 서명 완료, 약 27.7MB)

```bash
adb install -r -g apk/GoldenTime2026_SafeGuardAI_release.apk
```
설치 후에는 위 '실행 방법'의 서비스 시작·시나리오 재생 명령을 그대로 쓰면 됩니다. 서버 없이 실행하면 로컬 모드로 동작합니다.
