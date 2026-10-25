FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential ca-certificates cmake git ninja-build \
    && rm -rf /var/lib/apt/lists/*

COPY build.sh vector_generator.c /tools/vector-generator/

ENV WORK_DIR=/work
ENV OUT_DIR=/out

CMD ["bash", "/tools/vector-generator/build.sh"]
