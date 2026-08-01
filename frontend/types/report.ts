// 백엔드 dto/report/* 와 1:1 매칭되는 프론트 타입.
// 필드명/형태는 Spring DTO 기준 (camelCase JSON 직렬화 그대로).

/* ── 달력 (record/CalendarMainResponseDto, CalendarDayDto) ── */
export interface CalendarDay {
  date: string;                    // "2026-06-04"
  hasRecord: boolean;              // 운동 기록 존재 여부 (점 표시용)
  dailyAvgSyncRate: number | null; // 그 날 평균 싱크로율
}

export interface CalendarMainResponse {
  monthlyExerciseDays: number;     // 이번 달 운동 일수
  totalAvgSyncRate: number;        // 이번 달 평균 싱크로율
  consecutiveDays: number;         // 연속 운동 일수
  year: number;
  month: number;
  records: CalendarDay[];
}

/* ── 주간 활동 (record/WeeklyActivityResponseDto 등) ── */
export interface DailyLogSummary {
  dayOfWeek: string;               // "월", "화" ...
  workoutMinutes: number;          // 그 요일 운동 시간 (막대 높이)
  isToday: boolean;
}

export interface ExerciseSession {
  sessionId: number;               // 클릭 시 상세 이동용
  exerciseName: string;            // "스쿼트"
  setSummary: string;              // "1세트 x 12회"
  syncRate: number;
}

export interface WeeklyActivityResponse {
  dateRange: string;               // "6월 2일 - 8일"
  totalWorkouts: number;
  totalMinutes: number;
  totalCalories: number;
  dailyLogs: DailyLogSummary[];
  todayDetails: ExerciseSession[];
}

/* ── 특정 날짜 운동 목록 (record/DailyActivityResponseDto) ── */
export interface DailyActivityResponse {
  date: string;
  totalWorkouts: number;
  sessions: ExerciseSession[];
}

/* ── 세션 상세 보고서 (detailreport/*) ── */
export interface WorstSection {
  repNumber: number;               // 2 — repTrend 의 어느 점이 worst 인지 잇는 키
  exerciseName: string;
  timeStamp: string;               // "01:15" — 그 회차의 중앙 프레임
  /**
   * "2회차 · 싱크로율 75%"
   *
   * ⚠️ 문구는 잠정이다(이슈 #80). 회차를 알아야 하면 이 문자열을 파싱하지 말고
   * repNumber 를 쓸 것 — 문구가 확정되면 파싱은 깨진다.
   */
  reason: string;
}

/** 회차(rep) 하나의 싱크로율 — 추이 그래프의 한 점 */
export interface RepSyncRate {
  repNumber: number;               // 1부터, 세션 전체 기준 연번
  syncRate: number;                // 소수 1자리
  timeStamp: string;               // "01:15" — 그 회차의 중앙 프레임
}

export interface ExerciseSyncRate {
  exerciseId: number;
  name: string;
  setInfo: string;                 // "1세트 x 12회"
  syncRate: number;
}

export interface ComparisonWithPrevious {
  syncRateDiff: number;
  workoutMinutesDiff: number;
  caloriesDiff: number;
}

export interface SessionReportResponse {
  sessionId: number;
  avgSyncRate: number;
  totalReps: number;
  workoutMinutes: number;
  caloriesBurned: number;
  aiSafetyReport: string | null;
  worstSection: WorstSection | null;
  /**
   * 회차별 싱크로율 추이 (rep 오름차순). 측정된 회차가 없으면 빈 배열.
   *
   * syncRateDetails 는 이름과 달리 세션당 한 줄(운동 종목 단위 요약)이라 회차 흐름을 볼 수 없다.
   * worst 점은 repTrend.find(r => r.repNumber === worstSection?.repNumber) 로 찾는다.
   */
  repTrend: RepSyncRate[];
  syncRateDetails: ExerciseSyncRate[];
  comparisonWithPrevious: ComparisonWithPrevious | null;
}
