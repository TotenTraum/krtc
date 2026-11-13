package krtc.meet.log

/** [Traced] on Android: not woven (kaop-runtime has no Android variant), calls are not logged. */
@Target(AnnotationTarget.FUNCTION)
actual annotation class Traced(actual val args: Boolean = true)
