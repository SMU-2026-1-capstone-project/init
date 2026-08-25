#!/bin/bash
# 워커 3개를 SO_REUSEPORT 공유 포트가 아니라 각자 다른 포트로 띄운다.
# 이유(2026-08-26 실측): --workers 3 로 포트를 공유시키면 커널이 프레임 HTTP 요청을
# 세션과 무관하게 아무 워커에나 분산시켜, 세션 상태(get_registry)가 없는 워커로 가는
# 프레임이 NO_LEASE 로 거절됐다(6건 중 4건, 67%). Spring 이 세션 시작 시 워커 인덱스를
# 프론트에 알려주려면 워커가 애초에 "다른 주소"여야 한다 — 같은 주소를 공유하면 알려줄 게 없다.
#
# 🔴 워커 수(3)는 여기 한 곳에서만 정의한다. Spring 쪽 AI_CHANNEL_POOL_SIZE
# (ExerciseAnalysisService.java)는 별도로 맞춰야 한다 — 자동으로 동기화되지 않는다.
# 워커 수를 바꾸면 (1) 아래 포트 목록 (2) Spring의 AI_CHANNEL_POOL_SIZE (3) nginx-ai의
# map 블록 세 곳을 같이 고쳐야 한다.
set -e

WORKER_COUNT=3
HTTP_PORTS=(8000 8001 8002)
GRPC_PORTS=(8585 8586 8587)

for i in $(seq 0 $((WORKER_COUNT - 1))); do
  AI_GRPC_PORT="${GRPC_PORTS[$i]}" AI_WORKER_COUNT="$WORKER_COUNT" \
    uvicorn app.main:app --host 0.0.0.0 --port "${HTTP_PORTS[$i]}" &
done

# 하나라도 죽으면 컨테이너 전체를 내린다 — 일부만 살아있는 상태를 정상으로 보이게
# 두지 않기 위해서다(#430 의 「거짓 healthy」와 같은 원칙).
wait -n
exit $?
