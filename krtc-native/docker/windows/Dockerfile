# Windows x64 cross toolchain for the krtc shim: clang-cl 21 + lld-link against the MSVC ABI, with the MSVC
# CRT/STL and the Windows SDK fetched by xwin (https://github.com/Jake-Shadle/xwin). Building this image accepts
# the Microsoft Visual Studio license for those files (xwin --accept-license).
FROM debian:bookworm
ARG XWIN_VERSION=0.10.0
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
 && curl -fsSL https://apt.llvm.org/llvm-snapshot.gpg.key | gpg --dearmor -o /usr/share/keyrings/llvm.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/llvm.gpg] http://apt.llvm.org/bookworm/ llvm-toolchain-bookworm-21 main" > /etc/apt/sources.list.d/llvm.list \
 && apt-get update && apt-get install -y --no-install-recommends clang-21 llvm-21 lld-21 \
 && ln -s /usr/bin/clang-21 /usr/local/bin/clang-cl \
 && for t in lld-link llvm-lib llvm-nm llvm-readobj llvm-dlltool llvm-ar; do ln -s /usr/bin/$t-21 /usr/local/bin/$t; done \
 && rm -rf /var/lib/apt/lists/*
# xwin names the library directories x86_64; clang-cl and lld-link /winsysroot look for x64.
RUN set -e; case "$(uname -m)" in aarch64) a=aarch64; sum=6d56d28537a86f37aa3d041318898f25ee3100c6b6ec332ad873c28faf37be23 ;; \
      x86_64) a=x86_64; sum=d870eb4b2f390878af6da1ccd3cf321d22fcb72720984853b4be732ae597fc88 ;; esac; \
    f=xwin-$XWIN_VERSION-$a-unknown-linux-musl; \
    curl -fsSLo /tmp/x.tgz https://github.com/Jake-Shadle/xwin/releases/download/$XWIN_VERSION/$f.tar.gz; \
    echo "$sum  /tmp/x.tgz" | sha256sum -c -; tar -xzf /tmp/x.tgz -C /tmp; mv /tmp/$f/xwin /usr/local/bin/; rm -rf /tmp/x.tgz /tmp/$f; \
    xwin --accept-license --arch x86_64 splat --use-winsysroot-style --output /xwin; rm -rf /root/.xwin-cache .xwin-cache; \
    for d in /xwin/VC/Tools/MSVC/*/lib /xwin/Windows\ Kits/10/Lib/*/ucrt /xwin/Windows\ Kits/10/Lib/*/um; do ln -s x86_64 "$d/x64"; done
