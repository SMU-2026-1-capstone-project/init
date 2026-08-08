# ShadowFit 프로젝트 개요

## 프로젝트 소개
ShadowFit은 실시간 운동 자세 교정 모바일 애플리케이션입니다.
사용자가 원하는 운동 영상(로컬 영상 또는 YouTube URL)을 삽입하고, 휴대폰 카메라로 본인의 운동 자세를 실시간 촬영하여 **싱크로율 분석**, **TTS 음성 보조**, **달력 일지 작성**, **운동 보고서 출력** 기능을 제공합니다.

## 기술 스택
| 구분 | 기술 |
|------|------|
| Frontend | React Native (Expo) |
| Backend API | **Spring Boot 3.5.16** (Java 21) — 🔴 4.x 로 올리지 않는다(확정) |
| AI Server | FastAPI (Python 3.12) — MediaPipe, DTW |
| **서비스간 통신** | **gRPC** (Spring ↔ FastAPI 양방향, 내부 토큰 인증) |
| Database | MySQL 8.0+ (Docker) — **Flyway 로 스키마 이력 추적** |
| Infra | Docker / Docker Compose |
| **관측성** | correlation id 전파 + Actuator(**관리포트 9090**) + 커스텀 지표 9종 + **Prometheus·Grafana**(profile `obs`) |
| **신뢰성** | **아웃박스**(종료 통보 at-least-once) + 멱등 수신 · gRPC deadline · Resilience4j 서킷브레이커 |
| Pose Estimation | MediaPipe Python (ai-server에서 실행) |
| AI Feedback | GPT API (운동 종료 후 피드백) |
| TTS | 클라이언트 device TTS(`expo-speech`) — ⚠️ **미구현**([`11-tts-youtube-guide.md`](./11-tts-youtube-guide.md)) |

> 🔄 **2026-08-08 갱신.** 이 표는 `Spring Boot 3.4.0` 으로 멈춰 있었고(실제 **3.5.16**), **gRPC·Flyway·관측성·신뢰성 네 줄이 아예 없었다.** TTS 도 *"React Native TTS"* 라고만 적혀 있어 마치 되는 것처럼 보였다.
>
> 📌 **빠져 있던 네 줄이 다 «기능이 아닌 것» 이다.** 이 저장소의 문서 드리프트가 반복적으로 그 방향이다 — 기능 표는 관리되고, **보장·관측·운영은 트리거가 안 울린다**([`02-folder-structure.md`](./02-folder-structure.md)·[`05-database-design.md`](./05-database-design.md) 도 같은 누락이었다).

## 핵심 기능
1. **실시간 자세 분석**: MediaPipe를 활용한 관절 포인트 추출 및 싱크로율 계산
2. **기준 영상 매칭**: 로컬 영상 또는 YouTube URL로 기준 동작 설정
3. **TTS 음성 보조**: 운동 중 실시간 자세 교정 음성 안내
4. **운동 기록 관리**: 달력 기반 운동 일지 작성
5. **운동 보고서**: 운동 데이터 시각화 및 성과 리포트
6. **페르소나 기반 난이도**: 헬린이/헬창/다이어트/재활 등 맞춤 기준 적용
7. **적응형 난이도 조절**: 성공 시 강도 증가, 실패 시 유지/감소

## 타겟 운동
- 스쿼트 (하체)
- 데드리프트 (후면)
- 턱걸이 (상체)
- Plan B: 플랭크, 고강도 맨몸 운동

## 사용자 플로우
```
로그인 → 온보딩(페르소나 설정) → 메인 대시보드
→ 운동 시작(기준 영상 설정 + 실시간 분석) → TTS 보조
→ 운동 기록(대시보드/상세 보고서) → 마이페이지
```

## 영상 처리 전략 (마이크로서비스 아키텍처)
```
[React Native 앱]                    [AI Server (Python)]           [Backend (Spring Boot)]
 │ 카메라 프레임 ──────────────────► │ MediaPipe 관절 추출    │      │                      │
 │ (Base64)                         │ 관절 각도 계산          │      │ 회원/기록/보고서 CRUD   │
 │ ◄────────────────────────────── │ DTW 싱크로율 계산       │      │ GPT 피드백 생성        │
 │ 관절 좌표 + 각도 + 싱크로율       │                        │      │                      │
 │                                  │ 참고 영상 사전 분석     │      │                      │
 └─ 화면 표시/TTS 음성 안내          └────────────────────────┘      └──────────────────────┘
```
- **참고 영상 전처리**: 운동 영상에서 미리 관절 좌표를 추출하여 DB에 저장 (1회)
- **실시간 분석**: 앱에서 카메라 프레임을 AI Server로 전송 → 관절 감지 + DTW 비교 → 결과 반환
- **TTS/UI**: 분석 결과는 앱에서 화면 표시 및 음성 안내로 활용
- MediaPipe Python SDK는 React Native용보다 안정적이고 성능이 우수함
