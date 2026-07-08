package apollostorage.api

import apollostorage.blob.{FileSystemBlobStore, ObjectService}
import apollostorage.config.AppConfig
import apollostorage.domain.BucketName
import apollostorage.grpc.{CreateBucketRequest, ObjectApiClient}
import apollostorage.persistence.{BucketEntity, BucketEntityManager}
import com.typesafe.config.ConfigFactory
import grpc.health.v1.{HealthCheckRequest, HealthCheckResponse, HealthClient}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * Runtime wiring: the combined gRPC handler binds a configured port and serves both the object API
 * and health; `GRPC_PORT` overrides the configured port.
 */
final class GrpcServerSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("pekko.http.server.preview.enable-http2 = on")
        .withFallback(
          org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.config
        )
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers:

  private given Timeout = Timeout(10.seconds)

  "AppConfig.grpcPort" should {
    "read the default and honor a GRPC_PORT override" in {
      val base = "apollostorage.grpc.port = 8443\napollostorage.grpc.port = ${?GRPC_PORT}"
      AppConfig.grpcPort(ConfigFactory.parseString(base).resolve()) shouldBe 8443
      val overridden =
        ConfigFactory
          .parseString("GRPC_PORT = 9443")
          .withFallback(ConfigFactory.parseString(base))
          .resolve()
      AppConfig.grpcPort(overridden) shouldBe 9443
    }
  }

  "GrpcServer" should {
    "bind a port and serve both the object API and health" in {
      val root = Files.createTempDirectory("apollo-grpcserver")
      val store = FileSystemBlobStore(root)
      val manager = spawn(BucketEntityManager())
      val entityFor: BucketName => Future[ActorRef[BucketEntity.Command]] =
        b => manager.ask(replyTo => BucketEntityManager.GetEntity(b, replyTo))
      val objectApi = new ObjectApiImpl(ObjectService(store, entityFor), store, entityFor)
      val ready = new AtomicBoolean(true)

      val handler = GrpcServer.handler(objectApi, HealthServiceImpl(() => ready.get()))
      val binding = GrpcServer.bind(handler, "127.0.0.1", 0).futureValue
      binding.localAddress.getPort should be > 0

      val settings =
        GrpcClientSettings
          .connectToServiceAt("127.0.0.1", binding.localAddress.getPort)(system)
          .withTls(false)

      // object API smoke
      ObjectApiClient(settings)
        .createBucket(CreateBucketRequest("smoke"))
        .futureValue
        .bucket shouldBe "smoke"
      // health smoke
      HealthClient(settings).check(HealthCheckRequest()).futureValue.status shouldBe
        HealthCheckResponse.ServingStatus.SERVING
    }
  }
