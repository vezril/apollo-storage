package apollostorage

import apollostorage.api.{GrpcServer, HealthServiceImpl, ObjectApiImpl}
import apollostorage.blob.{BlobStoreReadiness, FileSystemBlobStore, ObjectService}
import apollostorage.build.BuildInfo
import apollostorage.config.AppConfig
import apollostorage.domain.BucketName
import apollostorage.http.{HealthRoutes, HttpServer}
import apollostorage.persistence.{BucketEntity, BucketEntityManager, PersistenceReadiness}
import apollostorage.projection.{BucketProjection, ReadModelRepository}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.projection.ProjectionBehavior
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/**
 * Entry point. Constructs the object stack, binds the HTTP health endpoint and the gRPC API (h2c),
 * then runs the blob-store and Postgres readiness checks before reporting ready. Any bind or
 * dependency failure logs the cause and exits non-zero. SIGTERM is handled by Pekko Coordinated
 * Shutdown.
 *
 * The guardian is the [[BucketEntityManager]] (v1 sharding stand-in, design D2).
 */
object Main:
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val config = ConfigFactory.load()
    val http = AppConfig.http(config)
    val blobRoot = AppConfig.blobRoot(config)
    val grpcPort = AppConfig.grpcPort(config)

    given system: ActorSystem[BucketEntityManager.Command] =
      ActorSystem(BucketEntityManager(), "apollostorage", config)
    import system.executionContext
    given Timeout = Timeout(10.seconds)
    given Scheduler = system.scheduler

    def entityFor(bucket: BucketName): Future[ActorRef[BucketEntity.Command]] =
      system.ask(replyTo => BucketEntityManager.GetEntity(bucket, replyTo))

    val readiness = new AtomicBoolean(false)

    // The object stack builds without touching the database.
    val blobStore = FileSystemBlobStore(blobRoot)
    val objectService = ObjectService(blobStore, entityFor)
    val readModel = new ReadModelRepository(AppConfig.postgres(config))
    val objectApi = ObjectApiImpl(objectService, blobStore, entityFor, readModel)
    val health = HealthServiceImpl(() => readiness.get())

    val httpRoutes = HealthRoutes(BuildInfo.version, () => readiness.get())
    val grpcHandler = GrpcServer.handler(objectApi, health)

    val bindings: Future[(ServerBinding, ServerBinding)] =
      for
        httpBinding <- HttpServer.bind(httpRoutes, http.host, http.port)
        grpcBinding <- GrpcServer.bind(grpcHandler, http.host, grpcPort)
      yield (httpBinding, grpcBinding)

    bindings.onComplete {
      case Success((httpBinding, grpcBinding)) =>
        HttpServer.wireShutdown(httpBinding, readiness)
        grpcBinding.addToCoordinatedShutdown(10.seconds)
        log.info(
          "ApolloStorage {} bound HTTP :{} and gRPC :{}; checking dependencies…",
          BuildInfo.version,
          Integer.valueOf(httpBinding.localAddress.getPort),
          Integer.valueOf(grpcBinding.localAddress.getPort)
        )
        BlobStoreReadiness.check(blobRoot) match
          case Failure(ex) =>
            log.error(s"Blob store not ready — ${ex.getMessage}", ex)
            system.terminate()
            System.exit(1)
          case Success(_) =>
            PersistenceReadiness.check(AppConfig.postgres(config)).onComplete {
              case Success(_) =>
                // Start the read-model projection now that Postgres is reachable.
                system ! BucketEntityManager.RunProjection(
                  ProjectionBehavior(BucketProjection(readModel))
                )
                readiness.set(true)
                log.info("Postgres journal + blob store ready — ApolloStorage is UP")
              case Failure(ex) =>
                log.error(s"Postgres journal unreachable after retries — ${ex.getMessage}", ex)
                system.terminate()
                System.exit(1)
            }
      case Failure(ex) =>
        log.error(s"Failed to bind (HTTP :${http.port} / gRPC :$grpcPort) — ${ex.getMessage}", ex)
        system.terminate()
        System.exit(1)
    }
