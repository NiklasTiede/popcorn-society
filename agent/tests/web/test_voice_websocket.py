from __future__ import annotations

import asyncio
import json
from typing import TYPE_CHECKING

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from popcorn_society_agent.adapters.logging import configure_logging
from popcorn_society_agent.concierge.voice import VoiceCommand, VoiceEvent
from popcorn_society_agent.concierge.voice_quota import (
    MemoryVoiceQuota,
    VoiceGrant,
    VoiceQuotaUnavailable,
)
from popcorn_society_agent.web.voice import create_voice_router

if TYPE_CHECKING:
    from pydantic import SecretStr

    from popcorn_society_agent.concierge.voice import VoiceTransport


class EchoVoice:
    closed = False

    def __init__(self) -> None:
        self.contexts: list[VoiceCommand] = []
        self.texts: list[str] = []

    async def run(self, transport: VoiceTransport, delegation: SecretStr | None = None) -> None:
        try:
            await transport.send(VoiceEvent(type="ready"))
            while True:
                data = await transport.receive()
                if isinstance(data, VoiceCommand):
                    if data.type == "context":
                        self.contexts.append(data)
                    if data.type == "text" and data.text is not None:
                        self.texts.append(data.text)
                    if data.type == "end":
                        return
                else:
                    await transport.send(data)
        finally:
            self.closed = True


def client_for(
    runner: EchoVoice | None, seconds: float = 10, browser_seconds: float = 1200
) -> TestClient:
    app = FastAPI()
    app.include_router(
        create_voice_router(
            runner,
            allowed_origins=("http://localhost:3000",),
            session_seconds=seconds,
            browser_seconds=browser_seconds,
        )
    )
    return TestClient(app)


def test_audio_end_and_disconnect_cleanup() -> None:
    runner = EchoVoice()
    with (
        client_for(runner) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        assert ws.receive_json()["type"] == "ready"
        ws.send_bytes(b"\x00\x01" * 2400)
        assert ws.receive_bytes() == b"\x00\x01" * 2400
        ws.send_json({"type": "end"})
        with pytest.raises(WebSocketDisconnect):
            ws.receive_json()
    assert runner.closed


def test_model_selection_routes_only_enabled_models_and_shares_session_budget() -> None:
    grok, live = EchoVoice(), EchoVoice()
    app = FastAPI()
    app.include_router(
        create_voice_router(
            grok,
            live_runner=live,
            allowed_origins=("http://localhost:3000",),
            session_seconds=10,
            browser_seconds=1200,
        )
    )
    with TestClient(app) as client:
        assert client.get("/v1/voice/models").json() == {"models": ["grok", "gpt-live-1"]}
        for model, runner in [("grok", grok), ("gpt-live-1", live)]:
            with client.websocket_connect(
                "/v1/voice", headers={"origin": "http://localhost:3000"}
            ) as ws:
                ws.send_json(
                    {
                        "type": "start",
                        "browser_id": "browser-00000000-0000-4000-8000-000000000000",
                        "model": model,
                    }
                )
                assert ws.receive_json()["type"] == "ready"
                ws.send_json({"type": "text", "text": model})
                ws.send_bytes(b"\x00" * 960)
                assert ws.receive_bytes() == b"\x00" * 960
                ws.send_json({"type": "end"})
                with pytest.raises(WebSocketDisconnect):
                    ws.receive_json()
            assert runner.texts == [model]
        with client.websocket_connect(
            "/v1/voice", headers={"origin": "http://localhost:3000"}
        ) as ws:
            ws.send_json(
                {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
            )
            assert ws.receive_json()["type"] == "ready"
            ws.send_json({"type": "end"})


def test_unavailable_live_model_does_not_silently_fall_back_to_grok() -> None:
    grok = EchoVoice()
    with client_for(grok) as client:
        assert client.get("/v1/voice/models").json() == {"models": ["grok"]}
        with client.websocket_connect(
            "/v1/voice", headers={"origin": "http://localhost:3000"}
        ) as ws:
            ws.send_json(
                {
                    "type": "start",
                    "browser_id": "browser-00000000-0000-4000-8000-000000000000",
                    "model": "gpt-live-1",
                }
            )
            assert "not enabled" in ws.receive_json()["text"]
    assert not grok.closed


@pytest.mark.parametrize(
    "start",
    [
        {"type": "invalid"},
        {
            "type": "start",
            "browser_id": "browser-00000000-0000-4000-8000-000000000000",
            "model": "gpt-live-1",
        },
        {
            "type": "start",
            "browser_id": "browser-00000000-0000-4000-8000-000000000000",
            "delegation": "synthetic-invalid",
        },
    ],
)
def test_rejected_start_does_not_consume_admission(start: dict[str, str]) -> None:
    with client_for(EchoVoice(), browser_seconds=1200) as client:
        with client.websocket_connect(
            "/v1/voice", headers={"origin": "http://localhost:3000"}
        ) as ws:
            ws.send_json(start)
            assert ws.receive_json()["type"] == "error"
        with client.websocket_connect(
            "/v1/voice", headers={"origin": "http://localhost:3000"}
        ) as ws:
            ws.send_json(
                {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
            )
            assert ws.receive_json()["type"] == "ready"
            ws.send_json({"type": "end"})


def test_quota_failure_prevents_provider_connection_and_releases_concurrency() -> None:
    class UnavailableQuota:
        def reserve(self, browser_id: str) -> VoiceGrant:
            raise VoiceQuotaUnavailable

        def settle(self, grant_id: str, used_seconds: float) -> None:
            raise VoiceQuotaUnavailable

    runner = EchoVoice()
    app = FastAPI()
    app.include_router(
        create_voice_router(
            runner,
            allowed_origins=("http://localhost:3000",),
            quota=UnavailableQuota(),
            session_seconds=10,
            browser_seconds=1200,
        )
    )
    with TestClient(app) as client:
        for _ in range(3):
            with client.websocket_connect(
                "/v1/voice", headers={"origin": "http://localhost:3000"}
            ) as ws:
                ws.send_json(
                    {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
                )
                assert "admission is temporarily unavailable" in ws.receive_json()["text"]
    assert not runner.closed


def test_active_limit_is_shared_and_does_not_spend_another_start() -> None:
    quota = MemoryVoiceQuota(1200, 15)
    app = FastAPI()
    app.include_router(
        create_voice_router(
            EchoVoice(),
            live_runner=EchoVoice(),
            allowed_origins=("http://localhost:3000",),
            quota=quota,
            session_seconds=10,
            browser_seconds=1200,
        )
    )
    with (
        TestClient(app) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as grok,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as live,
    ):
        grok.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        live.send_json(
            {
                "type": "start",
                "browser_id": "browser-00000000-0000-4000-8000-000000000000",
                "model": "gpt-live-1",
            }
        )
        assert grok.receive_json()["type"] == live.receive_json()["type"] == "ready"
        with client.websocket_connect(
            "/v1/voice", headers={"origin": "http://localhost:3000"}
        ) as extra:
            assert "busy" in extra.receive_json()["text"]
        assert quota.reserve("another-browser").seconds == 5
        assert quota.reserve("another-browser").seconds == 0
        grok.send_json({"type": "end"})
        live.send_json({"type": "end"})


@pytest.mark.parametrize("origin", ["http://evil.example", "null", ""])
def test_origin_rejected_before_provider_connection(origin: str) -> None:
    runner = EchoVoice()
    with (
        client_for(runner) as client,
        pytest.raises(WebSocketDisconnect),
        client.websocket_connect("/v1/voice", headers={"origin": origin}),
    ):
        pass
    assert not runner.closed


@pytest.mark.parametrize(
    "payload", [b"\x00", b"\x00" * 9602, '{"type":"execute","url":"https://evil.example"}']
)
def test_invalid_protocol_closes_session(payload: bytes | str) -> None:
    runner = EchoVoice()
    with (
        client_for(runner) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        ws.receive_json()
        if isinstance(payload, bytes):
            ws.send_bytes(payload)
        else:
            ws.send_text(payload)
        assert ws.receive_json()["type"] == "error"
        with pytest.raises(WebSocketDisconnect):
            ws.receive_json()
    assert runner.closed


def test_disabled_voice_and_daily_session_limit(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging(json_output=True)
    with (
        client_for(None) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        event = ws.receive_json()
        assert event["type"] == "error"
        assert "make run-agent-voice" in event["text"]
    logs = capsys.readouterr().out
    assert '"outcome": "disabled"' in logs
    assert '"error_code": "voice_disabled"' in logs
    # Many short restarts must not exhaust a time allowance.
    with client_for(EchoVoice()) as client:
        for _ in range(10):
            with client.websocket_connect(
                "/v1/voice", headers={"origin": "http://localhost:3000"}
            ) as ws:
                ws.send_json(
                    {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
                )
                assert ws.receive_json()["type"] == "ready"
                ws.send_json({"type": "end"})
                with pytest.raises(WebSocketDisconnect):
                    ws.receive_json()


@pytest.mark.parametrize("seconds", [0.05, 300.0, 600.0])
def test_deadline_cancels_runner(
    seconds: float, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    configure_logging(json_output=True)
    real_timeout = asyncio.timeout

    def short_session_timeout(delay: float | None) -> asyncio.Timeout:
        return real_timeout(0.05 if delay in (300, 600) else delay)

    monkeypatch.setattr(asyncio, "timeout", short_session_timeout)
    runner = EchoVoice()
    with (
        client_for(runner, seconds=seconds) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        assert ws.receive_json()["type"] == "ready"
        event = ws.receive_json()
        assert event["type"] == "error"
        expected = f"{int(seconds / 60)}-minute" if seconds >= 60 else "0.05-second"
        assert f"{expected} time limit" in event["text"]
        # Wait for quota settlement and the final log before TestClient cancels the handler.
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_json()
        assert closed.value.code == 1000
    assert runner.closed
    events = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    ended = next(event for event in events if event["event"] == "voice_session_ended")
    assert ended["outcome"] == "time_limit"
    assert ended["duration_ms"] >= 50
    assert ended["session_limit_seconds"] == seconds
    assert ended["phase"] == "active"


def test_end_cancels_pending_provider_handshake() -> None:
    class ConnectingVoice(EchoVoice):
        async def run(self, transport: VoiceTransport, delegation: SecretStr | None = None) -> None:
            try:
                await transport.send(VoiceEvent(type="status", status="thinking"))
                await asyncio.Event().wait()
            finally:
                self.closed = True

    runner = ConnectingVoice()
    with (
        client_for(runner) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        ws.receive_json()
        ws.send_json({"type": "end"})
        with pytest.raises(WebSocketDisconnect):
            ws.receive_json()
    assert runner.closed


@pytest.mark.parametrize("packets", [20, 21])
def test_microphone_backlog_has_400ms_headroom_and_reports_overflow(
    packets: int,
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging(json_output=True)

    class BlockedVoice(EchoVoice):
        async def run(self, transport: VoiceTransport, delegation: SecretStr | None = None) -> None:
            try:
                await transport.send(VoiceEvent(type="ready"))
                await asyncio.Event().wait()
            finally:
                self.closed = True

    runner = BlockedVoice()
    with (
        client_for(runner) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        assert ws.receive_json()["type"] == "ready"
        for _ in range(packets):
            ws.send_bytes(b"\x00" * 960)  # 20ms of mono PCM16 at 24kHz.
        if packets == 20:
            ws.send_json({"type": "end"})
        else:
            error = ws.receive_json()
            assert error["type"] == "error"
            assert "could not keep up" in error["text"]
        with pytest.raises(WebSocketDisconnect):
            ws.receive_json()
    assert runner.closed
    events = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    ended = next(event for event in events if event["event"] == "voice_session_ended")
    assert ended["outcome"] == ("user_end" if packets == 20 else "input_backpressure")
    assert ended["audio_input_bytes"] == packets * 960


@pytest.mark.parametrize("output_limit", [False, True])
def test_provider_value_errors_and_output_limits_are_not_invalid_browser_messages(
    output_limit: bool,
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging(json_output=True)

    class FailingVoice(EchoVoice):
        async def run(self, transport: VoiceTransport, delegation: SecretStr | None = None) -> None:
            await transport.send(VoiceEvent(type="ready"))
            if output_limit:
                await transport.send(b"\x00" * 480_002)
            else:
                raise ValueError("synthetic-private-provider-payload")

    with (
        client_for(FailingVoice()) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        assert ws.receive_json()["type"] == "ready"
        error = ws.receive_json()
        assert error["type"] == "error"
        assert ("audio limit" if output_limit else "connection failed") in error["text"]
    logs = capsys.readouterr().out
    assert "synthetic-private" not in logs
    assert "synthetic-private" not in error["text"]
    events = [json.loads(line) for line in logs.splitlines()]
    ended = next(event for event in events if event["event"] == "voice_session_ended")
    assert ended["error_code"] == (
        "voice_output_audio_limit" if output_limit else "voice_provider_error"
    )


def test_usage_limit_is_explained_without_exposing_provider_errors() -> None:
    from popcorn_society_agent.concierge.voice import VoiceSessionLimitError

    class LimitedVoice(EchoVoice):
        async def run(self, transport: VoiceTransport, delegation: SecretStr | None = None) -> None:
            await transport.send(VoiceEvent(type="ready"))
            raise VoiceSessionLimitError("synthetic provider payload must stay private")

    with (
        client_for(LimitedVoice()) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        assert ws.receive_json()["type"] == "ready"
        event = ws.receive_json()
        assert event["type"] == "error"
        assert "usage limit" in event["text"]
        assert "synthetic" not in event["text"]
        with pytest.raises(WebSocketDisconnect):
            ws.receive_json()


def test_personal_voice_cannot_start_without_verified_delegation() -> None:
    runner = EchoVoice()
    with (
        client_for(runner) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {
                "type": "start",
                "browser_id": "browser-00000000-0000-4000-8000-000000000000",
                "delegation": "synthetic-invalid",
            }
        )
        event = ws.receive_json()
        assert event["type"] == "error"
        assert "Sign in again" in event["text"]
    assert not runner.closed


def test_context_updates_are_validated_and_delivered_in_order_without_ending_voice() -> None:
    runner = EchoVoice()
    with (
        client_for(runner) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        assert ws.receive_json()["type"] == "ready"
        for movie_id in (6, 7):
            ws.send_json({"type": "context", "context": {"page": "movie", "movieId": movie_id}})
        ws.send_json({"type": "text", "text": "  Tell me about Forrest Gump  "})
        ws.send_bytes(b"\x00\x01" * 2400)
        assert ws.receive_bytes() == b"\x00\x01" * 2400
        assert runner.texts == ["Tell me about Forrest Gump"]
        assert [command.context.movie_id for command in runner.contexts if command.context] == [
            6,
            7,
        ]
        ws.send_json({"type": "end"})


def test_login_credential_reaches_runner_without_logging_or_replacing_socket(
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging(json_output=True)

    class LoginVoice(EchoVoice):
        async def run(self, transport: VoiceTransport, delegation: SecretStr | None = None) -> None:
            assert delegation is None
            await transport.send(VoiceEvent(type="ready"))
            command = await transport.receive()
            assert isinstance(command, VoiceCommand) and command.type == "authenticate"
            assert command.delegation is not None
            assert command.delegation.get_secret_value() == "synthetic-private-login"
            await transport.send(VoiceEvent(type="authenticated"))
            audio = await transport.receive()
            assert isinstance(audio, bytes)
            await transport.send(audio)
            await asyncio.Event().wait()

    with (
        client_for(LoginVoice()) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        assert ws.receive_json()["type"] == "ready"
        ws.send_json({"type": "authenticate", "delegation": "synthetic-private-login"})
        assert ws.receive_json()["type"] == "authenticated"
        ws.send_bytes(b"\x00" * 960)
        assert ws.receive_bytes() == b"\x00" * 960
        ws.send_json({"type": "end"})
    assert "synthetic-private-login" not in capsys.readouterr().out


@pytest.mark.parametrize("ready", [False, True])
def test_provider_timeout_is_not_reported_as_session_expiry(
    ready: bool, capsys: pytest.CaptureFixture[str]
) -> None:
    configure_logging(json_output=True)

    class TimedOutVoice(EchoVoice):
        async def run(self, transport: VoiceTransport, delegation: SecretStr | None = None) -> None:
            if ready:
                await transport.send(VoiceEvent(type="ready"))
            raise TimeoutError("synthetic-private-provider-message")

    with (
        client_for(TimedOutVoice()) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        if ready:
            assert ws.receive_json()["type"] == "ready"
        event = ws.receive_json()
        assert event["text"] == "Voice connection timed out. Please reconnect."
        # Wait for quota settlement and logging before TestClient cancels the handler.
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_json()
        assert closed.value.code == 1000
    logs = capsys.readouterr().out
    assert "synthetic-private" not in logs
    events = [json.loads(line) for line in logs.splitlines()]
    ended = next(event for event in events if event["event"] == "voice_session_ended")
    assert ended["outcome"] == "connection_timeout"
    assert ended["phase"] == ("active" if ready else "provider_connect")
    assert ended["duration_ms"] < 10_000


@pytest.mark.parametrize("delivery_error", [None, TimeoutError, WebSocketDisconnect])
def test_idle_expiry_is_reported_and_logged_separately(
    delivery_error: type[Exception] | None,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    from popcorn_society_agent.concierge.voice import VoiceIdleTimeoutError
    from popcorn_society_agent.web.voice import BrowserVoiceTransport

    original_send = BrowserVoiceTransport.send

    async def send(transport: BrowserVoiceTransport, event: VoiceEvent | bytes) -> None:
        if isinstance(event, VoiceEvent) and event.type == "standby" and delivery_error:
            raise delivery_error()
        await original_send(transport, event)

    monkeypatch.setattr(BrowserVoiceTransport, "send", send)
    configure_logging(json_output=True)

    class IdleVoice(EchoVoice):
        async def run(self, transport: VoiceTransport, delegation: SecretStr | None = None) -> None:
            await transport.send(VoiceEvent(type="ready"))
            raise VoiceIdleTimeoutError

    with (
        client_for(IdleVoice()) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json(
            {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
        )
        assert ws.receive_json()["type"] == "ready"
        if delivery_error is None:
            assert ws.receive_json()["type"] == "standby"
        with pytest.raises(WebSocketDisconnect):
            ws.receive_json()
    events = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    ended = next(event for event in events if event["event"] == "voice_session_ended")
    assert ended["outcome"] == "idle_timeout"


def test_session_logs_correlate_and_count_activity_without_payload(
    capsys: pytest.CaptureFixture[str],
) -> None:
    import structlog

    configure_logging(json_output=True)

    class LoggingVoice(EchoVoice):
        async def run(self, transport: VoiceTransport, delegation: SecretStr | None = None) -> None:
            structlog.get_logger().info("synthetic_runner_event")
            await super().run(transport, delegation)

    runner = LoggingVoice()
    with client_for(runner) as client:
        for _ in range(2):
            with client.websocket_connect(
                "/v1/voice", headers={"origin": "http://localhost:3000"}
            ) as ws:
                ws.send_json(
                    {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
                )
                assert ws.receive_json()["type"] == "ready"
                ws.send_json({"type": "text", "text": "synthetic-private-user-message"})
                ws.send_bytes(b"\x00\x01" * 2400)
                assert ws.receive_bytes() == b"\x00\x01" * 2400
                ws.send_json({"type": "end"})
                with pytest.raises(WebSocketDisconnect):
                    ws.receive_json()
    logs = capsys.readouterr().out
    assert "synthetic-private-user-message" not in logs
    events = [json.loads(line) for line in logs.splitlines()]
    starts = [event for event in events if event["event"] == "voice_session_started"]
    assert len(starts) == 2
    assert starts[0]["request_id"] != starts[1]["request_id"]
    for start in starts:
        matching = [event for event in events if event.get("request_id") == start["request_id"]]
        assert {event["event"] for event in matching} == {
            "voice_session_started",
            "synthetic_runner_event",
            "voice_session_ready",
            "voice_first_audio_packet_sent",
            "voice_session_ended",
        }
        ended = next(event for event in matching if event["event"] == "voice_session_ended")
        first_audio = [
            event for event in matching if event["event"] == "voice_first_audio_packet_sent"
        ]
        assert len(first_audio) == 1
        assert first_audio[0]["duration_ms"] >= 0
        assert ended["outcome"] == "user_end"
        assert ended["typed_messages"] == 1
        assert ended["control_messages"] == 2
        assert ended["audio_input_bytes"] == ended["audio_output_bytes"] == 4800
        assert ended["turns"] == 0


@pytest.mark.asyncio
async def test_buffered_socket_burst_yields_to_consumer_without_losing_audio() -> None:
    from typing import cast

    from fastapi import WebSocket

    from popcorn_society_agent.web.voice import BrowserVoiceTransport

    packets = [bytes([index, 0]) * 480 for index in range(40)]

    class BurstSocket:
        def __init__(self) -> None:
            self.messages: list[dict[str, object]] = [
                {"type": "websocket.receive", "bytes": data} for data in packets
            ]
            self.messages.append({"type": "websocket.receive", "text": '{"type":"end"}'})

        async def receive(self) -> dict[str, object]:
            return self.messages.pop(0)  # Buffered socket reads need not yield to other tasks.

    transport = BrowserVoiceTransport(cast("WebSocket", BurstSocket()), seconds=10)
    received: list[bytes | VoiceCommand] = []

    async def consume() -> None:
        received.extend([await transport.receive() for _ in packets])

    async with asyncio.timeout(1):
        await asyncio.gather(transport.read_browser(), consume())
    assert received == packets
    assert transport.end_requested


def test_browser_time_exhaustion_closes_provider_and_next_start_is_rejected() -> None:
    runner = EchoVoice()
    with client_for(runner, browser_seconds=0.05) as client:
        with client.websocket_connect(
            "/v1/voice", headers={"origin": "http://localhost:3000"}
        ) as ws:
            ws.send_json(
                {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
            )
            assert ws.receive_json()["type"] == "ready"
            assert ws.receive_json()["type"] == "quota-warning"
            event = ws.receive_json()
            assert event["type"] == "error"
            assert "browser's voice time" in event["text"]
            with pytest.raises(WebSocketDisconnect):
                ws.receive_json()
        assert runner.closed
        with client.websocket_connect(
            "/v1/voice", headers={"origin": "http://localhost:3000"}
        ) as ws:
            ws.send_json(
                {"type": "start", "browser_id": "browser-00000000-0000-4000-8000-000000000000"}
            )
            assert "browser's voice time" in ws.receive_json()["text"]


@pytest.mark.parametrize("browser_id", [None, "", "account-7", "browser-not-a-uuid", "x" * 500])
def test_invalid_browser_identity_never_connects_provider(browser_id: str | None) -> None:
    runner = EchoVoice()
    with (
        client_for(runner) as client,
        client.websocket_connect("/v1/voice", headers={"origin": "http://localhost:3000"}) as ws,
    ):
        ws.send_json({"type": "start", "browser_id": browser_id})
        assert ws.receive_json()["type"] == "error"
    assert not runner.closed
