#pragma once

#include <cstdint>
#include <string>

namespace krtc {
/** Something that can be captured as a screen share: a display (window = false) or a window. */
struct ScreenSource {
    int64_t id;
    bool window;
    std::string title;
};
}  // namespace krtc
