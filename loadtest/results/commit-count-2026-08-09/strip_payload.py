#!/usr/bin/env python3
"""ghz 리포트에서 `options.data` 를 걷어낸다 — 커밋 전 필수.

ghz 는 `--data-file` 의 **내용 전체**를 리포트의 `options.data` 에 그대로 박는다. 이 라운드의
N=10 페이로드는 51.6MB 라, 안 걷어내면 리포트 하나가 54MB 다.

**걷어내도 되는 이유는 «재생성 가능» 하기 때문이다.** 페이로드는 커밋된 생성기와 인자로
바이트 단위까지 다시 만들 수 있다:

    python loadtest/ghz/gen_batch_multi.py --sessions 901-1000 --reps 25  --out batch_n1.json
    python loadtest/ghz/gen_batch_multi.py --sessions 901-1000 --reps 125 --out batch_n5.json
    python loadtest/ghz/gen_batch_multi.py --sessions 901-1000 --reps 250 --out batch_n10.json
    python loadtest/ghz/gen_batch.py       --session 801 --reps 25        --out batch_single.json

⚠️ **`details`(요청별 원본)는 남긴다.** 그건 재생성이 안 되는 관측값이다. 크기를 줄이자고
   관측을 버리면 나중에 «왜 그 판의 꼬리가 길었나» 를 다시 못 묻는다 — 그때는 인프라가
   없어서 돈을 다시 내야 한다. 3차가 남긴 리포트도 같은 기준(판당 ~1.1MB)이었다.

사용: python strip_payload.py <디렉터리>
"""
import json
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    d = Path(sys.argv[1])
    total_before = total_after = 0
    for f in sorted(d.glob("*.json")):
        before = f.stat().st_size
        j = json.loads(f.read_text(encoding="utf-8"))
        opts = j.get("options") or {}
        if "data" not in opts:
            print(f"  {f.name}: options.data 없음 — 건너뜀")
            continue
        # 🔴 두 번 돌려도 안전해야 한다. 초판은 이 가드가 없어서, 이미 비운 파일을 다시 훑고
        #    «메시지 수» 를 1 로 덮어썼다 — 원본 수를 잃은 채 **틀린 수가 기록에 남았다.**
        #    그래서 수를 아예 안 적는다. 필요하면 생성기 인자로 알 수 있다.
        if "_dataStrippedNote" in opts:
            print(f"  {f.name}: 이미 제거됨 — 건너뜀")
            continue
        # 무엇을 지웠는지 리포트 안에 남긴다. 파일만 보고도 «원본이 통째였는지»를 알아야 한다.
        opts["data"] = None
        opts["_dataStrippedNote"] = (
            "payload removed for repo size — regenerate with "
            "loadtest/ghz/gen_batch_multi.py (see strip_payload.py docstring)")
        f.write_text(json.dumps(j, separators=(",", ":")), encoding="utf-8")
        after = f.stat().st_size
        total_before += before
        total_after += after
        print(f"  {f.name}: {before/1024/1024:.1f}MB → {after/1024/1024:.1f}MB")
    if total_before:
        print(f"\n합계: {total_before/1024/1024:.1f}MB → {total_after/1024/1024:.1f}MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())