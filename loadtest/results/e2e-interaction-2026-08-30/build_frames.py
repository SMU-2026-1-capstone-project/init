"""§3.3 실행 레시피용 — 실제 스쿼트 영상에서 load_ai.py 가 쓸 frames.json 을 뽑는다.

demo_squat.mp4(머리·어깨 클립, load-test-strategy.md §7.9)와 달리 사용자가 제공한 실제 전신
스쿼트 영상을 쓴다. measure_r.py 와 같은 데시메이션(원본 fps → VIDEO_PROCESS_FPS)으로 잘라
production 스트리밍 경로(StreamingSquatAnalyzer)가 실제로 rep 을 완성하는 것까지 이미
measure_r.py 로 확인했다(2026-08-30: rep 4개, R 평균 23.5).

실행: cd ai-server && .venv/Scripts/python.exe ../loadtest/results/e2e-interaction-2026-08-30/build_frames.py <영상경로> <출력경로>
"""
import base64
import json
import sys
from pathlib import Path

import cv2

AI_SERVER = Path(__file__).resolve().parent.parent.parent.parent / "ai-server"
if str(AI_SERVER) not in sys.path:
    sys.path.insert(0, str(AI_SERVER))

from app.config import settings  # noqa: E402


def main():
    video_path = sys.argv[1]
    out_path = sys.argv[2] if len(sys.argv) > 2 else "frames.json"
    quality = int(sys.argv[3]) if len(sys.argv) > 3 else 80

    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise FileNotFoundError(f"영상을 열 수 없음: {video_path}")

    original_fps = cap.get(cv2.CAP_PROP_FPS)
    frame_interval = max(1, int(original_fps / settings.VIDEO_PROCESS_FPS))

    frames_b64 = []
    idx = 0
    while True:
        ret, frame = cap.read()
        if not ret:
            break
        if idx % frame_interval == 0:
            # 640x480 로 리사이즈 — 원본 4K 그대로 쓰면 프레임당 ~970KB(실사용 13~14KB의 70배)라
            # AI 천장(451rps, proc-count-sweep-2026-08-24)을 잰 조건(coresidency rig 관례,
            # 640x480 q80)과 안 맞아 A/B·천장 대조가 프레임 크기 효과와 뒤섞인다.
            resized = cv2.resize(frame, (640, 480), interpolation=cv2.INTER_AREA)
            ok, buf = cv2.imencode(".jpg", resized, [cv2.IMWRITE_JPEG_QUALITY, quality])
            if not ok:
                print("JPEG 인코딩 실패")
                sys.exit(1)
            frames_b64.append(base64.b64encode(buf.tobytes()).decode())
        idx += 1
    cap.release()

    meta = {
        "n": len(frames_b64),
        "source": "real squat video (user-provided, 2026-08-30) — synthetic_body 합성 인체는 rep 완성 불가로 폐기",
        "original_fps": original_fps,
        "decimated_to_fps": settings.VIDEO_PROCESS_FPS,
        "jpeg_quality": quality,
        "note": "measure_r.py 로 production 스트리밍 경로에서 rep 4개 완성 확인 완료(R 평균 23.5)",
    }
    with open(out_path, "w") as fp:
        json.dump({"meta": meta, "frames": frames_b64}, fp)
    kb = sum(len(x) for x in frames_b64) / 1024
    print(f"{out_path} — {len(frames_b64)}프레임, base64 합계 {kb:.0f}KB")


if __name__ == "__main__":
    main()
