# ============================================================
# DocMind doc-service-py — FastAPI document parser
# Installs ONLY the base requirements.txt (no heavy ML libs). The engines
# lazy-import their real backends and fall back to stubs, so the image boots
# and serves without any model downloads. libmagic1 is installed so the
# optional python-magic cross-check works; our byte sniffer works regardless.
# ============================================================
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# System deps: libmagic1 for python-magic (optional MIME cross-check).
RUN apt-get update \
    && apt-get install -y --no-install-recommends libmagic1 \
    && rm -rf /var/lib/apt/lists/*

# Install base Python deps first for better layer caching.
COPY requirements.txt ./
RUN python -m pip install --upgrade pip \
    && python -m pip install -r requirements.txt

# App code.
COPY app ./app

EXPOSE 8091

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8091"]
