package krtc.meet.log

import ru.ttraum.kaop.runtime.AbstractJoinPoint
import ru.ttraum.kaop.runtime.Advice
import ru.ttraum.kaop.runtime.JoinPoint
import ru.ttraum.kaop.runtime.MethodInterceptor
import ru.ttraum.kaop.runtime.SuspendJoinPoint
import ru.ttraum.kaop.runtime.SuspendMethodInterceptor
import kotlin.time.TimeSource

/** [Traced] on JVM and iOS: kaop weaves [TracedInterceptor] around every function marked with it. */
@Advice(TracedInterceptor::class)
@Target(AnnotationTarget.FUNCTION)
annotation class WovenTraced(val args: Boolean = true)

actual typealias Traced = WovenTraced

object TracedInterceptor : MethodInterceptor, SuspendMethodInterceptor {
    /** Arguments longer than this are cut in the log. */
    private const val MAX_ARG_CHARS = 200

    override fun intercept(joinPoint: JoinPoint): Any? = traced(joinPoint) { joinPoint.proceed() }

    override suspend fun intercept(joinPoint: SuspendJoinPoint): Any? = traced(joinPoint) { joinPoint.proceed() }

    private inline fun traced(joinPoint: AbstractJoinPoint, proceed: () -> Any?): Any? {
        val name = joinPoint.method.name
        val tag = joinPoint.method.declaringClass?.substringAfterLast('.') ?: "app"
        val showArgs = joinPoint.annotation<WovenTraced>().args
        val args = if (!showArgs || joinPoint.argCount == 0) "" else
            joinPoint.method.parameterNames.zip(joinPoint.args).joinToString(", ") { (n, v) -> "$n=${v.toString().take(MAX_ARG_CHARS)}" }
        val start = TimeSource.Monotonic.markNow()
        return try {
            proceed().also { ClientLog.d(tag, "$name($args) ${start.elapsedNow().inWholeMilliseconds} ms") }
        } catch (e: Throwable) {
            ClientLog.e(tag, "$name($args) threw after ${start.elapsedNow().inWholeMilliseconds} ms: ${e.stackTraceToString()}")
            throw e
        }
    }
}
