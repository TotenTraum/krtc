package krtc.meet.log

/**
 * Logs every call of the function to [ClientLog] (woven by kaop at compile time on JVM and iOS): its arguments, how
 * long it took, and what it threw. [args] false keeps the arguments out of the log (chat text, anything private).
 * On Android it does nothing: kaop-runtime has no Android variant.
 */
@Target(AnnotationTarget.FUNCTION)
expect annotation class Traced(val args: Boolean = true)
