#pragma once

#include "krtc_screen_source.h"
#include <vector>
#include <unordered_set>
#import <ScreenCaptureKit/ScreenCaptureKit.h>

namespace krtc {
// SCK metadata is the source of truth for both the picker and capture IDs. Kept separate for permission-free
// regression tests with SCK-shaped objects (untitled/custom/floating windows and multiple displays).
API_AVAILABLE(macos(13.0)) inline std::vector<ScreenSource> SckSources(
    SCShareableContent *content, pid_t ownPid, const std::unordered_set<pid_t>& applicationPids) {
    std::vector<ScreenSource> out;
    int index = 0;
    for (SCDisplay *display in content.displays) {
        out.push_back({static_cast<int64_t>(display.displayID), false, "Display " + std::to_string(++index)});
    }
    for (SCWindow *window in content.windows) {
        SCRunningApplication *app = window.owningApplication;
        // Visibility, opacity, layer and a missing title do not prove that a user window cannot be captured.
        // Keep application windows on every Space; the picker checks actual frame delivery before sharing.
        if (!app || app.processID == ownPid || !applicationPids.contains(app.processID) ||
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
