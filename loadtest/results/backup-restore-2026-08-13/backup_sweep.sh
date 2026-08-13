#!/bin/bash
# 본 측정 — 팔 A(mysqldump·논리) vs 팔 B(XtraBackup·물리) + 팔 C(파일 스냅샷·대조군).
# 설계: docs/decisions/backup-restore-rto-rpo.md §2·§4·§9-1
#
# 🔴 **`probe.sh` 의 G1~G4 가 통과해 있어야 한다.** 전제 확인 없이 이것만 돌리면
#    «binlog 도 없는 서버에서 PITR 을 잰» 표가 나온다.
#
# 🔴 **복구는 반드시 별도 컨테이너다**(설계 §3 안전 규칙). 원본에 덮어쓰는 복구는
#    실험이 아니라 사고다. 이 한 줄이 이 실험에서 제일 중요하다.
#
# 무인 실행 전 축소 리허설: `SESSIONS=134 SCALE_TAG=rehearsal bash backup_sweep.sh`
#   경로만 본다. **리허설 수치는 측정값이 아니다.**
#
# 단계:
#   preflight  G5 — XtraBackup 백업이 «실제로 복구되는가» (이진). 실패 시 팔 B 를 뺀다
#   sweep      본 측정

set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# writer·디스크 샘플러·시딩은 무중단 DDL rig 의 부품을 그대로 쓴다(설계 §3 — 그래서 싸다).
RIG=${RIG:-$HERE/../online-ddl-2026-08-09/_rig.sh}
[ -f "$RIG" ] || { echo "🔴 rig 를 못 찾았다: $RIG"; exit 1; }

# 🔴 **`HERE` 를 source 뒤에 쓰면 안 된다.** `_rig.sh` 가 자기 `HERE` 를 다시 정의하므로
#    source 이후의 `$HERE` 는 **DDL rig 디렉터리**를 가리킨다. 결과가 남의 폴더에 쓰인다.
#    그래서 내 디렉터리를 다른 이름으로 붙잡아 둔다.
SELF_DIR=$HERE
# `_rig.sh` 는 `OUT=${OUT:-$HERE}` 라 **이미 설정돼 있으면 존중한다** — 러너가 넘기는
# `OUT=$OUTDIR/backup` 도 그대로 살아남는다. 미설정일 때만 내 디렉터리로 채운다.
OUT=${OUT:-$SELF_DIR}
source "$RIG"

LOG=$OUT/backup.tsv
# 🔴 `$SELF_DIR` 이다(`$HERE` 아님 — 위 주석). 그리고 **`$OUT` 아래에 두면 안 된다** —
#    러너가 `$OUTDIR` 를 통째로 S3 에 동기화하므로 덤프·XtraBackup 산출물(수십GB)이
#    그대로 업로드된다. 중간물은 동기화 대상 밖에 둔다.
WORK=${WORK:-$SELF_DIR/_work}
RESTORE_CONTAINER=${RESTORE_CONTAINER:-shadowfit-mysql-restore}
RESTORE_VOLUME=${RESTORE_VOLUME:-backup_restore_data}
MYSQL_IMAGE=${MYSQL_IMAGE:-mysql:8.0}
XB_IMAGE=${XB_IMAGE:-percona/percona-xtrabackup:8.0}

WRITER_MAX_SEC=${WRITER_MAX_SEC:-7200}
WRITER_GAP_MS=${WRITER_GAP_MS:-200}       # 초당 5회 — 정지 구간을 200ms 해상도로
DO_CHECKSUM=${DO_CHECKSUM:-1}             # 1억 행에서 비싸다. 끄면 행수만 검증한다
DROP_CACHES=${DROP_CACHES:-1}             # §9-1 ③ — EC2(root)에선 켠다. Docker Desktop 에선 불가

mkdir -p "$WORK"

DATADIR_VOL=$(docker inspect "$CONTAINER" \
  --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}')
[ -n "$DATADIR_VOL" ] || die "datadir 볼륨을 못 찾았다"

# ── 공통 ─────────────────────────────────────────────────────────────────
now_ms() { date +%s%3N; }
dir_mb() { du -sm "$1" 2>/dev/null | cut -f1; }

# 판 사이 «같은 초기 상태» 로 되돌린다 (설계 §4·§9-1 ③).
# 🔴 백업/복구는 디스크 캐시에 특히 민감하다 — 복구본이 페이지 캐시에 남으면 다음 판의
#    백업이 빨라진다. OS 캐시만 비우면 버퍼풀이 남으므로 **둘 다** 비운다.
reset_between_rounds() {
  docker restart "$CONTAINER" >/dev/null 2>&1
  for _ in $(seq 1 60); do
    docker exec "$CONTAINER" mysqladmin ping -h localhost --silent >/dev/null 2>&1 && break
    sleep 2
  done
  if [ "$DROP_CACHES" = "1" ]; then
    sync 2>/dev/null
    echo 3 > /proc/sys/vm/drop_caches 2>/dev/null \
      || echo "  ⚠️ drop_caches 실패(권한). OS 캐시가 안 비워졌다 — 조건에 남길 것"
  fi
}

# 🔴 복구 대상은 **매번 새 컨테이너·새 볼륨**이다. 재사용하면 이전 판의 데이터가 남아
#    «복구됐다» 와 «원래 있었다» 가 구분되지 않는다.
fresh_restore_target() {
  docker rm -f "$RESTORE_CONTAINER" >/dev/null 2>&1
  docker volume rm -f "$RESTORE_VOLUME" >/dev/null 2>&1
}

wait_restore_ready() {
  # `mysqladmin ping` 은 초기화 중 **임시 서버**에 붙어 오판한다(probe.sh G3 에서 밟았다).
  # 실제 쿼리가 성공할 때까지 기다린다.
  for _ in $(seq 1 90); do
    docker exec "$RESTORE_CONTAINER" mysql -uroot -p"$PW" -N -e "SELECT 1;" >/dev/null 2>&1 \
      && return 0
    sleep 2
  done
  return 1
}

# 복구 검증 — «복구됐다» 와 «복구가 끝났다» 는 다르다(설계 §2).
verify_restored() {  # stdout: "행수 체크섬"  실패 시 non-zero
  local n cs
  n=$(docker exec "$RESTORE_CONTAINER" mysql -uroot -p"$PW" -N \
        -e "SELECT COUNT(*) FROM $DB_NAME.pose_data_scale;" 2>/dev/null | tr -d '\r')
  [[ "$n" =~ ^[0-9]+$ ]] || return 1
  if [ "$DO_CHECKSUM" = "1" ]; then
    cs=$(docker exec "$RESTORE_CONTAINER" mysql -uroot -p"$PW" -N \
          -e "CHECKSUM TABLE $DB_NAME.pose_data_scale;" 2>/dev/null | awk '{print $2}' | tr -d '\r')
  else
    cs="-"
  fi
  echo "$n $cs"
}

src_fingerprint() {  # 원본 쪽 같은 값 — 비교 대상
  local n cs
  n=$(DBQ "SELECT COUNT(*) FROM pose_data_scale;")
  if [ "$DO_CHECKSUM" = "1" ]; then
    cs=$(DBQ "CHECKSUM TABLE pose_data_scale;" | awk '{print $2}')
  else
    cs="-"
  fi
  echo "$n $cs"
}

# ── 팔 A — mysqldump (논리) ──────────────────────────────────────────────
#
# 출력은 **비압축 파일**로 고정한다(§9-1 신규 결정). 압축을 넣으면 CPU 가 변수로 들어와
# 디스크 성능 비교가 흐려진다 — 이 문서가 재는 것은 «되찾는 시간» 이지 압축률이 아니다.
backup_arm_a() {  # $1 = tag → stdout: "백업초 산출물MB"
  local tag=$1; local f="$WORK/${tag}.sql" t0 t1
  t0=$(now_ms)
  docker exec "$CONTAINER" mysqldump -uroot -p"$PW" \
    --single-transaction --source-data=2 --databases "$DB_NAME" > "$f" 2>>"$MYSQL_ERR"
  local rc=$?; t1=$(now_ms)
  [ $rc -eq 0 ] || return 1
  echo "$(( (t1 - t0) / 1000 )) $(( $(stat -c%s "$f") / 1024 / 1024 ))"
}

restore_arm_a() {  # $1 = tag → stdout: 복구초
  local tag=$1; local f="$WORK/${tag}.sql" t0 t1
  fresh_restore_target
  docker run -d --name "$RESTORE_CONTAINER" -e MYSQL_ROOT_PASSWORD="$PW" \
    -v "$RESTORE_VOLUME:/var/lib/mysql" "$MYSQL_IMAGE" >/dev/null 2>&1 || return 1
  wait_restore_ready || return 1
  # 🔴 컨테이너 기동 시간은 복구 시간에 **안** 넣는다. 재는 것은 «덤프를 되먹이는» 비용이다.
  t0=$(now_ms)
  docker exec -i "$RESTORE_CONTAINER" mysql -uroot -p"$PW" < "$f" 2>/dev/null
  local rc=$?; t1=$(now_ms)
  [ $rc -eq 0 ] || return 1
  echo "$(( (t1 - t0) / 1000 ))"
}

# ── 팔 B — XtraBackup (물리) ─────────────────────────────────────────────
backup_arm_b() {  # $1 = tag → stdout: "백업초 산출물MB"
  local tag=$1; local d="$WORK/xb_$tag" t0 t1
  rm -rf "$d"; mkdir -p "$d"
  t0=$(now_ms)
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 --network "container:$CONTAINER" \
    -v "$DATADIR_VOL:/var/lib/mysql:ro" -v "$d:/backup" "$XB_IMAGE" \
    xtrabackup --backup --target-dir=/backup --datadir=/var/lib/mysql \
    --host=127.0.0.1 --user=root --password="$PW" > "$OUT/${tag}_xb.log" 2>&1
  local rc=$?; t1=$(now_ms)
  grep -q "completed OK" "$OUT/${tag}_xb.log" || return 1
  [ $rc -eq 0 ] || return 1
  echo "$(( (t1 - t0) / 1000 )) $(dir_mb "$d")"
}

restore_arm_b() {  # $1 = tag → stdout: 복구초 (prepare + 기동까지가 RTO 다)
  local tag=$1; local d="$WORK/xb_$tag" t0 t1
  fresh_restore_target
  t0=$(now_ms)
  # ① prepare — redo 를 적용해 «일관된 시점» 으로 만든다. 이게 없으면 못 올린다.
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 -v "$d:/backup" "$XB_IMAGE" \
    xtrabackup --prepare --target-dir=/backup >> "$OUT/${tag}_xb.log" 2>&1 || return 1
  # ② 준비된 datadir 를 새 볼륨에 붓는다
  docker volume create "$RESTORE_VOLUME" >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 -v "$d:/backup:ro" \
    -v "$RESTORE_VOLUME:/target" "$XB_IMAGE" \
    sh -c 'cp -a /backup/. /target/ && chown -R 999:999 /target' >/dev/null 2>&1 || return 1
  # ③ 그 위에서 서버를 올린다. **여기까지가 「다시 쓰기를 받기까지」** 다(Q1 = RTO).
  docker run -d --name "$RESTORE_CONTAINER" -e MYSQL_ROOT_PASSWORD="$PW" \
    -v "$RESTORE_VOLUME:/var/lib/mysql" "$MYSQL_IMAGE" >/dev/null 2>&1 || return 1
  wait_restore_ready || return 1
  t1=$(now_ms)
  echo "$(( (t1 - t0) / 1000 ))"
}

# ── 팔 C — 파일 스냅샷 (대조군, 전체 백업 아님) ──────────────────────────
#
# ⭐ 이 팔의 값은 «빠른 하한선» 이자 **계측의 양성 대조군**이다. H1 은 「A 는 사실상 안
#    멈춘다」인데, «안 멈춘다» 는 관측은 «계측이 못 잡았다» 와 구분되지 않는다. 명백히
#    잠그는 팔이 같은 rig 에 있어야 그 구분이 선다(probe.sh G4 와 같은 논리).
backup_arm_c() {  # $1 = tag → stdout: "백업초 산출물MB"
  local tag=$1; local d="$WORK/snap_$tag" t0 t1
  rm -rf "$d"; mkdir -p "$d"

  # 🔴 **`FLUSH TABLES ... FOR EXPORT` 는 세션 스코프다.** `docker exec` 를 따로 띄워
  #    걸면 그 세션이 즉시 끝나며 **잠금이 바로 풀린다** — 복사는 잠금 없이 돌고,
  #    writer 도 안 멈춘다. 그러면 이 팔은 «양성 대조군» 이 아니라 그냥 파일 복사다.
  #    그래서 **잠금을 쥔 세션을 배경에 살려두고**, 복사가 끝나면 KILL 로 끊는다.
  t0=$(now_ms)
  docker exec -d "$CONTAINER" mysql -uroot -p"$PW" "$DB_NAME" \
    -e "FLUSH TABLES pose_data_scale FOR EXPORT; SELECT SLEEP(86400);" >/dev/null 2>&1
  # 잠금이 실제로 잡힐 때까지 기다린다(안 기다리면 복사가 먼저 시작될 수 있다)
  local held=0 _i
  for _i in $(seq 1 50); do
    [ -n "$(DBQ "SELECT id FROM information_schema.processlist
                 WHERE info LIKE 'SELECT SLEEP(86400)%' LIMIT 1;")" ] && { held=1; break; }
    sleep 0.2
  done
  [ "$held" = "1" ] || { echo "  ✗ 팔 C 잠금 세션이 안 잡혔다" >&2; return 1; }

  MSYS_NO_PATHCONV=1 docker run --rm --user 0 -v "$DATADIR_VOL:/var/lib/mysql:ro" \
    -v "$d:/snap" "$XB_IMAGE" \
    sh -c "cp -a /var/lib/mysql/$DB_NAME/pose_data_scale.* /snap/" >/dev/null 2>&1
  local rc=$?

  # 세션을 끊으면 잠금이 풀린다(UNLOCK TABLES 는 **다른 세션에서 못 건다**).
  local kid
  kid=$(DBQ "SELECT id FROM information_schema.processlist
             WHERE info LIKE 'SELECT SLEEP(86400)%' LIMIT 1;")
  [ -n "$kid" ] && DBQ "KILL $kid;" >/dev/null 2>&1
  t1=$(now_ms)
  [ $rc -eq 0 ] || return 1
  echo "$(( (t1 - t0) / 1000 )) $(dir_mb "$d")"
}

# ── 한 판 ────────────────────────────────────────────────────────────────
run_one() {  # $1 = round, $2 = 팔(A|B|C)
  local round=$1 arm=$2; local tag="${round}_${arm}"
  local bkp restore_s summary src_fp dst_fp verdict

  echo; echo "──────── $tag ────────"

  start_disk_sampler "${tag}_disk.txt"
  start_writer "$arm" "$WRITER_MAX_SEC" "$WRITER_GAP_MS"

  # 🔴 **백업 «직전» 행수를 잡아둔다.** 검증을 «백업 후 원본» 과 하면 절대 안 맞는다 —
  #    writer 가 백업 중에도 쓰므로 복구본(백업 시점 스냅샷)은 항상 그보다 적다.
  #    스모크에서 6판이 전부 «불일치» 로 찍힐 뻔했다(차이 10~12행 = 백업 중 쓴 양).
  #    올바른 판정은 **복구본이 [백업 직전, 백업 직후] 구간 안에 있는가** 다.
  local n_before; n_before=$(DBQ "SELECT COUNT(*) FROM pose_data_scale;")

  echo "  [백업] 팔 $arm"
  case $arm in
    A) bkp=$(backup_arm_a "$tag") ;;
    B) bkp=$(backup_arm_b "$tag") ;;
    C) bkp=$(backup_arm_c "$tag") ;;
  esac
  local brc=$?

  stop_writer
  stop_disk_sampler
  src_fp=$(src_fingerprint)

  if [ $brc -ne 0 ] || [ -z "$bkp" ]; then
    printf "%s\t%s\tFAIL\t-\t-\t-\t-\t-\t-\t-\t-\n" "$round" "$arm" >> "$LOG"
    FAILED+=("$tag:백업실패"); return 1
  fi
  local backup_s artifact_mb
  read -r backup_s artifact_mb <<< "$bkp"

  # 팔 C 는 **전체 백업이 아니다.** 복구를 재지 않는다 — 재면 A·B 와 같은 표에 놓이게 되고
  # 그건 성격이 다른 것을 나란히 세우는 일이다(설계 §2).
  if [ "$arm" = "C" ]; then
    restore_s="-"; verdict="대조군(복구 미측정)"
  else
    echo "  [복구] 별도 컨테이너로"
    case $arm in
      A) restore_s=$(restore_arm_a "$tag") ;;
      B) restore_s=$(restore_arm_b "$tag") ;;
    esac
    if [ -z "$restore_s" ]; then
      printf "%s\t%s\t%s\t%s\tFAIL\t-\t-\t-\t-\t-\t-\n" \
        "$round" "$arm" "$backup_s" "$artifact_mb" >> "$LOG"
      FAILED+=("$tag:복구실패"); fresh_restore_target; return 1
    fi
    # 복구본 행수가 [백업 직전, 백업 직후] 안에 있으면 일관된 스냅샷이다.
    # 🔴 **체크섬은 비교하지 않는다** — 행 집합이 다르면 체크섬도 당연히 다르다.
    #    writer 를 멈추면 완벽 대조가 가능하지만 그러면 Q3(백업 중 멈추는가)를 못 잰다.
    #    「일관성」은 구간으로, 「읽히는가」는 체크섬 산출 성공 여부로 본다.
    local n_after dst_n dst_cs
    n_after=$(echo "$src_fp" | awk '{print $1}')
    dst_fp=$(verify_restored) || dst_fp=""
    if [ -z "$dst_fp" ]; then
      verdict="복구본을 못 읽음"; FAILED+=("$tag:검증불가")
    else
      dst_n=$(echo "$dst_fp" | awk '{print $1}'); dst_cs=$(echo "$dst_fp" | awk '{print $2}')
      if [ "$dst_n" -ge "$n_before" ] && [ "$dst_n" -le "$n_after" ]; then
        verdict="일치(${dst_n} ∈ [${n_before},${n_after}])"
      else
        verdict="구간이탈(dst=${dst_n} 범위=[${n_before},${n_after}])"
        FAILED+=("$tag:검증구간이탈")
      fi
      [ "$dst_cs" = "-" ] || verdict="$verdict cs=$dst_cs"
    fi
    fresh_restore_target
  fi

  dump_writer_log "${tag}_writer.tsv"
  summary=$(writer_summary)
  local att err mx p50 gap; read -r att err mx p50 gap <<< "$summary"

  # writer 가 한 건도 못 썼으면 이 판은 «멈췄는가» 를 말할 수 없다.
  if [ "${att:-0}" -le 1 ]; then
    echo "  ✗ writer 시도 ${att:-0}건 — 정지 구간을 판정할 근거가 없다" >&2
    FAILED+=("$tag:writer무효")
  fi

  local dpk; dpk=$(disk_peak "${tag}_disk.txt")
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$round" "$arm" "$backup_s" "$artifact_mb" "$restore_s" \
    "$att" "$err" "$mx" "$p50" "$dpk" "$verdict" >> "$LOG"

  echo "  → 백업 ${backup_s}s(${artifact_mb}MB) · 복구 $([ "$restore_s" = "-" ] && echo "미측정" || echo "${restore_s}s") · 검증 $verdict"
  echo "    시도 ${att}건(에러 ${err}) · 최대정지 ${mx}ms · 평시 p50 ${p50}ms · 디스크피크 ${dpk}MB"
  return 0
}

# ── preflight: G5 — XtraBackup 백업이 «실제로 복구되는가» ────────────────
#
# 🔴 G2 는 «붙는다» 만 봤다. 도구가 `perl` 이 없어 **버전 검사를 건너뛴 채** 돌았으므로
#    「붙는다」와 「유효하다」는 다르다. 유효성의 판정은 **복구해서 행수·체크섬이 맞는가** 다.
#    여기서 실패하면 팔 B 는 8시간을 헛돈다 — 그래서 본 측정 앞에 세운다.
phase_g5() {
  echo; echo "════ preflight G5 — XtraBackup 복구 유효성 ════"
  local src dst
  src=$(src_fingerprint)
  echo "  원본: $src"
  backup_arm_b "g5" >/dev/null || { echo "  🔴 G5 백업 실패"; return 1; }
  restore_arm_b "g5" >/dev/null || { echo "  🔴 G5 복구 실패"; return 1; }
  dst=$(verify_restored) || { echo "  🔴 G5 복구본을 못 읽었다"; fresh_restore_target; return 1; }
  fresh_restore_target
  echo "  복구: $dst"
  { echo "# G5 — XtraBackup 복구 유효성 ($(date -Is))"
    echo "원본 (행수 체크섬): $src"
    echo "복구 (행수 체크섬): $dst"
    echo "판정: $([ "$src" = "$dst" ] && echo 일치 || echo 불일치)"
  } > "$OUT/G5_xtrabackup_restore.txt"
  [ "$src" = "$dst" ] || { echo "  🔴 G5 불일치 — **팔 B 를 빼고 돌린다**"; return 1; }
  echo "  ✅ G5 통과 — 팔 B 가 유효하다"
}

# ── 실행 ─────────────────────────────────────────────────────────────────
require_container
install_writer
[ -f "$LOG" ] || printf "round\tarm\tbackup_s\tartifact_mb\trestore_s\tattempts\terrors\tmax_stall_ms\tp50_ms\tdisk_peak_mb\tverify\n" > "$LOG"

echo "무대 시딩 — SESSIONS=$SESSIONS"
seed_scale

# 판 순서 — 팔이 2개(A·B)라 라틴 방격 대신 위치 상쇄 배열(무중단 DDL 과 같은 규약).
# 팔 C 는 대조군이라 버림 없이 1판.
SEQ=(${SWEEP_SEQ:-discard:A discard:B r1:A r2:B r3:B r4:A r5:A r6:B c1:C})

if ! phase_g5; then
  echo "⚠️ G5 실패 — 팔 B 를 판 목록에서 뺀다. 팔 A·C 는 계속한다(설계 §2 «억지로 채우지 않는다»)"
  SEQ=($(printf '%s\n' "${SEQ[@]}" | grep -v ':B$'))
  FAILED+=("G5:XtraBackup복구불가")
fi

TOTAL=${#SEQ[@]}
echo; echo "판 $TOTAL 개: ${SEQ[*]}"

i=0
for item in "${SEQ[@]}"; do
  i=$((i+1))
  echo; echo "[$i/$TOTAL]"
  run_one "${item%%:*}" "${item##*:}" || true
  [ $i -lt "$TOTAL" ] && reset_between_rounds
done

echo; echo "════ 요약 ════"; cat "$LOG"
if [ "${#FAILED[@]}" -gt 0 ]; then
  echo; echo "🔴 실패 ${#FAILED[@]}건: ${FAILED[*]}"
fi
