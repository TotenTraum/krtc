# krtc-sfu: the native SFU server (Kotlin/Native, Linux x64 and arm64) in a small image.
# The binary needs only glibc (OpenSSL is linked in), so no build tools or JVM go into the image.
#
# Build context: a directory with linux-amd64/krtc-sfu and linux-arm64/krtc-sfu (deploy/docker/build-image.sh lays
# it out from the Gradle outputs). Configuration is by environment (KRTC_*) or arguments, see krtc-sfu-server.
# libcrypt.so.1 (a separate package since Debian 12, not part of glibc), for the target's architecture.
FROM debian:bookworm-slim AS libs
RUN mkdir /out && cp -L "/usr/lib/$(uname -m)-linux-gnu/libcrypt.so.1" /out/

# distroless/cc: glibc and libgcc, no shell or package manager (smaller, less to patch); runs as user 65532.
FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=libs /out/libcrypt.so.1 /usr/local/lib/
ENV LD_LIBRARY_PATH=/usr/local/lib

ARG TARGETARCH
LABEL org.opencontainers.image.title="krtc-sfu" \
      org.opencontainers.image.description="Selective forwarding unit for WebRTC calls (krtc)" \
      org.opencontainers.image.source="https://github.com/TotenTraum/krtc"

COPY --chmod=0755 linux-${TARGETARCH}/krtc-sfu /usr/local/bin/krtc-sfu

USER 65532
# HTTP: signaling (WebSocket /ws), the test page, /metrics, /stats, client logs (/logs).
EXPOSE 8080/tcp
# Media: one UDP port for every participant, and the same port over TCP (ICE-TCP) for networks that block UDP.
EXPOSE 50000/udp 50000/tcp
ENV KRTC_HTTP_PORT=8080 \
    KRTC_UDP_PORT=50000
ENTRYPOINT ["/usr/local/bin/krtc-sfu"]
