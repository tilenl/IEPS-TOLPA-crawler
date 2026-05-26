"""Check that Ollama is installed and the HTTP API is reachable."""

from __future__ import annotations

import json
import shutil
import urllib.error
import urllib.request
from dataclasses import dataclass


@dataclass(frozen=True)
class OllamaStatus:
    """Result of an Ollama readiness check."""

    cli_on_path: bool
    api_reachable: bool
    model_available: bool
    api_base: str
    model: str
    detail: str


def _http_get(url: str, timeout: float = 3.0) -> tuple[int, str]:
    request = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", errors="replace")
    except urllib.error.URLError as exc:
        raise ConnectionError(str(exc.reason)) from exc


def check_ollama(
    *,
    api_base: str = "http://localhost:11434",
    model: str = "llama3.2:1b",
) -> OllamaStatus:
    """Probe CLI presence, API health, and whether the requested model is pulled."""
    cli_on_path = shutil.which("ollama") is not None
    api_reachable = False
    model_available = False
    detail = ""

    tags_url = f"{api_base.rstrip('/')}/api/tags"
    try:
        status, body = _http_get(tags_url)
        api_reachable = status == 200
        if api_reachable:
            payload = json.loads(body)
            names = {item.get("name", "") for item in payload.get("models", [])}
            # Ollama may report "llama3.2:1b" or "llama3.2:1b-instruct-q4_0" etc.
            model_available = any(
                name == model or name.startswith(f"{model}") or name.split(":")[0] == model.split(":")[0]
                for name in names
            )
            if not model_available and names:
                detail = f"API OK; pulled models: {', '.join(sorted(names)[:8])}"
            elif not model_available:
                detail = "API OK but no models pulled yet."
        else:
            detail = f"GET /api/tags returned HTTP {status}"
    except ConnectionError as exc:
        detail = str(exc)
    except json.JSONDecodeError:
        api_reachable = True
        detail = "API reachable but /api/tags response was not JSON."

    return OllamaStatus(
        cli_on_path=cli_on_path,
        api_reachable=api_reachable,
        model_available=model_available,
        api_base=api_base,
        model=model,
        detail=detail,
    )


def format_install_help(status: OllamaStatus) -> str:
    """Return user-facing install/run instructions when Ollama is not ready."""
    lines = [
        "Ollama is required for PA3 answer generation but is not ready.",
        "",
    ]
    if not status.cli_on_path and not status.api_reachable:
        lines.extend(
            [
                "Install Ollama on macOS (pick one):",
                "  brew install --cask ollama",
                "  # or download the app: https://ollama.com/download/mac",
                "",
                "Then start it:",
                "  open -a Ollama          # menu-bar app (starts the server)",
                "  # or: ollama serve",
                "",
            ]
        )
    elif not status.api_reachable:
        lines.extend(
            [
                "Ollama may be installed but the server is not running.",
                "Start it with:",
                "  open -a Ollama",
                "  # or: ollama serve",
                "",
            ]
        )
    if status.api_reachable and not status.model_available:
        lines.extend(
            [
                f"Pull the course model (once):",
                f"  ollama pull {status.model}",
                "",
            ]
        )
    if status.detail:
        lines.append(f"Diagnostic: {status.detail}")
    lines.append(f"Verify: curl {status.api_base.rstrip('/')}/api/tags")
    return "\n".join(lines)


def require_ollama(*, api_base: str = "http://localhost:11434", model: str = "llama3.2:1b") -> OllamaStatus:
    """Raise RuntimeError with install instructions if Ollama is not usable."""
    status = check_ollama(api_base=api_base, model=model)
    if status.api_reachable and status.model_available:
        return status
    if status.api_reachable and not status.model_available:
        raise RuntimeError(format_install_help(status))
    raise RuntimeError(format_install_help(status))
