package apollostorage.api

import apollostorage.config.{AuthConfig, Principal, Scope}
import io.grpc.Status
import org.apache.pekko.grpc.GrpcServiceException
import org.apache.pekko.grpc.scaladsl.Metadata

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Outcome of an HTTP authorization check (design D60), so the admin route can map to a status. */
enum AuthOutcome:
  case Ok, Unauthenticated, Forbidden

/**
 * Authenticates a bearer token and authorizes it against the scope an operation requires (design
 * D35/D57/D59). When auth is disabled it is a no-op; when enabled, a missing/unknown token is
 * `UNAUTHENTICATED` and a valid token with an insufficient scope is `PERMISSION_DENIED`. Token
 * comparison is constant-time.
 */
final class TokenAuthenticator(cfg: AuthConfig):

  if cfg.enabled && cfg.principals.isEmpty then
    throw new IllegalStateException("authentication is enabled but no tokens are configured")

  private val principalBytes: Seq[(Array[Byte], Principal)] =
    cfg.principals.map(p => (p.token.getBytes(UTF_8), p))

  /**
   * No-op when disabled; otherwise throws `GrpcServiceException(UNAUTHENTICATED)` for a missing or
   * unknown `authorization: Bearer <token>`, or `PERMISSION_DENIED` when the token's scope does not
   * satisfy `required`.
   */
  def authorize(metadata: Metadata, required: Scope): Unit =
    if cfg.enabled then
      matched(metadata.getText("authorization")) match
        case None => throw unauthenticated
        case Some(p) if !p.scope.satisfies(required) => throw permissionDenied
        case Some(_) => ()

  /**
   * HTTP variant for the admin endpoint (design D60): `Ok` when auth is disabled or the header
   * carries a token whose scope satisfies `required`, `Unauthenticated` for a missing/unknown
   * token, `Forbidden` for a valid token of insufficient scope.
   */
  def authorizeHttp(header: Option[String], required: Scope): AuthOutcome =
    if !cfg.enabled then AuthOutcome.Ok
    else
      matched(header) match
        case None => AuthOutcome.Unauthenticated
        case Some(p) if p.scope.satisfies(required) => AuthOutcome.Ok
        case Some(_) => AuthOutcome.Forbidden

  /** The principal for a `Bearer <token>` header value, if the token is recognized. */
  private def matched(header: Option[String]): Option[Principal] =
    header
      .filter(_.regionMatches(true, 0, "Bearer ", 0, 7))
      .map(_.substring(7).trim)
      .flatMap { token =>
        val bytes = token.getBytes(UTF_8)
        principalBytes.collectFirst {
          case (expected, principal) if MessageDigest.isEqual(expected, bytes) => principal
        }
      }

  private def unauthenticated =
    new GrpcServiceException(
      Status.UNAUTHENTICATED.withDescription("missing or invalid bearer token")
    )

  private def permissionDenied =
    new GrpcServiceException(
      Status.PERMISSION_DENIED.withDescription("token scope does not permit this operation")
    )
