"""`POST /api/v1/pose` 의 응답이 **사실을 말하는가** (이슈 #267).

#196 통주행이 「HTTP 전 구간 200 인데 리포트 전 필드 0」을 정상으로 읽었다. 원인은 status 가
아니라 **`success` 축이 실제 상태를 안 담고 있었다는 것**이다 — 판정에 못 들어간 프레임도
`success=true` 에 `landmarks` 까지 채워 나갔고, 그래서 「검출 30/31」로 세면 멀쩡해 보였다.
`e1_walkthrough.py` 도 같은 자리에 한 번 걸렸다(`edb91cf`).

그래서 `success` 를 **«판정에 들어갔는가»** 로 좁히고 `skip_reason` 을 별도 축으로 뒀다.
이 파일이 고정하는 것은 그 계약이다.

⚠️ **여기서 재는 것은 응답 계약이지 검출 품질이 아니다.** MediaPipe 는 mock 으로 갈음한다 —
「가시성이 낮은 프레임을 실제로 알아보는가」는 `test_squat_analyzer` 쪽 몫이다.
"""
import unittest
from unittest import mock

import numpy as np

from app.api.endpoints import pose as pose_endpoint
from app.grpc.session_state import get_registry
from app.models.pose import PoseRequest, PoseSkipReason

from tests.test_squat_analyzer import _frame

_BLANK_IMAGE = np.zeros((4, 4, 3), dtype=np.uint8)
_STANDING_ANGLE = 172.0

# 33개 랜드마크 전부 visibility 0.1 — `_frame` 이 하체만 올려주므로, 그걸 안 쓰면
# `_frame_visibility_score` 가 바닥이라 «가시성 부족» 갈래로 떨어진다.
_INVISIBLE = [
    type(lm)(index=lm.index, x=lm.x, y=lm.y, z=lm.z, visibility=0.01)
    for lm in _frame(_STANDING_ANGLE)
]


def _fake_lease(detect_fn):
    class _L:
        def __enter__(self):
            return mock.Mock(detect=detect_fn)

        def __exit__(self, *exc):
            return False

    return lambda _session_id: _L()


class PoseResponseContractTest(unittest.TestCase):
    def _run(self, session_id, detect_fn, *, lease=True):
        req = PoseRequest(image="", session_id=session_id, exercise_type="squat")
        lease_patch = _fake_lease(detect_fn) if lease else (lambda _s: None)
        with mock.patch.object(pose_endpoint, "base64_to_image", lambda _: _BLANK_IMAGE), \
            mock.patch.object(pose_endpoint, "lease_detector", lease_patch):
            return pose_endpoint.detect_pose(req)

    def _session(self, session_id):
        state = get_registry().create(
            session_id=session_id, exercise_id=1, reference_angles=[]
        )
        self.addCleanup(get_registry().remove, session_id)
        return state

    # ── 판정에 들어간 프레임 ────────────────────────────────────────────────

    def test_판정에_들어간_프레임은_success_True_에_사유가_없다(self) -> None:
        self._session(9201)
        res = self._run(9201, lambda _img: _frame(_STANDING_ANGLE))

        self.assertTrue(res.success)
        self.assertIsNone(res.skip_reason, "성공 응답에 스킵 사유가 붙어 있다")
        self.assertIsNotNone(res.angles, "판정에 들어갔다면서 각도가 없다")

    # ── #267 의 두 자리 ────────────────────────────────────────────────────

    def test_가시성_부족은_success_False_다(self) -> None:
        """#196 이 속은 바로 그 자리. landmarks 는 오는데 판정은 0 이다."""
        state = self._session(9202)
        res = self._run(9202, lambda _img: _INVISIBLE)

        self.assertFalse(res.success, "판정에 못 들어갔는데 success=true 다 (#267 재발)")
        self.assertEqual(res.skip_reason, PoseSkipReason.LOW_VISIBILITY)
        self.assertIsNone(res.angles)
        # 🔴 오독의 원인이 여기다 — landmarks 는 **그대로 있다.**
        self.assertIsNotNone(
            res.landmarks,
            "오버레이용 landmarks 까지 지우면 화면이 끊긴다 — 막는 것은 판정이지 표시가 아니다",
        )
        self.assertEqual(state.visibility_skip_count, 1, "세션 요약이 셀 근거가 안 쌓인다")

    def test_유입_상한_드롭도_success_False_다(self) -> None:
        """서버가 «의도적으로» 자른 경우다. 그래도 판정에는 안 들어갔다."""
        self._session(9203)
        with mock.patch.object(pose_endpoint, "accept_frame", lambda _s, _n: False):
            res = self._run(9203, lambda _img: _frame(_STANDING_ANGLE))

        self.assertFalse(res.success)
        self.assertEqual(res.skip_reason, PoseSkipReason.RATE_LIMITED)
        self.assertIsNotNone(res.landmarks)

    def test_정상_드롭과_입력_문제는_사유로_갈린다(self) -> None:
        """`success` 한 축으로는 못 가르는 것 — skip_reason 이 존재하는 이유다."""
        self._session(9204)
        with mock.patch.object(pose_endpoint, "accept_frame", lambda _s, _n: False):
            dropped = self._run(9204, lambda _img: _frame(_STANDING_ANGLE))
        invisible = self._run(9204, lambda _img: _INVISIBLE)

        self.assertEqual(dropped.success, invisible.success, "전제: 둘 다 False 다")
        self.assertNotEqual(
            dropped.skip_reason,
            invisible.skip_reason,
            "정상 동작(상한)과 입력 문제(가시성)가 같은 사유로 나온다",
        )

    # ── 나머지 거절 갈래도 사유를 단다 ──────────────────────────────────────

    def test_배정_없음은_NO_LEASE_다(self) -> None:
        self._session(9205)
        res = self._run(9205, None, lease=False)

        self.assertFalse(res.success)
        self.assertEqual(res.skip_reason, PoseSkipReason.NO_LEASE)

    def test_세션_미시작은_SESSION_NOT_FOUND_다(self) -> None:
        # 레지스트리에 안 만든다.
        res = self._run(9206, lambda _img: _frame(_STANDING_ANGLE))

        self.assertFalse(res.success)
        self.assertEqual(res.skip_reason, PoseSkipReason.SESSION_NOT_FOUND)

    def test_포즈_미검출은_NO_POSE_다(self) -> None:
        self._session(9207)
        res = self._run(9207, lambda _img: [])

        self.assertFalse(res.success)
        self.assertEqual(res.skip_reason, PoseSkipReason.NO_POSE)

    # ── 계약 자체 ──────────────────────────────────────────────────────────

    def test_success_와_skip_reason_은_같이_움직인다(self) -> None:
        """둘이 어긋나면 축을 둘로 나눈 의미가 없다."""
        self._session(9208)
        cases = [
            self._run(9208, lambda _img: _frame(_STANDING_ANGLE)),
            self._run(9208, lambda _img: _INVISIBLE),
            self._run(9209, lambda _img: _frame(_STANDING_ANGLE)),  # 세션 없음
        ]
        for res in cases:
            self.assertEqual(
                res.success,
                res.skip_reason is None,
                f"success={res.success} 인데 skip_reason={res.skip_reason} 이다",
            )


if __name__ == "__main__":
    unittest.main()
