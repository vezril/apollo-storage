package apollostorage

import apollostorage.build.BuildInfo
import apollostorage.config.AppConfig
import apollostorage.http.{HealthRoutes, HttpServer}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.{Failure, Success}

/**
 * Entry point. Binds the HTTP health surface before reporting ready; on bind failure logs the
 * offending port and exits non-zero (service-runtime spec). SIGTERM is handled by Pekko Coordinated
 * Shutdown (JVM hook enabled), which withdraws readiness, unbinds, drains, then terminates with
 * exit code 0.
 */
object Main:
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val config = ConfigFactory.load()
    val http = AppConfig.http(config)

    given system: ActorSystem[Nothing] =
      ActorSystem[Nothing](Behaviors.empty[Nothing], "apollostorage", config)
    import system.executionContext

    val readiness = new AtomicBoolean(false)
    val routes = HealthRoutes(BuildInfo.version, () => readiness.get())

    HttpServer.bind(routes, http.host, http.port).onComplete {
      case Success(binding) =>
        readiness.set(true)
        HttpServer.wireShutdown(binding, readiness)
        log.info(
          "ApolloStorage {} listening on http://{}:{}/health",
          BuildInfo.version,
          http.host,
          Integer.valueOf(binding.localAddress.getPort)
        )
      case Failure(ex) =>
        log.error(s"Failed to bind HTTP on ${http.host}:${http.port} — ${ex.getMessage}", ex)
        system.terminate()
        System.exit(1)
    }
