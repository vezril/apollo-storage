package apollostorage.api

import apollostorage.config.{AuthConfig, TlsConfig}

/**
 * Startup warnings for insecure configurations (design D38), so a silently-insecure deployment is
 * visible in the logs.
 */
object SecurityWarnings:

  def warnings(tls: TlsConfig, auth: AuthConfig): Seq[String] =
    Seq(
      Option.when(!tls.enabled)("TLS is DISABLED — the gRPC API is served over cleartext h2c"),
      Option.when(!auth.enabled)("Authentication is DISABLED — every API call is unauthenticated"),
      Option.when(auth.enabled && !tls.enabled)(
        "Authentication is enabled but TLS is DISABLED — bearer tokens travel in cleartext"
      )
    ).flatten
