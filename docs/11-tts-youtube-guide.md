# TTS 음성 안내 & YouTube 영상 처리 가이드

## TTS (Text-to-Speech) 구현

### expo-speech 사용 (권장)
```bash
npx expo install expo-speech
```

```typescript
import * as Speech from 'expo-speech';

// 기본 사용법
const speak = (message: string) => {
  Speech.speak(message, {
    language: 'ko-KR',       // 한국어
    pitch: 1.0,              // 음높이
    rate: 1.0,               // 속도
  });
};

// 운동 중 피드백 예시
const exerciseFeedbacks = {
  kneeOver: '무릎이 발끝을 넘었습니다. 뒤로 빼주세요.',
  backBend: '허리가 굽었습니다. 등을 곧게 펴주세요.',
  goodForm: '좋은 자세입니다! 계속 유지하세요.',
  repCount: (count: number) => `${count}회 완료!`,
  syncLow: '싱크로율이 낮습니다. 기준 영상을 다시 확인해주세요.',
};

// 실시간 피드백 (너무 자주 말하지 않도록 쓰로틀링)
let lastSpokenTime = 0;
const SPEAK_INTERVAL = 3000; // 최소 3초 간격

const speakFeedback = (message: string) => {
  const now = Date.now();
  if (now - lastSpokenTime > SPEAK_INTERVAL) {
    Speech.speak(message, { language: 'ko-KR' });
    lastSpokenTime = now;
  }
};

// TTS 중지
const stopSpeaking = () => {
  Speech.stop();
};
```

### 피드백 우선순위 시스템
```typescript
type FeedbackPriority = 'HIGH' | 'MEDIUM' | 'LOW';

interface Feedback {
  message: string;
  priority: FeedbackPriority;
}

// 높은 우선순위 피드백만 즉시 안내, 낮은 우선순위는 큐에 저장
const feedbackQueue: Feedback[] = [];

const processFeedback = (feedback: Feedback) => {
  if (feedback.priority === 'HIGH') {
    Speech.stop(); // 현재 말하던 것 중지
    Speech.speak(feedback.message, { language: 'ko-KR' });
  } else {
    feedbackQueue.push(feedback);
  }
};
```

---

## YouTube 영상 처리

### YouTube 영상 재생
```bash
npm install react-native-youtube-iframe react-native-webview
```

```typescript
import YoutubePlayer from 'react-native-youtube-iframe';

// YouTube URL에서 비디오 ID 추출
const extractVideoId = (url: string): string | null => {
  const patterns = [
    /(?:youtube\.com\/watch\?v=|youtu\.be\/|youtube\.com\/embed\/)([^&\s?]+)/,
  ];
  for (const pattern of patterns) {
    const match = url.match(pattern);
    if (match) return match[1];
  }
  return null;
};

// 컴포넌트에서 사용
const ReferenceVideoPlayer = ({ youtubeUrl }: { youtubeUrl: string }) => {
  const videoId = extractVideoId(youtubeUrl);

  return (
    <YoutubePlayer
      height={200}
      videoId={videoId}
      play={true}
    />
  );
};
```

### YouTube 영상의 관절 좌표 추출 전략
YouTube 영상에서 직접 MediaPipe를 돌리려면:

1. **실시간 프레임 추출 방식** (권장)
   - YouTube 영상을 재생하면서 일정 간격으로 프레임 캡처
   - 캡처된 프레임에 MediaPipe 적용하여 관절 좌표 추출
   - 사전 분석 후 결과를 캐싱

2. **사전 분석 방식**
   - 운동 시작 전 기준 영상을 먼저 분석
   - 분석된 관절 좌표 시퀀스를 로컬에 저장
   - 운동 중에는 저장된 기준 데이터와 실시간 비교

```typescript
// 기준 영상 사전 분석 플로우
const analyzeReferenceVideo = async (videoSource: string) => {
  // 1. 영상에서 프레임 추출 (1초당 1프레임)
  // 2. 각 프레임에 MediaPipe 적용
  // 3. 관절 좌표 시퀀스 생성
  // 4. 로컬 저장 (AsyncStorage 또는 파일)

  const referenceData = {
    videoId: 'xxx',
    fps: 1,
    frames: [
      { timestamp: 0, landmarks: [...] },
      { timestamp: 1, landmarks: [...] },
      // ...
    ]
  };

  return referenceData;
};
```

### 로컬 영상 선택 및 처리
```typescript
import * as ImagePicker from 'expo-image-picker';

const pickLocalVideo = async () => {
  const result = await ImagePicker.launchImageLibraryAsync({
    mediaTypes: ImagePicker.MediaTypeOptions.Videos,
    allowsEditing: true,
    quality: 1,
  });

  if (!result.canceled) {
    const videoUri = result.assets[0].uri;
    // 해당 영상으로 MediaPipe 분석 시작
    return videoUri;
  }
};
```

## 영상 소스 통합 관리
```typescript
type VideoSource =
  | { type: 'youtube'; url: string; videoId: string }
  | { type: 'local'; uri: string };

const resolveVideoSource = (input: string): VideoSource => {
  const youtubeId = extractVideoId(input);
  if (youtubeId) {
    return { type: 'youtube', url: input, videoId: youtubeId };
  }
  return { type: 'local', uri: input };
};
```
