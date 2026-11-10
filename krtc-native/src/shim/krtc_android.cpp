// Android libc++ compatibility for the krtc shim.
//
// LiveKit's libwebrtc for Android is compiled against Chromium's newer NDK libc++, whose std::hash calls the
// out-of-line std::__ndk1::__hash_memory exported by the newer libc++ runtime. The NDK we link with (r29)
// does not export it yet, so provide it with the same algorithm the newer headers use when the runtime
// lacks it (__murmur2_or_cityhash). Only consistency within the process matters for hashing.
#include <cstddef>
#include <functional>

namespace std {
inline namespace __ndk1 {
[[gnu::pure]] size_t __hash_memory(const void *ptr, size_t size) noexcept { return __murmur2_or_cityhash<size_t>()(ptr, size); }
}  // namespace __ndk1
}  // namespace std
