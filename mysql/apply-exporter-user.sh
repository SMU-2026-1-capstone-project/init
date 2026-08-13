#!/usr/bin/env bash
# mysqld_exporter 계정 적용 — 비밀번호는 .env 의 MYSQL_EXPORTER_PASSWORD 하나에서만 온다 (#167).
#
# 왜 래퍼가 필요한가:
#   exporter-user.sql 이 비밀번호를 하드코딩하고 있었고, docker-compose.yml 은 같은 값을
#   ${MYSQL_EXPORTER_PASSWORD:-exporter} 로 따로 정했다. 두 곳이 독립이라 환경변수를 실제로
#   넣는 순간 어긋난다 — 익스포터는 새 값으로 접속을 시도하는데 계정은 옛 값으로 만들어져
#   인증 실패한다. .sql 파일은 셸 확장이 안 걸리므로 파일 안에서는 못 고친다.
#
#   그리고 그 실패가 하필 조용하다: 타깃이 DOWN 이 되면 MySQL 지표만 안 들어오고, 그건
#   «MySQL 은 놀고 백엔드가 탄다» 를 수집한 적 없는 지표로 말하게 만든 그 상황이다
#   (docs/decisions/commit-count-and-mysql-metrics.md §3-1).
#
# 사용:
#   set -a; . ./.env; set +a          # 또는 export MYSQL_EXPORTER_PASSWORD=... MYSQL_ROOT_PASSWORD=...
#   ./mysql/apply-exporter-user.sh
#
# 재실행해도 안전하다 — 템플릿이 CREATE 뒤에 ALTER 를 함께 낸다(§ALTER 주석 참고).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="$HERE/exporter-user.sql"
CONTAINER="${MYSQL_CONTAINER:-shadowfit-mysql}"

[ -f "$TEMPLATE" ] || { echo "템플릿 없음: $TEMPLATE"; exit 1; }

# 기본값을 두지 않는다 (#167 (나)). 값이 비어 있는데 조용히 'exporter' 로 만들어 두면,
# compose 쪽도 기본값을 잃은 지금은 컨테이너가 안 뜨거나 다른 값으로 붙어 결국 어긋난다.
# «잊으면 시끄럽게 깨진다» 는 MYSQL_ROOT_PASSWORD 관례를 따른다.
: "${MYSQL_EXPORTER_PASSWORD:?MYSQL_EXPORTER_PASSWORD 미설정 — .env 에 넣고 export 후 재실행 (.env.example 참고)}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 미설정 — 계정 생성에 root 가 필요하다}"

# SQL 문자열 리터럴에 그대로 들어가는 값이라 작은따옴표·역슬래시가 있으면 구문이 깨진다.
# 조용히 이스케이프하는 대신 거부한다 — 깨진 SQL 이 만드는 계정은 «만들어졌는데 비밀번호가
# 다른» 상태가 될 수 있고, 그건 이 이슈가 잡으려던 증상과 똑같이 조용하다.
case "$MYSQL_EXPORTER_PASSWORD" in
  *\'*|*\\*)
    echo "MYSQL_EXPORTER_PASSWORD 에 작은따옴표(') 또는 역슬래시(\\) 가 있습니다 — 다른 문자로 바꾸세요."
    exit 1
    ;;
esac

# sed 대신 셸 치환을 쓴다 — 비밀번호에 sed 메타문자(/ & 등)가 있어도 안전하다.
SQL="$(cat "$TEMPLATE")"
SQL="${SQL//__EXPORTER_PASSWORD__/$MYSQL_EXPORTER_PASSWORD}"

if [ "$SQL" = "$(cat "$TEMPLATE")" ]; then
  echo "템플릿에 __EXPORTER_PASSWORD__ 자리표시자가 없습니다 — $TEMPLATE 확인 필요"
  exit 1
fi

echo "[exporter-user] $CONTAINER 에 적용 중..."
printf '%s\n' "$SQL" | docker exec -i "$CONTAINER" \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD"
echo "[exporter-user] 완료 — 계정 'exporter'@'%' 가 현재 MYSQL_EXPORTER_PASSWORD 로 맞춰졌습니다."
