package apollostorage.blob

import apollostorage.domain.BucketName
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * Verifies the blob store reports operation latency, outcome, and bytes to its metrics sink (design
 * D42), without coupling the store to Prometheus.
 */
final class BlobMetricsSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with Eventually:

  private given scala.concurrent.ExecutionContext = system.executionContext

  final private class RecordingSink extends BlobMetrics:
    val ops = new ConcurrentLinkedQueue[(String, String)]()
    val bytes = new ConcurrentLinkedQueue[(String, Long)]()
    def observe(operation: String, outcome: String, seconds: Double): Unit =
      ops.add(operation -> outcome); ()
    def addBytes(direction: String, n: Long): Unit =
      bytes.add(direction -> n); ()
    def opList: List[(String, String)] = ops.asScala.toList
    def byteList: List[(String, Long)] = bytes.asScala.toList

  private def store(sink: BlobMetrics) =
    FileSystemBlobStore(java.nio.file.Files.createTempDirectory("apollo-blob-metrics"), sink)

  "FileSystemBlobStore metrics" should {

    "record a put with success outcome and written bytes" in {
      val sink = new RecordingSink
      val payload = ByteString("hello world")
      store(sink).put(BucketName.unsafe("metrics"), Source.single(payload), None).futureValue
      sink.opList should contain("put" -> "success")
      eventually(sink.byteList should contain("written" -> payload.length.toLong))
    }

    "record get bytes and delete outcome" in {
      val sink = new RecordingSink
      val s = store(sink)
      val put =
        s.put(BucketName.unsafe("metrics"), Source.single(ByteString("abcd")), None).futureValue
      s.get(put.ref).futureValue.foreach(_.runFold(ByteString.empty)(_ ++ _).futureValue)
      s.delete(put.ref).futureValue
      sink.opList should contain("get" -> "success")
      sink.opList should contain("delete" -> "success")
      eventually(sink.byteList should contain("read" -> 4L))
    }

    "record a failure outcome when a checksum mismatch aborts a put" in {
      val sink = new RecordingSink
      val wrong = apollostorage.domain.Checksums("deadbeef", "0" * 32)
      store(sink)
        .put(BucketName.unsafe("metrics"), Source.single(ByteString("xyz")), Some(wrong))
        .failed
        .futureValue
      sink.opList should contain("put" -> "failure")
    }
  }
