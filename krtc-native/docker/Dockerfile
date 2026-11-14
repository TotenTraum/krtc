# Linux toolchain for the krtc shim: clang 21 (libwebrtc's bundled libc++ needs a recent clang).
# Also runs the linuxArm64/linuxX64 demo, which links only glibc, so the runtime packages are minimal.
# libx11-dev: headers only, for the X11 screen capture (libX11 itself is loaded at run time).
FROM debian:bookworm
# apt.llvm.org now and then serves a half-synced index (hash mismatch, 404): retry instead of failing the build.
RUN echo 'Acquire::Retries "5";' > /etc/apt/apt.conf.d/80-retries
# The apt.llvm.org signing key (fingerprint 6084 F3CF 814B 57C1 CF12 EFD5 15CF 4D18 AF4F 7421), kept here: fetching it
# during the build failed now and then, and an empty key made gpg fail the whole step.
COPY llvm-snapshot.gpg.key /tmp/llvm-snapshot.gpg.key
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gnupg binutils \
 && gpg --dearmor -o /usr/share/keyrings/llvm.gpg /tmp/llvm-snapshot.gpg.key && rm /tmp/llvm-snapshot.gpg.key \
 && echo "deb [signed-by=/usr/share/keyrings/llvm.gpg] https://apt.llvm.org/bookworm/ llvm-toolchain-bookworm-21 main" > /etc/apt/sources.list.d/llvm.list \
 && apt-get update && apt-get install -y --no-install-recommends clang-21 llvm-21 lld-21 libclang-rt-21-dev libc6-dev libx11-dev \
 && ln -s /usr/bin/clang++-21 /usr/local/bin/clang++ && ln -s /usr/bin/llvm-ar-21 /usr/local/bin/ar \
 && ln -s /usr/bin/ld.lld-21 /usr/local/bin/ld.lld && ln -s /usr/bin/llvm-objcopy-21 /usr/local/bin/objcopy && ln -s /usr/bin/llvm-nm-21 /usr/local/bin/nm \
 && rm -rf /var/lib/apt/lists/*
