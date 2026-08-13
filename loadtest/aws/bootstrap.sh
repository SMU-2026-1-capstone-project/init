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