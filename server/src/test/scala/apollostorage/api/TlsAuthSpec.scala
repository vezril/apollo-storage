package apollostorage.api

import apollostorage.blob.{FileSystemBlobStore, ObjectService}
import apollostorage.config.{AuthConfig, PostgresConfig, TlsConfig}
import apollostorage.domain.BucketName
import apollostorage.grpc.{CreateBucketRequest, ObjectApiClient}
import apollostorage.persistence.{BucketEntity, BucketSharding}
import com.typesafe.config.ConfigFactory
import grpc.health.v1.{HealthCheckRequest, HealthCheckResponse, HealthClient}
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityRef
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.security.KeyStore
import javax.net.ssl.{TrustManagerFactory, X509TrustManager}
import scala.concurrent.duration.*
import scala.util.Using

/**
 * End-to-end TLS + bearer-token authentication (design D34/D35/D37): a client that trusts the
 * server certificate and presents a valid token succeeds over TLS; a tokenless object call is
 * `UNAUTHENTICATED`; the health service needs no token.
 */
final class TlsAuthSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("pekko.http.server.preview.enable-http2 = on")
        .withFallback(apollostorage.ClusterTestSupport.clusterConfig)
        .withFallback(EventSourcedBehaviorTestKit.config)
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers:

  private given Timeout = Timeout(10.seconds)
  private given scala.concurrent.ExecutionContext = system.executionContext
  private val token = "test-secret-token"
  private var client: ObjectApiClient = scala.compiletime.uninitialized
  private var health: HealthClient = scala.compiletime.uninitialized

  private def resourcePath(name: String): String =
    getClass.getClassLoader.getResource(s"tls/$name").getPath

  private def trustManager: X509TrustManager =
    val ts = KeyStore.getInstance("PKCS12")
    Using.resource(getClass.getClassLoader.getResourceAsStream("tls/truststore.p12"))(in =>
      ts.load(in, "changeit".toCharArray)
    )
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ts)
    tmf.getTrustManagers.collectFirst { case x: X509TrustManager => x }.get

  override protected def beforeAll(): Unit =
    super.beforeAll()
    val sharding = apollostorage.ClusterTestSupport.formSingleNode(system)
    val store = FileSystemBlobStore(java.nio.file.Files.createTempDirectory("apollo-tls"))
    val entityFor: BucketName => EntityRef[BucketEntity.Command] =
      b => BucketSharding.entityRef(sharding, b)
    val readModel = new apollostorage.projection.ReadModelRepository(
      PostgresConfig("localhost", 1, "x", "x", "x", 1.second)
    )
    val authenticator = new TokenAuthenticator(AuthConfig(enabled = true, tokens = Seq(token)))
    val impl =
      new ObjectApiImpl(ObjectService(store, entityFor), store, entityFor, readModel, authenticator)

    val tls =
      TlsContext.httpsServer(TlsConfig(enabled = true, resourcePath("server.p12"), "changeit"))
    val handler = GrpcServer.handler(impl, HealthServiceImpl(() => true))
    val binding = GrpcServer.bind(handler, "127.0.0.1", 0, Some(tls)).futureValue
    val port = binding.localAddress.getPort

    val settings =
      GrpcClientSettings
        .connectToServiceAt("localhost", port)(system)
        .withTrustManager(trustManager)
    client = ObjectApiClient(settings)
    health = HealthClient(settings)
    ()

  private def codeOf(t: Throwable): Status.Code = t match
    case e: StatusRuntimeException => e.getStatus.getCode
    case other => throw other

  "TLS + auth" should {

    "accept an authenticated call over TLS" in {
      client
        .createBucket()
        .addHeader("authorization", s"Bearer $token")
        .invoke(CreateBucketRequest("secure"))
        .futureValue
        .bucket shouldBe "secure"
    }

    "reject a call with no token as UNAUTHENTICATED" in {
      codeOf(client.createBucket(CreateBucketRequest("nope")).failed.futureValue) shouldBe
        Status.Code.UNAUTHENTICATED
    }

    "reject a call with a wrong token as UNAUTHENTICATED" in {
      codeOf(
        client
          .createBucket()
          .addHeader("authorization", "Bearer wrong")
          .invoke(CreateBucketRequest("nope2"))
          .failed
          .futureValue
      ) shouldBe Status.Code.UNAUTHENTICATED
    }

    "serve health over TLS without a token" in {
      health.check(HealthCheckRequest()).futureValue.status shouldBe
        HealthCheckResponse.ServingStatus.SERVING
    }
  }
