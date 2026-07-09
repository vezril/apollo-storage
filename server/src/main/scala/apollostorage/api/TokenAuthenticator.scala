package apollostorage.api

import apollostorage.config.AuthConfig
import io.grpc.Status
import org.apache.pekko.grpc.GrpcServiceException
import org.apache.pekko.grpc.scaladsl.Metadata

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/**
 * Validates a bearer token from gRPC request metadata (design D35/D39). When auth is disabled it is
 * a no-op; when enabled, a missing or unrecognized token raises `UNAUTHENTICATED`. Comparison is
 * constant-time to avoid timing side-channels.
 */
final class TokenAuthenticator(cfg: AuthConfig):

  if cfg.enabled && cfg.tokens.isEmpty then
    throw new IllegalStateException("authentication is enabled but no tokens are configured")

  private val tokenBytes: Seq[Array[Byte]] = cfg.tokens.map(_.getBytes(UTF_8))

  /**
   * No-op when disabled; otherwise throws `GrpcServiceException(UNAUTHENTICATED)` unless a valid
   * `authorization: Bearer <token>` is present.
   */
  def check(metadata: Metadata): Unit =
    if cfg.enabled then
      val presented = metadata
        .getText("authorization")
        .filter(_.regionMatches(true, 0, "Bearer ", 0, 7))
        .map(_.substring(7).trim)
      if !presented.exists(valid) then
        throw new GrpcServiceException(
          Status.UNAUTHENTICATED.withDescription("missing or invalid bearer token")
        )

  /**
   * Authorize an HTTP request by its `Authorization` header value (design D56, reused for the admin
   * endpoint). Returns `true` when auth is disabled, or when a valid `Bearer <token>` is present;
   * `false` otherwise.
   */
  def authorizeBearer(header: Option[String]): Boolean =
    if !cfg.enabled then true
    else
      header
        .filter(_.regionMatches(true, 0, "Bearer ", 0, 7))
        .map(_.substring(7).trim)
        .exists(valid)

  private def valid(token: String): Boolean =
    val bytes = token.getBytes(UTF_8)
    tokenBytes.exists(expected => MessageDigest.isEqual(expected, bytes))
