#!/bin/bash
# 팔 B 복구 재측정 — reflink 를 끄고 「실제로 붓는 시간」을 잰다 ([#210](https://github.com/Shadowfit/init/issues/210)).
#
# ─────────────────────────────────────────────────────────────────────────
# 왜 따로 있나
#
# `backup_sweep.sh` 의 `restore_arm_b` 가 쓰는 `cp -a` 는 이 무대의 파일시스템(xfs
# `reflink=1`)에서 **extent 를 공유할 뿐 복사하지 않았다.** 그래서 「10.4GB 를 9초에」가
# 나왔고, #201 이 넣은 `sync_ms` 는 내려쓸 게 없어서 15~22ms 로 찍혔다 — **가설이 틀린
# 자리를 정확하게 쟀다.**
#
# 이 스크립트는 그 자리를 바로잡는다:
#   ① `--reflink=never` 로 **진짜 복사**를 시킨다
#   ② 같은 판에서 `--reflink=auto`(= 기존 동작)도 돌려 **대조군**으로 세운다
#   ③ 「복사가 일어났는가」를 시간이 아니라 **디스크 소비량(df 델타)** 으로 직접 잰다
#      — 시간만 보면 또 같은 함정에 빠진다
#   ④ prepare · cp · 기동을 **쪼개서** 잰다. 초판이 셋을 한 덩어리로 재서 어디가 9초인지
#      구분이 안 됐다
#
# 🔴 백업을 다시 뜨지 않는다. b 라운드가 남긴 **아티팩트를 그대로 쓴다** — 1억 행 무대는
#    이미 real 무대로 덮여 사라졌고, 재측정에 필요한 것은 아티팩트뿐이다.
# ⚠️ 그 아티팩트들은 b 라운드에서 **이미 prepare 된 상태**다. 그래서 이 스크립트의
#    `prepare_s` 는 초판의 3초와 **같은 것을 재지 않는다**(두 번째 prepare 는 거의 no-op).
#    cp 와 기동만 초판과 비교한다.
# ─────────────────────────────────────────────────────────────────────────
#
# 사용:
#   OUT=/tmp/out sudo -E bash restore_reflink.sh

set -uo pipefail

SELF_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=${OUT:?OUT 이 필요하다}
WORK=${WORK:-$SELF_DIR/_work}

PW=${PW:-1234}
DB_NAME=${DB_NAME:-shadowfit}
MYSQL_IMAGE=${MYSQL_IMAGE:-mysql:8.0}
XB_IMAGE=${XB_IMAGE:-percona/percona-xtrabackup:8.0}
RESTORE_CONTAINER=${RESTORE_CONTAINER:-backup_restore_rf}
RESTORE_VOLUME=${RESTORE_VOLUME:-backup_restore_rf_data}
DROP_CACHES=${DROP_CACHES:-1}

# 판 배치 — `모드:태그:아티팩트:검증`.
#
# 🔴 버림판을 앞에 둔다(첫 판은 캐시·컨테이너 이미지 상태가 다르다) 그리고 두 모드를
#    **번갈아** 배치한다. 몰아서 돌리면 「모드 차이」와 「판 순서」가 분리되지 않는다.
PLAN=${PLAN:-"
never:discard:xb_r3_B:0
never:r1:xb_r3_B:1
auto:a1:xb_r6_B:1
never:r2:xb_r6_B:0
auto:a2:xb_r3_B:0
never:r3:xb_r3_B:0
auto:a3:xb_r6_B:0
"}

# b 라운드가 기록한 값 — 재복구가 **같은 것을 되살리는지** 보는 기준이다.
# 원문: loadtest/results/backup-restore-aws-b-2026-08-13/backup/backup.tsv
expected_cs() {
  case "$1" in
    xb_r3_B) echo "254366807"  ;;
    xb_r6_B) echo "2695667686" ;;
    *)       echo "-"          ;;
  esac
}
expected_rows() {  # 「백업 시점의 행수」는 writer 때문에 구간으로만 정해진다
  case "$1" in
    xb_r3_B) echo "100003342 100004041" ;;
    xb_r6_B) echo "100005978 100006647" ;;
    *)       echo "- -"                 ;;
  esac
}

mkdir -p "$OUT" || { echo "🔴 $OUT 를 못 만든다" >&2; exit 1; }
LOG=$OUT/restore_reflink.tsv
[ -f "$LOG" ] || printf "round\tmode\tartifact\tprepare_s\tcp_s\tcp_sync_ms\tboot_s\trestore_s\tpost_sync_ms\twrote_mb\trows\tchecksum\tverdict\n" > "$LOG"

now_ms()  { date +%s%3N; }
avail_kb() { df --output=avail / | tail -1 | tr -d ' '; }

drop_caches_now() {
  sync 2>/dev/null
  echo 3 > /proc/sys/vm/drop_caches 2>/dev/null \
    || echo "  ⚠️ drop_caches 실패(권한) — 캐시가 안 비워졌다. **조건에 남길 것**" >&2
}

fresh_restore_target() {
  docker rm -f "$RESTORE_CONTAINER" >/dev/null 2>&1
  docker volume rm -f "$RESTORE_VOLUME" >/dev/null 2>&1
}

wait_restore_ready() {
  for _ in $(seq 1 90); do
    docker exec "$RESTORE_CONTAINER" mysql -uroot -p"$PW" -N -e "SELECT 1;" >/dev/null 2>&1 && return 0
    sleep 2
  done
  return 1
}

echo "════════ 팔 B 복구 재측정 (#210) ════════"
echo "  결과   : $LOG"
echo "  파일시스템: $(df -T / | tail -1 | awk '{print $2}') / reflink=$(xfs_info / 2>/dev/null | grep -o 'reflink=[01]' | head -1)"
echo

for entry in $PLAN; do
  [ -n "$entry" ] || continue
  IFS=: read -r mode tag art do_cs <<< "$entry"
  d="$WORK/$art"
  if [ ! -d "$d" ]; then
    printf "%s\t%s\t%s\t-\t-\t-\t-\t-\t-\t-\t-\t-\t아티팩트 없음\n" "$tag" "$mode" "$art" >> "$LOG"
    echo "🔴 $tag — $d 가 없다. 건너뛴다"; continue
  fi

  echo "── $tag (mode=$mode, $art)"
  fresh_restore_target
  [ "$DROP_CACHES" = "1" ] && drop_caches_now

  # ① prepare — ⚠️ 이 아티팩트들은 이미 prepare 됐다. 여기 값은 초판의 3초와 다른 것이다.
  t0=$(now_ms)
  docker run --rm --user 0 -v "$d:/backup" "$XB_IMAGE" \
    xtrabackup --prepare --target-dir=/backup >> "$OUT/${tag}_xb.log" 2>&1
  t1=$(now_ms)

  # ② 붓기 — 여기가 #210 의 자리다. 시간과 **디스크 소비량**을 같이 잰다.
  docker volume create "$RESTORE_VOLUME" >/dev/null 2>&1
  sync 2>/dev/null; a0=$(avail_kb)
  t2=$(now_ms)
  docker run --rm --user 0 -v "$d:/backup:ro" -v "$RESTORE_VOLUME:/target" "$XB_IMAGE" \
    sh -c "cp -a --reflink=$mode /backup/. /target/ && chown -R 999:999 /target" >/dev/null 2>&1
  cp_rc=$?
  t3=$(now_ms)
  s0=$(now_ms); sync 2>/dev/null; s1=$(now_ms)
  a1=$(avail_kb)

  if [ $cp_rc -ne 0 ]; then
    printf "%s\t%s\t%s\t%s\t-\t-\t-\t-\t-\t-\t-\t-\tcp 실패(rc=%s)\n" \
      "$tag" "$mode" "$art" "$(( (t1-t0)/1000 ))" "$cp_rc" >> "$LOG"
    echo "  🔴 cp 실패 (rc=$cp_rc)"; fresh_restore_target; continue
  fi

  # ③ 기동 — 「다시 쓰기를 받기까지」
  t4=$(now_ms)
  docker run -d --name "$RESTORE_CONTAINER" -e MYSQL_ROOT_PASSWORD="$PW" \
    -v "$RESTORE_VOLUME:/var/lib/mysql" "$MYSQL_IMAGE" >/dev/null 2>&1
  if wait_restore_ready; then boot_ok=1; else boot_ok=0; fi
  t5=$(now_ms)
  p0=$(now_ms); sync 2>/dev/null; p1=$(now_ms)

  prepare_s=$(( (t1-t0)/1000 ))
  cp_s=$(( (t3-t2)/1000 ))
  cp_sync_ms=$(( s1-s0 ))
  boot_s=$(( (t5-t4)/1000 ))
  restore_s=$(( (t5-t0)/1000 ))
  post_sync_ms=$(( p1-p0 ))
  wrote_mb=$(( (a0 - a1) / 1024 ))

  rows="-"; cs="-"; verdict="기동 실패"
  if [ "$boot_ok" = "1" ]; then
    rows=$(docker exec "$RESTORE_CONTAINER" mysql -uroot -p"$PW" -N \
             -e "SELECT COUNT(*) FROM $DB_NAME.pose_data_scale;" 2>/dev/null | tr -d '\r')
    read -r lo hi <<< "$(expected_rows "$art")"
    if [[ "$rows" =~ ^[0-9]+$ ]] && [ "$lo" != "-" ] && [ "$rows" -ge "$lo" ] && [ "$rows" -le "$hi" ]; then
      verdict="행수 일치($rows ∈ [$lo,$hi])"
    else
      verdict="🔴 행수 불일치($rows ∉ [$lo,$hi])"
    fi
    if [ "$do_cs" = "1" ]; then
      cs=$(docker exec "$RESTORE_CONTAINER" mysql -uroot -p"$PW" -N \
             -e "CHECKSUM TABLE $DB_NAME.pose_data_scale;" 2>/dev/null | awk '{print $2}' | tr -d '\r')
      exp=$(expected_cs "$art")
      if [ "$cs" = "$exp" ]; then verdict="$verdict cs=일치($cs)"
      else                        verdict="🔴 $verdict cs=불일치($cs≠$exp)"; fi
    fi
  fi

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$tag" "$mode" "$art" "$prepare_s" "$cp_s" "$cp_sync_ms" "$boot_s" \
    "$restore_s" "$post_sync_ms" "$wrote_mb" "$rows" "$cs" "$verdict" >> "$LOG"
  echo "  → prepare ${prepare_s}s · cp ${cp_s}s(+sync ${cp_sync_ms}ms, **디스크 ${wrote_mb}MB**) · 기동 ${boot_s}s · 합 ${restore_s}s"
  echo "     $verdict"

  fresh_restore_target
done

echo
echo "════════ 끝 ════════"
column -t -s $'\t' "$LOG" 2>/dev/null || cat "$LOG"
