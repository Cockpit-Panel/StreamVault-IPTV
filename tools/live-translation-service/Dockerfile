FROM python:3.12-slim

# CTranslate2 (faster-whisper) needs OpenMP at runtime.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY service.py .

# Model downloads land here; docker-compose mounts a volume at this path so the
# ~3 GB large-v3 download survives container rebuilds.
ENV HF_HOME=/data/hf-cache

EXPOSE 8765

CMD ["uvicorn", "service:app", "--host", "0.0.0.0", "--port", "8765"]
