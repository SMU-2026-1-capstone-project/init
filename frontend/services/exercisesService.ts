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

export const exercisesService = {
  // 운동 세션 시작 (POST /exercises/sessions → 202)
  startSession: (data: StartSessionRequest) =>
    api.post<StartSessionResponse>('/exercises/sessions', data),

  // 운동 세션 종료 (PATCH /sessions/{id}/end → 200, 멱등)
  endSession: (sessionId: number) =>
    api.patch<void>(`/sessions/${sessionId}/end`),

  // 페르소나별 피드백 멘트 템플릿 (GET /exercises/{id}/feedback-templates)
  getFeedbackTemplates: (exerciseId: number) =>
    api.get<FeedbackTemplate[]>(`/exercises/${exerciseId}/feedback-templates`),

  // 세션 자세 결함 집계 (GET /sessions/{id}/feedback-summary)
  getSessionFeedbackSummary: (sessionId: number) =>
    api.get<SessionFeedbackSummary>(`/sessions/${sessionId}/feedback-summary`),
};
