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
const MULT = Number(__ENV.MULT || 60);         // 가정 피크 배수
const DUR  = __ENV.DUR || '120s';
const PW   = __ENV.K6_PASSWORD || 'Test1234!';
const PREFIX = __ENV.PREFIX || 'r14';

const BASE_RATE = 0.075;                       // ×1 = 0.075 요청/초 (위 유도)

const T = {
  session:  new Trend('t_report_session', true),
  weekly:   new Trend('t_weekly_summary', true),
  calendar: new Trend('t_calendar', true),
  daily:    new Trend('t_daily', true),
};
const badStatus = new Counter('bad_status');   // 🔴 http_req_failed 를 안 쓴다 — setup 요청이 섞인다

export const options = {
  scenarios: {
    read: {
      executor: 'constant-arrival-rate',
      rate: Math.round(BASE_RATE * MULT),
      timeUnit: '1s',
      duration: DUR,
      preAllocatedVUs: Number(__ENV.VUS || 50),
      maxVUs: Number(__ENV.VUS || 50),
    },
  },
  // 🔴 thresholds 를 «판정» 으로 안 쓴다. 게이트는 아래 둘이고, 판정선 대면은 결과 문서가 한다.
  thresholds: {
    bad_status: ['count==0'],
    dropped_iterations: ['count==0'],   // 깨지면 «느리다» 가 아니라 「가정 피크 ×N」이 깨진 것이다
  },
};

export function setup() {
  if (!BASE) throw new Error('BASE 를 넘길 것 (예: http://10.0.0.5:8080)');
  const email = `${PREFIX}_${Date.now()}@test.com`;
  const h = { headers: { 'Content-Type': 'application/json' } };

  let r = http.post(`${BASE}/member/signup`, JSON.stringify({
    username: `${PREFIX}user`, email, password: PW, sex: 'MALE', role: 'USER' }), h);
  if (r.status !== 200) throw new Error('signup 실패: ' + r.status + ' ' + r.body);

  r = http.post(`${BASE}/member/login`, JSON.stringify({ email, password: PW }), h);
  if (r.status !== 200) throw new Error('login 실패: ' + r.status);
  const token = r.json('accessToken');

  // 🔴 시드는 rig 밖에서 한다. 이 계정의 member_id 로 세션·리포트를 넣어야 읽을 것이 생긴다
  //    (시더가 member_id 를 1·5·12 로 하드코딩한다 — 파일 머리 참고).
  //    러너가 SEED_URL 로 그 절차를 미리 돌리고, 여기서는 결과만 받는다.
  const sids = (__ENV.K6_SIDS || '').split(',').filter(Boolean).map(Number);
  if (sids.length === 0) throw new Error('K6_SIDS 가 비었다 — 시드 단계가 안 돌았다');
  return { token, sids };
}

export default function (data) {
  const h = { headers: { Authorization: `Bearer ${data.token}` } };
  const sid = data.sids[Math.floor(Math.random() * data.sids.length)];

  let res = http.get(`${BASE}/reports/session/${sid}`, h);
  T.session.add(res.timings.duration);
  if (!check(res, { 'session 200': (r) => r.status === 200 })) badStatus.add(1);

  res = http.get(`${BASE}/reports/weekly-summary`, h);
  T.weekly.add(res.timings.duration);
  if (!check(res, { 'weekly 200': (r) => r.status === 200 })) badStatus.add(1);

  res = http.get(`${BASE}/reports/calendar?year=2026&month=6`, h);
  T.calendar.add(res.timings.duration);
  if (!check(res, { 'calendar 200': (r) => r.status === 200 })) badStatus.add(1);

  res = http.get(`${BASE}/reports/daily?date=2026-06-15`, h);
  T.daily.add(res.timings.duration);
  if (!check(res, { 'daily 200': (r) => r.status === 200 })) badStatus.add(1);
}
