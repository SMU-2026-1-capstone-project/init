#!/bin/bash
# 통주행 사슬 점검 — 「어느 링크에서 끊기는가」를 한 번에 특정한다.
#
# ─────────────────────────────────────────────────────────────────────────
# 왜 이 스크립트가 있나
#
# 2026-08-12 E1 통주행(#196)은 **HTTP 가 전 구간 200 인데 리포트가 전부 0** 이었다.
# 그때의 진단은 사람이 손으로 했고, 결과는 이슈 3개(#196·#192·#193)로 흩어졌다.
#
# 그런데 그 셋은 따로 난 결함이 아니라 **끊긴 사슬 하나**다:
#
#   입력(프레임) → AI 채점(기준 좌표 필요) → rep 적재 → 리포트 집계 → 화면
#
# 앞 링크가 막히면 뒤 링크는 고쳐도 **확인이 안 된다.** 그래서 고치기 전에
# 「지금 몇 번째 링크에서 끊기는가」를 먼저 사실로 만든다. 고친 뒤에도 같은 스크립트를
# 돌려 **한 칸 전진했는지**를 본다.
#
# 🔴 이 스크립트는 «성공» 을 만들지 않는다. 각 링크에서 **행이 생겼는가만** 본다.
#    HTTP 200 은 통과 근거가 아니다 — #196 이 정확히 그 함정이었다.
# ─────────────────────────────────────────────────────────────────────────
#
# 사용:
#   bash scripts/chain_check.sh                      # 전체
#   BASE=http://1.2.3.4:8080 bash scripts/chain_check.sh
#
# 종료 코드: 0 = 전 링크 통과, 1 = 어딘가 끊김(어디인지 마지막에 찍는다)

set -uo pipefail

BASE=${BASE:-http://localhost:8080}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-shadowfit-mysql}
DB_USER=${DB_USER:-root}
DB_PW=${DB_PW:-1234}
DB_NAME=${DB_NAME:-shadowfit}
AI_CONTAINER=${AI_CONTAINER:-shadowfit-ai}
BACKEND_CONTAINER=${BACKEND_CONTAINER:-shadowfit-backend}

EMAIL=${EMAIL:-chain_$(date +%s)@shadowfit.local}
PW=${PW:-P@ssw0rd!}

BROKEN_AT=""
LINK=0

q() {  # SQL 한 줄 실행 → 값만
  docker exec -i "$MYSQL_CONTAINER" mysql -u"$DB_USER" -p"$DB_PW" "$DB_NAME" -N -B -e "$1" 2>/dev/null | tr -d '\r'
}

link() { LINK=$((LINK+1)); echo; echo "──── L$LINK. $* ────"; }
ok()   { echo "  ✅ $*"; }
info() { echo "     $*"; }
# 🔴 «끊김» 은 처음 것만 기록한다. 뒤 링크의 실패는 앞 링크의 결과일 뿐이라
#    같이 세면 «결함 5개» 처럼 보이지만 실제로는 하나다.
broke() { echo "  🔴 $*"; [ -n "$BROKEN_AT" ] || BROKEN_AT="L$LINK — $1"; }
skip() { echo "  ⏭  $* (측정하지 못했다 — «통과» 가 아니다)"; }

echo "════════ 통주행 사슬 점검 ════════"
echo "  BASE=$BASE  DB=$MYSQL_CONTAINER/$DB_NAME  계정=$EMAIL"

# ── L1 인프라 ────────────────────────────────────────────────────────────
link "인프라 — 컨테이너·스키마"
for c in "$MYSQL_CONTAINER" "$BACKEND_CONTAINER"; do
  if docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null | grep -q true; then
    ok "$c 실행 중"
  else
    broke "$c 가 안 돌고 있다 — docker compose up -d"
  fi
done
docker inspect -f '{{.State.Running}}' "$AI_CONTAINER" 2>/dev/null | grep -q true \
  && ok "$AI_CONTAINER 실행 중" || info "⚠️ $AI_CONTAINER 미기동 — AI 경로(L5)는 건너뛴다"

TABLES=$(q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME';")
[ "${TABLES:-0}" -gt 0 ] && ok "스키마 $TABLES 테이블" || broke "스키마가 비어 있다 — Flyway 미실행"

# ── L2 마스터 데이터 ─────────────────────────────────────────────────────
link "마스터 데이터 — 운동 · 기준 좌표(#192)"
EX=$(q "SELECT COUNT(*) FROM exercises;")
REF=$(q "SELECT COUNT(*) FROM exercise_references;")
[ "${EX:-0}" -gt 0 ] && ok "exercises ${EX}행" || broke "exercises 가 0행이다 — 세션을 만들 수 없다"
if [ "${REF:-0}" -gt 0 ]; then
  ok "exercise_references ${REF}행"
else
  # 이 링크가 끊겨도 뒤를 계속 본다 — 「기준 없이 어디까지 가는가」가 정보다.
  broke "exercise_references 가 0행이다 (#192) — 채점의 정답지가 없다"
fi

# ── L3 회원 ──────────────────────────────────────────────────────────────
link "회원 — 가입 · 로그인 · 온보딩"
SIGNUP=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/member/signup" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"chain_$(date +%s)\",\"email\":\"$EMAIL\",\"password\":\"$PW\",\"sex\":\"MALE\",\"role\":\"USER\"}")
[ "$SIGNUP" = "200" ] || [ "$SIGNUP" = "201" ] && ok "signup $SIGNUP" || broke "signup 이 $SIGNUP 이다"

LOGIN_BODY=$(curl -s -X POST "$BASE/member/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}")
TOKEN=$(echo "$LOGIN_BODY" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$TOKEN" ] && ok "login — 토큰 발급" \
  || broke "로그인 토큰을 못 받았다: $(echo "$LOGIN_BODY" | head -c 200)"

AUTH=(-H "Authorization: Bearer $TOKEN")
ONB=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "$BASE/member/onboarding/$EMAIL" \
  "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"selectedPersona":"ADVANCED","workoutLevel":"STARTER","height":180.0,"weight":75.5,
       "preferredUrl":"https://www.youtube.com/watch?v=q6hBSSis_60"}')
# 🔴 `preferredUrl` 을 빼면 **세션 생성이 400 이 된다.** 세션은 회원의 선호 영상을
#    `reference_source` 로 물고 시작하기 때문이다. 처음에 이 필드를 빼고 돌렸다가
#    「세션 생성 실패」로 찍혔는데, 원인은 세션 API 가 아니라 **온보딩이 덜 채워진 것**이었다.
[ "$ONB" = "200" ] && ok "onboarding 200" || info "⚠️ onboarding $ONB — 세션 생성이 막히면 여기부터 본다"

# ── L4 세션 ──────────────────────────────────────────────────────────────
link "세션 시작"
EX_ID=$(q "SELECT id FROM exercises ORDER BY id LIMIT 1;")
SESSION_BODY=$(curl -s -X POST "$BASE/exercises/sessions" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d "{\"exerciseId\":${EX_ID:-1}}")
SESSION_ID=$(echo "$SESSION_BODY" | grep -o '"sessionId":[0-9]*' | head -1 | cut -d: -f2)
[ -n "$SESSION_ID" ] && ok "세션 생성 — sessionId=$SESSION_ID" \
  || broke "세션 생성 실패: $(echo "$SESSION_BODY" | head -c 200)"

# ── L5 프레임 유입 → 채점 ────────────────────────────────────────────────
link "프레임 유입 → AI 채점 (#196)"
# 🔴 이 링크는 **입력 자산 문제**로 이미 한 번 끊긴 것이 확인됐다(#196):
#    `demo_squat.mp4` 는 얼굴만 잡힌 실패 테이크라 하체 가시성이 0.24 로
#    `VISIBILITY_FLOOR=0.55` 에 전량 걸린다. 여기서는 **그 상태가 그대로인지**만 본다.
VIDEO=ai-server/scripts/demo_videos/demo_squat.mp4
if [ -f "$VIDEO" ]; then
  info "데모 영상 존재: $VIDEO ($(du -h "$VIDEO" | cut -f1))"
  broke "쓸 수 있는 스쿼트 입력이 없다 (#196) — 이 영상은 하체가 안 잡힌 실패 테이크다"
else
  broke "데모 영상 자체가 없다: $VIDEO"
fi

# ── L6 적재 ──────────────────────────────────────────────────────────────
link "pose_data 적재"
ROWS=$(q "SELECT COUNT(*) FROM pose_data WHERE session_id=${SESSION_ID:-0};")
if [ "${ROWS:-0}" -gt 0 ]; then
  ok "pose_data ${ROWS}행"
else
  broke "pose_data 0행 — rep 완성 → 적재 구간이 한 번도 안 돌았다"
  info "우회 확인: 적재 «경로» 자체는 부하 rig 가 매번 통과시킨다(gRPC SavePoseDataBatch)."
  info "           즉 여기 0 은 코드가 아니라 **입력(L5)** 의 결과일 가능성이 높다"
fi

# ── L7 세션 종료 → 리포트 ────────────────────────────────────────────────
link "세션 종료 → 리포트 값"
END=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "$BASE/sessions/${SESSION_ID:-0}/end" "${AUTH[@]}")
[ "$END" = "200" ] && ok "세션 종료 200" || broke "세션 종료가 $END 다"

REPORT=$(curl -s "$BASE/reports/session/${SESSION_ID:-0}" "${AUTH[@]}")
TOTAL_REPS=$(echo "$REPORT" | grep -o '"totalReps":[0-9.]*' | head -1 | cut -d: -f2)
AVG=$(echo "$REPORT" | grep -o '"avgSyncRate":[0-9.]*' | head -1 | cut -d: -f2)
info "리포트: totalReps=${TOTAL_REPS:-?} avgSyncRate=${AVG:-?}"
# 🔴 200 을 통과로 세지 않는다. #196 의 함정이 정확히 이것이었다 — 응답은 200 인데 전 필드가 0.
if [ -n "$TOTAL_REPS" ] && [ "$TOTAL_REPS" != "0" ]; then
  ok "리포트에 0 이 아닌 값이 있다"
else
  broke "리포트가 200 이지만 값이 0 이다 (#196) — «응답이 왔다» 와 «내용이 있다» 는 다르다"
fi

# ── L8 피드백 감지기 ─────────────────────────────────────────────────────
link "자세 문제 유형 감지 → 피드백 (#193)"
FB=$(q "SELECT COUNT(*) FROM session_feedback_logs WHERE session_id=${SESSION_ID:-0};")
if [ "${FB:-0}" -gt 0 ]; then
  ok "session_feedback_logs ${FB}행"
else
  broke "피드백 0행 (#193) — 전송 함수가 아니라 «감지기» 가 없다"
fi

# ── 판정 ─────────────────────────────────────────────────────────────────
echo
echo "════════ 판정 ════════"
if [ -z "$BROKEN_AT" ]; then
  echo "✅ 전 링크 통과 — 한 사람이 한 세션을 끝까지 통과했다."
  exit 0
fi
echo "🔴 **첫 번째 끊긴 링크: $BROKEN_AT**"
echo
echo "   뒤 링크의 실패는 대부분 이것의 결과다. **여기부터 고친다.**"
echo "   고친 뒤 같은 스크립트를 다시 돌려 «한 칸 전진했는지» 를 본다."
exit 1
