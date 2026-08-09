import api from './api';
import type {
  LoginRequest,
  LoginResponse,
  SignupRequest,
  ReissueRequest,
} from '@/types/auth';

// 백엔드 MemberController prefix: /member
export const authService = {
  login: (data: LoginRequest) =>
    api.post<LoginResponse>('/member/login', data),

  // 백엔드 signup 응답은 단순 String("회원가입 성공" 등)
  signup: (data: SignupRequest) =>
    api.post<string>('/member/signup', data),

  // 본문이 없다 — 서버는 지울 대상을 Authorization 헤더의 요청자로 정한다 (#137 ㄴ-4).
  // 예전에는 accessToken·refreshToken 을 실어 보냈는데, 전자는 블랙리스트 등록에만 쓰이다
  // 그 기능이 없어졌고 후자는 요청자 기준으로 바뀐 시점에 이미 안 쓰이고 있었다.
  logout: () => api.post<void>('/member/logout'),

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
