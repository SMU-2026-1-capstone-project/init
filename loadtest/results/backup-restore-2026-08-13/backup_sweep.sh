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
#
# ── 무대(STAGE) ──────────────────────────────────────────────────────────
#   dummy (기본) : `joint_coordinates='{}'` 더미 JSON. 1억 행 본 측정의 무대
#   real         : 실제 33 랜드마크 JSON(~2KB/행)을 `_pose_template` 에서 복제한 축소 무대.
#                  설계 §9-1 「확정된 것」의 후반부(«real-JSON 소규모 대조 1판»)다 — #202.
#
#   🔴 **두 무대는 같은 표에 절대 안 올린다.** 행 수도 기계도 같지 않다. real 은
#      「행 «크기» 가 팔 A 를 얼마나 더 불리하게 만드는가」만 본다(배수·방향만).
#   🔴 **무대 이름은 둘 다 `pose_data_scale` 이다**(테이블명이 아니라 «내용» 이 다르다).
#      writer·검증·디스크 샘플러가 전부 이 이름에 붙어 있어서, 이름을 바꾸면 rig 4곳이
#      같이 흔들린다. 그래서 real 무대도 같은 DDL·같은 이름으로 세운다.
#
# ── 복구 시간의 정의 (#201) ──────────────────────────────────────────────
#   초판(2026-08-13)은 팔 B 복구를 **9초**로 찍었다. 10.4GB 를 gp3 125MB/s 볼륨에서
#   9초에 옮기는 것은 불가능하고, 실제로는 `cp` 가 **페이지 캐시까지만 쓰고 반환**한 것이다.
#   팔 A 는 InnoDB 를 거쳐 redo 로 내구성이 서는데 팔 B 는 안 섰다 — **두 팔이 다른 것을 쟀다.**
#
#   이제 세 값을 다 남긴다. 정의를 하나로 정하고 나머지를 버리지 않는다:
#     restore_s          «서버가 다시 쓰기를 받기까지» (초판과 같은 정의 — 비교 가능)
#     sync_ms            그 뒤 `sync` 가 끝나기까지 = **아직 디스크에 안 내려간 분량**
#     restore_durable_s  둘의 합 = «크래시를 견디는 상태까지»
#   그리고 **복구 직전에 캐시를 비운다**(`DROP_CACHES_BEFORE_RESTORE`). 복구는 장애 중에
#   하는 일이라 「방금 백업해서 캐시가 따뜻한」 상태는 현실이 아니다. 양 팔에 똑같이 건다.

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
# 🔴 **source 앞에서 만든다.** `_rig.sh` 가 `MYSQL_ERR=$OUT/mysql_stderr.log` 를 잡는데,
#    디렉터리가 없으면 첫 `DB()` 의 리다이렉트가 통째로 실패하고 «프로시저가 안 만들어졌다»
#    로 보인다 — 원인은 MySQL 이 아니라 없는 폴더다. `probe.sh:42` 가 같은 이유로 이미
#    이 줄을 갖고 있었고, 이쪽에만 없었다(#203).
mkdir -p "$OUT" || { echo "🔴 OUT 을 못 만든다: $OUT" >&2; exit 1; }
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
DROP_CACHES_BEFORE_RESTORE=${DROP_CACHES_BEFORE_RESTORE:-1}   # #201 — 복구는 장애 중에 한다

# ── 무대 ─────────────────────────────────────────────────────────────────
STAGE=${STAGE:-dummy}                     # dummy | real
REAL_SESSIONS=${REAL_SESSIONS:-1000}      # real 무대: 세션 수 × 템플릿 750행
REAL_TEMPLATE_ROWS=${REAL_TEMPLATE_ROWS:-750}
case "$STAGE" in dummy|real) ;; *) echo "🔴 STAGE 는 dummy|real 이어야 한다 (받은 값 '$STAGE')"; exit 1 ;; esac

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

# 캐시를 비운다 — `reset_between_rounds` 안의 그 동작을 단독으로도 쓴다(#201).
#
# 🔴 **실패를 경고로 넘기지 않는다.** 못 비운 채로 복구를 재면 warm-cache 시간이 `backup.tsv`
#    에 «정상 값» 으로 앉는다 — 그게 정확히 #201 이 만든 사고다(「9초」가 표에선 멀쩡해 보였다).
#    비우기가 불가능한 환경(Docker Desktop·비 root)에서 돌릴 거면
#    `DROP_CACHES_BEFORE_RESTORE=0` 으로 **끄고 그 사실을 조건에 남긴다.** 끄지 않은 채
#    실패하는 것은 «조건을 모르고 잰» 것이라 판을 버리는 게 맞다.
drop_caches_now() {  # $1 = 실패했을 때 찍을 맥락 → 실패 시 non-zero
  sync 2>/dev/null
  echo 3 > /proc/sys/vm/drop_caches 2>/dev/null && return 0
  echo "  🔴 drop_caches 실패(권한) — ${1:-} 캐시를 못 비웠다. 이 판은 무효다" >&2
  return 1
}

# 🔴 **이 함수가 #201 의 답이다.** 복구가 끝난 «직후» 아직 안 내려간 분량을 시간으로 잰다.
#    0 에 가까우면 그 팔의 복구 시간은 이미 내구성까지 포함한 값이고, 크면 그만큼이
#    「빨라 보였을 뿐」이다. 판정을 사람이 나중에 하도록 **값으로** 남긴다.
sync_ms() {
  local t0 t1
  t0=$(now_ms); sync 2>/dev/null; t1=$(now_ms)
  echo $(( t1 - t0 ))
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

restore_arm_a() {  # $1 = tag → stdout: "복구초 sync_ms"
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
  # 팔 A 는 커밋마다 redo 를 내리므로(flush=1) 여기서 남는 건 대개 더티 페이지다.
  # 그래도 **같은 자를 양 팔에 댄다** — 「안 나올 것」이라는 예상도 값으로 확인한다.
  echo "$(( (t1 - t0) / 1000 )) $(sync_ms)"
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

restore_arm_b() {  # $1 = tag → stdout: "복구초 sync_ms" (prepare + 기동까지가 RTO 다)
  local tag=$1; local d="$WORK/xb_$tag" t0 t1
  fresh_restore_target
  t0=$(now_ms)
  # ① prepare — redo 를 적용해 «일관된 시점» 으로 만든다. 이게 없으면 못 올린다.
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 -v "$d:/backup" "$XB_IMAGE" \
    xtrabackup --prepare --target-dir=/backup >> "$OUT/${tag}_xb.log" 2>&1 || return 1
  # ② 준비된 datadir 를 새 볼륨에 붓는다
  #
  # 🔴 `--reflink=never` 가 **필수다**(#210). 이 무대의 루트는 xfs `reflink=1` 이라 기본
  #    동작이 extent 를 공유해버린다 — 10.4GB 가 «복사» 되고도 디스크 소비가 0 이었고,
  #    그래서 복구가 9초로 찍혔다. 재는 것은 「datadir 를 실제로 붓는 시간」이므로
  #    **공유가 아니라 복사여야 한다.**
  docker volume create "$RESTORE_VOLUME" >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 docker run --rm --user 0 -v "$d:/backup:ro" \
    -v "$RESTORE_VOLUME:/target" "$XB_IMAGE" \
    sh -c 'cp -a --reflink=never /backup/. /target/ && chown -R 999:999 /target' >/dev/null 2>&1 || return 1
  # ③ 그 위에서 서버를 올린다. **여기까지가 「다시 쓰기를 받기까지」** 다(Q1 = RTO).
  docker run -d --name "$RESTORE_CONTAINER" -e MYSQL_ROOT_PASSWORD="$PW" \
    -v "$RESTORE_VOLUME:/var/lib/mysql" "$MYSQL_IMAGE" >/dev/null 2>&1 || return 1
  wait_restore_ready || return 1
  t1=$(now_ms)
  # 🔴 여기가 초판이 틀린 자리다(#201). `cp -a` 는 fsync 하지 않아 위 구간이 **페이지 캐시까지**
  #    일 수 있다. 그 분량을 시간으로 뽑아 옆 칸에 세운다.
  echo "$(( (t1 - t0) / 1000 )) $(sync_ms)"
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

# ── real 무대 시딩 (#202) ────────────────────────────────────────────────
#
# 더미 무대와 **DDL·테이블명이 같고 페이로드만 다르다.** 그래야 「행 크기」 하나만 바뀐다.
# 템플릿은 `loadtest/seed/gen_pose_template.py`(33 랜드마크 JSON ≈2,076B/행)를 쓴다 —
# 새로 만들지 않는다. 그 스크립트의 정직 단서(원본과 바이트가 같지 않다·루트가 배열이다)가
# 그대로 승계된다.
seed_real() {
  local gen="$SELF_DIR/../../seed/gen_pose_template.py"
  local sql="$WORK/_pose_template.sql" t0 t1 py
  [ -f "$gen" ] || { echo "🔴 템플릿 생성기가 없다: $gen" >&2; return 1; }

  # 🔴 python 이 없으면 **다른 페이로드로 대신 재지 않는다.** 여기서 멈추는 편이
  #    「real 무대를 쟀다」는 얼굴의 다른 무대보다 낫다(#202 가 생긴 이유가 그것이다).
  py=$(command -v python3 || command -v python) \
    || { echo "🔴 python3 가 없다 — real 무대를 세울 수 없다. **대체 페이로드로 재지 않는다**" >&2; return 1; }

  assert_no_writer
  local rows=$(( REAL_SESSIONS * REAL_TEMPLATE_ROWS ))
  printf "무대 시딩 — STAGE=real · %s 세션 × %s행 = %'d 행 (실 JSON ≈2KB/행)\n" \
    "$REAL_SESSIONS" "$REAL_TEMPLATE_ROWS" "$rows"
  [ "$REAL_SESSIONS" -ge 1 ] && [ "$REAL_SESSIONS" -le 10000 ] \
    || { echo "🔴 REAL_SESSIONS 는 1~10000 이어야 한다 (받은 값 '$REAL_SESSIONS')" >&2; return 1; }

  t0=$(date +%s)
  "$py" "$gen" --rows "$REAL_TEMPLATE_ROWS" --out "$sql" >/dev/null \
    || { echo "🔴 템플릿 생성 실패" >&2; return 1; }
  docker exec -i "$CONTAINER" mysql -uroot -p"$PW" "$DB_NAME" < "$sql" \
    || { echo "🔴 템플릿 적재 실패" >&2; return 1; }

  # 무대 자체는 더미와 **같은 DDL**이다(위 seed_scale 과 한 글자도 다르면 안 된다).
  DB -e "
    DROP TABLE IF EXISTS pose_data_scale;
    CREATE TABLE pose_data_scale (
      id bigint NOT NULL AUTO_INCREMENT,
      session_id bigint NOT NULL,
      timestamp_sec double NOT NULL,
      joint_coordinates text COLLATE utf8mb4_unicode_ci NOT NULL,
      sync_rate double DEFAULT NULL,
      is_correct tinyint(1) DEFAULT 1,
      feedback_message varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
      created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (id, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    DROP TABLE IF EXISTS _seq_real;
    CREATE TABLE _seq_real (n INT PRIMARY KEY);
    INSERT INTO _seq_real
    WITH d AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
               UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9)
    SELECT a.n*1000 + b.n*100 + c.n*10 + e.n FROM d a, d b, d c, d e;
  " || { echo "🔴 real 무대 준비 실패" >&2; return 1; }

  # 시딩 한정 완화 — 끝에서 되돌린다(seed_scale 과 같은 규약. 안 되돌리면 다음 판이 다른
  # 내구성으로 측정된다).
  #
  # 🔴 **되돌리기를 «성공 경로» 에만 두면 안 된다.** 아래 시딩·인덱스 빌드가 실패하면
  #    `flush=2` 인 채로 함수가 빠져나가고, 그 뒤 판들이 **완화된 내구성에서 측정된다.**
  #    표에는 아무 흔적도 안 남는다. 그래서 나가는 경로마다 `restore_flush` 를 부른다.
  restore_flush() { DB -e "SET GLOBAL innodb_flush_log_at_trx_commit=1;" >/dev/null 2>&1; }
  DB -e "SET GLOBAL innodb_flush_log_at_trx_commit=2;" || return 1

  local s e chunk=100
  for (( s=0; s<REAL_SESSIONS; s+=chunk )); do
    e=$(( s + chunk )); [ $e -gt "$REAL_SESSIONS" ] && e=$REAL_SESSIONS
    DB -e "INSERT INTO pose_data_scale
             (session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message, created_at)
           SELECT s.n+1, t.timestamp_sec, CAST(t.joint_coordinates AS CHAR), t.sync_rate, 1,
                  NULLIF(t.feedback_message,''),
                  TIMESTAMP('2026-01-01 06:00:00') + INTERVAL s.n MINUTE
                    + INTERVAL FLOOR(t.timestamp_sec) SECOND
           FROM _seq_real s CROSS JOIN _pose_template t
           WHERE s.n >= $s AND s.n < $e;" \
      || { restore_flush; echo "🔴 real 시딩 실패 (세션 $s~$e)" >&2; return 1; }
  done

  DB -e "CREATE INDEX idx_session_timestamp ON pose_data_scale (session_id, timestamp_sec);
         SET GLOBAL innodb_flush_log_at_trx_commit=1;
         ANALYZE TABLE pose_data_scale;
         DROP TABLE IF EXISTS _seq_real;
         DROP TABLE IF EXISTS _pose_template;" \
    || { restore_flush; echo "🔴 인덱스 빌드/정리 실패" >&2; return 1; }
  rm -f "$sql"
  t1=$(date +%s)

  # 🔴 «시딩했다» 와 «시딩됐다» 는 다르다 — 그리고 이 무대는 **행 크기가 조건**이라
  #    행 수만 세면 안 된다. 페이로드가 실제로 크게 들어갔는지까지 확인한다.
  local n avg flush mb
  n=$(DBQ "SELECT COUNT(*) FROM pose_data_scale;")
  [ "$n" = "$rows" ] || { echo "🔴 행 수가 $rows 이 아니다 — 실제 '$n'" >&2; return 1; }
  avg=$(DBQ "SELECT ROUND(AVG(LENGTH(joint_coordinates))) FROM pose_data_scale;")
  [ "${avg:-0}" -ge 1500 ] \
    || { echo "🔴 평균 페이로드가 ${avg}B 다 — real 무대가 아니다(더미가 들어갔다)" >&2; return 1; }
  flush=$(DBQ "SELECT @@innodb_flush_log_at_trx_commit;")
  [ "$flush" = "1" ] || { echo "🔴 내구성 복구가 안 됐다 — flush='$flush'" >&2; return 1; }
  mb=$(DBQ "SELECT ROUND((data_length+index_length)/1024/1024) FROM information_schema.tables
            WHERE table_schema='$DB_NAME' AND table_name='pose_data_scale';")
  printf "  [시딩] 완료 %ss — %'d 행 · 평균 페이로드 %sB · 테이블 %sMB · flush=1 복구 확인\n" \
    "$((t1-t0))" "$n" "$avg" "$mb"
  { echo "# real 무대 조건 ($(date -Is))"
    echo "행수            : $n ($REAL_SESSIONS 세션 × $REAL_TEMPLATE_ROWS)"
    echo "평균 페이로드   : ${avg}B (더미 무대는 2B — '{}')"
    echo "테이블 크기     : ${mb}MB"
    echo "템플릿          : loadtest/seed/gen_pose_template.py --rows $REAL_TEMPLATE_ROWS"
    echo "⚠️ writer 가 넣는 행은 페이로드가 '{}' 다 — 정지 관측용이라 무대 크기에 영향 없음"
  } > "$OUT/REAL_STAGE.txt"
}

# ── 한 판 ────────────────────────────────────────────────────────────────
run_one() {  # $1 = round, $2 = 팔(A|B|C)
  local round=$1 arm=$2; local tag="${round}_${arm}"
  local bkp restore_s rsync_ms restore_durable_s summary src_fp dst_fp verdict

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
    printf "%s\t%s\t%s\tFAIL\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\n" "$round" "$arm" "$STAGE" >> "$LOG"
    FAILED+=("$tag:백업실패"); return 1
  fi
  local backup_s artifact_mb
  read -r backup_s artifact_mb <<< "$bkp"

  # 팔 C 는 **전체 백업이 아니다.** 복구를 재지 않는다 — 재면 A·B 와 같은 표에 놓이게 되고
  # 그건 성격이 다른 것을 나란히 세우는 일이다(설계 §2).
  if [ "$arm" = "C" ]; then
    restore_s="-"; rsync_ms="-"; restore_durable_s="-"; verdict="대조군(복구 미측정)"
  else
    # 🔴 복구 «직전» 에 캐시를 비운다(#201). 방금 백업해서 따뜻해진 캐시 위에서 복구를 재면
    #    「빠른 복구」가 아니라 「캐시에서 꺼낸 시간」이 나온다. 양 팔에 똑같이 건다.
    #    비우기에 실패하면 **재지 않는다.** 「빠른 복구」로 보이는 값이 표에 남는 쪽이
    #    비어 있는 칸보다 훨씬 위험하다 — 「재봤더니 X」와 「재지 못했다」의 구분(rig 규약).
    if [ "$DROP_CACHES_BEFORE_RESTORE" = "1" ] && ! drop_caches_now "복구 직전"; then
      printf "%s\t%s\t%s\t%s\t%s\tFAIL\t-\t-\t-\t-\t-\t-\t-\t캐시비움실패\n" \
        "$round" "$arm" "$STAGE" "$backup_s" "$artifact_mb" >> "$LOG"
      FAILED+=("$tag:캐시비움실패"); fresh_restore_target; return 1
    fi

    echo "  [복구] 별도 컨테이너로"
    local rout
    case $arm in
      A) rout=$(restore_arm_a "$tag") ;;
      B) rout=$(restore_arm_b "$tag") ;;
    esac
    read -r restore_s rsync_ms <<< "${rout:-}"
    if [ -z "${restore_s:-}" ]; then
      printf "%s\t%s\t%s\t%s\t%s\tFAIL\t-\t-\t-\t-\t-\t-\t-\t-\n" \
        "$round" "$arm" "$STAGE" "$backup_s" "$artifact_mb" >> "$LOG"
      FAILED+=("$tag:복구실패"); fresh_restore_target; return 1
    fi
    restore_durable_s=$(( restore_s + (${rsync_ms:-0} + 500) / 1000 ))
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
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$round" "$arm" "$STAGE" "$backup_s" "$artifact_mb" \
    "$restore_s" "$rsync_ms" "$restore_durable_s" \
    "$att" "$err" "$mx" "$p50" "$dpk" "$verdict" >> "$LOG"

  echo "  → 백업 ${backup_s}s(${artifact_mb}MB) · 복구 $([ "$restore_s" = "-" ] && echo "미측정" || echo "${restore_s}s (+sync ${rsync_ms}ms → 내구 ${restore_durable_s}s)") · 검증 $verdict"
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
  # 🔴 **«무엇을 비교했는가» 를 판정과 같은 줄에 적는다.** `DO_CHECKSUM=0`(리허설 기본)이면
  #    체크섬이 양쪽 다 `-` 라 실제 비교는 **행 수뿐**인데, 파일에는 「판정: 일치」만 남아
  #    나중에 읽는 사람이 «내용까지 같다» 로 읽는다. 같은 행 수로도 내용은 다를 수 있다.
  local basis
  if [ "$DO_CHECKSUM" = "1" ]; then basis="행수+체크섬"
  else basis="행수만 (DO_CHECKSUM=0 — 체크섬 미산출. «내용 일치» 를 뜻하지 않는다)"; fi
  { echo "# G5 — XtraBackup 복구 유효성 ($(date -Is))"
    echo "비교 기준: $basis"
    echo "원본 (행수 체크섬): $src"
    echo "복구 (행수 체크섬): $dst"
    echo "판정: $([ "$src" = "$dst" ] && echo 일치 || echo 불일치) — $basis"
  } > "$OUT/G5_xtrabackup_restore.txt"
  [ "$DO_CHECKSUM" = "1" ] || echo "  ⚠️ G5 는 행수만 비교했다(DO_CHECKSUM=0). 본 측정에서는 켤 것"
  [ "$src" = "$dst" ] || { echo "  🔴 G5 불일치 — **팔 B 를 빼고 돌린다**"; return 1; }
  echo "  ✅ G5 통과 — 팔 B 가 유효하다"
}

# ── 실행 ─────────────────────────────────────────────────────────────────

# 🔴 **러너가 이 스크립트를 중간에 끊을 수 있다** — `loadtest/aws/run_all.sh` 가
#    `timeout --kill-after` 로 감싼다. 그 순간 팔 C 의 `SELECT SLEEP(86400)` 세션이 살아
#    있으면 `pose_data_scale` 이 **최대 24시간 잠긴 채 남고**, 같은 라운드의 뒤 단계(從
#    항목 등)가 **그 잠금 위에서 측정된다.** 표에는 정상으로 보인다 — 또 「조용히 다른 것을
#    잰」 판이 되는 경로다. 복구 컨테이너·볼륨도 같이 정리한다(둘 다 삭제는 멱등이다).
cleanup_all() {
  local kid
  kid=$(DBQ "SELECT id FROM information_schema.processlist
             WHERE info LIKE 'SELECT SLEEP(86400)%' LIMIT 1;" 2>/dev/null)
  [ -n "${kid:-}" ] && DBQ "KILL $kid;" >/dev/null 2>&1
  fresh_restore_target
}
trap cleanup_all EXIT INT TERM

require_container
install_writer
[ -f "$LOG" ] || printf "round\tarm\tstage\tbackup_s\tartifact_mb\trestore_s\tsync_ms\trestore_durable_s\tattempts\terrors\tmax_stall_ms\tp50_ms\tdisk_peak_mb\tverify\n" > "$LOG"

if [ "$STAGE" = "real" ]; then
  seed_real || { echo "🔴 real 무대를 못 세웠다 — 이 스윕을 중단한다"; exit 1; }
else
  echo "무대 시딩 — STAGE=dummy · SESSIONS=$SESSIONS"
  seed_scale
fi

# 판 순서 — 팔이 2개(A·B)라 라틴 방격 대신 위치 상쇄 배열(무중단 DDL 과 같은 규약).
# 팔 C 는 대조군이라 버림 없이 1판.
#
# 🔴 real 무대는 **대조**다(설계 §9-1, #202). 팔당 버림판 1 + 본판 1 만 돌린다 —
#    본 측정과 같은 판 수를 돌리면 「같은 급의 수치」로 오해된다. 팔 C 도 안 태운다
#    (Q3「멎는가」는 무대 크기가 아니라 잠금 메커니즘이고, 더미 무대에서 이미 답했다).
if [ "$STAGE" = "real" ]; then
  SEQ=(${SWEEP_SEQ:-discard:A discard:B r1:A r2:B})
else
  SEQ=(${SWEEP_SEQ:-discard:A discard:B r1:A r2:B r3:B r4:A r5:A r6:B c1:C})
fi

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
  # 🔴 **판이 끝나면 그 판의 산출물을 지운다.** 크기는 `artifact_mb` 로 표에 이미 남았고,
  #    원본을 들고 있을 이유가 없다. 태그가 판마다 다르므로 각 팔의 `rm -rf` 는 «자기 판»
  #    만 지운다 — 1억 행 기본 목록(A 4판·B 4판)이면 덤프 ~5.7GB × 4 + 데이터디렉터리
  #    사본 ~10.4GB × 4 가 동시에 남아 **뒤쪽 판이 «백업실패» 로 찍힌다.** 그건 측정값이
  #    아니라 환경 결함인데 표에서는 구분되지 않는다. 진단이 필요하면 `KEEP_ARTIFACTS=1`.
  if [ "${KEEP_ARTIFACTS:-0}" != "1" ]; then
    _tag="${item%%:*}_${item##*:}"
    rm -rf "$WORK/${_tag}.sql" "$WORK/xb_$_tag" "$WORK/snap_$_tag"
  fi
  [ $i -lt "$TOTAL" ] && reset_between_rounds
done

echo; echo "════ 요약 ════"; cat "$LOG"
if [ "${#FAILED[@]}" -gt 0 ]; then
  echo; echo "🔴 실패 ${#FAILED[@]}건: ${FAILED[*]}"
fi
