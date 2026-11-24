FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /opt/voicx-relay
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY server.py .
RUN useradd --system --uid 10001 relay && chown -R relay:relay /opt/voicx-relay
USER relay
EXPOSE 8765
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8765"]
