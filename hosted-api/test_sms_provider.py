import json
from urllib.error import HTTPError

import pytest

import sms_provider


class FakeResponse:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, limit):
        return b'{"success":true,"messageId":"sms-123"}'


def test_send_adasms_uses_server_secret_and_expected_payload(monkeypatch):
    monkeypatch.setenv("ADASMS_API_KEY", "server-only-test-key")
    captured = {}

    def fake_urlopen(request, timeout):
        captured["url"] = request.full_url
        captured["authorization"] = request.get_header("Authorization")
        captured["payload"] = json.loads(request.data.decode("utf-8"))
        captured["timeout"] = timeout
        return FakeResponse()

    monkeypatch.setattr(sms_provider, "urlopen", fake_urlopen)
    result = sms_provider.send_adasms("+967777405578", "مرحبا")

    assert captured["url"] == sms_provider.DEFAULT_ADASMS_URL
    assert captured["authorization"] == "Bearer server-only-test-key"
    assert captured["payload"] == {"phone": "+967777405578", "message": "مرحبا"}
    assert captured["timeout"] == 15
    assert result == {"httpStatus": 200, "providerMessageId": "sms-123"}


def test_send_adasms_fails_closed_without_key(monkeypatch):
    monkeypatch.delenv("ADASMS_API_KEY", raising=False)
    with pytest.raises(sms_provider.SmsProviderNotConfigured):
        sms_provider.send_adasms("+967777405578", "hello")


def test_send_adasms_does_not_allow_overridden_provider_url(monkeypatch):
    monkeypatch.setenv("ADASMS_API_KEY", "server-only-test-key")
    monkeypatch.setenv("ADASMS_API_URL", "https://attacker.example/collect")
    with pytest.raises(sms_provider.SmsProviderNotConfigured):
        sms_provider.send_adasms("+967777405578", "hello")


def test_send_adasms_sanitizes_provider_http_errors(monkeypatch):
    monkeypatch.setenv("ADASMS_API_KEY", "server-only-test-key")

    def fail(*args, **kwargs):
        raise HTTPError(sms_provider.DEFAULT_ADASMS_URL, 429, "rate limited", {}, None)

    monkeypatch.setattr(sms_provider, "urlopen", fail)
    with pytest.raises(sms_provider.SmsProviderError) as error:
        sms_provider.send_adasms("+967777405578", "hello")
    assert error.value.code == "sms_provider_rejected"
    assert error.value.http_status == 429
    assert "rate limited" not in str(error.value)
