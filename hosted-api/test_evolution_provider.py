import json
import pytest

import evolution_provider as provider


class FakeResponse:
    status = 201
    def __enter__(self): return self
    def __exit__(self, *args): return False
    def read(self, limit):
        return b'{"key":{"id":"wamid.test-1"}}'


def test_send_text_uses_configured_instance_and_server_api_key(monkeypatch):
    monkeypatch.setenv("EVOLUTION_SEND_ENABLED", "true")
    monkeypatch.setenv("EVOLUTION_API_URL", "https://evolution.example")
    monkeypatch.setenv("EVOLUTION_API_KEY", "server-secret")
    monkeypatch.setenv("EVOLUTION_BAILEYS_INSTANCE", "voicx-baileys")
    captured = {}
    def fake_urlopen(request, timeout):
        captured["url"] = request.full_url
        captured["key"] = request.get_header("Apikey")
        captured["payload"] = json.loads(request.data.decode())
        captured["timeout"] = timeout
        return FakeResponse()
    monkeypatch.setattr(provider, "urlopen", fake_urlopen)
    result = provider.send_evolution_text("baileys", "+967777000000", "hello")
    assert captured["url"] == "https://evolution.example/message/sendText/voicx-baileys"
    assert captured["key"] == "server-secret"
    assert captured["payload"] == {"number": "+967777000000", "text": "hello"}
    assert result["providerMessageId"] == "wamid.test-1"


def test_send_fails_closed_by_default(monkeypatch):
    monkeypatch.delenv("EVOLUTION_SEND_ENABLED", raising=False)
    with pytest.raises(provider.EvolutionNotConfigured) as exc:
        provider.send_evolution_text("baileys", "+967777000000", "hello")
    assert exc.value.code == "whatsapp_sending_disabled"


def test_channel_instances_are_separate(monkeypatch):
    monkeypatch.setenv("EVOLUTION_SEND_ENABLED", "true")
    monkeypatch.setenv("EVOLUTION_API_URL", "https://evolution.example")
    monkeypatch.setenv("EVOLUTION_API_KEY", "server-secret")
    monkeypatch.setenv("EVOLUTION_BAILEYS_INSTANCE", "wa-web")
    monkeypatch.setenv("EVOLUTION_CLOUD_INSTANCE", "wa-cloud")
    assert provider._channel_config("baileys")[2] == "wa-web"
    assert provider._channel_config("cloud_api")[2] == "wa-cloud"


def test_rejects_http_and_invalid_instance(monkeypatch):
    monkeypatch.setenv("EVOLUTION_SEND_ENABLED", "true")
    monkeypatch.setenv("EVOLUTION_API_URL", "http://evolution.example")
    monkeypatch.setenv("EVOLUTION_API_KEY", "server-secret")
    monkeypatch.setenv("EVOLUTION_BAILEYS_INSTANCE", "wa")
    with pytest.raises(provider.EvolutionNotConfigured):
        provider._channel_config("baileys")


def test_webhook_secret_is_channel_specific(monkeypatch):
    monkeypatch.setenv("EVOLUTION_WEBHOOK_SECRET_BAILEYS", "baileys-secret")
    monkeypatch.setenv("EVOLUTION_WEBHOOK_SECRET_CLOUD", "cloud-secret")
    assert provider.verify_webhook_secret("baileys", "baileys-secret")
    assert not provider.verify_webhook_secret("cloud_api", "baileys-secret")


def test_instance_mapping_is_server_side(monkeypatch):
    monkeypatch.setenv("EVOLUTION_INSTANCE_ORG_MAP", '{"wa-web":"org-1"}')
    assert provider.organization_for_instance("wa-web") == "org-1"
    assert provider.organization_for_instance("unknown") is None
