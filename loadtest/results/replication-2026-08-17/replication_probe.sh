#!/usr/bin/env bash
# 복제 — 로컬에서 답나는 이진 사실 3개 (Q3·Q4·Q5)
#
# 설계: docs/decisions/replication-lag-and-semisync.md
#
# 🔴 범위 — 이 rig 은 Q1(몇 초 뒤처지나)·Q2(반동기가 무는 값)를 재지 않는다.
#    설계 §5 가 그 둘을 «EC2 2대» 로 못박았다. 같은 디스크·RTT ≈ 0 인 로컬에서 재면
#    반동기의 대가가 구조적으로 과소평가되고, 그건 08-08 에 「로컬 2코어」로 낸 값을
#    나중에 전부 조건부로 되돌린 것과 같은 종류의 실수가 된다.
#
#    여기서 답하는 것:
#      Q3  세션 종료 직후 리플리카에서 읽으면 «없음» 이 보이는가        (창의 유무 = 이진 사실)
#      Q4  반동기는 언제 조용히 비동기로 강등되는가                     (메커니즘)
#      Q5  Seconds_Behind_Source 를 믿을 수 있는가                      (메커니즘)
#
#    Q3 의 «창 길이» 절대값은 로컬 값이라 인용하지 않는다. 인용하는 것은 «창이 있다/없다» 다.
#
# 팔 C(SOURCE_DELAY=5)를 맨 먼저 돌린다 — 측정이 아니라 **계측의 양성 대조군**이다.
# 「지연이 거의 없다」는 관측은 「계측이 지연을 못 잡은 것」과 구분되지 않는다(설계 §2).
# 정확히 5초를 만들어 그것이 5초로 찍히는지부터 본다.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/_out"
mkdir -p "$OUT"

SRC=repl_source
REP=repl_replica
MP='-uroot -prepl'

# Q3 반복 횟수. 이진 사실이라 크게 필요 없지만, 0/N 과 N/N 을 가르려면 여러 판이 필요하다.
Q3_ROUNDS="${Q3_ROUNDS:-30}"

log()  { printf '[repl] %s\n' "$*"; }
head_() { printf '\n===== %s =====\n' "$*"; }

# 컨테이너 안에서 SQL 실행. -N -s = 헤더 없이 탭 구분.
sql()  { docker exec -i "$1" mysql $MP -N -s -e "$2"; }
sqlv() { docker exec -i "$1" mysql $MP -e "$2"; }

# SHOW REPLICA STATUS 의 한 필드를 꺼낸다.
# 주의: `\G` 는 `-N -s`(=sql) 와 같이 쓰면 파싱이 어긋난다 — 값이 빈 문자열로 나오고,
#       그게 「복제가 안 붙었다」 같은 엉뚱한 판정으로 뒤늦게 드러난다(이 rig 이 실제로 그랬다).
#       반드시 정렬 출력(sqlv)에서 뽑는다.
rstat() { sqlv "$REP" "SHOW REPLICA STATUS\G" 2>/dev/null | awk -F': *' -v k="$1" '$0 ~ "^[ ]*"k":" {print $2; exit}'; }

wait_healthy() {
  local c=$1 i
  for i in $(seq 1 60); do
    if docker exec "$c" mysqladmin ping -h 127.0.0.1 $MP >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "!! $c 가 안 뜬다" >&2; return 1
}

# ─────────────────────────────────────────────────────────────────────────────
# 0. 사전 확인 (D0) — 측정이 아니다. 무엇이 켜져 있는지 기록한다.
# ─────────────────────────────────────────────────────────────────────────────
d0_preflight() {
  head_ "D0. 사전 확인"
  {
    echo "# D0 — 무엇이 켜져 있나 (설계 §2 사전확인)"
    echo "# 생성: $(date -u '+%Y-%m-%dT%H:%M:%SZ') UTC"
    echo
    for v in log_bin binlog_format gtid_mode server_id log_replica_updates \
             replica_parallel_workers replica_parallel_type \
             innodb_flush_log_at_trx_commit sync_binlog version; do
      printf 'source   %-32s %s\n' "$v" "$(sql $SRC "SELECT @@$v;" 2>/dev/null || echo '-')"
    done
    echo
    for v in server_id read_only log_replica_updates version; do
      printf 'replica  %-32s %s\n' "$v" "$(sql $REP "SELECT @@$v;" 2>/dev/null || echo '-')"
    done
    echo
    echo "# 반동기 플러그인 파일 (D1 — 08-13 에 확인된 것을 이 이미지에서 재확인)"
    echo "# plugin_dir 를 서버에 물어본다 — 경로를 하드코딩하면 이미지가 바뀔 때 조용히 빈 목록이 된다."
    docker exec "$SRC" sh -c 'ls -1 "$(mysql -uroot -prepl -N -s -e "SELECT @@plugin_dir;" 2>/dev/null)" 2>/dev/null | grep -i semi' \
      || echo "  (semisync .so 를 못 찾았다)"
  } | tee "$OUT/D0_preflight.txt"
}

# ─────────────────────────────────────────────────────────────────────────────
# 복제 연결 — 포지션 기반 (gtid_mode=OFF 이므로)
# ─────────────────────────────────────────────────────────────────────────────
setup_replication() {
  head_ "복제 연결 (포지션 기반)"

  sqlv $SRC "
    CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED WITH mysql_native_password BY 'replpw';
    GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
    FLUSH PRIVILEGES;
  " >/dev/null

  # 🔴 스키마는 좌표를 잡은 «뒤» 에 만든다.
  #    먼저 만들면 그 DDL 이 복제 시작 지점보다 앞서서 리플리카에 영영 안 간다. 그러면
  #    리플리카는 DB 자체가 없고, 적용 스레드가 errno 1049(Unknown database)로 죽는다.
  #    — 이 rig 이 실제로 그랬고, 증상은 «하트비트가 전부 NA» 라는 엉뚱한 얼굴로 나타났다.
  #    좌표 뒤에 만들면 DDL 이 복제를 타고 넘어가므로 스키마 전파까지 같이 검증된다.

  # 소스의 현재 좌표를 잡는다.
  # ⚠️ `\G` 는 `-N -s` 와 같이 쓰면 파싱이 어긋난다 — 빈 값이 나오고, 그 빈 값이 한참 뒤
  #    CHANGE REPLICATION SOURCE TO 의 문법 오류로 드러난다. 탭 구분 한 줄로 받는다.
  local pos file coord
  coord=$(sql $SRC "SHOW MASTER STATUS;")
  file=$(printf '%s' "$coord" | awk '{print $1}')
  pos=$(printf  '%s' "$coord" | awk '{print $2}')
  log "소스 좌표 file=$file pos=$pos"
  if [ -z "$file" ] || [ -z "$pos" ]; then
    echo "!! 소스 좌표를 못 읽었다 — 복제를 붙일 수 없다" >&2; return 1
  fi

  sqlv $REP "
    STOP REPLICA;
    RESET REPLICA ALL;
    CHANGE REPLICATION SOURCE TO
      SOURCE_HOST='repl_source', SOURCE_PORT=3306,
      SOURCE_USER='repl', SOURCE_PASSWORD='replpw',
      SOURCE_LOG_FILE='$file', SOURCE_LOG_POS=$pos,
      GET_SOURCE_PUBLIC_KEY=1;
    START REPLICA;
  " >/dev/null

  sleep 3
  local io sqlt
  io=$(rstat Replica_IO_Running)
  sqlt=$(rstat Replica_SQL_Running)
  log "IO=$io SQL=$sqlt"
  if [ "$io" != "Yes" ] || [ "$sqlt" != "Yes" ]; then
    sqlv $REP "SHOW REPLICA STATUS\G" | sed -n '1,60p' | tee "$OUT/_replica_status_fail.txt"
    echo "!! 복제가 안 붙었다" >&2; return 1
  fi

  # 이제 스키마를 만든다 — 복제를 타고 리플리카로 간다.
  # 측정 대상 표 «report_probe» 는 「세션 종료 직후 리포트」의 대역이다.
  sqlv $SRC "
    CREATE DATABASE IF NOT EXISTS replprobe;
    USE replprobe;
    CREATE TABLE IF NOT EXISTS report_probe (
      id BIGINT PRIMARY KEY,
      committed_at_us BIGINT NOT NULL
    ) ENGINE=InnoDB;
    CREATE TABLE IF NOT EXISTS heartbeat (
      id INT PRIMARY KEY,
      beat_us BIGINT NOT NULL
    ) ENGINE=InnoDB;
    INSERT INTO heartbeat (id, beat_us) VALUES (1, 0)
      ON DUPLICATE KEY UPDATE beat_us = VALUES(beat_us);
  " >/dev/null

  # 스키마가 실제로 건너갔는지 확인한다. 이걸 안 보면 아래 측정이 전부 «NA» 로 나오고,
  # 그 NA 를 지연으로 오독하게 된다.
  local i ok=0
  for i in $(seq 1 20); do
    if [ "$(sql $REP "SELECT COUNT(*) FROM information_schema.tables
                      WHERE table_schema='replprobe' AND table_name IN ('report_probe','heartbeat');" 2>/dev/null || echo 0)" = "2" ]; then
      ok=1; break
    fi
    sleep 1
  done
  if [ "$ok" != "1" ]; then
    sqlv $REP "SHOW REPLICA STATUS\G" | sed -n '1,60p' | tee "$OUT/_replica_status_fail.txt"
    echo "!! 스키마가 리플리카로 안 갔다" >&2; return 1
  fi
  log "스키마 전파 확인 — 리플리카에 report_probe·heartbeat 있음"
}

now_us() { date +%s%6N; }

# ─────────────────────────────────────────────────────────────────────────────
# 팔 C — 양성 대조군. 정확히 5초를 만들고 계측이 그걸 잡는지 본다.
#         이게 통과해야 아래 «창이 거의 없다» 를 믿을 수 있다.
# ─────────────────────────────────────────────────────────────────────────────
arm_c_positive_control() {
  head_ "팔 C — 양성 대조군 (SOURCE_DELAY=5)"

  sqlv $REP "STOP REPLICA; CHANGE REPLICATION SOURCE TO SOURCE_DELAY=5; START REPLICA;" >/dev/null
  sleep 1

  {
    echo "# 팔 C — 계측의 양성 대조군 (설계 §2 팔 C)"
    echo "# 인위로 정확히 5초를 만든다. 하트비트 지연이 ~5s 로 찍히면 이 rig 은 지연을 잡는다."
    echo "# 못 잡으면 아래 팔 A 의 «창이 짧다» 는 관측은 계측 실패와 구분되지 않는다."
    echo
    printf '%-10s %-16s %-22s\n' "sample" "heartbeat_lag_s" "Seconds_Behind_Source"
    local i beat rep_beat lag sbs
    for i in $(seq 1 12); do
      beat=$(now_us)
      sqlv $SRC "UPDATE replprobe.heartbeat SET beat_us=$beat WHERE id=1;" >/dev/null
      sleep 1
      rep_beat=$(sql $REP "SELECT beat_us FROM replprobe.heartbeat WHERE id=1;" 2>/dev/null || echo 0)
      # 리플리카가 들고 있는 beat 가 «지금» 보다 얼마나 과거인가 = 실제 지연
      lag=$(awk -v n="$(now_us)" -v b="${rep_beat:-0}" 'BEGIN{ if(b==0){print "NA"} else {printf "%.2f", (n-b)/1000000} }')
      sbs=$(rstat Seconds_Behind_Source)
      printf '%-10s %-16s %-22s\n' "$i" "$lag" "${sbs:-NA}"
    done
  } | tee "$OUT/C_positive_control.txt"

  sqlv $REP "STOP REPLICA; CHANGE REPLICATION SOURCE TO SOURCE_DELAY=0; START REPLICA;" >/dev/null
  sleep 2
}

# ─────────────────────────────────────────────────────────────────────────────
# Q3 — read-after-write. 커밋 직후 리플리카를 읽으면 «없음» 이 보이는가.
#      재는 것: miss 율(첫 읽기가 놓치는가) + 창 길이.
#      🔴 창 길이의 절대값은 로컬 값이다. 인용하는 것은 miss 의 «유무».
# ─────────────────────────────────────────────────────────────────────────────
q3_read_after_write() {
  head_ "Q3 — read-after-write 창"

  local i t0 found first_miss=0 miss=0 win_us total=0 line
  : > "$OUT/Q3_windows.tsv"
  echo -e "round\tfirst_read_missed\twindow_us" >> "$OUT/Q3_windows.tsv"

  for i in $(seq 1 "$Q3_ROUNDS"); do
    t0=$(now_us)
    sqlv $SRC "INSERT INTO replprobe.report_probe (id, committed_at_us) VALUES ($i, $t0);" >/dev/null

    # 커밋 직후 «곧바로» 한 번 읽는다 — 이게 사용자가 하는 일이다.
    found=$(sql $REP "SELECT COUNT(*) FROM replprobe.report_probe WHERE id=$i;" 2>/dev/null || echo 0)
    if [ "${found:-0}" -eq 0 ]; then
      first_miss=1; miss=$((miss+1))
      # 보일 때까지 조인다 → 창의 길이
      while :; do
        found=$(sql $REP "SELECT COUNT(*) FROM replprobe.report_probe WHERE id=$i;" 2>/dev/null || echo 0)
        [ "${found:-0}" -ge 1 ] && break
      done
    else
      first_miss=0
    fi
    win_us=$(( $(now_us) - t0 ))
    total=$((total+1))
    printf '%s\t%s\t%s\n' "$i" "$first_miss" "$win_us" >> "$OUT/Q3_windows.tsv"
  done

  {
    echo "# Q3 — 세션 종료 직후 리플리카를 읽으면 «없음» 이 보이는가"
    echo "# 판수: $total"
    echo "# 첫 읽기 miss: $miss / $total"
    awk -F'\t' 'NR>1{n++; s+=$3; if($3>mx)mx=$3; a[n]=$3}
      END{ if(n){ asort_done=0;
        printf "# 창 길이(us): mean=%.0f max=%.0f\n", s/n, mx } }' "$OUT/Q3_windows.tsv" 2>/dev/null || true
    echo
    echo "🔴 창 길이의 «절대값» 은 로컬(같은 디스크·RTT≈0) 값이라 인용하지 않는다."
    echo "   이 판이 답하는 것은 «창이 존재하는가» 이고, 그 답은 miss 열이다."
  } | tee "$OUT/Q3_summary.txt"
}

# ─────────────────────────────────────────────────────────────────────────────
# Q4 — 반동기는 언제 조용히 비동기로 강등되는가.
#      「반동기니까 유실 0」이 조건부라는 것을 눈으로 잡는 자리.
# ─────────────────────────────────────────────────────────────────────────────
q4_semisync_degrade() {
  head_ "Q4 — 반동기 강등"

  # §7 함정: 8.0.26+ 신 이름(semisync_source.so)과 구 이름(semisync_master.so)이 둘 다 있다.
  # 혼용하면 «켰다고 생각했는데 안 켜진» 상태가 된다 → 켠 뒤 status 로 반드시 확인한다.
  local plug_src plug_rep
  # 🔴 이 이미지에는 신 이름과 구 이름이 **둘 다** 들어 있다(D0 확인). 아무거나 INSTALL 하면
  #    「켰다고 생각했는데 안 켜진」 상태가 만들어진다 — 그래서 켠 뒤 status 로 반드시 확인한다.
  if docker exec "$SRC" sh -c 'test -f "$(mysql -uroot -prepl -N -s -e "SELECT @@plugin_dir;" 2>/dev/null)/semisync_source.so"' >/dev/null 2>&1; then
    plug_src="rpl_semi_sync_source SONAME 'semisync_source.so'"
    plug_rep="rpl_semi_sync_replica SONAME 'semisync_replica.so'"
    SRC_PREFIX="rpl_semi_sync_source"; REP_PREFIX="rpl_semi_sync_replica"
  else
    plug_src="rpl_semi_sync_master SONAME 'semisync_master.so'"
    plug_rep="rpl_semi_sync_slave SONAME 'semisync_slave.so'"
    SRC_PREFIX="rpl_semi_sync_master"; REP_PREFIX="rpl_semi_sync_slave"
  fi
  log "플러그인 계열: $SRC_PREFIX / $REP_PREFIX"

  sqlv $SRC "INSTALL PLUGIN $plug_src;" >/dev/null 2>&1 || true
  sqlv $REP "INSTALL PLUGIN $plug_rep;" >/dev/null 2>&1 || true

  sqlv $SRC "SET GLOBAL ${SRC_PREFIX}_enabled = 1;" >/dev/null
  # 기본 10초. 강등까지 걸리는 시간을 보려고 그대로 둔다.
  sqlv $REP "SET GLOBAL ${REP_PREFIX}_enabled = 1;" >/dev/null
  sqlv $REP "STOP REPLICA IO_THREAD; START REPLICA IO_THREAD;" >/dev/null
  sleep 3

  {
    echo "# Q4 — 반동기는 언제 조용히 비동기로 강등되는가"
    echo "# 플러그인 계열: $SRC_PREFIX (8.0.26+ 신 이름 / 구 이름 혼용 사고를 §7 이 경고한 자리)"
    echo
    echo "## 강등 전"
    printf 'status=%s  timeout_ms=%s  clients=%s\n' \
      "$(sql $SRC "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='${SRC_PREFIX}_status';")" \
      "$(sql $SRC "SELECT @@${SRC_PREFIX}_timeout;")" \
      "$(sql $SRC "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='${SRC_PREFIX}_clients';")"
    echo
    echo "## 리플리카를 죽인다 — 소스는 계속 쓴다"
    docker stop $REP >/dev/null
    local t0 i st yes_tx no_tx dur
    t0=$(now_us)
    printf '%-8s %-10s %-8s %-8s %-14s\n' "t_s" "status" "yes_tx" "no_tx" "write_ms"
    for i in $(seq 1 20); do
      local w0 w1
      w0=$(now_us)
      sqlv $SRC "INSERT INTO replprobe.report_probe (id, committed_at_us) VALUES (100000+$i, $(now_us));" >/dev/null 2>&1 || true
      w1=$(now_us)
      st=$(sql $SRC "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='${SRC_PREFIX}_status';" 2>/dev/null || echo NA)
      yes_tx=$(sql $SRC "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='${SRC_PREFIX}_yes_tx';" 2>/dev/null || echo NA)
      no_tx=$(sql $SRC "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='${SRC_PREFIX}_no_tx';" 2>/dev/null || echo NA)
      dur=$(awk -v a="$w0" -v b="$w1" 'BEGIN{printf "%.1f", (b-a)/1000}')
      printf '%-8s %-10s %-8s %-8s %-14s\n' \
        "$(awk -v a="$t0" -v b="$(now_us)" 'BEGIN{printf "%.1f", (b-a)/1000000}')" \
        "$st" "$yes_tx" "$no_tx" "$dur"
      sleep 1
    done
    echo
    echo "## 리플리카를 되살린다"
    docker start $REP >/dev/null
    sleep 12
    sqlv $REP "START REPLICA;" >/dev/null 2>&1 || true
    sleep 5
    printf '복귀 후 status=%s  clients=%s\n' \
      "$(sql $SRC "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='${SRC_PREFIX}_status';")" \
      "$(sql $SRC "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='${SRC_PREFIX}_clients';")"
  } | tee "$OUT/Q4_semisync_degrade.txt"
}

# ─────────────────────────────────────────────────────────────────────────────
# Q5 — Seconds_Behind_Source 를 믿을 수 있는가.
#      팔 C 가 이미 절반 답한다(정확히 5초일 때 SBS 가 뭐라 하는가).
#      여기서는 «IO 가 끊겼는데 SQL 은 할 일이 없는» 상태를 만든다 — SBS 의 고전적 거짓말.
# ─────────────────────────────────────────────────────────────────────────────
q5_sbs_lies() {
  head_ "Q5 — Seconds_Behind_Source 검증"

  {
    echo "# Q5 — SBS 가 «따라잡았다» 고 말할 때 실제로 따라잡았는가"
    echo
    echo "## 시나리오: IO 스레드만 끊는다"
    echo "# 릴레이 로그를 다 적용한 뒤라 SQL 스레드는 할 일이 없다. SBS 는 그 상태를"
    echo "# «안 밀렸다» 로 볼 수 있는데, 소스에는 새 쓰기가 계속 쌓인다."
    echo
    sqlv $REP "STOP REPLICA IO_THREAD;" >/dev/null
    sleep 1
    local i beat rep_beat lag sbs io
    printf '%-8s %-12s %-22s %-10s\n' "t_s" "real_lag_s" "Seconds_Behind_Source" "IO"
    local t0; t0=$(now_us)
    for i in $(seq 1 10); do
      beat=$(now_us)
      sqlv $SRC "UPDATE replprobe.heartbeat SET beat_us=$beat WHERE id=1;" >/dev/null
      rep_beat=$(sql $REP "SELECT beat_us FROM replprobe.heartbeat WHERE id=1;" 2>/dev/null || echo 0)
      lag=$(awk -v n="$(now_us)" -v b="${rep_beat:-0}" 'BEGIN{ if(b==0){print "NA"} else {printf "%.2f", (n-b)/1000000} }')
      sbs=$(rstat Seconds_Behind_Source)
      io=$(rstat Replica_IO_Running)
      printf '%-8s %-12s %-22s %-10s\n' \
        "$(awk -v a="$t0" -v b="$(now_us)" 'BEGIN{printf "%.1f", (b-a)/1000000}')" \
        "$lag" "${sbs:-NA}" "${io:-NA}"
      sleep 1
    done
    echo
    sqlv $REP "START REPLICA IO_THREAD;" >/dev/null
    sleep 3
    echo "IO 복구 후 SBS=$(rstat Seconds_Behind_Source)"
  } | tee "$OUT/Q5_sbs.txt"
}

main() {
  log "무대 올림"
  docker compose -f "$HERE/docker-compose.yml" up -d
  wait_healthy $SRC
  wait_healthy $REP

  d0_preflight
  setup_replication
  arm_c_positive_control      # 먼저 — 계측을 먼저 믿을 수 있게 만든다
  q3_read_after_write
  q5_sbs_lies                 # Q4 가 리플리카를 죽이므로 Q5 를 먼저 돌린다
  q4_semisync_degrade

  head_ "완료 — 산출물"
  ls -1 "$OUT"
  echo
  echo "무대 내리기: docker compose -f $HERE/docker-compose.yml down -v"
}

main "$@"
