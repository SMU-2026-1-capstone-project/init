// AI 서버(FastAPI) app/models/pose.py 매칭 — POST /api/v1/pose
import type { FeedbackType } from './feedback';

// AI 가 돌려주는 자세 결함 유형 (백엔드 FeedbackType 8종과 동일)
export type AiFeedbackType = FeedbackType;

// 실시간 포즈 감지 요청
export interface PoseDetectRequest {
  image: string; // base64 인코딩 프레임
  exercise_type: string; // 'squat' | 'lunge' | 'plank' ... (AI는 squat 구현)
  session_id: number;
  // 세션 소유권 비밀값 (이슈 #187). 세션 시작 응답의 sessionNonce 를 그대로 싣는다.
  // 없거나 틀리면 AI 가 프레임을 버리는데, 응답은 «세션 없음» 과 같은 모양이라
  // 클라에서는 «세션이 죽었다» 로 보인다 — 그게 의도다(살아있는 세션 열거를 막는다).
  session_nonce: string;
  // timestamp_sec 은 제거했다 (이슈 #156). 프레임 시각은 서버가 도착 시각으로 만든다 —
  // 여기서 보내던 Date.now()/1000 은 epoch 라 「세션 시작 기준 경과 초」가 아니었고,
  // 변환 없이 리포트까지 흘러 시각 표시가 무의미해졌다. AI 는 이 필드가 와도 읽지 않는다.
}

// 실시간 포즈 감지 응답
// ⚠️ AI PoseResponse 에는 feedback_type 필드가 아직 없음. exercise.tsx 가 r.feedback_type 을
//    읽으므로 optional 로 둠 — 실제 동작하려면 AI 서버 응답에 추가 필요(협의 대상).
// 프레임이 판정에 못 들어간 사유 (이슈 #267). AI PoseSkipReason 과 1:1 이다.
//   RATE_LIMITED         서버가 의도적으로 잘랐다 — 세션이 건강해도 나온다
//   NO_POSE·LOW_VISIBILITY   입력 문제 — 프레임은 왔는데 쓸 수가 없다
//   NO_LEASE·SESSION_NOT_FOUND·UNSUPPORTED_EXERCISE   세션 순서·상태 문제
export type PoseSkipReason =
  | 'RATE_LIMITED'
  | 'NO_POSE'
  | 'LOW_VISIBILITY'
  | 'NO_LEASE'
  | 'SESSION_NOT_FOUND'
  | 'UNSUPPORTED_EXERCISE';

export interface PoseDetectResponse {
  // 🔴 «판정에 들어갔는가» 다 (이슈 #267 에서 의미가 좁혀졌다). «요청이 처리됐는가» 가 아니다.
  //
  // 예전에는 유입 상한 드롭·가시성 부족 스킵이 success=true 로 와서, 200 이나 success 를 세면
  // 정상으로 보이는데 판정에 들어간 프레임은 0 일 수 있었다(#196 이 그렇게 오독했다).
  //
  // ⚠️ 지금 exercise.tsx 는 이 필드를 안 읽는다 — sync_rate·rep_count 만 본다. 그래서 계약이
  //    바뀌어도 화면은 그대로다(스켈레톤은 landmarks 로 그리고, 그 필드는 스킵에도 채워 온다).
  //    「판정이 한 번도 안 됐다」를 사용자에게 알리려면 여기를 읽는 것이 시작점이다.
  success: boolean;
  // success=false 일 때만 채워진다.
  skip_reason?: PoseSkipReason | null;
  sync_rate?: number | null;
  rep_count?: number | null;
  rep_completed?: boolean;
  feedback_type?: AiFeedbackType | null;
  angles?: number[] | null;
  message?: string | null;
}
