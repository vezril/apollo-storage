package apollostorage.blob

import apollostorage.config.S3Config
import apollostorage.domain.{BlobRef, BucketName, Checksums}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http}
import org.apache.pekko.stream.connectors.s3.scaladsl.S3
import org.apache.pekko.stream.connectors.s3.{AccessStyle, S3Attributes, S3Ext, S3Settings}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.util.ByteString
import org.apache.pekko.{Done, NotUsed}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import java.nio.file.{Files, Paths}
import java.security.{KeyStore, MessageDigest, SecureRandom}
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32C
import javax.net.ssl.{SSLContext, TrustManager, TrustManagerFactory, X509TrustManager}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Using}

/**
 * S3-compatible blob store (add-s3-backend-and-rest-api). Delegates durable byte storage to an
 * external S3 store (e.g. the QNAP's QuObjects) via Alpakka S3, keeping the same `BlobStore`
 * contract as the filesystem backend. All Apollo blobs live under one S3 bucket (`cfg.bucket`); the
 * opaque `BlobRef` (`<apolloBucket>/<shard>/<id>`) is used verbatim as the S3 object key. Multipart
 * upload provides the atomic-staging that the filesystem backend gets from temp-file→rename: parts
 * are invisible until completion, and a checksum mismatch removes the object so nothing retrievable
 * remains.
 */
final class S3BlobStore(cfg: S3Config, metrics: BlobMetrics = BlobMetrics.noop)(using
    system: ActorSystem[?]
) extends BlobStore:
  private given ExecutionContext = system.executionContext

  configureClientTls()

  private val s3Settings: S3Settings =
    val regionProvider: AwsRegionProvider = () => Region.of(cfg.region)
    S3Ext(system).settings
      .withEndpointUrl(cfg.endpoint)
      .withCredentialsProvider(
        StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKey, cfg.secretKey))
      )
      .withS3RegionProvider(regionProvider)
      .withAccessStyle(
        if cfg.pathStyle then AccessStyle.PathAccessStyle else AccessStyle.VirtualHostAccessStyle
      )
  private val attrs = S3Attributes.settings(s3Settings)
  private val s3Bucket = cfg.bucket

  def put(
      bucket: BucketName,
      data: Source[ByteString, Any],
      expected: Option[Checksums]
  ): Future[BlobPutResult] =
    val id = UUID.randomUUID().toString.replace("-", "")
    val ref = BlobRef(s"${bucket.value}/${id.take(2)}/$id")
    val key = ref.value
    val crc = new CRC32C()
    val md = MessageDigest.getInstance("MD5")
    val bytes = new AtomicLong(0L)
    val digesting = Flow[ByteString].map { bs =>
      val arr = bs.toArray
      crc.update(arr, 0, arr.length)
      md.update(arr, 0, arr.length)
      val _ = bytes.addAndGet(arr.length.toLong)
      bs
    }
    val start = System.nanoTime()
    data
      .via(digesting)
      .runWith(S3.multipartUpload(s3Bucket, key).withAttributes(attrs))
      .flatMap { _ =>
        val computed =
          Checksums(f"${crc.getValue}%08x", md.digest().map("%02x".format(_)).mkString)
        expected match
          case Some(exp) if exp != computed =>
            // Compensating delete so no retrievable object remains on a mismatch.
            S3.deleteObject(s3Bucket, key)
              .withAttributes(attrs)
              .runWith(Sink.ignore)
              .flatMap(_ => Future.failed(BlobStoreException.ChecksumMismatch(exp, computed)))
          case _ =>
            Future.successful(BlobPutResult(ref, bytes.get(), computed))
      }
      .transform { result =>
        recordOp("put", result, start)
        result.foreach(r => metrics.addBytes("written", r.size))
        result
      }

  def get(ref: BlobRef): Future[Option[Source[ByteString, Any]]] =
    val start = System.nanoTime()
    S3.getObjectMetadata(s3Bucket, ref.value)
      .withAttributes(attrs)
      .runWith(Sink.head)
      .map {
        case Some(_) =>
          Some(countBytesRead(S3.getObject(s3Bucket, ref.value).withAttributes(attrs)))
        case None => None
      }
      .transform { result =>
        recordOp("get", result, start); result
      }

  def delete(ref: BlobRef): Future[Boolean] =
    val start = System.nanoTime()
    // S3 DeleteObject is idempotent and does not report prior existence; report success as removed.
    S3.deleteObject(s3Bucket, ref.value)
      .withAttributes(attrs)
      .runWith(Sink.ignore)
      .map(_ => true)
      .transform { result =>
        recordOp("delete", result, start); result
      }

  // --- Enumeration for blob-gc reconciliation (design D51) against S3 ---

  def listBucketsOnDisk(): Future[Vector[BucketName]] =
    S3.listBucket(s3Bucket, None)
      .withAttributes(attrs)
      .runWith(Sink.seq)
      .map(
        _.iterator
          .map(_.key.split("/", 2).head)
          .filter(_.nonEmpty)
          .distinct
          .map(BucketName.unsafe)
          .toVector
      )

  def listStoredBlobs(bucket: BucketName): Future[Vector[StoredBlob]] =
    S3.listBucket(s3Bucket, Some(s"${bucket.value}/"))
      .withAttributes(attrs)
      .runWith(Sink.seq)
      .map(_.iterator.map(o => StoredBlob(BlobRef(o.key), o.lastModified, o.size)).toVector)

  def listTempArtifacts(bucket: BucketName): Future[Vector[TempArtifact]] =
    // Incomplete multipart uploads are the S3 analog of `.tmp` write debris. The id encodes the
    // uploadId + key so `deleteTempArtifact` can abort it (size is unknown mid-upload → 0).
    S3.listMultipartUpload(s3Bucket, Some(s"${bucket.value}/"))
      .withAttributes(attrs)
      .runWith(Sink.seq)
      .map(_.iterator.map(u => TempArtifact(s"${u.uploadId}|${u.key}", u.initiated, 0L)).toVector)

  def deleteTempArtifact(bucket: BucketName, id: String): Future[Boolean] =
    id.split("\\|", 2) match
      case Array(uploadId, key) =>
        S3.deleteUploadSource(s3Bucket, key, uploadId)
          .withAttributes(attrs)
          .runWith(Sink.ignore)
          .map(_ => true)
      case _ => Future.failed(BlobStoreException.InvalidReference(id))

  /**
   * Startup readiness: verify the target bucket is reachable and exists (design D14). A list probe
   * fails on an unreachable endpoint or a missing bucket, so a misconfigured store surfaces up
   * front.
   */
  def checkReadiness(): Future[Unit] =
    S3.listBucket(s3Bucket, None).withAttributes(attrs).take(1).runWith(Sink.ignore).map(_ => ())

  /** Create the backing S3 bucket if it does not already exist (idempotent setup/readiness aid). */
  def ensureBucket(): Future[Done] =
    S3.makeBucketSource(s3Bucket).withAttributes(attrs).runWith(Sink.ignore).recover { case _ =>
      Done // already exists (or the readiness check surfaces a real connectivity failure)
    }

  // --- helpers ---

  private def recordOp(operation: String, result: Try[?], startNanos: Long): Unit =
    val outcome = if result.isSuccess then "success" else "failure"
    metrics.observe(operation, outcome, (System.nanoTime() - startNanos) / 1e9)

  private def countBytesRead(src: Source[ByteString, Any]): Source[ByteString, Any] =
    val counter = new AtomicLong(0L)
    src
      .map { bs =>
        counter.addAndGet(bs.length.toLong); bs
      }
      .watchTermination() { (mat, done) =>
        done.onComplete(_ => metrics.addBytes("read", counter.get()))
        mat
      }

  /**
   * When the endpoint is HTTPS, install the client trust policy: a truststore (production) or, as a
   * LAN-only escape hatch, trust-all + no hostname check for a self-signed/mismatched cert. Note
   * this sets the ActorSystem's DEFAULT client HTTPS context (process-wide) — acceptable because
   * Apollo's only runtime outbound HTTPS is to the S3 store.
   */
  private def configureClientTls(): Unit =
    if cfg.endpoint.toLowerCase.startsWith("https") then
      if cfg.tlsInsecure then Http()(system).setDefaultClientHttpsContext(insecureContext())
      else if cfg.truststorePath.nonEmpty then
        Http()(system).setDefaultClientHttpsContext(truststoreContext())

  private def insecureContext() =
    val trustAll = new X509TrustManager:
      def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
      def getAcceptedIssuers: Array[X509Certificate] = Array.empty
    val ssl = SSLContext.getInstance("TLS")
    ssl.init(null, Array[TrustManager](trustAll), new SecureRandom()) // scalafix:ok DisableSyntax
    ConnectionContext.httpsClient { (host: String, port: Int) =>
      val engine = ssl.createSSLEngine(host, port)
      engine.setUseClientMode(true)
      val params = engine.getSSLParameters
      params.setEndpointIdentificationAlgorithm(null) // scalafix:ok DisableSyntax
      engine.setSSLParameters(params)
      engine
    }

  private def truststoreContext() =
    val ks = KeyStore.getInstance(KeyStore.getDefaultType)
    Using.resource(Files.newInputStream(Paths.get(cfg.truststorePath))) { in =>
      ks.load(in, cfg.truststorePassword.toCharArray)
    }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)
    val ssl = SSLContext.getInstance("TLS")
    ssl.init(null, tmf.getTrustManagers, new SecureRandom()) // scalafix:ok DisableSyntax
    ConnectionContext.httpsClient(ssl)
