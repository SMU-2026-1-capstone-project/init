// HTTP 읽기 경로 p99 — 판정선 대면판 (SLO 11번 · slo-baseline §4-2 「리포트·캘린더 읽기 p99 ≤ 1s」)
//
// 🔴 이 파일이 있는 이유. 2026-08-23 로컬 판(results/http-read-p99-2026-08-23/)이 읽기축을
//    처음 쟀지만 **판정선과 대면하지 못했다.** 그 판이 스스로 셋을 적어뒀다:
//      ① 2코어 동거 박스에 **부하기까지 같이** 돈다
//      ② slo-baseline 이 「시간 임계는 이 환경에서 안 쓴다」고 못박았다
//      ③ **16 VU 에 근거가 없다** — 임의로 고른 램프 상한이다
//    이 rig 은 셋을 다 없앤다. 쓰기축 판(從 R13, k6/write_p99.js)이 쓴 형태를 그대로 따른다.
//
// ── 부하를 가정 P1 에서 유도한다 (임의 VU 를 안 쓴다) ──────────────────────────
//
//   동접 67.5세션 = DAU 1,000 × 1.5세션/일 × p 0.18 × (15분/60)   ← load-test-strategy §4.2
//   리포트 조회는 «세션당 1회 + 캘린더/주간 열람» 이므로, 이 rig 의 ×1 을
//   **세션 시작률과 같은 0.075/초** 로 잡는다. 팔은 그 배수다.
//
// 🔑 `constant-arrival-rate` 인 것이 핵심이다. VU 로 묶으면(closed loop) 서버가 느려질수록
//    부하가 저절로 줄어 **「가정 피크 ×N」 진술 자체가 성립하지 않는다.**
//
// ── 쓰기축과 다른 점 ────────────────────────────────────────────────────────
//   쓰기축은 «계정 완결 왕복» 이 rig 천장을 만들었다(회원당 IN_PROGRESS 1개 제약).
//   읽기는 세션을 만들지 않으므로 그 천장이 없다. 대신 **데이터 소유권**이 문제다 —
//   시더가 member_id 를 1·5·12 로 하드코딩해서, 새로 만든 계정에는 읽을 것이 없다.
//   그래서 이 rig 은 **setup 이 계정을 만들고 그 계정에 데이터를 시드**한다(SEED_URL).
//   로컬 판이 손으로 하던 자리다.
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE;                       // 예: http://172.31.32.85:8080
const MULT = Number(__ENV.MULT || 60);         // 가정 피크 배수 (SPIKE_MULT 없을 때만 쓴다)
const DUR  = __ENV.DUR || '120s';
const PW   = __ENV.K6_PASSWORD || 'Test1234!';

const BASE_RATE = 0.075;                       // ×1 = 0.075 요청/초 (위 유도)

// ── 스파이크 모드 (#587 · docs/decisions/read-path-spike-test.md) ──────────
//
// SPIKE_MULT 를 주면 executor 가 `ramping-arrival-rate` 로 바뀐다. 안 주면 기존
// `constant-arrival-rate` 그대로라 從 R14 스윕(measure_http_read_p99_sweep.sh)은
// 이 변경으로 안 흔들린다 — 같은 파일이 두 실험을 겸한다.
//
// 단계: 베이스라인(BASE_MULT, 관찰) → 급증(RAMP_DUR) → 스파이크 유지(SPIKE_DUR) →
//       급락(RAMP_DUR) → 베이스라인 복귀·관찰(RECOVERY_DUR)
const SPIKE_MULT = __ENV.SPIKE_MULT ? Number(__ENV.SPIKE_MULT) : null;
const BASE_MULT     = Number(__ENV.BASE_MULT || 60);
const BASELINE_DUR  = __ENV.BASELINE_DUR || '30s';
const RAMP_DUR      = __ENV.RAMP_DUR || '2s';
const SPIKE_DUR     = __ENV.SPIKE_DUR || '20s';
const RECOVERY_DUR  = __ENV.RECOVERY_DUR || '90s';

// 위 5단계 지속시간을 초로도 들고 있는다 — default() 에서 «지금이 몇 번째 단계인가» 를
// 벽시계로 판정하려면 문자열('20s')이 아니라 초 숫자가 필요하다.
function toSec(s) { return typeof s === 'number' ? s : Number(String(s).replace('s', '')); }
const PHASE_BOUNDS = SPIKE_MULT ? (() => {
  const t0 = toSec(BASELINE_DUR);
  const t1 = t0 + toSec(RAMP_DUR);
  const t2 = t1 + toSec(SPIKE_DUR);
  const t3 = t2 + toSec(RAMP_DUR);
  const t4 = t3 + toSec(RECOVERY_DUR);
  return { t0, t1, t2, t3, t4 };
})() : null;

function phaseAt(elapsedSec) {
  if (!PHASE_BOUNDS) return null;
  const { t0, t1, t2, t3 } = PHASE_BOUNDS;
  if (elapsedSec < t0) return 'baseline';
  if (elapsedSec < t1) return 'rampup';
  if (elapsedSec < t2) return 'spike';
  if (elapsedSec < t3) return 'rampdown';
  return 'recovery';
}

// 엔드포인트 4개 × (스파이크 모드면 단계 5개, 아니면 구분 없음 1개) Trend.
// 스파이크가 아닐 때는 기존 이름(t_report_session 등)을 그대로 써서 從 R14 표 파서
// (measure_http_read_p99_sweep.sh 의 extract_row)가 안 깨진다.
const PHASES = ['baseline', 'rampup', 'spike', 'rampdown', 'recovery'];
const ENDPOINTS = ['session', 'weekly', 'calendar', 'daily'];
const METRIC_NAME = { session: 't_report_session', weekly: 't_weekly_summary', calendar: 't_calendar', daily: 't_daily' };

const T = {};
for (const ep of ENDPOINTS) {
  if (SPIKE_MULT) {
    T[ep] = {};
    for (const ph of PHASES) T[ep][ph] = new Trend(`${METRIC_NAME[ep]}__${ph}`, true);
  } else {
    T[ep] = new Trend(METRIC_NAME[ep], true);
  }
}
const badStatus = new Counter('bad_status');   // 🔴 http_req_failed 를 안 쓴다 — setup 요청이 섞인다

const baseScenario = SPIKE_MULT ? {
  executor: 'ramping-arrival-rate',
  startRate: Math.round(BASE_RATE * BASE_MULT),
  timeUnit: '1s',
  preAllocatedVUs: Number(__ENV.VUS || 300),
  maxVUs: Number(__ENV.VUS || 300),
  stages: [
    { target: Math.round(BASE_RATE * BASE_MULT), duration: BASELINE_DUR },   // 베이스라인 유지
    { target: Math.round(BASE_RATE * SPIKE_MULT), duration: RAMP_DUR },      // 급증
    { target: Math.round(BASE_RATE * SPIKE_MULT), duration: SPIKE_DUR },     // 스파이크 유지
    { target: Math.round(BASE_RATE * BASE_MULT), duration: RAMP_DUR },       // 급락
    { target: Math.round(BASE_RATE * BASE_MULT), duration: RECOVERY_DUR },   // 회복 관찰
  ],
} : {
  executor: 'constant-arrival-rate',
  rate: Math.round(BASE_RATE * MULT),
  timeUnit: '1s',
  duration: DUR,
  preAllocatedVUs: Number(__ENV.VUS || 50),
  maxVUs: Number(__ENV.VUS || 50),
};

export const options = {
  scenarios: { read: baseScenario },
  // 🔴 thresholds 를 «판정» 으로 안 쓴다. 게이트는 아래 둘이고, 판정선 대면은 결과 문서가 한다.
  thresholds: {
    bad_status: ['count==0'],
    dropped_iterations: ['count==0'],   // 깨지면 «느리다» 가 아니라 「가정 피크 ×N」이 깨진 것이다
  },
};

export function setup() {
  if (!BASE) throw new Error('BASE 를 넘길 것 (예: http://10.0.0.5:8080)');
  // 🔴 여기서 새 계정을 등록하면 안 된다(#576) — /reports/session/{id} 는 요청자 memberId 로
  //    소유권을 확인하고, weekly/calendar/daily 는 요청자 자신의 memberId 로만 조회한다.
  //    그래서 이 rig 은 반드시 seed/seed_k6_read_account.sh 가 미리 시드해 둔 **그 계정**으로
  //    로그인만 한다 — 새 계정은 남의 세션엔 403, 자기 세션엔 데이터가 없어 둘 다 못 잰다.
  const email = __ENV.K6_EMAIL;
  if (!email) throw new Error('K6_EMAIL 이 필요하다 — seed/seed_k6_read_account.sh 의 출력을 넘길 것');
  const h = { headers: { 'Content-Type': 'application/json' } };

  const r = http.post(`${BASE}/member/login`, JSON.stringify({ email, password: PW }), h);
  if (r.status !== 200) throw new Error('login 실패: ' + r.status + ' ' + r.body);
  const token = r.json('accessToken');

  // 🔴 시드는 rig 밖에서 한다 — seed/seed_k6_read_account.sh 가 이 계정의 member_id 로
  //    세션·리포트를 이미 넣어 뒀고, 여기서는 그 세션 id 목록만 받는다.
  const sids = (__ENV.K6_SIDS || '').split(',').filter(Boolean).map(Number);
  if (sids.length === 0) throw new Error('K6_SIDS 가 비었다 — 시드 단계가 안 돌았다');
  return { token, sids, startMs: Date.now() };
}

function record(ep, res, data) {
  if (SPIKE_MULT) {
    const elapsed = (Date.now() - data.startMs) / 1000;
    T[ep][phaseAt(elapsed)].add(res.timings.duration);
  } else {
    T[ep].add(res.timings.duration);
  }
}

export default function (data) {
  const h = { headers: { Authorization: `Bearer ${data.token}` } };
  const sid = data.sids[Math.floor(Math.random() * data.sids.length)];

  let res = http.get(`${BASE}/reports/session/${sid}`, h);
  record('session', res, data);
  if (!check(res, { 'session 200': (r) => r.status === 200 })) badStatus.add(1);

  res = http.get(`${BASE}/reports/weekly-summary`, h);
  record('weekly', res, data);
  if (!check(res, { 'weekly 200': (r) => r.status === 200 })) badStatus.add(1);

  res = http.get(`${BASE}/reports/calendar?year=2026&month=6`, h);
  record('calendar', res, data);
  if (!check(res, { 'calendar 200': (r) => r.status === 200 })) badStatus.add(1);

  res = http.get(`${BASE}/reports/daily?date=2026-06-15`, h);
  record('daily', res, data);
  if (!check(res, { 'daily 200': (r) => r.status === 200 })) badStatus.add(1);
}
