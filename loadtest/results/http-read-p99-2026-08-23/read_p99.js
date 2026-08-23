// HTTP 읽기 경로 p99 — SLO 11번의 «부하 하 실측이 한 번도 없다» 를 처음 채우는 rig.
//
// 🔴 이 rig 으로 답이 나오는 것 / 안 나오는 것
//   ✅ 엔드포인트 사이 **상대** 비교 — 어느 것이 먼저 휘는가, 꼬리가 어떻게 벌어지는가
//   ❌ **절대 p99 를 SLO 판정선(300ms · 1s)에 대면 안 된다.** 이 박스는 2코어에 MySQL·백엔드·AI
//      동거인데 부하기까지 같이 돈다. 그리고 slo-baseline.md 가 스스로
//      «시간 임계는 이 환경에서 안 쓴다 — 이웃 프로세스가 통과 여부를 정한다» 고 못박았다.
//      판정선 대면은 EC2 從 라운드에서 한다.
//   ❌ 합성 분포다 — 시드 세션이 같은 템플릿이라 값 분포가 균일하다.
//
// ── 쓰는 법 ──────────────────────────────────────────────────────────────────
//
// 🔴 **계정은 직접 만들어 넘긴다.** 시더(`loadtest/seed/seed_report_rig.sh`)가 member_id 를
//    1·5·12 로 하드코딩해서, 새로 만든 계정에는 읽을 데이터가 없다. 그렇다고 시드 회원의
//    비밀번호를 갈아끼우면 안 된다 — 남의 계정 자격증명을 만지는 일이다.
//    대신 **내가 만든 계정에 데이터를 시드**한다(README §1).
//
//   K6_EMAIL=... K6_PASSWORD=... K6_SIDS=69726,69727,... \
//     k6 run --summary-trend-stats "avg,p(50),p(95),p(99),max" read_p99.js
//
//   K6_SIDS 는 그 계정이 소유하고 **리포트가 붙은** 세션 id 들이다:
//     SELECT s.id FROM exercise_sessions s JOIN reports r ON r.session_id=s.id
//      WHERE s.member_id=<내 회원 id> ORDER BY s.id LIMIT 50;
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const EMAIL = __ENV.K6_EMAIL;
const PASSWORD = __ENV.K6_PASSWORD;
const SIDS = (__ENV.K6_SIDS || '').split(',').filter(Boolean).map(Number);

// 엔드포인트마다 따로 잰다 — 합쳐 놓으면 «어느 것이 먼저 휘는가» 가 사라진다.
const T = {
  session:  new Trend('t_report_session', true),
  weekly:   new Trend('t_weekly_summary', true),
  calendar: new Trend('t_calendar', true),
  daily:    new Trend('t_daily', true),
};

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 1,
      // ⚠️ 이 램프에는 근거가 없다 — «가정 부하» 에서 유도한 값이 아니라 임의로 고른 상한이다.
      //    그래서 이 판은 «상대» 만 읽는다(README §3).
      stages: [
        { duration: '20s', target: 1 },
        { duration: '20s', target: 4 },
        { duration: '20s', target: 8 },
        { duration: '20s', target: 16 },
      ],
      gracefulRampDown: '5s',
    },
  },
  // 🔴 thresholds 를 «판정» 으로 쓰지 않는다 — 실패 0 만 본다.
  thresholds: { http_req_failed: ['rate<0.01'] },
};

export function setup() {
  if (!EMAIL || !PASSWORD) throw new Error('K6_EMAIL / K6_PASSWORD 를 넘길 것 (파일 머리 참고)');
  if (SIDS.length === 0) throw new Error('K6_SIDS 를 넘길 것 (파일 머리의 쿼리 참고)');
  const r = http.post(`${BASE}/member/login`, JSON.stringify({ email: EMAIL, password: PASSWORD }),
                      { headers: { 'Content-Type': 'application/json' } });
  if (r.status !== 200) throw new Error('login 실패: ' + r.status + ' ' + r.body);
  return { token: r.json('accessToken') };
}

export default function (data) {
  const h = { headers: { Authorization: `Bearer ${data.token}` } };
  const sid = SIDS[Math.floor(Math.random() * SIDS.length)];

  let res = http.get(`${BASE}/reports/session/${sid}`, h);
  T.session.add(res.timings.duration);
  check(res, { 'session 200': (r) => r.status === 200 });

  res = http.get(`${BASE}/reports/weekly-summary`, h);
  T.weekly.add(res.timings.duration);
  check(res, { 'weekly 200': (r) => r.status === 200 });

  res = http.get(`${BASE}/reports/calendar?year=2026&month=6`, h);
  T.calendar.add(res.timings.duration);
  check(res, { 'calendar 200': (r) => r.status === 200 });

  res = http.get(`${BASE}/reports/daily?date=2026-06-15`, h);
  T.daily.add(res.timings.duration);
  check(res, { 'daily 200': (r) => r.status === 200 });
}
