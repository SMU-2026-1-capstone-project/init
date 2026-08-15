"""두 토큰이 같으면 기동을 거부하는가 (#230).

🔴 **이 테스트가 지키는 것은 «코드» 가 아니라 «사고 이력» 이다.** #134 는 두 토큰이 같은
   값이라 앱 번들에서 추출한 토큰으로 Spring 내부 gRPC 까지 뚫린 사건이었다. 그때 값을
   나눴는데 **같은 값을 다시 넣는 것을 막는 코드가 없었다** — 주석만 있었다.
   이 테스트가 없으면 가드가 조용히 지워져도 아무도 모른다.
"""
import importlib
import sys

import pytest


def _reload_config(monkeypatch, internal, public):
    monkeypatch.setenv("INTERNAL_API_TOKEN", internal)
    monkeypatch.setenv("AI_PUBLIC_TOKEN", public)
    sys.modules.pop("app.config", None)
    return importlib.import_module("app.config")


def test_같은_값이면_기동을_거부한다(monkeypatch):
    with pytest.raises(RuntimeError) as e:
        _reload_config(monkeypatch, "SAME_SECRET", "SAME_SECRET")
    # 메시지에 «왜» 가 남아야 한다 — 운영자가 로그만 보고 고칠 수 있어야 하므로
    assert "#230" in str(e.value)
    assert "번들" in str(e.value)


def test_다른_값이면_통과한다(monkeypatch):
    cfg = _reload_config(monkeypatch, "INTERNAL_SECRET", "PUBLIC_SECRET")
    assert cfg.settings.INTERNAL_API_TOKEN != cfg.settings.AI_PUBLIC_TOKEN


@pytest.mark.parametrize("internal,public", [("", ""), ("X", ""), ("", "X")])
def test_빈_값은_막지_않는다(monkeypatch, internal, public):
    """로컬·테스트가 토큰 없이 도는 경로가 있다. 운영 필수화는 compose 의 `:?` 가 맡는다 —
    여기서 같이 막으면 그 두 관심사가 섞인다."""
    _reload_config(monkeypatch, internal, public)
