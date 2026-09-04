// HTTP 쓰기 경로 p99 — slo-baseline §4-2 의 「세션 쓰기 p99 ≤ 300ms」에 **대응하는 실측이 0** 인 자리를 채운다.
//
// 재는 것은 두 요청뿐이다(§4-2 표가 이름 붙인 그대로):
//   ① POST   /exercises/sessions        — 회원 조회 · 운동 조회(캐시) · 중복 활성 검사 · 세션 INSERT
//                                         (커밋 후 AI 로 가는 gRPC 는 @Async 라 이 지연에 안 들어간다)
//   ② PATCH  /sessions/{id}/end         — 세션 UPDATE + outbox INSERT
//
// ── 부하 수준은 임의로 고르지 않는다 ────────────────────────────────────────
//
// 읽기축 판(2026-08-23)이 스스로 «16 VU 에 근거가 없다» 고 적었다. 이 판은 그걸 안 되풀이한다.
// 가정 P1(load-test-strategy.md §4.2, 2026-08-23 사용자 confirm)에서 **유도**한다:
//
//   동접 67.5세션 = DAU 1,000 × 1.5세션/일 × p 0.18 × (15분/60)
//   → 세션 **시작률** = 67.5 / 900초 = 0.075/초   (종료율도 같다 — 정상상태)
//
// 이 rig 의 1 iteration = 시작 1 + 종료 1 이므로 **`RATE=0.075` 가 가정 피크 ×1** 이다.
// 🔴 그런데 ×1 로는 p99 를 못 낸다 — 120초에 9 iteration 이면 표본이 없다.
//    그래서 이 판이 답하는 질문은 «가정 피크에서 몇 ms 냐» 가 아니라
//    **«가정 피크의 몇 배까지 목표(300ms)를 지키나»** 다. 배수는 `MULT` 로 넘기고 rig 이 곱한다.
//
// ── 이 rig 이 모사하지 않는 것 (읽을 때 빼고 읽어야 하는 것) ────────────────
//   ❌ 세션 길이 — 시작하자마자 끝낸다. 15분짜리 세션의 «살아 있는 동안» 은 gRPC 적재 축 몫이다
//   ❌ 프레임 적재 — 그 축은 ghz 판들이 이미 쟀다
//   ❌ 실사용 분포 — 계정도 운동도 합성이다([[project_synthetic_data_distribution_limit]])
//
// ── 계정 ────────────────────────────────────────────────────────────────────
// 🔴 **VU 하나당 계정 하나여야 한다.** `createSession` 이 `existsByMemberIdAndStatus` 로
//    회원당 활성 세션 1개를 강제해서(SessionService:108 · 409 W005), 계정을 공유하면
//    표가 «지연» 이 아니라 «409 비율» 을 재게 된다. 그래서 `maxVUs = preAllocatedVUs = 계정 수` 로 묶는다.
//
// 계정은 setup() 이 **API 로 직접 만든다**(읽기축 판이 손으로 하던 자리다):
//   POST /member/signup → POST /member/login → PATCH /member/onboarding/{email}
// 온보딩이 필요한 이유: `startAnalysis` 가 `member.preferredUrl` 이 비면 400 을 던진다.
//
//   BASE=http://<대상>:8080 RATE=7.5 DUR=120s ACCOUNTS=64 EXERCISE_ID=1 \
//     k6 run --summary-trend-stats "avg,p(50),p(95),p(99),max" write_p99.js
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE       = __ENV.BASE || 'http://localhost:8080';
const RATE       = Number(__ENV.RATE || '7.5');          // iteration/초 (rig 이 배수를 곱해서 넘긴다)
const DUR        = __ENV.DUR || '120s';
const ACCOUNTS   = Number(__ENV.ACCOUNTS || '64');
const EXERCISE_ID = Number(__ENV.EXERCISE_ID || '1');
const PREFIX     = __ENV.ACCOUNT_PREFIX || 'k6w';        // 판마다 달라야 signup 이 충돌하지 않는다
// 🔴 계정 생성을 rig 이 미리 해 뒀으면 0 으로 부른다. 이유는 **레이트리밋**이다 —
//    `/member/signup`·`/member/login`·`/member/reissue` 는 IP당 60초에 60건이 상한이고
//    (`AuthRateLimitFilter` · `application.yml` 의 `ip-per-window: 60`), 계정 64개를
//    가입+로그인하면 128건이라 61번째에서 429 로 죽는다(2026-08-23 AWS 1차 판이 여기서 멈췄다).
//    측정 대상 두 엔드포인트는 이 필터에 안 걸리므로 **판 자체는 영향이 없다.**
const DO_SIGNUP  = (__ENV.SIGNUP || '1') !== '0';
const PASSWORD   = __ENV.K6_PASSWORD || 'K6load!2026';
const PREFERRED_URL = __ENV.PREFERRED_URL || 'https://www.youtube.com/watch?v=k6loadrig';

// 도착률을 정수로 만들기 위한 단위(위 scenario 주석 참고). 100 이면 0.01/초까지 표현된다.
const UNIT_S   = Number(__ENV.RATE_UNIT_SECONDS || '100');
const RATE_INT = Math.max(1, Math.round(RATE * UNIT_S));

// 🔴 성공/실패를 한 Trend 에 섞으면 mean 도 percentile 도 거짓말을 한다 — 실패가 섞인 응답은
//    보통 타임아웃·즉시 에러라 분포 양끝을 잡아당긴다. status 별로 Trend 를 나눈다.
const T = {
  start_ok:   new Trend('t_session_start_ok', true),    // POST /exercises/sessions, 2xx
  start_fail: new Trend('t_session_start_fail', true),  // POST /exercises/sessions, 2xx 아님(409 포함)
  end_ok:     new Trend('t_session_end_ok', true),      // PATCH /sessions/{id}/end, 200
  end_fail:   new Trend('t_session_end_fail', true),    // PATCH /sessions/{id}/end, 200 아님
};
const C = {
  conflict: new Counter('c_conflict_409'),     // 계정 배타가 깨졌거나 앞 판의 세션이 남았다는 뜻
  recovered: new Counter('c_recovered'),       // 409 를 active 조회 → 종료로 되돌린 횟수
  // 🔴 실패는 `http_req_failed` 로 안 센다. 그 지표엔 **setup 요청이 섞인다** —
  //    같은 프리픽스로 다시 돌리면 재가입이 실패하는데(정상이다, 계정을 재사용한다)
  //    그게 실패율로 잡혀서 게이트가 «측정이 깨졌다» 고 거짓 경보를 낸다(스모크에서 발견).
  //    그래서 **측정 대상 두 요청만** 여기서 직접 센다.
  failed: new Counter('c_measured_failed'),
};

export const options = {
  scenarios: {
    // 🔴 ramping-vus 가 아니라 constant-arrival-rate 다. 이 판이 고정하고 싶은 것은
    //    «동시 VU 수» 가 아니라 **도착률**이다 — 가정 P1 이 주는 값이 도착률이기 때문이다.
    //    VU 로 고정하면 서버가 느려질수록 부하가 저절로 줄어(closed loop) 배수 진술이 깨진다.
    rate: {
      executor: 'constant-arrival-rate',
      // 🔴 k6 는 `rate` 를 **정수**로만 받는다(실수를 주면 unmarshal 에러로 죽는다).
      //    가정 피크 ×1 은 0.075/초라 정수로 못 적는다 — 그래서 단위를 100초로 늘려
      //    같은 도착률을 정수로 표현한다(0.075/s = 7.5/100s → 반올림 8/100s).
      //    간격은 균등하게 나뉘므로(100s ÷ rate) 단위를 바꿔도 «몰아치기» 가 되지 않는다.
      rate: RATE_INT,
      timeUnit: `${UNIT_S}s`,
      duration: DUR,
      preAllocatedVUs: ACCOUNTS,
      maxVUs: ACCOUNTS,        // 계정 수를 넘겨 VU 를 더 만들면 계정이 겹쳐 409 가 난다
    },
  },
  // 🔴 threshold 를 판정으로 쓰지 않는다([[feedback_no_arbitrary_threshold_values]]) — 실패 0 만 본다.
  thresholds: { http_req_failed: ['rate<0.01'] },
  // CLI 플래그에 안 맡긴다 — 아래 handleSummary 가 이 목록을 그대로 읽기 때문에,
  // 플래그를 빼먹으면 p99 자리가 조용히 undefined 가 된다.
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  // setup 은 계정 수 × 4요청이다(가입·로그인·온보딩·잔여세션 정리). 기본 60초는
  // 64계정이거나 대상이 느릴 때 모자라고, 그때 증상이 «측정 실패» 가 아니라
  // «setup timeout» 이라 원인이 안 읽힌다.
  setupTimeout: __ENV.SETUP_TIMEOUT || '600s',
};

function json(token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h['Authorization'] = `Bearer ${token}`;
  return { headers: h };
}

export function setup() {
  const tokens = [];
  for (let i = 1; i <= ACCOUNTS; i++) {
    const email = `${PREFIX}${i}@loadtest.local`;
    if (DO_SIGNUP) {
      // signup 은 멱등이 아니다 — 이미 있으면 실패하는데, 그건 «같은 PREFIX 로 또 돌렸다» 는 뜻이라
      // 로그인만 하고 넘어간다(계정을 재사용해도 이 판이 재는 값은 안 바뀐다).
      http.post(`${BASE}/member/signup`, JSON.stringify({
        username: `${PREFIX}${i}`, email, password: PASSWORD, sex: 'MALE', role: 'USER',
      }), json());
    }

    const lr = http.post(`${BASE}/member/login`,
                         JSON.stringify({ email, password: PASSWORD }), json());
    if (lr.status === 429) {
      throw new Error(`login 429(${email}) — 인증 레이트리밋(IP당 60초 60건)에 걸렸다. `
        + `계정 수를 줄이거나, rig 이 계정을 미리 만들고 SIGNUP=0 으로 부를 것`);
    }
    if (lr.status !== 200) throw new Error(`login 실패(${email}): ${lr.status} ${lr.body}`);
    const token = lr.json('accessToken');

    // preferredUrl 이 비면 startAnalysis 가 400(INVALID_INPUT_VALUE)을 던진다 —
    // 그러면 이 판은 «지연» 이 아니라 «400 을 얼마나 빨리 뱉나» 를 재게 된다.
    // (SIGNUP=0 이면 rig 의 준비 단계가 이미 해 뒀다. 온보딩은 레이트리밋 대상이 아니지만
    //  판마다 되풀이할 이유가 없어 같이 건너뛴다.)
    if (DO_SIGNUP) {
      const or = http.patch(`${BASE}/member/onboarding/${email}`,
                            JSON.stringify({ preferredUrl: PREFERRED_URL }), json(token));
      if (or.status !== 200) throw new Error(`onboarding 실패(${email}): ${or.status} ${or.body}`);
    }

    // 앞 판이 남긴 활성 세션이 있으면 지금 끝내 둔다 — 안 그러면 첫 iteration 이 409 로 시작한다.
    const ar = http.get(`${BASE}/sessions/active`, json(token));
    if (ar.status === 200) {
      const sid = ar.json('sessionId');
      if (sid) http.patch(`${BASE}/sessions/${sid}/end`, null, json(token));
    }
    tokens.push(token);
  }
  return { tokens };
}

export default function (data) {
  // __VU 는 1..maxVUs 이고 maxVUs = ACCOUNTS 라 계정이 겹치지 않는다.
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const opt = json(token);

  let res = http.post(`${BASE}/exercises/sessions`,
                      JSON.stringify({ exerciseId: EXERCISE_ID }), opt);
  // 🔴 **202 다, 200 이 아니다** — 세션은 만들어졌지만 AI 로 가는 분석 요청이 커밋 후 비동기라
  //    컨트롤러가 accepted() 를 준다. 200 만 통과시키면 이 rig 이 전 판을 «실패» 로 세고
  //    종료 요청을 아예 안 보내서 표가 통째로 빈다(2026-08-23 계약 확인으로 발견).
  const started = res.status >= 200 && res.status < 300;
  (started ? T.start_ok : T.start_fail).add(res.timings.duration);
  check(res, { 'start 2xx': () => started });
  if (!started && res.status !== 409) C.failed.add(1);

  let sid = null;
  if (started) {
    sid = res.json('sessionId');
  } else if (res.status === 409) {
    // 앞 iteration 이 종료를 못 마치고 죽은 자리. 표본을 버리는 대신 되돌리고,
    // **몇 번 일어났는지 세어서** 결과에 같이 남긴다(조용히 넘기면 판이 거짓말을 한다).
    C.conflict.add(1);
    const ar = http.get(`${BASE}/sessions/active`, opt);
    if (ar.status === 200) {
      const old = ar.json('sessionId');
      if (old) { http.patch(`${BASE}/sessions/${old}/end`, null, opt); C.recovered.add(1); }
    }
    return;
  } else {
    return;
  }

  res = http.patch(`${BASE}/sessions/${sid}/end`, null, opt);
  const ended = res.status === 200;                     // 종료는 200 이다(계약 확인 완료)
  (ended ? T.end_ok : T.end_fail).add(res.timings.duration);
  check(res, { 'end 200': () => ended });
  if (!ended) C.failed.add(1);
}

// rig(measure_http_write_p99.sh)이 읽을 한 줄. 셸에서 JSON 을 파싱하면 파서 의존이 생기고,
// 그 의존이 없는 박스에서 표가 조용히 비게 된다 — 그래서 여기서 만들어 내보낸다.
export function handleSummary(data) {
  const m = (name, key, dflt) => {
    const x = data.metrics[name];
    if (!x || x.values[key] === undefined) return dflt;
    return x.values[key];
  };
  const f = (v) => (v === null || v === undefined ? 'NA' : Number(v).toFixed(2));
  const row = [
    __ENV.ARM_LABEL || 'NA',
    __ENV.BLOCK || 'NA',
    RATE,
    f(m('t_session_start_ok', 'p(50)')), f(m('t_session_start_ok', 'p(95)')),
    f(m('t_session_start_ok', 'p(99)')), f(m('t_session_start_ok', 'max')),
    // 🔴 실패 레이턴시는 성공과 같은 열에 안 섞는다 — 게이트가 걸린 판(실패>0)에서
    //    「뭐가 느렸길래 실패했나」를 따로 읽을 수 있어야 한다. 정상 판(실패 0)에서는 전부 NA다.
    f(m('t_session_start_fail', 'p(50)')), f(m('t_session_start_fail', 'p(95)')),
    f(m('t_session_start_fail', 'p(99)')), f(m('t_session_start_fail', 'max')),
    f(m('t_session_end_ok', 'p(50)')),   f(m('t_session_end_ok', 'p(95)')),
    f(m('t_session_end_ok', 'p(99)')),   f(m('t_session_end_ok', 'max')),
    f(m('t_session_end_fail', 'p(50)')), f(m('t_session_end_fail', 'p(95)')),
    f(m('t_session_end_fail', 'p(99)')), f(m('t_session_end_fail', 'max')),
    m('iterations', 'count', 0),
    // 🔴 이 셋이 게이트다 — 하나라도 0 이 아니면 그 판의 (ok) 지연 수치는 인용하면 안 된다.
    m('c_measured_failed', 'count', 0),         // 측정 두 요청의 실패 건수(setup 은 안 센다)
    m('dropped_iterations', 'count', 0),        // 도착률을 못 냈다 = 「배수」 진술이 깨진다
    m('c_conflict_409', 'count', 0),            // 계정 배타가 깨졌다
  ].join(' ');

  const out = { stdout: `\n[rig] ${row}\n` };
  if (__ENV.SUMMARY_OUT) out[__ENV.SUMMARY_OUT] = row + '\n';
  return out;
}
