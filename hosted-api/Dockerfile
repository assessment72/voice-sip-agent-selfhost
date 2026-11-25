FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 DATABASE_PATH=/data/calls.sqlite3
WORKDIR /srv/hosted-api
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py mongodb_store.py mongodb_calls.py sms_provider.py evolution_provider.py ./
COPY migrations ./migrations
COPY admin ./admin
RUN useradd --system --uid 10001 app && mkdir -p /data && chown -R app:app /srv/hosted-api /data
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=3)"
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080", "--proxy-headers", "--forwarded-allow-ips=127.0.0.1"]
