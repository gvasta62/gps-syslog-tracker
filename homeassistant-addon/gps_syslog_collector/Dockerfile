ARG BUILD_FROM
FROM ${BUILD_FROM}

# Il collector usa solo la libreria standard di Python.
RUN apk add --no-cache python3

COPY collector.py /app/collector.py
COPY run.sh /run.sh
RUN chmod a+x /run.sh

CMD [ "/run.sh" ]
