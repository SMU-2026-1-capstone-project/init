import axios from 'axios';
import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import Constants from 'expo-constants';
import { router } from 'expo-router';

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
  }
  return config;
});

// 재발급이 진행 중이면 그 약속을 공유한다 (이슈 #135).
//
// 이게 없으면 401 이 동시에 N 개 터질 때 재발급도 N 번 돈다. 서버는 회전(rotation)을 하므로
// 첫 번째가 refresh 를 바꿔버리고 나머지는 **구본**을 들고 가게 된다. 서버에 유예창(10초)이
// 있어 그 경우도 «재시도» 로 받아주지만, 그건 안전망이지 설계가 아니다 — 유예를 매번 쓰면
// 그 창이 정말 필요한 «응답 유실» 케이스와 구분이 안 된다.
let reissuePromise: Promise<string | null> | null = null;

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

// 재발급을 한 번만 돌리고 새 access token 을 돌려준다. 실패하면 null.
async function reissueOnce(): Promise<string | null> {
  if (!reissuePromise) {
    reissuePromise = (async () => {
      try {
        const refreshToken = await SecureStore.getItemAsync('refreshToken');
        if (!refreshToken) return null;

        const res = await reissueClient.post('/member/reissue', { refreshToken });
        const { accessToken, refreshToken: rotated } = res.data;

        // ⚠️ **둘 다** 저장해야 한다. 서버가 회전하므로 refresh 도 새 값이고,
        // 옛 refresh 를 들고 있으면 다음 재발급이 «폐기된 구본» 으로 판정돼 세션이 끊긴다.
        await SecureStore.setItemAsync('accessToken', accessToken);
        await SecureStore.setItemAsync('refreshToken', rotated);

        const { useAuthStore } = require('@/stores/authStore');
        useAuthStore.getState().applyReissuedTokens(accessToken, rotated);

        return accessToken;
      } catch {
        return null;
      } finally {
        reissuePromise = null;
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

    if (status === 401 && hadAuthHeader && !config._retried) {
      // 재시도는 딱 한 번. 새 토큰으로도 401 이면 그건 만료가 아니라 권한 문제다.
      config._retried = true;

      const newAccessToken = await reissueOnce();
      if (newAccessToken) {
        config.headers.Authorization = `Bearer ${newAccessToken}`;
        return api.request(config);
      }
    }

    if (status === 401 && hadAuthHeader) {
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
