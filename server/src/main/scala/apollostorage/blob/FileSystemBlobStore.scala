package apollostorage.blob

import apollostorage.domain.{BlobRef, BucketName, Checksums}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Flow, Source}
import org.apache.pekko.util.ByteString

import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32C
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Filesystem/NFS-backed blob store. Writes are crash-safe (temp file → fsync → atomic rename,
 * design D11) and blobs are immutable under an opaque, sharded reference `<bucket>/<id[0:2]>/<id>`
 * (design D9).
 */
final class FileSystemBlobStore(root: Path, metrics: BlobMetrics = BlobMetrics.noop)(using
    system: ActorSystem[?]
) extends BlobStore:
  private given ExecutionContext = system.executionContext

  def put(
      bucket: BucketName,
      data: Source[ByteString, Any],
      expected: Option[Checksums]
  ): Future[BlobPutResult] =
    val id = UUID.randomUUID().toString.replace("-", "")
    val ref = BlobRef(s"${bucket.value}/${id.take(2)}/$id")
    val finalPath = resolve(ref)
    val tmp = root.resolve(bucket.value).resolve(".tmp").resolve(id)

    val crc = new CRC32C()
    val md = MessageDigest.getInstance("MD5")
    val digesting = Flow[ByteString].map { bs =>
      val arr = bs.toArray
      crc.update(arr, 0, arr.length)
      md.update(arr, 0, arr.length)
      bs
    }

    val start = System.nanoTime()
    Future(Files.createDirectories(tmp.getParent))
      .flatMap(_ => data.via(digesting).runWith(FileIO.toPath(tmp)))
      .flatMap { io =>
        val computed = Checksums(f"${crc.getValue}%08x", md.digest().map("%02x".format(_)).mkString)
        expected match
          case Some(exp) if exp != computed =>
            Future.failed(BlobStoreException.ChecksumMismatch(exp, computed))
          case _ =>
            Future.fromTry(Try {
              fsyncFile(tmp)
              Files.createDirectories(finalPath.getParent)
              Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE)
              fsyncDirBestEffort(finalPath.getParent)
              BlobPutResult(ref, io.count, computed)
            })
      }
      .recoverWith { case NonFatal(e) =>
        Files.deleteIfExists(tmp)
        Future.failed(e)
      }
      .transform { result =>
        recordOp("put", result, start)
        result.foreach(r => metrics.addBytes("written", r.size))
        result
      }

  def get(ref: BlobRef): Future[Option[Source[ByteString, Any]]] =
    val start = System.nanoTime()
    Future {
      val path = resolve(ref)
      if Files.isRegularFile(path) then Some(countBytesRead(FileIO.fromPath(path))) else None
    }.transform { result =>
      recordOp("get", result, start)
      result
    }

  def delete(ref: BlobRef): Future[Boolean] =
    val start = System.nanoTime()
    Future(Files.deleteIfExists(resolve(ref))).transform { result =>
      recordOp("delete", result, start)
      result
    }

  /** Report an operation's outcome + latency to the metrics sink. */
  private def recordOp(operation: String, result: Try[?], startNanos: Long): Unit =
    val outcome = if result.isSuccess then "success" else "failure"
    metrics.observe(operation, outcome, (System.nanoTime() - startNanos) / 1e9)

  /** Wrap a read stream so the bytes it yields are reported once it terminates. */
  private def countBytesRead(src: Source[ByteString, Any]): Source[ByteString, Any] =
    val counter = new java.util.concurrent.atomic.AtomicLong(0L)
    src
      .map { bs =>
        counter.addAndGet(bs.length.toLong); bs
      }
      .watchTermination() { (mat, done) =>
        done.onComplete(_ => metrics.addBytes("read", counter.get()))
        mat
      }

  /** Resolve a reference under the root, refusing any path that escapes it. */
  private def resolve(ref: BlobRef): Path =
    val path = root.resolve(ref.value).normalize()
    if !path.startsWith(root.normalize()) then throw BlobStoreException.InvalidReference(ref.value)
    path

  private def fsyncFile(path: Path): Unit =
    val ch = FileChannel.open(path, StandardOpenOption.WRITE)
    try ch.force(true)
    finally ch.close()

  // Directory fsync makes the rename durable; unsupported on some platforms
  // (e.g. macOS), so best-effort.
  private def fsyncDirBestEffort(dir: Path): Unit =
    try
      val ch = FileChannel.open(dir)
      try ch.force(true)
      finally ch.close()
    catch case NonFatal(_) => ()
