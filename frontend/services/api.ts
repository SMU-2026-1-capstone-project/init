import axios from 'axios';
import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import Constants from 'expo-constants';
import { router } from 'expo-router';
import { currentSessionVersion } from './sessionVersion';

// Expo dev 모드에서는 Metro 가 알려주는 호스트(=PC LAN IP)를 자동으로 사용한다.
// 안드로이드 에뮬레이터는 호스트 PC 를 10.0.2.2 로 봐야 하므로 별도 처리.
function resolveDevHost(): string {
  if (Platform.OS === 'android') {
    // 에뮬레이터에서 hostUri 가 127.0.0.1 또는 10.x.x.x 처럼 잡힐 수 있어
    // 안드로이드 에뮬레이터 표준 매핑인 10.0.2.2 를 우선 사용한다.
    const hostUri = Constants.expoConfig?.hostUri ?? '';
    const host = hostUri.split(':')[0];
    if (!host || host === 'localhost' || host.startsWith('127.')) {
      return '10.0.2.2';
    }
    return host;
  }

  // iOS 시뮬레이터 / 웹 / 실제 디바이스 모두 Metro 호스트와 같은 네트워크
  const hostUri = Constants.expoConfig?.hostUri ?? '';
  const host = hostUri.split(':')[0];
  return host || 'localhost';
}

const BASE_URL = __DEV__
  ? `http://${resolveDevHost()}:8080`
  : 'https://api.shadowfit.com'; // 추후 프로덕션 URL

// 백엔드 컨트롤러 prefix가 /api/v1 이 아니라 /member, /exercises, /reports 등이라
// baseURL 에 prefix 를 붙이지 않는다.
const api = axios.create({
  baseURL: BASE_URL,
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
});

// 디버깅용: 실제 어디로 붙는지 부팅 시 한 번 출력
if (__DEV__) {
  // eslint-disable-next-line no-console
  console.log('[api] baseURL =', BASE_URL);
}

// 요청 인터셉터: JWT accessToken 자동 첨부
api.interceptors.request.use(async (config) => {
  const token = await SecureStore.getItemAsync('accessToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
    // 이 요청이 **어느 세션의 것인지** 같이 찍는다 (이슈 #170).
    // 응답이 돌아왔을 때 세션이 이미 바뀌었다면, 그 401 은 죽은 세션의 것이라
    // 현재 세션을 로그아웃시킬 근거가 되지 못한다.
    config._sessionVersion = currentSessionVersion();
  }
  return config;
});

// 커스텀 플래그 — axios 설정 객체에 우리가 얹는 값들.
// 선언해두지 않으면 요청 인터셉터(타입이 붙어 있는 자리)에서 대입이 컴파일되지 않는다.
declare module 'axios' {
  interface InternalAxiosRequestConfig {
    _retried?: boolean;
    _sessionVersion?: number;
  }
}

// 재발급 결과. **세 갈래여야 한다** (이슈 #170).
//
// 예전에는 `string | null` 이라 «실패» 와 «세션이 바뀌어 버려진 결과» 가 똑같이 null 이었다.
// 그 둘을 합치면 후자에서도 forceLogout 이 돌아 **새 세션이 로그아웃된다** — 고치려던 것과
// 정확히 같은 사고다.
type ReissueResult =
  | { status: 'ok'; accessToken: string }
  | { status: 'stale' }    // 세션이 바뀌었다 — 이 요청만 실패시키고 현재 세션은 건드리지 않는다
  | { status: 'failed' };  // 재발급 자체가 실패했다 — 세션이 끝났다

// 재발급이 진행 중이면 그 약속을 공유한다 (이슈 #135).
//
// 이게 없으면 401 이 동시에 N 개 터질 때 재발급도 N 번 돈다. 서버는 회전(rotation)을 하므로
// 첫 번째가 refresh 를 바꿔버리고 나머지는 **구본**을 들고 가게 된다. 서버에 유예창(10초)이
// 있어 그 경우도 «재시도» 로 받아주지만, 그건 안전망이지 설계가 아니다 — 유예를 매번 쓰면
// 그 창이 정말 필요한 «응답 유실» 케이스와 구분이 안 된다.
//
// ⚠️ 공유는 **같은 세션 안에서만** 유효하다 (이슈 #170). 세션이 바뀐 뒤에도 옛 promise 를
// 재사용하면 새 세션의 401 이 남의 재발급 결과를 기다렸다가 stale 로 버려진다.
let reissuePromise: Promise<ReissueResult> | null = null;
let reissuePromiseVersion = -1;

// 재발급 전용 클라이언트 — **인터셉터가 없다**. 이게 요점이다.
//
// api 로 재발급을 부르면 그 요청이 401 일 때 응답 인터셉터가 또 돈다. 재귀는 url 검사로
// 막을 수 있지만, 그 경로에서 forceLogout 이 한 번 돌고 바깥 호출자가 또 한 번 돌려서
// **로그아웃과 화면 이동이 두 번** 일어난다. 아예 안 태우는 쪽이 분기보다 단순하다.
//
// 요청 인터셉터도 안 붙는다 — 만료된 access token 을 실어 보낼 이유가 없다.
// (/member/reissue 는 permitAll 이라 서버가 무시하긴 하지만, 안 보내는 게 맞다)
const reissueClient = axios.create({
  baseURL: BASE_URL,
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
});

// 재발급을 한 번만 돌리고 새 access token 을 돌려준다.
async function reissueOnce(): Promise<ReissueResult> {
  const startedVersion = currentSessionVersion();

  if (!reissuePromise || reissuePromiseVersion !== startedVersion) {
    reissuePromiseVersion = startedVersion;
    reissuePromise = (async (): Promise<ReissueResult> => {
      try {
        const refreshToken = await SecureStore.getItemAsync('refreshToken');
        if (!refreshToken) return { status: 'failed' };

        const res = await reissueClient.post('/member/reissue', { refreshToken });
        const { accessToken, refreshToken: rotated } = res.data;

        // 🔴 여기가 경합의 자리다 (이슈 #170). 위 두 await 를 기다리는 동안 사용자가
        // 로그아웃했거나 다른 계정으로 로그인했을 수 있다. 그러면 지금 손에 든 토큰은
        // **이미 남의 세션 것**이고, 저장하면 화면 사용자와 Authorization 이 갈린다.
        if (currentSessionVersion() !== startedVersion) {
          return { status: 'stale' };
        }

        // ⚠️ **둘 다** 저장해야 한다. 서버가 회전하므로 refresh 도 새 값이고,
        // 옛 refresh 를 들고 있으면 다음 재발급이 «폐기된 구본» 으로 판정돼 세션이 끊긴다.
        //
        // 순서가 refresh 먼저인 것도 이유가 있다 (이슈 #171). 두 쓰기 사이에서 앱이 죽으면
        // 이 순서에서는 «옛 access + 새 refresh» 가 남는다 — 옛 access 는 곧 401 을 받고
        // 그때 새 refresh 로 재발급이 성공하니 **스스로 복구된다.** 반대로 access 를 먼저
        // 쓰면 «새 access + 폐기된 refresh» 가 남아 다음 재발급이 세션을 끊는다.
        await SecureStore.setItemAsync('refreshToken', rotated);
        await SecureStore.setItemAsync('accessToken', accessToken);

        const { useAuthStore } = require('@/stores/authStore');
        useAuthStore.getState().applyReissuedTokens(accessToken, rotated);

        return { status: 'ok', accessToken };
      } catch {
        return { status: 'failed' };
      } finally {
        // 내 것일 때만 치운다. 세션이 바뀌어 새 promise 가 들어섰다면 그걸 지우면 안 된다.
        if (reissuePromiseVersion === startedVersion) {
          reissuePromise = null;
        }
      }
    })();
  }
  return reissuePromise;
}

// 응답 인터셉터: 401 이면 **먼저 재발급을 시도**하고, 그래도 안 되면 강제 로그아웃 (이슈 #135).
//
// 예전에는 401 을 받으면 곧바로 forceLogout 이었다. 그래서 서버에 재발급이 생겨도 효과가 0
// 이었고, access 수명을 줄일 수도 없었다(줄이면 그 주기로 로그아웃됐다).
//
// 단, "로그인/회원가입 자체"의 401 은 잘못된 비번을 알려야 하니 처리하지 않음
//   → Authorization 헤더가 실제로 첨부됐던 요청에서만 동작
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const status = error.response?.status;
    const config = error.config;
    const hadAuthHeader = !!config?.headers?.Authorization;

    // 재발급 요청은 reissueClient 로 나가서 이 인터셉터를 아예 안 탄다 — 재귀 걱정이 없다.

    // 이 401 이 **아직 살아 있는 세션의 것인가** (이슈 #170).
    // 요청이 나갈 때 찍어둔 버전과 지금이 다르면, 그 사이 로그아웃·계정 전환이 있었다는 뜻이다.
    // 죽은 세션의 뒤늦은 401 로 현재 세션을 건드리면 안 된다.
    const fromLiveSession = config?._sessionVersion === currentSessionVersion();

    if (status === 401 && hadAuthHeader && fromLiveSession && !config._retried) {
      // 재시도는 딱 한 번. 새 토큰으로도 401 이면 그건 만료가 아니라 권한 문제다.
      config._retried = true;

      const result = await reissueOnce();
      if (result.status === 'ok') {
        config.headers.Authorization = `Bearer ${result.accessToken}`;
        return api.request(config);
      }
      if (result.status === 'stale') {
        // 재발급이 도는 사이에 세션이 바뀌었다. 이 요청만 실패시키고 아래 로그아웃 경로는 타지 않는다.
        return Promise.reject(error);
      }
    }

    if (status === 401 && hadAuthHeader && fromLiveSession) {
      // 재발급이 실패했거나 이미 한 번 재시도한 뒤다 — 세션이 끝났다.
      //
      // ⚠️ 서버가 «폐기된 구본» 으로 판정한 경우(A006)도 여기로 온다. 그 둘을 구분하려면
      // 응답에 에러 코드가 있어야 하는데 ErrorResponseDto 는 status·message·timestamp 만
      // 싣는다 — 지금은 둘 다 같은 처리를 받는다.
      const { useAuthStore } = require('@/stores/authStore');
      await useAuthStore.getState().forceLogout();
      // _layout 가드는 __DEV__ 에서 자동 redirect 를 안 시키므로
      // 토큰 만료 케이스만큼은 명시적으로 로그인 화면으로 이동
      router.replace('/(auth)/login');
    }
    return Promise.reject(error);
  }
);

export default api;
