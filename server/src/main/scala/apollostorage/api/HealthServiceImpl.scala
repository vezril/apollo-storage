package apollostorage.api

import grpc.health.v1.{Health, HealthCheckRequest, HealthCheckResponse}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.Future

/**
 * Standard `grpc.health.v1.Health` service backed by the same readiness flag as the HTTP `/health`
 * endpoint (design D7/D19): `SERVING` once Postgres and the blob store are ready, `NOT_SERVING`
 * otherwise.
 */
final class HealthServiceImpl(isReady: () => Boolean) extends Health:

  private def status: HealthCheckResponse.ServingStatus =
    if isReady() then HealthCheckResponse.ServingStatus.SERVING
    else HealthCheckResponse.ServingStatus.NOT_SERVING

  def check(in: HealthCheckRequest): Future[HealthCheckResponse] =
    Future.successful(HealthCheckResponse(status))

  def watch(in: HealthCheckRequest): Source[HealthCheckResponse, NotUsed] =
    Source.single(HealthCheckResponse(status))
