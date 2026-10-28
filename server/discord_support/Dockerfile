# Build from server/: docker build -f discord_support/Dockerfile .
FROM python:3.13-slim
WORKDIR /app
COPY discord_support/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home support && mkdir /data && chown support /data
# Explicit runtime files keep test environments, private evidence, migrations,
# operator policies and the Codex runner out of the public-facing bot image.
COPY --chown=support discord_support/__init__.py discord_support/service.py discord_support/conversation.py discord_support/gateway.py discord_support/builds.py discord_support/repairs.py discord_support/tags.py discord_support/commands.py discord_support/knowledge.json discord_support/playbooks.json /app/discord_support/
USER support
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8012
CMD ["uvicorn", "discord_support.service:create_app", "--factory", "--host", "0.0.0.0", "--port", "8012", "--workers", "1", "--no-access-log"]
