package krtc.idl

/**
 * Marks API from `extended`-tier specifications (see tools/spec-inventory/scope.mjs):
 * in scope, but not required for krtc 1.0 and may change or be missing on some platforms.
 */
@RequiresOptIn(
    message = "Extended-tier WebRTC/Media Capture API; may be unavailable on some platforms.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.TYPEALIAS,
)
public annotation class KrtcExtendedApi
