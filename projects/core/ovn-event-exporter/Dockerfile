FROM python:3

ADD requirements.txt requirements.txt
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

ENV PYTHONUNBUFFERED=1

USER 1001

ADD ovn-event-exporter.py /usr/sbin/ovn-event-exporter.py

CMD ["/usr/sbin/ovn-event-exporter.py"]
ENTRYPOINT ["python3"]
