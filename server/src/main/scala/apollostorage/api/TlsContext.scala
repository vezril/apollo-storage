package apollostorage.api

import apollostorage.config.TlsConfig
import org.apache.pekko.http.scaladsl.{ConnectionContext, HttpsConnectionContext}

import java.nio.file.{Files, Path}
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.util.Using

/**
 * Builds the server TLS context from a PKCS#12 keystore (design D34). Fails fast with a clear error
 * if the keystore is missing or the password is wrong.
 */
object TlsContext:

  def httpsServer(cfg: TlsConfig): HttpsConnectionContext =
    val path = Path.of(cfg.keystorePath)
    if !Files.isRegularFile(path) then
      throw new IllegalStateException(s"TLS keystore not found: ${cfg.keystorePath}")

    val password = cfg.keystorePassword.toCharArray
    val keyStore = KeyStore.getInstance("PKCS12")
    try Using.resource(Files.newInputStream(path))(in => keyStore.load(in, password))
    catch
      case e: Exception =>
        throw new IllegalStateException(
          s"Failed to load TLS keystore ${cfg.keystorePath} (wrong password or corrupt): ${e.getMessage}",
          e
        )

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(keyStore, password)
    val sslContext = SSLContext.getInstance("TLS")
    // null trust managers = the JVM defaults (standard SSLContext.init contract).
    sslContext.init(kmf.getKeyManagers, null, new SecureRandom) // scalafix:ok DisableSyntax
    ConnectionContext.httpsServer(sslContext)
