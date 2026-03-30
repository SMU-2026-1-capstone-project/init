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

## React Native에서 MediaPipe 사용 방법

### 방법 1: react-native-mediapipe (네이티브 모듈)
```bash
npm install react-native-mediapipe
```

```typescript
import { usePoseDetection } from 'react-native-mediapipe';

const ExerciseSession = () => {
  const { poses, isReady } = usePoseDetection({
    modelComplexity: 1,         // 0: Lite, 1: Full, 2: Heavy
    smoothLandmarks: true,
    minDetectionConfidence: 0.5,
    minTrackingConfidence: 0.5,
  });

  // poses에서 관절 좌표 추출
  if (poses.length > 0) {
    const landmarks = poses[0].landmarks;
    // landmarks[25] = left_knee 등
  }
};
```

### 방법 2: TensorFlow.js + MoveNet (권장 대안)
MediaPipe 네이티브 바인딩이 불안정할 경우 사용합니다.

```bash
npm install @tensorflow/tfjs @tensorflow/tfjs-react-native
npm install @tensorflow-models/pose-detection
```

```typescript
import * as tf from '@tensorflow/tfjs';
import '@tensorflow/tfjs-react-native';
import * as poseDetection from '@tensorflow-models/pose-detection';

// 모델 초기화
const initPoseDetector = async () => {
  await tf.ready();

  const detector = await poseDetection.createDetector(
    poseDetection.SupportedModels.BlazePose,
    {
      runtime: 'tfjs',
      modelType: 'full',        // lite, full, heavy
      enableSmoothing: true,
    }
  );
  return detector;
};

// 프레임에서 포즈 감지
const detectPose = async (detector, frame) => {
  const poses = await detector.estimatePoses(frame);
  return poses;
};
```

### 방법 3: WebView 브릿지 (가장 안정적)
MediaPipe의 웹 버전을 WebView에서 실행하고, 결과를 React Native로 전달합니다.

```typescript
// WebView에서 실행할 HTML
const mediapipeHTML = `
<!DOCTYPE html>
<html>
<head>
  <script src="https://cdn.jsdelivr.net/npm/@mediapipe/pose/pose.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/@mediapipe/camera_utils/camera_utils.js"></script>
</head>
<body>
  <video id="video" autoplay></video>
  <script>
    const pose = new Pose({
      locateFile: (file) => \`https://cdn.jsdelivr.net/npm/@mediapipe/pose/\${file}\`
    });

    pose.setOptions({
      modelComplexity: 1,
      smoothLandmarks: true,
      minDetectionConfidence: 0.5,
      minTrackingConfidence: 0.5
    });

    pose.onResults((results) => {
      // React Native로 결과 전달
      window.ReactNativeWebView.postMessage(
        JSON.stringify(results.poseLandmarks)
      );
    });
  </script>
</body>
</html>
`;
```

## 싱크로율 계산 알고리즘

### 1. 관절 각도 기반 비교
```typescript
// 3개 관절의 각도 계산
const calculateAngle = (
  pointA: {x: number, y: number},
  pointB: {x: number, y: number},  // 꼭짓점
  pointC: {x: number, y: number}
): number => {
  const radians = Math.atan2(pointC.y - pointB.y, pointC.x - pointB.x)
                - Math.atan2(pointA.y - pointB.y, pointA.x - pointB.x);
  let angle = Math.abs(radians * 180.0 / Math.PI);
  if (angle > 180) angle = 360 - angle;
  return angle;
};

// 스쿼트 예시: 무릎 각도 계산
const kneeAngle = calculateAngle(
  landmarks[23],  // hip
  landmarks[25],  // knee (꼭짓점)
  landmarks[27]   // ankle
);
```

### 2. DTW (Dynamic Time Warping) 알고리즘
시계열 데이터(레퍼런스 vs 사용자) 간의 유사도를 측정합니다.

```typescript
const dtw = (
  referenceSequence: number[][],
  userSequence: number[][]
): number => {
  const n = referenceSequence.length;
  const m = userSequence.length;
  const matrix = Array(n + 1).fill(null).map(() =>
    Array(m + 1).fill(Infinity)
  );
  matrix[0][0] = 0;

  for (let i = 1; i <= n; i++) {
    for (let j = 1; j <= m; j++) {
      const cost = euclideanDistance(referenceSequence[i-1], userSequence[j-1]);
      matrix[i][j] = cost + Math.min(
        matrix[i-1][j],     // insertion
        matrix[i][j-1],     // deletion
        matrix[i-1][j-1]    // match
      );
    }
  }

  return matrix[n][m];
};

// 유클리드 거리 계산
const euclideanDistance = (a: number[], b: number[]): number => {
  return Math.sqrt(
    a.reduce((sum, val, i) => sum + Math.pow(val - b[i], 2), 0)
  );
};
```

### 3. 싱크로율(%) 변환
```typescript
const calculateSyncRate = (dtwScore: number, maxScore: number): number => {
  const normalizedScore = Math.max(0, 1 - (dtwScore / maxScore));
  return Math.round(normalizedScore * 100);
};
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
