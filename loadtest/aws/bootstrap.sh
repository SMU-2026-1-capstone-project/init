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
#   ai-venv          — AI 를 **venv 로** 띄울 준비 (從 R10). 도커·MySQL·Spring 없다 —
#                      컨테이너로 띄우면 계측 노브가 안 넘어가서다(#399)
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

# ai-venv 전용. 🔴 **버전이 측정 조건이다** — ai-server/Dockerfile 이 python:3.12-slim 이고
#   R10 이 재는 것이 GIL 거동이라, 다른 버전으로 재면 비교가 성립하지 않는다.
AI_PY_VERSION=${AI_PY_VERSION:-3.12}
AI_PY=${AI_PY:-}                         # 인터프리터를 직접 줄 때 (배포판에 3.12 가 없는 경우)

# p6-loader 전용
GHZ_VERSION=${GHZ_VERSION:-0.120.0}
GHZ_SESSIONS=${GHZ_SESSIONS:-901-1900}   # seed-multi-sessions.sql 과 **같은 범위여야** 한다
GHZ_REPS=${GHZ_REPS:-25}

step() { echo; echo "──── $* ────"; }
die()  { echo; echo "🔴 부트스트랩 중단 — $*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "root 로 돌려야 한다 (sudo -i). 위 주석의 docker 그룹 함정 참고"

# 🔴 모르는 ROLE 은 **여기서 멈춘다.** 예전에는 오타가 조용히 기본값(db)으로 떨어져서,
#    「왜 AI 가 아니라 MySQL 박스지」를 10분 뒤에 알게 되는 자리였다. 역할이 넷이 되면서
#    그 확률이 올라간다.
case "$ROLE" in
  db|p6-target|p6-loader|ai-venv) ;;
  *) die "모르는 ROLE 이다: '$ROLE' — db · p6-target · p6-loader · ai-venv 중 하나여야 한다" ;;
esac

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

# 🔴 ai-venv 는 데몬을 **안 띄운다.** 그 박스가 답해야 하는 질문이 하필 「16 vCPU 중 얼마를
#    쓰나」이고, dockerd 는 **재려는 것과 같은 CPU 를 문다.** 이 역할은 컨테이너를 하나도
#    안 띄우므로(§ai-venv) 띄울 이유도 없다 — 조건에 안 적힌 상주 프로세스를 남기지 않는다.
if [ "$ROLE" = "ai-venv" ]; then
  systemctl disable --now docker >/dev/null 2>&1 || true
  echo "  ROLE=ai-venv — docker 데몬을 안 띄운다 (이 박스의 CPU 가 측정 대상이다)"
else
  systemctl enable --now docker >/dev/null 2>&1 || die "docker 데몬을 못 띄웠다"
  docker info >/dev/null 2>&1 || die "docker 가 응답하지 않는다"
fi

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

# ── AI 를 venv 로 (ai-venv) ───────────────────────────────────────────────
#
# 🔴 從 R10(프레임 경로 계측)이 쓰는 모양이다. **도커가 아니라 venv 인 이유는 하나** —
#    컨테이너로 띄우면 계측 노브(`FRAME_PATH_METRICS`·`GIL_SWITCH_INTERVAL`)가 안 넘어간다(#399).
#    MySQL·스키마·Spring 은 필요 없다. rig 이 uvicorn 을 직접 띄우고 gRPC 로 세션을 연다.
#
# 🔴 **파이썬 버전은 측정 조건이다.** 기준 관측(「346 RPS 에 9.5 vCPU」)이 난 컨테이너는
#    `python:3.12-slim`(ai-server/Dockerfile)이고, R10 이 재려는 것이 **GIL 거동**이다.
#    3.9 나 3.11 로 재면 «다른 인터프리터의 GIL» 을 재는 것이라 비교가 안 된다.
#    그래서 **못 맞추면 조용히 내려가지 않고 멈춘다** — 손으로 고르려면 `AI_PY` 로 준다.
if [ "$ROLE" = "ai-venv" ]; then
  step "파이썬 $AI_PY_VERSION — 버전이 측정 조건이다"

  AI_PY_BIN=${AI_PY:-}
  if [ -z "$AI_PY_BIN" ]; then
    AI_PY_BIN=$(command -v "python$AI_PY_VERSION" 2>/dev/null || true)
  fi
  if [ -z "$AI_PY_BIN" ]; then
    echo "  python$AI_PY_VERSION 이 없다 — 배포판 패키지로 시도한다"
    if command -v dnf >/dev/null 2>&1; then
      dnf -y install "python$AI_PY_VERSION" "python$AI_PY_VERSION-devel" >/dev/null 2>&1 || true
    else
      apt-get install -y -qq "python$AI_PY_VERSION" "python$AI_PY_VERSION-venv" "python$AI_PY_VERSION-dev" >/dev/null 2>&1 || true
    fi
    AI_PY_BIN=$(command -v "python$AI_PY_VERSION" 2>/dev/null || true)
  fi
  [ -n "$AI_PY_BIN" ] || die "python$AI_PY_VERSION 을 못 구했다.
   🔴 **다른 버전으로 대신 돌리지 않는다** — 기준 관측이 python:3.12-slim 컨테이너의 것이고
      R10 이 재는 것이 GIL 거동이라, 버전이 다르면 비교 자체가 성립하지 않는다.
   손으로 깔았으면 AI_PY=/경로/python3.12 로 줄 것.
   (Ubuntu 22.04 기본 저장소엔 3.12 가 없다 — deadsnakes PPA 또는 pyenv 가 필요하다)"
  echo "  $AI_PY_BIN → $("$AI_PY_BIN" -V 2>&1)"

  # OpenCV 런타임 의존성. Dockerfile 이 libgl1·libglib2.0-0 을 까는 자리와 같다.
  # ⚠️ 패키지 이름이 배포판마다 다르다 — AL2023 은 mesa-libGL·glib2 다.
  step "OpenCV 런타임 의존성"
  if command -v dnf >/dev/null 2>&1; then
    dnf -y install mesa-libGL glib2 >/dev/null || die "mesa-libGL/glib2 설치 실패"
  else
    apt-get install -y -qq libgl1 libglib2.0-0 >/dev/null || die "libgl1/libglib2.0-0 설치 실패"
  fi

  # 🔴 CPU 샘플러 — 이 역할은 도커가 없어서 `docker stats` 가 **없다.** 기준 관측
  #    「346 RPS 에 8.69 vCPU」가 그 명령으로 걷힌 값이라, 대체 수단이 없으면 라운드가
  #    구간 비율만 답하고 **제목의 숫자를 못 만진다**(#400 ⑤). pidstat 을 미리 깔아 둔다 —
  #    라운드 중에 깔면 그것부터가 조건에 안 적힌 작업이다.
  #    ⚠️ **깔기만 한다. 걷는 것은 rig 의 몫이고 아직 없다.**
  if command -v dnf >/dev/null 2>&1; then
    dnf -y install sysstat >/dev/null 2>&1 || true
  else
    apt-get install -y -qq sysstat >/dev/null 2>&1 || true
  fi
  command -v pidstat >/dev/null 2>&1 \
    && echo "  pidstat ✅ $(command -v pidstat)" \
    || echo "  🔴 pidstat 이 없다 — AI CPU 를 걷을 수단이 이 박스에 없다(#400 ⑤)"

  # 🔴 자리가 고정이다 — rig(`run_arms.py`)이 `ai-server/.venv/{bin,Scripts}` 를 찾는다.
  step "venv — $WORKDIR/ai-server/.venv"
  AI_VENV="$WORKDIR/ai-server/.venv"
  if [ ! -x "$AI_VENV/bin/python" ]; then
    "$AI_PY_BIN" -m venv "$AI_VENV" || die "venv 생성 실패 (Ubuntu 면 python$AI_PY_VERSION-venv 가 필요하다)"
  fi
  "$AI_VENV/bin/python" -m pip install -q --upgrade pip >/dev/null 2>&1 || true

  step "의존성 — requirements.txt (mediapipe 때문에 3~8분)"
  "$AI_VENV/bin/python" -m pip install -q -r "$WORKDIR/ai-server/requirements.txt" \
    || die "pip install 실패"

  # proto 스텁. 저장소에 커밋본이 있지만 **Dockerfile 은 매번 다시 만든다** — 설치된
  # grpcio-tools 와 어긋난 스텁은 import 는 되고 런타임에 깨진다. 같은 자리를 그대로 따른다.
  # 🔴 protoc 를 여기서 직접 부르지 않는다 — #132 가 «저장소 어디에도 protoc 호출이 없어서
  #    재생성 방법이 아는 사람만 아는 것» 을 고치면서 진입점을 하나로 줄였다(gen_proto.sh).
  #    호출부가 둘이 되면 한쪽만 바뀌는 자리가 다시 생긴다 — 그 스크립트가 산출물 위치가
  #    «취향이 아니라 import 규약의 결과» 라는 것까지 들고 있다.
  step "proto 스텁 재생성"
  ( cd "$WORKDIR/ai-server" && VENV_PY="$AI_VENV/bin/python" bash scripts/gen_proto.sh ) \
    || die "protoc 실패 (ai-server/scripts/gen_proto.sh)"

  # 여기까지 왔는데 import 가 깨지면 rig 이 «판이 시작된 뒤» 죽는다. 지금 확인한다.
  step "확인 — import 와 버전"
  ( cd "$WORKDIR/ai-server" && "$AI_VENV/bin/python" - <<'PY'
import sys, mediapipe, cv2, numpy, grpc
sys.path.insert(0, ".")
import exercise_pb2, exercise_pb2_grpc          # noqa: F401
print(f"  python     {sys.version.split()[0]}")
print(f"  mediapipe  {mediapipe.__version__}")
print(f"  opencv     {cv2.__version__}")
print(f"  numpy      {numpy.__version__}")
print(f"  grpcio     {grpc.__version__}")
PY
  ) || die "의존성 import 실패 — 위 오류를 볼 것"

  # rig 이 쓰는 프레임 자산은 **커밋본**이다(408KB) — 이 박스에서 만들 수 없다.
  [ -f "$WORKDIR/loadtest/results/coresidency-2026-08-15/frames.json" ] \
    || die "frames.json 이 저장소에 없다 — rig 이 부하를 못 만든다"

  # 🔴 위 «확인» 단계가 버전을 찍지만 그건 **콘솔로 흘러간다.** 이 ROLE 이 생긴 이유가
  #    「손으로 깔면 무엇을 깔았는지가 라운드 조건에서 빠진다」(#407)라, 결과 디렉터리에
  #    **같이 넣을 수 있는 파일**로 한 벌 남긴다. 기준 라운드도 같은 것을 남겼다
  #    (`results/ai-scaling-aws-2026-08-17/conditions.txt` — lscpu + 스택 버전).
  step "라운드 조건 기록"
  AI_COND=${AI_COND:-/root/ai_venv_conditions.txt}
  {
    echo "생성   : $(date -u +%FT%TZ) UTC"
    echo "커밋   : $(git -C "$WORKDIR" rev-parse HEAD) ($REF)"
    echo "박스   : $(nproc) vCPU · $(awk '/MemTotal/{printf "%.1f", $2/1048576}' /proc/meminfo)GB"
    lscpu 2>/dev/null | grep -E 'Model name|Thread\(s\) per core|Core\(s\) per socket' | sed 's/^ */         /'
    echo "python : $("$AI_VENV/bin/python" -V 2>&1) ($AI_VENV/bin/python)"
    echo "도커   : 데몬 미기동 (ROLE=ai-venv)"
    echo "샘플러 : $(command -v pidstat 2>/dev/null || echo '없음')"
    echo "--- pip freeze ---"
    "$AI_VENV/bin/python" -m pip freeze 2>/dev/null
  } > "$AI_COND"
  echo "  $AI_COND — 🔴 **결과 디렉터리에 같이 넣을 것**"

  # 검출기 메모리는 실측값으로 미리 보여준다. 판정이 아니라 대조용이다 —
  # 풀을 얼마로 띄울지는 rig 인자(`--pool`)이고 이 스크립트가 안 정한다.
  awk -v mem="$(awk '/MemTotal/{print $2}' /proc/meminfo)" 'BEGIN{
    printf "  검출기 98.7MB/개(실측) → 풀 201 이면 %.1fGB · 이 박스 RAM %.1fGB\n",
           201*98.7/1024, mem/1048576 }'

  step "AI venv 준비됨 (從 R10-a)"
  cat <<EOF
  작업 디렉터리 : $WORKDIR
  커밋          : $(git -C "$WORKDIR" rev-parse --short HEAD) ($REF)
  인터프리터    : $AI_VENV/bin/python
  frames.json   : ✅ 커밋본
  조건 파일     : $AI_COND  ← 결과 디렉터리에 같이 넣을 것
  CPU 샘플러    : $(command -v pidstat 2>/dev/null || echo '🔴 없다')  (도커가 없어 docker stats 를 못 쓴다, #400 ⑤)

  다음 — R10-a(1대 동거). 무대 결정은 docs/decisions/r10-loadgen-topology.md §7:
  cd $WORKDIR && \
  nohup python3 loadtest/results/frame-path-overhead-2026-08-23/run_arms.py \
    --sessions 160 --dur 90 --pool 201 \
    --sessions 160 --dur 90 --pool 201     --plan "B,A,B,A@0.001,B@0.001,B@0.001,A@0.001,B,A" --discard 1 \
    --out loadtest/results/frame-path-r10a-\$(date +%F) > /root/r10a.log 2>&1 &

  🔴 규모는 **기존 라운드와 같은 조건**이라야 비교가 된다 — 160세션 · 90초 · 풀 201
     (ai-receive-path-scaling.md §8-2 표 · 풀 201 은 §「그러면 이게 결함 신호다」).
     rig 기본은 8세션 · 45초 · 풀=세션+4 라 **안 주면 다른 판이 된다.**

  🔴 팔 표기: A = 계측 OFF · B = 계측 ON · @<초> = GIL 스위치 간격.
     위 판은 4칸(A · B · A@0.001 · B@0.001) × 2반복 + 버림 1 이고 **위치 합이 맞다.**
     구간 비율·lease 는 B 에서만 걷힌다 — A 만으로 돌리면 판정선 셋 중 둘이 빈다.
  🔴 규모를 안 주면 rig 기본(8세션·45초·풀=세션+4)으로 도는데, 그건 **다른 판**이다.
  🔴 handler_concurrency 는 이 판의 판정선이 아니다 — 부하기가 동거해서 절대값이 다친다(§7).
  ⚠️ nohup 없이 & 만 붙이면 SSH 가 끊길 때 같이 죽는다.
EOF
  exit 0
fi

# ── 컨테이너 ─────────────────────────────────────────────────────────────
step "MySQL 컨테이너"
cd "$WORKDIR" || die "$WORKDIR 로 못 들어간다"
docker compose up -d mysql || die "compose up 실패"

# 🔴 `mysqladmin ping` 으로는 «준비됐다» 를 못 본다 (#275 ②, 2026-08-23 재발 — 인스턴스 둘).
#    MySQL 공식 이미지는 초기화 때 **임시 서버를 띄웠다 껐다** 하는데 ping 은 그 임시 서버에도
#    붙는다. 그래서 예전 코드는 루프에서 «떴다» 를 찍고 **바로 다음 줄에서** 죽었다:
#
#        헬스체크 대기. — 떴다
#      🔴 부트스트랩 중단 — MySQL 이 5분 안에 안 떴다
#
#    🔴 #275 가 제안한 두 방향(**헬스 상태** / **ping 연속 N회**)은 **둘 다 안 된다.**
#    2026-08-23 에 fresh `mysql:8.0` 을 띄워 1초 간격으로 찍은 타임라인이 그것을 지운다:
#
#        t=18~20  health=healthy · ping(소켓)=OK · 실제 질의=실패   ← 임시 서버
#        t=21~23  health=healthy(낡음) · ping=실패 · 질의=실패      ← 재시작 창(옛 코드가 죽던 자리)
#        t=24     health=healthy · ping=실패 · **질의=1**           ← 진짜 서버
#
#    헬스체크의 test 가 `mysqladmin ping` 이라 **헬스 상태도 임시 서버를 healthy 로 본다.**
#    ping 연속 3회도 t=18~20 구간에 그대로 들어맞는다. 그래서 기준을 하나로 바꾼다 —
#    «인증된 질의가 TCP 로 되는가».
echo -n "  준비 대기"
mysql_ready=0
# 상한 90회 × 5초 = 7.5분. 예전 5분에서 올렸다 — 2026-08-23 스모크에서 (부하가 걸린) 로컬
# 박스가 **166초**를 썼다. 실패의 대가가 «인스턴스 하나» 라 여유를 두는 편이 싸다.
for _ in $(seq 1 90); do
  # 🔴 «인증된 질의가 TCP 로 되는가» 하나만 본다. ping 도 헬스 상태도 임시 서버를 통과한다(위).
  #    TCP 인 이유: 임시 서버는 네트워킹 없이 소켓으로만 뜨므로 TCP 는 **진짜 서버의 신호**다.
  #    MYSQL_PWD 로 넘긴다 — argv 에 실리지 않고 경고도 안 난다(run_all.sh 의 같은 규약).
  if docker exec -e MYSQL_PWD="$PW" shadowfit-mysql \
       mysql -uroot -h 127.0.0.1 --protocol=TCP -N -e "SELECT 1" >/dev/null 2>&1; then
    mysql_ready=$((mysql_ready+1))
    if [ "$mysql_ready" -ge 2 ]; then
      echo " — 떴다 (TCP 인증 질의 연속 2회)"; break
    fi
  else
    mysql_ready=0
  fi
  echo -n "."; sleep 5
done
# 🔴 여기서 다시 묻지 않는다. 예전 코드의 결함이 그 재확인이었다 — 루프가 «준비» 를 확인한
#    뒤 한 번 더 물어서, 재시작 창에 걸리면 멀쩡한 박스를 버린다.
[ "$mysql_ready" -ge 2 ] || die "MySQL 이 7.5분 안에 준비되지 않았다 (#275 ②)"

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