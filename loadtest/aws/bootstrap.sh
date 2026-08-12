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
cat > "$WORKDIR/.env" <<EOF
MYSQL_DATABASE=$DB_NAME
MYSQL_USER=shadowfit
MYSQL_ROOT_PASSWORD=$PW
MYSQL_PASSWORD=$PW
MYSQL_PORT=3306
EOF
echo "  MYSQL_ROOT_PASSWORD 를 rig 기본값과 맞췄다"

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