# 페르소나 & 적응형 난이도 시스템 가이드

## 페르소나 정의 (회의록 3주차)

| 페르소나 | 대상 | 싱크로율 기준 | 특징 |
|---------|------|-------------|------|
| 헬린이 (BEGINNER) | 운동 초심자, 홈트 입문자 | 60% 이상 | 직관적 가이드, 쉬운 피드백 |
| 헬창 (ADVANCED) | 중/상급자 | 85% 이상 | ROM 분석, 좌우 불균형 데이터 |
| 다이어트 (DIET) | 체중 감량 목적 | 70% 이상 | 칼로리 소모 중심, 운동량 강조 |
| 재활 (REHAB) | 부상 회복 중 | 50% 이상 | 안전 범위 초과 경고, 저강도 |

## 페르소나별 피드백 톤

### 헬린이 (BEGINNER)
```
"좋아요! 자세가 점점 좋아지고 있어요 💪"
"무릎 위치만 조금 신경 쓰면 완벽해요!"
"첫 번째 세트 완료! 잘하고 있어요!"
```

### 헬창 (ADVANCED)
```
"좌측 무릎 각도 78도, 우측 82도 - 좌우 편차 4도"
"ROM 범위 92% 달성. 바텀 포지션에서 2도 부족"
"이전 대비 평균 싱크로율 3.2% 향상"
```

### 다이어트 (DIET)
```
"현재 예상 칼로리 소모: 85kcal! 계속 화이팅!"
"이번 세트 심박수 구간 도달. 효율적인 운동 중!"
"목표 운동량까지 30% 남았어요!"
```

### 재활 (REHAB)
```
"⚠️ 관절 각도가 안전 범위를 벗어났습니다. 천천히 줄여주세요."
"좋습니다. 안전한 범위 내에서 운동 중이에요."
"무리하지 말고 통증이 있으면 바로 중단해주세요."
```

## 적응형 난이도 조절 로직

```typescript
interface DifficultyConfig {
  level: number;           // 1-10
  targetReps: number;      // 목표 반복 횟수
  targetSyncRate: number;  // 목표 싱크로율
  restTimeSec: number;     // 세트 간 휴식 시간
}

const adjustDifficulty = (
  currentLevel: number,
  completedReps: number,
  targetReps: number,
  avgSyncRate: number,
  targetSyncRate: number,
  persona: string
): number => {
  const repCompletion = completedReps / targetReps;
  const syncAchievement = avgSyncRate / targetSyncRate;

  // 성공 판정: 목표 반복 횟수 달성 + 싱크로율 기준 충족
  if (repCompletion >= 1.0 && syncAchievement >= 1.0) {
    // 성공 → 난이도 상승
    return Math.min(currentLevel + 1, 10);
  } else if (repCompletion < 0.5 || syncAchievement < 0.7) {
    // 실패 (크게 미달) → 난이도 하락
    return Math.max(currentLevel - 1, 1);
  } else {
    // 부분 성공 → 난이도 유지
    return currentLevel;
  }
};

// 난이도별 설정
const getDifficultyConfig = (level: number, persona: string): DifficultyConfig => {
  const baseReps = persona === 'REHAB' ? 5 : 10;
  const baseSyncRate = {
    BEGINNER: 60,
    ADVANCED: 85,
    DIET: 70,
    REHAB: 50,
  }[persona] || 60;

  return {
    level,
    targetReps: baseReps + (level - 1) * 2,
    targetSyncRate: Math.min(baseSyncRate + (level - 1) * 2, 95),
    restTimeSec: Math.max(90 - (level - 1) * 5, 30),
  };
};
```

## GPT 피드백 생성 (운동 종료 후)

```typescript
// Backend에서 GPT API 호출
const generateFeedback = async (sessionData: SessionData, persona: string) => {
  const prompt = `
    사용자 페르소나: ${persona}
    운동: ${sessionData.exerciseName}
    평균 싱크로율: ${sessionData.avgSyncRate}%
    총 반복 횟수: ${sessionData.totalReps}
    운동 시간: ${sessionData.duration}분
    주요 문제점: ${sessionData.issues.join(', ')}

    위 운동 결과를 바탕으로 ${persona} 페르소나에 맞는 톤으로
    1) 운동 결과 요약 (2-3문장)
    2) 잘한 점 (1-2개)
    3) 개선 포인트 (1-2개)
    4) 다음 운동 추천사항
    을 작성해주세요.
  `;

  // OpenAI API 호출
  // ...
};
```
