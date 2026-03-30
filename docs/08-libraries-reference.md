# 라이브러리 전체 목록 및 용도

## Frontend (React Native / Expo)

### 핵심 프레임워크
| 패키지 | 용도 | 설치 명령어 |
|--------|------|-----------|
| expo | Expo 프레임워크 | `npx create-expo-app@latest` |
| expo-router | 파일 기반 라우팅 | `npx expo install expo-router` |
| react-native | React Native 코어 | Expo에 포함 |
| typescript | 타입스크립트 | Expo 템플릿에 포함 |

### 카메라 & 미디어
| 패키지 | 용도 | 설치 명령어 |
|--------|------|-----------|
| expo-camera | 카메라 접근 및 프레임 캡처 | `npx expo install expo-camera` |
| expo-av | 비디오 재생 (기준 영상) | `npx expo install expo-av` |
| expo-media-library | 로컬 미디어 접근 | `npx expo install expo-media-library` |
| expo-image-picker | 영상 파일 선택 | `npx expo install expo-image-picker` |

### 자세 감지
> MediaPipe는 **ai-server (Python)**에서 실행됩니다.
> 프론트엔드에서는 AI Server API를 호출하여 결과를 받습니다.

### 음성 안내
| 패키지 | 용도 | 설치 명령어 |
|--------|------|-----------|
| expo-speech | TTS 음성 합성 | `npx expo install expo-speech` |

### 네트워크 & 상태 관리
| 패키지 | 용도 | 설치 명령어 |
|--------|------|-----------|
| axios | HTTP 클라이언트 | `npm install axios` |
| zustand | 전역 상태 관리 | `npm install zustand` |
| @tanstack/react-query | 서버 상태 관리 & 캐싱 | `npm install @tanstack/react-query` |

### UI 컴포넌트
| 패키지 | 용도 | 설치 명령어 |
|--------|------|-----------|
| react-native-calendars | 달력 UI | `npm install react-native-calendars` |
| react-native-chart-kit | 차트/그래프 | `npm install react-native-chart-kit` |
| react-native-svg | SVG 렌더링 (차트 의존) | `npm install react-native-svg` |
| react-native-youtube-iframe | YouTube 영상 재생 | `npm install react-native-youtube-iframe` |
| react-native-webview | WebView (YouTube, MediaPipe) | `npm install react-native-webview` |
| react-native-reanimated | 애니메이션 | `npx expo install react-native-reanimated` |
| react-native-gesture-handler | 제스처 처리 | `npx expo install react-native-gesture-handler` |

### 보안 & 저장
| 패키지 | 용도 | 설치 명령어 |
|--------|------|-----------|
| expo-secure-store | JWT 토큰 안전 저장 | `npx expo install expo-secure-store` |
| @react-native-async-storage/async-storage | 로컬 데이터 저장 | `npx expo install @react-native-async-storage/async-storage` |

### 네비게이션 의존성
| 패키지 | 용도 | 설치 명령어 |
|--------|------|-----------|
| react-native-screens | 네이티브 스크린 | `npx expo install react-native-screens` |
| react-native-safe-area-context | 안전 영역 | `npx expo install react-native-safe-area-context` |

---

## Backend (Spring Boot)

### 핵심 의존성 (build.gradle)
| 의존성 | 용도 |
|--------|------|
| spring-boot-starter-web | REST API 서버 |
| spring-boot-starter-data-jpa | JPA/Hibernate ORM |
| spring-boot-starter-security | 인증/보안 |
| spring-boot-starter-validation | 요청 데이터 검증 |
| mysql-connector-j | MySQL JDBC 드라이버 |
| lombok | 보일러플레이트 코드 제거 |
| spring-boot-devtools | 핫 리로딩 |

### 추가 의존성
| 의존성 | 용도 | Gradle 표기 |
|--------|------|------------|
| jjwt | JWT 토큰 생성/검증 | `io.jsonwebtoken:jjwt-api:0.12.5` |
| OpenAI Java SDK | GPT 피드백 생성 | `com.theokanning.openai-gpt3-java:service:0.18.2` |
| SpringDoc OpenAPI | Swagger API 문서 | `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6` |

---

## AI Server (Python / FastAPI)

### 핵심 의존성 (requirements.txt)
| 패키지 | 용도 |
|--------|------|
| fastapi | REST API 프레임워크 |
| uvicorn | ASGI 서버 |
| pydantic / pydantic-settings | 데이터 검증 및 환경 설정 |
| mediapipe | Google MediaPipe 포즈 감지 (33개 관절) |
| opencv-python-headless | 이미지/영상 처리 |
| numpy | 수치 계산 |
| dtaidistance | DTW(Dynamic Time Warping) 알고리즘 (C 바인딩) |
| python-multipart | 파일 업로드 지원 |

---

## 일괄 설치 스크립트

### Frontend 전체 설치
```bash
cd frontend

# Expo 공식 패키지
npx expo install expo-camera expo-av expo-media-library expo-image-picker \
  expo-speech expo-secure-store expo-router expo-linking expo-constants \
  react-native-screens react-native-safe-area-context \
  react-native-gesture-handler react-native-reanimated react-native-svg \
  @react-native-async-storage/async-storage

# npm 패키지
npm install axios zustand @tanstack/react-query \
  react-native-calendars react-native-chart-kit \
  react-native-youtube-iframe react-native-webview
```

### AI Server 설치
```bash
cd ai-server
python -m venv venv
source venv/Scripts/activate  # Windows
pip install -r requirements.txt
```

### Backend 의존성
`build.gradle`에 명시 후:
```bash
cd backend
./gradlew build
```
