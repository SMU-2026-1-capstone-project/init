#!/bin/bash
# 통주행 점검용 스택 부트스트랩 — 빈 EC2 를 «3서비스가 떠 있는 상태» 로 만든다.
#
# ─────────────────────────────────────────────────────────────────────────
# 왜 `loadtest/aws/bootstrap.sh` 를 안 쓰나
#
# 그쪽은 **MySQL 만** 띄운다. 백엔드를 빼둔 것은 의도였다 — Java·gradle 빌드가 붙으면
# 부트스트랩이 몇 배 느려지는데, 부하 측정 rig 는 백엔드 jar 를 따로 올려 쓰기 때문이다.
#
# 통주행 점검은 반대다. **앱이 실제로 도는 것 자체가 대상**이라 3서비스가 다 필요하다.
# 그래서 목적이 다른 스크립트를 하나 더 둔다 — 한쪽을 고치다 다른 쪽을 깨지 않게.
# ─────────────────────────────────────────────────────────────────────────
#
# 사용:
#   sudo -i
#   bash chain_bootstrap.sh
#
# 전제: Amazon Linux 2023 (dnf). 다른 배포판은 loadtest/aws/bootstrap.sh 의 apt 분기를 참고.

set -uo pipefail

REPO=${REPO:-https://github.com/Shadowfit/init.git}
REF=${REF:-work/2026-08-13}
WORKDIR=${WORKDIR:-/root/init}

step() { echo; echo "──── $* ────"; }
die()  { echo; echo "🔴 부트스트랩 중단 — $*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "root 로 돌려야 한다 (sudo -i)"

step "패키지"
dnf -y install docker git tar >/dev/null || die "dnf 설치 실패"
mkdir -p /usr/libexec/docker/cli-plugins
if ! docker compose version >/dev/null 2>&1; then
  curl -fsSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" \
    -o /usr/libexec/docker/cli-plugins/docker-compose || die "compose 플러그인 실패"
  chmod +x /usr/libexec/docker/cli-plugins/docker-compose
fi
# 🔴 buildx 도 같이 깐다. AL2023 의 `dnf install docker` 에는 **buildx 가 없고**,
#    요즘 compose 는 빌드에 buildx 를 요구한다 — `compose build requires buildx 0.17.0 or later`
#    로 죽는다. 이 메시지는 «이미지 빌드» 단계에서야 나오므로, 안 깔면 패키지·클론·.env 를
#    다 지난 뒤 **가장 늦게** 실패한다(2026-08-14 첫 시도가 그랬다).
# ⚠️ compose 와 달리 `latest/download` 로 못 받는다 — 자산 이름에 버전이 박혀 있어서
#    릴리스 API 로 URL 을 먼저 찾는다.
if ! docker buildx version >/dev/null 2>&1; then
  BX=$(curl -s https://api.github.com/repos/docker/buildx/releases/latest \
       | grep -o 'https://[^"]*buildx-v[0-9.]*\.linux-amd64' | head -1)
  [ -n "$BX" ] || die "buildx 릴리스 URL 을 못 찾았다"
  curl -fsSL "$BX" -o /usr/libexec/docker/cli-plugins/docker-buildx || die "buildx 내려받기 실패"
  chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
fi
systemctl enable --now docker >/dev/null 2>&1 || die "docker 데몬 기동 실패"

step "저장소 — $REPO @ $REF"
if [ -d "$WORKDIR/.git" ]; then
  git -C "$WORKDIR" fetch --all -q && git -C "$WORKDIR" checkout -q "$REF" \
    && git -C "$WORKDIR" pull -q --ff-only 2>/dev/null
else
  git clone -q "$REPO" "$WORKDIR" || die "clone 실패"
  git -C "$WORKDIR" checkout -q "$REF" || die "$REF 체크아웃 실패"
fi
echo "  커밋: $(git -C "$WORKDIR" rev-parse --short HEAD)"
cd "$WORKDIR" || die "cd 실패"

# ── .env ─────────────────────────────────────────────────────────────────
#
# 🔴 비밀값은 **여기서 무작위로 만든다.** 예시 파일의 placeholder 를 그대로 쓰면
#    JWT 가 조용히 약해지고, 그 상태로 「인증이 된다」를 확인해버린다.
#    ⚠️ 이 인스턴스는 **점검용 임시 박스**다. 여기서 만든 값은 어디에도 재사용하지 않는다.
step ".env"
if [ ! -f .env ]; then
  DBPW=$(openssl rand -hex 12)
  cat > .env <<EOF
MYSQL_DATABASE=shadowfit
MYSQL_USER=shadowfit
MYSQL_ROOT_PASSWORD=1234
MYSQL_PASSWORD=$DBPW
MYSQL_PORT=3306
DB_USERNAME=shadowfit
DB_PASSWORD=$DBPW
JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
JWT_EXPIRATION_TIME=1800
JWT_REFRESH_EXPIRATION_TIME=604800
INTERNAL_API_TOKEN=$(openssl rand -hex 24)
AI_PUBLIC_TOKEN=$(openssl rand -hex 24)
OPENAI_API_KEY=
MYSQL_EXPORTER_PASSWORD=$(openssl rand -hex 12)
GRAFANA_USER=admin
GRAFANA_PASSWORD=$(openssl rand -hex 12)
AI_MEM_LIMIT=4g
EOF
  echo "  생성함 (root PW 는 rig 관례대로 1234 — chain_check.sh 기본값과 맞춘다)"
else
  echo "  이미 있다 — 그대로 쓴다"
fi

# ── 기동 ─────────────────────────────────────────────────────────────────
#
# 🔴 `--profile obs` 를 안 붙인다. 이 라운드는 «앱이 도는가» 만 본다 —
#    Prometheus·Grafana 는 그 판정에 아무것도 더하지 않고 빌드 시간만 늘린다.
step "이미지 빌드 · 기동 (수 분 걸린다)"
docker compose up -d --build mysql shadowfit-backend shadowfit-ai \
  || die "compose up 실패 — 위 로그의 첫 에러를 볼 것"

step "기동 대기"
# «떴다» 를 컨테이너 상태가 아니라 **응답**으로 확인한다. 컨테이너는 Running 인데
# 애플리케이션이 아직 부팅 중인 구간이 길다(Flyway 마이그레이션 포함).
# 🔴 액추에이터는 **9090**(관리 포트)이다. 8080 은 API 포트라 `/actuator/health` 가 404 다 —
#    8080 으로 기다리면 앱이 멀쩡히 떠 있어도 «10분 안에 안 뜬다» 로 찍힌다(첫 시도가 그랬다).
#    9090 은 127.0.0.1 에만 바인딩되므로 **인스턴스 안에서만** 확인된다(#128).
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:9090/actuator/health 2>/dev/null)
  [ "$code" = "200" ] && { echo "  ✅ 백엔드 health 200 (${i}0초 이내)"; break; }
  [ "$i" = "60" ] && echo "  ⚠️ 백엔드가 10분 안에 health 를 안 준다 — 로그를 볼 것"
  sleep 10
done
docker compose ps

echo
echo "다음: bash $WORKDIR/scripts/chain_check.sh"
