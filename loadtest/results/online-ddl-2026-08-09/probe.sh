#!/bin/bash
# 사전 확인 — 본 측정 전에 «전제가 서는가» 만 본다. 여기서 나온 값은 측정치가 아니다.
#
# 설계: docs/decisions/online-ddl-vs-blocking-alter.md §2(사전 확인)·§7(함정 체크리스트)
#
# ⚠️ 이 스크립트를 먼저 돌리지 않고 도구부터 붙이면, 나중에 «그냥 INPLACE 로 되는데?»
#    한 마디에 실험 전체가 무너진다. 순서가 설계의 일부다.

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_rig.sh"

require_container
echo "=== 사전 확인 ==="
echo

FAIL_HARD=0
note() { echo "  $*"; }
bad()  { echo "  ✗ $*" >&2; FAIL_HARD=1; }

# ── [1] D0 — MySQL 이 이 DDL 을 INPLACE 로 거절하는가 ────────────────────
#
# 🔴 이게 이 실험의 전제다. 거절해야 «그래서 외부 도구가 필요하다» 가 성립한다.
#    성공하면 팔 B·C 는 «필요 없는 도구» 를 재는 셈이 되므로 설계를 재검토해야 한다.
#
# 1,000만 행이 아니라 소형 복제본에 던진다 — 거절 여부는 파서/스토리지엔진 판정이라
# 행 수와 무관하고, 혹시 «성공» 하면 큰 테이블에선 오래 걸려버린다.
echo "## [1] ALGORITHM=INPLACE, LOCK=NONE 을 던져본다 (소형 복제본)"
DB -e "
  DROP TABLE IF EXISTS pose_data_probe;
  CREATE TABLE pose_data_probe LIKE pose_data_scale;
" >/dev/null 2>&1
if ! DBQ "SELECT 1 FROM information_schema.tables
          WHERE table_schema='$DB_NAME' AND table_name='pose_data_probe';" | grep -q 1; then
  # pose_data_scale 이 아직 없으면 정의를 직접 만든다 (probe 를 시딩 전에 돌릴 수 있게)
  DB -e "CREATE TABLE pose_data_probe (
           id bigint NOT NULL AUTO_INCREMENT,
           session_id bigint NOT NULL,
           timestamp_sec double NOT NULL,
           joint_coordinates text NOT NULL,
           sync_rate double DEFAULT NULL,
           is_correct tinyint(1) DEFAULT 1,
           feedback_message varchar(500) DEFAULT NULL,
           created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
           PRIMARY KEY (id, created_at)
         ) ENGINE=InnoDB;" >/dev/null 2>&1 || die "probe 테이블을 못 만들었다"
fi
DB -e "INSERT INTO pose_data_probe (session_id, timestamp_sec, joint_coordinates, created_at)
       VALUES (1, 0, '{}', '2026-03-01 00:00:00'), (1, 1, '{}', '2026-07-01 00:00:00');" >/dev/null 2>&1

# 🔴 판정은 «종료 코드» 가 아니라 «에러 번호» 로 한다.
#    2026-08-09 1차 실행이 이 구분이 없어서 거짓 통과했다 — `PARTITION BY (...)` 뒤에
#    콤마로 ALGORITHM 을 이어 붙인 SQL 이 파서에서 깨졌는데(1064), rc≠0 이라는 이유로
#    «거절됨 ✅ 전제 성립» 이 찍혔다. 서버는 INPLACE 를 판정한 적조차 없었다.
#    **문법이 깨진 것과 서버가 거절한 것은 같은 rc 를 낸다.** 번호를 봐야 갈린다.
#
# 문법 — MySQL 8.0 의 ALTER TABLE 은 `alter_option [, ...] [partition_options]` 순서다.
#    partition_options 는 목록의 원소가 아니라 **뒤에 붙는 별개 절**이라 콤마를 못 쓴다.
#      ✗ ALTER TABLE t PARTITION BY RANGE (...) (...), ALGORITHM=INPLACE, LOCK=NONE
#      ✅ ALTER TABLE t ALGORITHM=INPLACE, LOCK=NONE PARTITION BY RANGE (...) (...)
err_no() { printf '%s\n' "$1" | sed -n 's/^ERROR \([0-9][0-9]*\).*/\1/p' | head -1; }

throw_alter() {  # $1 = alter_option 절. stdout=서버 출력, rc=mysql 종료 코드
  docker exec -i "$CONTAINER" mysql -uroot -p"$PW" "$DB_NAME" \
    -e "ALTER TABLE pose_data_probe $1 ${PARTITION_SPEC};" 2>&1
}

OPTS_A="ALGORITHM=INPLACE, LOCK=NONE"
OUT_A=$(throw_alter "$OPTS_A"); RC_A=$?
NO_A=$(err_no "$OUT_A")

{
  echo "# D0 — ALGORITHM=INPLACE 거절 여부 (probe.sh [1])"
  echo "# 소형 복제본 pose_data_probe 에 던진 결과. 측정치가 아니라 전제 확인이다."
  echo
  echo "## 던진 문장"
  echo "ALTER TABLE pose_data_probe $OPTS_A ${PARTITION_SPEC};"
  echo
  echo "## 서버 응답 (rc=$RC_A, errno=${NO_A:-없음})"
  echo "$OUT_A"
} > "$OUT/probe_inplace_rejection.txt"

case "${NO_A:-}" in
  1845|1846)
    # 1845 ER_ALTER_OPERATION_NOT_SUPPORTED / 1846 ...NOT_SUPPORTED_REASON
    # → 서버가 이 DDL 을 INPLACE 로 못 한다고 **판정**했다. 이게 우리가 원한 답이다.
    note "거절됨 ✅ — 전제 성립 (errno=$NO_A). 서버 메시지:"
    printf '%s\n' "$OUT_A" | sed 's/^/      /'
    note "→ 이 메시지가 «왜 외부 도구가 필요한가» 의 1차 근거다. 결과 문서에 원문 인용할 것."
    ;;
  1064)
    echo "  🔴 문법 에러(1064) — **서버가 거절한 게 아니라 내 SQL 이 깨졌다.**" >&2
    printf '%s\n' "$OUT_A" | sed 's/^/      /' >&2
    echo "     이건 전제의 근거가 아니라 스크립트 결함이다. probe.sh 를 고치고 다시 돌릴 것." >&2
    FAIL_HARD=1
    ;;
  "")
    if [ $RC_A -eq 0 ]; then
      echo "  🔴 성공했다 — **이 실험의 전제가 흔들린다.**" >&2
      echo "     도구 없이 무중단이 된다면 팔 B·C 는 «필요 없는 도구» 를 재는 것이다." >&2
      echo "     설계 §2 대로 재검토가 필요하다. 본 측정으로 넘어가지 말 것." >&2
    else
      echo "  🔴 에러 번호를 못 읽었는데 rc=$RC_A 다 — 연결 실패 같은 판정 밖 사유로 본다." >&2
      printf '%s\n' "$OUT_A" | sed 's/^/      /' >&2
      echo "     거절의 근거로 쓸 수 없다. 원인을 먼저 볼 것." >&2
    fi
    FAIL_HARD=1
    ;;
  *)
    echo "  🔴 거절이긴 한데 사유가 다르다 (errno=$NO_A) — INPLACE 판정이 아니다." >&2
    printf '%s\n' "$OUT_A" | sed 's/^/      /' >&2
    echo "     예: 1146(테이블 없음)·1503(PK 가 파티션 키를 안 담음)은 전제와 무관한 실패다." >&2
    FAIL_HARD=1
    ;;
esac

# ── [1b] LOCK=NONE 만 걸린 건 아닌가 ─────────────────────────────────────
#
# [1a] 는 두 옵션을 같이 던진다. 거절 사유가 «INPLACE 자체» 가 아니라 «LOCK=NONE» 일 수도
# 있는데, 그 둘은 결론이 다르다: INPLACE 가 된다면 풀 카피는 피하는 셈이라 팔 B 가 사는
# 이유가 반쯤 줄어든다. 한 줄 더 던져서 갈라 둔다.
if [ "${NO_A:-}" = "1845" ] || [ "${NO_A:-}" = "1846" ]; then
  OUT_B=$(throw_alter "ALGORITHM=INPLACE"); RC_B=$?
  NO_B=$(err_no "$OUT_B")
  {
    echo
    echo "## [1b] LOCK 절 없이 ALGORITHM=INPLACE 만 (rc=$RC_B, errno=${NO_B:-없음})"
    echo "$OUT_B"
  } >> "$OUT/probe_inplace_rejection.txt"

  if [ $RC_B -eq 0 ]; then
    echo "  🔴 LOCK 절을 빼니 INPLACE 로 됐다 — 거절 사유는 LOCK=NONE 이었다." >&2
    echo "     «INPLACE 불가» 가 아니라 «무중단 불가» 다. 설계 §2 의 전제 문장을 고쳐야 한다." >&2
    FAIL_HARD=1
    # 파티션이 걸려버렸다 — 아래 DROP 이 치우지만 상태를 남긴다.
  else
    note "LOCK 절 없이도 거절됨 ✅ (errno=${NO_B:-없음}) — 사유는 INPLACE 자체다"
  fi
fi
DB -e "DROP TABLE IF EXISTS pose_data_probe;" >/dev/null 2>&1
echo

# ── [2] 함정 체크리스트 (설계 §7) ────────────────────────────────────────
echo "## [2] 함정 체크리스트"

# 🔴 이 절의 절반은 pose_data_scale 을 읽는데, probe 는 **시딩 전에도 돌게** 설계돼 있다
#    ([1] 이 소형 복제본을 따로 만드는 이유가 그거다). 테이블이 없으면 트리거 0개·PK 없음·
#    디스크 0MB 가 그대로 나오고, 그건 «확인됐다» 가 아니라 «확인할 대상이 없었다» 다.
#    [1] 에서 rc 로 문법 에러를 «거절» 로 읽은 것과 같은 계열의 착각이라 여기도 갈라 둔다.
DEFERRED=0
defer() { echo "  ⏸  $* — 시딩 후 재실행 필요"; DEFERRED=$((DEFERRED + 1)); }

SCALE_EXISTS=$(DBQ "SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema='$DB_NAME' AND table_name='pose_data_scale';")
[ "${SCALE_EXISTS:-0}" = "1" ] \
  && note "pose_data_scale 있음 — 아래 표 항목을 실제로 읽는다" \
  || note "pose_data_scale 없음(시딩 전) — 이 테이블을 읽는 항목은 판정하지 않는다"

# 트리거 — pt-osc 는 트리거를 새로 건다. 이미 있으면 실패한다.
if [ "${SCALE_EXISTS:-0}" = "1" ]; then
  TRG=$(DBQ "SELECT COUNT(*) FROM information_schema.triggers
             WHERE trigger_schema='$DB_NAME' AND event_object_table='pose_data_scale';")
  if [ "${TRG:-0}" = "0" ]; then note "트리거 0개 ✅ (pt-osc 가 걸 자리가 비어 있다)"
  else bad "pose_data_scale 에 트리거가 ${TRG}개 있다 — pt-osc 가 거부한다"; fi
else
  defer "트리거 유무"
fi

# binlog — gh-ost 는 row-based 를 요구한다. docker-compose 에 지정이 없어 기본값 확인이 필요.
LOGBIN=$(DBQ "SELECT @@log_bin;")
BFMT=$(DBQ "SELECT @@binlog_format;")
note "log_bin=$LOGBIN  binlog_format=$BFMT"
[ "$LOGBIN" = "1" ] || note "  ⚠️ binlog 가 꺼져 있다 — gh-ost 불가, binlog 델타 지표도 못 걷는다"
[ "$BFMT" = "ROW" ] || note "  ⚠️ ROW 가 아니다 — gh-ost 불가"

# 디스크 — 팔 B 는 사본을 만든다. 1,000만 행 ~1.1GB 예상 → 여유 2배 이상이어야 한다.
#   시딩 전에는 현재 크기가 0 이라 «2배» 를 걸 대상이 없다. 그때는 예상치로 본다.
AVAIL=$(docker exec "$CONTAINER" bash -c "df -Pm /var/lib/mysql | awk 'NR==2 {print \$4}'")
CUR=$(disk_now)
note "데이터 파일 ${CUR:-0}MB · 여유 ${AVAIL:-?}MB"
if [ -z "${AVAIL:-}" ]; then
  bad "여유 디스크를 못 읽었다 — 팔 B 가 중간에 죽을지 판정 불가"
elif [ "${CUR:-0}" -gt 0 ]; then
  [ "$AVAIL" -gt $((CUR * 2)) ] || bad "여유 디스크가 현재 크기의 2배 미만 — 팔 B 사본이 안 들어간다"
else
  # 시딩 전 — 설계 §9 의 1,000만 행 예상치(~1.1GB)에 사본까지 2배로 잡는다.
  if [ "$AVAIL" -gt 2200 ]; then note "  시딩 전이라 예상치로 판정: 예상 2,200MB < 여유 ${AVAIL}MB ✅"
  else bad "여유 ${AVAIL}MB — 예상 소요 2,200MB(원본+사본)를 못 담는다"; fi
fi

# PK — pt-osc 의 청크 인덱스 선택이 단일 PK 전제와 다를 수 있다(설계 §7).
if [ "${SCALE_EXISTS:-0}" = "1" ]; then
  PK=$(DBQ "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
            FROM information_schema.statistics
            WHERE table_schema='$DB_NAME' AND table_name='pose_data_scale' AND index_name='PRIMARY';")
  [ -n "${PK:-}" ] && [ "$PK" != "NULL" ] \
    && note "PK = ($PK)  ← pt-osc 가 어느 인덱스로 청크를 나누는지 실행 로그에서 확인할 것" \
    || bad "PK 를 못 읽었다 — pt-osc 는 PK 없는 테이블을 거부한다"
else
  defer "PK 구성"
fi

# 실 pose_data 는 대상이 아니다 — 이미 파티션이 걸려 있다.
REALP=$(DBQ "SELECT COUNT(*) FROM information_schema.partitions
             WHERE table_schema='$DB_NAME' AND table_name='pose_data' AND partition_name IS NOT NULL;")
note "실 pose_data 파티션 ${REALP:-0}개 — 대상 아님(이미 걸려 있어 같은 DDL 을 못 건다)"
echo

# ── [3] 도구 가용성 ──────────────────────────────────────────────────────
echo "## [3] 도구"

if docker image inspect percona/percona-toolkit >/dev/null 2>&1; then
  note "percona-toolkit 이미지 있음 ✅"

  # ── [3b] pt-osc 가 실제로 붙고, 이 DDL 을 받는가 ────────────────────────
  #
  # 🔴 이미지가 있는 것과 도구가 도는 것은 다르다. 확인 안 하면 팔 B 4판이 전부
  #    실패한 뒤에야 드러나는데, 그게 시딩 4번 뒤(=2시간 뒤)다.
  #
  #    함정 두 개가 실제로 있었다:
  #      ① root 로는 못 붙는다. Perl DBD::mysql 이 caching_sha2_password(8.0 기본)를
  #         비-SSL 에서 못 읽는다 → «Authentication requires secure connection», rc=2.
  #         DSN 에 mysql_ssl=1 을 넣는 우회도 안 된다(호스트명으로 파싱된다).
  #      ② --alter 로 PARTITION BY 를 받는지가 미검증이었다.
  #
  #    소형 테이블에 진짜로 던져서 둘을 한 번에 가른다. 1,000만 행이 아니라 3행이라 몇 초다.
  ensure_ptosc_user
  DB -e "
    DROP TABLE IF EXISTS ptosc_smoke;
    CREATE TABLE ptosc_smoke (
      id bigint NOT NULL AUTO_INCREMENT,
      session_id bigint NOT NULL,
      created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (id, created_at)
    ) ENGINE=InnoDB;
    INSERT INTO ptosc_smoke (session_id, created_at) VALUES
      (1,'2026-01-15 00:00:00'),(2,'2026-03-15 00:00:00'),(3,'2026-06-15 00:00:00');
  " >/dev/null 2>&1 || bad "스모크 테이블을 못 만들었다"

  docker run --rm --network "container:$CONTAINER" percona/percona-toolkit \
    pt-online-schema-change \
      --alter "$PARTITION_SPEC" \
      --execute --print --statistics --recursion-method=none \
      "h=127.0.0.1,P=3306,u=$PTOSC_USER,p=$PTOSC_PW,D=$DB_NAME,t=ptosc_smoke" \
    > "$OUT/probe_ptosc_smoke.log" 2>&1
  SMOKE_RC=$?
  SMOKE_PARTS=$(DBQ "SELECT COUNT(*) FROM information_schema.partitions
                     WHERE table_schema='$DB_NAME' AND table_name='ptosc_smoke'
                       AND partition_name IS NOT NULL;" | tr -d '[:space:]')

  # 🔴 rc=0 만 보면 안 된다. [1] 에서 rc 로 문법 에러를 «거절» 로 읽은 것과 같은 계열이다.
  #    도구가 조용히 포기해도 0 을 낼 수 있으므로 **파티션이 실제로 걸렸는지**까지 본다.
  if [ $SMOKE_RC -eq 0 ] && [ "${SMOKE_PARTS:-0}" = "14" ]; then
    note "pt-osc 접속 ✅ · --alter 로 PARTITION BY 수용 ✅ (소형 14개 파티션 확인)"
    note "  방식: 빈 새 테이블에 ALTER 를 걸고 원본을 청크 복사한다 — 원문 probe_ptosc_smoke.log"
    SWAP=$(grep -E 'Swapping tables|Swapped original' "$OUT/probe_ptosc_smoke.log" | head -2)
    [ -n "$SWAP" ] && printf '%s\n' "$SWAP" | sed 's/^/      /'
    note "  ⚠️ 위 두 시각의 차이가 3행짜리 테이블에서의 컷오버 비용이다. 0 이 아니면"
    note "     설계 §1 Q3(«무중단의 실제 차단 시간»)의 답이 0 이 아닐 수 있다는 신호다."
  else
    echo "  🔴 pt-osc 스모크 실패 (rc=$SMOKE_RC, 파티션 ${SMOKE_PARTS:-0}개) — 팔 B 를 못 돈다." >&2
    tail -5 "$OUT/probe_ptosc_smoke.log" | sed 's/^/      /' >&2
    echo "     본 측정으로 넘어가면 팔 B 4판이 전부 실패한 뒤에야 이게 드러난다." >&2
    FAIL_HARD=1
  fi
  DB -e "DROP TABLE IF EXISTS ptosc_smoke;" >/dev/null 2>&1
else
  note "percona-toolkit 이미지 없음 → 받아야 한다:  docker pull percona/percona-toolkit"
  bad "팔 B 를 못 돈다"
fi

# gh-ost — 설계 §9 결정: **본 측정에서 제외.** 여기선 «될 것 같은가» 만 남긴다.
echo
note "gh-ost: 본 측정 제외(설계 §9 결정). 아래는 후속 라운드 판단용 재료다."
if [ "$LOGBIN" = "1" ] && [ "$BFMT" = "ROW" ]; then
  note "  binlog 전제는 충족. 남은 미검증은 **PARTITION BY 를 --alter 로 받는가**"
  note "  → 확인 방법: 소형 테이블에 --test-on-replica 없이 --execute 를 걸어보고 거절 메시지를 본다"
else
  note "  binlog 전제부터 불충족 — 이 환경에선 gh-ost 를 볼 필요가 없다"
fi
echo

# ── 판정 ─────────────────────────────────────────────────────────────────
if [ $FAIL_HARD -ne 0 ]; then
  echo "🔴 전제가 서지 않았다 — 본 측정으로 넘어가지 말 것." >&2
  exit 1
fi
if [ ${DEFERRED:-0} -gt 0 ]; then
  echo "✅ D0 전제 확인 완료 — 단 ${DEFERRED}개 항목은 **판정 안 됨**(pose_data_scale 이 없다)."
  echo "   ddl_sweep.sh 는 시딩을 스스로 하므로 진행 가능하지만, 보류 항목은 위 ⏸ 로 남는다."
  echo "   전부 판정된 probe 기록이 필요하면 시딩 후 이 스크립트를 한 번 더 돌릴 것."
else
  echo "✅ 전제 확인 완료. ddl_sweep.sh 로 넘어가도 된다."
fi
