# React Native (Expo) 설치 및 설정 가이드

## 사전 요구사항
- **Node.js** 18 이상 (LTS 권장)
- **npm** 또는 **yarn**
- **Android Studio** (Android 에뮬레이터 사용 시)
- **Xcode** (iOS 개발 시, macOS만 가능)
- 실제 디바이스 테스트용 **Expo Go** 앱 (카메라 기능 테스트 필수)

## 1. Node.js 설치
```bash
# Windows - 공식 사이트에서 다운로드
# https://nodejs.org/ 에서 LTS 버전 다운로드 및 설치

# 설치 확인
node --version
npm --version
```

## 2. Expo 프로젝트 생성
```bash
# 프로젝트 폴더로 이동
cd shadowfit

# Expo 프로젝트 생성 (TypeScript 템플릿)
npx create-expo-app@latest frontend --template tabs

# 프로젝트 폴더로 이동
cd frontend
```

## 3. Expo Router 설정 (파일 기반 라우팅)
프로젝트 생성 시 tabs 템플릿을 선택하면 Expo Router가 기본 포함됩니다.

```bash
# Expo Router 관련 패키지 확인
npx expo install expo-router expo-linking expo-constants expo-status-bar
```

## 4. 필수 라이브러리 설치

### 카메라 & 미디어
```bash
# 카메라 접근
npx expo install expo-camera

# 미디어 라이브러리 (로컬 영상 선택)
npx expo install expo-media-library

# 비디오 플레이어
npx expo install expo-av

# 이미지 피커
npx expo install expo-image-picker
```

### MediaPipe (자세 감지)
```bash
# React Native에서 MediaPipe 사용을 위한 패키지
# 방법 1: react-native-mediapipe (커뮤니티 패키지)
npm install react-native-mediapipe

# 방법 2: @mediapipe/pose + 웹뷰 브릿지 방식
npm install @mediapipe/pose @mediapipe/camera_utils @mediapipe/drawing_utils

# TensorFlow.js + MediaPipe 조합 (대안)
npm install @tensorflow/tfjs @tensorflow/tfjs-react-native
npm install @tensorflow-models/pose-detection
```

> **참고**: MediaPipe의 React Native 네이티브 지원은 제한적입니다.
> `react-native-mediapipe`가 불안정할 경우, `expo-camera`로 프레임을 캡처하고
> TensorFlow.js의 pose-detection 모델(MoveNet/BlazePose)을 사용하는 것을 권장합니다.

### TTS (음성 안내)
```bash
npm install react-native-tts
# 또는 Expo 호환
npx expo install expo-speech
```

### HTTP 통신
```bash
npm install axios
```

### 상태 관리
```bash
# Zustand (가볍고 간편)
npm install zustand

# 또는 React Query (서버 상태 관리)
npm install @tanstack/react-query
```

### 달력
```bash
npm install react-native-calendars
```

### 차트 (보고서 시각화)
```bash
npm install react-native-chart-kit react-native-svg
# 또는
npm install victory-native react-native-svg
```

### 네비게이션 보조
```bash
npx expo install react-native-screens react-native-safe-area-context
npx expo install react-native-gesture-handler react-native-reanimated
```

### 인증 (JWT 토큰 저장)
```bash
npx expo install expo-secure-store
```

### YouTube 영상 처리
```bash
npm install react-native-youtube-iframe
npm install react-native-webview
```

## 5. 개발 서버 실행
```bash
# 개발 서버 시작
npx expo start

# Android 에뮬레이터에서 실행
npx expo start --android

# iOS 시뮬레이터에서 실행 (macOS만)
npx expo start --ios

# 실제 디바이스에서 실행 (Expo Go 앱 필요)
# QR 코드 스캔
```

## 6. Development Build (카메라/네이티브 모듈용)
카메라, MediaPipe 등 네이티브 모듈을 사용하려면 **Development Build**가 필요합니다.
Expo Go에서는 네이티브 모듈이 제한됩니다.

```bash
# EAS CLI 설치
npm install -g eas-cli

# EAS 프로젝트 설정
eas build:configure

# Development Build 생성
eas build --profile development --platform android
# 또는
eas build --profile development --platform ios
```

### app.json 주요 설정
```json
{
  "expo": {
    "name": "ShadowFit",
    "slug": "shadowfit",
    "version": "1.0.0",
    "scheme": "shadowfit",
    "plugins": [
      [
        "expo-camera",
        {
          "cameraPermission": "운동 자세 촬영을 위해 카메라 접근이 필요합니다."
        }
      ],
      [
        "expo-media-library",
        {
          "photosPermission": "운동 영상을 불러오기 위해 갤러리 접근이 필요합니다.",
          "savePhotosPermission": "운동 기록을 저장하기 위해 갤러리 접근이 필요합니다."
        }
      ]
    ]
  }
}
```

## 7. TypeScript 설정
```bash
# tsconfig.json은 Expo가 자동 생성
# 필요 시 path alias 추가
```

`tsconfig.json` 에 path alias 설정:
```json
{
  "extends": "expo/tsconfig.base",
  "compilerOptions": {
    "strict": true,
    "paths": {
      "@/*": ["./*"],
      "@components/*": ["./components/*"],
      "@hooks/*": ["./hooks/*"],
      "@services/*": ["./services/*"],
      "@utils/*": ["./utils/*"],
      "@types/*": ["./types/*"],
      "@constants/*": ["./constants/*"]
    }
  }
}
```

## 주의사항
- **카메라 기능은 실제 디바이스**에서만 제대로 테스트 가능
- MediaPipe 네이티브 모듈 사용 시 Expo Go가 아닌 **Development Build** 필수
- YouTube 영상의 관절 좌표 추출은 클라이언트에서 직접 처리 (서버 부담 최소화)
