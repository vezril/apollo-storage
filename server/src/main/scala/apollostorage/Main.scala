package apollostorage

import apollostorage.api.{GrpcServer, HealthServiceImpl, ObjectApiImpl}
import apollostorage.blob.{BlobStoreReadiness, FileSystemBlobStore, ObjectService}
import apollostorage.build.BuildInfo
import apollostorage.config.AppConfig
import apollostorage.http.{HealthRoutes, HttpServer}
import apollostorage.persistence.{BucketSharding, PersistenceReadiness}
import apollostorage.projection.{BucketProjection, ReadModelRepository}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.apache.pekko.projection.ProjectionBehavior
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/**
 * Entry point. Forms a Pekko cluster (Management + Bootstrap, design D28), hosts bucket entities
 * via Cluster Sharding (D29), binds the HTTP health endpoint and the gRPC API, and — once Postgres
 * and the blob store are ready — starts the read-model projection distributed across the cluster
 * via ShardedDaemonProcess (D30). Any bind or dependency failure exits non-zero; SIGTERM is handled
 * by Coordinated Shutdown (shard handoff + rebalance, D31).
 */
object Main:
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val config = ConfigFactory.load()
    val http = AppConfig.http(config)
    val blobRoot = AppConfig.blobRoot(config)
    val grpcPort = AppConfig.grpcPort(config)
    val projectionInstances = config.getInt("apollostorage.projection.instances")

    given system: ActorSystem[Nothing] =
      ActorSystem[Nothing](Behaviors.empty[Nothing], "apollostorage", config)
    import system.executionContext
    given Timeout = Timeout(10.seconds)

    // Cluster formation and entity hosting.
    PekkoManagement(system).start()
    ClusterBootstrap(system).start()
    val sharding = BucketSharding.init(system)
    def entityFor(bucket: apollostorage.domain.BucketName) =
      BucketSharding.entityRef(sharding, bucket)

    val readiness = new AtomicBoolean(false)
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
                startProjection(readModel, projectionInstances)
                readiness.set(true)
                log.info("Postgres + blob store ready — ApolloStorage is UP")
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

  /** Distribute the projection across the cluster: one instance per slice range. */
  private def startProjection(readModel: ReadModelRepository, instances: Int)(using
      system: ActorSystem[?]
  ): Unit =
    val ranges = BucketProjection.sliceRanges(instances)
    ShardedDaemonProcess(system).init[ProjectionBehavior.Command](
      name = "bucket-projection",
      numberOfInstances = ranges.size,
      behaviorFactory = i => ProjectionBehavior(BucketProjection.forRange(readModel, ranges(i))),
      stopMessage = ProjectionBehavior.Stop
    )
