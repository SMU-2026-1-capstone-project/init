export type ExerciseLevel = 'BEGINNER' | 'NOVICE' | 'INTERMEDIATE' | 'ADVANCED' | 'EXPERT';

export type PersonaType = 'FRIENDLY' | 'STRICT' | 'REHAB';

export interface OnboardingData {
  exerciseLevel: ExerciseLevel | null;
  targetWeight: number;
  persona: PersonaType | null;
  referenceVideoUrl?: string;
  referenceVideoFile?: string;
}

export interface UserProfile {
  id: number;
  email: string;
  exerciseLevel: ExerciseLevel;
  targetWeight: number;
  persona: PersonaType;
  referenceVideoUrl?: string;
  createdAt: string;
}

export interface WorkoutSession {
  id: number;
  date: string;
  exercises: ExerciseRecord[];
  totalMinutes: number;
  totalCalories: number;
}

export interface ExerciseRecord {
  name: string;
  sets: number;
  reps: number;
  syncRate: number;
}

export interface DailyRecord {
  date: string;
  hasDot: boolean;
  dotColor?: string;
}

export interface WeeklyStats {
  workouts: number;
  minutes: number;
  calories: number;
  changePercent: number;
}

export interface WorkoutReport {
  id: number;
  date: string;
  averageSyncRate: number;
  totalReps: number;
  totalMinutes: number;
  totalCalories: number;
  exercises: ExerciseRecord[];
  worstMoment?: {
    exercise: string;
    time: string;
    syncRate: number;
    issue: string;
  };
  aiReport?: string;
}
