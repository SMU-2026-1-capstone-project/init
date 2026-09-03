// 그룹 WS 핸드셰이크 동시성 — HikariCP 풀 병목 경계를 좁힌다.
//
// 배경: docs/decisions/group-websocket-capacity-deep-dive.md §1-5·§9.
//   §1-5(로컬, measure_group_handshake_concurrency.py, M≤40) — hikaricp_connections_pending
//   항상 0, HikariCP 무죄로 계측 확정.
//   §9(AWS c7i.xlarge, measure_group_ws_multi_group_spike.py, 300연결) — 같은 지표가 처음
//   15로 관측됨. 두 실측 사이(40~300)의 정확한 경계가 미확정 — 이 스크립트가 그 경계를 좁힌다.
//
// 이 스크립트가 답하는 것: 동시 핸드셰이크 수 M을 스윕하며
//   ① 핸드셰이크 지연(open까지) ② 실패/타임아웃 ③ hikaricp_connections_pending·
//   tomcat_threads_busy_threads·jvm_threads_live_threads 피크
// 를 함께 본다. "N에서 몇 ms냐"가 아니라 "pending이 0에서 양수로 넘어가는 M이 어디냐"가 목적
// ([[feedback_tps_over_dau_justification]] — 천장 숫자가 아니라 꺾이는 원인/지점).
//
// 🔴 이 판은 측정 전용이다. maximum-pool-size(15)나 JwtHandshakeInterceptor 쿼리를 여기서
//    고치지 않는다 — 코드 변경은 이 실측 결과를 문서화한 뒤 별도 확인을 받고 진행한다
//    ([[feedback_decision_doc]], [[feedback_user_decides_not_claude]]).
//
// 🔴 k6 VU는 threading.Barrier가 아니다 — per-vu-iterations executor로 최대한 동시에 시작하지만
//    VU 초기화 자체가 순차적이라 완벽한 동시 시작은 아니다. python 판(threading.Barrier)보다
//    "동시성"이 느슨하다는 걸 결과 해석 시 감안할 것.
//
// 🔴 계정/그룹 시딩은 이 스크립트가 안 한다 — 별도로 분리했다. k6는 순수 HTTP 클라이언트라
//    email -> memberId 조회 수단이 없다(이 앱은 GET /member/me가 없고, admin 조회 API는
//    signup으로 admin 토큰을 못 만들어 못 씀 — MemberRequestDto 주석, 이슈 #138). 기존
//    measure_group_handshake_concurrency.py처럼 memberId는 docker exec mysql로만 읽을 수
//    있어서, seed_group_members.py(같은 디렉터리)가 계정 M개+그룹 1개+전원 ACTIVE 멤버십을
//    미리 만들고 {groupId, tokens: [...]}를 JSON으로 남긴다. 이 k6 스크립트는 그 JSON을
//    setup()에서 읽기만 하고, 부하 생성(핸드셰이크 버스트)만 전담한다.
//
// 실행 예:
//   python seed_group_members.py --count 100 --out seed-100.json
//   BASE=http://localhost:8080 SEED_FILE=seed-100.json CONCURRENCY=100 \
//     k6 run --summary-trend-stats "avg,p(50),p(95),p(99),max" group_ws_handshake_concurrency.js
//
// 여러 M을 스윕하려면(이등분 탐색은 사람이 관측값 보고 다음 M을 정한다 — 스크립트가 자동으로
// 안 정한다, 관측 없는 5단계 사전 확정은 이 프로젝트 방법론과 안 맞는다):
//   for M in 40 100 150 300; do
//     python seed_group_members.py --count $M --out seed-$M.json
//     SEED_FILE=seed-$M.json CONCURRENCY=$M k6 run group_ws_handshake_concurrency.js
//   done
// 판 반복([[feedback_measure_design_needs_repeats]])은 같은 SEED_FILE로 k6 run을 여러 번
// 부르면 된다 — 소켓은 매 판 새로 열고 닫으므로 시딩을 다시 할 필요는 없다.

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE || 'http://localhost:8080';
const WS_BASE = BASE.replace(/^http/, 'ws');
const PROM_BASE = __ENV.PROM_BASE || 'http://localhost:9090';
const CONCURRENCY = Number(__ENV.CONCURRENCY || '40'); // SEED_FILE의 tokens 수와 맞출 것
const SEED_FILE = __ENV.SEED_FILE || 'seed-group.json';
const seedData = JSON.parse(open(SEED_FILE));

const T = {
  handshake: new Trend('t_ws_handshake', true), // GET→101까지, ms
  hikariPending: new Trend('t_hikari_pending', false),
  tomcatBusy: new Trend('t_tomcat_busy', false),
  jvmThreads: new Trend('t_jvm_threads', false),
};
const C = {
  failed: new Counter('c_handshake_failed'),
};

export const options = {
  scenarios: {
    // 한 판 = M개 VU가 각자 핸드셰이크 1회. REPS만큼 반복하려면 셸에서 여러 번 run한다
    // (판 사이에 서버 상태를 리셋할 필요가 없다 — 매 판이 독립 소켓 open/close다).
    burst: {
      executor: 'per-vu-iterations',
      vus: CONCURRENCY,
      iterations: 1,
      maxDuration: '60s',
      exec: 'handshakeBurst',
    },
    // 버스트와 동시에 도는 저빈도 폴러 — python Poller 클래스와 같은 역할.
    metricsPoller: {
      executor: 'constant-vus',
      vus: 1,
      duration: '20s',
      exec: 'pollMetrics',
    },
  },
  thresholds: { ws_connecting: ['p(95)<60000'] }, // 게이트 아님, 타임아웃 방지용 상한만
};

export function handshakeBurst() {
  const idx = (exec.vu.idInTest - 1) % seedData.tokens.length;
  const token = seedData.tokens[idx];
  const url = `${WS_BASE}/ws/groups/${seedData.groupId}?token=${token}`;

  const t0 = Date.now();
  const res = ws.connect(url, {}, (socket) => {
    socket.on('open', () => {
      T.handshake.add(Date.now() - t0);
      socket.close();
    });
    socket.on('error', (e) => {
      C.failed.add(1);
      console.error(`handshake 실패: ${e.error ? e.error() : e}`);
    });
  });
  check(res, { '101 upgrade': (r) => r && r.status === 101 });
  if (!res || res.status !== 101) C.failed.add(1);
}

const METRIC_RE = {
  hikariPending: /^hikaricp_connections_pending\{pool="HikariPool-1"[^}]*\}\s+([\d.]+)/m,
  tomcatBusy: /^tomcat_threads_busy_threads\{name="http-nio-8080"[^}]*\}\s+([\d.]+)/m,
  jvmThreads: /^jvm_threads_live_threads\s+([\d.]+)/m,
};

export function pollMetrics() {
  const res = http.get(`${PROM_BASE}/actuator/prometheus`, { timeout: '3s' });
  if (res.status === 200) {
    const body = res.body;
    for (const [key, re] of Object.entries(METRIC_RE)) {
      const m = body.match(re);
      if (m) T[key].add(Number(m[1]));
    }
  }
  sleep(0.05); // 50ms 간격 — python Poller와 동일
}

// 셸 rig이 CSV 한 줄로 이어붙일 수 있게 요약을 stdout에 남긴다(write_p99.js handleSummary 관행).
export function handleSummary(data) {
  const m = (name, key, dflt) => {
    const x = data.metrics[name];
    if (!x || x.values[key] === undefined) return dflt;
    return x.values[key];
  };
  const f = (v) => (v === null || v === undefined ? 'NA' : Number(v).toFixed(1));
  const row = [
    `M=${CONCURRENCY}`,
    `handshake_p50=${f(m('t_ws_handshake', 'p(50)'))}`,
    `handshake_p90=${f(m('t_ws_handshake', 'p(90)'))}`,
    `handshake_max=${f(m('t_ws_handshake', 'max'))}`,
    `failed=${m('c_handshake_failed', 'count', 0)}`,
    `hikari_pending_max=${f(m('t_hikari_pending', 'max'))}`,
    `tomcat_busy_max=${f(m('t_tomcat_busy', 'max'))}`,
    `jvm_threads_max=${f(m('t_jvm_threads', 'max'))}`,
  ].join(' ');
  const out = { stdout: `\n[group-ws-handshake] ${row}\n` };
  if (__ENV.SUMMARY_OUT) out[__ENV.SUMMARY_OUT] = row + '\n';
  return out;
}
