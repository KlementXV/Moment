# syntax=docker/dockerfile:1.7
# Build from repository root: docker build -f keyserver/Dockerfile -t moment-keyserver:0.1.0 .
FROM rust:1.98.1-slim-bookworm AS build
WORKDIR /build
RUN apt-get update && apt-get install -y --no-install-recommends pkg-config ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY keyserver/Cargo.toml keyserver/Cargo.lock ./
COPY keyserver/build.rs ./
COPY keyserver/src ./src
COPY keyserver/migrations ./migrations
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/usr/local/cargo/git \
    --mount=type=cache,target=/build/target \
    cargo build --locked --release && install -m 0755 target/release/moment-keyserver /moment-keyserver

FROM debian:bookworm-slim AS onnx
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
# Digests from the official Microsoft release API, not a checksum fetched during build.
RUN set -eu; \
    case "$TARGETARCH" in \
      amd64) ort_arch=x64; ort_sha=1fa4dcaef22f6f7d5cd81b28c2800414350c10116f5fdd46a2160082551c5f9b ;; \
      arm64) ort_arch=aarch64; ort_sha=7c63c73560ed76b1fac6cff8204ffe34fe180e70d6582b5332ec094810241e5c ;; \
      *) echo 'Supported platforms: linux/amd64 and linux/arm64' >&2; exit 1 ;; \
    esac; \
    curl --fail --location --retry 3 \
      "https://github.com/microsoft/onnxruntime/releases/download/v1.23.2/onnxruntime-linux-${ort_arch}-1.23.2.tgz" \
      --output /tmp/ort.tgz; \
    echo "${ort_sha}  /tmp/ort.tgz" | sha256sum --check --strict; \
    mkdir -p /opt/onnxruntime; \
    tar -xzf /tmp/ort.tgz --strip-components=1 -C /opt/onnxruntime; \
    rm /tmp/ort.tgz

FROM debian:bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libstdc++6 libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 moment \
    && useradd --uid 10001 --gid moment --no-create-home --shell /usr/sbin/nologin moment
WORKDIR /app
COPY --from=build /moment-keyserver /usr/local/bin/moment-keyserver
COPY --from=onnx /opt/onnxruntime/lib /opt/onnxruntime/lib
COPY --from=onnx /opt/onnxruntime/LICENSE /opt/onnxruntime/LICENSE
COPY --from=onnx /opt/onnxruntime/ThirdPartyNotices.txt /opt/onnxruntime/ThirdPartyNotices.txt
COPY app/app/src/main/assets/moderation/ /app/assets/
ENV BIND_ADDR=0.0.0.0:8080 \
    ORT_DYLIB_PATH=/opt/onnxruntime/lib/libonnxruntime.so \
    LD_LIBRARY_PATH=/opt/onnxruntime/lib \
    MODERATION_MODEL_PATH=/app/assets/marqo-nsfw.onnx \
    MODERATION_POLICY_PATH=/app/assets/policy.json
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/moment-keyserver"]
