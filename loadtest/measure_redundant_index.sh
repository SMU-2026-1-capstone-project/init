#!/usr/bin/env bash
# session_feedback_logs 인덱스 중복 여부 실측
#
# 원래 가설(2026-07-24): idx_session_feedback(session_id, occurred_at) 는
#   uk_session_event(session_id, occurred_at, feedback_type) 의 **선두 2컬럼과 겹치므로**
#   지워도 된다. 그 근거로 실제로 지웠다(10만 행 batch INSERT 7,894ms → 6,202ms, 약 −21%).
#
# 🔴 그 근거는 V5 에서 사라졌다. V5__feedback_log_rep_key.sql:52-53 —
#
#     DROP INDEX uk_session_event,                                    ← 없어진 키
#     ADD UNIQUE KEY uk_session_rep (session_id, rep_number, feedback_type)
#
# 새 키는 occurred_at 을 **두 번째로 갖지 않는다.** 그래서 「겹치니까 중복」이라는 근거가
# 통째로 없어졌고, `WHERE session_id=? ORDER BY occurred_at` 은 이제 filesort 로 떨어진다.
# 결론(안 되살린다)은 유지됐지만 근거가 「세션당 정렬 대상이 수십 행이라 filesort 가 싸다」로
# 바뀌었고, **그 새 근거는 EXPLAIN 으로 확인된 적이 없다**(V5:58-70 이 미측정으로 적어둔 것).
#
# 이 rig 이 답하는 질문은 그래서 이렇게 바뀐다:
#
#     (옛) idx_session_feedback 은 uk_session_event 와 중복인가          ← 키가 없어져 무의미
#     (새) uk_session_rep 만 있는 지금, idx_session_feedback 이 필요한가  ← 이것을 잰다
#
# ── 팔 ─────────────────────────────────────────────────────────────────────────
#   기본                        : V5 스키마 — uk_session_rep. **현재 프로덕션과 같다.**
#   REDUNDANT_INDEX_PRE_V5=1    : V5 이전 스키마 — uk_session_event. 옛 기준선을 재보고 싶을 때만.
#
# ⚠️ 기본이 V5 가 된 것은 #320 이다. 그 전까지 이 rig 은 rep_number 컬럼조차 없는 테이블을
#    만들고 uk_session_event 를 걸었다 — 시키는 대로 돌리면 occurred_at 이 두 번째인 키 위에서
#    재게 되어 «정렬이 인덱스를 탄다» 가 당연히 나오고, 위 (새) 질문은 건드리지도 못한 채
#    표만 답해진 것처럼 보였다. **틀린 근거로 같은 결론이 나오는 자리였다.**
#
# ⚠️ 로컬 2코어(i3-6100) + MySQL·백엔드 동거 환경 — 절대 ms 수치는 신뢰 금지, 메커니즘·상대 델타만.
#
# 🔴 분포 한계: 시딩이 단일 템플릿 복제라 session_id 선택도가 균일하다. 옵티마이저의 카디널리티
#    추정이 실제 분포를 안 닮으므로, **「어느 키를 고르나」·「filesort 로 떨어지나」의 판정을
#    이 rig 하나로 확정하지 말 것.** 여기서 얻는 것은 구조(키가 쓰이는가·Extra 가 무엇인가)다.
set -u

if [ "${REDUNDANT_INDEX_PRE_V5:-0}" = "1" ]; then
    SCHEMA_ARM="V5 이전"
    UK_NAME="uk_session_event"
    UK_COLS="(session_id, occurred_at, feedback_type)"
    REP_COL_DDL=""
    REP_COL_NAME=""
    REP_COL_VALUE=""
    cat >&2 <<'WARN'
⚠️ REDUNDANT_INDEX_PRE_V5=1 — **V5 이전** 스키마로 잰다 (uk_session_event).

   이 판의 결과는 «V5 이전 세계의 값» 이다. 현재 스키마의 근거로 인용하지 말 것.
   지금 프로덕션과 같은 조건으로 재려면 이 환경변수 없이 다시 실행한다.
WARN
else
    SCHEMA_ARM="현재 (V5)"
    UK_NAME="uk_session_rep"
    UK_COLS="(session_id, rep_number, feedback_type)"
    REP_COL_DDL="  rep_number INT NOT NULL,"
    REP_COL_NAME="rep_number, "
    # r.n = 0..PER-1 을 (rep 4개 × feedback_type 5개) 로 가른다 — 그래야 세션 안에서
    # (rep_number, feedback_type) 이 유일하다. feedback_type 이 MOD(r.n,5) 로 도는 것과 짝이다.
    REP_COL_VALUE="FLOOR(r.n/5)+1, "
fi

echo "## 스키마 팔: ${SCHEMA_ARM} — ${UK_NAME} ${UK_COLS}"

PW=1234
DB(){ docker exec shadowfit-mysql mysql -uroot -p$PW shadowfit "$@" 2>/dev/null; }

# 규모. 기본은 본 측정값이고, 환경변수로 줄이면 «돌아가는지» 만 싸게 확인할 수 있다.
#   예: SESSIONS=200 INSERT_SESSIONS=50 bash loadtest/measure_redundant_index.sh
# ⚠️ 줄인 판의 EXPLAIN 은 근거로 쓰지 말 것 — 행이 적으면 옵티마이저가 다른 계획을 고른다.
SESSIONS=${SESSIONS:-50000}                 # 세션 수
PER=${PER:-20}                              # 세션당 평균 이벤트 수 (20 * 50,000 = 1,000,000 행)
INSERT_SESSIONS=${INSERT_SESSIONS:-5000}    # 이후 batch INSERT 비교에 쓸 신규 세션 수 (5,000*20=100,000 행)

# 🔴 이 rig 은 시작할 때 session_feedback_logs_scale 과 _seq 를 **DROP 한다.**
#    이 저장소는 세션이 동시에 붙으므로, 돌리기 전에 그 테이블을 쓰는 다른 측정이 없는지 볼 것:
#      docker exec shadowfit-mysql mysql -uroot -p1234 shadowfit -e "SHOW TABLES LIKE '%_scale'"

echo "## [1/6] 스크래치 테이블 생성 (인덱스 없이 — 시딩 가속)"
DB -e "
DROP TABLE IF EXISTS session_feedback_logs_scale;
CREATE TABLE session_feedback_logs_scale (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
${REP_COL_DDL}
  feedback_type VARCHAR(30) NOT NULL,
  sync_rate_at_trigger DECIMAL(5,2),
  occurred_at DATETIME NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS _seq;
CREATE TABLE _seq (n INT PRIMARY KEY);
INSERT INTO _seq
WITH d AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9)
SELECT d0.n+d1.n*10+d2.n*100+d3.n*1000+d4.n*10000 FROM d d0,d d1,d d2,d d3,d d4;"

echo "## [2/6] 시딩 — ${SESSIONS} 세션 × ${PER}행 (${UK_NAME} 을 만족하도록 r.n 을 가른다)"
DB -e "
INSERT INTO session_feedback_logs_scale (session_id, ${REP_COL_NAME}feedback_type, sync_rate_at_trigger, occurred_at, created_at)
SELECT s.n+1,
       ${REP_COL_VALUE}
       ELT(1+MOD(r.n,5), 'KNEE_OUT','BACK_BENT','HIP_HIGH','KNEE_IN','GOOD_FORM'),
       ROUND(50 + MOD(s.n*7+r.n*3, 45), 2),
       TIMESTAMP('2026-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND,
       TIMESTAMP('2026-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND
FROM _seq s CROSS JOIN _seq r WHERE s.n < $SESSIONS AND r.n < $PER;"

echo "## [3/6] 인덱스 일괄 빌드 — idx_session_feedback + ${UK_NAME}"
DB -e "
ALTER TABLE session_feedback_logs_scale
  ADD INDEX idx_session_feedback (session_id, occurred_at),
  ADD UNIQUE KEY ${UK_NAME} ${UK_COLS};
ANALYZE TABLE session_feedback_logs_scale;"
DB -t -e "SELECT COUNT(*) total_rows, COUNT(DISTINCT session_id) sessions FROM session_feedback_logs_scale;"

echo
echo "## [4/6] EXPLAIN — idx_session_feedback 존재 상태"
echo "### (a) findBySessionIdOrderByOccurredAtAsc 패턴"
DB -e "EXPLAIN SELECT * FROM session_feedback_logs_scale WHERE session_id=100 ORDER BY occurred_at ASC\G" | grep -E 'key:|key_len:|rows:|Extra:'
echo "### (b) GROUP BY feedback_type 집계 패턴"
DB -e "EXPLAIN SELECT feedback_type, COUNT(*) FROM session_feedback_logs_scale WHERE session_id=100 GROUP BY feedback_type\G" | grep -E 'key:|key_len:|rows:|Extra:'

echo
echo "## [5/6] batch INSERT 비용 — idx 있는 상태로 신규 ${INSERT_SESSIONS}세션×${PER}행 적재"
t0=$(date +%s%3N)
DB -e "
INSERT INTO session_feedback_logs_scale (session_id, ${REP_COL_NAME}feedback_type, sync_rate_at_trigger, occurred_at, created_at)
SELECT s.n+1+1000000,
       ${REP_COL_VALUE}
       ELT(1+MOD(r.n,5), 'KNEE_OUT','BACK_BENT','HIP_HIGH','KNEE_IN','GOOD_FORM'),
       ROUND(50 + MOD(s.n*7+r.n*3, 45), 2),
       TIMESTAMP('2027-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND,
       TIMESTAMP('2027-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND
FROM _seq s CROSS JOIN _seq r WHERE s.n < $INSERT_SESSIONS AND r.n < $PER;"
t1=$(date +%s%3N)
echo "idx 있음 INSERT (${INSERT_SESSIONS}*${PER}행): $((t1-t0)) ms"

echo
echo "## idx_session_feedback DROP"
DB -e "ALTER TABLE session_feedback_logs_scale DROP INDEX idx_session_feedback;"

echo
echo "## [6/6] EXPLAIN — idx_session_feedback DROP 후 (${UK_NAME} 만 남음 = 현재 프로덕션 모양)"
echo "### (a) findBySessionIdOrderByOccurredAtAsc 패턴"
DB -e "EXPLAIN SELECT * FROM session_feedback_logs_scale WHERE session_id=100 ORDER BY occurred_at ASC\G" | grep -E 'key:|key_len:|rows:|Extra:'
echo "### (b) GROUP BY feedback_type 집계 패턴"
DB -e "EXPLAIN SELECT feedback_type, COUNT(*) FROM session_feedback_logs_scale WHERE session_id=100 GROUP BY feedback_type\G" | grep -E 'key:|key_len:|rows:|Extra:'

echo
echo "## batch INSERT 비용 — idx 없는 상태(${UK_NAME} 만)로 동일 규모 신규 세션 적재"
t0=$(date +%s%3N)
DB -e "
INSERT INTO session_feedback_logs_scale (session_id, ${REP_COL_NAME}feedback_type, sync_rate_at_trigger, occurred_at, created_at)
SELECT s.n+1+2000000,
       ${REP_COL_VALUE}
       ELT(1+MOD(r.n,5), 'KNEE_OUT','BACK_BENT','HIP_HIGH','KNEE_IN','GOOD_FORM'),
       ROUND(50 + MOD(s.n*7+r.n*3, 45), 2),
       TIMESTAMP('2028-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND,
       TIMESTAMP('2028-01-01 06:00:00') + INTERVAL s.n MINUTE + INTERVAL r.n SECOND
FROM _seq s CROSS JOIN _seq r WHERE s.n < $INSERT_SESSIONS AND r.n < $PER;"
t1=$(date +%s%3N)
echo "idx 없음 INSERT (${INSERT_SESSIONS}*${PER}행): $((t1-t0)) ms"

echo
echo "## 읽는 법 (#320)"
cat <<READ
  [4/6](a) 와 [6/6](a) 의 Extra 를 비교한다. 현재(V5) 팔에서 [6/6](a) 에 «Using filesort» 가
  뜨면 그것이 V5:58-70 이 «미측정» 으로 적어둔 그 filesort 다 — 뜬다는 것 자체는 예상대로이고,
  **판단해야 하는 것은 그 filesort 가 감당할 만한가**다. rows 를 함께 볼 것(세션당 ${PER}행 전제).

  🔴 이 rig 은 그 «감당할 만한가» 에 답하지 않는다. 시딩 분포가 균일해 옵티마이저 추정이
     실제를 안 닮고, 로컬 2코어라 절대 ms 도 못 쓴다. 여기서 얻는 것은 **구조**다 —
     어느 키가 쓰이고 Extra 에 무엇이 뜨는가.
READ

echo
echo "## 정리"
DB -e "DROP TABLE IF EXISTS session_feedback_logs_scale; DROP TABLE IF EXISTS _seq;"
echo "DONE"
