#pragma once

#include "krtc_screen_source.h"
#include <vector>
#include <unordered_set>
#include <unordered_map>
#import <ScreenCaptureKit/ScreenCaptureKit.h>

namespace krtc {
// SCK metadata is the source of truth for both the picker and capture IDs. Kept separate for permission-free
// regression tests with SCK-shaped objects (untitled/custom/floating windows and multiple displays).
API_AVAILABLE(macos(13.0)) inline std::vector<ScreenSource> SckSources(
    SCShareableContent *content, pid_t ownPid, const std::unordered_set<pid_t>& applicationPids,
    NSArray<NSDictionary *> *windowInfo = nil) {
    std::vector<ScreenSource> out;
    std::unordered_set<CGWindowID> transparent;
    for (NSDictionary *info in windowInfo) {
        NSNumber *alpha = info[(__bridge NSString *)kCGWindowAlpha];
        NSNumber *number = info[(__bridge NSString *)kCGWindowNumber];
        if (number && alpha && alpha.doubleValue == 0) transparent.insert(number.unsignedIntValue);
    }
    int index = 0;
    for (SCDisplay *display in content.displays) {
        out.push_back({static_cast<int64_t>(display.displayID), false, "Display " + std::to_string(++index)});
    }
    NSMutableArray<SCWindow *> *candidates = [NSMutableArray new];
    std::unordered_set<pid_t> namedApps;
    std::unordered_map<pid_t, std::pair<CGWindowID, double>> untitled;
    for (SCWindow *window in content.windows) {
        SCRunningApplication *app = window.owningApplication;
        // Chromium's source picker also uses a 40-point minimum to remove capture indicators and tiny helpers.
        // Retain ordinary floating/modal windows here; Moonlight and similar apps use those levels.
        if (!app || app.processID == ownPid || !applicationPids.contains(app.processID) ||
            transparent.contains(window.windowID) ||
            window.windowLayer < CGWindowLevelForKey(kCGNormalWindowLevelKey) ||
            window.windowLayer > CGWindowLevelForKey(kCGModalPanelWindowLevelKey) ||
            window.frame.size.width < 40 || window.frame.size.height < 40) continue;
        [candidates addObject:window];
        if (window.title.length) namedApps.insert(app.processID);
        else {
            const double area = window.frame.size.width * window.frame.size.height;
            auto &best = untitled[app.processID];
            if (area > best.second) best = {window.windowID, area};
        }
    }
    // As in Chromium/WebRTC, unnamed surfaces are a fallback for apps without titled windows, rather than
    // another entry for every backing surface. Preserve fullscreen unnamed windows, including other Spaces.
    // https://chromium.googlesource.com/chromium/src/+/main/chrome/browser/media/webrtc/thumbnail_capturer_mac.mm
    for (bool titled : {true, false}) {
        for (SCWindow *window in candidates) {
            if ((window.title.length > 0) != titled) continue;
            SCRunningApplication *app = window.owningApplication;
            bool fullscreen = false;
            for (SCDisplay *display in content.displays) {
                if (CGRectEqualToRect(window.frame, display.frame)) { fullscreen = true; break; }
            }
            if (!titled && !fullscreen &&
                (namedApps.contains(app.processID) || untitled[app.processID].first != window.windowID)) continue;
            NSString *appName = app.applicationName.length ? app.applicationName : app.bundleIdentifier;
            NSString *title = window.title;
            NSString *label = title.length ? title : appName;
            if (appName.length && title.length && ![appName isEqualToString:title])
                label = [NSString stringWithFormat:@"%@ — %@", appName, title];
            std::string name = label.UTF8String ?: "";
            if (name.empty()) name = "Window " + std::to_string(window.windowID);
            out.push_back({static_cast<int64_t>(window.windowID), true, std::move(name)});
        }
    }
    return out;
}
}  // namespace krtc
