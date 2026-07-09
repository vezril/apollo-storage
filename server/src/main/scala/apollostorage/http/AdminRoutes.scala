package apollostorage.http

import apollostorage.api.TokenAuthenticator
import apollostorage.blob.GcReport
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.Future

/**
 * Administrative endpoints (design D56). `POST /admin/blob-gc` runs a reconciliation sweep and
 * returns the report as JSON — a **dry run** unless `?delete=true` is given. Gated by API auth when
 * auth is enabled (reuses the same bearer tokens). Off unless blob-gc is enabled, in which case the
 * caller omits this route entirely (so `/admin/blob-gc` is a 404).
 */
object AdminRoutes:

  def blobGc(sweep: Boolean => Future[GcReport], authenticator: TokenAuthenticator): Route =
    path("admin" / "blob-gc") {
      post {
        parameter("delete".as[Boolean] ? false) { deleteConfirmed =>
          optionalHeaderValueByName("authorization") { authHeader =>
            if authenticator.authorizeBearer(authHeader) then
              onSuccess(sweep(deleteConfirmed)) { report =>
                complete(HttpEntity(ContentTypes.`application/json`, toJson(report)))
              }
            else complete(StatusCodes.Unauthorized)
          }
        }
      }
    }

  private def toJson(r: GcReport): String =
    s"""{"dryRun":${r.dryRun},"bucketsScanned":${r.bucketsScanned},""" +
      s""""bucketsSkipped":${r.bucketsSkipped},"blobsScanned":${r.blobsScanned},""" +
      s""""liveBlobs":${r.liveBlobs},"orphansFound":${r.orphansFound},""" +
      s""""bytesOrphaned":${r.bytesOrphaned},"reclaimed":${r.reclaimed},""" +
      s""""bytesReclaimed":${r.bytesReclaimed},"tmpFound":${r.tmpFound},""" +
      s""""tmpReclaimed":${r.tmpReclaimed}}"""
