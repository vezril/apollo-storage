package apollostorage

import apollostorage.blob.{BlobStoreReadiness, FileSystemBlobStore, ObjectService}
import apollostorage.build.BuildInfo
import apollostorage.config.AppConfig
import apollostorage.domain.BucketName
import apollostorage.http.{HealthRoutes, HttpServer}
import apollostorage.persistence.{BucketEntity, BucketEntityManager, PersistenceReadiness}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/**
 * Entry point. Binds the HTTP health surface before reporting ready; on bind or dependency failure
 * logs the cause and exits non-zero (service-runtime and blob-storage specs). SIGTERM is handled by
 * Pekko Coordinated Shutdown.
 *
 * The actor system guardian is the [[BucketEntityManager]], the v1 stand-in for sharding (design
 * D2) that hands out per-bucket entities.
 */
object Main:
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val config = ConfigFactory.load()
    val http = AppConfig.http(config)
    val blobRoot = AppConfig.blobRoot(config)

    given system: ActorSystem[BucketEntityManager.Command] =
      ActorSystem(BucketEntityManager(), "apollostorage", config)
    import system.executionContext
    given Timeout = Timeout(10.seconds)
    given Scheduler = system.scheduler

    def entityFor(bucket: BucketName): Future[ActorRef[BucketEntity.Command]] =
      system.ask(replyTo => BucketEntityManager.GetEntity(bucket, replyTo))

    val readiness = new AtomicBoolean(false)
    val routes = HealthRoutes(BuildInfo.version, () => readiness.get())

    HttpServer.bind(routes, http.host, http.port).onComplete {
      case Success(binding) =>
        HttpServer.wireShutdown(binding, readiness)
        log.info(
          "ApolloStorage {} bound http://{}:{}/health; checking dependencies…",
          BuildInfo.version,
          http.host,
          Integer.valueOf(binding.localAddress.getPort)
        )
        // The blob store must be present and writable before we accept commits.
        BlobStoreReadiness.check(blobRoot) match
          case Failure(ex) =>
            log.error(s"Blob store not ready — ${ex.getMessage}", ex)
            system.terminate()
            System.exit(1)
          case Success(_) =>
            // Report ready only after the journal is reachable too; exit non-zero
            // if the database is unavailable after bounded retries.
            PersistenceReadiness.check(AppConfig.postgres(config)).onComplete {
              case Success(_) =>
                val blobStore = FileSystemBlobStore(blobRoot)
                // Composition root: constructed and ready for the object API (a
                // later change); nothing drives it at runtime yet.
                val _ = ObjectService(blobStore, entityFor)
                readiness.set(true)
                log.info(
                  "Postgres journal + blob store ready — ApolloStorage is UP (blob root {})",
                  blobRoot
                )
              case Failure(ex) =>
                log.error(s"Postgres journal unreachable after retries — ${ex.getMessage}", ex)
                system.terminate()
                System.exit(1)
            }
      case Failure(ex) =>
        log.error(s"Failed to bind HTTP on ${http.host}:${http.port} — ${ex.getMessage}", ex)
        system.terminate()
        System.exit(1)
    }
