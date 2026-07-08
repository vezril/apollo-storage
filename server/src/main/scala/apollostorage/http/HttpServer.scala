package apollostorage.http

import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.server.Route

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * Binds the health surface and wires readiness into Coordinated Shutdown so that `/health` flips to
 * `DOWN` before the port unbinds (graceful drain).
 */
object HttpServer:

  /**
   * Attempt to bind. The returned Future fails fast if the port is unavailable (see service-runtime
   * spec: occupied port ⇒ fast failure). Callers decide exit semantics.
   */
  def bind(
      routes: Route,
      host: String,
      port: Int
  )(using system: ActorSystem[?]): Future[ServerBinding] =
    Http()(system).newServerAt(host, port).bind(routes)

  /**
   * Register readiness withdrawal (before unbind) and the binding's own unbind + terminate tasks
   * with Coordinated Shutdown.
   */
  def wireShutdown(
      binding: ServerBinding,
      readiness: AtomicBoolean
  )(using system: ActorSystem[?]): Unit =
    val cs = CoordinatedShutdown(system)
    cs.addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "withdraw-readiness") { () =>
      readiness.set(false)
      Future.successful(Done)
    }
    binding.addToCoordinatedShutdown(hardTerminationDeadline = 10.seconds)
    ()
