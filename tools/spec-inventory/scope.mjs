// Which WebIDL files from @webref/idl make up krtc's scope, and how.
//
// tier:
//   core      — required for "full coverage" (1.0 gate)
//   extended  — in scope, may ship after 1.0
//   excluded  — tracked for visibility, not implemented
//
// Spec titles/URLs are kept here because @webref/idl ships only the IDL text.

// Package of the generated krtc-idl skeleton. Placeholder until the Maven group id is chosen.
export const KOTLIN_PACKAGE = 'krtc.idl';

export const PLATFORMS =['android', 'ios', 'macos', 'jvm', 'js', 'wasmJs', 'linux', 'mingw'];

// Allowed values for a platform status in spec-coverage.yaml.
export const STATUSES = ['planned', 'partial', 'full', 'na'];

export const SPECS = {
  // --- core -------------------------------------------------------------
  'webrtc': {
    tier: 'core',
    title: 'WebRTC: Real-Time Communication in Browsers',
    url: 'https://w3c.github.io/webrtc-pc/',
  },
  'webrtc-stats': {
    tier: 'core',
    title: 'Identifiers for WebRTC\'s Statistics API',
    url: 'https://w3c.github.io/webrtc-stats/',
  },
  'webrtc-svc': {
    tier: 'core',
    title: 'Scalable Video Coding (SVC) Extension for WebRTC',
    url: 'https://w3c.github.io/webrtc-svc/',
  },
  'webrtc-encoded-transform': {
    tier: 'core',
    title: 'WebRTC Encoded Transform',
    url: 'https://w3c.github.io/webrtc-encoded-transform/',
  },
  'webrtc-priority': {
    tier: 'core',
    title: 'WebRTC Priority Control API',
    url: 'https://w3c.github.io/webrtc-priority/',
  },
  'webrtc-ice': {
    tier: 'core',
    title: 'IceTransport Extensions for WebRTC',
    url: 'https://w3c.github.io/webrtc-ice/',
  },
  'mediacapture-streams': {
    tier: 'core',
    title: 'Media Capture and Streams',
    url: 'https://w3c.github.io/mediacapture-main/',
  },
  'mediacapture-extensions': {
    tier: 'core',
    title: 'Media Capture and Streams Extensions',
    url: 'https://w3c.github.io/mediacapture-extensions/',
  },
  'screen-capture': {
    tier: 'core',
    title: 'Screen Capture',
    url: 'https://w3c.github.io/mediacapture-screen-share/',
  },
  'audio-output': {
    tier: 'core',
    title: 'Audio Output Devices API',
    url: 'https://w3c.github.io/mediacapture-output/',
  },
  'mst-content-hint': {
    tier: 'core',
    title: 'MediaStreamTrack Content Hints',
    url: 'https://w3c.github.io/mst-content-hint/',
  },

  // --- extended ---------------------------------------------------------
  'mediacapture-transform': {
    tier: 'extended',
    title: 'MediaStreamTrack Insertable Media Processing using Streams',
    url: 'https://w3c.github.io/mediacapture-transform/',
  },
  'mediacapture-region': {
    tier: 'extended',
    title: 'Region Capture',
    url: 'https://w3c.github.io/mediacapture-region/',
  },
  'mediacapture-viewport': {
    tier: 'extended',
    title: 'Viewport Capture',
    url: 'https://w3c.github.io/mediacapture-viewport/',
  },
  'mediacapture-surface-control': {
    tier: 'extended',
    title: 'Captured Surface Control',
    url: 'https://w3c.github.io/mediacapture-surface-control/',
  },
  'mediacapture-handle-actions': {
    tier: 'extended',
    title: 'Capture Handle Actions',
    url: 'https://w3c.github.io/mediacapture-handle/actions/',
  },
  'capture-handle-identity': {
    tier: 'extended',
    title: 'Capture Handle - Bootstrapping Collaboration when Screensharing',
    url: 'https://w3c.github.io/mediacapture-handle/identity/',
  },
  'prefer-current-tab': {
    tier: 'extended',
    title: 'preferCurrentTab',
    url: 'https://wicg.github.io/prefer-current-tab/',
  },
  'image-capture': {
    tier: 'extended',
    title: 'MediaStream Image Capture',
    url: 'https://w3c.github.io/mediacapture-image/',
  },
  'mediastream-recording': {
    tier: 'extended',
    title: 'MediaStream Recording',
    url: 'https://w3c.github.io/mediacapture-record/',
  },
  'webrtc-diagnostic-logging': {
    tier: 'extended',
    title: 'WebRTC Diagnostic Logging',
    url: 'https://w3c.github.io/webrtc-diagnostic-logging/',
  },

  // --- excluded ---------------------------------------------------------
  'webrtc-identity': {
    tier: 'excluded',
    title: 'Identity for WebRTC 1.0',
    url: 'https://w3c.github.io/webrtc-identity/',
    reason: 'Not implemented by major engines; IdP proxy model has no native analogue.',
  },
  'mediacapture-fromelement': {
    tier: 'excluded',
    title: 'Media Capture from DOM Elements',
    url: 'https://w3c.github.io/mediacapture-fromelement/',
    reason: 'Bound to HTMLMediaElement/HTMLCanvasElement; browser-only, available via the js target directly.',
  },
  'mediacapture-automation': {
    tier: 'excluded',
    title: 'Media Capture Automation',
    url: 'https://w3c.github.io/mediacapture-automation/',
    reason: 'WebDriver test automation API, not a runtime API.',
  },
};

// web-platform-tests directories to map onto the inventory: WPT dir → krtc spec shortname
// (null = spans several specs). The pinned WPT revision lives in wpt.lock.
export const WPT_REPO = 'https://github.com/web-platform-tests/wpt.git';
export const WPT_DIRS = {
  'webrtc': 'webrtc',
  'webrtc-extensions': null,
  'webrtc-stats': 'webrtc-stats',
  'webrtc-svc': 'webrtc-svc',
  'webrtc-encoded-transform': 'webrtc-encoded-transform',
  'webrtc-priority': 'webrtc-priority',
  'webrtc-ice': 'webrtc-ice',
  'webrtc-identity': 'webrtc-identity',
  'mediacapture-streams': 'mediacapture-streams',
  'mediacapture-extensions': 'mediacapture-extensions',
  'screen-capture': 'screen-capture',
  'audio-output': 'audio-output',
  'mst-content-hint': 'mst-content-hint',
  'mediacapture-insertable-streams': 'mediacapture-transform',
  'mediacapture-region': 'mediacapture-region',
  'mediacapture-viewport': 'mediacapture-viewport',
  'mediacapture-handle': null,
  'mediacapture-image': 'image-capture',
  'mediacapture-record': 'mediastream-recording',
  'mediacapture-fromelement': 'mediacapture-fromelement',
  'webrtc-diagnostic-logging': 'webrtc-diagnostic-logging',
};

// Targets of partial interfaces that only exist in a browser DOM. Members added to these
// need a native-platform equivalent to be designed (flagged as domBound in the inventory).
export const DOM_BOUND_TARGETS = new Set([
  'Navigator', 'Window', 'WorkerGlobalScope', 'DedicatedWorkerGlobalScope', 'Element',
  'HTMLElement', 'HTMLMediaElement', 'HTMLVideoElement', 'HTMLCanvasElement', 'Document',
]);
