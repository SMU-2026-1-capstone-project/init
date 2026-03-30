# MediaPipe 자세 감지 가이드

## MediaPipe Pose 개요
Google MediaPipe의 Pose 솔루션은 신체의 **33개 관절 포인트(랜드마크)**를 실시간으로 추출합니다.

## 주요 관절 포인트 (운동별 중요도)

| ID | 관절명 | 스쿼트 | 데드리프트 | 턱걸이 |
|----|--------|--------|----------|--------|
| 11 | left_shoulder | O | O | **필수** |
| 12 | right_shoulder | O | O | **필수** |
| 13 | left_elbow | - | - | **필수** |
| 14 | right_elbow | - | - | **필수** |
| 23 | left_hip | **필수** | **필수** | O |
| 24 | right_hip | **필수** | **필수** | O |
| 25 | left_knee | **필수** | **필수** | - |
| 26 | right_knee | **필수** | **필수** | - |
| 27 | left_ankle | **필수** | O | - |
| 28 | right_ankle | **필수** | O | - |

## 아키텍처: Python AI Server에서 실행

MediaPipe는 **Python AI Server (FastAPI)**에서 실행됩니다.
React Native에서 직접 실행하는 대신 서버에서 처리하는 이유:
- MediaPipe Python SDK가 가장 안정적이고 성능이 우수
- 모바일 기기의 연산 부하를 서버로 분산
- DTW 계산도 Python C 바인딩(dtaidistance)으로 10~100배 빠름

### 전체 흐름
```
[React Native]                     [AI Server (Python)]
1. 카메라 프레임 캡처          →    2. MediaPipe로 관절 감지
   (Base64 인코딩)                    (33개 랜드마크 추출)
                                   3. 운동별 관절 각도 계산
5. 화면에 오버레이 표시        ←    4. 관절 좌표 + 각도 반환
   + TTS 음성 안내

[운동 완료 후]
6. 수집된 각도 시퀀스 전송     →    7. DTW로 싱크로율 계산
9. 결과 화면 표시              ←    8. 싱크로율(%) 반환
```

### API 엔드포인트

| Method | URL | 용도 |
|--------|-----|------|
| POST | `/api/v1/pose` | 실시간 프레임 → 관절 좌표 + 각도 |
| POST | `/api/v1/sync` | 각도 시퀀스 비교 → 싱크로율(%) |
| POST | `/api/v1/video/analyze` | 참고 영상 전처리 (사전 분석) |
| GET | `/health` | 서버 상태 확인 |

### React Native에서 AI Server 호출 예시
```typescript
import axios from 'axios';

const AI_SERVER_URL = 'http://localhost:8000/api/v1';

// 실시간 포즈 감지
const detectPose = async (base64Frame: string, exerciseType: string) => {
  const response = await axios.post(`${AI_SERVER_URL}/pose`, {
    image: base64Frame,
    exercise_type: exerciseType,  // "squat", "deadlift", "pullup"
  });
  return response.data;
  // { success: true, landmarks: [...], angles: [145.2, 148.7, ...] }
};

// 싱크로율 계산
const calculateSyncRate = async (
  referenceAngles: number[][],
  userAngles: number[][]
) => {
  const response = await axios.post(`${AI_SERVER_URL}/sync`, {
    reference_angles: referenceAngles,
    user_angles: userAngles,
  });
  return response.data;
  // { sync_rate: 85.5, dtw_distance: 12.3 }
};
```

## 싱크로율 계산 알고리즘 (AI Server 내부)

### 1. 관절 각도 계산 (angle_calculator.py)
세 관절의 3D 좌표로 피벗 기준 각도를 계산합니다.
```python
# 벡터 내적으로 각도 계산 (0~180도)
vec_ba = [A.x - B.x, A.y - B.y, A.z - B.z]
vec_bc = [C.x - B.x, C.y - B.y, C.z - B.z]
angle = arccos(dot(vec_ba, vec_bc) / (|vec_ba| * |vec_bc|))
```

### 2. DTW (dtw_calculator.py)
C 바인딩 dtaidistance 라이브러리로 고속 DTW 계산:
```python
from dtaidistance import dtw
distance = dtw.distance(ref_sequence, user_sequence, window=10)
```

### 3. 싱크로율 변환
```python
# 거리 → 유사도 (시그모이드 기반)
sync_rate = 100 * exp(-distance / 30)  # 0~100%
```

## 카메라 세팅 가이드 (회의록 결정사항)
- 초기에는 **엄격한 가이드**로 시작 (정확한 카메라 위치/각도 요구)
- 운동별 불필요한 관절 인식 제약을 점진적으로 완화
  - 스쿼트: 목(nose) 인식 제외 가능
  - 턱걸이: 하체 관절 인식 제외 가능
- UI에 카메라 가이드 오버레이 표시 (올바른 촬영 각도 안내)

## 운동별 주요 체크 포인트

### 스쿼트
- 무릎 각도 (hip-knee-ankle)
- 허리 각도 (shoulder-hip-knee) → 과도한 전방 기울임 감지
- 무릎이 발끝을 넘지 않는지 (knee x vs ankle x)
- 좌우 대칭 (left vs right 각도 차이)

### 데드리프트
- 허리 곧은 정도 (shoulder-hip 라인)
- 엉덩이 힌지 각도 (shoulder-hip-knee)
- 바벨/손 위치와 무릎 관계

### 턱걸이
- 팔꿈치 각도 (shoulder-elbow-wrist)
- 턱 위치와 손 높이 비교
- 몸통 흔들림 (hip의 x좌표 변화량)
