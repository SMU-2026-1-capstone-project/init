export interface LoginRequest {
  email: string;
  password: string;
}

export interface SignupRequest {
  email: string;
  password: string;
  passwordConfirm: string;
}

export interface AuthResponse {
  token: string;
  user: User;
}

export interface User {
  id: number;
  email: string;
  nickname?: string;
  createdAt?: string;
}
