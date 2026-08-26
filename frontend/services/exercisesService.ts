import api from './api';
import type { FeedbackTemplate, SessionFeedbackSummary } from '@/types/feedback';

interface StartSessionRequest {
  exerciseId: number;
}

// 백엔드 ExercisesResponseDto (POST /exercises/sessions, 202)
interface StartSessionResponse {
  sessionId: number;
  exerciseId: number;
  startTime: string;
  status: string;
  // 세션 소유권 비밀값 (이슈 #187). 이 응답으로만 내려오고, 이후 POST /pose 마다 동봉해야
  // AI 가 «이 세션을 만든 클라» 로 인정한다. session_id 는 순차 정수라 추측되지만 이 값은
  // 안 된다 — 안 보내면 프레임이 조용히 버려진다.
  //
  // 🔴 화면·로그·저장소 어디에도 남기지 말 것. 세션이 끝나면 같이 버린다.
  sessionNonce: string;
  // AI 워커 인덱스(0~N-1, 2026-08-26). POST /pose 마다 X-AI-Worker 헤더로 동봉할 것 —
  // 안 보내면 프레임이 세션 시작 때와 다른 AI 프로세스로 갈 수 있어 NO_LEASE로 거절된다.
  aiWorkerIndex: number | null;
}

// 백엔드 ActiveSessionResponseDto (GET /sessions/active, 200 또는 204)
export interface ActiveSessionResponse {
  sessionId: number;
  exerciseId: number;
  exerciseName: string;
  startTime: string;
  status: string;
  // null이면 운동 중(이어하기 가능), 값이 있으면 사용자가 이미 종료를 눌러 결과 처리 대기 중
  // — 이 경우 이어하기를 권하면 안 된다.
  endTime: string | null;
  sessionNonce: string | null;
  aiWorkerIndex: number | null;
}

// 백엔드 ReattachSessionResponseDto (POST /sessions/{id}/reattach, 200)
export interface ReattachSessionResponse {
  sessionId: number;
  // 이어서 셀 기준 rep 수 — UI 카운터를 이 값으로 맞춰야 한다(0부터 그리면 서버 집계와 어긋남).
  restoredRepCount: number;
  // true = AI 상태가 이미 살아있어 복원이 불필요했다(손실 없음).
  alreadyActive: boolean;
  // true = 분석기 내부 상태(rep 진행 단계·각도 스무딩 이력)가 리셋됐다. 진행 중이던 rep은
  // 어느 쪽이든 버려진다 — 그대로 노출해도 되는 문구가 message 에 온다.
  analyzerStateReset: boolean;
  message: string;
  sessionNonce: string | null;
  aiWorkerIndex: number | null;
}

export const exercisesService = {
  // 운동 세션 시작 (POST /exercises/sessions → 202)
  startSession: (data: StartSessionRequest) =>
    api.post<StartSessionResponse>('/exercises/sessions', data),

  // 운동 세션 종료 (PATCH /sessions/{id}/end → 200, 멱등)
  endSession: (sessionId: number) =>
    api.patch<void>(`/sessions/${sessionId}/end`),

  // 진행 중인 내 세션 조회 (GET /sessions/active → 200 + 세션 또는 204 없음).
  // 204는 axios 예외가 아니다 — 호출부가 res.status로 갈라야 한다.
  getActiveSession: () => api.get<ActiveSessionResponse>('/sessions/active'),

  // 세션 재부착 — 이어하기 (POST /sessions/{id}/reattach → 200 / 404 / 410 / 503).
  // 프레임 전송 전에 반드시 먼저 불러야 한다 — 안 부르면 AI가 프레임을 전부 거부한다(#59).
  reattachSession: (sessionId: number) =>
    api.post<ReattachSessionResponse>(`/sessions/${sessionId}/reattach`),

  // 페르소나별 피드백 멘트 템플릿 (GET /exercises/{id}/feedback-templates)
  getFeedbackTemplates: (exerciseId: number) =>
    api.get<FeedbackTemplate[]>(`/exercises/${exerciseId}/feedback-templates`),

  // 세션 자세 결함 집계 (GET /sessions/{id}/feedback-summary)
  getSessionFeedbackSummary: (sessionId: number) =>
    api.get<SessionFeedbackSummary>(`/sessions/${sessionId}/feedback-summary`),
};
