#!/usr/bin/env bash
# 관리자 목록(A 회원 · B 세션) — 필터 조합별 인덱스 커버리지 실측
# (docs/decisions/admin-page-scope.md §4-3 · §4-4, 2026-08-04)
#
# 질문 A(회원): idx_users_created_at 하나를 넣었다. 그런데 필터 5개의 부분집합은 32가지다.
#       "인덱스를 넣었다"가 "모든 조회가 빨라졌다"는 뜻이 아니라면, 정확히 어느 조합이
#       인덱스를 타고 어느 조합이 20만 행 스캔으로 돌아가는가.
#
# 질문 B(세션): 회원 목록에는 없던 변수가 하나 있다 — 검색어가 조인 너머(users.username)에
#       있어서 옵티마이저가 **어느 테이블부터 읽을지**를 고른다. 세션부터 읽고 회원을 붙일지,
#       회원을 먼저 걸러 그 회원들의 세션을 찾을지. 코드만 봐서는 알 수 없다.
#
# ── 이 장치가 앞선 rig(measure_admin_index.sh)와 다른 점 ────────────────────────
#
#   SQL 을 손으로 쓰지 않는다. §4-3 이 남긴 절차는 show-sql 로 뽑고 값을 손으로 채우라는
#   것이었는데, 그러면 **측정한 쿼리가 앱이 실제로 보내는 쿼리와 같다는 보증이 사라진다.**
#   여기서는 AdminMemberExplainCaptureTest 가 실제 MemberQueryRepositoryImpl 을 MySQL 상대로
#   실행하고, 서버 general log 에 도착한 SQL 을 그대로 EXPLAIN 에 건다. Connector/J 는 기본값
#   (useServerPrepStmts=false)에서 값을 클라이언트 측에서 채워 보내므로 로그에 완성된 SQL 이
#   남는다 — 이 전제가 깨지면 [5/7] 에서 '?' 가 보이고 스크립트가 멈춘다.
#
#   실 스키마를 그대로 쓴다. V1__baseline.sql 을 스크래치 DB 지정만 얹어 통째로 적용하므로, 컬럼 타입·
#   길이·인덱스가 실테이블과 어긋날 여지가 없다. (앞선 rig 는 스크래치 테이블을 손으로 다시
#   써서, 실테이블 인덱스 하나를 빠뜨린 채 측정한 이력이 있다 — §4-2 결함 #1)
#
# ── 이 장치가 대답하지 못하는 것 ───────────────────────────────────────────────
#
#   ⚠️ 시간(ms) 은 내지 않는다. 합성 데이터의 값 분포가 균일해 선택도가 현실과 다르고,
#      인덱스 효용은 선택도에 달려 있다. (§4-1 "측정하지 않은 것" 2번과 같은 이유)
#   ⚠️ created_at 이 365일에 균등이라, 기간 필터의 선택도가 실제 서비스와 다르다.
#      옵티마이저의 range vs full-scan 판단은 이 선택도에 달려 있으므로, 아래 결과는
#      "이 분포에서는 이렇게 고른다"까지다.
#
# ── 얼마나 걸리나 (2026-08-09 실측) ───────────────────────────────────────────
#
#   682초 (11분 22초) — 스크래치 DB 생성 + 회원 20만 + 세션 100만 시딩 + 자기검증 +
#   필터 조합 EXPLAIN 까지 전부.
#
#   ⚠️ 전제: 2물리코어 장비, MySQL 컨테이너 **단독**(다른 컨테이너 전부 정지),
#      스크래치 DB 가 없는 상태에서 시작. 이웃을 켜둔 채면 더 걸린다.
#
#   📌 진행률은 COUNT(*) 로 못 본다 — 세션 INSERT 가 한 트랜잭션이라 커밋 전까지 다른
#      커넥션에는 0 으로 보인다. 유일한 신호가 information_schema.tables.table_rows 인데
#      그건 추정치다(이 프로젝트가 세 번 데인 그 값, admin-page-scope.md §4-5·§4-5-1·§4-5-2).
#
set -euo pipefail

# Git Bash(MSYS)는 '/tmp/...' 처럼 생긴 인자를 Windows 경로로 바꿔서 넘긴다. 아래 경로들은
# 전부 **컨테이너 안** 경로라 그 변환이 걸리면 엉뚱한 곳을 가리킨다. Linux 에서는 이 변수가
# 아무 일도 하지 않으므로 그대로 둬도 된다.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

PW=1234
DB_NAME=shadowfit_explain
CONTAINER=shadowfit-mysql
LOGFILE=/tmp/admin_explain_capture.log
USERS=200000
SESSIONS=1000000

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
# ⚠️ 실패해도 general log 를 끈다. 캡처 테스트(gradle)가 죽으면 set -e 가 즉시 빠져나가는데
#    로그가 켜진 채 남으면 디스크가 계속 차고 이후 측정이 느려진다 — 측정 장치가 다음 측정을
#    오염시키는 것이라 §4-2 결함 #4 와 같은 계열이다.
cleanup(){
  local rc=$?
  docker exec "$CONTAINER" mysql -uroot -p$PW -e "SET GLOBAL general_log='OFF';" 2>/dev/null || true
  rm -rf "$WORK"
  [[ $rc -ne 0 ]] && echo "!! 비정상 종료(exit $rc) — general log 를 껐다." >&2
  return 0
}
trap cleanup EXIT

DB(){ docker exec -i "$CONTAINER" mysql -uroot -p$PW "$@" 2>/dev/null; }

echo "############ 필터 조합별 인덱스 커버리지 ############"
echo "스크래치 DB ${DB_NAME} / 회원 ${USERS}"
echo

# ── [1/7] 실 스키마를 DB 이름만 바꿔 적용 ─────────────────────────────────────
# 스키마 정본은 V1__baseline.sql(구 mysql/schema.sql)이다 — Flyway 도입으로 위치가 바뀌었다(이슈 #115).
#
# 예전 이 자리는 파일 안의 CREATE DATABASE shadowfit / USE shadowfit 두 줄을 sed 로 치환했다.
# 이제 그 두 줄이 파일에 없다 — Flyway 는 이미 연결된 DB 위에서 실행하므로 마이그레이션이
# DB 를 고르면 안 되기 때문이다. 그래서 치환이 아니라 **앞에 붙인다.**
#
# 안전장치는 유지한다: 붙인 결과에 실 DB 를 가리키는 줄이 하나라도 있으면 중단한다.
# 스키마 파일이 바뀌어 USE shadowfit 이 다시 생겼는데 조용히 넘어가면 실 DB 를 건드리게 된다.
BASELINE="${REPO_ROOT}/backend/src/main/resources/db/migration/V1__baseline.sql"
echo "## [1/8] 스크래치 DB 생성 — V1__baseline.sql 원본 적용"

if [ ! -f "$BASELINE" ]; then
  echo "!! 스키마 정본을 찾지 못했다: $BASELINE" >&2
  echo "   마이그레이션 파일이 옮겨졌는지 확인할 것." >&2
  exit 1
fi

{
  echo "CREATE DATABASE IF NOT EXISTS ${DB_NAME};"
  echo "USE ${DB_NAME};"
  cat "$BASELINE"
} > "$WORK/schema.sql"

if grep -qE "^(USE|CREATE DATABASE IF NOT EXISTS) shadowfit;" "$WORK/schema.sql"; then
  echo "!! 실 DB(shadowfit) 를 가리키는 라인이 남아 있다. 중단한다." >&2
  exit 1
fi

DB -e "DROP DATABASE IF EXISTS ${DB_NAME};"
DB < "$WORK/schema.sql"
echo "   테이블 $(DB -sN -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}';")개"

# ── [2/7] 시딩 ────────────────────────────────────────────────────────────────
echo "## [2-1/8] 회원 ${USERS} 시딩"
DB "$DB_NAME" -e "
DROP TABLE IF EXISTS _seq;
CREATE TABLE _seq (n INT PRIMARY KEY);
INSERT INTO _seq
SELECT d0.n+d1.n*10+d2.n*100+d3.n*1000+d4.n*10000+d5.n*100000
FROM (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d0
CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d1
CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d2
CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d3
CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d4
CROSS JOIN (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d5;"

# username 100명당 1명에 'kim' 을 심는다(선택도 1%). 전부 'u<n>' 이면 검색어 조합이
# 0건짜리 축퇴 쿼리가 되어, 관리자가 실제로 검색창에 이름을 치는 상황과 멀어진다.
# workout_level NULL = 온보딩 미완(10%) — 구현이 NULL 을 별도 처리하지 않는 지점과 맞춘다.
DB "$DB_NAME" -e "
INSERT INTO users (username, email, password, selected_persona, workout_level, onboarding_completed, created_at)
SELECT CASE WHEN n % 100 = 0 THEN CONCAT('kim', n) ELSE CONCAT('u', n) END,
       CONCAT('u', n, '@t.com'),
       '\$2a\$10\$seedseedseedseedseedseedseedseedseedseedseedseedseedse',
       ELT(1 + (n % 4), 'BEGINNER','ADVANCED','DIET','REHAB'),
       CASE WHEN n % 10 = 0 THEN NULL
            ELSE ELT(1 + (n % 5), 'STARTER','BEGINNER','INTERMEDIATE','ADVANCED','EXPERT') END,
       CASE WHEN n % 10 = 0 THEN FALSE ELSE TRUE END,
       TIMESTAMP('2025-08-01 00:00:00') + INTERVAL (n % 365) DAY
FROM _seq WHERE n < ${USERS};"
DB "$DB_NAME" -e "ANALYZE TABLE users;" >/dev/null
echo "   회원 $(DB -sN "$DB_NAME" -e 'SELECT COUNT(*) FROM users;') / 그중 kim $(DB -sN "$DB_NAME" -e "SELECT COUNT(*) FROM users WHERE username LIKE '%kim%';")"

# ── [2-2/8] 세션 시딩 (B) ─────────────────────────────────────────────────────
echo "## [2-2/8] 운동 3종 + 세션 ${SESSIONS} 시딩"
DB "$DB_NAME" -e "
INSERT INTO exercises (name, category, analysis_supported) VALUES
  ('스쿼트','LOWER',TRUE), ('푸시업','UPPER',FALSE), ('플랭크','CORE',FALSE);"

# FK 검사를 끄고 넣는다 — 100만 행마다 users/exercises 를 확인하면 시딩이 수 배 느려진다.
# 값은 위에서 만든 범위 안에서만 만들므로 무결성은 구성으로 보장된다. 끝나면 되돌린다.
DB "$DB_NAME" -e "
SET FOREIGN_KEY_CHECKS = 0;
INSERT INTO exercise_sessions
  (member_id, exercise_id, start_time, end_time, total_reps, avg_sync_rate, status, created_at)
SELECT 1 + (n % ${USERS}), 1 + (n % 3),
       TIMESTAMP('2025-08-01 06:00:00') + INTERVAL mins MINUTE,
       TIMESTAMP('2025-08-01 06:15:00') + INTERVAL mins MINUTE,
       30, 75.00,
       ELT(1 + (st % 4), 'COMPLETED','COMPLETED','FAILED','IN_PROGRESS'),
       TIMESTAMP('2025-08-01 06:00:00') + INTERVAL mins MINUTE
FROM (
  SELECT n,
         CONV(SUBSTRING(MD5(CONCAT('ts', n)), 1, 8), 16, 10) % 525600 AS mins,
         CONV(SUBSTRING(MD5(CONCAT('st', n)), 1, 8), 16, 10)          AS st
  FROM _seq WHERE n < ${SESSIONS}
) t;
SET FOREIGN_KEY_CHECKS = 1;"
# 🔴 CROSS JOIN 을 걷어낸 이유 — 2026-08-06 발견한 세 번째 시딩 결함(§4-2 결함 #6).
#    이전 판은 `FROM _seq a CROSS JOIN (SELECT 0 UNION SELECT 1) b WHERE a.n < 1000000
#    LIMIT 1000000` 이었다. _seq 는 이미 100만 행(0~999,999)인데 거기에 2를 곱해 200만을
#    만들고 앞에서 100만만 잘랐으니, **n 이 0~499,999 만 쓰이고 각 값이 두 번씩** 들어갔다.
#
#    관측된 결과:
#      · 완전히 동일한 세션(id 제외)이 2벌씩 × 500,000 조합
#      · distinct start_time 이 100만 행에 500,000 개뿐
#      · member_id = 1+(n%200000) 인데 n 이 절반만 도니 **id ≤ 100,000 회원은 세션 6개,
#        초과는 4개** — 세션 수마저 member_id 로 결정된다(결함 #5 와 같은 계열)
#
#    _seq 가 SESSIONS 를 못 채우면 조용히 적게 들어가므로 아래에서 행 수를 검증한다.
# ⚠️ status 의 주변분포는 COMPLETED 50% / FAILED 25% / IN_PROGRESS 25% 다. 드라이빙 테이블
#    선택은 선택도에 민감하므로 이 분포가 B 결과 해석의 가장 큰 단서인데, **이 값에 근거가
#    없다.** 어디까지가 논증이고 어디부터가 가정인지 갈라 적는다.
#
#    [논증됨] IN_PROGRESS 25% 는 **구조적으로 불가능하다.** IN_PROGRESS 는 과도 상태다 —
#      모든 세션은 분석 완료(COMPLETED)나 타임아웃(FAILED)으로 반드시 빠져나간다
#      (SessionTimeoutScheduler). 누적 테이블에서 IN_PROGRESS 로 남아 있는 건 "지금 이 순간
#      운동 중인 사람"뿐이므로, DAU 1,000 · 세션 15분 가정이면 상시 수십 건 규모다.
#      100만 행 중 25만이 아니라 **사실상 0%** 여야 한다.
#
#    [가정, 미검증] COMPLETED 와 FAILED 의 비율. FAILED 는 앱 종료·네트워크 단절·AI 콜백
#      유실에서 나오는데 그 발생률을 이 프로젝트는 **한 번도 재본 적이 없다.** 실 DB 의
#      세션은 2026-08-06 기준 7건(전부 COMPLETED)이라 표본이라 할 수 없다. 그러므로
#      "FAILED 는 소수일 것" 같은 서술을 근거로 쓰지 않는다.
#
#    ⇒ 이 시딩은 "현실의 분포"가 아니라 **각 상태에 충분한 행을 주어 계획을 관찰하기 위한
#      배치**다. 선택도가 현실과 다르므로 결과는 "이 분포에서는 이렇게 고른다"까지다.
#
# 🔴 해시를 쓰는 이유 — 2026-08-06 에 발견한 시딩 결함(§4-2 결함 #5)의 수정이다.
#    이전 판은 status = ELT(1 + (n % 4), ...) 였는데, member_id 도 1 + (n % 200000) 이라
#    **둘이 같은 n 의 함수**였다. USERS(200,000) 가 4의 배수라 n mod 4 가 member_id 로 완전히
#    결정되고, 그 결과 **회원 20만 명 중 19만 9,920명이 평생 한 가지 상태의 세션만** 가졌다.
#
#    영향이 컸던 곳은 조합 (d) 상태+검색어다. 'kim' 회원의 세션이 전부 COMPLETED 라
#    FAILED+kim 이 **구조적으로 0건**이었고, 그 조합은 AdminSessionExplainCaptureTest 가
#    "드라이빙 테이블 선택이 갈리는 지점 = 이 캡처의 핵심"이라고 적어둔 바로 그 조합이다.
#    즉 §4-4 는 핵심 조합에서 **빈 결과를 재고 있었다.**
#
#    ⚠️ 처음엔 CRC32 를 썼는데 **그것도 틀렸다.** CRC32 는 GF(2) 위에서 선형이라 등차 입력의
#      구조가 하위 비트에 그대로 남는다. 실제로 재보니 종속이 사라진 게 아니라 방향만 뒤집혔다:
#
#        오프셋별 "status 가 같은 비율" (무작위면 0.25)
#          n vs n+1       0.0044      n vs n+2   0.4100
#          n vs n+200000  0.0000  ← 회원의 연속 세션은 **절대** 같은 상태가 될 수 없었다
#        값별 개수도 250000 × 4 로 오차 0 — 진짜 해시라면 ±433(σ) 는 흔들려야 한다.
#
#      MD5 로 바꾸고 같은 검사를 하면 전 오프셋 0.2476~0.2524, 값별 개수 편차 ±450 이다.
#      후자가 정상이다. 그래서 CONV(SUBSTRING(MD5(...),1,8),16,10) 을 쓴다 — 100만 행 시딩에서
#      MD5 의 추가 비용은 무시할 수준이고, 여기서 아껴야 할 것은 시간이 아니라 신뢰다.
#
#    주변분포(50/25/25)는 그대로라 §4-4 와의 차이는 "상관"에서만 온다.
#
# 🔴 start_time 도 해시로 바꿨다 (2026-08-06, 결함 #5 의 두 번째 축).
#    이전 판은 `(n % 525600) MINUTE` 이었는데 member_id 도 `n % 200000` 이라 같은 종속이 있었다.
#    회원 m 의 세션은 (m-1), (m-1)+74400, (m-1)+200000, (m-1)+274400, (m-1)+400000 분에
#    놓여 전부 (m-1) 만큼 밀린다 — **id 가 작은 회원일수록 이른 시각에 쏠린다.**
#    기간으로 자르면 회원 부분집합이 편향되므로 대시보드 집계 e(기간 내
#    COUNT(DISTINCT member_id))가 직접 영향을 받는다.
#
#    ⚠️ 분포의 성격이 바뀐 것을 기록해둔다 — 이전엔 1년의 매 분에 세션이 고르게 하나씩
#      놓이는 **완전 균등 스윕**이었고, 지금은 분 단위로 뽑는 **무작위 균등**이다(분당 건수가
#      Poisson 처럼 흔들린다). 후자가 덜 인공적이지만, **둘 다 하루·요일 주기가 없다**는
#      한계는 그대로다. 실제 트래픽은 새벽에 비고 저녁에 몰린다.
#      부수적으로 distinct start_time 이 525,600 전부에서 약 446,000(= 525600·(1-e^-1.9))
#      으로 준다. 해시 충돌이라 정상이며, 아래 검증 임계를 그만큼 느슨하게 잡았다.
#
#    🔴 **대가 — 삽입 순서가 무작위가 된다.** 시딩은 n 순서(= PK 순서)로 INSERT 하는데,
#      이전엔 n 이 커지면 start_time 도 커져 (status, start_time) 인덱스에 거의 append
#      였다. 지금은 PK 순서로 넣어도 start_time 이 사방으로 튀어 **인덱스 페이지가 계속
#      쪼개진다.** 즉 이 테이블의 인덱스는 이전보다 단편화돼 있고, 그 방향은 **실제 서비스와
#      반대**다(실제 세션은 시간순으로 쌓이므로 append 에 가깝다).
#
#      영향은 갈린다:
#        · EXPLAIN(계획 선택)  — 영향 없음. 통계는 카디널리티를 보지 페이지 배치를 보지 않는다
#        · 시간(ms)            — 영향 있음. 흩어진 페이지는 스캔이 느리다
#      ⇒ **§4-5(08-06 이전 판)의 ms 와 직접 비교하지 말 것.** "상관을 고쳐서 바뀐 것"과
#        "단편화가 늘어서 바뀐 것"이 섞인다. 상관 제거를 우선한 이유는, 상관은 **계획 선택**을
#        왜곡하는 반면(집계 e 가 직격) 단편화는 시간만 건드리고 그 시간은 이 장비에서
#        어차피 절대값을 신뢰하지 않기로 한 값이기 때문이다(load-test-strategy.md 전제).
#
# ⚠️ exercise_id 는 `1 + (n % 3)` 으로 남겼다. 여기엔 같은 종류의 해악이 없다 —
#    200000 mod 3 = 2 라 회원 m 의 다섯 세션이 세 운동에 고루 흩어진다(member_id 가 값을
#    결정하지 않는다). 검증에서도 이 축은 보지 않는다.
# ⚠️ CANCELLED 는 시딩하지 않는다 — Java enum 철자가 어긋나 있어(issue #106) 어차피 코드에서
#    도달할 수 없는 값이다. 있는 척하면 측정이 현실보다 좋아 보인다.

DB "$DB_NAME" -e "ANALYZE TABLE exercise_sessions, exercises;" >/dev/null
echo "   세션 $(DB -sN "$DB_NAME" -e 'SELECT COUNT(*) FROM exercise_sessions;') / FAILED $(DB -sN "$DB_NAME" -e "SELECT COUNT(*) FROM exercise_sessions WHERE status='FAILED';")"
echo

# ── [2-3/8] 시딩 자기 검증 ────────────────────────────────────────────────────
# 2026-08-06 에 하루 동안 시딩 결함 3건(§4-2 #5·#6)이 연달아 나왔고, 셋 다 공통점이 있다 —
# **시딩이 의도대로 됐는지 아무도 확인하지 않았다.** 스크립트는 매번 성공적으로 끝났고,
# 행 수도 맞았다. 틀린 것은 행 수가 아니라 **행들 사이의 관계**였다.
#
# 그래서 여기서 관계를 검사한다. 실패하면 측정을 시작하지 않는다 — 틀린 데이터 위에서 나온
# EXPLAIN 은 "결과가 없는 것"보다 나쁘다(그럴듯해 보이므로).
echo "## [2-3/8] 시딩 자기 검증"
FAIL=0
chk(){ # $1=이름 $2=실제 $3=기대설명 $4=판정(0=OK)
  if [[ "$4" == "0" ]]; then printf "   ✅ %-28s %s\n" "$1" "$2"
  else printf "   🔴 %-28s %s  (기대: %s)\n" "$1" "$2" "$3"; FAIL=1; fi
}

N=$(DB -sN "$DB_NAME" -e "SELECT COUNT(*) FROM exercise_sessions;")
chk "행 수" "$N" "${SESSIONS}" "$([[ "$N" == "$SESSIONS" ]] && echo 0 || echo 1)"

# 중복 행 — 결함 #6. id 를 뺀 모든 컬럼이 같은 행이 무더기로 있으면 CROSS JOIN 실수의 재발이다.
# ⚠️ 0 을 요구하지 않는다. start_time 이 해시가 된 뒤로는 **우연한 충돌**이 가능하다 —
#    같은 회원의 다섯 세션 중 둘이 같은 분에 떨어질 수 있다. 결함 #6 은 50만 건이었으므로
#    0.1%(1,000건) 를 경계로 두면 우연과 구조적 중복은 충분히 갈린다.
DUP=$(DB -sN "$DB_NAME" -e "SELECT COALESCE(SUM(c-1),0) FROM (
  SELECT COUNT(*) c FROM exercise_sessions
  GROUP BY member_id, exercise_id, start_time, status HAVING c > 1) t;")
DUP_MAX=$(( SESSIONS / 1000 ))
chk "중복 행" "$DUP" "< ${DUP_MAX}" "$([[ "$DUP" -lt "$DUP_MAX" ]] && echo 0 || echo 1)"

# 회원당 세션 수가 한 가지여야 한다 — 갈리면 member_id 가 세션 수를 결정하고 있다(결함 #6).
SPREAD=$(DB -sN "$DB_NAME" -e "SELECT COUNT(*) FROM (
  SELECT COUNT(*) c FROM exercise_sessions GROUP BY member_id) t GROUP BY c;" | wc -l)
chk "회원당 세션 수 종류" "$SPREAD" "1" "$([[ "$SPREAD" == "1" ]] && echo 0 || echo 1)"

# status ↔ member_id 독립성 — 결함 #5. 회원 5명 중 1명꼴로도 2종 이상이 안 나오면 종속이다.
# 회원당 세션이 k 개일 때 "전부 같은 상태"일 확률은 0.5^k + 2*0.25^k 로, k=5 면 약 3.3% 다.
# 여유를 둬 20% 를 넘으면 실패로 본다 — 종속이면 이 값이 100% 에 붙는다.
ONEKIND=$(DB -sN "$DB_NAME" -e "SELECT ROUND(100*SUM(cnt=1)/COUNT(*),2) FROM (
  SELECT member_id, COUNT(DISTINCT status) cnt FROM exercise_sessions GROUP BY member_id) t;")
chk "회원당 status 1종 비율(%)" "$ONEKIND" "< 20" \
    "$(awk -v v="$ONEKIND" 'BEGIN{exit !(v<20)}' && echo 0 || echo 1)"

# 검색어 × 상태 교차가 비어 있으면 조합 (d) 가 0건을 재게 된다 — 결함 #5 의 직접 증상.
CROSS=$(DB -sN "$DB_NAME" -e "SELECT COUNT(*) FROM exercise_sessions s
  JOIN users m ON m.id = s.member_id
  WHERE s.status='FAILED' AND m.username LIKE '%kim%';")
chk "FAILED × kim 교차" "$CROSS" "> 0" "$([[ "$CROSS" -gt 0 ]] && echo 0 || echo 1)"

# start_time ↔ member_id 독립성 — 결함 #5 의 두 번째 축.
# 좁은 기간을 잘랐을 때 그 안의 member_id 가 전 구간에 퍼져 있어야 한다. 종속이면 한 구간에
# 몰린다. 균등분포 0..USERS 의 표준편차는 USERS/√12 이므로, 하루치의 표준편차를 그 값으로
# 나눈 비율이 100% 에 가까우면 독립이다. (이전 판은 이 값이 한 자릿수 %였다)
EXPECTED_SD=$(awk -v u="$USERS" 'BEGIN{printf "%.0f", u/sqrt(12)}')
SPREAD_PCT=$(DB -sN "$DB_NAME" -e "
  SELECT ROUND(100 * STDDEV_POP(member_id) / ${EXPECTED_SD}) FROM exercise_sessions
  WHERE start_time >= '2025-11-01 00:00:00' AND start_time < '2025-11-02 00:00:00';")
chk "하루치 member_id 퍼짐(%)" "$SPREAD_PCT" "> 80" \
    "$(awk -v v="$SPREAD_PCT" 'BEGIN{exit !(v>80)}' && echo 0 || echo 1)"

# distinct start_time — 결함 #6 의 다른 증상. 해시라 충돌이 있어 SESSIONS 보다 적은 게 정상이고,
# 분 해상도가 525,600 뿐이라 100만 행이면 대부분의 분이 채워진다. 절반 이하면 이상하다.
DSTART=$(DB -sN "$DB_NAME" -e "SELECT COUNT(DISTINCT start_time) FROM exercise_sessions;")
chk "distinct start_time" "$DSTART" "> 300000" \
    "$([[ "$DSTART" -gt 300000 ]] && echo 0 || echo 1)"

if [[ "$FAIL" != "0" ]]; then
  echo "!! 시딩 검증 실패 — 이 데이터 위의 측정은 신뢰할 수 없다. 중단한다." >&2
  exit 1
fi
echo

# ── [3/7] general log 켜기 ────────────────────────────────────────────────────
# 파일 경로를 바꾸려면 로그가 꺼져 있어야 한다(켠 채로 바꾸면 반영이 어긋난다).
echo "## [3/8] general log ON — 서버에 도착한 SQL 을 그대로 받는다"
DB -e "
SET GLOBAL general_log = 'OFF';
SET GLOBAL log_output = 'FILE';
SET GLOBAL general_log_file = '${LOGFILE}';"
docker exec "$CONTAINER" sh -c "rm -f ${LOGFILE}"
DB -e "SET GLOBAL general_log = 'ON';"

# ── [4/7] 실제 리포지토리 실행 ────────────────────────────────────────────────
echo "## [4/8] 캡처 테스트 실행 (실제 QueryDSL 코드 경로 — 회원·세션)"
(
  cd "$REPO_ROOT"
  # --rerun 이 없으면 두 번째 실행부터 Gradle 이 UP-TO-DATE 로 건너뛴다. 입력이 같으니
  # Gradle 입장에선 맞는 판단이지만, 이 태스크의 산출물은 build/ 가 아니라 **DB 서버의
  # general log** 라 Gradle 이 볼 수 없다. 캡처가 비면 그대로 측정이 없는 것이므로 강제한다.
  ./gradlew --quiet :backend:test --tests '*ExplainCaptureTest*' \
            -Dexplain.capture=true --rerun
)
DB -e "SET GLOBAL general_log = 'OFF';"
echo

# ── [5/7] 로그에서 조합별 SQL 추출 ────────────────────────────────────────────
echo "## [5/8] 캡처된 SQL"
docker exec "$CONTAINER" cat "$LOGFILE" > "$WORK/general.log"

# 마커 사이에 있는 users 대상 SELECT 만 뽑는다. 라벨은 마커에서 가져오므로 어느 조합의
# 쿼리인지 추측할 필요가 없다. 목록 쿼리와 count 쿼리가 각각 한 줄씩 나온다.
awk '
  /\/\*EXPLAIN_MARK\*\/[a-z0-9_]+_BEGIN/ {
    match($0, /\/\*EXPLAIN_MARK\*\/[a-z0-9_]+_BEGIN/)
    lbl = substr($0, RSTART + 16, RLENGTH - 16 - 6); next
  }
  /\/\*EXPLAIN_MARK\*\/[a-z0-9_]+_END/ { lbl = ""; next }
  lbl != "" {
    line = $0
    sub(/^.*[ \t]Query[ \t]+/, "", line)
    if (line ~ /^[Ss][Ee][Ll][Ee][Cc][Tt] / && line ~ /[Ff][Rr][Oo][Mm] (users|exercise_sessions)/)
      printf "%s\t%s\n", lbl, line
  }
' "$WORK/general.log" > "$WORK/captured.tsv"

CAPTURED=$(wc -l < "$WORK/captured.tsv")
if [[ "$CAPTURED" -eq 0 ]]; then
  echo "!! 캡처된 SQL 이 없다. general log 나 테스트 실행을 확인할 것." >&2
  exit 1
fi
if grep -q '?' "$WORK/captured.tsv"; then
  echo "!! SQL 에 '?' 가 남아 있다 — 서버 측 prepared statement 로 동작했다는 뜻이라" >&2
  echo "   값이 로그에 안 남는다. 이 장치의 전제가 깨졌으므로 중단한다." >&2
  exit 1
fi
nl -ba "$WORK/captured.tsv" | sed 's/\t/  |  /'
echo "   (총 ${CAPTURED}건)"
echo

# ── [6/7] 조합별 EXPLAIN — 인덱스 있음 / 없음 ─────────────────────────────────
build_explain() {  # $1=출력파일
  : > "$1"
  while IFS=$'\t' read -r lbl sql; do
    printf "SELECT '>>> %s' AS step;\n" "$lbl" >> "$1"
    printf "EXPLAIN %s;\n" "${sql%;}" >> "$1"
  done < "$WORK/captured.tsv"
}
build_explain "$WORK/explain.sql"

echo "## [6/8] AFTER — 관리자 인덱스 2종 있음"
docker exec -i "$CONTAINER" mysql -uroot -p$PW --vertical "$DB_NAME" < "$WORK/explain.sql" 2>/dev/null
echo

# 둘을 같이 뗀다. 이게 PR #104 이전의 실제 상태이고, 세션 조회가 users 를 조인하므로 한쪽만
# 떼면 "관리자 인덱스가 없던 시절"이 아니라 있지도 않았던 중간 상태를 재게 된다.
# ⚠️ member_id 선두 인덱스 3종은 그대로 둔다 — 실테이블에 원래 있던 것들이라, 빼면
#    before 가 실제보다 나빠 보인다(§4-2 결함 #1 과 같은 실수).
echo "## [7/8] BEFORE — 관리자 인덱스 2종 제거 후 같은 SQL"
DB "$DB_NAME" -e "
ALTER TABLE users DROP INDEX idx_users_created_at;
ALTER TABLE exercise_sessions DROP INDEX idx_session_status_starttime;"
DB "$DB_NAME" -e "ANALYZE TABLE users, exercise_sessions;" >/dev/null
docker exec -i "$CONTAINER" mysql -uroot -p$PW --vertical "$DB_NAME" < "$WORK/explain.sql" 2>/dev/null
DB "$DB_NAME" -e "
ALTER TABLE users ADD INDEX idx_users_created_at (created_at);
ALTER TABLE exercise_sessions ADD INDEX idx_session_status_starttime (status, start_time);"
echo

echo "############ 읽는 법 ############"
echo "type=ALL 이면 20만 행 전수 스캔, range 면 인덱스 범위 탐색."
echo "Extra 의 'Using filesort' 는 정렬을 위해 따로 줄을 세웠다는 뜻 — 인덱스 순서를 못 쓴 것."
echo "⚠️ 시간(ms) 은 이 장치가 내지 않는다. 합성 분포가 균일해 선택도가 현실과 다르다."
echo
echo "정리: DROP DATABASE ${DB_NAME};"
