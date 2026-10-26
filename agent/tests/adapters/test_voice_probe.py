from __future__ import annotations

import asyncio
import json
import wave
from contextlib import asynccontextmanager
from typing import TYPE_CHECKING

import pytest
from pydantic_ai.exceptions import ModelHTTPError
from pydantic_ai.messages import BinaryAudio, ModelMessage
from pydantic_ai.realtime import RealtimeModel, RealtimeModelSettings
from pydantic_ai.realtime.codec import (
    AudioDelta,
    CancelResponse,
    CommitAudio,
    CreateResponse,
    InputTranscript,
    OutputTranscript,
    RealtimeCodecEvent,
    RealtimeConnection,
    RealtimeInput,
    ResponseDone,
    ToolCall,
    ToolResult,
)
from pydantic_ai.realtime.profiles import RealtimeModelProfile

from popcorn_society_agent.adapters.voice_probe import (
    BYTES_PER_SECOND,
    MAX_OUTPUT_SECONDS,
    SAMPLE_RATE,
    ProbeOptions,
    VoiceProbeError,
    load_probe_audio,
    run_voice_probe,
)
from popcorn_society_agent.voice_probe_cli import FIXTURE

if TYPE_CHECKING:
    from collections.abc import AsyncGenerator, AsyncIterator, Sequence
    from pathlib import Path

    from pydantic_ai.models import ModelRequestParameters


class ScriptedConnection(RealtimeConnection):
    def __init__(self, scenario: str) -> None:
        self.scenario = scenario
        self.events: asyncio.Queue[RealtimeCodecEvent | None] = asyncio.Queue()
        self.sent: list[RealtimeInput] = []
        self.closed = False
        self.response_requested = asyncio.Event()

    async def send(self, content: RealtimeInput) -> None:
        self.sent.append(content)
        if isinstance(content, CreateResponse):
            self.response_requested.set()
            if self.scenario == "timeout":
                return
            if self.scenario == "disconnect":
                await self.events.put(None)
                return
            await self.events.put(InputTranscript("synthetic fixture", is_final=True))
            title = "Other movie" if self.scenario == "wrong_target" else "Forrest Gump"
            await self.events.put(
                ToolCall(
                    "fixture-call",
                    tool_name="search_fixture_movie",
                    args=json.dumps({"title": title}),
                )
            )
            await self.events.put(ResponseDone())
        elif isinstance(content, ToolResult):
            transcript = (
                "Forrest Gump runs for 1420 minutes."
                if self.scenario == "wrong_reply"
                else "Forrest Gump runs for one hundred and forty-two minutes."
            )
            await self.events.put(OutputTranscript(transcript))
            audio_size = BYTES_PER_SECOND // 2
            if self.scenario == "oversize":
                audio_size = BYTES_PER_SECOND * (MAX_OUTPUT_SECONDS + 1)
            await self.events.put(AudioDelta(b"\x00" * audio_size))
            if self.scenario not in {"interrupt", "ignored_interrupt"}:
                await self.events.put(ResponseDone())
        elif isinstance(content, CancelResponse):
            await self.events.put(ResponseDone(interrupted=self.scenario != "ignored_interrupt"))

    async def __aiter__(self) -> AsyncIterator[RealtimeCodecEvent]:
        while (event := await self.events.get()) is not None:
            yield event


class ScriptedModel(RealtimeModel):
    def __init__(self, scenario: str = "complete") -> None:
        self.connection = ScriptedConnection(scenario)

    @property
    def model_name(self) -> str:
        return "test-voice"

    @property
    def system(self) -> str:
        return "test"

    @property
    def profile(self) -> RealtimeModelProfile:
        return RealtimeModelProfile(
            supports_manual_turn_control=True,
            supports_interruption=True,
            supports_output_truncation=False,
            audio_input_sample_rate=SAMPLE_RATE,
            audio_output_sample_rate=SAMPLE_RATE,
        )

    @asynccontextmanager
    async def connect(
        self,
        *,
        messages: Sequence[ModelMessage],
        model_settings: RealtimeModelSettings | None,
        model_request_parameters: ModelRequestParameters,
    ) -> AsyncGenerator[RealtimeConnection]:
        assert [tool.name for tool in model_request_parameters.function_tools] == [
            "search_fixture_movie"
        ]
        try:
            if self.connection.scenario == "auth_error":
                raise ModelHTTPError(
                    401, model_name="test-voice", body="synthetic-sensitive-provider-body"
                )
            yield self.connection
        finally:
            self.connection.closed = True


@pytest.mark.asyncio
async def test_audio_tool_roundtrip_and_connection_cleanup() -> None:
    model = ScriptedModel()
    audio = b"\x00" * BYTES_PER_SECOND
    result = await run_voice_probe(model, audio, ProbeOptions())

    assert result.report.passed
    assert result.report.fixture_tool_calls == 1
    assert result.report.output_audio_seconds == 0.5
    assert model.connection.closed
    sent_audio = b"".join(
        chunk.data for chunk in model.connection.sent if isinstance(chunk, BinaryAudio)
    )
    assert sent_audio == audio
    assert any(isinstance(event, CommitAudio) for event in model.connection.sent)
    assert "synthetic fixture" not in result.report.model_dump_json()
    assert "forrest gump" not in result.report.model_dump_json().lower()


@pytest.mark.asyncio
async def test_wrong_tool_target_does_not_pass_even_with_audio() -> None:
    result = await run_voice_probe(
        ScriptedModel("wrong_target"), b"\x00" * BYTES_PER_SECOND, ProbeOptions()
    )
    assert not result.report.passed
    assert not result.report.fixture_arguments_match


@pytest.mark.asyncio
async def test_wrong_spoken_runtime_does_not_pass_with_correct_tool() -> None:
    result = await run_voice_probe(
        ScriptedModel("wrong_reply"), b"\x00" * BYTES_PER_SECOND, ProbeOptions()
    )
    assert result.report.fixture_arguments_match
    assert not result.report.fixture_reply_matches
    assert not result.report.passed


@pytest.mark.asyncio
async def test_cancellation_request_without_provider_confirmation_does_not_pass() -> None:
    result = await run_voice_probe(
        ScriptedModel("ignored_interrupt"),
        b"\x00" * BYTES_PER_SECOND,
        ProbeOptions(interrupt_after_audio_ms=100),
    )
    assert result.report.interruption_requested
    assert not result.report.interruption_confirmed
    assert not result.report.passed


@pytest.mark.asyncio
async def test_caller_cancellation_propagates_and_closes_session() -> None:
    model = ScriptedModel("timeout")
    task = asyncio.create_task(run_voice_probe(model, b"\x00" * BYTES_PER_SECOND, ProbeOptions()))
    async with asyncio.timeout(2):
        await model.connection.response_requested.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert model.connection.closed


@pytest.mark.asyncio
async def test_interruption_cancels_without_unsupported_truncation() -> None:
    model = ScriptedModel("interrupt")
    result = await run_voice_probe(
        model, b"\x00" * BYTES_PER_SECOND, ProbeOptions(interrupt_after_audio_ms=100)
    )
    assert result.report.passed
    assert result.report.interruption_requested
    assert sum(isinstance(event, CancelResponse) for event in model.connection.sent) == 1
    assert model.connection.closed


@pytest.mark.asyncio
async def test_timeout_closes_the_connection(monkeypatch: pytest.MonkeyPatch) -> None:
    model = ScriptedModel("timeout")
    real_timeout = asyncio.timeout
    deadline = real_timeout(None)

    def controlled_timeout(delay: float | None) -> asyncio.Timeout:
        return deadline if delay == 0.1 else real_timeout(delay)

    monkeypatch.setattr(asyncio, "timeout", controlled_timeout)
    async with real_timeout(5):
        task = asyncio.create_task(
            run_voice_probe(model, b"\x00" * BYTES_PER_SECOND, ProbeOptions(session_seconds=0.1))
        )
        try:
            # Expire an established session, independently of provider setup speed.
            await model.connection.response_requested.wait()
            deadline.reschedule(asyncio.get_running_loop().time())
            with pytest.raises(VoiceProbeError, match="voice_session_timeout"):
                await task
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
    assert model.connection.closed


@pytest.mark.asyncio
async def test_output_audio_limit_closes_the_connection() -> None:
    model = ScriptedModel("oversize")
    async with asyncio.timeout(5):
        with pytest.raises(VoiceProbeError, match="output_audio_limit_exceeded"):
            await run_voice_probe(model, b"\x00" * BYTES_PER_SECOND, ProbeOptions())
    assert model.connection.closed


@pytest.mark.asyncio
async def test_disconnect_never_reports_success() -> None:
    model = ScriptedModel("disconnect")
    try:
        result = await run_voice_probe(
            model, b"\x00" * BYTES_PER_SECOND, ProbeOptions(session_seconds=0.2)
        )
    except VoiceProbeError:
        pass
    else:
        assert not result.report.passed
    assert model.connection.closed


@pytest.mark.asyncio
async def test_authentication_failure_does_not_expose_provider_body() -> None:
    model = ScriptedModel("auth_error")
    with pytest.raises(VoiceProbeError, match=r"^voice_authentication_failed$"):
        await run_voice_probe(model, b"\x00" * BYTES_PER_SECOND, ProbeOptions())
    assert model.connection.closed


def test_checked_in_synthetic_fixture_has_valid_format() -> None:
    audio = load_probe_audio(FIXTURE)
    assert BYTES_PER_SECOND < len(audio) < 15 * BYTES_PER_SECOND


@pytest.mark.parametrize("sample_rate", [16_000, 44_100, 48_000])
def test_wrong_sample_rate_rejected_before_connection(tmp_path: Path, sample_rate: int) -> None:
    path = tmp_path / "wrong-rate.wav"
    with wave.open(str(path), "wb") as audio:
        audio.setparams((1, 2, sample_rate, 0, "NONE", "not compressed"))
        audio.writeframes(b"\x00" * BYTES_PER_SECOND)
    with pytest.raises(VoiceProbeError, match="24000_hz"):
        load_probe_audio(path)
