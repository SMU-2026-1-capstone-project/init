"""DetectorPool.acquire 락 범위 회귀 테스트 (#599).

이전 구현은 `PoseDetector()` 생성(MediaPipe 그래프 초기화, 비파이썬 무거운 작업)을
`self._guard`(풀 전체가 공유하는 단일 락) 안에서 실행했다 — 세션 A 하나의 생성이 끝날
때까지 세션 B·C·... 의 acquire·lease·release 가 전부 막혔다(실측: 동시성 1→50에서
p50 7배 악화, 이슈 참고). 수정은 자리만 락 안에서 예약(`_PENDING`)하고 생성은 락 밖에서
한다 — 이 테스트는 그 성질을 스레드 강제 인터리브로 결정적으로 잡는다.
"""

import threading
import time
import unittest
from unittest import mock

from app.core import mediapipe_detector as md
from app.core.mediapipe_detector import DetectorPool


def _fake_detector_cls(build_started, release_build):
    """PoseDetector() 를 흉내낸다 — 생성이 시작되면 신호를 보내고, 풀릴 때까지 붙잡혀 있는다."""

    class _Fake:
        def __init__(self):
            build_started.set()
            release_build.wait(timeout=5)
            self.closed = False

        def close(self):
            self.closed = True

    return _Fake


class DetectorPoolLockScopeTest(unittest.TestCase):
    def test_slow_construction_does_not_block_other_sessions(self):
        """세션 1의 PoseDetector() 생성이 도는 동안, 세션 2의 acquire/lease는 안 막혀야 한다."""
        build_started = threading.Event()
        release_build = threading.Event()

        with mock.patch.object(md, "PoseDetector", _fake_detector_cls(build_started, release_build)):
            pool = DetectorPool(capacity=5)
            other_acquired = threading.Event()

            t1 = threading.Thread(target=lambda: pool.acquire(1))
            t1.start()
            self.assertTrue(build_started.wait(timeout=2), "세션 1의 생성이 시작되지 않았다")

            # 세션 1이 아직 생성 중인 지금, 무관한 세션 2가 막히지 않고 바로 자리를 얻어야 한다.
            t2_ok = pool.acquire(2)
            other_acquired.set()
            self.assertTrue(t2_ok)
            self.assertIsNotNone(pool.lease(2), "세션 2는 세션 1의 생성과 무관하게 즉시 리스를 받아야 한다")

            release_build.set()
            t1.join(timeout=2)
            self.assertIsNotNone(pool.lease(1), "생성이 끝난 뒤에는 세션 1도 리스를 받아야 한다")

    def test_lease_returns_none_while_pending(self):
        """생성이 끝나기 전에는 lease가 «자리 없음»(None)과 같게 취급돼야 한다 — None 검출기로 크래시하면 안 된다."""
        build_started = threading.Event()
        release_build = threading.Event()

        with mock.patch.object(md, "PoseDetector", _fake_detector_cls(build_started, release_build)):
            pool = DetectorPool(capacity=5)
            t1 = threading.Thread(target=lambda: pool.acquire(1))
            t1.start()
            self.assertTrue(build_started.wait(timeout=2))

            self.assertIsNone(pool.lease(1), "생성 중(_PENDING)에는 lease가 None이어야 한다")

            release_build.set()
            t1.join(timeout=2)

    def test_capacity_respected_under_concurrent_acquire(self):
        """예약(_PENDING) 단계가 용량 계산에 즉시 반영돼야 한다 — 동시 acquire가 정원을 넘기면 안 된다."""
        results = []
        results_lock = threading.Lock()

        def worker(sid, pool):
            ok = pool.acquire(sid)
            with results_lock:
                results.append(ok)

        # 생성 자체는 안 막는다(즉시 반환) — 이 테스트는 "동시에 몰렸을 때 정원을 지키는가"만 본다.
        fake_started = threading.Event()
        fake_release = threading.Event()
        fake_release.set()

        with mock.patch.object(md, "PoseDetector", _fake_detector_cls(fake_started, fake_release)):
            pool = DetectorPool(capacity=3)
            threads = [threading.Thread(target=worker, args=(sid, pool)) for sid in range(10)]
            for t in threads:
                t.start()
            for t in threads:
                t.join(timeout=2)

            self.assertEqual(sum(1 for ok in results if ok), 3, "정원(3)을 넘겨 받아들이면 안 된다")
            self.assertEqual(sum(1 for ok in results if not ok), 7)
            used, cap = pool.status()
            self.assertEqual((used, cap), (3, 3))

    def test_release_during_pending_closes_orphaned_detector(self):
        """생성 완료 전에 release가 먼저 오면(취소) — 크래시 없이, 뒤늦게 만들어진 검출기는 닫혀야 한다."""
        build_started = threading.Event()
        release_build = threading.Event()
        fake_cls = _fake_detector_cls(build_started, release_build)
        created = []
        orig_init = fake_cls.__init__

        def tracking_init(self):
            orig_init(self)
            created.append(self)

        fake_cls.__init__ = tracking_init

        with mock.patch.object(md, "PoseDetector", fake_cls):
            pool = DetectorPool(capacity=5)
            t1 = threading.Thread(target=lambda: pool.acquire(1))
            t1.start()
            self.assertTrue(build_started.wait(timeout=2))

            # 생성이 끝나기 전에 취소(release) — 크래시하면 안 된다.
            released = pool.release(1)
            self.assertFalse(released, "아직 완성되지 않은 자리는 release 대상이 아니다(False)")

            release_build.set()
            t1.join(timeout=2)

            # 뒤늦게 완성된 검출기는 아무도 안 쓰므로 즉시 닫혀야 하고, 풀에 남아있으면 안 된다.
            self.assertEqual(len(created), 1)
            self.assertTrue(created[0].closed, "취소된 뒤 늦게 완성된 검출기는 닫혀야 한다")
            self.assertIsNone(pool.lease(1))
            used, _ = pool.status()
            self.assertEqual(used, 0)


if __name__ == "__main__":
    unittest.main()
