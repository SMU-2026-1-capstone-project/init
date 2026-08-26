import axios from 'axios';
import Constants from 'expo-constants';
import { Platform } from 'react-native';
import type { PoseDetectRequest, PoseDetectResponse } from '@/types/pose';

// AI 서버(FastAPI)는 Spring(:8080)이 아니라 별도 :8000 /api/v1 로 직결한다 (분기 H2).
//
// 🔴 **릴리스와 개발이 갈린다** (이슈 #148). 예전에는 갈리지 않았고, 그게 결함이었다 —
// 릴리스 빌드에는 Metro 가 없어 `hostUri` 가 빈 문자열이라 아래 개발 경로가 조용히
// `http://localhost:8000` 을 만들어냈다. 그리고 iOS ATS·Android(API 28+) 가 평문 HTTP 를
// 차단하므로, **설정을 안 한 실수가 「연결 거부」라는 엉뚱한 증상으로** 나타났다.
// 원인이 URL 이라는 단서가 어디에도 없었다.
//
// 그래서 릴리스에서는 **명시적으로 주입한 https 주소만** 받는다. 못 만들면 아래
// `aiConfigError` 에 이유를 담고, 화면이 그것을 사용자에게 보여준다(조용히 실패하지 않는다).
//
// ⚠️ 프로덕션 주소를 여기 하드코딩하지 않는다. 배포 호스트도 도메인도 아직 정해지지 않았고
//    (docs/decisions/reverse-proxy-and-tls.md §8 선행 조건이 전부 비어 있다), 지어낸 도메인을
//    코드에 박는 것이 지금 혼란의 원인이기도 하다(api.ts 의 「추후 프로덕션 URL」).

/** 릴리스에서 base URL 을 못 만든 이유. `null` 이면 정상. */
export let aiConfigError: string | null = null;

function resolveAiBaseUrl(): string {
  const override = process.env.EXPO_PUBLIC_AI_BASE_URL;

  if (!__DEV__) {
    // 릴리스: 주입된 값만 쓴다. 개발 호스트 추론으로 **떨어지지 않는다**.
    if (!override) {
      aiConfigError =
        'AI 서버 주소가 설정되지 않았습니다 (EXPO_PUBLIC_AI_BASE_URL). 빌드 설정을 확인해 주세요.';
      return '';
    }
    if (!override.startsWith('https://')) {
      // http 로 주입해도 ATS·cleartext 정책이 막는다. 여기서 걸러야 「왜 안 붙지」가 아니라
      // 「주소가 https 가 아니다」로 보인다.
      aiConfigError =
        'AI 서버 주소가 https 가 아닙니다 (EXPO_PUBLIC_AI_BASE_URL). 평문 HTTP 는 앱에서 차단됩니다.';
      return '';
    }
    return override;
  }

  // 개발: Metro 호스트(=PC LAN IP)를 그대로 쓰되 포트만 8000.
  // 안드로이드 에뮬레이터는 호스트 PC 를 10.0.2.2 로 봐야 한다.
  if (override) return override;

  const hostUri = Constants.expoConfig?.hostUri ?? '';
  let host = hostUri.split(':')[0] || 'localhost';
  if (Platform.OS === 'android' && (host === 'localhost' || host.startsWith('127.'))) {
    host = '10.0.2.2';
  }
  return `http://${host}:8000/api/v1`;
}

const aiApi = axios.create({
  baseURL: resolveAiBaseUrl(),
  timeout: 8000,
  headers: { 'Content-Type': 'application/json' },
});

// AI 서버 InternalAuthMiddleware: Authorization: Bearer <AI_PUBLIC_TOKEN> 강제.
// 토큰 미설정 시 헤더 없이 보냄(=401) — exercise.tsx 가 토큰 없으면 폴링 자체를 안 함.
//
// ⚠️ 이 값은 앱 번들에 인라인된다(EXPO_PUBLIC_ 접두). 그래서 Spring↔AI 내부 gRPC 토큰
// (INTERNAL_API_TOKEN)과 **값이 분리돼 있다** — 이슈 #134. 여기에 내부 토큰을 다시
// 붙이면 앱에서 추출한 값으로 Spring 내부 RPC 를 칠 수 있게 되므로 되돌리지 말 것.
aiApi.interceptors.request.use((config) => {
  const token = process.env.EXPO_PUBLIC_AI_PUBLIC_TOKEN;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export const aiService = {
  // 이 경로를 쓸 수 있는 상태인가. false 면 aiConfigError 에 이유가 있다.
  isConfigured: () => aiConfigError === null,

  // 실시간 포즈 감지 (POST {ai}:8000/api/v1/pose)
  //
  // workerIndex: 세션 시작 응답(aiWorkerIndex)을 그대로 되돌려준다. AI를 프로세스
  // 여러 개로 나눈 뒤(2026-08-26, GIL 병목 회피), 세션 상태가 프로세스 로컬 메모리에만
  // 있어 앞단 nginx가 X-AI-Worker 헤더로 항상 같은 워커에 고정 라우팅해야 한다 — 안 주면
  // nginx가 워커 0으로 기본 전달하는데, 세션이 다른 워커에서 시작됐으면 NO_LEASE로 거절된다.
  detectPose: (data: PoseDetectRequest, workerIndex?: number | null) =>
    aiApi.post<PoseDetectResponse>('/pose', data, {
      headers: workerIndex != null ? { 'X-AI-Worker': String(workerIndex) } : {},
    }),
};
