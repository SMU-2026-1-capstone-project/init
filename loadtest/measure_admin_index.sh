#!/usr/bin/env bash
# 관리자 전용 인덱스 — 읽기 계획 변화 + 쓰기 비용 실측
# (docs/decisions/admin-page-scope.md §4 ㄱ안, 2026-08-03)
#
# 가설: 기존 세션 인덱스는 전부 member_id 선두라, member_id 조건 없이 상태·기간으로 훑는
#       관리자 목록에는 하나도 타지 않는다. users 는 보조 인덱스가 아예 없어 정렬이 filesort 다.
#       idx_session_status_starttime(status, start_time) 과 idx_users_created_at(created_at) 을
#       추가하면 실행 계획이 풀스캔+filesort 에서 인덱스 레인지로 바뀌는가, 그리고 그 대가로
#       세션 INSERT 가 얼마나 느려지는가.
#
# ⚠️ 두 축의 신뢰도가 다르다 — 이 스크립트가 존재하는 이유의 절반이 이 구분이다.
#
#   [쓰기 비용]  ✅ 유효. 인덱스 B+tree 유지비용은 값 분포와 무관하다. before/after 델타를 쓴다.
#   [읽기 이득]  ⚠️ 계획 변화(EXPLAIN)까지만. 시간 수치는 내지 않는다.
#                합성 데이터는 단일 템플릿 복제라 값 분포가 균일한데, 인덱스 효용은 선택도에
#                달려 있다. 실제로는 COMPLETED 가 대부분이고 FAILED 는 소수일 텐데 균일하게
#                깔면 옵티마이저 카디널리티 추정이 현실과 달라진다. 그 위에서 잰 ms 는
#                "빨라졌다"의 근거가 못 된다. (docs/decisions/load-test-glossary.md 의
#                합성데이터 한계와 같은 계열)
#
# ⚠️ 로컬 2코어(i3-6100) + MySQL·백엔드 동거 — 절대 수치 신뢰 금지, 상대·델타만.
# ⚠️ 워밍업 통제: INSERT 측정마다 앞부분을 버린다. (load-test-strategy.md §7.6 "1차 측정은
#    무효 — 워밍업 미통제" 교훈. 같은 실수를 반복하지 않기 위해 명시적으로 넣는다.)
# set -e 를 켜는 이유: DB 명령이 실패해도 계속 돌면, 마지막 요약이 이전 실행의 남은 결과
# 파일로 그럴듯한 숫자를 찍는다. 측정 스크립트에서 그건 틀린 결과보다 나쁘다.
set -euo pipefail
PW=1234
# 결과 파일은 전용 임시 디렉터리에 둔다 — /tmp 고정 경로는 심볼릭 링크 선점 여지가 있고,
# 이전 실행의 잔여 파일이 남아 요약이 옛 숫자를 섞을 수도 있다. (CodeRabbit #104)
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
DB(){ docker exec shadowfit-mysql mysql -uroot -p$PW shadowfit "$@" 2>/dev/null; }

USERS=200000        # 회원 수
SESSIONS=1000000    # 세션 수
# 라운드 수 근거: 1차 실행(5라운드)에서 before 최댓값이 after 최솟값보다 커 분포가 겹쳤다.
# 2코어에 MySQL 이 동거하는 환경이라 라운드별 편차가 크다 — 표본을 늘려야 델타가 노이즈와
# 구분된다. 평균만 보면 그 겹침이 안 보이므로 아래 요약에서 min/중앙값도 같이 낸다.
WARMUP=4            # 버릴 라운드 수
ROUNDS=12           # 측정 라운드 수
PER_ROUND=20000     # 라운드당 INSERT 행 수

echo "############ 관리자 인덱스 실험 ############"
echo "회원 ${USERS} / 세션 ${SESSIONS} / 라운드 ${ROUNDS}회(워밍업 ${WARMUP}회 버림) × ${PER_ROUND}행"
echo

echo "## [1/7] 숫자 시퀀스 준비"
DB -e "
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

echo "## [2/7] 스크래치 테이블 (실테이블 미오염 — 인덱스 없이 시딩해 가속)"
DB -e "
DROP TABLE IF EXISTS users_scale;
CREATE TABLE users_scale (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL,
  email VARCHAR(100) NOT NULL,
  selected_persona ENUM('BEGINNER','ADVANCED','DIET','REHAB') NOT NULL DEFAULT 'BEGINNER',
  workout_level INT DEFAULT 1,
  onboarding_completed BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS sessions_scale;
CREATE TABLE sessions_scale (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  exercise_id BIGINT NOT NULL,
  start_time DATETIME NOT NULL,
  end_time DATETIME,
  total_reps INT DEFAULT 0,
  avg_sync_rate DECIMAL(5,2),
  status ENUM('IN_PROGRESS','COMPLETED','CANCELLED','FAILED') DEFAULT 'IN_PROGRESS',
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_scale_member_starttime (member_id, start_time),
  INDEX idx_scale_member_exercise_status_start (member_id, exercise_id, status, start_time),
  INDEX idx_scale_member_status (member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
# ↑ 실테이블의 세션 인덱스 3종을 전부 재현한다. "관리자 인덱스만 없는 상태"가 before 여야
#   비교가 성립하는데, 하나라도 빠뜨리면 before 의 INSERT 가 실제보다 싸게 나와 델타가
#   과장된다. (1차 rig 는 idx_..._member_exercise_status_start 를 빠뜨렸다 — CodeRabbit #104.
#   그 실행의 델타는 위 사유로 상향 편향돼 있었다.)

echo "## [3/7] 시딩"
DB -e "
INSERT INTO users_scale (username, email, selected_persona, workout_level, onboarding_completed, created_at)
SELECT CONCAT('u', n), CONCAT('u', n, '@t.com'),
       ELT(1 + (n % 4), 'BEGINNER','ADVANCED','DIET','REHAB'),
       1 + (n % 5), n % 2,
       TIMESTAMP('2025-08-01 00:00:00') + INTERVAL (n % 365) DAY
FROM _seq WHERE n < ${USERS};"

DB -e "
INSERT INTO sessions_scale (member_id, exercise_id, start_time, end_time, total_reps, avg_sync_rate, status, created_at)
SELECT 1 + (n % ${USERS}), 1 + (n % 3),
       TIMESTAMP('2025-08-01 06:00:00') + INTERVAL (n % 525600) MINUTE,
       TIMESTAMP('2025-08-01 06:15:00') + INTERVAL (n % 525600) MINUTE,
       30, 75.00,
       ELT(1 + (n % 4), 'COMPLETED','COMPLETED','FAILED','IN_PROGRESS'),
       TIMESTAMP('2025-08-01 06:00:00') + INTERVAL (n % 525600) MINUTE
FROM _seq a CROSS JOIN (SELECT 0 UNION SELECT 1) b WHERE a.n < ${SESSIONS} LIMIT ${SESSIONS};"
# ⚠️ status 가 n%4 로 결정된다 = COMPLETED 50% / FAILED 25% / IN_PROGRESS 25%.
#    실제 분포가 아니다. 이 균일성이 위 헤더의 "읽기 이득은 계획 변화까지만" 제약의 근거다.

DB -e "ANALYZE TABLE users_scale, sessions_scale;" >/dev/null

echo "   회원 $(DB -sN -e 'SELECT COUNT(*) FROM users_scale;') / 세션 $(DB -sN -e 'SELECT COUNT(*) FROM sessions_scale;')"
echo

# ── 관리자 대표 쿼리 (admin-page-scope.md §3 A·B 의 기본 화면) ──────────────
Q_MEMBER="SELECT id, username, selected_persona FROM users_scale
          WHERE created_at BETWEEN '2025-09-01' AND '2025-12-01'
          ORDER BY created_at DESC LIMIT 20;"
Q_SESSION="SELECT id, member_id, status, start_time FROM sessions_scale
           WHERE status = 'FAILED' AND start_time >= '2026-01-01'
           ORDER BY start_time DESC LIMIT 20;"

explain_both() {
  echo "-- [회원 목록] 가입일 기간 + 최신순"
  DB -e "EXPLAIN ${Q_MEMBER}"
  echo "-- [세션 목록] 상태 등치 + 기간 범위 + 최신순"
  DB -e "EXPLAIN ${Q_SESSION}"
}

echo "## [4/7] BEFORE — 관리자 인덱스 없이 실행 계획"
explain_both
echo

echo "## [5/7] 쓰기 비교용 쌍둥이 테이블 — 인덱스 유무 하나만 다르게"
# ⚠️ 1차 rig 는 한 테이블에서 before 를 재고, 인덱스를 붙인 뒤 after 를 쟀다. 그러면 before 의
#    측정 라운드가 32만 행을 추가하므로 after 는 "더 큰 테이블 + 다른 버퍼풀 상태"에서 돌아간다.
#    즉 인덱스 유무와 테이블 크기가 함께 변해 관측된 차이를 인덱스 비용으로 단정할 수 없었다.
#    (CodeRabbit #104) — 같은 초기 상태의 테이블 두 개로 분리하고, 라운드를 번갈아 돌려
#    시간에 따른 드리프트(백그라운드 부하·버퍼 상태 변화)가 양쪽에 똑같이 걸리게 한다.
DB -e "
DROP TABLE IF EXISTS sessions_noidx;
DROP TABLE IF EXISTS sessions_idx;
CREATE TABLE sessions_noidx LIKE sessions_scale;
CREATE TABLE sessions_idx   LIKE sessions_scale;
INSERT INTO sessions_noidx SELECT * FROM sessions_scale;
INSERT INTO sessions_idx   SELECT * FROM sessions_scale;
ALTER TABLE sessions_idx ADD INDEX idx_scale_status_starttime (status, start_time);"
DB -e "ANALYZE TABLE sessions_noidx, sessions_idx;" >/dev/null
echo "   noidx $(DB -sN -e 'SELECT COUNT(*) FROM sessions_noidx;') / idx $(DB -sN -e 'SELECT COUNT(*) FROM sessions_idx;') — 같은 행에서 출발"
echo

insert_into() {  # $1=테이블
  DB -e "
    INSERT INTO $1 (member_id, exercise_id, start_time, end_time, total_reps, avg_sync_rate, status, created_at)
    SELECT 1 + (n % ${USERS}), 1 + (n % 3),
           NOW() - INTERVAL (n % 1000) MINUTE, NOW(), 30, 75.00,
           ELT(1 + (n % 4), 'COMPLETED','COMPLETED','FAILED','IN_PROGRESS'), NOW()
    FROM _seq WHERE n < ${PER_ROUND};"
}
timed() { local t0 t1; t0=$(date +%s%3N); insert_into "$1"; t1=$(date +%s%3N); echo $((t1-t0)); }

echo "## [6/7] 세션 INSERT — 라운드 교대 (워밍업 ${WARMUP}회 버림)"
: > "$WORK/before.txt"; : > "$WORK/after.txt"
for ((i=1; i<=WARMUP+ROUNDS; i++)); do
  # 매 라운드 순서를 뒤집는다 — 한쪽이 항상 먼저 돌면 그 자리의 이점/불이익이 고정된다.
  if (( i % 2 == 1 )); then
    b=$(timed sessions_noidx); a=$(timed sessions_idx)
  else
    a=$(timed sessions_idx);   b=$(timed sessions_noidx)
  fi
  if (( i <= WARMUP )); then
    echo "   round ${i}: noidx ${b}ms / idx ${a}ms   (워밍업 — 버림)"
  else
    echo "   round ${i}: noidx ${b}ms / idx ${a}ms"
    echo "${b}" >> "$WORK/before.txt"; echo "${a}" >> "$WORK/after.txt"
  fi
done
echo

echo "## [7/7] AFTER — 인덱스 추가 후 실행 계획"
DB -e "ALTER TABLE users_scale ADD INDEX idx_scale_users_created_at (created_at);"
DB -e "ALTER TABLE sessions_scale ADD INDEX idx_scale_status_starttime (status, start_time);"
DB -e "ANALYZE TABLE users_scale, sessions_scale;" >/dev/null
explain_both
echo

echo "############ 요약 ############"
stat(){ sort -n "$1" | awk '{v[n++]=$1; s+=$1}
  END {printf "%s min=%d  p50=%d  avg=%.0f  max=%d\n", lbl, v[0],
       (n%2 ? v[int(n/2)] : (v[n/2-1]+v[n/2])/2), s/n, v[n-1]}' lbl="$2"; }
stat "$WORK/before.txt" "noidx"
stat "$WORK/after.txt"  "idx  "
echo
# min 을 함께 보는 이유: 2코어 동거 환경에서 상방 이상치는 CPU 경합이지 인덱스 비용이 아니다.
# min 은 "가장 방해가 적었던 라운드"라 구조적 비용에 가장 가깝다. 반대로 min 끼리도 벌어지면
# 그 차이는 노이즈로 설명되지 않는다.
BMIN=$(sort -n "$WORK/before.txt" | head -1)
AMIN=$(sort -n "$WORK/after.txt"  | head -1)
BMAX=$(sort -n "$WORK/before.txt" | tail -1)
awk -v bm="$BMIN" -v am="$AMIN" 'BEGIN{printf "min 기준 델타: %+.1f%%  (구조적 비용에 가장 가까운 추정)\n", (am-bm)/bm*100}'
if [[ "$AMIN" -gt "$BMAX" ]]; then
  echo "분포 판정: ✅ 겹치지 않음 (after 최소 ${AMIN}ms > before 최대 ${BMAX}ms) — 델타가 노이즈로 설명되지 않는다"
else
  echo "분포 판정: ⚠️ 겹침 (after 최소 ${AMIN}ms ≤ before 최대 ${BMAX}ms) — 방향은 참고만, 배수는 신뢰 금지"
fi
echo
echo "읽기: 위 EXPLAIN 의 type / key / rows / Extra 변화를 볼 것."
echo "      ⚠️ 시간 수치는 내지 않는다 — 합성 분포가 균일해 선택도가 현실과 다르다(헤더 참고)."
echo
echo "정리: DROP TABLE users_scale, sessions_scale, sessions_noidx, sessions_idx, _seq;"
