#pragma once

#include "krtc_screen_source.h"
#include <vector>
#import <ScreenCaptureKit/ScreenCaptureKit.h>

namespace krtc {
// SCK metadata is the source of truth for both the picker and capture IDs. Kept separate for permission-free
// regression tests with SCK-shaped objects (untitled/custom/floating windows and multiple displays).
API_AVAILABLE(macos(13.0)) inline std::vector<ScreenSource> SckSources(SCShareableContent *content, pid_t ownPid) {
    std::vector<ScreenSource> out;
    int index = 0;
    for (SCDisplay *display in content.displays) {
        out.push_back({static_cast<int64_t>(display.displayID), false, "Display " + std::to_string(++index)});
    }
    for (SCWindow *window in content.windows) {
        SCRunningApplication *app = window.owningApplication;
        // SCK decides which surfaces are shareable. Floating/always-on-top game windows need not be layer 0.
        if (!app || app.processID == ownPid ||
            window.frame.size.width <= 0 || window.frame.size.height <= 0) continue;
        NSString *appName = app.applicationName.length ? app.applicationName : app.bundleIdentifier;
        NSString *title = window.title;
        NSString *label = title.length ? title : appName;
        if (appName.length && title.length && ![appName isEqualToString:title])
            label = [NSString stringWithFormat:@"%@ — %@", appName, title];
        std::string name = label.UTF8String ?: "";
        if (name.empty()) name = "Window " + std::to_string(window.windowID);
        out.push_back({static_cast<int64_t>(window.windowID), true, std::move(name)});
    }
    return out;
}
}  // namespace krtc
