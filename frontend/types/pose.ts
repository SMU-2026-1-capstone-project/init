// AI 서버(FastAPI) app/models/pose.py 매칭 — POST /api/v1/pose
import type { FeedbackType } from './feedback';

// AI 가 돌려주는 자세 결함 유형 (백엔드 FeedbackType 8종과 동일)
export type AiFeedbackType = FeedbackType;

// 실시간 포즈 감지 요청
export interface PoseDetectRequest {
  image: string; // base64 인코딩 프레임
  exercise_type: string; // 'squat' | 'lunge' | 'plank' ... (AI는 squat 구현)
  session_id: number;
  // timestamp_sec 은 제거했다 (이슈 #156). 프레임 시각은 서버가 도착 시각으로 만든다 —
  // 여기서 보내던 Date.now()/1000 은 epoch 라 「세션 시작 기준 경과 초」가 아니었고,
  // 변환 없이 리포트까지 흘러 시각 표시가 무의미해졌다. AI 는 이 필드가 와도 읽지 않는다.
}

// 실시간 포즈 감지 응답
// ⚠️ AI PoseResponse 에는 feedback_type 필드가 아직 없음. exercise.tsx 가 r.feedback_type 을
//    읽으므로 optional 로 둠 — 실제 동작하려면 AI 서버 응답에 추가 필요(협의 대상).
export interface PoseDetectResponse {
  success: boolean;
  sync_rate?: number | null;
  rep_count?: number | null;
  rep_completed?: boolean;
  feedback_type?: AiFeedbackType | null;
  angles?: number[] | null;
  message?: string | null;
}
