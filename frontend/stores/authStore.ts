import { create } from 'zustand';
import * as SecureStore from 'expo-secure-store';
import { authService } from '@/services/authService';
import { memberService } from '@/services/memberService';
import { bumpSessionVersion } from '@/services/sessionVersion';
import type {
  AuthUser,
  LoginRequest,
  SignupRequest,
  UserRole,
} from '@/types/auth';
import type { OnboardingResponse } from '@/types/user';

interface AuthState {
  user: AuthUser | null;
  accessToken: string | null;
  refreshToken: string | null;
  role: UserRole | null;
  isLoading: boolean;
  isAuthenticated: boolean;
  // null = 아직 조회 안 함 (라우팅 가드는 이 동안 대기)
  onboardingCompleted: boolean | null;

  login: (data: LoginRequest) => Promise<void>;
  signup: (data: SignupRequest) => Promise<void>;
  logout: () => Promise<void>;
  // 토큰 만료 등으로 서버 호출 없이 로컬 세션만 정리할 때 사용
  forceLogout: () => Promise<void>;
  // api.ts 의 401 인터셉터가 재발급에 성공하면 호출한다 (이슈 #135).
  // SecureStore 쓰기는 인터셉터가 이미 했고, 여기서는 메모리 상태만 맞춘다.
  applyReissuedTokens: (accessToken: string, refreshToken: string) => void;
  restoreSession: () => Promise<void>;
  // 온보딩 완료 화면에서 PATCH 후 호출
  markOnboardingCompleted: () => void;
  // 마이페이지에서 수동 갱신용 (서버 데이터 다시 가져옴)
  refreshOnboardingStatus: () => Promise<void>;
}

// 백엔드 OnboardingService.completeOnboarding 조건과 동일
// (selectedPersona 는 Member 기본값이 BEGINNER 라 항상 not-null → 제외)
function isOnboardingCompleted(data: OnboardingResponse): boolean {
  return (
    data.workoutLevel != null &&
    data.height != null &&
    data.weight != null &&
    !!data.preferredUrl
  );
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  accessToken: null,
  refreshToken: null,
  role: null,
  isLoading: true,
  isAuthenticated: false,
  onboardingCompleted: null,

  login: async (data) => {
    const res = await authService.login(data);
    const { accessToken, refreshToken, role } = res.data;

    // 여기서부터 «다른 사람» 이다 (이슈 #170). 진행 중이던 재발급이 이 뒤에 끝나면
    // 그 결과는 옛 세션 것이라 버려져야 한다.
    //
    // 위치가 응답 **뒤**인 것이 요점이다. 앞에서 올리면 로그인이 실패했을 때 세션은 그대로인데
    // 버전만 어긋나, 살아 있는 세션의 401 이 전부 «죽은 세션» 으로 판정되어 재발급이 멈춘다.
    bumpSessionVersion();

    await SecureStore.setItemAsync('accessToken', accessToken);
    await SecureStore.setItemAsync('refreshToken', refreshToken);
    await SecureStore.setItemAsync('userEmail', data.email);

    set({
      user: { email: data.email, role },
      accessToken,
      refreshToken,
      role,
      isAuthenticated: true,
      isLoading: false,
      onboardingCompleted: null, // 조회 직전엔 null
    });

    // 온보딩 상태 조회 (실패해도 로그인 자체는 유지)
    try {
      const onboardingRes = await memberService.getOnboarding(data.email);
      set({ onboardingCompleted: isOnboardingCompleted(onboardingRes.data) });
    } catch {
      // 조회 실패 시 미완료로 간주 (안전한 fallback)
      set({ onboardingCompleted: false });
    }
  },

  // 백엔드 signup 은 토큰을 주지 않으므로 회원가입 후 자동 login 호출
  // login 흐름이 onboardingCompleted 까지 set 해주므로 여기선 추가 처리 없음
  // 신규 가입자는 5개 필드가 다 비어있으니 자동으로 false 가 됨
  signup: async (data) => {
    await authService.signup(data);
    await get().login({ email: data.email, password: data.password });
  },

  logout: async () => {
    // 서버 호출보다 **먼저** 올린다 (이슈 #170). 로그아웃은 서버 응답과 무관하게 로컬 세션을
    // 정리하므로(아래 catch), 이 시점에 세션은 이미 끝난 것으로 본다. 늦게 올리면 서버 왕복
    // 동안 완료된 재발급이 토큰을 다시 써넣어 «로그아웃했는데 로그인 상태» 가 된다.
    bumpSessionVersion();

    try {
      // 본문 없이 부른다 (#137). Authorization 헤더는 요청 인터셉터가 붙인다 —
      // 토큰이 없으면 서버가 401 을 주고, 그 경우에도 아래 로컬 정리는 그대로 돈다.
      await authService.logout();
    } catch {
      // 서버 에러는 무시하고 로컬 세션은 정리
    }
    await SecureStore.deleteItemAsync('accessToken');
    await SecureStore.deleteItemAsync('refreshToken');
    await SecureStore.deleteItemAsync('userEmail');

    set({
      user: null,
      accessToken: null,
      refreshToken: null,
      role: null,
      isAuthenticated: false,
      onboardingCompleted: null,
    });
  },

  restoreSession: async () => {
    try {
      const accessToken = await SecureStore.getItemAsync('accessToken');
      const refreshToken = await SecureStore.getItemAsync('refreshToken');
      const email = await SecureStore.getItemAsync('userEmail');

      if (accessToken && email) {
        set({
          user: { email, role: 'USER' },
          accessToken,
          refreshToken,
          role: 'USER',
          isAuthenticated: true,
          onboardingCompleted: null,
        });

        // 온보딩 상태 조회
        try {
          const onboardingRes = await memberService.getOnboarding(email);
          set({
            onboardingCompleted: isOnboardingCompleted(onboardingRes.data),
            isLoading: false,
          });
        } catch {
          set({ onboardingCompleted: false, isLoading: false });
        }
      } else {
        set({ isLoading: false });
      }
    } catch {
      await SecureStore.deleteItemAsync('accessToken');
      await SecureStore.deleteItemAsync('refreshToken');
      await SecureStore.deleteItemAsync('userEmail');
      set({
        user: null,
        accessToken: null,
        refreshToken: null,
        role: null,
        isAuthenticated: false,
        onboardingCompleted: null,
        isLoading: false,
      });
    }
  },

  // 서버에 logout API 를 호출하지 않고 로컬만 정리 (토큰 만료 케이스용)
  forceLogout: async () => {
    bumpSessionVersion();   // 이슈 #170 — logout 과 같은 이유
    await SecureStore.deleteItemAsync('accessToken');
    await SecureStore.deleteItemAsync('refreshToken');
    await SecureStore.deleteItemAsync('userEmail');
    set({
      user: null,
      accessToken: null,
      refreshToken: null,
      role: null,
      isAuthenticated: false,
      onboardingCompleted: null,
      isLoading: false,
    });
  },

  // 재발급 성공 — 세션은 그대로고 토큰만 바뀐다 (이슈 #135).
  //
  // isAuthenticated·user·onboardingCompleted 를 **건드리지 않는 것이 요점**이다. 재발급은
  // 로그인이 아니라 «이어서 쓰는 것» 이라, 여기서 상태를 다시 세우면 화면이 재마운트되거나
  // 라우팅 가드가 한 번 튄다 — 사용자에게는 «갑자기 깜빡였다» 로 보인다.
  applyReissuedTokens: (accessToken, refreshToken) =>
    set({ accessToken, refreshToken }),

  markOnboardingCompleted: () => set({ onboardingCompleted: true }),

  refreshOnboardingStatus: async () => {
    const email = get().user?.email;
    if (!email) return;
    try {
      const onboardingRes = await memberService.getOnboarding(email);
      set({ onboardingCompleted: isOnboardingCompleted(onboardingRes.data) });
    } catch {
      // 조회 실패 시 기존 값 유지
    }
  },
}));
