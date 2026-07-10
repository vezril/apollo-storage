package apollostorage

import apollostorage.api.{
  GrpcMetrics,
  GrpcServer,
  HealthServiceImpl,
  ObjectApiImpl,
  SecurityWarnings,
  TlsContext,
  TokenAuthenticator
}
import apollostorage.blob.{
  BlobGc,
  BlobMetrics,
  BlobStoreReadiness,
  FileSystemBlobStore,
  ObjectService
}
import apollostorage.build.BuildInfo
import apollostorage.config.AppConfig
import apollostorage.http.{AdminRoutes, HealthRoutes, HttpServer, MetricsRoutes}
import apollostorage.metrics.MetricsRegistry
import apollostorage.persistence.{BucketSharding, PersistenceMigration, PersistenceReadiness}
import apollostorage.projection.{BucketProjection, ReadModelRepository}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.server.Directives.*
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
    val _ = PekkoManagement(system).start()
    ClusterBootstrap(system).start()
    val sharding = BucketSharding.init(system)
    def entityFor(bucket: apollostorage.domain.BucketName) =
      BucketSharding.entityRef(sharding, bucket)

    val readiness = new AtomicBoolean(false)

    // Metrics (design D40-D44). When enabled, a registry feeds blob/gRPC instrumentation and
    // the /metrics endpoint; when disabled, nothing is installed (no-op sink, no route).
    val metricsCfg = AppConfig.metrics(config)
    val metrics: Option[MetricsRegistry] =
      if metricsCfg.enabled then Some(new MetricsRegistry(BuildInfo.version, () => readiness.get()))
      else None
    val blobMetrics: BlobMetrics = metrics.fold(BlobMetrics.noop)(m =>
      new BlobMetrics:
        def observe(operation: String, outcome: String, seconds: Double): Unit =
          m.observeBlob(operation, outcome, seconds)
        def addBytes(direction: String, n: Long): Unit = m.addBytes(direction, n)
    )

    val blobStore = FileSystemBlobStore(blobRoot, blobMetrics)
    val objectService = ObjectService(blobStore, entityFor)
    val readModel = new ReadModelRepository(AppConfig.postgres(config))

    // Transport security + authentication (design D34-D39).
    val tlsCfg = AppConfig.tls(config)
    val authCfg = AppConfig.auth(config)
    val authenticator = TokenAuthenticator(authCfg) // fails fast if enabled with no tokens
    SecurityWarnings.warnings(tlsCfg, authCfg).foreach(w => log.warn(w))
    val httpsContext = if tlsCfg.enabled then Some(TlsContext.httpsServer(tlsCfg)) else None

    val objectApi = ObjectApiImpl(objectService, blobStore, entityFor, readModel, authenticator)
    val health = HealthServiceImpl(() => readiness.get())

    // Orphan-blob GC admin trigger (design D50-D56). Off by default; when enabled, the sweep is
    // reachable at POST /admin/blob-gc, dry-run unless confirmed, gated by the same auth.
    val blobGcCfg = AppConfig.blobGc(config)
    val adminRoutes = Option.when(blobGcCfg.enabled) {
      val gc =
        new BlobGc(blobStore, entityFor, java.time.Duration.ofMillis(blobGcCfg.grace.toMillis))
      AdminRoutes.blobGc(delete => gc.sweep(delete = delete), authenticator)
    }
    log.info(
      "Blob GC {} (POST /admin/blob-gc)",
      if blobGcCfg.enabled then "ENABLED" else "DISABLED"
    )

    val healthRoutes = HealthRoutes(BuildInfo.version, () => readiness.get())
    val httpRoutes =
      (metrics.map(MetricsRoutes.apply).toList ++ adminRoutes.toList).foldLeft(healthRoutes)(_ ~ _)
    val grpcHandlerRaw = GrpcServer.handler(objectApi, health)
    val grpcHandler = metrics.fold(grpcHandlerRaw)(m => GrpcMetrics.instrument(grpcHandlerRaw, m))
    log.info(
      "Metrics {} (GET /metrics on the HTTP port)",
      if metricsCfg.enabled then "ENABLED" else "DISABLED"
    )

    val bindings: Future[(ServerBinding, ServerBinding)] =
      for
        httpBinding <- HttpServer.bind(httpRoutes, http.host, http.port)
        grpcBinding <- GrpcServer.bind(grpcHandler, http.host, grpcPort, httpsContext)
      yield (httpBinding, grpcBinding)

    bindings.onComplete {
      case Success((httpBinding, grpcBinding)) =>
        HttpServer.wireShutdown(httpBinding, readiness)
        val _ = grpcBinding.addToCoordinatedShutdown(10.seconds)
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
            val pgCfg = AppConfig.postgres(config)
            // Reachable → apply the schema (self-migration, D62-D66) → then serve.
            val dbReady = PersistenceReadiness.check(pgCfg).flatMap { _ =>
              if AppConfig.autoMigrate(config) then
                PersistenceMigration
                  .run(pgCfg)
                  .recoverWith { case ex =>
                    Future.failed(
                      new RuntimeException(s"schema migration failed — ${ex.getMessage}", ex)
                    )
                  }
              else
                log.info("Schema auto-migration DISABLED — expecting a pre-provisioned database")
                Future.unit
            }
            dbReady.onComplete {
              case Success(_) =>
                startProjection(readModel, projectionInstances)
                readiness.set(true)
                log.info("Postgres + blob store ready — ApolloStorage is UP")
              case Failure(ex) =>
                log.error(s"Startup database step failed — ${ex.getMessage}", ex)
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
