from __future__ import annotations

import json
import os
import re
import hmac
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class EvolutionError(RuntimeError):
    def __init__(self, code: str, http_status: int | None = None):
        super().__init__(code)
        self.code = code
        self.http_status = http_status


class EvolutionNotConfigured(EvolutionError):
    pass


def _enabled() -> bool:
    return os.getenv("EVOLUTION_SEND_ENABLED", "false").strip().lower() in {"1", "true", "yes"}


def _channel_config(channel: str) -> tuple[str, str, str]:
    if channel not in {"baileys", "cloud_api"}:
        raise EvolutionError("unsupported_whatsapp_channel")
    base = os.getenv("EVOLUTION_API_URL", "").strip().rstrip("/")
    key = os.getenv("EVOLUTION_API_KEY", "").strip()
    instance_var = "EVOLUTION_BAILEYS_INSTANCE" if channel == "baileys" else "EVOLUTION_CLOUD_INSTANCE"
    instance = os.getenv(instance_var, "").strip()
    if not _enabled():
        raise EvolutionNotConfigured("whatsapp_sending_disabled")
    if not base or not key or not instance:
        raise EvolutionNotConfigured("evolution_provider_not_configured")
    if not base.startswith("https://"):
        raise EvolutionNotConfigured("evolution_https_required")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", instance):
        raise EvolutionNotConfigured("invalid_evolution_instance")
    return base, key, instance


def send_evolution_text(channel: str, phone: str, message: str, timeout: int = 15) -> dict:
    base, api_key, instance = _channel_config(channel)
    request = Request(
        f"{base}/message/sendText/{instance}",
        data=json.dumps({"number": phone, "text": message}, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "apikey": api_key},
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read(256_000)
            payload = json.loads(raw.decode("utf-8")) if raw else {}
            key = payload.get("key") or payload.get("message", {}).get("key") or {}
            message_id = key.get("id") or payload.get("messageId") or payload.get("id")
            return {"httpStatus": int(response.status), "providerMessageId": message_id,
                    "instance": instance, "channel": channel}
    except HTTPError as exc:
        raise EvolutionError("evolution_provider_rejected", exc.code) from None
    except (URLError, TimeoutError, OSError):
        # A timeout is an unknown delivery outcome. Never retry automatically.
        raise EvolutionError("evolution_provider_outcome_unknown") from None
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
        raise EvolutionError("evolution_provider_invalid_response") from None


def verify_webhook_secret(channel: str, supplied: str | None) -> bool:
    if channel not in {"baileys", "cloud_api"} or not supplied:
        return False
    expected = os.getenv(
        "EVOLUTION_WEBHOOK_SECRET_BAILEYS" if channel == "baileys"
        else "EVOLUTION_WEBHOOK_SECRET_CLOUD",
        "",
    ).strip()
    return bool(expected) and hmac.compare_digest(expected, supplied)


def organization_for_instance(instance: str) -> str | None:
    try:
        mapping = json.loads(os.getenv("EVOLUTION_INSTANCE_ORG_MAP", "{}"))
    except (TypeError, json.JSONDecodeError):
        return None
    value = mapping.get(instance) if isinstance(mapping, dict) else None
    return value if isinstance(value, str) and value else None


def forward_n8n_event(event: dict, timeout: int = 8) -> bool:
    url = os.getenv("N8N_WHATSAPP_WEBHOOK_URL", "").strip()
    token = os.getenv("N8N_WHATSAPP_WEBHOOK_TOKEN", "").strip()
    if not url or not token:
        return False
    if not url.startswith("https://"):
        return False
    request = Request(
        url, data=json.dumps(event, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Voicx-Webhook-Token": token},
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            return 200 <= int(response.status) < 300
    except (HTTPError, URLError, TimeoutError, OSError):
        return False
