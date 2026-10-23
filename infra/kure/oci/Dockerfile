FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/var/cache/huggingface \
    HF_HUB_DISABLE_TELEMETRY=1 \
    SENTENCE_TRANSFORMERS_HOME=/var/cache/huggingface \
    OMP_NUM_THREADS=2 \
    MKL_NUM_THREADS=2

WORKDIR /app
COPY requirements.txt serve.py ./
RUN pip install --no-cache-dir --no-deps --index-url https://download.pytorch.org/whl/cpu "torch==2.14.1" \
    && pip install --no-cache-dir -r requirements.txt \
    && useradd --system --uid 10001 --create-home app \
    && mkdir -p /var/cache/huggingface \
    && chown -R app:app /app /var/cache/huggingface

USER app
EXPOSE 18081
CMD ["python", "/app/serve.py", "--device", "cpu", "--threads", "2", "--host", "0.0.0.0", "--port", "18081", "--state", "/var/cache/huggingface/state"]
