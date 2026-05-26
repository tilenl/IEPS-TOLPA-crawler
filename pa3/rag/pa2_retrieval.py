"""PA3 bridge to PA2 retrieval (avoids shadowing the pa2 `retrieval` module name)."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import ModuleType

_PA2_IMPL = Path(__file__).resolve().parents[2] / "pa2" / "implementation-extraction"
_PA2_RETRIEVAL_PATH = _PA2_IMPL / "retrieval.py"


def _load_pa2_retrieval() -> ModuleType:
    """Load pa2/implementation-extraction/retrieval.py under a unique module name."""
    name = "_pa2_retrieval_lib"
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, _PA2_RETRIEVAL_PATH)
    if spec is None or spec.loader is None:
        raise ImportError(f"Cannot load PA2 retrieval from {_PA2_RETRIEVAL_PATH}")
    if str(_PA2_IMPL) not in sys.path:
        sys.path.insert(0, str(_PA2_IMPL))
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


_pa2 = _load_pa2_retrieval()

RetrievalConfig = _pa2.RetrievalConfig
RetrievalHit = _pa2.RetrievalHit
retrieve = _pa2.retrieve

__all__ = ["RetrievalConfig", "RetrievalHit", "retrieve"]
