FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    SMS_PORT=8765
WORKDIR /app

COPY desktop/requirements.txt desktop/requirements.txt
RUN pip install --no-cache-dir -r desktop/requirements.txt \
    && groupadd --gid 10001 sms \
    && useradd --uid 10001 --gid sms --no-create-home sms \
    && mkdir /app/.data \
    && chown sms:sms /app/.data \
    && chmod 700 /app/.data

COPY desktop/ desktop/
USER sms
EXPOSE 8765
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:' + os.environ['SMS_PORT'] + '/', timeout=3).close()"
ENTRYPOINT ["python", "desktop/server.py"]

