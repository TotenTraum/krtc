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
@property CGRect frame;
@property(copy) NSString *title;
@property(strong) AppMetadata *owningApplication;
@end
@implementation WindowMetadata
@end

@interface DisplayMetadata : NSObject
@property CGDirectDisplayID displayID;
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
    window.frame = CGRectMake(0, 0, 1280, 720);
    return window;
}

int main() {
    @autoreleasepool {
        auto *a = [DisplayMetadata new]; a.displayID = 42;
        auto *b = [DisplayMetadata new]; b.displayID = 107;
        auto *vivaldi = Window(501, @"Vivaldi", @"", 10, 0);
        auto *moonlight = Window(502, @"Moonlight", nil, 11, 3); // always-on-top game window, no title
        auto *browser = Window(503, @"Vivaldi", @"Example page", 10, 0);
        auto *own = Window(504, @"krtc meet", @"Settings", 99, 0);
        auto *empty = Window(505, @"Hidden helper", nil, 12, 0); empty.frame = CGRectZero;
        auto *content = [ContentMetadata new];
        content.displays = @[a, b];
        content.windows = @[vivaldi, moonlight, browser, own, empty];
        const auto sources = krtc::SckSources((SCShareableContent *)content, 99);
        CHECK(sources.size() == 5);
        CHECK(!sources[0].window && sources[0].id == 42); // real SCK display IDs, not picker/index IDs
        CHECK(!sources[1].window && sources[1].id == 107);
        CHECK(sources[2].window && sources[2].id == 501 && sources[2].title == "Vivaldi");
        CHECK(sources[3].window && sources[3].id == 502 && sources[3].title == "Moonlight");
        CHECK(sources[4].title == "Vivaldi — Example page");
        std::puts("macOS screen sources: all checks passed");
    }
}
