import api from './api';
import type {
  LoginRequest,
  LoginResponse,
  SignupRequest,
  LogoutRequest,
  ReissueRequest,
} from '@/types/auth';

// 백엔드 MemberController prefix: /member
export const authService = {
  login: (data: LoginRequest) =>
    api.post<LoginResponse>('/member/login', data),

  // 백엔드 signup 응답은 단순 String("회원가입 성공" 등)
  signup: (data: SignupRequest) =>
    api.post<string>('/member/signup', data),

  // 백엔드 LogOutRequestDto 가 무엇을 요구하는지 정확히 모르면 토큰 기반으로 보냄
  logout: (data: LogoutRequest) =>
    api.post<void>('/member/logout', data),

  // 회원 탈퇴
  deleteAccount: (email: string) =>
    api.delete<void>(`/member/${encodeURIComponent(email)}`),

  // 토큰 재발급 (이슈 #135).
  //
  // ⚠️ 이걸 직접 부르지 마라 — api.ts 의 401 인터셉터가 자동으로 부른다. 손으로 부르면
  // 회전이 두 번 돌아 서로의 토큰을 무효화한다. 여기 두는 것은 인터셉터가 쓰기 위해서다.
  reissue: (data: ReissueRequest) =>
    api.post<LoginResponse>('/member/reissue', data),
};
