// 백엔드 DTO 기준 (com.shadowfit.dto.login, com.shadowfit.model.member.UserRole, Sex)

export type UserRole = 'ADMIN' | 'USER';
export type Sex = 'MALE' | 'FEMALE' | 'NONE'; // 백엔드 enum 오타(FEAMALE) 수정에 맞춰 정정 — issue #106 계열

// 백엔드 LoginRequestDto
export interface LoginRequest {
  email: string;
  password: string;
}

// 백엔드 LoginResponseDto
export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  role: UserRole;
}

// 백엔드 MemberRequestDto
export interface SignupRequest {
  username: string;
  email: string;
  password: string;
  sex: Sex;
  // role 은 없다 — 서버가 USER 로 고정한다 (이슈 #138).
  // 보내도 서버가 무시하지만, 타입에 두면 "정할 수 있는 값"으로 읽힌다.
}

// LogoutRequest 는 없앴다 — POST /member/logout 은 본문을 받지 않는다 (이슈 #137).
// 서버가 지울 대상을 Authorization 헤더의 요청자로 정하므로 보낼 것이 없다.

// 백엔드 ReissueRequestDto (이슈 #135)
//
// refresh 하나만 보낸다. 이 API 는 access 가 **만료된 뒤**에 부르는 것이 정상 경로라
// 인증을 안 태우고, 신원은 refresh token 자신의 서명에서 나온다.
// 응답은 LoginResponse 와 같은 모양이다 — access·refresh 가 **둘 다** 새 값으로 온다(회전).
export interface ReissueRequest {
  refreshToken: string;
}

// 클라이언트에서 보관하는 사용자 식별 정보
// 백엔드에 getMe 엔드포인트가 없어서 토큰에 담긴 정보로만 구성
export interface AuthUser {
  email: string;
  role: UserRole;
}
