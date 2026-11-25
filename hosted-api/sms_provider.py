"""Server-side Adasms SMS adapter. Never expose ADASMS_API_KEY to clients."""
from __future__ import annotations

import json
import os
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


DEFAULT_ADASMS_URL = "https://adasms-standalone.vercel.app/api/sms/send"


class SmsProviderNotConfigured(RuntimeError):
    pass


class SmsProviderError(RuntimeError):
    def __init__(self, code: str, http_status: int | None = None):
        super().__init__(code)
        self.code = code
        self.http_status = http_status


def send_adasms(phone: str, message: str) -> dict:
    api_key = os.getenv("ADASMS_API_KEY", "").strip()
    if not api_key:
        raise SmsProviderNotConfigured("sms_provider_not_configured")

    endpoint = os.getenv("ADASMS_API_URL", DEFAULT_ADASMS_URL).strip()
    if endpoint != DEFAULT_ADASMS_URL:
        raise SmsProviderNotConfigured("sms_provider_url_not_allowed")

    payload = json.dumps({"phone": phone, "message": message}, ensure_ascii=False).encode("utf-8")
    request = Request(
        endpoint,
        data=payload,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urlopen(request, timeout=15) as response:
            status = int(response.status)
            raw = response.read(32_768)
    except HTTPError as exc:
        # Do not propagate provider response bodies; they may contain private details.
        raise SmsProviderError("sms_provider_rejected", int(exc.code)) from None
    except (URLError, TimeoutError, OSError):
        raise SmsProviderError("sms_provider_unavailable") from None

    try:
        data = json.loads(raw.decode("utf-8")) if raw else {}
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise SmsProviderError("sms_provider_invalid_response", status) from None
    if not isinstance(data, dict) or data.get("success") is False or data.get("ok") is False:
        raise SmsProviderError("sms_provider_rejected", status)

    provider_id = data.get("messageId") or data.get("message_id") or data.get("id")
    return {
        "httpStatus": status,
        "providerMessageId": str(provider_id)[:160] if provider_id is not None else None,
    }
