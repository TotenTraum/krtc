#include "krtc_sck_sources.h"
#include <cstdio>
#include <cstdlib>

#define CHECK(condition) do { if (!(condition)) { std::fprintf(stderr, "failed at line %d: %s\n", __LINE__, #condition); std::exit(1); } } while (0)

// SCShareableContent/SCWindow properties are readonly. These objects exercise the actual conversion with the
// same Objective-C selectors, without opening apps, inspecting the user's windows or requiring Screen Recording.
@interface AppMetadata : NSObject
@property pid_t processID;
@property(copy) NSString *applicationName;
@property(copy) NSString *bundleIdentifier;
@end
@implementation AppMetadata
@end

@interface WindowMetadata : NSObject
@property CGWindowID windowID;
@property NSInteger windowLayer;
@property(getter=isOnScreen) BOOL onScreen;
@property CGRect frame;
@property(copy) NSString *title;
@property(strong) AppMetadata *owningApplication;
@end
@implementation WindowMetadata
@end

@interface DisplayMetadata : NSObject
@property CGDirectDisplayID displayID;
@property CGRect frame;
@end
@implementation DisplayMetadata
@end

@interface ContentMetadata : NSObject
@property(copy) NSArray *displays;
@property(copy) NSArray *windows;
@end
@implementation ContentMetadata
@end

static WindowMetadata *Window(CGWindowID id, NSString *name, NSString *title, pid_t pid, NSInteger layer) {
    auto *app = [AppMetadata new];
    app.processID = pid;
    app.applicationName = name;
    auto *window = [WindowMetadata new];
    window.windowID = id;
    window.owningApplication = app;
    window.title = title;
    window.windowLayer = layer;
    window.onScreen = YES;
    window.frame = CGRectMake(0, 0, 1280, 720);
    return window;
}

int main() {
    @autoreleasepool {
        auto *a = [DisplayMetadata new]; a.displayID = 42; a.frame = CGRectMake(0, 0, 1920, 1080);
        auto *b = [DisplayMetadata new]; b.displayID = 107; b.frame = CGRectMake(1920, 0, 1920, 1080);
        auto *vivaldi = Window(501, @"Vivaldi", @"", 10, 0);
        auto *moonlight = Window(502, @"Moonlight", nil, 11, 3); // always-on-top game window, no title
        auto *browser = Window(503, @"Vivaldi", @"Example page", 10, 0);
        auto *own = Window(504, @"krtc meet", @"Settings", 99, 0);
        auto *empty = Window(505, @"Vivaldi", nil, 10, 0); empty.frame = CGRectZero;
        auto *minimized = Window(506, @"Vivaldi", @"Minimized", 10, 0); minimized.onScreen = NO;
        auto *small = Window(507, @"Vivaldi", @"Small window", 10, 0); small.frame = CGRectMake(0, 0, 400, 24);
        auto *menu = Window(508, @"Vivaldi", @"Menu", 10, CGWindowLevelForKey(kCGPopUpMenuWindowLevelKey));
        auto *overlay = Window(509, @"System overlay", @"Overlay", 12, CGWindowLevelForKey(kCGStatusWindowLevelKey));
        auto *transparent = Window(510, @"Vivaldi", nil, 10, 0);
        auto *desktop = Window(511, @"Desktop", @"Background", 12, -1);
        auto *stale = Window(512, @"Closed app", @"Stale surface", 13, 0);
        auto *modal = Window(513, @"Moonlight", @"Settings", 11, CGWindowLevelForKey(kCGModalPanelWindowLevelKey));
        auto *noOwner = Window(514, @"Unknown", nil, 12, 0); noOwner.owningApplication = nil;
        auto *otherSpace = Window(515, @"Moonlight", @"Another Space", 11, 3); otherSpace.onScreen = NO;
        auto *elevated = Window(516, @"Moonlight", @"On top", 11, CGWindowLevelForKey(kCGFloatingWindowLevelKey));
        auto *strip = Window(517, @"Moonlight", nil, 11, 0); strip.frame = CGRectMake(0, 0, 1280, 2);
        auto *fullscreen = Window(518, @"Vivaldi", nil, 10, 0); fullscreen.frame = a.frame; fullscreen.onScreen = NO;
        auto *custom = Window(519, @"Untitled app", nil, 14, 3);
        auto *duplicate = Window(520, @"Untitled app", nil, 14, 0); duplicate.frame = CGRectMake(0, 0, 200, 200);
        auto *otherSpaceUntitled = Window(521, @"Other Space app", nil, 15, 0); otherSpaceUntitled.onScreen = NO;
        auto *content = [ContentMetadata new];
        content.displays = @[a, b];
        content.windows = @[vivaldi, moonlight, browser, own, empty, minimized, small, menu, overlay,
                            transparent, desktop, stale, modal, noOwner, otherSpace, elevated, strip, fullscreen, custom, duplicate, otherSpaceUntitled];
        const std::unordered_set<pid_t> applications{10, 11, 14, 15, 99}; // system agents / terminated apps are not GUI apps
        const auto sources = krtc::SckSources((SCShareableContent *)content, 99, applications, @[
            @{(__bridge NSString *)kCGWindowNumber: @510, (__bridge NSString *)kCGWindowAlpha: @0},
            @{(__bridge NSString *)kCGWindowNumber: @516, (__bridge NSString *)kCGWindowAlpha: @0.5},
        ]);
        CHECK(sources.size() == 10);
        CHECK(!sources[0].window && sources[0].id == 42); // real SCK display IDs, not picker/index IDs
        CHECK(!sources[1].window && sources[1].id == 107);
        CHECK(sources[2].title == "Vivaldi — Example page"); // titled windows precede auxiliary candidates
        const std::vector<int64_t> expected{503, 506, 513, 515, 516, 518, 519, 521};
        for (size_t i = 0; i < expected.size(); ++i) CHECK(sources[i + 2].id == expected[i]);
        CHECK(sources[7].title == "Vivaldi" && sources[8].title == "Untitled app"); // fullscreen / sole untitled fallback
        // Other Spaces (absent from Quartz), fullscreen, floating and standalone untitled app windows remain.
        // Generic Moonlight/Vivaldi backing surfaces disappear when their titled windows are present.
        const auto noApplications = krtc::SckSources((SCShareableContent *)content, 99, {});
        CHECK(noApplications.size() == 2); // displays remain available without GUI apps
        std::puts("macOS screen sources: all checks passed");
    }
}
