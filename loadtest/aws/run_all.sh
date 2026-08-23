#!/bin/bash
# EC2 무인 측정 러너 — 단계를 순서대로 돌리고 결과를 S3 로 계속 올린다.
#
# 설계 원칙 세 개. 전부 «밤을 통째로 잃지 않는다» 로 수렴한다:
#
#   ① set -e 를 쓰지 않는다. 한 단계가 죽어도 다음 단계는 돈다. 실패는 숫자가 아니라
#      단계 표의 FAIL 로 남는다 («재봤더니 0» 과 «재지 못했다» 는 다르다 — _rig.sh 의 규약)
#   ② 주기적으로 S3 에 올린다. 5판째 죽어도 4판은 건진다. 최종 업로드만 믿지 않는다
#   ③ 각 단계에 상한 시간을 건다. 무한정 기다리다 아침을 맞는 것이 이 rig 의 실패 이력이다
#
# 사용:
#   S3_BASE=s3://버킷/프리픽스 nohup bash loadtest/aws/run_all.sh > /root/run_all.log 2>&1 &
#
# ⚠️ nohup 없이 & 만 붙이면 SSH 가 끊길 때 같이 죽는다.

set -uo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
RIG=$ROOT/loadtest/results/online-ddl-2026-08-09
BACKUP_RIG=$ROOT/loadtest/results/backup-restore-2026-08-13
CORES_RIG=$ROOT/loadtest/results/coresidency-2026-08-15
REPL_RIG=$ROOT/loadtest/results/replication-2026-08-17

# ── 설정 ─────────────────────────────────────────────────────────────────
S3_BASE=${S3_BASE:?S3_BASE 가 필요하다 — 예: s3://my-bucket/shadowfit}
RUN_ID=${RUN_ID:-ec2-$(date +%Y%m%d-%H%M%S)}
# 🔴 기본값은 여기서 안 정한다 — `PHASES` 를 읽어야 정할 수 있고, 그건 아래에 있다(#358).
OUTDIR=${OUTDIR:-}
S3_DEST="${S3_BASE%/}/$RUN_ID"

# 라운드마다 갈아끼운다. 기본값은 무중단 DDL(P1) 라운드이고 **그건 2026-08-12 에 끝났다.**
#
#   P3 백업/복구 라운드:
#     PHASES="preflight backup_rehearsal backup ridealong collect"
#
#   P3-b 재측정 라운드 (#201 내구성 · #202 real 대조):
#     PHASES="preflight backup_rehearsal backup backup_real ridealong collect"
#     🔴 `backup_real` 은 반드시 `backup` **뒤**다 — 무대(`pose_data_scale`)를 real 로 다시
#        세우므로 순서가 뒤집히면 1억 행 본 측정이 다른 무대 위에서 돈다.
#
#   HTTP 쓰기 p99 라운드 (從, slo-baseline §4-2 의 빈 절반):
#     TARGET_HOST=10.0.0.5 PHASES="httpwrite collect"
#     🔴 **부하기 박스에서** 돌린다(ROLE=p6-loader). 대상과 같은 박스면 이 라운드의
#        존재 이유(판정선 대면)가 사라져서 단계가 스스로 멈춘다.
#
#   P6 동거 용량 라운드 (主-P6):
#     TARGET_HOST=10.0.0.5 AI_PUBLIC_TOKEN=... \
#     PHASES="coresidency_preflight coresidency_rehearsal coresidency collect"
#
#     🔴 **#223 이 닫히기 전에는 이 라운드가 안 돈다.**
#        - #222 (팔 A 가 성립 안 함) 은 **(a)안으로 닫았다**(2026-08-16) — 팔 A 를
#          «B 와 같은 구성 + ghz 부하 없음» 으로 재정의. 실행 검증은 아직 없다
#        - 그 대가로 A 와 B 는 **구성이 같아졌다.** 갈리는 것은 ghz 부하 하나다.
#        - #223 은 **배선됐다**(2026-08-16, 요청/초 고정). 그래서 아래 GHZ_* 가 필수다:
#            GHZ_RPS=<정한 값> GHZ_DATA=<gen_batch_multi.py 산출물> GHZ_TOKEN=<INTERNAL_API_TOKEN>
#          비어 있으면 preflight 가 막는다. ghz 없이 도는 라운드는 CORES_ARMS="A" 뿐이고
#          (B·C·D 는 전부 從 부하를 쓴다), 그건 «유휴 동거 기준선» 하나만 얻는 라운드다
#
#     🔴 **이 라운드만 러너의 자리가 다르다.** 다른 단계는 «러너 = 측정 대상 박스» 인데
#        (`docker exec $CONTAINER` 로 로컬 MySQL 을 친다), P6 의 rig 은
#        «부하기에서 돌며 대상 박스를 SSH 로 몬다» 를 전제한다(`coresidency_sweep.sh:16`).
#        그래서 P6 라운드에서 이 스크립트는 **부하기 박스**에서 돈다.
#        - 대상 박스의 조건(타입·vCPU·RAM·캡)은 `collect` 가 `TARGET_SSH` 로 따로 걷는다.
#          안 그러면 매니페스트가 **부하기의 스펙**을 측정 조건으로 박제한다
#        - 부하기를 따로 두지 않고 같은 박스에서 돌리려면(설계 §10 미결정)
#          `TARGET_SSH="bash -c"` 로 두면 코드 변경 없이 선다. 단 그때 재는 것은
#          «부하기까지 동거하는 박스» 라 조건이 다르다 — 결과에 그대로 적을 것
#
#   🔴 `coresidency` 를 `ddl`·`backup` 과 같은 PHASES 에 넣지 말 것. 러너의 자리 자체가
#      다르고(위), 저 둘은 디스크가 지배해서 섞이면 셋 다 오염된다.
#
# 🔴 `ddl` 과 `backup` 을 **같이 넣지 말 것.** 둘 다 디스크가 지배해서 한 라운드에 섞으면
#    서로 오염된다(AWS-RIDE-ALONG §7 이 P1↔P2 에 건 경고와 같다). 라운드를 나눈다.
PHASES=${PHASES:-"preflight rehearsal ddl ridealong collect"}

# ── 결과 디렉터리 이름 — PHASES 에서 라운드를 읽는다 (#358) ───────────────
# 예전 기본값은 무조건 `online-ddl-` 이었다. 라운드가 넷이 된 지금 그 값은 넷 중 하나만
# 맞는데, **틀려도 실패하지 않는다** — 복제 라운드가 «online-ddl» 이름으로 저장소에 박히고,
# 산출물 자체는 멀쩡해서 결과를 커밋할 때가 되어서야 눈에 띈다. 무인 라운드면 그때는
# 이미 인스턴스를 내린 뒤다.
#
# 🔴 `OUTDIR` 을 명시하면 이 블록은 통째로 안 돈다. 추론은 **안 줬을 때만** 한다 —
#    그래서 아래 «섞였다» 판정도 명시한 사람은 안 건드린다.
# ⚠️ `S3_DEST` 는 `RUN_ID` 에서 따로 나온다(위). 즉 **여기서 고쳐도 S3 키는 안 바뀐다** —
#    로컬은 `<라운드>-aws-<날짜>`, S3 는 `ec2-<타임스탬프>` 로 **이름이 아예 다르다.**
#    둘을 잇는 것은 `MANIFEST.txt` 다 — 머리에 `RUN_ID` 를, 본문에 `S3 결과` 경로를 적는다.
#    유일성 요구가 서로 다르기 때문이다: 로컬은 «사람이 읽고 커밋하는 이름», S3 는 «겹치면 안 되는 키».
#    S3 는 예전부터 라운드 이름을 안 달았고, 이 변경의 범위 밖이다.
if [ -z "$OUTDIR" ]; then
  _rounds=""
  for _p in $PHASES; do
    case "$_p" in
      ddl|rehearsal)                       _r=online-ddl ;;
      backup|backup_rehearsal|backup_real) _r=backup-restore ;;
      repl|repl_gate|repl_preflight)       _r=replication ;;
      coresidency*)                        _r=coresidency ;;
      r276)                                _r=r276-newkeys ;;
      r276app)                             _r=r276-app-retry ;;
      httpwrite)                           _r=http-write-p99 ;;
      ukbp)                                _r=uk-bufferpool ;;
      framepath*)                          _r=frame-path ;;
      *)                                   continue ;;   # preflight·ridealong·collect 는 라운드를 안 정한다
    esac
    case " $_rounds " in *" $_r "*) ;; *) _rounds="${_rounds:+$_rounds }$_r" ;; esac
  done

  case "$_rounds" in
    "")
      # 측정 단계가 없다(preflight·collect 만 같은 경우). 라운드 이름을 붙일 근거가 없다.
      echo "⚠️  PHASES 에 측정 단계가 없다 — 결과 디렉터리를 'run-' 으로 만든다: $PHASES" >&2
      OUTDIR=$ROOT/loadtest/results/run-$RUN_ID ;;
    *" "*)
      # 🔴 여러 라운드가 섞였다. 이 조합은 이 파일 위쪽 주석이 이미 금지한 것이고
      #    (ddl↔backup 은 디스크가 지배해서, coresidency 는 러너의 자리가 달라서),
      #    이름을 하나 고르면 나머지가 틀린 이름으로 박힌다. 고르지 않고 멈춘다.
      echo "🔴 PHASES 에 라운드가 둘 이상 섞였다 ($_rounds) — 라운드를 나누거나 OUTDIR 을 직접 줄 것." >&2
      echo "   PHASES=$PHASES" >&2
      exit 1 ;;
    *)
      # 이름은 **커밋되는 물건**이라 저장소 관례를 그대로 따른다 — `<라운드>-aws-<날짜>`
      # (`backup-restore-aws-2026-08-13` · `coresidency-aws-2026-08-16` …).
      # RUN_ID(`ec2-<타임스탬프>`)를 쓰면 라운드마다 손으로 고쳐 커밋하게 된다.
      _today=$(date +%F)
      OUTDIR=$ROOT/loadtest/results/$_rounds-aws-$_today
      # 🔴 같은 날 두 번째 라운드면 **같은 디렉터리에 섞지 않는다.** 두 라운드의 산출물이
      #    한 폴더에 겹치면 나중에 못 가른다. 관례가 이미 «-b-» 를 쓴다
      #    (`backup-restore-aws-b-2026-08-13` · `coresidency-aws-b-2026-08-16`).
      if [ -e "$OUTDIR" ]; then
        OUTDIR=""
        for _sfx in b c d e f g h i j; do
          _try=$ROOT/loadtest/results/$_rounds-aws-$_sfx-$_today
          [ -e "$_try" ] && continue
          OUTDIR=$_try
          echo "⚠️  같은 날 이미 라운드가 있다 — '$_sfx' 판으로 만든다: $OUTDIR" >&2
          break
        done
        [ -n "$OUTDIR" ] || { echo "🔴 같은 날 라운드가 열 판을 넘었다 — OUTDIR 을 직접 줄 것." >&2; exit 1; }
      fi
      ;;
  esac
fi

SYNC_SEC=${SYNC_SEC:-300}
AUTO_SHUTDOWN=${AUTO_SHUTDOWN:-0}

PW=${PW:-1234}
DB_NAME=${DB_NAME:-shadowfit}
CONTAINER=${CONTAINER:-shadowfit-mysql}

REHEARSAL_SESSIONS=${REHEARSAL_SESSIONS:-134}

# 🔴 기본 5,400s(90분)는 팔 B 로컬 실측 2,360s 대비 여유가 2.3배뿐이다. EBS 가 로컬 NVMe
#    보다 느려 팔 B 가 늘어나면 **writer 가 DDL 도중 먼저 죽어 max_stall·p50 이 통째로
#    구멍난다.** 측정 자체는 계속 도는데 지표만 못 쓰게 되는, 제일 나쁜 실패 모양이다.
export WRITER_MAX_SEC=${WRITER_MAX_SEC:-14400}   # 4시간

TIMEOUT_REHEARSAL=${TIMEOUT_REHEARSAL:-3600}     # 1시간 (예상 ~15분)
TIMEOUT_DDL=${TIMEOUT_DDL:-43200}                # 12시간 (로컬 추정 5.9시간 × 2)

# 백업/복구 — 설계 §9 는 «측정 2h» 로 잡았지만 그 값은 **1,000만 행 기준 추정**이었고
# 무대가 1억 행으로 확정됐다. 어느 팔이 얼마나 걸리는지가 바로 Q1·Q2 라 **미리 모른다.**
# 그래서 상한을 넉넉히 준다 — 걸려서 끊기는 것보다 낫다.
TIMEOUT_BACKUP=${TIMEOUT_BACKUP:-43200}          # 12시간
BACKUP_SESSIONS=${BACKUP_SESSIONS:-133334}       # 1억 행 (133,334 × 750)
TIMEOUT_BACKUP_REAL=${TIMEOUT_BACKUP_REAL:-7200} # 2시간 (무대 ~1.5GB, 판 4개)
BACKUP_REAL_SESSIONS=${BACKUP_REAL_SESSIONS:-1000}  # 1,000 × 750행 ≈ 75만 행 ≈ 1.5GB
TIMEOUT_RIDEALONG=${TIMEOUT_RIDEALONG:-900}

# ── #276 데드락 라운드 ──────────────────────────────────────────────────
#
# 로컬(2물리코어)에서 팔 4 × 4판이 십수 분이었다. 팔 2 × 4판이면 그보다 짧다 —
# 아래 1시간은 «매달리면 끊는다» 용 상한이지 예상 시간이 아니다.
#
# 🔴 이 라운드가 AWS 로 올라온 이유는 «더 큰 박스» 가 아니라 **격리**다. 로컬은 다른 작업이
#    같은 MySQL 을 쓰고 있어 팔 비교가 그 부하에 오염된다(2026-08-23).
TIMEOUT_R276=${TIMEOUT_R276:-3600}
R276_ARMS=${R276_ARMS:-"same_partition new_keys_only"}
R276_ORDER=${R276_ORDER:-latin}
R276_ROUNDS=${R276_ROUNDS:-4}
# 워커 수는 **박스의 코어 수에 걸린다** — 로컬 판이 2→3 에서 13배 뛰었고 그 자리가 코어 수를
# 넘는 첫 칸과 겹쳤다(r276-worker-sweep-fine, «해석이지 실측이 아니다»). 4 vCPU 박스에서
# 8 은 그 위다. 값을 바꾸면 팔 비교가 아니라 다른 라운드가 되므로 기본값을 박아둔다.
R276_WORKERS=${R276_WORKERS:-8}

# ── #276 ② 앱 경로 재시도 스윕 ──────────────────────────────────────────
#
# 배포된 상한이 동시성이 올라가도 버티는지를 **실제 코드 경로**로 잰다.
# 이 단계는 ROLE=p6-target 박스를 전제한다 — Spring 이 떠 있고 세션 901~1000 이 시드돼 있어야
# 한다. ghz 는 p6-loader 역할에만 깔리므로 여기서 없으면 직접 받는다(부트스트랩을 두 번
# 돌리면 .env 가 다시 쓰이면서 토큰이 어긋난다 — 그 길로 가지 않는다).
TIMEOUT_R276APP=${TIMEOUT_R276APP:-5400}
R276APP_LEVELS=${R276APP_LEVELS:-"8 16 32"}
R276APP_REQS=${R276APP_REQS:-500}
R276APP_BLOCKS=${R276APP_BLOCKS:-4}
R276APP_SESSIONS=${R276APP_SESSIONS:-901-1000}
# 상한 팔 (#276 ②). 비어 있으면 상한을 안 건드린다 — 1·2차 라운드와 같은 동작이다.
R276APP_RETRY_ARMS=${R276APP_RETRY_ARMS:-""}
# 백오프 팔 (#276 ③ 후속). RETRY_ARMS 와 동시에 주면 rig 이 막는다.
R276APP_BACKOFF_ARMS=${R276APP_BACKOFF_ARMS:-""}

# ── 유니크 키 대가 @ 버퍼풀 초과 (從 R8 후속) ───────────────────────────
#
# R8 은 `Innodb_buffer_pool_reads` 가 양쪽 다 0 인 판이었다 — 인덱스가 메모리에 다 들어가
# **디스크를 안 쳤다.** 그래서 「change buffer 를 못 쓰는 대가」를 사실상 안 쟀다.
# 이 단계는 버퍼풀을 줄여 그 체제를 만들고, 팔 셋(없음/비유니크/유니크)으로 잰다.
#
# 🔴 시딩(수백만 행)과 팔마다의 ADD INDEX 가 붙어 **다른 단계보다 오래 걸린다.**
TIMEOUT_UKBP=${TIMEOUT_UKBP:-10800}
UKBP_POOL_MB=${UKBP_POOL_MB:-128}
UKBP_SEED_ROWS=${UKBP_SEED_ROWS:-3000000}
UKBP_INSERT_ROWS=${UKBP_INSERT_ROWS:-100000}
UKBP_BLOCKS=${UKBP_BLOCKS:-4}

# ── HTTP 쓰기 p99 (從) ───────────────────────────────────────────────────
#
# slo-baseline §4-2 의 「세션 쓰기 p99 ≤ 300ms」에 대응하는 실측이 0 이다. 읽기 절반은
# 2026-08-23 로컬 판이 채웠지만, 그 판은 스스로 «부하기까지 동거라 판정선에 대면 안 된다»
# 고 적었다. 이 단계가 그 자리를 EC2 에서 채운다 — **부하기는 이 박스, 대상은 TARGET_HOST**.
#
# 🔴 팔은 VU 가 아니라 **가정 피크 배수**다(rig 머리 참고). ×1 = 0.075 세션시작/초.
TIMEOUT_HTTPWRITE=${TIMEOUT_HTTPWRITE:-5400}
HTTPW_MULTS=${HTTPW_MULTS:-"60 180 360"}   # 표본 수로 고른 값 — rig 머리 참고
HTTPW_DUR=${HTTPW_DUR:-120s}
HTTPW_BLOCKS=${HTTPW_BLOCKS:-4}
HTTPW_ACCOUNTS=${HTTPW_ACCOUNTS:-64}
HTTPW_EXERCISE_ID=${HTTPW_EXERCISE_ID:-1}
HTTPW_PORT=${HTTPW_PORT:-8080}

# ── 동거 용량 (主 P6) ────────────────────────────────────────────────────
#
# 러너는 **부하기**에서 돈다(위 헤더). 아래 값은 전부 rig 의 기본값과 같게 두되, 러너에서
# 조건을 한 곳에 모아 매니페스트에 남기려고 다시 적는다.
TARGET_HOST=${TARGET_HOST:-}
AI_CONTAINER=${AI_CONTAINER:-shadowfit-ai}
TARGET_REPO_DIR=${TARGET_REPO_DIR:-/root/init}
# 대상 호스트가 없으면 **빈 값**이다 — `root@` 만 남은 명령이 도는 것을 막는다.
TARGET_SSH=${TARGET_SSH:-${TARGET_HOST:+ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 root@$TARGET_HOST}}
AI_PUBLIC_TOKEN=${AI_PUBLIC_TOKEN:-}

CORES_ARMS=${CORES_ARMS:-"A B C"}            # D(관측 스택)를 넣으면 판이 33% 는다
CORES_LEVELS=${CORES_LEVELS:-"20 40 60 80 90 100 120 160"}  # 2026-08-17 확정 — 설계 §5-3 ⑥.
                                             # 90·100 은 천장(80~120)을 «점» 으로 짚으려고 넣었다.
                                             # 🔴 `coresidency_sweep.sh` 의 LEVELS 와 **같은 값**이어야 한다
CORES_DUR=${CORES_DUR:-90}
CORES_REPEATS=${CORES_REPEATS:-3}
# 레벨 순서 치환(#252)과 앵커 판(시간 추세 기준점). 유도·근거는 `coresidency_sweep.sh` 주석.
CORES_LEVEL_SHIFT=${CORES_LEVEL_SHIFT:-2}    # 0 이면 오름차순 고정 — 그러면 #252 가 되살아난다
CORES_ANCHOR=${CORES_ANCHOR:-1}              # 라운드마다 + 끝에 1판. REPEATS 3 이면 4판 ≈ 11분
CORES_ANCHOR_ARM=${CORES_ANCHOR_ARM:-B}
CORES_ANCHOR_LEVEL=${CORES_ANCHOR_LEVEL:-80} # 포화 «직전» — 천장에 붙은 레벨은 기준선 구실을 못 한다
CORES_REH_ANCHOR_LEVEL=${CORES_REH_ANCHOR_LEVEL:-10}  # 리허설 격자(5·10)에 맞춘 값
# 자기 프로브(설계 §4-2) — 대상 박스에서 1세션을 돌려 **서버 쪽 시계**를 따로 잰다.
# 이게 없으면 「천장이 서버인가 부하기인가」가 CPU 지표만으로는 안 갈린다(2라운드가 실증).
CORES_PROBE=${CORES_PROBE:-1}
CORES_PROBE_PREFIX=${CORES_PROBE_PREFIX:-probe}   # 부하기 계정(cores*)과 갈라야 한다
# §T 부하기 코어 팔 — 「부하기 구성 탓」과 「대상 박스 물리 호스트 개체차 탓」을 가른다.
# 같은 세션·같은 호스트에서 부하기 코어 수만 흔든다(taskset). 상세는 sweep 의 §T 주석.
CORES_TASKSET=${CORES_TASKSET:-1}
CORES_TASKSET_CPUS=${CORES_TASKSET_CPUS:-2}       # 1라운드 무대(c7i.large)와 같은 코어 수
CORES_TASKSET_ARM=${CORES_TASKSET_ARM:-B}
CORES_TASKSET_LEVELS=${CORES_TASKSET_LEVELS:-"120 160"}
CORES_TASKSET_REPS=${CORES_TASKSET_REPS:-3}
CORES_REH_TASKSET_LEVELS=${CORES_REH_TASKSET_LEVELS:-"10"}  # 리허설 격자에 맞춘 값
CORES_REH_LEVELS=${CORES_REH_LEVELS:-"5 10"} # 축소 리허설 — README 「무인 실행 전 필수」
CORES_REH_DUR=${CORES_REH_DUR:-20}

# 從 부하(#223) — 팔 B·C·D 를 «옆이 일하는» 상태로 만드는 ghz. **요청/초 고정**이다.
# 🔴 `GHZ_RPS` 에 기본값을 두지 않는다. 정한 값이므로 근거가 조건 칸에 같이 가야 한다.
GHZ_RPS=${GHZ_RPS:-}
GHZ_DATA=${GHZ_DATA:-}                       # gen_batch_multi.py 산출물 (부하기 로컬)
GHZ_TOKEN=${GHZ_TOKEN:-}                     # INTERNAL_API_TOKEN
GHZ_BIN=${GHZ_BIN:-/usr/local/bin/ghz}       # bootstrap.sh:179 의 설치 경로 (#249). 옛 기본값은
                                             # go install 시절의 /home/ec2-user/go/bin/ghz 였고,
                                             # 두 리허설 모두 이 값을 손으로 넘겨 우회하고 있었다
GHZ_CONC=${GHZ_CONC:-50}

# 소요 환산(rig 파라미터 기준): 팔당 버림1+본판12 = 13판 × (DUR 90s + AI 재기동 ~18s +
# 세션 개설·종료) ≈ 30분. 3팔이면 1.5h 안팎, 팔 D 까지면 2h 안팎이다.
# 상한은 그 2~3배로 준다 — 걸려서 끊기는 것보다 낫다(설계 원칙 ③).
TIMEOUT_CORES=${TIMEOUT_CORES:-21600}                    # 6시간
TIMEOUT_CORES_REHEARSAL=${TIMEOUT_CORES_REHEARSAL:-1800} # 30분 (예상 몇 분)

# ── 프레임 경로 계측 (從 R10-a) ──────────────────────────────────────────
#
# 🔴 이 단계는 다른 단계와 **구조가 다르다.** coresidency 는 부하기에서 돌며 대상 박스를
#    SSH 로 모는데, R10-a 는 **1대 동거**라 러너가 곧 그 박스다(무대 결정:
#    docs/decisions/r10-loadgen-topology.md §7). 그래서 여기엔 TARGET_SSH 가 없고,
#    서버는 **rig 이 팔마다 직접 띄우고 내린다**(재기동이 곧 팔 전환이라 그 구조가 필요했다).
#
# 🔴 `FP_PLAN` 에 기본값을 두지 않는다. 격자의 정본은 설계 §13 이고, 여기에 예시를 박아두면
#    그 예시가 조용히 정본이 된다 — 2026-08-23 에 실제로 격자가 두 벌이 된 적이 있다.
#    반면 세션·판길이·풀은 «재현 대상이 있는 값» 이라 기본을 둔다(§13-2: 160·90·201).
FP_PLAN=${FP_PLAN:-}
FP_SESSIONS=${FP_SESSIONS:-160}
FP_FPS=${FP_FPS:-3}
FP_DUR=${FP_DUR:-90}
FP_POOL=${FP_POOL:-201}
FP_WARMUP=${FP_WARMUP:-5}
FP_DISCARD=${FP_DISCARD:-1}
FP_TAG=${FP_TAG:-run1}
FP_HTTP_PORT=${FP_HTTP_PORT:-8100}
FP_GRPC_PORT=${FP_GRPC_PORT:-8685}
FP_RIG=${FP_RIG:-$ROOT/loadtest/results/frame-path-overhead-2026-08-23/run_arms.py}
FP_VENV=${FP_VENV:-$ROOT/ai-server/.venv/bin/python}

# 소요 환산(설계 §13-7): 26판 × (부하 90s + setup + 기동·정리). 판당 120초면 52분,
# 160초면 69분. 🔴 setup(160세션 여는 시간)이 미측정이라 버림판이 처음 준다.
# 상한은 그 2~3배로 준다 — 걸려서 끊기는 것보다 낫다.
TIMEOUT_FP=${TIMEOUT_FP:-10800}                          # 3시간

# ── 복제 지연 · 반동기 (主 P4) ───────────────────────────────────────────
#
# 🔴 **이 라운드도 2대다.** 다만 P6 와 자리가 반대다 — P6 는 부하기에서 돌며 대상을
#    SSH 로 몰지만, P4 의 러너는 **소스 박스**에서 돈다. 이유는 시계 하나다: 하트비트를
#    «소스 시각 vs 리플리카 시각» 으로 재면 두 인스턴스의 시계 차이가 그대로 지연으로
#    찍힌다(설계 §7). 그래서 쓰는 것도 읽는 것도 소스 박스의 시계로 묶는다.
#    리플리카는 원격 3306 과 SSH 로만 만진다.
#
# 🔴 `repl` 을 `ddl`·`backup` 과 같은 PHASES 에 넣지 말 것. 저 둘은 디스크가 지배하고,
#    이쪽은 무대(1,000만 행)를 자기 조건으로 고정한다 — 섞이면 셋 다 오염된다.
#
# 🔴 일반 `preflight` 를 쓰지 않는다. 그쪽은 percona-toolkit 이미지를 묻는데(팔 B DDL용)
#    이 라운드엔 없어도 되고, 대신 **리플리카 도달성**을 물어야 한다. 환경이 맞는데
#    실패로 찍히는 자리를 만들지 않으려고 preflight 를 따로 둔다(coresidency 와 같은 이유).
REPLICA_HOST=${REPLICA_HOST:-}
REPLICA_SSH=${REPLICA_SSH:-${REPLICA_HOST:+ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 root@$REPLICA_HOST}}
# AZ 구성은 rig 이 정하는 것이 아니라 «인스턴스를 어디 띄웠는지» 다. 라벨만 받아 조건에 박는다.
# 설계 §9-1 ② 가 「이 문서에서 제일 중요한 미결정」이라 부른 항목이라 **비워두면 경고한다.**
REPL_AZ_MODE=${REPL_AZ_MODE:-}
REPL_SESSIONS=${REPL_SESSIONS:-13334}          # 1,000만 행 (설계 §9-1 ①)
REPL_REHEARSAL_SESSIONS=${REPL_REHEARSAL_SESSIONS:-134}   # 경로 점검용 축소 무대
# 게이트: 시딩 + XtraBackup 사본 전송 + 따라잡기 + G1~G3. 본 측정: 10판 × (DUR + 따라잡기).
TIMEOUT_REPL_GATE=${TIMEOUT_REPL_GATE:-10800}  # 3시간
TIMEOUT_REPL=${TIMEOUT_REPL:-14400}            # 4시간

# 🔴 이름을 **여기서 한 번** 정하고 rig 에 물려준다 (#374).
#    게이트(위 repl_preflight)와 rig(`repl2_rig.sh:60`)가 각각 이름을 들고 있으면,
#    `XB_IMAGE` 를 덮어썼을 때 **게이트는 8.0 을 보고 통과시키고 rig 은 없는 이미지로 돈다.**
#    그 실패는 논리 덤프로 되돌아가는데 **게이트를 이미 지난 뒤라 라운드가 그대로 돌고**,
#    표에는 실패로 안 남는다(`replica_build.txt` 의 «초기화 경로» 열 하나뿐이다).
#    export 하면 rig 이 이 값을 그대로 받으므로 둘이 어긋날 수 없다.
#    ⚠️ `bootstrap.sh` 는 **이 스크립트보다 먼저 도는 별개 실행**이라 자기 기본값을 갖는다.
#       한쪽만 덮어쓰면 위 게이트가 «없다» 로 **큰 소리로 막는다** — 조용히 어긋나지 않는다.
XB_IMAGE=${XB_IMAGE:-percona/percona-xtrabackup:8.0}

export XB_IMAGE
export REPLICA_HOST REPLICA_SSH REPL_AZ_MODE

export PW DB_NAME CONTAINER

mkdir -p "$OUTDIR" || { echo "🔴 $OUTDIR 를 못 만든다" >&2; exit 1; }
PHASE_LOG=$OUTDIR/phases.tsv
[ -f "$PHASE_LOG" ] || printf "phase\tstatus\tseconds\tstarted_at\n" > "$PHASE_LOG"

say() { echo; echo "════════ $* ════════"; }
note() { echo "  $*"; }

RUN_T0=$(date +%s)

# ── IMDS ─────────────────────────────────────────────────────────────────
#
# 🔴 IMDSv2 가 요구되는 인스턴스(요즘 AMI 기본값)에서는 토큰 없이 부르면 401 이다.
#    토큰을 먼저 받고, 실패하면 v1 로 떨어진다. 이걸 안 하면 매니페스트의 인스턴스
#    타입·AZ 가 통째로 빈칸이 되는데 — **그게 바로 요금을 나중에 못 뽑는 이유가 된다.**
IMDS_TOKEN=""
imds_init() {
  IMDS_TOKEN=$(curl -sf --max-time 3 -X PUT "http://169.254.169.254/latest/api/token" \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" 2>/dev/null)
}
imds() {  # $1 = 경로. 못 읽으면 빈 문자열
  if [ -n "$IMDS_TOKEN" ]; then
    curl -sf --max-time 3 -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" \
      "http://169.254.169.254/latest/meta-data/$1" 2>/dev/null
  else
    curl -sf --max-time 3 "http://169.254.169.254/latest/meta-data/$1" 2>/dev/null
  fi
}
imds_init

# ── S3 ───────────────────────────────────────────────────────────────────
sync_s3() {  # 조용히. 실패해도 측정은 계속한다 — 다음 주기에 다시 시도한다
  aws s3 sync "$OUTDIR" "$S3_DEST" --only-show-errors 2>&1 | head -5
}

# 🔴 출력을 **반드시** 파일로 뺀다. 안 그러면 이 백그라운드 루프가 호출자의 stdout 을
#    물고 놓지 않아서, 본체가 다 끝나도 파이프가 안 닫힌다(`... | tail` 이 영영 안 끝난다).
#    로컬 스모크에서 실제로 걸렸다 — 측정은 다 됐는데 명령이 안 끝나는 것처럼 보인다.
start_syncer() {
  ( while :; do sleep "$SYNC_SEC"; sync_s3; done ) >> "$OUTDIR/_syncer.log" 2>&1 &
  SYNC_PID=$!
  note "S3 주기 동기화 시작 — ${SYNC_SEC}초마다 → $S3_DEST"
}
# 서브셸만 죽이면 그 안의 `sleep` 이 살아남아 물려받은 fd 를 계속 잡고 있다. 자식까지 끊는다.
stop_syncer() {
  [ -n "${SYNC_PID:-}" ] || return 0
  pkill -P "$SYNC_PID" 2>/dev/null
  kill "$SYNC_PID" 2>/dev/null
  SYNC_PID=""
}
trap 'stop_syncer' EXIT

# ── 단계 실행기 ──────────────────────────────────────────────────────────
#
# 여기가 «격리» 다. 단계가 죽어도 rc 만 표에 적고 다음으로 넘어간다.
#
# ⚠️ 상한 시간은 **여기서 걸지 않는다.** `timeout` 은 프로그램을 실행하는 명령이라
#    쉘 함수에 못 씌운다(씌운 것처럼 보이고 조용히 안 걸린다). 그래서 워치독은 각 단계
#    안에서 **실제로 오래 도는 외부 명령**(probe.sh·ddl_sweep.sh·docker exec)에 직접 건다.
run_phase() {  # $1=이름 $2...=명령
  local name=$1; shift
  local t0 t1 rc started
  started=$(date -Is)
  say "$name"
  t0=$(date +%s)
  "$@"
  rc=$?
  t1=$(date +%s)

  local status="OK"
  case $rc in
    0)   status="OK" ;;
    124) status="TIMEOUT" ;;
    *)   status="FAIL($rc)" ;;
  esac
  printf "%s\t%s\t%s\t%s\n" "$name" "$status" "$((t1-t0))" "$started" >> "$PHASE_LOG"
  note "→ $name : $status ($((t1-t0))초)"
  sync_s3 >/dev/null 2>&1
  return $rc
}

# ── 단계 정의 ────────────────────────────────────────────────────────────

# 라운드가 무엇이든 똑같이 물어야 하는 것 둘. 단계별 preflight 가 이것을 나눠 갖는다.
preflight_s3() {  # 🔴 S3 쓰기를 **제일 먼저** 확인한다. 8시간 돌고 업로드에서 막히는 것이 최악이다.
  echo "preflight $(date -Is)" > "$OUTDIR/_write_test.txt"
  if aws s3 cp "$OUTDIR/_write_test.txt" "$S3_DEST/_write_test.txt" --only-show-errors; then
    note "✅ S3 쓰기 가능 — $S3_DEST"
    return 0
  fi
  note "🔴 S3 에 못 쓴다 — 인스턴스 프로파일 권한을 볼 것. 여기서 멈춘다"
  return 1
}

# 🔴 요금 태그는 **살아 있을 때만** 붙일 수 있다. 지금 경고하면 고칠 수 있고,
#    끄고 나서 알면 이 라운드의 실제 청구액은 영영 못 가른다. 이 repo 에 지금까지
#    EC2 요금 기록이 한 줄도 없는 이유가 그것이다.
preflight_tags() {  # 경고만 한다 — 태그가 없다고 측정을 막지는 않는다
  local tags; tags=$(imds "tags/instance" | tr '\n' ' ')
  if echo "$tags" | grep -qi "Project"; then
    note "✅ 요금 태그 있음 — Cost Explorer 에서 이 측정만 뽑을 수 있다"
  elif [ -z "$tags" ]; then
    note "⚠️ 인스턴스 태그를 못 읽었다 — 태그가 없거나 «메타데이터의 태그 허용» 이 꺼져 있다."
    note "   지금 붙일 것: aws ec2 create-tags --resources \$(imds instance-id) --tags Key=Project,Value=shadowfit-measure"
  else
    note "⚠️ Project 태그가 없다 (현재: $tags) — 요금을 이 측정에 귀속시킬 수 없다"
  fi
}

phase_preflight() {
  local ok=0

  preflight_s3 || ok=1

  docker exec "$CONTAINER" mysqladmin ping -h localhost --silent >/dev/null 2>&1 \
    && note "✅ MySQL 응답" || { note "🔴 MySQL 무응답"; ok=1; }

  docker image inspect percona/percona-toolkit >/dev/null 2>&1 \
    && note "✅ percona-toolkit 이미지" || { note "🔴 percona-toolkit 이미지 없음 — 팔 B 4판이 전부 실패한다"; ok=1; }

  local free; free=$(df -BG --output=avail "$ROOT" | tail -1 | tr -dc '0-9')
  note "디스크 여유 ${free}GB (팔 B 는 사본을 만든다 + binlog 가 B판당 ~445MB 쌓인다)"
  [ "${free:-0}" -ge 20 ] || { note "🔴 20GB 미만"; ok=1; }

  note "WRITER_MAX_SEC=$WRITER_MAX_SEC (기본 5400 에서 상향됨)"

  preflight_tags
  calibrate_box

  return $ok
}

# ── 축 0: 박스 보정 (#255) ────────────────────────────────────────────────
#
# 🔴 이 라운드의 판정에 안 쓰더라도 **무조건 남긴다.**
#
# #255 는 같은 구성이 라운드를 건너 처리량 +17.7% 인데 **AI CPU 는 같았다** — 즉 같은 CPU 로
# 일을 덜 했다. `docker stats` 의 CPU% 는 «시간» 이지 «일의 양» 이 아니라서, 물리 호스트의
# 유효 클럭이 다르면 그 서명이 그대로 나온다. 그걸 가르려면 **박스가 초당 얼마나 일하는지**를
# 앱과 무관하게 재둔 값이 있어야 한다.
#
# 🔴 지금 그 값이 **어느 라운드에도 없다.** P6 1·2라운드의 보정값은 영영 못 얻는다 — 그 박스는
#    사라졌다. 그래서 처방이 «지금부터 모든 라운드가 들고 다니게» 다
#    (설계: docs/decisions/round-to-round-nonreproducibility.md §3 축 0).
#
# 비용은 라운드당 **10~20초**다. 실패해도 라운드를 막지 않는다 — 값이 없는 것과 라운드가
# 죽는 것은 다른 일이다.
calibrate_box() {
  local py=python3 script=$ROOT/loadtest/calibrate_box.py
  [ -x "$ROOT/ai-server/.venv/bin/python" ] && py=$ROOT/ai-server/.venv/bin/python
  [ -f "$script" ] || { note "⚠️ 보정 스크립트가 없다 — 이 라운드는 축 0 이 빈다 (#255)"; return 0; }
  mkdir -p "$OUTDIR"
  local out=$OUTDIR/calibration.tsv before after
  before=$( [ -f "$out" ] && wc -l < "$out" || echo 0 )
  "$py" "$script" --tsv "$out" 2>&1 | sed 's/^/  /'
  after=$( [ -f "$out" ] && wc -l < "$out" || echo 0 )

  # 🔴 «돌았다» 와 «값이 생겼다» 는 다르다. 파이프 뒤 종료코드는 sed 의 것이고, 인터프리터가
  #    아무것도 안 하고 0 으로 끝나는 경우도 있다(이 저장소의 로컬 박스에서 실제로 그랬다 —
  #    `python3` 가 «Python» 한 줄만 찍고 종료). 그러면 「기록됨」이 거짓말이 된다.
  #    **줄이 늘었는지로 판정한다.**
  if [ "$after" -gt "$before" ]; then
    note "축 0 기록됨 → $out ($((after-before))줄)"
  else
    note "⚠️ 보정이 값을 안 남겼다 ($py) — 값 없이 진행한다."
    note "   🔴 **이 라운드는 비재현이 나와도 못 가른다** (#255 축 0)"
  fi
  return 0
}

# 축소 리허설. **여기서 실패하면 본 측정으로 넘어가지 않는다** — 그게 리허설의 존재 이유다.
phase_rehearsal() {
  local out=$OUTDIR/rehearsal
  mkdir -p "$out"
  note "SESSIONS=$REHEARSAL_SESSIONS — 경로 점검용. 이 판의 수치는 측정값이 아니다"
  OUT=$out SESSIONS=$REHEARSAL_SESSIONS \
    timeout --kill-after=60 "$TIMEOUT_REHEARSAL" bash "$RIG/probe.sh"      || return 1
  OUT=$out SESSIONS=$REHEARSAL_SESSIONS \
    timeout --kill-after=60 "$TIMEOUT_REHEARSAL" bash "$RIG/ddl_sweep.sh"  || return 1
  return 0
}

phase_ddl() {
  local out=$OUTDIR/ddl
  mkdir -p "$out"
  note "정판 — SESSIONS 기본값(13334 = 1,000만 행), 8판"
  # ⚠️ 상한에 걸려 스윕이 끊기면 writer 가 살아남을 수 있다. 다음 실행의 assert_no_writer
  #    가 그걸 잡아 시딩 전에 죽인다(#183 의 재발 방지 장치) — 조용히 오염되지는 않는다.
  OUT=$out timeout --kill-after=120 "$TIMEOUT_DDL" bash "$RIG/probe.sh"     || return 1
  OUT=$out timeout --kill-after=120 "$TIMEOUT_DDL" bash "$RIG/ddl_sweep.sh" || return 1
  return 0
}

# ── 백업/복구 (主 P3) ────────────────────────────────────────────────────
#
# 🔴 **DDL 과 같은 라운드에 돌리더라도 반드시 순차다.** 둘 다 디스크가 지배해서 겹치면
#    둘 다 오염된다(AWS-RIDE-ALONG §7 이 P1↔P2 에 대해 건 것과 같은 경고).
#    `PHASES` 가 순서대로 도는 구조라 그것만 지키면 된다.
#
# 리허설을 따로 둔다 — **이 경로는 EC2 에서 한 번도 돈 적이 없다.** 08-12 가 부트스트랩에서
# 죽었듯, 안 밟아본 경로를 본 규모로 바로 돌리면 몇 시간을 버린다. 축소로 먼저 밟는다.
phase_backup_rehearsal() {
  local out=$OUTDIR/backup_rehearsal
  mkdir -p "$out"
  note "SESSIONS=$REHEARSAL_SESSIONS — 경로 점검용. **이 판의 수치는 측정값이 아니다**"
  OUT=$out SESSIONS=$REHEARSAL_SESSIONS DO_CHECKSUM=0 \
    timeout --kill-after=60 "$TIMEOUT_REHEARSAL" bash "$BACKUP_RIG/probe.sh"        || return 1
  OUT=$out SESSIONS=$REHEARSAL_SESSIONS DO_CHECKSUM=0 \
    timeout --kill-after=60 "$TIMEOUT_REHEARSAL" bash "$BACKUP_RIG/backup_sweep.sh" || return 1

  # 🔴 real 무대도 **여기서 한 번 밟는다**(#202). 안 밟으면 그 경로의 첫 실행이 본 판이 되고,
  #    거기서 죽으면 무인 라운드에서 몇 시간을 버린다 — 08-12 가 정확히 그 사고였다.
  #    20세션 × 750행 = 15,000행이라 몇십 초면 끝난다.
  if [ "${REHEARSAL_SKIP_REAL:-0}" = "1" ]; then
    note "real 리허설 건너뜀 (REHEARSAL_SKIP_REAL=1)"
  else
    note "real 무대 경로 점검 — 20세션 × 750행. **이 판의 수치도 측정값이 아니다**"
    OUT=$out/real STAGE=real REAL_SESSIONS=20 DO_CHECKSUM=0 \
      timeout --kill-after=60 "$TIMEOUT_REHEARSAL" bash "$BACKUP_RIG/backup_sweep.sh" || return 1
  fi
  return 0
}

phase_backup() {
  local out=$OUTDIR/backup
  mkdir -p "$out"
  note "정판 — SESSIONS=$BACKUP_SESSIONS (1억 행), 팔 A·B 각 버림1+본판3 + 팔 C 1판"
  # probe.sh 가 G1~G4 를, backup_sweep.sh 가 preflight 로 G5 를 본다.
  # G5 가 실패하면 스윕이 **팔 B 만 빼고** 계속한다 — 팔 A·C 까지 버릴 이유는 없다.
  OUT=$out SESSIONS=$BACKUP_SESSIONS \
    timeout --kill-after=120 "$TIMEOUT_BACKUP" bash "$BACKUP_RIG/probe.sh"        || return 1
  OUT=$out SESSIONS=$BACKUP_SESSIONS \
    timeout --kill-after=120 "$TIMEOUT_BACKUP" bash "$BACKUP_RIG/backup_sweep.sh" || return 1
  return 0
}

# real-JSON 축소 대조 (#202) — 설계 §9-1 「확정된 것」의 후반부.
#
# 🔴 **본 측정과 같은 표에 올리는 값이 아니다.** 더미 1억 행 ↔ real 75만 행은 규모가 다르다.
#    여기서 보는 것은 「행 «크기» 가 팔 A(논리)를 얼마나 더 불리하게 만드는가」 하나뿐이다.
# 🔴 **`backup` 다음에 둔다.** 이 단계가 `pose_data_scale` 을 real 페이로드로 다시 세우므로
#    순서가 뒤집히면 본 측정이 real 무대 위에서 돌아 조건이 통째로 바뀐다.
phase_backup_real() {
  local out=$OUTDIR/backup_real
  mkdir -p "$out"
  note "real 대조 — ${BACKUP_REAL_SESSIONS}세션 × 750행(실 JSON ≈2KB/행), 팔 A·B 각 버림1+본판1"
  OUT=$out STAGE=real REAL_SESSIONS=$BACKUP_REAL_SESSIONS \
    timeout --kill-after=120 "$TIMEOUT_BACKUP_REAL" bash "$BACKUP_RIG/backup_sweep.sh" || return 1
  return 0
}

# ── 복제 지연 · 반동기 (主 P4) ───────────────────────────────────────────
#
# 러너 = **소스 박스**. 리플리카는 원격이다(위 설정 블록의 이유).

phase_repl_preflight() {
  local ok=0

  preflight_s3 || ok=1

  docker exec "$CONTAINER" mysqladmin ping -h localhost --silent >/dev/null 2>&1 \
    && note "✅ 소스 MySQL 응답" || { note "🔴 소스 MySQL 무응답"; ok=1; }

  if [ -z "$REPLICA_HOST" ]; then
    note "🔴 REPLICA_HOST 가 비었다 — 2대 무대가 성립하지 않는다"; ok=1
  else
    # 리플리카 3306 — 소스 컨테이너의 mysql 클라이언트로 «실제로 붙어» 본다.
    # 포트가 열렸는지가 아니라 인증까지 되는지가 이 라운드의 전제다.
    if docker exec -i "$CONTAINER" mysql -h "$REPLICA_HOST" -P 3306 --get-server-public-key \
         -uroot -p"$PW" -N -B -e "SELECT 1;" >/dev/null 2>&1; then
      note "✅ 리플리카 3306 접속 — $REPLICA_HOST"
    else
      note "🔴 리플리카($REPLICA_HOST:3306)에 못 붙는다 — 보안그룹 인바운드 3306 부터 볼 것"; ok=1
    fi
    if $REPLICA_SSH "echo ok" >/dev/null 2>&1; then
      note "✅ 리플리카 SSH — 사본을 붓고 컨테이너를 올릴 수 있다"
    else
      note "🔴 리플리카 SSH 가 안 된다 — XtraBackup 경로가 통째로 막힌다(키·22 인바운드)"; ok=1
    fi
  fi

  # 🔴 여기서 막는다 (#368). 예전에는 경고만 하고 넘겼는데, 그 문구(«논리 덤프로
  #    되돌아간다 — 느리지만 돈다»)가 **폴백을 선택지처럼 읽히게** 만들었다.
  #    폴백은 사고 대비이지 선택지가 아니다:
  #      · 이 라운드의 목적 중 하나가 «XtraBackup 원격 절차를 처음 밟아 보는 것» 이다
  #        (rig 문서 §6 — 그 경로는 코드로만 있고 실행 이력이 0 이다).
  #        조용히 논리 덤프로 대체되면 게이트는 다 통과하고 **라운드가 목적을 못 이룬다**
  #      · 그 대체는 표에 «실패» 로 안 남는다. `replica_build.txt` 의 «초기화 경로» 열
  #        하나에만 남고, 그건 라운드가 끝난 뒤에 읽는 파일이다
  #    ⚠️ 일부러 논리 덤프로 돌리려면 `REPLICA_INIT=dump` 를 준다 — 그때는 이 검사를 건너뛴다.
  #       «의도한 덤프» 와 «이미지가 없어서 된 덤프» 를 여기서 가른다.
  if [ "${REPLICA_INIT:-xtrabackup}" = "dump" ]; then
    note "⏭  xtrabackup 이미지 검사 건너뜀 — REPLICA_INIT=dump (논리 덤프를 **의도한** 라운드다)"
  elif docker image inspect "$XB_IMAGE" >/dev/null 2>&1; then
    note "✅ xtrabackup 이미지"
  else
    note "🔴 xtrabackup 이미지가 없다 — 이대로 돌면 리플리카 초기화가 조용히 논리 덤프로 대체되고, 이 라운드가 밟아 보려던 경로를 못 밟는다. 받는 법: docker pull $XB_IMAGE. 부트스트랩(ROLE=db)이 받아 두므로, 없다면 이 박스가 부트스트랩을 안 거쳤다는 뜻이다"
    ok=1
  fi

  # 🔴 **리플리카에도 있어야 한다** — 사본을 붓는 `docker run` 이 그쪽에서 돈다
  #    (`repl2_rig.sh:509`). 소스만 보고 통과시키면 절반만 확인한 것이다. P3(1대)엔
  #    없던 요구라 이 검사도 이 라운드가 처음이다.
  if [ -n "$REPLICA_HOST" ] && [ "${REPLICA_INIT:-xtrabackup}" != "dump" ]; then
    if $REPLICA_SSH "docker image inspect $XB_IMAGE >/dev/null 2>&1" >/dev/null 2>&1; then
      note "✅ xtrabackup 이미지 — 리플리카에도 있다"
    else
      note "🔴 리플리카에 xtrabackup 이미지가 없다 — 사본을 붓는 단계가 거기서 막힌다. 리플리카 박스에서: docker pull $XB_IMAGE"
      ok=1
    fi
  fi

  local free; free=$(df -BG --output=avail "$ROOT" | tail -1 | tr -dc '0-9')
  note "디스크 여유 ${free}GB (사본을 소스 박스에 한 번 만든다 + 판마다 행이 쌓인다)"
  [ "${free:-0}" -ge 20 ] || { note "🔴 20GB 미만"; ok=1; }

  # 🔴 막지는 않는다. 다만 이 값이 비면 **Q2 의 수치를 나중에 해석할 수 없다** —
  #    반동기의 대가는 AZ 간 RTT 에 지배되기 때문이다(설계 §9-1 ②).
  if [ -z "$REPL_AZ_MODE" ]; then
    note "⚠️ REPL_AZ_MODE 가 비었다 — 결과의 조건 칸이 «(미기입)» 으로 남는다."
    note "   예: REPL_AZ_MODE=\"same-az(ap-northeast-2a)\" 또는 \"cross-az(2a→2c)\""
  else
    note "✅ AZ 구성 — $REPL_AZ_MODE"
  fi

  preflight_tags
  return $ok
}

# 무대 세우기 + 게이트 G1~G3. **여기서 막히면 본 측정을 안 돈다.**
# G3(양성 대조군)이 특히 그렇다 — 계측이 안 서면 Q1 의 「지연이 작다」는
# 「계측이 못 잡았다」와 구분되지 않는다.
phase_repl_gate() {
  local out=$OUTDIR/repl
  mkdir -p "$out"
  note "무대 SESSIONS=$REPL_SESSIONS · 리플리카=$REPLICA_HOST"
  OUT=$out SESSIONS=$REPL_SESSIONS \
    timeout --kill-after=120 "$TIMEOUT_REPL_GATE" bash "$REPL_RIG/repl2_probe.sh" || return 1
  return 0
}

phase_repl() {
  local out=$OUTDIR/repl
  mkdir -p "$out"
  note "본 측정 — 팔 A·B 각 버림1+본판3 + 핫세션 대조 2판"
  OUT=$out SESSIONS=$REPL_SESSIONS \
    timeout --kill-after=120 "$TIMEOUT_REPL" bash "$REPL_RIG/repl2_sweep.sh" || return 1
  return 0
}

# ── 동거 용량 (主 P6) ────────────────────────────────────────────────────
#
# 이 세 단계는 다른 단계와 «어디를 보는가» 가 다르다 — 러너는 부하기에 있고 측정 대상은
# 원격이다. 그래서 preflight 도 따로 둔다: `preflight` 는 로컬 MySQL·percona 이미지·
# 디스크 20GB 를 묻는데 부하기 박스엔 셋 다 없다. 그걸 그대로 쓰면 **환경이 맞는데
# 실패로 찍힌다** — 08-12 에 «부트스트랩이 스키마를 안 만들어서» 從 항목을 놓친 것과
# 같은 계열의, 「환경 결함이 측정 결과처럼 보이는」 자리다.

phase_coresidency_preflight() {
  local ok=0
  preflight_s3 || ok=1

  [ -n "$TARGET_HOST" ] \
    && note "✅ 대상 호스트: $TARGET_HOST" \
    || { note "🔴 TARGET_HOST 가 없다 — 무엇을 재는지가 안 정해졌다"; ok=1; }
  [ -n "$AI_PUBLIC_TOKEN" ] \
    && note "✅ AI_PUBLIC_TOKEN 있음" \
    || { note "🔴 AI_PUBLIC_TOKEN 이 비었다 — 프레임 경로가 전부 401 이다"; ok=1; }

  # 🔴 rig 은 «python» 을 부른다(probe.sh G0·G1·G2 · coresidency_sweep.sh:94).
  #    AL2023 은 python3 만 있는 경우가 흔한데, 그러면 게이트가 통째로 죽는다.
  if command -v python >/dev/null 2>&1; then
    note "✅ python: $(command -v python)"
  else
    note "🔴 «python» 이 PATH 에 없다 — rig 이 python3 가 아니라 python 을 부른다 (alias 를 걸 것)"
    ok=1
  fi

  # 프레임 자산은 커밋돼 있다(408KB). 여기서 만들 수는 없다 — gen_frames.py 는 mediapipe 가
  # 필요해서 ai-server 이미지 안에서 도는 물건이다.
  if [ -f "$CORES_RIG/frames.json" ]; then
    note "✅ 프레임 자산 $(wc -c < "$CORES_RIG/frames.json") B"
  else
    note "🔴 frames.json 이 없다 — 여기서는 못 만든다(mediapipe 필요). 미리 만들어 올릴 것"
    ok=1
  fi

  # 160세션 × (부하 스레드 1 + 커넥션) — 기본 1024 면 상단 레벨에서 «조용히» 실패한다.
  local nofile; nofile=$(ulimit -n 2>/dev/null)
  note "부하기 ulimit -n = ${nofile:-?} (최고 레벨 ${CORES_LEVELS##* }세션)"

  # §T — 부하기 코어 팔은 **부하기가 제한값보다 커야** 뜻이 있다. 2 vCPU 박스에서 «2코어 제한»
  #      과 «전체» 는 같은 조건이고, 그러면 「부하기 탓 ↔ 호스트 탓」이 또 안 갈린다.
  if [ "$CORES_TASKSET" = "1" ]; then
    local lcpu; lcpu=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)
    if ! command -v taskset >/dev/null 2>&1; then
      note "🔴 부하기에 taskset 이 없다(util-linux) — §T 코어 팔이 통째로 안 돈다"; ok=1
    elif [ "$lcpu" -le "$CORES_TASKSET_CPUS" ]; then
      note "🔴 부하기가 ${lcpu}코어 — «제한 $CORES_TASKSET_CPUS» 와 «전체» 가 같은 조건이다."
      note "   더 큰 부하기(c7i.xlarge 이상)로 띄우거나 CORES_TASKSET=0 으로 «안 가른다» 를 명시할 것"
      ok=1
    else
      note "✅ §T 코어 팔 — 부하기 ${lcpu}코어 · 제한 ${CORES_TASKSET_CPUS}코어로 대조"
    fi
  fi

  if [ -n "$TARGET_SSH" ]; then
    if $TARGET_SSH "test -d $TARGET_REPO_DIR && docker ps >/dev/null 2>&1" 2>/dev/null; then
      note "✅ 대상 박스 도달 — $TARGET_REPO_DIR · docker 사용 가능"
    else
      note "🔴 대상 박스에 못 붙거나 $TARGET_REPO_DIR / docker 가 없다 — 팔 전환이 통째로 죽는다"
      ok=1
    fi

    # 🔴 #214 — 메모리 캡이 없으면 **첫 세션에서** RuntimeError 다. 팔 B 도 메모리 캡은 건다
    #    (팔 B 가 흔드는 것은 CPU 캡 하나다). percona-toolkit 이미지가 없어 팔 B 4판이 전부
    #    죽었던 것과 같은 부류 — 도구의 성질이 아니라 환경 결함인데 표에는 똑같이 보인다.
    local aimem
    aimem=$($TARGET_SSH "docker inspect -f '{{.HostConfig.Memory}}' $AI_CONTAINER 2>/dev/null" 2>/dev/null | tr -d '\r')
    if [ -z "$aimem" ]; then
      note "⚠️ 대상에서 $AI_CONTAINER 를 못 찾았다 — 아직 안 떠 있으면 정상. 게이트 G3 가 다시 본다"
    elif [ "$aimem" = "0" ]; then
      note "🔴 AI 메모리 캡이 없다 (#214) — 첫 세션에서 죽는다. AI_MEM_LIMIT 를 걸 것"
      ok=1
    else
      note "✅ AI 메모리 캡 $((aimem/1024/1024))MB"
    fi

    # 🔴 #254 — 옆(Spring) 지표는 **9090** 이고 compose 가 그 포트를 127.0.0.1 에만 연다.
    #    못 긁으면 H3(«캡이 옆을 지키는가»)가 **또** 판정 열 없이 끝난다. 두 라운드가 그랬다.
    #    백엔드가 아직 안 떠 있는 preflight 시점이면 경고만 하고, 리허설의 side 게이트가 다시 본다.
    if $TARGET_SSH "docker ps --format '{{.Names}}' | grep -qx shadowfit-backend" 2>/dev/null; then
      if $TARGET_SSH "curl -sf --max-time 5 http://127.0.0.1:9090/actuator/prometheus >/dev/null" 2>/dev/null; then
        note "✅ 대상 actuator(9090) 스크레이프 가능 — H3 판정 열이 생긴다 (#254)"
      else
        note "🔴 대상에서 actuator(9090)를 못 긁는다 — H3 가 또 판정 열 없이 끝난다 (#254)"
        note "   볼 곳: management.server.port=9090 · compose 의 127.0.0.1:9090 매핑 · 컨테이너 안에 curl 이 아니라 **호스트** curl 을 쓴다"
        ok=1
      fi
    else
      note "⚠️ 대상에 shadowfit-backend 가 아직 없다 — actuator 확인은 리허설의 side 게이트로 미룬다"
    fi

    # 자기 프로브는 **대상 박스에서** 파이썬을 돌린다(설계 §4-2). 없으면 스윕이 프로브를
    # 스스로 끄는데, 그러면 「서버인가 부하기인가」를 또 못 가른 채 라운드가 끝난다.
    if [ "$CORES_PROBE" = "1" ]; then
      local tpy
      tpy=$($TARGET_SSH "command -v python3 || command -v python" 2>/dev/null | head -1 | tr -d '\r')
      [ -n "$tpy" ] \
        && note "✅ 대상 박스 python: $tpy (자기 프로브)" \
        || { note "🔴 대상 박스에 python 이 없다 — 자기 프로브가 안 돈다 (설계 §4-2). 깔거나 CORES_PROBE=0 으로 «없이 돈다» 를 명시할 것"; ok=1; }
    fi

    # 팔 C·D 는 대상 박스의 compose 가 AI_CPUS·MYSQL_CPUS·BACKEND_CPUS 를 **필수**로 읽는다
    # (`${AI_CPUS:?}`). 없으면 그 팔의 13판이 전부 죽는데, 표에는 팔 하나가 빈 것으로만 보인다.
    if echo "$CORES_ARMS" | grep -qE '(^| )(C|D)( |$)'; then
      local missing=""
      local v
      for v in AI_CPUS MYSQL_CPUS BACKEND_CPUS; do
        $TARGET_SSH "grep -q '^$v=' $TARGET_REPO_DIR/.env" 2>/dev/null || missing="$missing $v"
      done
      [ -z "$missing" ] \
        && note "✅ 팔 C 용 캡 변수 3개 확인 (.env)" \
        || { note "🔴 대상 .env 에 없다:$missing — 팔 C 가 통째로 죽는다"; ok=1; }
    fi
  fi

  # 從 부하(#223). 팔 B·C·D 가 하나라도 있으면 **여기서** 막는다 — 리허설까지 가서 알면
  # 리허설 시간을 버리고, 본판까지 가면 라운드를 버린다.
  if echo " $CORES_ARMS " | grep -qE ' (B|C|D) '; then
    local miss=""
    [ -n "$GHZ_RPS" ]   || miss="$miss GHZ_RPS"
    [ -n "$GHZ_TOKEN" ] || miss="$miss GHZ_TOKEN"
    [ -f "${GHZ_DATA:-/nonexistent}" ] || miss="$miss GHZ_DATA(파일)"
    [ -x "$GHZ_BIN" ]   || miss="$miss GHZ_BIN(실행권한)"
    if [ -z "$miss" ]; then
      note "✅ 從 부하 ${GHZ_RPS} req/s 고정 · c=$GHZ_CONC · $(basename "$GHZ_DATA")"
    else
      note "🔴 從 부하 설정이 비었다:$miss — 팔 B/C/D 는 «유휴 동거» 를 잰다 (#223)"
      ok=1
    fi
  else
    note "從 부하 없음 — CORES_ARMS='$CORES_ARMS' 에 B·C·D 가 없다(유휴 동거 기준선만 얻는 라운드)"
  fi

  preflight_tags
  return $ok
}

# 「스크립트가 0 으로 끝났다」와 「판이 성립했다」는 다르다.
#
# 🔴 이 rig 의 급소가 정확히 여기다 — 세션이 안 열리면 프레임이 전부 거절되는데 요청 수·지연은
#    정상으로 찍힌다(README 게이트 G2). 그래서 리허설의 통과 여부를 **종료 코드가 아니라
#    결과 표**로 판정한다. #202(「설계에 있던 판이 rig 에 없었는데 표는 정상」)와 같은 방어다.
cores_assert_usable() {  # $1 = coresidency.tsv
  local tsv=$1
  [ -f "$tsv" ] || { note "🔴 결과 표가 없다: $tsv"; return 1; }
  awk -F'\t' '
    NR>1 {
      rows++
      printf "     %s %s %s세션 → req=%s setup_fail=%s nolease=%s nopose=%s\n", $1,$2,$3,$4,$12,$10,$11
      if ($4 == "FAIL")   { bad++; next }
      if ($12+0 > 0)      { bad++; next }
      if ($4+0 == 0)      { bad++; next }
    }
    END {
      if (rows == 0) { print "  🔴 본판이 한 줄도 없다 — 스윕이 판을 못 돌았다"; exit 1 }
      if (bad > 0) {
        printf "  🔴 %d/%d 판이 성립하지 않았다 (FAIL · setup_fail>0 · 요청 0건).\n", bad, rows
        print  "     이 상태의 수치는 «측정» 이 아니라 «세션이 안 열린 것» 이다 — 본 측정으로 넘어가지 않는다"
        exit 1
      }
      printf "  ✅ %d판 전부 성립 (setup_fail 0)\n", rows
    }' "$tsv"
}

# 🔴 「ghz 가 돌았다」와 「옆이 실제로 일했다」는 다르다 — 그리고 **판정이 여기 없었다**
#    (2026-08-16 설계 대조에서 잡혔다).
#
#    `stop_ghz` 는 성공 응답 0건을 **stdout 에 🔴 로 찍을 뿐** 종료 코드에 영향이 없고,
#    `cores_assert_usable` 은 `coresidency.tsv` 만 본다. 그래서 GHZ_TOKEN 이 틀렸거나 6565 가
#    막혀 從 부하가 전멸해도 **리허설이 통과**하고, 본판 6시간이 통째로 «유휴 동거» 가 된다.
#    A 와 B 를 가르는 것이 그 부하 하나뿐이므로(#222 (a)안) 결과는 «동거 비용이 작다» 로
#    정상처럼 읽힌다 — #201·#202 와 같은 부류라 사람이 아니라 게이트가 잡아야 한다.
#
#    버림판은 판정에서 뺀다. 그 판은 표에 안 들어가고, 워밍업 중 한 번 튄 것으로 라운드를
#    막으면 «환경 결함이 아닌 것» 때문에 멈추게 된다.
cores_assert_ghz() {  # $1 = ghz.tsv
  local tsv=$1
  echo " $CORES_ARMS " | grep -qE ' (B|C|D) ' || { note "從 부하 없는 라운드 — ghz 판정 생략"; return 0; }
  [ -f "$tsv" ] || { note "🔴 從 부하 표가 없다: $tsv — «걸었는지» 를 단언할 수 없다"; return 1; }
  awk -F'\t' '
    NR>1 && $2 != "A" && $1 !~ /_discard_/ {
      rows++
      if ($5 == "FAIL")                { bad++; printf "     %s %s → 리포트를 못 읽었다\n", $1, $2; next }
      if ($7+0 == 0)                   { bad++; printf "     %s %s → 성공 0건 (%s건 전부 실패)\n", $1, $2, $6; next }
      if ($4+0 > 0 && $5+0 < $4*0.5)   { printf "     ⚠️ %s %s → 실측 %s < 목표 %s 의 절반 — 다른 조건이다\n", $1, $2, $5, $4 }
      if ($8+0 > 0)                    { printf "     ⚠️ %s %s → 실패 %s/%s\n", $1, $2, $8, $6 }
    }
    END {
      if (rows == 0) { print "  🔴 從 부하를 쓰는 팔의 판이 한 줄도 없다 — 그 팔들이 안 돌았거나 부하가 안 붙었다"; exit 1 }
      if (bad > 0) {
        printf "  🔴 %d/%d 판에서 從 부하가 걸리지 않았다.\n", bad, rows
        print  "     이 상태로 본판을 돌면 팔 B·C 가 «유휴 동거» 가 되고, 표는 «동거 비용이 작다» 로 읽힌다"
        print  "     볼 곳: GHZ_TOKEN(INTERNAL_API_TOKEN 과 같은 값인가) · 대상 6565 도달 · 페이로드 세션 시드"
        exit 1
      }
      printf "  ✅ 從 부하 %d판 전부 성립 (성공 응답 > 0)\n", rows
    }' "$tsv"
}

# 🔴 부하기 지표(#250)도 같은 이유로 게이트가 필요하다. 08-15·08-16 두 라운드가 **부하기를
#    안 걷은 채** 끝났고, 그래서 「천장이 서버인가 부하기인가」가 두 번 미결로 남았다.
#    샘플러가 들어간 뒤에도 위험은 남는다 — `pgrep -f 'load_ai\.py'` 가 안 맞으면
#    `load_ai_pct` 가 **전부 0** 인 표가 조용히 생기고, 파일은 있으니 «걷었다» 로 보인다.
#    그래서 «행이 있는가» 가 아니라 **«부하기 프로세스가 실제로 보였는가»** 를 판정한다.
# 🔴 자기 프로브(설계 §4-2)도 «있는가» 가 아니라 **«두 시계를 같은 창에서 읽었는가»** 로
#    판정한다. `window` 가 warm 이 아니면 부하기 p50 과 나란히 못 놓는다 — 그런 표는
#    「서버가 빨랐다」로 잘못 읽히고, 그게 이 프로브를 넣은 이유를 통째로 무너뜨린다.
cores_assert_probe() {  # $1 = probe_rtt.tsv
  local tsv=$1
  [ "$CORES_PROBE" = "1" ] || { note "자기 프로브 꺼짐(CORES_PROBE=0) — 서버 시계 없이 도는 라운드다"; return 0; }
  [ -f "$tsv" ] || { note "🔴 probe_rtt.tsv 가 없다 — 대상 박스 시계를 못 걷었다 (설계 §4-2)"; return 1; }
  awk -F'\t' '
    NR>1 {
      rows++; win[$10]++
      if ($10 == "warm" && $7+0 > 0) { ok++; if ($7+0 > worst) worst = $7+0 }
    }
    END {
      printf "     판 %d — warm %d · full %d · no_overlap %d · empty %d · FAIL %d\n",
             rows, win["warm"]+0, win["full"]+0, win["no_overlap"]+0, win["empty"]+0, win["-"]+0
      if (ok+0 > 0) printf "     서버 시계 최대 p50 %.1f ms\n", worst
      if (rows == 0) { print "  🔴 프로브 행이 한 줄도 없다 — 대상 박스에서 안 돌았다"; exit 1 }
      if (ok+0 == 0) {
        print "  🔴 부하기 창과 겹치는 프로브 표본이 **한 판도** 없다"
        print "     볼 곳: 대상 박스 python · $PROBE_DIR 자산 · probe 계정이 409 로 막혔는지(reset_sessions)"
        exit 1
      }
      printf "  ✅ 두 시계 성립 (%d판)\n", ok
    }' "$tsv"
}

# §T — 부하기 코어 팔. 「돌았나」와 「쌍이 성립했나」는 다르다: 한쪽 조건만 남으면 대조가
#      없는 것이고, 그러면 「부하기 탓 ↔ 호스트 탓」이 이번에도 안 갈린다.
#      🔴 기아 가설은 이미 기각됐으므로(AI CPU 등가) 여기서 볼 것은 **처리량 차이**다 —
#         차이가 나면 부하기 구성이 결과를 움직인다는 뜻이고, 안 나면 라운드 간 +17.7% 는
#         호스트 개체차 쪽으로 기운다.
cores_assert_taskset() {  # $1 = coresidency.tsv
  local tsv=$1
  [ "$CORES_TASKSET" = "1" ] || { note "§T 코어 팔 꺼짐(CORES_TASKSET=0)"; return 0; }
  [ -f "$tsv" ] || { note "🔴 결과 표가 없다: $tsv"; return 1; }
  awk -F'\t' '
    NR>1 && $2 ~ /^lc(2|F)_t/ {
      rows++
      key = ($2 ~ /^lc2_/) ? "lc2" : "lcF"
      if ($4 == "FAIL" || $4+0 == 0) { bad++; next }
      cnt[key]++; sum[key] += $5+0
      lv[$3] = 1
    }
    END {
      if (rows == 0) {
        print "     §T 판이 없다 — 부하기가 작거나 taskset 이 없어 스윕이 건너뛴 것이다(그 사유는 스윕 로그에)"
        exit 0
      }
      printf "     §T %d판 (제한 %d · 전체 %d · 성립 안 함 %d)\n", rows, cnt["lc2"]+0, cnt["lcF"]+0, bad+0
      if (cnt["lc2"]+0 == 0 || cnt["lcF"]+0 == 0) {
        print "  🔴 한쪽 조건만 남았다 — 대조가 성립하지 않는다"
        exit 1
      }
      a = sum["lc2"]/cnt["lc2"]; b = sum["lcF"]/cnt["lcF"]
      printf "  ✅ §T 성립 — 제한 %.1f fps ↔ 전체 %.1f fps (차 %+.1f%%)\n", a, b, 100*(a-b)/b
      print  "     차이가 크면 «부하기 구성 탓», 없으면 라운드 간 +17.7% 는 «호스트 개체차» 쪽이다"
    }' "$tsv"
}

cores_assert_loader() {  # $1 = 결과 디렉터리
  local dir=$1 n
  n=$(ls "$dir"/loader_*.tsv 2>/dev/null | wc -l)
  [ "$n" -gt 0 ] || { note "🔴 loader_*.tsv 가 하나도 없다 — 부하기를 또 안 걷었다 (#250)"; return 1; }
  awk -F'\t' '
    FNR>1 {
      rows++
      if ($3 == "-1") neg++              # #255 — 프로세스가 창 안에서 끝난 구간
      else if ($3+0 > 0) seen++
      if ($2+0 > max) max = $2+0
      if ($9 != "-1" && $9+0 > tx) tx = $9+0
    }
    END {
      printf "     표본 %d행 · load_ai 가 보인 표본 %d · 못 잰 구간(-1) %d\n", rows, seen+0, neg+0
      printf "     부하기 최대 CPU %.1f%% (100%% = 1 vCPU) · 최대 송신 %.2f Mbps\n", max+0, tx+0
      if (rows == 0)  { print "  🔴 표본이 한 줄도 없다 — 샘플러가 안 돌았다"; exit 1 }
      if (seen+0 == 0) {
        print "  🔴 load_ai 프로세스가 **한 표본도** 안 보였다 — pgrep 패턴이 안 맞는 것이다"
        print "     이대로면 부하기 CPU 가 전부 0 인 표가 생기고, 「부하기는 안 붙었다」로 잘못 읽힌다"
        exit 1
      }
      printf "  ✅ 부하기 계측 성립\n"
    }' "$dir"/loader_*.tsv
}

# 🔴 「지표를 걷는 코드가 있다」와 「지표가 걷혔다」는 다르다 (#254 · #250 이 같은 자리에서
#    두 번 막혔다). 옆(Spring·MySQL) 스냅샷은 **판정 열을 만드는 것이 존재 이유**인데,
#    스크레이프가 조용히 실패하면 `side.tsv` 는 FAIL 행만 남고 라운드는 정상 종료한다.
#    그 상태로 본판을 돌면 H3 는 **네 번째 라운드에도** 미답이다.
cores_assert_side() {  # $1 = side.tsv
  local tsv=$1
  [ -f "$tsv" ] || { note "🔴 옆 지표 표가 없다: $tsv — H3 는 또 판정 열 없이 끝난다 (#254)"; return 1; }
  awk -F'\t' '
    NR>1 {
      rows[$6]++
      if ($8 == "FAIL") fails[$6]++
      # 게이지는 창 한가운데 것만 뜻이 있다 — 그 한 점이 실제로 찍혔는지를 본다
      if ($4 == "mid" && $6 == "mysql" && $7 == "Threads_running" && $8 != "FAIL") mid_gauge++
    }
    END {
      bad = 0
      for (src in rows) {
        printf "     %s: %d행 (FAIL %d)\n", src, rows[src], fails[src]+0
        if (rows[src] == fails[src]) { printf "  🔴 %s 스냅샷이 전부 실패했다\n", src; bad = 1 }
      }
      if (!("spring" in rows)) { print "  🔴 Spring(actuator) 행이 한 줄도 없다"; bad = 1 }
      if (!("mysql"  in rows)) { print "  🔴 MySQL 행이 한 줄도 없다"; bad = 1 }
      if (mid_gauge+0 == 0) {
        print "  🔴 창 한가운데(mid) 게이지가 없다 — 포화 때 옆이 어땠는지가 안 남는다"
        bad = 1
      }
      if (bad) {
        print  "     이대로 본판을 돌면 H3(«캡이 옆을 지키는가»)는 이번에도 판정할 열이 없다 (#254)"
        print  "     볼 곳: 대상 9090 도달 · MYSQL_USER/PW · SIDE_RE 가 실제 지표 이름과 맞는가"
        exit 1
      }
      printf "  ✅ 옆 지표 수집 성립 (mid 게이지 %d점)\n", mid_gauge
    }' "$tsv"
}

# 축소 리허설. **여기서 실패하면 본 측정으로 넘어가지 않는다** — 리허설의 존재 이유다.
# README 「축소 리허설(무인 실행 전 필수)」와 같은 규모로 돈다.
phase_coresidency_rehearsal() {
  local out=$OUTDIR/coresidency_rehearsal
  mkdir -p "$out"
  note "게이트 G0~G3 → 축소 스윕 (LEVELS='$CORES_REH_LEVELS' DUR=$CORES_REH_DUR REPEATS=1)"
  note "**이 판의 수치는 측정값이 아니다** — 경로만 본다"

  env OUT="$out" BASE="http://$TARGET_HOST:8080" AI="http://$TARGET_HOST:8000" \
      TOKEN="$AI_PUBLIC_TOKEN" AI_CONTAINER="$AI_CONTAINER" DOCKER="$TARGET_SSH docker" \
      timeout --kill-after=60 "$TIMEOUT_CORES_REHEARSAL" bash "$CORES_RIG/probe.sh" \
    > "$out/probe.log" 2>&1 || { cat "$out/probe.log"; return 1; }
  cat "$out/probe.log"

  env OUT="$out" HOST="$TARGET_HOST" TOKEN="$AI_PUBLIC_TOKEN" SSH="$TARGET_SSH" \
      REPO_DIR="$TARGET_REPO_DIR" ARMS="$CORES_ARMS" LEVELS="$CORES_REH_LEVELS" \
      DUR="$CORES_REH_DUR" REPEATS=1 \
      LEVEL_SHIFT="$CORES_LEVEL_SHIFT" ANCHOR="$CORES_ANCHOR" \
      ANCHOR_ARM="$CORES_ANCHOR_ARM" ANCHOR_LEVEL="$CORES_REH_ANCHOR_LEVEL" \
      PROBE="$CORES_PROBE" PROBE_PREFIX="$CORES_PROBE_PREFIX" \
      TASKSET_BLOCK="$CORES_TASKSET" TASKSET_CPUS="$CORES_TASKSET_CPUS" \
      TASKSET_ARM="$CORES_TASKSET_ARM" TASKSET_LEVELS="$CORES_REH_TASKSET_LEVELS" TASKSET_REPS=1 \
      GHZ_RPS="$GHZ_RPS" GHZ_DATA="$GHZ_DATA" GHZ_TOKEN="$GHZ_TOKEN" \
      GHZ_BIN="$GHZ_BIN" GHZ_CONC="$GHZ_CONC" MYSQL_CONTAINER="$CONTAINER" \
      MYSQL_USER=root MYSQL_PW="$PW" \
      timeout --kill-after=60 "$TIMEOUT_CORES_REHEARSAL" bash "$CORES_RIG/coresidency_sweep.sh" \
    || return 1

  # 🔴 전 팔을 리허설에서 밟는다. 팔 하나가 «구성은 되는데 세션이 안 열리는» 상태면
  #    본판에서 그 팔 13판이 통째로 빈다 — 그걸 아침에 알면 라운드를 다시 사야 한다.
  #    #222 의 팔 A 가 그 상태였고 (a)안으로 고쳤다 — 이 판정은 그 수정이 실제로 섰는지를
  #    EC2 에서 처음으로 확인하는 자리이기도 하다(코드 판독만 돼 있다).
  cores_assert_usable "$out/coresidency.tsv" || return 1
  # 從 부하까지 봐야 «동거» 를 잰 것이다. AI 쪽만 성립해도 옆이 놀았으면 이 라운드는 헛돈다.
  cores_assert_ghz "$out/ghz.tsv" || return 1
  # 그리고 옆 지표가 실제로 걷혔는지 (#254). 없으면 본판을 돌아도 H3 는 또 미답이다.
  cores_assert_side "$out/side.tsv" || return 1
  # 부하기 자신도 (#250). 「파일은 있는데 전부 0」이 이 축의 실패 모드다.
  cores_assert_loader "$out" || return 1
  # 두 시계가 같은 창에서 읽혔는지 (설계 §4-2). 이게 없으면 「서버인가 부하기인가」가 또 미결이다.
  cores_assert_probe "$out/probe_rtt.tsv" || return 1
  # §T 코어 팔의 쌍이 성립하는지. 리허설에서 경로를 밟아둬야 본판에서 한쪽만 남는 일이 없다.
  cores_assert_taskset "$out/coresidency.tsv" || return 1
  return 0
}

phase_coresidency() {
  local out=$OUTDIR/coresidency
  mkdir -p "$out"
  note "정판 — 팔 '$CORES_ARMS' × 레벨 '$CORES_LEVELS' × ${CORES_REPEATS}반복 (+팔당 버림 1)"
  # #223 은 배선됐다(요청/초 고정). A 와 B 를 가르는 것은 그 부하 하나뿐이므로,
  # 설정이 비면 스윕이 스스로 라운드를 거부한다(assert_arms_distinguishable).
  # 🔴 읽을 때 볼 것은 «걸렸나» 가 아니라 «얼마가 걸렸나» 다 — ghz.tsv 의 achieved_rps.
  note "從 부하 ${GHZ_RPS:-?} req/s 고정 — 실측치는 ghz.tsv 의 achieved_rps 로 확인할 것"

  env OUT="$out" BASE="http://$TARGET_HOST:8080" AI="http://$TARGET_HOST:8000" \
      TOKEN="$AI_PUBLIC_TOKEN" AI_CONTAINER="$AI_CONTAINER" DOCKER="$TARGET_SSH docker" \
      timeout --kill-after=60 "$TIMEOUT_CORES" bash "$CORES_RIG/probe.sh" \
    > "$out/probe.log" 2>&1 || { cat "$out/probe.log"; return 1; }
  cat "$out/probe.log"

  env OUT="$out" HOST="$TARGET_HOST" TOKEN="$AI_PUBLIC_TOKEN" SSH="$TARGET_SSH" \
      REPO_DIR="$TARGET_REPO_DIR" ARMS="$CORES_ARMS" LEVELS="$CORES_LEVELS" \
      DUR="$CORES_DUR" REPEATS="$CORES_REPEATS" STATS_SEC="${STATS_SEC:-5}" \
      LEVEL_SHIFT="$CORES_LEVEL_SHIFT" ANCHOR="$CORES_ANCHOR" \
      ANCHOR_ARM="$CORES_ANCHOR_ARM" ANCHOR_LEVEL="$CORES_ANCHOR_LEVEL" \
      PROBE="$CORES_PROBE" PROBE_PREFIX="$CORES_PROBE_PREFIX" \
      TASKSET_BLOCK="$CORES_TASKSET" TASKSET_CPUS="$CORES_TASKSET_CPUS" \
      TASKSET_ARM="$CORES_TASKSET_ARM" TASKSET_LEVELS="$CORES_TASKSET_LEVELS" \
      TASKSET_REPS="$CORES_TASKSET_REPS" \
      GHZ_RPS="$GHZ_RPS" GHZ_DATA="$GHZ_DATA" GHZ_TOKEN="$GHZ_TOKEN" \
      GHZ_BIN="$GHZ_BIN" GHZ_CONC="$GHZ_CONC" MYSQL_CONTAINER="$CONTAINER" \
      MYSQL_USER=root MYSQL_PW="$PW" \
      timeout --kill-after=120 "$TIMEOUT_CORES" bash "$CORES_RIG/coresidency_sweep.sh" \
    || return 1

  # 본판은 **막지 않는다** — 이미 다 돌았고, 성립 여부는 표에 남겨 사람이 읽는다.
  # (판정은 사람이 한다: sweep 말미의 「특히 볼 것」 세 줄)
  cores_assert_usable "$out/coresidency.tsv" \
    || note "⚠️ 위 판들은 그대로 남긴다 — 지우지 말고 «성립 안 함» 으로 읽을 것"
  cores_assert_ghz "$out/ghz.tsv" \
    || note "⚠️ 從 부하가 안 걸린 판이 있다 — 그 판의 «동거» 는 «유휴 동거» 다. 표에 그대로 적을 것"
  cores_assert_side "$out/side.tsv" \
    || note "⚠️ 옆 지표가 빈다 — H3(«캡이 옆을 지키는가»)는 이 라운드로도 못 닫는다 (#254). 결과에 그대로 적을 것"
  cores_assert_loader "$out" \
    || note "⚠️ 부하기 지표가 빈다 — 「천장이 서버인가 부하기인가」가 세 번째로 미결이다 (#250)"
  cores_assert_probe "$out/probe_rtt.tsv" \
    || note "⚠️ 서버 쪽 시계가 없다 — 이 라운드도 «서버가 느린 것»과 «부하기가 느린 것»을 못 가른다"
  cores_assert_taskset "$out/coresidency.tsv" \
    || note "⚠️ §T 코어 팔의 대조가 안 섰다 — 「부하기 탓 ↔ 호스트 개체차」는 이번에도 미결이다"
  return 0
}

# 從 항목 — 인프라가 살아 있을 때만 값이 생긴다. AWS-RIDE-ALONG.md §1 참고.
phase_ridealong() {
  local out=$OUTDIR/ridealong
  mkdir -p "$out"
  # 워치독을 명령에 직접 건다 (run_phase 주석 참고). 從 항목이 매달려서 라운드를 잡아먹지 않게.
  #
  # 🔴 **`-p$PW` 를 쓰지 않는다.** ① 비밀번호가 프로세스 인자로 노출되고 ② mysql 이
  #    `[Warning] Using a password on the command line interface can be insecure.` 를
  #    **stderr 로** 뱉는데, 아래 수집이 그걸 데이터 파일에 합쳐 담고 있었다 —
  #    `R2_global_status.txt` 1행이 경고라 **TSV 로서 깨져 있다**(PR #200 리뷰).
  #    `MYSQL_PWD` 는 argv 에 안 실리고 경고도 안 난다.
  local q="timeout $TIMEOUT_RIDEALONG docker exec -i -e MYSQL_PWD=$PW $CONTAINER mysql -uroot $DB_NAME"
  # stderr 는 **데이터 파일이 아니라 여기로** 모은다. 섞으면 파서가 조용히 틀린다.
  local err="$out/_stderr.log"

  # R1 — worst-section. 2026-08-08 에 정확히 이걸 안 돌리고 인프라를 삭제했다.
  #      ⚠️ 백엔드(Flyway)가 안 돌았으면 테이블 자체가 없다. 그때는 «해당 없음» 이 정답이고,
  #         없는 것을 «0» 으로 적으면 안 된다.
  {
    echo "# R1 worst-section — $(date -Is)"
    if $q -N -e "SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema='$DB_NAME' AND table_name='reports';" 2>/dev/null | grep -q '^1$'; then
      $q -e "SELECT 'reports 전체' k, COUNT(*) v FROM reports
             UNION ALL SELECT 'detailed_analysis 채워진 행', COUNT(*) FROM reports WHERE detailed_analysis IS NOT NULL
             UNION ALL SELECT 'pose_data 전체', COUNT(*) FROM pose_data
             UNION ALL SELECT 'exercise_sessions', COUNT(*) FROM exercise_sessions;" 2>>"$err"
    else
      echo "해당 없음 — reports 테이블이 없다(백엔드/Flyway 미실행). 0 이 아니라 «측정 대상 부재» 다."
    fi
  } > "$out/R1_worst_section.txt" 2>>"$err"

  # R2 — MySQL 지표. pool-cliff 초판이 «병목이 백엔드 CPU 로 이동» 을 철회한 사유가
  #      바로 이 지표의 부재였다. 이번엔 처음부터 걷는다.
  $q -e "SHOW GLOBAL STATUS;"    > "$out/R2_global_status.txt"    2>>"$err"
  $q -e "SHOW GLOBAL VARIABLES;" > "$out/R2_global_variables.txt" 2>>"$err"
  $q -e "SELECT DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT/1e12 sum_s, SUM_ROWS_EXAMINED
         FROM performance_schema.events_statements_summary_by_digest
         ORDER BY SUM_TIMER_WAIT DESC LIMIT 20;" > "$out/R2_top_digest.txt" 2>>"$err"

  # R3 — 3-way 조인. reports/sessions/users 시딩이 선행이라 이번 라운드 범위 밖이다.
  echo "미실행 — reports·exercise_sessions·users 시딩이 선행 조건. AWS-RIDE-ALONG.md §1 從-R3" \
    > "$out/R3_hash_join.SKIPPED.txt"

  note "R1·R2 수집, R3 은 미실행(사유 기록)"
  return 0
}

# ── 프레임 경로 계측 (從 R10-a) ──────────────────────────────────────────
#
# 게이트가 먼저다. 이 라운드는 «환경 결함» 이 «측정 결과» 로 위장하기 쉬운 자리가 넷 있고,
# 넷 다 **판이 끝난 뒤에는 구분이 안 된다.**
fp_gate() {
  local rc=0

  # ① 인터프리터 — 3.12 가 아니면 GIL 거동이 다른 기계다(설계 §13, 부트스트랩 ai-venv 와 같은 이유)
  if [ ! -x "$FP_VENV" ]; then
    note "🔴 venv 인터프리터가 없다: $FP_VENV — ROLE=ai-venv 부트스트랩을 안 거쳤다"; rc=1
  else
    local v; v=$("$FP_VENV" -V 2>&1 | awk '{print $2}')
    case "$v" in
      3.12.*) note "✅ python $v" ;;
      *) note "🔴 python $v — 기준 라운드는 3.12.x 다. GIL 을 흔드는 판이라 이건 «조건» 이 아니라 «교락» 이다"; rc=1 ;;
    esac
  fi

  # ② 격자 — 없으면 여기서 멈춘다. «아무 판이나» 돌면 팔과 판 순서가 안 갈린다
  if [ -z "$FP_PLAN" ]; then
    note "🔴 FP_PLAN 이 비었다 — 기본값을 두지 않는 것이 의도다(설계 §13 이 정본)."
    note "   격자를 먼저 정하고 그 문자열을 그대로 넘길 것. aws/README.md 의 R10-a 절에 있다"
    rc=1
  else
    note "격자 $(echo "$FP_PLAN" | tr ',' '\n' | wc -l)판 · 버림 $FP_DISCARD · 세션 $FP_SESSIONS · ${FP_DUR}초 · 풀 $FP_POOL"
  fi

  # ③ 프레임 자산 — 이 박스에서 못 만든다(mediapipe 로 영상을 돌려야 한다)
  [ -f "$CORES_RIG/frames.json" ] \
    && note "✅ frames.json $(wc -c < "$CORES_RIG/frames.json") B" \
    || { note "🔴 frames.json 이 없다 — 부하기가 쏠 프레임이 없다"; rc=1; }

  # ④ 🔴 포트가 비어 있나 — 전 판 서버가 안 죽어 있으면 **팔이 조용히 안 바뀐다.**
  #    rig 이 새로 띄우려다 실패해도 «기존 서버» 가 응답하므로 판은 정상으로 보인다.
  local p
  for p in "$FP_HTTP_PORT" "$FP_GRPC_PORT"; do
    if (exec 3<>/dev/tcp/127.0.0.1/"$p") 2>/dev/null; then
      exec 3<&- 2>/dev/null; exec 3>&- 2>/dev/null
      note "🔴 포트 $p 가 이미 열려 있다 — 그 프로세스를 내리고 다시 시작할 것 (안 내리면 팔 전환이 조용히 안 먹는다)"
      rc=1
    fi
  done

  # ⑤ CPU 샘플러 — 도커가 없어 docker stats 를 못 쓴다. /proc 가 대체 수단이다(#400 ⑤)
  [ -d /proc ] && note "✅ /proc — AI·부하기 CPU 를 걷는다" \
    || note "⚠️ /proc 가 없다 — CPU 축이 통째로 빈다. 「9.5 of 16」을 못 만진다"

  return $rc
}

# 판이 끝난 뒤 «성립했나» 를 본다. **막지 않는다** — 이미 다 돌았고, 판정은 사람이 한다.
# (coresidency 의 cores_assert_* 와 같은 결)
fp_assert() {
  local j=$1
  [ -f "$j" ] || { note "🔴 요약이 없다: $j"; return 1; }
  "$FP_VENV" - "$j" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
rs = [r for r in d["results"] if not r.get("discard")]
bad = []
def add(c, m):
    if c: bad.append(m)
add([r for r in rs if r.get("error")], "기동·부하 실패 판이 있다")
# 🔴 풀 소진은 RPS 를 «천장 돌파» 처럼 보이게 한다 (설계 §2-3 — 그 판들은 전부 무효였다)
add([r for r in rs if (r.get("outcomes") or {}).get("nolease")], "nolease>0 판이 있다 — 풀 소진. 그 판은 무효다")
add([r for r in rs if r.get("setup_fail")], "setup_fail>0 판이 있다 — 세션이 다 안 열렸다")
on = [r for r in rs if r.get("frame_path") is not None]
add(not on, "계측 ON 판이 하나도 없다 — 구간 비율·lease 가 통째로 빈다")
add([r for r in on if (r.get("frame_path") or {}).get("error")], "계측 스냅샷 회수 실패 판이 있다")
add([r for r in rs if (r.get("cpu") or {}).get("error")], "CPU 샘플러가 실패한 판이 있다 — 제목의 숫자를 못 만진다")
print(f"  본판 {len(rs)} · 계측 ON {len(on)}")
for m in bad:
    print("  🔴 " + m)
sys.exit(1 if bad else 0)
PY
}

phase_framepath() {
  local out=$OUTDIR/framepath
  mkdir -p "$out"

  fp_gate || { note "🔴 게이트 실패 — 이 상태로 돌리면 «환경 결함» 이 «측정 결과» 로 찍힌다"; return 1; }

  note "rig 이 서버를 팔마다 직접 띄우고 내린다 (재기동이 곧 팔 전환이다)"
  note "🔴 부하기가 같은 박스에 산다 — 절대 처리량 인용 금지. 이 판이 답하는 것은 팔 사이의 상대 델타다"

  timeout --kill-after=120 "$TIMEOUT_FP" \
    "$FP_VENV" "$FP_RIG" \
      --out "$out" --tag "$FP_TAG" \
      --python "$FP_VENV" \
      --sessions "$FP_SESSIONS" --fps "$FP_FPS" --dur "$FP_DUR" \
      --pool "$FP_POOL" --warmup "$FP_WARMUP" \
      --plan "$FP_PLAN" --discard "$FP_DISCARD" \
      --http-port "$FP_HTTP_PORT" --grpc-port "$FP_GRPC_PORT" \
    > "$out/run_arms.log" 2>&1 || { tail -40 "$out/run_arms.log"; return 1; }
  tail -20 "$out/run_arms.log"

  fp_assert "$out/arms_$FP_TAG.json" \
    || note "⚠️ 위 판들은 그대로 남긴다 — 지우지 말고 «성립 안 함» 으로 읽을 것"

  # 조건 파일은 부트스트랩이 만든다(ROLE=ai-venv). 결과 옆에 같이 둔다 — 없으면 스택이 안 남는다
  [ -f /root/ai_venv_conditions.txt ] && cp /root/ai_venv_conditions.txt "$out/" \
    || note "⚠️ ai_venv_conditions.txt 가 없다 — 무엇을 깔았는지가 결과에 안 남는다"
  return 0
}

# #276 — «uk 있음 + 중복 0 + 같은 파티션» 칸을 채운다.
#
# 이 단계가 답하는 것은 **정상 트래픽(재전송 없음)도 데드락이 나는가** 하나다. 로컬 라운드의
# 네 팔에는 그 칸이 없었고, 그 빈칸 탓에 조건 서술이 두 벌로 갈렸다(이슈 08-17 코멘트 ↔
# r276-deadlock-2026-08-20 README §0). rig 헤더 ㅁ 참고.
#
# 대조군 `same_partition` 을 **같은 라운드에서 같이** 돌린다 — 08-20 은 다른 박스·다른 날이라
# 절대 비율을 갖다 붙일 수 없다. 팔 비교의 기준선은 이 라운드 안에 있어야 한다.
phase_r276() {
  local out=$OUTDIR/r276
  mkdir -p "$out"

  # rig 은 자기 자리에서 `. ./.env` 를 읽고 `$CONTAINER` 로 MySQL 을 친다 — 부트스트랩이
  # 만든 .env 의 MYSQL_ROOT_PASSWORD 가 $PW 와 같아야 도는데, 그건 bootstrap.sh 가 맞춰준다.
  timeout $TIMEOUT_R276 env \
      ARMS="$R276_ARMS" ORDER="$R276_ORDER" ROUNDS="$R276_ROUNDS" WORKERS="$R276_WORKERS" \
      CONTAINER="$CONTAINER" OUT="$out" \
      bash "$ROOT/loadtest/measure_r276_deadlock.sh" > "$out/run.log" 2>&1
  local rc=$?

  # 🔴 잠금 원문은 **판이 끝난 직후에만** 있다 — 다음 데드락이 덮어쓴다. 08-20 라운드는
  #    이걸 손으로 떴는데, 무인 라운드에서 손은 없다. rc 와 무관하게 뜬다(실패한 판일수록 필요하다).
  docker exec -i -e MYSQL_PWD=$PW $CONTAINER mysql -uroot $DB_NAME \
      -e "SHOW ENGINE INNODB STATUS\G" > "$out/innodb-status.txt" 2>"$out/_stderr.log"

  if [ $rc -ne 0 ]; then
    note "🔴 #276 라운드 rc=$rc — run.log 를 볼 것 (표가 찍혔는지는 summary.md 로 판단)"
    return 1
  fi
  note "#276 라운드 완료 — 팔 «$R276_ARMS» · $R276_ROUNDS판 · 순서 $R276_ORDER"
  return 0
}

# #276 ② — 상한이 동시성을 어디까지 버티나 (앱 경로)
phase_r276app() {
  local out=$OUTDIR/r276app
  mkdir -p "$out"

  # ghz — p6-target 에는 안 깔린다. 없으면 여기서 받는다(부트스트랩 재실행은 .env 를 다시 써서
  # Spring 이 들고 있는 토큰과 어긋나게 만든다).
  if ! command -v ghz >/dev/null 2>&1 && [ ! -x /usr/local/bin/ghz ]; then
    note "ghz 가 없다 — 릴리스를 받는다 (v${GHZ_VERSION:-0.120.0})"
    case "$(uname -m)" in x86_64) _a=x86_64 ;; aarch64) _a=arm64 ;; *) _a="" ;; esac
    if [ -n "$_a" ] && curl -fsSL \
        "https://github.com/bojand/ghz/releases/download/v${GHZ_VERSION:-0.120.0}/ghz-linux-${_a}.tar.gz" \
        -o /tmp/ghz.tgz && tar -xzf /tmp/ghz.tgz -C /tmp ghz; then
      install -m 0755 /tmp/ghz /usr/local/bin/ghz
    else
      note "🔴 ghz 설치 실패 — 이 단계는 잴 것이 없다"
      return 1
    fi
  fi

  timeout $TIMEOUT_R276APP env \
      LEVELS="$R276APP_LEVELS" REQS="$R276APP_REQS" BLOCKS="$R276APP_BLOCKS" \
      SESSIONS="$R276APP_SESSIONS" RETRY_ARMS="$R276APP_RETRY_ARMS" \
      BACKOFF_ARMS="$R276APP_BACKOFF_ARMS" \
      CONTAINER="$CONTAINER" DB_NAME="$DB_NAME" PW="$PW" OUT="$out" \
      bash "$ROOT/loadtest/measure_r276_app_retry.sh" > "$out/run.log" 2>&1
  local rc=$?

  # 🔴 백엔드 로그도 걷는다 — 데드락 재시도는 WARN 으로 남고, 지표와 어긋나면 그 로그가 심판이다.
  docker logs --tail 2000 shadowfit-backend > "$out/backend.log" 2>&1

  if [ $rc -ne 0 ]; then
    note "🔴 #276 ② 라운드 rc=$rc — run.log 를 볼 것"
    return 1
  fi
  note "#276 ② 라운드 완료 — 레벨 «$R276APP_LEVELS» · 판당 $R276APP_REQS 요청"
  return 0
}

# 從 R8 후속 — 유니크 키의 대가를 «버퍼풀을 넘긴 규모» 에서 (재고표 2번)
phase_ukbp() {
  local out=$OUTDIR/ukbp
  mkdir -p "$out"

  timeout $TIMEOUT_UKBP env \
      POOL_MB="$UKBP_POOL_MB" SEED_ROWS="$UKBP_SEED_ROWS" \
      INSERT_ROWS="$UKBP_INSERT_ROWS" BLOCKS="$UKBP_BLOCKS" \
      CONTAINER="$CONTAINER" DB_NAME="$DB_NAME" PW="$PW" OUT="$out" \
      bash "$ROOT/loadtest/measure_uk_bufferpool.sh" > "$out/run.log" 2>&1
  local rc=$?

  # 🔴 게이트 결과를 단계 로그에도 올린다 — summary.md 안에만 있으면 «표가 있으니 됐다» 로 읽힌다.
  grep -E "게이트|성립 안 함" "$out/run.log" | tail -5 | while read -r l; do note "  $l"; done

  if [ $rc -ne 0 ]; then
    note "🔴 uk-bufferpool 라운드 rc=$rc — run.log 를 볼 것"
    return 1
  fi
  note "uk-bufferpool 라운드 완료 — 풀 ${UKBP_POOL_MB}MB · 무대 ${UKBP_SEED_ROWS}행 · 판당 ${UKBP_INSERT_ROWS}행"
  return 0
}

phase_httpwrite() {
  local out=$OUTDIR/httpwrite
  mkdir -p "$out"

  # 🔴 이 단계는 **부하기에서** 돈다 — 대상이 원격이어야 판정선에 댈 수 있다.
  #    TARGET_HOST 가 없으면 localhost 를 치게 되는데, 그러면 읽기축 로컬 판을
  #    비싸게 되풀이하는 것뿐이라 여기서 멈춘다.
  if [ -z "$TARGET_HOST" ]; then
    note "🔴 TARGET_HOST 가 없다 — 이 단계의 존재 이유가 «부하기와 대상 분리» 다. 멈춘다"
    return 1
  fi

  if ! command -v k6 >/dev/null 2>&1 && [ ! -x /usr/local/bin/k6 ]; then
    note "🔴 k6 가 없다 — ROLE=p6-loader 부트스트랩을 안 거친 박스다 (bootstrap.sh 가 깐다)"
    return 1
  fi

  timeout $TIMEOUT_HTTPWRITE env       BASE="http://$TARGET_HOST:$HTTPW_PORT"       MULTS="$HTTPW_MULTS" DUR="$HTTPW_DUR" BLOCKS="$HTTPW_BLOCKS"       ACCOUNTS="$HTTPW_ACCOUNTS" EXERCISE_ID="$HTTPW_EXERCISE_ID"       K6_BIN="$(command -v k6 || echo /usr/local/bin/k6)" OUT="$out"       bash "$ROOT/loadtest/measure_http_write_p99.sh" > "$out/run.log" 2>&1
  local rc=$?

  # 대상의 백엔드 로그도 걷는다 — 지연이 튄 자리가 앱 쪽 사건과 겹치는지 볼 유일한 근거다.
  # 지표 한 장도 같이 걷는다: 이 판은 시작마다 AI 로 비동기 gRPC 를, 종료마다 outbox 를 만든다.
  # 높은 배수에서 서킷이 열렸거나 outbox 가 적체됐으면 그 팔의 p99 는 «그 상태의 값» 이라
  # 그렇게 적어야 한다(rig 머리 참고). 관리 포트라 대상 안에서만 보인다.
  if [ -n "$TARGET_SSH" ]; then
    $TARGET_SSH "docker logs --tail 2000 shadowfit-backend 2>&1" > "$out/target-backend.log" 2>&1 || true
    $TARGET_SSH "curl -s --max-time 10 http://127.0.0.1:9090/actuator/prometheus"       > "$out/target-prometheus.txt" 2>/dev/null || true
  fi

  if [ $rc -ne 0 ]; then
    note "🔴 HTTP 쓰기 p99 라운드 rc=$rc — run.log 를 볼 것 (게이트가 막았을 수 있다)"
    return 1
  fi
  note "HTTP 쓰기 p99 라운드 완료 — 배수 «$HTTPW_MULTS» · 판당 $HTTPW_DUR"
  return 0
}

# 조건 기록. «조건 없는 수치는 인용 불가» 라 이 파일이 없으면 측정도 반쪽이다.
phase_collect() {
  local m=$OUTDIR/MANIFEST.txt
  {
    echo "# 측정 조건 — $RUN_ID"
    echo "생성          : $(date -Is)"
    echo "커밋          : $(git -C "$ROOT" rev-parse HEAD 2>/dev/null) ($(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null))"
    echo "인스턴스 타입 : $(imds instance-type)"
    echo "인스턴스 ID   : $(imds instance-id)"
    echo "AZ / 리전     : $(imds placement/availability-zone) / $(imds placement/region)"
    echo "vCPU / RAM    : $(nproc) / $(awk '/MemTotal/ {printf "%.0fGB", $2/1048576}' /proc/meminfo 2>/dev/null)"
    echo "커널          : $(uname -r)"
    echo "디스크        : $(df -h "$ROOT" | awk 'NR==2 {print $2, "여유", $4}')"
    # 🔴 로컬에 MySQL 이 없는 라운드(P6 — 러너가 부하기다)에서는 빈칸이 남는다. 빈칸은
    #    «0» 이나 «못 걷었다» 로 읽히므로 **왜 없는지**를 적는다 (_rig.sh 의 FAIL≠0 규약).
    if docker inspect "$CONTAINER" >/dev/null 2>&1; then
      echo "MySQL         : $(docker exec -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot -N -e 'SELECT VERSION();' 2>/dev/null | tr -d '\r')"
      echo "버퍼풀        : $(docker exec -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot -N -e "SELECT @@innodb_buffer_pool_size;" 2>/dev/null | tr -d '\r')"
    else
      echo "MySQL         : 해당 없음 — 이 박스에 $CONTAINER 가 없다(러너가 측정 대상이 아닌 라운드)"
    fi
    echo "WRITER_MAX_SEC: $WRITER_MAX_SEC"
    # 🔴 #198 — 이 한 줄이 없어서 08-12 라운드를 회수할 때 버킷 이름을 사람에게 물어야 했다.
    #    러너 로그(`/root/run_all.log`)에도 찍히지만 그건 $OUTDIR 밖이라 S3 로 안 올라가고
    #    인스턴스와 함께 죽는다. **살아남는 파일에 적어야 한다.**
    echo "S3 결과       : $S3_DEST"

    # 🔴 P6 라운드에서는 **위 블록이 부하기의 스펙**이다. 동거 용량은 「이 박스에 몇 세션이
    #    사는가」라서, 대상 박스를 안 적으면 매니페스트가 조용히 다른 기계를 조건으로 박제한다.
    #    조건 없는 수치는 이 프로젝트에서 인용 불가이고, 틀린 조건은 그보다 나쁘다.
    if [ -n "$TARGET_SSH" ]; then
      echo
      echo "# 대상 박스 (측정 대상 — 위 블록은 «부하기» 다)"
      echo "호스트        : $TARGET_HOST"
      echo "인스턴스 타입 : $($TARGET_SSH "TOK=\$(curl -sf --max-time 3 -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60'); curl -sf --max-time 3 -H \"X-aws-ec2-metadata-token: \$TOK\" http://169.254.169.254/latest/meta-data/instance-type" 2>/dev/null | tr -d '\r')"
      echo "vCPU / RAM    : $($TARGET_SSH "nproc; awk '/MemTotal/ {printf \"%.0fGB\", \$2/1048576}' /proc/meminfo" 2>/dev/null | tr '\n' ' ' | tr -d '\r')"
      echo "컨테이너 캡   :"
      $TARGET_SSH "docker ps --format '{{.Names}}' | while read -r c; do
          printf '  %-22s mem=%s cpus=%s\n' \"\$c\" \
            \"\$(docker inspect -f '{{.HostConfig.Memory}}' \$c)\" \
            \"\$(docker inspect -f '{{.HostConfig.NanoCpus}}' \$c)\"; done" 2>/dev/null | tr -d '\r'
      echo "동거 팔       : ARMS='$CORES_ARMS' LEVELS='$CORES_LEVELS' DUR=${CORES_DUR}s REPEATS=$CORES_REPEATS"
      echo "동거 배열     : LEVEL_SHIFT=$CORES_LEVEL_SHIFT (레벨 순서 치환 #252) · ANCHOR=$CORES_ANCHOR ${CORES_ANCHOR_ARM}@${CORES_ANCHOR_LEVEL}세션"
      echo "자기 프로브   : PROBE=$CORES_PROBE (대상 박스 1세션 · 계정 prefix '$CORES_PROBE_PREFIX') — 설계 §4-2"
      echo "코어 팔(§T)   : TASKSET=$CORES_TASKSET · ${CORES_TASKSET_CPUS}코어↔전체 · 팔 $CORES_TASKSET_ARM · 레벨 '$CORES_TASKSET_LEVELS' × ${CORES_TASKSET_REPS}쌍"
      echo "⚠️ 위 «캡» 은 라운드 **종료 시점** 값이다 — 스윕이 팔마다 갈아끼우므로 마지막 팔의 상태다"
    fi

    # 🔴 P4 라운드의 조건은 **두 박스가 같은 기계인가** 다. 다르면 관측된 지연이
    #    「복제 구조 때문」인지 「기계 차이 때문」인지 원리적으로 안 갈린다(설계 §3).
    #    그 판정을 나중에 하려면 리플리카 쪽 스펙이 여기 남아 있어야 한다.
    if [ -n "$REPLICA_HOST" ]; then
      echo
      echo "# 리플리카 박스 (위 블록은 «소스» 다)"
      echo "호스트        : $REPLICA_HOST"
      echo "AZ 구성(라벨) : ${REPL_AZ_MODE:-(미기입) — 사람이 채울 것. 이 값 없이는 Q2 를 해석할 수 없다}"
      if [ -n "$REPLICA_SSH" ]; then
        echo "인스턴스 타입 : $($REPLICA_SSH "TOK=\$(curl -sf --max-time 3 -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60'); curl -sf --max-time 3 -H \"X-aws-ec2-metadata-token: \$TOK\" http://169.254.169.254/latest/meta-data/instance-type" 2>/dev/null | tr -d '\r')"
        echo "AZ            : $($REPLICA_SSH "TOK=\$(curl -sf --max-time 3 -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60'); curl -sf --max-time 3 -H \"X-aws-ec2-metadata-token: \$TOK\" http://169.254.169.254/latest/meta-data/placement/availability-zone" 2>/dev/null | tr -d '\r')"
        echo "vCPU / RAM    : $($REPLICA_SSH "nproc; awk '/MemTotal/ {printf \"%.0fGB\", \$2/1048576}' /proc/meminfo" 2>/dev/null | tr '\n' ' ' | tr -d '\r')"
      else
        echo "인스턴스 타입 : (SSH 없음 — 못 걷었다)"
      fi
      echo "MySQL         : $(docker exec -i "$CONTAINER" mysql -h "$REPLICA_HOST" -P 3306 --get-server-public-key -uroot -p"$PW" -N -B -e 'SELECT VERSION();' 2>/dev/null | tr -d '\r')"
      echo "server_id     : 소스=$(docker exec -e MYSQL_PWD="$PW" "$CONTAINER" mysql -uroot -N -e 'SELECT @@server_id;' 2>/dev/null | tr -d '\r') / 리플리카=$(docker exec -i "$CONTAINER" mysql -h "$REPLICA_HOST" -P 3306 --get-server-public-key -uroot -p"$PW" -N -B -e 'SELECT @@server_id;' 2>/dev/null | tr -d '\r')"
      # 🔴 `_out2` 를 붙이지 않는다. 이 단계는 rig 에 `OUT=$OUTDIR/repl` 을 넘기므로
      #    산출물이 `repl/` 바로 아래에 떨어진다. `_out2` 는 rig 를 손으로 돌릴 때의
      #    기본값이다 (2026-08-22 리뷰 지적, PR #348).
      echo "왕복·초기화   : repl/rtt.txt · repl/replica_build.txt 참조"
      echo "⚠️ 두 박스의 타입이 다르면 이 라운드의 지연 값은 **복제 구조의 값이 아니다**"
    fi

    echo
    echo "# 단계"
    cat "$PHASE_LOG"
    echo
    echo "⚠️ 절대 소요 시간을 «운영에서 N분» 으로 인용 금지 — 하드웨어 종속(설계 §5)"
    echo "⚠️ rehearsal/ 의 수치는 측정값이 아니다 — 경로 점검용 축소 판"
  } > "$m"
  cat "$m"
  return 0
}

# ── 실행 ─────────────────────────────────────────────────────────────────
say "무인 측정 — $RUN_ID"
note "결과   : $OUTDIR"
note "S3     : $S3_DEST"
note "단계   : $PHASES"
note "자동정지: $([ "$AUTO_SHUTDOWN" = "1" ] && echo "켜짐" || echo "꺼짐")"

start_syncer

for p in $PHASES; do
  case $p in
    preflight)
      run_phase preflight phase_preflight || {
        note "🔴 사전 확인 실패 — 여기서 멈춘다. 이 상태로 돌리면 «환경 결함» 이 «측정 결과» 로 찍힌다"
        break
      } ;;
    rehearsal)
      run_phase rehearsal phase_rehearsal || {
        note "🔴 리허설 실패 — 본 측정으로 넘어가지 않는다. 리허설의 존재 이유가 이것이다"
        break
      } ;;
    ddl)       run_phase ddl       phase_ddl ;;
    backup_rehearsal)
      # 🔴 `break` 는 안 된다 — 뒤에 오는 從 항목·collect 까지 버릴 이유는 없다.
      #    `continue` 도 안 된다 — 바로 다음이 `backup` 이면 그대로 본 측정에 들어간다.
      #    플래그로 **그 단계만** 막는다.
      if run_phase backup_rehearsal phase_backup_rehearsal; then
        BACKUP_REHEARSAL_OK=1
      else
        BACKUP_REHEARSAL_OK=0
        note "🔴 백업 리허설 실패 — 본 측정을 건너뛴다. 리허설의 존재 이유가 이것이다"
      fi ;;
    backup)
      if [ "${BACKUP_REHEARSAL_OK:-1}" = "1" ]; then
        run_phase backup phase_backup
      else
        note "⏭  백업 본 측정 건너뜀 — 리허설이 실패했다(환경 결함이 측정 결과로 찍히면 안 된다)"
        printf "backup\tSKIP\t0\t%s\n" "$(date -Is)" >> "$PHASE_LOG"
      fi ;;
    backup_real)
      # 리허설 판정을 그대로 따른다 — 같은 rig 를 쓰므로 리허설이 깨졌으면 이것도 못 믿는다.
      if [ "${BACKUP_REHEARSAL_OK:-1}" = "1" ]; then
        run_phase backup_real phase_backup_real
      else
        note "⏭  real 대조 건너뜀 — 리허설이 실패했다"
        printf "backup_real\tSKIP\t0\t%s\n" "$(date -Is)" >> "$PHASE_LOG"
      fi ;;
    repl_preflight)
      run_phase repl_preflight phase_repl_preflight || {
        note "🔴 사전 확인 실패 — 여기서 멈춘다. 이 상태로 돌리면 «환경 결함» 이 «측정 결과» 로 찍힌다"
        break
      } ;;
    repl_gate)
      # backup 리허설과 같은 형태: `break` 도 `continue` 도 안 된다. 플래그로 그 단계만 막는다.
      if run_phase repl_gate phase_repl_gate; then
        REPL_GATE_OK=1
      else
        REPL_GATE_OK=0
        note "🔴 게이트 실패 — 본 측정을 건너뛴다. 계측이 안 선 채로 잰 지연은 «복제의 성질» 이 아니라 «무대의 결함» 이다"
      fi ;;
    repl)
      if [ "${REPL_GATE_OK:-1}" = "1" ]; then
        run_phase repl phase_repl
      else
        note "⏭  복제 본 측정 건너뜀 — 게이트가 실패했다"
        printf "repl\tSKIP\t0\t%s\n" "$(date -Is)" >> "$PHASE_LOG"
      fi ;;
    coresidency_preflight)
      run_phase coresidency_preflight phase_coresidency_preflight || {
        note "🔴 사전 확인 실패 — 여기서 멈춘다. 이 상태로 돌리면 «환경 결함» 이 «측정 결과» 로 찍힌다"
        break
      } ;;
    coresidency_rehearsal)
      # backup 과 같은 형태: `break` 도 `continue` 도 안 된다. 플래그로 **그 단계만** 막는다.
      if run_phase coresidency_rehearsal phase_coresidency_rehearsal; then
        CORES_REHEARSAL_OK=1
      else
        CORES_REHEARSAL_OK=0
        note "🔴 동거 리허설 실패 — 본 측정을 건너뛴다. 세션이 안 열리는 채로 도는 것이 이 rig 의 최악이다"
      fi ;;
    coresidency)
      if [ "${CORES_REHEARSAL_OK:-1}" = "1" ]; then
        run_phase coresidency phase_coresidency
      else
        note "⏭  동거 본 측정 건너뜀 — 리허설이 실패했다"
        printf "coresidency\tSKIP\t0\t%s\n" "$(date -Is)" >> "$PHASE_LOG"
      fi ;;
    framepath)
      run_phase framepath phase_framepath || {
        note "🔴 프레임 경로 라운드 실패 — 게이트가 막았거나 rig 이 죽었다. run_arms.log 를 볼 것"
      } ;;
    r276)      run_phase r276      phase_r276 ;;
    r276app)   run_phase r276app   phase_r276app ;;
    ukbp)      run_phase ukbp      phase_ukbp ;;
    httpwrite) run_phase httpwrite phase_httpwrite ;;
    ridealong) run_phase ridealong phase_ridealong ;;
    collect)   run_phase collect   phase_collect ;;
    *)         note "알 수 없는 단계 '$p' — 건너뛴다" ;;
  esac
done

# ── 마무리 ───────────────────────────────────────────────────────────────
say "최종 업로드"
stop_syncer

# 매니페스트 안의 단계 표는 collect 가 돌던 «그 순간» 의 스냅샷이라 자기 행이 빠져 있다.
# 끝난 뒤의 최종본을 뒤에 붙인다 — 인용할 때 보는 파일이 반쪽이면 안 된다.
if [ -f "$OUTDIR/MANIFEST.txt" ]; then
  { echo; echo "# 단계 (최종)"; cat "$PHASE_LOG"; } >> "$OUTDIR/MANIFEST.txt"
fi

# ── 요금 ─────────────────────────────────────────────────────────────────
#
# 실제 청구액은 인스턴스 안에서 알 수 없다(단가를 모른다). 대신 **곱해야 할 것들**을
# 남긴다 — 타입 · 가동 시간 · 볼륨. 청구액은 나중에 Cost Explorer 에서 태그로 뽑아
# 이 칸에 채운다. 이 repo 에 EC2 요금 기록이 한 줄도 없어서 «AWS 실측 얼마 드나» 에
# 추정으로밖에 답할 수 없었다. 그 칸을 여기서 연다.
RUN_SEC=$(( $(date +%s) - RUN_T0 ))
UP_SEC=$(awk '{printf "%d", $1}' /proc/uptime 2>/dev/null || echo 0)
{
  echo
  echo "# 요금 (청구액은 나중에 채운다)"
  echo "인스턴스 타입   : $(imds instance-type)"
  echo "리전            : $(imds placement/region)"
  echo "태그            : $(imds tags/instance | tr '\n' ' ')"
  echo "인스턴스 가동   : $(awk -v s="$UP_SEC" 'BEGIN{printf "%.2f", s/3600}')시간 (부팅부터 지금까지 — 요금 대상은 이쪽)"
  echo "러너 소요       : $(awk -v s="$RUN_SEC" 'BEGIN{printf "%.2f", s/3600}')시간"
  echo "루트 볼륨       : $(lsblk -dno SIZE,TYPE 2>/dev/null | head -1)"
  echo "실제 청구액     : (미기입) — Cost Explorer 에서 태그 Project=shadowfit-measure 로 필터해 채울 것"
  echo
  echo "⚠️ 인스턴스를 끈 뒤에도 **볼륨이 남으면 요금이 계속 나간다.** 삭제까지 확인할 것"
} >> "$OUTDIR/MANIFEST.txt"

if sync_s3; then
  FINAL_OK=1; note "✅ $S3_DEST"
else
  FINAL_OK=0; note "🔴 최종 업로드 실패"
fi

say "단계 요약"
cat "$PHASE_LOG"

# 🔴 업로드가 실패했으면 **절대 안 끈다.** 인스턴스 안에만 있는 결과를 끄는 건
#    측정을 통째로 버리는 것과 같다. 사람이 와서 회수해야 한다.
if [ "$AUTO_SHUTDOWN" = "1" ] && [ "$FINAL_OK" = "1" ]; then
  # 🔴 **리플리카를 먼저 끈다.** 2대 라운드(P4)에서 이 스크립트는 소스 박스에서 돌고,
  #    소스를 먼저 끄면 리플리카를 끌 SSH 가 사라져 **그 박스가 켜진 채로 남는다.**
  #    결과는 이미 S3 에 올라갔고(FINAL_OK) 리플리카에는 산출물이 없으므로 꺼도 잃을 것이 없다.
  #    ⚠️ 업로드가 실패한 판(아래 elif)에서는 리플리카도 안 끈다 — 무대를 들여다볼 수 있어야 한다.
  if [ -n "$REPLICA_HOST" ]; then
    if $REPLICA_SSH "shutdown -h +1 '측정 종료 — run_all.sh (소스가 껐다)'" >/dev/null 2>&1; then
      note "리플리카($REPLICA_HOST)에 정지를 걸었다 — 60초 후"
    else
      note "🔴 리플리카($REPLICA_HOST)를 못 껐다 — **직접 정지할 것.** 소스만 꺼지면 그 박스는 켜진 채 요금이 붙는다"
    fi
  fi
  note "60초 후 정지한다 (취소: pkill -f 'shutdown')"
  shutdown -h +1 "측정 종료 — run_all.sh"
elif [ "$AUTO_SHUTDOWN" = "1" ]; then
  note "⚠️ 자동 정지가 켜져 있지만 업로드가 실패해서 **끄지 않는다.** 결과가 이 인스턴스에만 있다"
  if [ -n "$REPLICA_HOST" ]; then
    note "   리플리카($REPLICA_HOST)도 안 끈다 — 무대를 들여다볼 수 있어야 한다. 회수한 뒤 **두 대 다** 직접 정지할 것"
  fi
else
  note "자동 정지 꺼짐 — 회수 확인 후 직접 정지할 것. AWS-RIDE-ALONG.md §5 체크리스트를 볼 것"
  # 🔴 `&&` 로 쓰지 말 것 — 이 줄이 스크립트의 **마지막 명령**이라, 1대 라운드에서 조건이
  #    거짓이면 run_all.sh 가 **종료코드 1** 로 끝난다(성공한 판인데도).
  if [ -n "$REPLICA_HOST" ]; then
    note "   🔴 **두 대다.** 리플리카($REPLICA_HOST)를 잊지 말 것 — 산출물이 없어서 눈에 안 띈다"
  fi
fi