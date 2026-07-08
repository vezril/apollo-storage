package apollostorage.config

import com.typesafe.config.Config

/** HTTP bind settings, resolved from HOCON with env-var overrides (design D8). */
final case class HttpConfig(host: String, port: Int)

object AppConfig:
  def http(config: Config): HttpConfig =
    HttpConfig(
      host = config.getString("apollostorage.http.host"),
      port = config.getInt("apollostorage.http.port")
    )
