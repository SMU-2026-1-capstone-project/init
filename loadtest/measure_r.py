r"""R 실측 — 실데모 스쿼트 영상을 production 스트리밍 경로(pose.py) 그대로 재현해
rep 1회당 gRPC 배치 행 수(=R)를 직접 센다.

ai-server 코드는 수정하지 않고 import만 한다(읽기 전용).
배치 분석기(analyze_squat_frames, min_rep_frames=12)가 아니라 실제 운영 경로인
StreamingSquatAnalyzer(MIN_REP_FRAMES=4)를 쓴다 — pose.py 와 동일.

실행:
  cd ai-server
  .venv\Scripts\python.exe ..\loadtest\measure_r.py scripts\demo_videos\demo_squat.mp4

🔴 2026-08-09 수리 (이슈 #145) — 이 스크립트는 작성 후 한 번도 돌지 않은 채 낡아 있었다.
   `process_frame` 이 2-튜플에서 3-튜플로 바뀌어(smoothed_knee_angle 추가) 첫 감지
   프레임에서 ValueError 로 죽었다. 아래 호출부를 pose.py:77 과 다시 맞췄다.
   ⚠️ 이 스크립트는 운영 경로를 **손으로 베낀 복제본**이라 같은 드리프트가 또 난다 —
   ai-server 를 고칠 때 여기도 같이 보든지, 아니면 운영 코드를 직접 부르게 바꿔야 한다.
   (venv 경로도 문서와 달랐다: 레포 루트가 아니라 ai-server/.venv 다)
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2

# ai-server 패키지 import 가능하도록 경로 추가 (cwd=ai-server 가정, 안전하게 보강)
AI_SERVER = Path(__file__).resolve().parent.parent / "ai-server"
if str(AI_SERVER) not in sys.path:
    sys.path.insert(0, str(AI_SERVER))

from app.config import settings  # noqa: E402
from app.core.mediapipe_detector import get_detector  # noqa: E402
from app.core.squat_analyzer import StreamingSquatAnalyzer, _frame_visibility_score  # noqa: E402
from app.grpc.session_state import PerRepFrame, SessionState  # noqa: E402


def measure(video_path: str) -> None:
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise FileNotFoundError(f"영상을 열 수 없음: {video_path}")

    original_fps = cap.get(cv2.CAP_PROP_FPS)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    duration = total_frames / original_fps if original_fps > 0 else 0.0

    # video_processor.py 와 동일한 데시메이션
    frame_interval = max(1, int(original_fps / settings.VIDEO_PROCESS_FPS))
    processed_fps = original_fps / frame_interval if original_fps > 0 else settings.VIDEO_PROCESS_FPS

    analyzer = StreamingSquatAnalyzer("squat")
    state = SessionState(session_id=999_999, exercise_id=1, exercise_type="squat")
    detector = get_detector()

    decimated = 0       # 분석에 투입된 (데시메이션 후) 프레임 수
    detected = 0        # MediaPipe 가 사람을 찾은 프레임 (pose.py: landmarks truthy)
    vis_passed = 0      # visibility>=0.55 통과 (current_rep_frames 에 실제 누적된 프레임)
    rep_sizes: list[int] = []  # rep 완성 시점의 배치 행 수 = R 분포
    vis_scores: list[float] = []  # 진단: 감지 프레임의 하위신체 평균 visibility

    frame_idx = 0
    while True:
        ret, frame = cap.read()
        if not ret:
            break
        if frame_idx % frame_interval == 0:
            decimated += 1
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            landmarks = detector.detect(rgb)
            if landmarks:  # pose.py:56 — 미감지 시 early return (frame_index 증가 안 함)
                detected += 1
                vis_scores.append(_frame_visibility_score(landmarks))
                # pose.py:77 — 3-튜플. smoothed_knee_angle 은 R 에 영향이 없지만(행 수는
                # 버퍼 길이로 정해진다) 운영 경로와 같은 모양을 유지하려고 그대로 싣는다.
                angles, smoothed_knee_angle, rep_event = analyzer.process_frame(state, landmarks)
                if angles is not None:  # visibility 통과만 누적 (pose.py:79)
                    vis_passed += 1
                    ts = frame_idx / original_fps if original_fps > 0 else float(state.frame_index)
                    state.current_rep_frames.append(
                        PerRepFrame(
                            timestamp_sec=ts,
                            joint_coordinates="",
                            angles=angles,
                            smoothed_knee_angle=smoothed_knee_angle,
                        )
                    )
                    if rep_event is not None:  # rep 완성 → 배치 전송 + 버퍼 clear (pose.py:107~129)
                        rep_sizes.append(len(state.current_rep_frames))
                        state.current_rep_frames.clear()
        frame_idx += 1
    cap.release()

    reps = len(rep_sizes)
    rows_sent = sum(rep_sizes)
    trailing = len(state.current_rep_frames)  # 마지막 rep 미완성분 (배치 미전송)

    print("=" * 60)
    print("R 실측 — 스트리밍 경로(pose.py) 재현")
    print("=" * 60)
    print(f"영상: {Path(video_path).resolve()}")
    print(f"원본 fps: {original_fps:.2f} | 총 프레임: {total_frames} | 길이: {duration:.2f}s")
    print(f"데시메이션: frame_interval={frame_interval} → 처리 fps ≈ {processed_fps:.2f} "
          f"(VIDEO_PROCESS_FPS={settings.VIDEO_PROCESS_FPS})")
    print("-" * 60)
    print(f"투입 프레임(데시메이션 후): {decimated}")
    print(f"  MediaPipe 감지 성공:       {detected}")
    print(f"  visibility≥0.55 누적:      {vis_passed}")
    if vis_scores:
        s = sorted(vis_scores)
        n = len(s)
        mean = sum(s) / n
        print(f"  [진단] 하위신체 평균 visibility (감지 {n}프레임):")
        print(f"         평균={mean:.3f}  min={s[0]:.3f}  "
              f"p50={s[n // 2]:.3f}  max={s[-1]:.3f}")
        print(f"         ≥0.55: {sum(1 for v in s if v >= 0.55)}/{n}  "
              f"≥0.65: {sum(1 for v in s if v >= 0.65)}/{n}")
    print("-" * 60)
    print(f"완성된 rep 수:               {reps}")
    print(f"전송된 총 행 수(배치 합):    {rows_sent}")
    print(f"미전송 trailing 프레임:      {trailing}")
    if reps > 0:
        print("-" * 60)
        print(f"rep별 배치 행 수(R 분포):    {rep_sizes}")
        print(f"R 평균:                      {rows_sent / reps:.2f}")
        print(f"R 최소/최대:                 {min(rep_sizes)} / {max(rep_sizes)}")
        print(f"세션당 총 적재 행:           {rows_sent} (완성 rep 기준)")
    print("=" * 60)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="R(rep당 PoseData 행 수) 실측")
    parser.add_argument("video", help="스쿼트 데모 영상 경로")
    args = parser.parse_args()
    measure(args.video)
