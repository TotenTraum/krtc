#include "krtc_h264_bitstream.h"
#include <cassert>
#include <vector>

int main() {
    std::vector<uint8_t> out;
    const std::vector<uint8_t> annex{0, 0, 0, 1, 0x67, 66, 0xe0, 31, 0, 0, 1, 0x68, 1, 0, 0, 1, 0x65, 2};
    assert(krtc::H264AnnexB(annex.data(), annex.size(), out) && out == annex);
    assert(krtc::H264HasNal(out, 5) && krtc::H264HasNal(out, 7) && krtc::H264HasNal(out, 8));
    assert(krtc::H264HasConstrainedBaselineSps(out));
    const std::vector<uint8_t> avcc{0, 0, 0, 4, 0x67, 66, 0xc0, 31, 0, 0, 0, 2, 0x68, 1, 0, 0, 0, 2, 0x65, 2};
    assert(krtc::H264AnnexB(avcc.data(), avcc.size(), out));
    assert(krtc::H264HasNal(out, 5) && krtc::H264HasConstrainedBaselineSps(out));
    out[5] = 100; // A high-profile driver output must not be sent as negotiated constrained baseline.
    assert(!krtc::H264HasConstrainedBaselineSps(out));
    for (const auto& bad : std::vector<std::vector<uint8_t>>{
            {}, {0, 0}, {0, 0, 0, 0}, {0xff, 0xff, 0xff, 0xff}, {0, 0, 0, 3, 0x65, 1},
            {0, 0, 0, 2, 0x65, 1, 0}}) {
        assert(!krtc::H264AnnexB(bad.data(), bad.size(), out));
    }
    assert(!krtc::H264HasNal({}, 5));
    assert(!krtc::H264HasConstrainedBaselineSps({0, 0, 1, 0x67}));
}
