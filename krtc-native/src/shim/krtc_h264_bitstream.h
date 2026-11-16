#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace krtc {
// MF encoders normally return Annex B. Accept AVCC samples too, then give WebRTC Annex B only.
inline bool H264AnnexB(const uint8_t* data, size_t size, std::vector<uint8_t>& out) {
    out.clear();
    if (size >= 4 && data[0] == 0 && data[1] == 0 &&
        (data[2] == 1 || (data[2] == 0 && data[3] == 1))) {
        out.assign(data, data + size);
        return true;
    }
    size_t pos = 0;
    while (pos < size) {
        if (size - pos < 4) return false;
        const uint32_t n = (uint32_t(data[pos]) << 24) | (uint32_t(data[pos + 1]) << 16) |
                           (uint32_t(data[pos + 2]) << 8) | data[pos + 3];
        pos += 4;
        if (n == 0 || n > size - pos) return false;
        out.insert(out.end(), {0, 0, 0, 1});
        out.insert(out.end(), data + pos, data + pos + n);
        pos += n;
    }
    return !out.empty();
}

inline bool H264HasNal(const std::vector<uint8_t>& data, uint8_t type) {
    for (size_t i = 0; i + 3 < data.size(); ++i) {
        if (data[i] != 0 || data[i + 1] != 0) continue;
        size_t nal = i + 2;
        if (data[nal] == 0) ++nal;
        if (nal + 1 < data.size() && data[nal] == 1 && (data[nal + 1] & 31) == type) return true;
    }
    return false;
}

inline bool H264HasConstrainedBaselineSps(const std::vector<uint8_t>& data) {
    for (size_t i = 0; i + 3 < data.size(); ++i) {
        if (data[i] != 0 || data[i + 1] != 0) continue;
        size_t nal = i + 2;
        if (data[nal] == 0) ++nal;
        if (nal + 3 < data.size() && data[nal] == 1 && (data[nal + 1] & 31) == 7)
            return data[nal + 2] == 66 && (data[nal + 3] & 0x40) != 0;
    }
    return false;
}
}
