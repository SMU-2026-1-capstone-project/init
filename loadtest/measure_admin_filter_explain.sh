#!/usr/bin/env bash
# 관리자 회원 목록 — 필터 조합별 인덱스 커버리지 실측
# (docs/decisions/admin-page-scope.md §4-3, 2026-08-04)
#
# 질문: idx_users_created_at 하나를 넣었다. 그런데 필터 5개의 부분집합은 32가지다.
#       "인덱스를 넣었다"가 "모든 조회가 빨라졌다"는 뜻이 아니라면, 정확히 어느 조합이
#       인덱스를 타고 어느 조합이 20만 행 스캔으로 돌아가는가.
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
#   실 스키마를 그대로 쓴다. mysql/schema.sql 을 DB 이름만 바꿔 통째로 적용하므로, 컬럼 타입·
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

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

DB(){ docker exec -i "$CONTAINER" mysql -uroot -p$PW "$@" 2>/dev/null; }

echo "############ 필터 조합별 인덱스 커버리지 ############"
echo "스크래치 DB ${DB_NAME} / 회원 ${USERS}"
echo

# ── [1/7] 실 스키마를 DB 이름만 바꿔 적용 ─────────────────────────────────────
# schema.sql 은 CREATE DATABASE shadowfit / USE shadowfit 을 안에 박고 있다. 그대로 파이프하면
# 실 DB 에 붙는다. 두 줄만 정확히 치환하고, 치환이 실제로 일어났는지 확인한 뒤에만 적용한다
# — 스키마 파일이 바뀌어 패턴이 안 맞는데 조용히 넘어가면 실 DB 를 건드리게 된다.
echo "## [1/7] 스크래치 DB 생성 — mysql/schema.sql 원본 적용"
sed -e "s/^CREATE DATABASE IF NOT EXISTS shadowfit;/CREATE DATABASE IF NOT EXISTS ${DB_NAME};/" \
    -e "s/^USE shadowfit;/USE ${DB_NAME};/" \
    "${REPO_ROOT}/mysql/schema.sql" > "$WORK/schema.sql"

if ! grep -q "^CREATE DATABASE IF NOT EXISTS ${DB_NAME};" "$WORK/schema.sql" \
   || ! grep -q "^USE ${DB_NAME};" "$WORK/schema.sql"; then
  echo "!! schema.sql 의 DB 지정 라인을 치환하지 못했다. 원본이 바뀌었는지 확인할 것." >&2
  exit 1
fi
if grep -qE "^(USE|CREATE DATABASE IF NOT EXISTS) shadowfit;" "$WORK/schema.sql"; then
  echo "!! 실 DB(shadowfit) 를 가리키는 라인이 남아 있다. 중단한다." >&2
  exit 1
fi

DB -e "DROP DATABASE IF EXISTS ${DB_NAME};"
DB < "$WORK/schema.sql"
echo "   테이블 $(DB -sN -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}';")개"

# ── [2/7] 시딩 ────────────────────────────────────────────────────────────────
echo "## [2/7] 회원 ${USERS} 시딩"
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
echo

# ── [3/7] general log 켜기 ────────────────────────────────────────────────────
# 파일 경로를 바꾸려면 로그가 꺼져 있어야 한다(켠 채로 바꾸면 반영이 어긋난다).
echo "## [3/7] general log ON — 서버에 도착한 SQL 을 그대로 받는다"
DB -e "
SET GLOBAL general_log = 'OFF';
SET GLOBAL log_output = 'FILE';
SET GLOBAL general_log_file = '${LOGFILE}';"
docker exec "$CONTAINER" sh -c "rm -f ${LOGFILE}"
DB -e "SET GLOBAL general_log = 'ON';"

# ── [4/7] 실제 리포지토리 실행 ────────────────────────────────────────────────
echo "## [4/7] AdminMemberExplainCaptureTest 실행 (실제 QueryDSL 코드 경로)"
(
  cd "$REPO_ROOT"
  # --rerun 이 없으면 두 번째 실행부터 Gradle 이 UP-TO-DATE 로 건너뛴다. 입력이 같으니
  # Gradle 입장에선 맞는 판단이지만, 이 태스크의 산출물은 build/ 가 아니라 **DB 서버의
  # general log** 라 Gradle 이 볼 수 없다. 캡처가 비면 그대로 측정이 없는 것이므로 강제한다.
  ./gradlew --quiet :backend:test --tests '*AdminMemberExplainCaptureTest*' \
            -Dexplain.capture=true --rerun
)
DB -e "SET GLOBAL general_log = 'OFF';"
echo

# ── [5/7] 로그에서 조합별 SQL 추출 ────────────────────────────────────────────
echo "## [5/7] 캡처된 SQL"
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
    if (line ~ /^[Ss][Ee][Ll][Ee][Cc][Tt] / && line ~ /[Ff][Rr][Oo][Mm] users/)
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

echo "## [6/7] AFTER — idx_users_created_at 있음"
docker exec -i "$CONTAINER" mysql -uroot -p$PW --vertical "$DB_NAME" < "$WORK/explain.sql" 2>/dev/null
echo

echo "## [7/7] BEFORE — idx_users_created_at 제거 후 같은 SQL"
DB "$DB_NAME" -e "ALTER TABLE users DROP INDEX idx_users_created_at;"
DB "$DB_NAME" -e "ANALYZE TABLE users;" >/dev/null
docker exec -i "$CONTAINER" mysql -uroot -p$PW --vertical "$DB_NAME" < "$WORK/explain.sql" 2>/dev/null
DB "$DB_NAME" -e "ALTER TABLE users ADD INDEX idx_users_created_at (created_at);"
echo

echo "############ 읽는 법 ############"
echo "type=ALL 이면 20만 행 전수 스캔, range 면 인덱스 범위 탐색."
echo "Extra 의 'Using filesort' 는 정렬을 위해 따로 줄을 세웠다는 뜻 — 인덱스 순서를 못 쓴 것."
echo "⚠️ 시간(ms) 은 이 장치가 내지 않는다. 합성 분포가 균일해 선택도가 현실과 다르다."
echo
echo "정리: DROP DATABASE ${DB_NAME};"
