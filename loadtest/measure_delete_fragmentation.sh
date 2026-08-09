#!/usr/bin/env bash
# 소량 DELETE 반복이 파편화를 «누적» 시키는가
#   — outbox-reliable-messaging.md §4-1-2 가 "(a) 주기 DELETE 로 시작" 을 채택하면서
#     "소량 반복 DELETE 의 파편화 누적은 미검증" 으로 남겨둔 항목을 연다.
#
# ⚠️ 대상 테이블에 대한 정정: 이 항목은 문서·메모리 4곳에 `pose_data` 로 적혀 있으나,
#    pose_data 는 V1__baseline.sql:209 에서 이미 RANGE 파티션이고 TTL 은 DROP PARTITION 이다
#    (소량 DELETE 로 지울 일이 없다). 실제로 "주기 DELETE" 를 채택해두고 파편화를 미검증으로
#    남긴 테이블은 outbox_events 다. 그래서 여기서는 outbox_events 의 행 모양을 복제해 잰다.
#
# ⚠️ 실제 테이블을 건드리지 않는다. 전용 복제 테이블(outbox_frag)만 만들고 쓴다.
#
# ── 무엇을 «파편화» 로 볼 것인가 ────────────────────────────────────────────────
#   DELETE 후 .ibd 파일이 안 줄어드는 것은 파편화가 아니라 InnoDB 의 정상 동작이다.
#   비워진 페이지는 free list 로 가고 이후 INSERT 가 재사용한다. 따라서 판정 기준은
#   "파일이 줄어드는가" 가 아니라 **"비워진 공간이 재사용되는가"** 여야 한다.
#
# ── 무엇으로 재는가 (1차 실행에서 배운 것) ─────────────────────────────────────
#   .ibd file_size 는 이 규모에서 지표가 못 된다. 7,000행(실데이터 ~1.8MB)일 때 파일은
#   7MB 였다 — 4배가 InnoDB 초기 할당이라 데이터 증감이 파일에 안 비친다.
#   그래서 **주 지표는 클러스터 인덱스가 실제로 점유한 페이지 수**(= data_length/16KB)로 하고,
#   file_size 는 참고로만 찍는다. 그리고 신호가 할당 단위(1MB 익스텐트)를 확실히 넘도록
#   기본 규모를 키운다.
#
#   페이지 수 추정 정확도: 기본 innodb_stats_persistent_sample_pages 는 20 이라
#   1차 실행에서 «OPTIMIZE 후 페이지가 늘어난» 앞뒤 안 맞는 값이 나왔다.
#   실험 테이블에 STATS_SAMPLE_PAGES=200 을 걸어 표본을 키운다.
#
# ── 시나리오 (outbox 의 실제 모양 = FIFO) ──────────────────────────────────────
#   AUTO_INCREMENT PK 라 INSERT 는 항상 뒤에 붙고, 정리는 오래된 것부터 앞에서 지운다.
#
#   [B] steady-state (본실험): 사이클마다 INSERT n / DELETE n(가장 오래된 n행)
#                              → 행 수는 일정한데 점유 페이지가 계속 자라는가?
#   [R] 참고 (대조):           B 종료 후 OPTIMIZE TABLE → 같은 행 수를 새로 담은 크기.
#
#   🔴 [R] 은 «하한» 이 아니다 (2026-08-09 S2 실행에서 드러났다).
#      처음엔 R 을 이상적 하한으로 두고 B_final/R 을 «파편화 배수» 로 출력했는데,
#      실측은 B=1348 페이지 / R=1507 페이지로 **재구축한 쪽이 12% 더 컸다.**
#      AUTO_INCREMENT 순차 삽입은 리프 페이지를 거의 꽉 채우는 반면, InnoDB 의 정렬
#      인덱스 재구축(OPTIMIZE = ALTER TABLE FORCE)은 페이지마다 여유를 남기기 때문이다.
#      즉 이 워크로드에서 OPTIMIZE 는 «조밀하게 다시 담기» 가 아니다.
#      그래서 배수 출력은 걷어냈다 — 0.89x 같은 값은 "하한보다 좋다" 가 아니라 무의미하다.
#      R 은 이제 «재구축하면 어떻게 되는가» 참고값으로만 찍는다.
#
# ── 판정 기준 (실행 전에 못박는다. 사후 조정하지 않는다) ──────────────────────
#   · B 의 점유 페이지가 초기 안정화 후 더 자라지 않는다  → 재사용됨, 누적 없음
#   · 사이클에 대해 단조 증가한다                        → 누적 있음 (파티션 에스컬레이션 근거)
#   숫자 임계값은 두지 않는다 — 이 프로젝트에 이 지표의 baseline 이 없어서
#   임계를 만들면 근거 없는 약속이 된다. 판정은 «추세» 로만 한다.
#
# ── 이 환경에서 믿으면 안 되는 것 ──────────────────────────────────────────────
#   · 시간(초)은 재지 않는다. 2물리코어에 MySQL 이 다른 컨테이너와 동거하므로 무의미하다.
#     크기(페이지·바이트)만 본다 — CPU 경합의 영향을 받지 않는다.
#   · information_schema.tables 의 data_free 는 캐시된 추정치다. 참고로만 찍는다.
set -euo pipefail

cd "$(dirname "$0")/.."

PW=$(grep -E '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')
SCHEMA=$(grep -E '^MYSQL_DATABASE=' .env | cut -d= -f2- | tr -d '\r')
: "${PW:?MYSQL_ROOT_PASSWORD 를 .env 에서 못 읽었다}"
: "${SCHEMA:?MYSQL_DATABASE 를 .env 에서 못 읽었다}"

# 실패를 삼키지 않는다. 오늘 스크립트 3개가 정확히 이것 때문에 «다른 실험» 을 결론으로
# 바꿨다(#139·#140·#145·#146). stderr 를 통째로 버리지 않고, mysql 클라이언트가 매 호출
# 뱉는 «알려진 잡음 2줄» 만 지운다. 그 외 stderr 는 살리고, 실패하면 즉시 죽는다.
#   · 비밀번호 경고는 MYSQL_PWD 로 애초에 안 나오게 한다
#   · charset.cnf world-writable 경고는 마운트 파일 권한 탓이라 이 실험과 무관
NOISE='World-writable config file|Using a password on the command line'
MY() { docker exec -e MYSQL_PWD="$PW" -i shadowfit-mysql mysql -uroot "$@" \
         2> >(grep -Ev "$NOISE" >&2 || true); }
DB()  { MY -N -B "$SCHEMA" -e "$1"; }
DBT() { MY -t     "$SCHEMA" -e "$1"; }

# ── 규모 ──────────────────────────────────────────────────────────────────────
# S1 «실제 규모»  : DAU 1,000 · outbox 는 세션당 1행 · SENT 7일 보존
#                   → 정상상태 ~7,000행, 하루 1회 정리 시 사이클당 ~1,000행
#                   1차 실행 결과: 이 규모는 테이블 전체가 ~100페이지라 파편화를 «잴 수가 없다».
#                   그 자체가 결론이지만, 메커니즘 유무는 S2 로 따로 봐야 한다.
# S2 «메커니즘»    : 같은 FIFO 패턴을 신호가 할당 단위를 넘는 규모로. 24사이클 = 전체 3회전.
# S3 «구멍 뚫기»  : S2 와 같은 규모인데 삭제 대상이 «가장 오래된 N행» 이 아니라
#                   «가장 오래된 SENT N행» 이다. FAILED 는 남아서 구멍을 만든다.
#                   §4-1-2 의 실제 정책(SENT 7일 / FAILED 90일)이 이 모양이다.
#                   총 행수는 S2 와 같이 고정되므로 S2 의 페이지 수와 직접 비교된다.
#                   두 번째 인자 = FAILED 비율의 역수(K). n % K == 0 인 행이 FAILED.
#                     K=100 → 1% · K=20 → 5% · K=5 → 20%
SCALE="${1:-S2}"
FAILED_K="${2:-20}"
case "$SCALE" in
  S1) STEADY=7000;   CHURN=1000;  CYCLES=30 ;;
  S2) STEADY=200000; CHURN=25000; CYCLES=24 ;;
  S3) STEADY=200000; CHURN=25000; CYCLES=16 ;;
  *)  echo "사용법: $0 [S1|S2|S3] [FAILED_K]" >&2; exit 2 ;;
esac

TAG="$SCALE"
[ "$SCALE" = "S3" ] && TAG="S3-failed$(awk -v k=$FAILED_K 'BEGIN{printf "%g", 100/k}')pct"

echo "=== 소량 DELETE 파편화 — $TAG (정상상태 ${STEADY}행 / 사이클당 ${CHURN}행 / ${CYCLES}사이클) ==="
[ "$SCALE" = "S3" ] && echo "    구멍 뚫기: 신규 행의 1/${FAILED_K} 이 FAILED 로 남는다 (삭제는 SENT 만)"
echo "    전체 회전수: $(awk -v c=$CHURN -v y=$CYCLES -v s=$STEADY 'BEGIN{printf "%.1f", c*y/s}')회"
echo

# ── [0] 준비 ──────────────────────────────────────────────────────────────────
echo "## [0] 준비"
DB "DROP TABLE IF EXISTS outbox_frag;"
DB "
CREATE TABLE outbox_frag (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    payload         JSON         NOT NULL,
    correlation_id  VARCHAR(64)  NULL,
    status          ENUM('PENDING','PROCESSING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME     NULL,
    locked_by       VARCHAR(64)  NULL,
    lock_expires_at DATETIME     NULL,
    sent_at         DATETIME     NULL,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pending (status, next_retry_at),
    INDEX idx_reclaim (status, lock_expires_at)
) ENGINE=InnoDB STATS_SAMPLE_PAGES=200;"

DB "SET SESSION cte_max_recursion_depth = 1000000;
    DROP TABLE IF EXISTS nums;
    CREATE TABLE nums (n INT PRIMARY KEY);
    INSERT INTO nums
    WITH RECURSIVE s(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM s WHERE n+1 < $CHURN)
    SELECT n FROM s;"
echo "   생성기 nums: $(DB 'SELECT COUNT(*) FROM nums;')행"

hist() { MY -N -B -e "SHOW ENGINE INNODB STATUS\G" \
         | grep -o 'History list length [0-9]*' | grep -o '[0-9]*' || echo 0; }

# purge 는 비동기다. 지우자마자 재면 «아직 안 지운 것» 을 파편화로 착각한다.
# 1차 실행의 함정: 이 서버는 idle 에서도 History list length 가 0 으로 안 내려간다
# (다른 세션·내부 트랜잭션). 절대 임계로 기다리면 매번 타임아웃까지 헛돈다.
# 그래서 «시작 시점의 바닥값» 을 재두고 거기로 돌아오는지를 본다.
PURGE_FLOOR=$(hist)
echo "   purge 바닥값(실험 시작 시점): History list length = $PURGE_FLOOR"
wait_purge() {
  local i=0 h
  while [ $i -lt 20 ]; do
    h=$(hist)
    [ "${h:-0}" -le $(( PURGE_FLOOR + 5 )) ] && return 0
    sleep 1; i=$((i+1))
  done
  echo "   ⚠️ purge 가 20초 안에 바닥값으로 안 돌아왔다 (현재 $h / 바닥 $PURGE_FLOOR)." >&2
  echo "      이 사이클 수치는 «아직 안 지운 것» 을 포함할 수 있다." >&2
}

# 주 지표 = 클러스터 인덱스 점유 페이지. data_length 는 InnoDB 에서
# clustered_index_size × innodb_page_size 로 유도된 값이라 둘은 같은 것을 본다.
snapshot() {
  DB "ANALYZE TABLE outbox_frag;" >/dev/null
  local rows dl il df fsize
  rows=$(DB "SELECT COUNT(*) FROM outbox_frag;")
  read -r dl il df <<<"$(DB "SELECT data_length, index_length, data_free FROM information_schema.tables WHERE table_schema='$SCHEMA' AND table_name='outbox_frag';")"
  fsize=$(DB "SELECT file_size FROM information_schema.innodb_tablespaces WHERE name='$SCHEMA/outbox_frag';")
  echo "$rows $dl $il $df $fsize"
}

fmt_row() { # label rows data_len idx_len data_free file_size
  awk -v c="$1" -v r="$2" -v d="$3" -v x="$4" -v fr="$5" -v f="$6" \
    'BEGIN{printf "| %-8s | %10d | %9d | %9.2f | %9.2f | %9.2f | %9.1f |\n",
           c, r, d/16384, d/1048576, x/1048576, f/1048576, (r>0? d/r : 0)}'
}
HDR="| 사이클   |       행수 | 클러스터p | 데이터MB  | 인덱스MB  | 파일MB    | 바이트/행 |"
SEP="|----------|------------|-----------|-----------|-----------|-----------|-----------|"

# ── [1] 초기 적재 ─────────────────────────────────────────────────────────────
echo
echo "## [1] 초기 적재 (${STEADY}행, DELETE 없음)"
loaded=0
while [ $loaded -lt $STEADY ]; do
  n=$(( STEADY - loaded < CHURN ? STEADY - loaded : CHURN ))
  DB "INSERT INTO outbox_frag (aggregate_type, aggregate_id, event_type, payload, status, sent_at)
      SELECT 'SESSION', n + $loaded, 'STOP_ANALYSIS',
             JSON_OBJECT('sessionId', n + $loaded), 'SENT', NOW()
      FROM nums LIMIT $n;"
  loaded=$(( loaded + n ))
done
wait_purge
read -r r d x fr f <<<"$(snapshot)"
BASE_PAGES=$(( d / 16384 )); BASE_BPR=$(awk -v d=$d -v r=$r 'BEGIN{printf "%.1f", d/r}')
echo "$HDR"; echo "$SEP"; fmt_row "적재후" "$r" "$d" "$x" "$fr" "$f"

# ── [2] steady-state ──────────────────────────────────────────────────────────
echo
echo "## [2] steady-state 사이클 — 행 수는 일정, 점유 페이지가 자라는가"
echo "$HDR"; echo "$SEP"
off=$STEADY
for c in $(seq 1 $CYCLES); do
  if [ "$SCALE" = "S3" ]; then
    # 신규 행의 1/K 을 FAILED 로 심는다. 균등 분포는 «최악의 경우» 다 —
    # 실제 장애는 몰려서 나므로(AI 다운 → FAILED 버스트) 페이지 통째로 살아남는 쪽이라
    # 구멍이 덜 생긴다. 균등하게 흩뿌리는 쪽이 페이지를 더 많이 붙잡는다.
    DB "INSERT INTO outbox_frag (aggregate_type, aggregate_id, event_type, payload, status, sent_at, retry_count)
        SELECT 'SESSION', n + $off, 'STOP_ANALYSIS',
               JSON_OBJECT('sessionId', n + $off),
               IF(n % $FAILED_K = 0, 'FAILED', 'SENT'), NOW(),
               IF(n % $FAILED_K = 0, 11, 0)
        FROM nums;"
    # 삭제는 SENT 만. FAILED 는 남아 구멍이 된다.
    DB "DELETE FROM outbox_frag WHERE status='SENT' ORDER BY id LIMIT $CHURN;"
  else
    DB "INSERT INTO outbox_frag (aggregate_type, aggregate_id, event_type, payload, status, sent_at)
        SELECT 'SESSION', n + $off, 'STOP_ANALYSIS',
               JSON_OBJECT('sessionId', n + $off), 'SENT', NOW()
        FROM nums;"
    DB "DELETE FROM outbox_frag ORDER BY id LIMIT $CHURN;"
  fi
  off=$(( off + CHURN ))
  if [ "$c" -le 3 ] || [ $(( c % 4 )) -eq 0 ]; then
    wait_purge
    read -r r d x fr f <<<"$(snapshot)"
    fmt_row "$c" "$r" "$d" "$x" "$fr" "$f"
    # 재는 대상이 의도한 모양인지 매번 확인한다. «조용히 다른 것을 재는» 사고를
    # 오늘만 두 번 봤다(#153 시더, #139·#140 결론 도구).
    if [ "$SCALE" = "S3" ]; then
      echo "           └ 구성: $(DB "SELECT CONCAT(SUM(status='SENT'),' SENT / ',SUM(status='FAILED'),' FAILED') FROM outbox_frag;")"
    fi
  fi
done
read -r r d x fr f <<<"$(snapshot)"
END_PAGES=$(( d / 16384 )); END_BPR=$(awk -v d=$d -v r=$r 'BEGIN{printf "%.1f", d/r}')

# ── [3] 하한 대조 ─────────────────────────────────────────────────────────────
echo
echo "## [3] 참고 — OPTIMIZE TABLE (같은 행 수를 «새로 담으면» 얼마인가)"
echo "    ※ 하한이 아니다. 순차 삽입 클러스터 인덱스에서는 재구축이 더 커질 수 있다(헤더 주석 참조)."
DB "OPTIMIZE TABLE outbox_frag;" >/dev/null
wait_purge
read -r r2 d2 x2 fr2 f2 <<<"$(snapshot)"
OPT_PAGES=$(( d2 / 16384 )); OPT_BPR=$(awk -v d=$d2 -v r=$r2 'BEGIN{printf "%.1f", d/r}')
echo "$HDR"; echo "$SEP"; fmt_row "최적화" "$r2" "$d2" "$x2" "$fr2" "$f2"

# ── [4] 판정 ─────────────────────────────────────────────────────────────────
echo
echo "## [4] 판정"
printf "  적재직후      : %6d 페이지  (%s B/행)\n" "$BASE_PAGES" "$BASE_BPR"
printf "  %d사이클 후   : %6d 페이지  (%s B/행)   ← 적재직후 대비 %.2fx\n" \
       "$CYCLES" "$END_PAGES" "$END_BPR" \
       "$(awk -v a=$END_PAGES -v b=$BASE_PAGES 'BEGIN{print (b>0? a/b : 0)}')"
printf "  OPTIMIZE 후   : %6d 페이지  (%s B/행)   ← 참고값(하한 아님)\n" "$OPT_PAGES" "$OPT_BPR"
echo
echo "  ⚠️ 사이클 후 / OPTIMIZE 후 의 «배수» 는 출력하지 않는다. 이 워크로드에서 OPTIMIZE 는"
echo "     하한이 아니라서(순차 삽입이 재구축보다 조밀하다) 그 비율은 파편화를 뜻하지 않는다."
echo
echo "  ※ 판정은 위 [2] 표의 «클러스터p» 열이 사이클에 대해 단조 증가하는지로 한다."
echo "     한계: 합성 데이터라 payload 길이가 균일하다. 실제 outbox 도 payload 가"
echo "     {\"sessionId\": N} 한 종류라 이 축에서는 합성 한계가 실물과 거의 같다"
echo "     — 다만 correlation_id 가 실제로는 NULL 이 아닐 수 있고 여기선 전부 NULL 이다."
echo
echo "  정리: docker exec -e MYSQL_PWD=... shadowfit-mysql mysql -uroot -e \\"
echo "        'DROP TABLE ${SCHEMA}.outbox_frag, ${SCHEMA}.nums;'"