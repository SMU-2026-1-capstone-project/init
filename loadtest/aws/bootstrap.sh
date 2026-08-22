#!/bin/bash
# EC2 부트스트랩 — 빈 인스턴스를 «측정 가능한 상태» 로 만든다. 측정은 하지 않는다.
#
# 측정은 run_all.sh 가 한다. 둘을 나눈 이유: 부트스트랩이 실패하면 측정을 시작하면 안 되고,
# 측정이 실패해도 부트스트랩을 다시 할 필요는 없기 때문이다.
#
# 🔴 **root 로 돌린다** (`sudo -i` 후 실행). docker 그룹에 유저를 넣는 방식은 재로그인이
#    필요한데, 무인 실행에서 그 재로그인이 빠지면 rig 의 `docker` 호출이 통째로 실패한다.
#    「권한을 줬는데 왜 안 되지」로 밤을 날리는 대표 함정이라 아예 피한다.
#
# 사용:
#   sudo -i
#   curl -fsSL https://raw.githubusercontent.com/Shadowfit/init/main/loadtest/aws/bootstrap.sh -o bootstrap.sh
#   bash bootstrap.sh
#
# 전제:
#   · Amazon Linux 2023 또는 Ubuntu 22.04+
#   · 인스턴스 프로파일에 대상 S3 버킷 쓰기 권한 (run_all.sh 가 확인한다)

set -uo pipefail

REPO=${REPO:-https://github.com/Shadowfit/init.git}
REF=${REF:-main}                    # 측정 대상 커밋/브랜치. **측정 조건이므로 매니페스트에 남는다**
WORKDIR=${WORKDIR:-/root/init}
PW=${PW:-1234}                      # rig 기본 PW(_rig.sh). 바꾸면 run_all.sh 에도 같은 값을 넘겨야 한다
DB_NAME=${DB_NAME:-shadowfit}

# ── 역할 ─────────────────────────────────────────────────────────────────
#
# 🔴 P1(DDL)·P3(백업) 라운드는 MySQL 만 있으면 됐다. **P6(동거 용량)는 다르다** — 측정
#    대상이 «한 박스에 사는 세 컨테이너» 라서 Spring·AI 를 실제로 띄워야 하고, 부하를 거는
#    쪽은 아예 다른 박스다. 그래서 역할을 나눈다.
#
#   db        (기본) — MySQL + 스키마 + percona-toolkit. 기존 라운드 그대로. **동작 불변**
#   p6-target        — 위 + Spring·AI 빌드/기동 + 토큰·캡 + 세션 시드 (측정 대상 박스)
#   p6-loader        — python·ghz·페이로드 (부하를 거는 박스. run_all.sh 가 여기서 돈다)
ROLE=${ROLE:-db}

# p6-target 전용. 값의 근거는 아래 각 자리에 적는다 — 근거 없는 숫자를 .env 에 박지 않는다.
AI_MEM_LIMIT=${AI_MEM_LIMIT:-20000m}
POSE_DETECTOR_POOL_SIZE=${POSE_DETECTOR_POOL_SIZE:-160}
# 팔 C 의 CPU 캡 (2026-08-16 사용자 결정). 근거는 «비율» 이지 «절대값» 이 아니다:
#   c7i.4xlarge = **16 vCPU**(물리 8코어 × HT) 를 AI:MySQL:Spring = 4:2:2 로 **전부** 나눈다.
#   🔴 docker 의 `cpus:` 는 vCPU 단위다. 물리 코어 수(8)로 착각해 4/2/2 를 넣으면 합이 8 이라
#      **박스 절반이 노는 조건**이 되고, 팔 B↔C 차이에 «캡 효과» 와 «절반 효과» 가 섞인다.
#   ⚠️ 이 비율 자체는 실측이 아니라 정한 값이다 — #212 가 «근거 없음» 으로 열어둔 그 자리이고,
#      이번 팔 B↔C 대조가 그 값에 처음으로 근거를 붙이려는 시도다. 결과에 그대로 적는다.
AI_CPUS=${AI_CPUS:-8}
MYSQL_CPUS=${MYSQL_CPUS:-4}
BACKEND_CPUS=${BACKEND_CPUS:-4}
AI_PUBLIC_TOKEN=${AI_PUBLIC_TOKEN:-}
INTERNAL_API_TOKEN=${INTERNAL_API_TOKEN:-}

# p6-loader 전용
GHZ_VERSION=${GHZ_VERSION:-0.120.0}
GHZ_SESSIONS=${GHZ_SESSIONS:-901-1900}   # seed-multi-sessions.sql 과 **같은 범위여야** 한다
GHZ_REPS=${GHZ_REPS:-25}

step() { echo; echo "──── $* ────"; }
die()  { echo; echo "🔴 부트스트랩 중단 — $*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "root 로 돌려야 한다 (sudo -i). 위 주석의 docker 그룹 함정 참고"

# ── 패키지 ───────────────────────────────────────────────────────────────
step "패키지"
if command -v dnf >/dev/null 2>&1; then
  dnf -y install docker git awscli-2 tar >/dev/null || die "dnf 설치 실패"
  # AL2023 은 compose 플러그인을 따로 받는다
  if ! docker compose version >/dev/null 2>&1; then
    mkdir -p /usr/libexec/docker/cli-plugins
    curl -fsSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" \
      -o /usr/libexec/docker/cli-plugins/docker-compose || die "compose 플러그인 내려받기 실패"
    chmod +x /usr/libexec/docker/cli-plugins/docker-compose
  fi
  # 🔴 compose v2 의 `build` 는 **buildx 를 따로 요구**한다("requires buildx 0.17.0 or later").
  #    AL2023 의 docker 패키지엔 없어서, 이미지를 빌드하는 역할(p6-target)이 여기서 죽는다.
  #    MySQL 만 띄우던 기존 라운드는 build 를 안 해서 이 구멍이 안 보였다 (2026-08-16 실측).
  #    ⚠️ «있는가» 가 아니라 «버전이 되는가» 로 물어야 한다. AL2023 은 buildx **0.12.1** 을
  #       이미 깔아 두므로 존재 검사만 하면 그대로 통과하고 build 에서 다시 죽는다
  #       (2026-08-16 에 실제로 두 번 죽었다 — 없어서 한 번, 낡아서 한 번).
  BX_VER=$(docker buildx version 2>/dev/null | grep -oE 'v?[0-9]+\.[0-9]+\.[0-9]+' | head -1 | tr -d v)
  BX_OK=$(awk -v v="${BX_VER:-0.0.0}" 'BEGIN{split(v,a,"."); print (a[1]>0 || a[2]>=17) ? 1 : 0}')
  if [ "$BX_OK" != "1" ]; then
    echo "  buildx ${BX_VER:-없음} → 0.19.3 으로 올린다 (compose build 는 0.17+ 를 요구한다)"
    mkdir -p /usr/libexec/docker/cli-plugins
    case "$(uname -m)" in x86_64) BX_ARCH=amd64 ;; aarch64) BX_ARCH=arm64 ;; *) BX_ARCH="" ;; esac
    [ -n "$BX_ARCH" ] && curl -fsSL \
      "https://github.com/docker/buildx/releases/download/v0.19.3/buildx-v0.19.3.linux-${BX_ARCH}" \
      -o /usr/libexec/docker/cli-plugins/docker-buildx \
      && chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
  fi
elif command -v apt-get >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq || die "apt update 실패"
  apt-get install -y -qq docker.io docker-compose-v2 git awscli >/dev/null || die "apt 설치 실패"
else
  die "지원하는 패키지 관리자를 못 찾았다 (dnf/apt 만 안다)"
fi

systemctl enable --now docker >/dev/null 2>&1 || die "docker 데몬을 못 띄웠다"
docker info >/dev/null 2>&1 || die "docker 가 응답하지 않는다"

# ── 저장소 ───────────────────────────────────────────────────────────────
step "저장소 — $REPO @ $REF"
if [ -d "$WORKDIR/.git" ]; then
  git -C "$WORKDIR" fetch --all -q && git -C "$WORKDIR" checkout -q "$REF" && git -C "$WORKDIR" pull -q --ff-only 2>/dev/null
else
  git clone -q "$REPO" "$WORKDIR" || die "clone 실패 (public repo 라 인증은 필요 없다 — 네트워크를 볼 것)"
  git -C "$WORKDIR" checkout -q "$REF" || die "$REF 체크아웃 실패"
fi
echo "  커밋: $(git -C "$WORKDIR" rev-parse --short HEAD)"

# ── .env ─────────────────────────────────────────────────────────────────
#
# 🔴 rig 의 기본 PW 와 compose 의 MYSQL_ROOT_PASSWORD 가 어긋나면 **전 판이 실패한다.**
#    여기서 한 곳에서 만들어 그 어긋남을 없앤다.
step ".env"
#
# 🔴 `compose up mysql` 이어도 **compose 는 파일 전체를 해석한다.** 그래서 우리가 안 띄우는
#    서비스의 필수 변수까지 있어야 한다 — `docker-compose.yml:220` 의
#    `MYSQL_EXPORTER_PASSWORD:?` 가 없으면 mysql 하나 띄우는 것도 실패한다.
#    첫 EC2 실행(2026-08-13)이 정확히 여기서 죽었다.
cat > "$WORKDIR/.env" <<EOF
MYSQL_DATABASE=$DB_NAME
MYSQL_USER=shadowfit
MYSQL_ROOT_PASSWORD=$PW
MYSQL_PASSWORD=$PW
MYSQL_PORT=3306
MYSQL_EXPORTER_PASSWORD=$PW
DB_USERNAME=shadowfit
DB_PASSWORD=$PW
EOF
echo "  MYSQL_ROOT_PASSWORD 를 rig 기본값과 맞췄다 (+ 해석만 되면 되는 변수들)"

if [ "$ROLE" = "p6-target" ]; then
  # 토큰은 없으면 만든다 — 값 자체는 아무 문자열이어도 되지만 **양쪽이 같아야** 한다.
  [ -n "$AI_PUBLIC_TOKEN" ]    || AI_PUBLIC_TOKEN=$(head -c 24 /dev/urandom | base64 | tr -d '/+=')
  [ -n "$INTERNAL_API_TOKEN" ] || INTERNAL_API_TOKEN=$(head -c 24 /dev/urandom | base64 | tr -d '/+=')
  cat >> "$WORKDIR/.env" <<EOF
AI_MEM_LIMIT=$AI_MEM_LIMIT
POSE_DETECTOR_POOL_SIZE=$POSE_DETECTOR_POOL_SIZE
AI_PUBLIC_TOKEN=$AI_PUBLIC_TOKEN
INTERNAL_API_TOKEN=$INTERNAL_API_TOKEN
AI_CPUS=$AI_CPUS
MYSQL_CPUS=$MYSQL_CPUS
BACKEND_CPUS=$BACKEND_CPUS
EOF
  # 🔴 메모리 한도가 검출기 «풀 크기» 를 정한다(mediapipe_detector.py:169-179):
  #        상한 = (한도MB − 100.5) / 98.7        ← 둘 다 M2 실측값
  #    한도가 작으면 최고 레벨에서 재는 것이 «CPU 경합» 이 아니라 «풀 자리 없음» 이 된다.
  #    여기서는 풀 크기를 **최고 레벨과 같게 명시**해 그 둘이 안 섞이게 한다.
  #    한도 자체의 여유분(프레임 버퍼·파이썬 힙은 미측정)은 «정한 값» 이다 — 조건에 적는다.
  awk -v lim="${AI_MEM_LIMIT%m}" 'BEGIN{ printf "  AI 메모리 한도 %sMB → 검출기 상한 %d 개 (풀은 명시값 %s)\n",
       lim, int((lim-100.5)/98.7), "'"$POSE_DETECTOR_POOL_SIZE"'" }'
  echo "  CPU 캡(팔 C·D 전용): AI=$AI_CPUS · MySQL=$MYSQL_CPUS · Spring=$BACKEND_CPUS vCPU"
  echo "     합 $(( AI_CPUS + MYSQL_CPUS + BACKEND_CPUS )) vCPU / 이 박스 $(nproc) vCPU  ← 남는 만큼이 «노는 조건» 이다"
  echo "     비율 4:2:2 는 «정한 값» 이다 (#212). 팔 B↔C 대조가 그 값에 근거를 붙이려는 시도다"
  echo "  AI_PUBLIC_TOKEN=$AI_PUBLIC_TOKEN"
  echo "  INTERNAL_API_TOKEN=$INTERNAL_API_TOKEN"
  echo "  🔴 위 두 토큰을 부하기의 run_all.sh 에 그대로 넘긴다 — 다르면 401/전 요청 실패다"
fi

# ── 부하기 (p6-loader) ───────────────────────────────────────────────────
#
# 이 박스는 **측정 대상이 아니다.** MySQL·스키마·percona 가 필요 없고, 대신 부하를 만들
# 도구가 있어야 한다. run_all.sh 도 여기서 돈다(그래서 S3 쓰기 권한이 이 인스턴스에 붙어야 한다).
if [ "$ROLE" = "p6-loader" ]; then
  step "부하기 도구"

  # 🔴 rig 은 «python» 을 부른다(probe.sh · coresidency_sweep.sh). AL2023 은 python3 만 있다.
  #    이걸 안 걸면 게이트가 통째로 죽는데, 증상은 «검출 실패» 처럼 보인다.
  command -v python3 >/dev/null 2>&1 || {
    if command -v dnf >/dev/null 2>&1; then dnf -y install python3 >/dev/null; else apt-get install -y -qq python3 >/dev/null; fi
  }
  command -v python >/dev/null 2>&1 || ln -sf "$(command -v python3)" /usr/local/bin/python
  echo "  python → $(python -V 2>&1)"

  # ghz 는 릴리스 바이너리를 받는다 — go 툴체인을 깔면 부트스트랩이 몇 분 더 길어진다.
  GHZ_BIN=/usr/local/bin/ghz
  if [ ! -x "$GHZ_BIN" ]; then
    case "$(uname -m)" in
      x86_64)  GHZ_ARCH=x86_64 ;;
      aarch64) GHZ_ARCH=arm64 ;;
      *) die "ghz 릴리스에 없는 아키텍처: $(uname -m)" ;;
    esac
    curl -fsSL "https://github.com/bojand/ghz/releases/download/v${GHZ_VERSION}/ghz-linux-${GHZ_ARCH}.tar.gz" \
      -o /tmp/ghz.tgz || die "ghz 내려받기 실패"
    tar -xzf /tmp/ghz.tgz -C /tmp ghz || die "ghz 압축 해제 실패"
    install -m 0755 /tmp/ghz "$GHZ_BIN" || die "ghz 설치 실패"
  fi
  echo "  ghz → $("$GHZ_BIN" --version 2>&1 | head -1)"

  # 從 부하 페이로드. 🔴 커밋돼 있지 않다(~54MB, .gitignore) — 여기서 만든다.
  #    세션 범위는 대상 박스의 시드와 **같아야** 한다. 다르면 전 요청이 FK 로 실패하는데
  #    ghz 표는 정상으로 보인다.
  step "從 부하 페이로드 — 세션 $GHZ_SESSIONS · reps $GHZ_REPS"
  python "$WORKDIR/loadtest/ghz/gen_batch_multi.py" \
    --sessions "$GHZ_SESSIONS" --reps "$GHZ_REPS" --out /root/batch_multi.json \
    || die "페이로드 생성 실패"
  echo "  /root/batch_multi.json ($(du -h /root/batch_multi.json | cut -f1))"

  # AI 부하기가 쓰는 프레임 자산은 **커밋돼 있다**(408KB) — 여기서 만들 수 없다(mediapipe 필요).
  [ -f "$WORKDIR/loadtest/results/coresidency-2026-08-15/frames.json" ] \
    && echo "  frames.json ✅ (커밋본)" \
    || die "frames.json 이 저장소에 없다 — ai-server 이미지 안에서 만들어 올려야 한다"

  step "부하기 준비됨"
  cat <<EOF
  작업 디렉터리 : $WORKDIR
  커밋          : $(git -C "$WORKDIR" rev-parse --short HEAD) ($REF)
  ghz           : $GHZ_BIN
  페이로드      : /root/batch_multi.json

  다음 — 대상 박스로 붙을 SSH 키를 이 박스에 두고(예: /root/.ssh/measure.pem, chmod 600),
  cd $WORKDIR && \\
  S3_BASE=s3://<버킷>/<프리픽스> TARGET_HOST=<대상 사설 IP> \\
  TARGET_SSH="ssh -i /root/.ssh/measure.pem -o StrictHostKeyChecking=no root@<대상 사설 IP>" \\
  AI_PUBLIC_TOKEN=<대상 .env 의 AI_PUBLIC_TOKEN> GHZ_TOKEN=<대상 .env 의 INTERNAL_API_TOKEN> \\
  GHZ_RPS=19 GHZ_DATA=/root/batch_multi.json GHZ_BIN=$GHZ_BIN \\
  CORES_ARMS="A B C" \\
  PHASES="coresidency_preflight coresidency_rehearsal coresidency collect" \\
    nohup bash loadtest/aws/run_all.sh > /root/run_all.log 2>&1 &

  ⚠️ nohup 없이 & 만 붙이면 SSH 가 끊길 때 같이 죽는다.
EOF
  exit 0
fi

# ── 컨테이너 ─────────────────────────────────────────────────────────────
step "MySQL 컨테이너"
cd "$WORKDIR" || die "$WORKDIR 로 못 들어간다"
docker compose up -d mysql || die "compose up 실패"

echo -n "  헬스체크 대기"
for _ in $(seq 1 60); do
  if docker exec shadowfit-mysql mysqladmin ping -h localhost --silent >/dev/null 2>&1; then
    echo " — 떴다"; break
  fi
  echo -n "."; sleep 5
done
docker exec shadowfit-mysql mysqladmin ping -h localhost --silent >/dev/null 2>&1 \
  || die "MySQL 이 5분 안에 안 떴다"

# ── 도구 이미지 ──────────────────────────────────────────────────────────
#
# 🔴 이게 없으면 팔 B 4판이 전부 «DDL실패» 로 찍힌다. 도구의 성질이 아니라 환경 결함인데
#    표에는 똑같이 보인다 — 그래서 측정 전에 여기서 받는다.
step "percona-toolkit 이미지"
docker pull -q percona/percona-toolkit || die "percona-toolkit pull 실패"

# 🔴 XtraBackup 은 **여기가 유일하게 «양쪽 박스» 를 거치는 지점**이다 (#368).
#    P3(백업)은 1대라 rig 이 스스로 받아 넘겼는데(`backup-restore-2026-08-13/probe.sh:105`),
#    P4(복제)는 2대다 — 소스에서 사본을 뜨고 **리플리카에서 그 사본을 붓는다**
#    (`replication-2026-08-17/repl2_rig.sh:509`, `RSSH docker run`). 로컬에만 받으면 절반만 덮인다.
#    두 박스가 다 이 부트스트랩을 거치므로 여기서 받으면 그 문제가 통째로 닫힌다.
#
#    없어도 «실패로 안 보이는» 것이 이 이미지의 성질이다 — 런타임 pull 로 넘어가거나
#    논리 덤프로 되돌아가고, 되돌림은 「성공」처럼 보인다. 그래서 측정 전에 받는다.
if [ "$ROLE" = "db" ]; then
  # ⚠️ 이름의 정본은 rig 이다(`repl2_rig.sh:60`). 이 스크립트는 run_all.sh 보다 **먼저 도는
  #    별개 실행**이라 그 값을 물려받을 길이 없어 기본값을 한 벌 더 갖는다 (#374).
  #    한쪽만 덮어쓰면 run_all.sh 의 게이트가 «이미지가 없다» 로 **막는다** — 조용히 안 어긋난다.
  XB_IMAGE=${XB_IMAGE:-percona/percona-xtrabackup:8.0}
  step "xtrabackup 이미지 (P3 백업 · P4 복제) — $XB_IMAGE"
  docker pull -q "$XB_IMAGE" || die "$XB_IMAGE pull 실패"
fi

# ── 스키마 (Flyway) ──────────────────────────────────────────────────────
#
# 🔴 08-12 라운드에서 從 항목 R1·R3 이 **둘 다 «측정 대상 부재» 로 죽었다.** 러너가 DDL 측정에
#    필요한 mysql 만 띄우고 백엔드를 안 올려서 `reports`·`exercise_sessions`·`users` 가
#    아예 없었기 때문이다. 인프라가 살아 있을 때만 잴 수 있는 항목이라 놓치면 다음 인스턴스까지
#    밀린다 (AWS-RIDE-ALONG.md §5 체크리스트 1번).
#
#    백엔드를 통째로 띄우지는 않는다 — Java·gradle 빌드가 붙으면 부트스트랩이 몇 배 느려지고,
#    측정에 필요한 건 «스키마» 지 «애플리케이션» 이 아니다. Flyway 이미지로 마이그레이션만 건다.
#
#    ⚠️ 이 단계는 DDL 측정 대상(`pose_data_scale`)을 건드리지 않는다. rig 가 만드는 테이블과
#       이름이 다르다. 다만 같은 스키마에 작은 테이블 십수 개가 생기므로, 그 사실을 조건으로
#       남긴다(run_all.sh 의 MANIFEST).
step "스키마 — Flyway 마이그레이션"
if [ "${SKIP_FLYWAY:-0}" = "1" ]; then
  echo "  SKIP_FLYWAY=1 — 건너뛴다. 從 R1·R3 은 «측정 대상 부재» 로 찍힌다"
else
  MIG="$WORKDIR/backend/src/main/resources/db/migration"
  [ -d "$MIG" ] || die "마이그레이션 디렉터리가 없다: $MIG"

  # host 네트워크로 붙는다 — compose 네트워크 이름에 의존하지 않기 위해서다.
  if docker run --rm --network host \
      -v "$MIG:/flyway/sql:ro" \
      flyway/flyway \
      -url="jdbc:mysql://127.0.0.1:3306/$DB_NAME?allowPublicKeyRetrieval=true&useSSL=false" \
      -user=root -password="$PW" -connectRetries=10 \
      migrate; then
    echo "  ✅ 스키마 생성됨 — 從 R1·R3 이 잴 대상이 생겼다"
  else
    # 🔴 여기서 die 하지 않는다. 主 P1/P3 은 rig 가 자기 테이블을 직접 만들어 쓰므로
    #    스키마가 없어도 돈다. 從 항목만 못 재는 것이라 «측정 전체를 막을» 사유는 아니다.
    #    다만 조용히 넘어가면 08-12 를 반복하므로 시끄럽게 남긴다.
    echo "  🔴 Flyway 실패 — 從 R1·R3 은 이번에도 «측정 대상 부재» 가 된다. 主 측정은 계속한다"
  fi
fi

# ── 측정 대상 스택 (p6-target) ───────────────────────────────────────────
#
# 🔴 기존 라운드가 백엔드를 안 띄운 것은 «측정에 필요한 건 스키마지 애플리케이션이 아니» 어서였다.
#    P6 는 반대다 — 측정 대상이 **세 컨테이너의 동거**라 셋 다 실제로 떠 있어야 한다.
#    빌드가 붙어 이 단계만 10~25분이다(gradle + mediapipe).
if [ "$ROLE" = "p6-target" ]; then
  step "Spring·AI 빌드 (10~25분)"
  cd "$WORKDIR" || die "$WORKDIR 로 못 들어간다"
  docker compose build shadowfit-backend shadowfit-ai || die "이미지 빌드 실패"

  step "스택 기동 — mysql · backend · ai"
  docker compose up -d mysql shadowfit-backend shadowfit-ai || die "compose up 실패"

  echo -n "  백엔드 헬스체크 대기"
  for _ in $(seq 1 60); do
    curl -sf --max-time 3 http://localhost:9090/actuator/health >/dev/null 2>&1 && { echo " — 떴다"; break; }
    echo -n "."; sleep 5
  done
  curl -sf --max-time 3 http://localhost:9090/actuator/health >/dev/null 2>&1 \
    || echo " ⚠️ 5분 안에 안 떴다 — 게이트 G2 가 다시 본다"

  echo -n "  AI 헬스체크 대기"
  for _ in $(seq 1 36); do
    curl -sf --max-time 3 http://localhost:8000/health >/dev/null 2>&1 && { echo " — 떴다"; break; }
    echo -n "."; sleep 5
  done
  curl -sf --max-time 3 http://localhost:8000/health >/dev/null 2>&1 \
    || echo " ⚠️ 3분 안에 안 떴다 — /health 는 검출기 풀을 안 건드린다(#214). G3 가 다시 본다"

  # ── 시드 ───────────────────────────────────────────────────────────────
  # 從 부하 페이로드는 세션 901~1900 을 쓴다. 그 행이 없으면 **전 요청이 FK 로 실패**하는데
  # ghz 의 요청 수·지연은 정상으로 찍힌다. 그래서 여기서 만들어 둔다.
  step "從 부하용 세션 시드 901~1900"
  M="docker exec -i -e MYSQL_PWD=$PW shadowfit-mysql mysql -uroot $DB_NAME"

  # 🔴 세션의 FK 대상인 member 1 이 먼저 있어야 한다. Flyway 는 `exercises` 마스터(V2)까지만
  #    넣고 users 는 안 넣는다(dev-seed 쪽 소관).
  # 🔴 `INSERT IGNORE` 를 쓰지 않는다 — 중복만 삼키는 게 아니라 FK 위반 행을 지우고
  #    NOT NULL 위반에 빈 값을 써 넣는다(#219). 존재 검사 후 넣는다.
  $M -e "INSERT INTO users (id, email, password, username, sex, role, selected_persona,
                            preferred_url, onboarding_completed)
         SELECT 1, 'loadtest@shadowfit.local', 'x', 'loadtest', 'MALE', 'USER', 'ADVANCED',
                'https://www.youtube.com/watch?v=q6hBSSis_60', TRUE
         FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = 1);" \
    || die "member 1 시드 실패 — 세션 시드가 FK 로 전부 죽는다"

  $M < "$WORKDIR/loadtest/seed/seed-multi-sessions.sql" \
    || die "세션 시드 실패 (seed-multi-sessions.sql)"

  SEEDED=$($M -N -e "SELECT COUNT(*) FROM exercise_sessions WHERE id BETWEEN 901 AND 1900;" | tr -d '[:space:]')
  [ "$SEEDED" = "1000" ] \
    || die "세션 시드가 1000개가 아니다 ($SEEDED) — 이대로면 從 부하의 일부가 조용히 FK 로 죽는다"
  echo "  exercise_sessions 901~1900 · $SEEDED 개 확인"
fi

# ── 요약 ─────────────────────────────────────────────────────────────────
step "준비됨"
cat <<EOF
  작업 디렉터리 : $WORKDIR
  커밋          : $(git -C "$WORKDIR" rev-parse --short HEAD) ($REF)
  디스크 여유   : $(df -h "$WORKDIR" | awk 'NR==2 {print $4}')
  MySQL         : $(docker exec shadowfit-mysql mysql -uroot -p"$PW" -N -e "SELECT VERSION();" 2>/dev/null | tr -d '\r')

다음:
  cd $WORKDIR
  S3_BASE=s3://<버킷>/<프리픽스> nohup bash loadtest/aws/run_all.sh > /root/run_all.log 2>&1 &

  ⚠️ nohup 없이 & 만 붙이면 SSH 가 끊길 때 같이 죽는다.
EOF