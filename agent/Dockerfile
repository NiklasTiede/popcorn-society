FROM ghcr.io/astral-sh/uv:0.11.14 AS uv

FROM python:3.14.10-slim AS builder

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

WORKDIR /app

COPY --from=uv /uv /uvx /bin/
COPY pyproject.toml uv.lock README.md ./
COPY src ./src

RUN uv sync --frozen --no-dev --no-editable --no-cache

FROM python:3.14.10-slim AS runtime

ARG APP_VERSION=0.1.0

ENV PATH="/app/.venv/bin:$PATH" \
    POPCORN_SOCIETY_AGENT_VERSION="${APP_VERSION}" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=builder --chown=10001:10001 /app/.venv /app/.venv

USER 10001:10001

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/healthz', timeout=2).read()"]

CMD ["uvicorn", "popcorn_society_agent.bootstrap:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090", "--workers", "1", "--limit-concurrency", "16", "--backlog", "64", "--timeout-keep-alive", "5", "--timeout-graceful-shutdown", "40", "--no-access-log", "--no-server-header", "--no-proxy-headers"]
