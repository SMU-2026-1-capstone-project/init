#!/usr/bin/env bash
# 복제 2대 무대 — 무대 세우기 + 게이트 (본 측정 전에 돈다)
#
# 설계: docs/decisions/replication-lag-and-semisync.md §9-1 ⑧
#
# 게이트는 백업·DDL 라운드와 같은 규약이다: **여기서 막히면 본 측정을 안 돈다.**
# 환경 결함이 측정 결과로 찍히는 것이 제일 나쁜 실패라서다.
#
#   G1  복제가 붙는가                 IO/SQL=Yes 「그리고」 소스에 쓴 행이 리플리카에서 보인다
#   G2  반동기가 실제로 켜지는가      status=ON 「그리고」 yes_tx 가 는다
#   G3  계측이 지연을 잡는가          SOURCE_DELAY=5 를 lag_net 이 5초로 찍는다
#
# G4(강등 관측)는 여기서 안 돈다. 2026-08-17 로컬 라운드가 Q4 를 이미 닫았고
# (같은 디렉터리 README), 리플리카를 죽였다 살리는 절차는 이 무대에서 «따라잡기» 를
# 다시 만든다 — 본 측정 앞에서 할 일이 아니다. **안 돈 이유를 여기 적어 둔다.**
#
# 🔴 G3 이 이 라운드의 급소다. 양성 대조군이 안 서면 Q1 의 「지연이 작다」는
#    「계측이 못 잡았다」와 구분되지 않는다. 로컬 라운드가 정확히 거기서 Q3 을 잃었다.

set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/repl2_rig.sh"

GATES="$OUT/gates.tsv"
printf 'gate\tstatus\tdetail\n' > "$GATES"
gate_row() { printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$GATES"; }

# ── 무대 ─────────────────────────────────────────────────────────────────
stage_up() {
  head_ "무대 — 소스 시딩"
  require_stage
  assert_durability
  set_server_ids
  enable_gtid_both
  ensure_repl_user
  create_load_objects

  local n exp
  exp=$(( (SESSIONS - 1) * 750 + 250 ))
  n=$(SDBQ "SELECT COUNT(*) FROM $DB_NAME.pose_data_scale;" 2>/dev/null)
  if [ "${n:-0}" = "$exp" ]; then
    log "무대가 이미 서 있다 — pose_data_scale $n 행 (시딩 건너뜀)"
  else
    [ -d "$DDL_RIG" ] || die "시더를 못 찾았다 — DDL_RIG=$DDL_RIG"
    log "시딩 시작 — SESSIONS=$SESSIONS (기대 $exp 행). online-ddl rig 의 시더를 그대로 쓴다"
    # 🔴 시더는 남의 rig 이다. 변수를 섞지 않으려고 서브셸에서 부른다.
    ( OUT="$OUT" SESSIONS="$SESSIONS" CONTAINER="$CONTAINER" PW="$PW" DB_NAME="$DB_NAME" \
      bash -c "source '$DDL_RIG/_rig.sh'; seed_scale" ) || die "시딩 실패"
    n=$(SDBQ "SELECT COUNT(*) FROM $DB_NAME.pose_data_scale;")
    [ "$n" = "$exp" ] || die "행 수가 $exp 이 아니다 — 실제 '$n'"
  fi

  # 하트비트는 **사본을 뜨기 전에** 만든다. 표가 사본에 들어가 있어야 리플리카에서 읽힌다.
  start_heartbeat
}

build_replica() {
  head_ "리플리카 세우기 (§9-1 ③ — XtraBackup 사본)"
  rebuild_replica || die "리플리카를 못 세웠다"
  local c
  c=$(wait_caught_up)
  if [ "${c:-x}" = "-1" ]; then
    die "따라잡기가 상한($CATCHUP_TIMEOUT s)을 넘겼다 — 이 상태로는 어떤 판도 조건이 성립하지 않는다"
  fi
  # 🔴 이 값은 «측정» 이 아니라 «관측» 이다(설계 §9-1 🔶②). 판에서 제외되는 구간이지만
  #    「리플리카를 새로 세우면 합류까지 얼마 걸리는가」는 운영이 자주 묻는 값이라 남긴다.
  {
    echo "# 리플리카 합류 — 관측(측정 아님)"
    echo "초기화 경로:   $REPLICA_INIT_USED"
    echo "초기화 소요:   ${REPLICA_BUILD_S}s   (사본 뜨기 + 전송 + 붓기 + 기동)"
    echo "따라잡기 소요: ${c}s   (복제 붙인 뒤 소스 좌표를 따라잡기까지)"
    echo "무대 행수:     $(SDBQ "SELECT COUNT(*) FROM $DB_NAME.pose_data_scale;")"
  } | tee "$OUT/replica_build.txt"
}

# ── G1 ───────────────────────────────────────────────────────────────────
# 「붙었다」를 상태 필드로만 판정하지 않는다. 필드는 Yes 인데 스키마가 안 건너가서
# 전부 NA 로 나오는 실패를 로컬 rig 이 실제로 겪었다 — **행이 건너가는지**로 본다.
g1_replication() {
  head_ "G1 — 복제가 붙는가"
  local io sq token seen i
  io=$(rstat Replica_IO_Running); sq=$(rstat Replica_SQL_Running)
  if [ "$io" != "Yes" ] || [ "$sq" != "Yes" ]; then
    RDB -e "SHOW REPLICA STATUS\G" > "$OUT/G1_replica_status.txt" 2>&1
    gate_row G1 FAIL "IO=$io SQL=$sq"
    return 1
  fi
  token=$(now_us)
  SDB -e "INSERT INTO replprobe.session_probe (id, version) VALUES (999998, $token)
          ON DUPLICATE KEY UPDATE version = $token;" >/dev/null || { gate_row G1 FAIL "소스 쓰기 실패"; return 1; }
  for i in $(seq 1 30); do
    seen=$(RDBQ "SELECT version FROM replprobe.session_probe WHERE id=999998;" 2>/dev/null)
    [ "$seen" = "$token" ] && { gate_row G1 OK "IO/SQL=Yes · 쓴 값이 리플리카에서 보인다"; \
                                log "G1 OK"; return 0; }
    sleep 1
  done
  gate_row G1 FAIL "행이 30s 안에 안 건너갔다"
  return 1
}

# ── G2 ───────────────────────────────────────────────────────────────────
# §7 함정: 한쪽만 켜면 «켜진 것처럼 보이는데 실제로는 비동기» 다. status 만으로는
# 안 갈리므로 yes_tx 증가까지 본다.
g2_semisync() {
  head_ "G2 — 반동기가 실제로 켜지는가"
  detect_semisync_names
  if ! semisync_on; then
    gate_row G2 FAIL "status 가 ON 이 안 된다 ($SRC_PREFIX)"
    semisync_off
    return 1
  fi
  if semisync_yes_tx_grows; then
    gate_row G2 OK "status=ON · yes_tx 증가 확인 · timeout=${SEMISYNC_TIMEOUT_MS}ms · wait_point=$(SDBQ "SELECT @@${SRC_PREFIX}_wait_point;" 2>/dev/null)"
    log "G2 OK"
    semisync_off
    return 0
  fi
  gate_row G2 FAIL "status=ON 인데 yes_tx 가 안 는다 — 리플리카 쪽 플러그인을 볼 것"
  semisync_off
  return 1
}

# ── G3 ───────────────────────────────────────────────────────────────────
# 양성 대조군. 정확히 5초를 만들고 **정본 지표(lag_net)** 가 그 5초를 찍는지 본다.
# 🔴 판정은 lag_rep 가 아니라 lag_net 으로 한다. lag_rep 에는 하트비트 주기와 질의
#    왕복이 섞여 있고, 로컬 라운드가 5초를 7~11.5초로 읽은 것이 정확히 그 섞임이었다.
g3_positive_control() {
  head_ "G3 — 계측이 지연을 잡는가 (SOURCE_DELAY=5)"
  local f="$OUT/G3_positive_control.tsv"
  RDB -e "STOP REPLICA; CHANGE REPLICATION SOURCE TO SOURCE_DELAY=5; START REPLICA;" >/dev/null \
    || { gate_row G3 FAIL "SOURCE_DELAY 설정 실패"; return 1; }
  sleep 8   # 지연이 자리를 잡을 시간. 이 구간 자체는 판정에 안 쓴다
  start_sampler "$f"
  sleep 20
  stop_sampler
  RDB -e "STOP REPLICA; CHANGE REPLICATION SOURCE TO SOURCE_DELAY=0; START REPLICA;" >/dev/null
  local c; c=$(wait_caught_up); log "대조군 해제 후 따라잡기 ${c}s"

  local st n p50
  st=$(lag_stats "$f"); n=$(echo "$st" | awk '{print $1}'); p50=$(echo "$st" | awk '{print $2}')
  {
    echo "# G3 — 양성 대조군 (인위 지연 5초)"
    echo "표본 $n · lag_net p50=${p50}us p95=$(echo "$st" | awk '{print $3}')us max=$(echo "$st" | awk '{print $4}')us"
    echo "SBS p50=$(echo "$st" | awk '{print $5}') max=$(echo "$st" | awk '{print $6}')"
    echo
    echo "# 판정: lag_net p50 이 5초(5,000,000us) 근처인가."
    echo "#  «근처» 의 기준을 숫자로 박지 않는다 — 임계값을 만들 근거가 없다."
    echo "#  대신 원시 표(위 파일)를 사람이 본다. 5초를 3초나 9초로 찍으면 그 자체가 산출물이고,"
    echo "#  그때 Q1 의 값은 «그 굵기 이상만 신뢰» 라는 단서를 달고 인용해야 한다."
  } | tee "$OUT/G3_summary.txt"

  if [ "${n:-0}" -lt 5 ]; then
    gate_row G3 FAIL "표본이 $n 개뿐 — 샘플러가 안 돌았다"
    return 1
  fi
  # 자릿수 판정만 한다(초 단위인가). 5초를 「몇 % 이내」로 요구하는 순간 임의 기준이 된다.
  if [ "${p50:-0}" -ge 1000000 ]; then
    gate_row G3 OK "lag_net p50=${p50}us — 초 단위 지연을 잡는다 (원시표 확인 필요)"
    log "G3 OK (p50=${p50}us)"
    return 0
  fi
  gate_row G3 FAIL "lag_net p50=${p50}us — 5초를 만들었는데 계측이 못 잡는다"
  return 1
}

main() {
  head_ "복제 2대 무대 — 게이트"
  d0_preflight
  stage_up
  build_replica
  rtt_probe "$OUT/rtt.txt"

  local rc=0
  g1_replication     || rc=1
  g2_semisync        || rc=1
  g3_positive_control|| rc=1

  head_ "게이트 결과"
  cat "$GATES"
  if [ "$rc" != "0" ]; then
    echo >&2
    echo "🔴 게이트가 통과하지 못했다 — **본 측정을 돌리지 말 것.**" >&2
    echo "   이 상태의 수치는 «복제의 성질» 이 아니라 «무대의 결함» 이다." >&2
    exit 1
  fi
  echo
  echo "✅ 게이트 통과. 본 측정: bash $HERE/repl2_sweep.sh"
}

main "$@"
