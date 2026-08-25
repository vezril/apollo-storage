package apollostorage.tracing

import org.slf4j.MDC

import scala.concurrent.ExecutionContext

/**
 * An [[ExecutionContext]] that carries the submitting thread's SLF4J MDC onto the worker thread for
 * the duration of a task (request-tracing capability). MDC is a thread-local, so a `correlationId`
 * set at request entry would otherwise be invisible to a `logger.trace` running later in a `Future`
 * continuation or after an actor ask. This wrapper snapshots the MDC at submit time, installs it
 * while the task runs, and restores the worker thread's prior MDC afterward — symmetric set/restore
 * so nothing leaks between tasks on a reused pool thread.
 */
final class MdcPropagatingExecutionContext(delegate: ExecutionContext) extends ExecutionContext:

  def execute(runnable: Runnable): Unit =
    val captured = Option(MDC.getCopyOfContextMap)
    delegate.execute { () =>
      val previous = Option(MDC.getCopyOfContextMap)
      install(captured)
      try runnable.run()
      finally install(previous)
    }

  def reportFailure(cause: Throwable): Unit = delegate.reportFailure(cause)

  /** Replace the current thread's MDC with `map` (or clear it when there was none). */
  private def install(map: Option[java.util.Map[String, String]]): Unit =
    map.fold(MDC.clear())(MDC.setContextMap)
