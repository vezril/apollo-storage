import scala.sys.process._

// ---------------------------------------------------------------------------
// ApolloStorage — GCS-inspired, event-sourced object store (homelab-production).
// Two modules (design D5):
//   core   — pure domain (zero Pekko deps), exhaustively unit-tested.
//   server — Pekko runtime + persistence, integration-tested.
// Version derived from git tags via sbt-dynver (design D6).
// ---------------------------------------------------------------------------

ThisBuild / scalaVersion := "3.3.4" // Scala 3 LTS
ThisBuild / organization := "io.apollostorage"
ThisBuild / organizationName := "ApolloStorage"
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / startYear := Some(2026)

// sbt-dynver: no version literal committed. Use a Docker-tag-safe separator
// (git describe's default '+' is illegal in image tags).
ThisBuild / dynverSeparator := "-"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror", // warnings are build failures (static-analysis)
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  // Silence warnings in pekko-grpc-generated sources (the vendored health stubs) — they can't be
  // hand-fixed and would otherwise fail -Werror.
  "-Wconf:src=.*pekko-grpc.*:s",
  "-source:3.3",
  "-Yretain-trees"
)

// SemanticDB so scalafix's semantic rules (DisableSyntax, OrganizeImports) run in CI.
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// The Apollo gRPC contract is generated from the Lexicon, not a local .proto
// (design adopt-lexicon-grpc-contracts, D46/D48). Resolve the published stubs from
// GitHub Packages. Maven reads of GitHub Packages need authentication even for a public package —
// an anonymous read is a 401 — but they do NOT need a PAT: the `io.codex.lexicon-*` packages are
// public, and the endpoint wants authentication rather than authorization for the package's own
// repository, so the built-in GITHUB_TOKEN is sufficient. Verified in CI by blanking LEXICON_TOKEN
// and watching the build resolve and compile on GITHUB_TOKEN alone.
//
// LEXICON_TOKEN still wins when set, so a PAT remains supported; it is simply no longer required.
// That matters for Dependabot, whose pull_request events receive no repository secrets.
ThisBuild / resolvers += "GitHub Packages — the-lexicon".at(
  "https://maven.pkg.github.com/vezril/the-lexicon"
)
ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "vezril",
  sys.env
    .get("LEXICON_TOKEN")
    .filter(_.nonEmpty)
    .orElse(sys.env.get("GITHUB_TOKEN"))
    .getOrElse("")
)

// Aligned so pekko-projection (which pulls pekko 1.2.x / r2dbc 1.1.x) does not
// create a mixed-version classpath (Pekko forbids that).
lazy val pekkoVersion = "1.2.0"
lazy val pekkoHttpVersion = "1.2.0"
lazy val pekkoR2dbcVersion = "1.1.0"
lazy val pekkoProjectionVersion = "1.1.0"
lazy val pekkoManagementVersion = "1.2.1"
lazy val scalaTestVersion = "3.2.19"
lazy val testcontainersVersion = "0.43.0"
lazy val logbackVersion = "1.5.12"
lazy val prometheusVersion = "0.16.0"

// Netty arrives transitively (gRPC/HTTP machinery), and 4.1.112.Final carries
// CVE-2026-75595 — a CRITICAL SNI routing bypass via a fragmented TLS ClientHello, which the
// release Trivy scan correctly refused to publish. Apollo terminates TLS and serves gRPC, so this
// is on a live path, not dormant.
//
// Overridden as a whole set, not just netty-handler: Netty does not support a mixed-version
// classpath any more than Pekko does, and 14 artifacts resolve here. Staying on the 4.1.x line
// (4.1.137.Final) rather than jumping to 4.2.x keeps this a patch-level change.
lazy val nettyVersion = "4.1.137.Final"
lazy val nettyOverrides = Seq(
  "netty-buffer",
  "netty-codec",
  "netty-codec-dns",
  "netty-codec-http",
  "netty-codec-socks",
  "netty-common",
  "netty-handler",
  "netty-handler-proxy",
  "netty-resolver",
  "netty-resolver-dns",
  "netty-resolver-dns-classes-macos",
  "netty-transport",
  "netty-transport-classes-epoll",
  "netty-transport-native-unix-common",
  // The two native, classifier-carrying artifacts. They are not the vulnerable module, but they
  // ship JNI binaries that pair with the Java classes above — epoll is the one that actually runs
  // in the Linux image, so leaving it a minor behind the core is a real skew, not a cosmetic one.
  "netty-transport-native-epoll",
  "netty-resolver-dns-native-macos"
).map(a => "io.netty" % a % nettyVersion)

// Fork tests so Pekko/JVM system properties and testcontainers behave predictably.
lazy val commonSettings = Seq(
  Test / fork := true,
  Test / testForkedParallel := false,
  scalafmtOnCompile := false,
  // Production code gets the full strict set; tests relax the two discard checks — ScalaTest
  // matchers return `Assertion` and `.futureValue` results are routinely used for effect, so
  // -Wnonunit-statement / -Wvalue-discard there is noise that fights the test DSL.
  Test / scalacOptions --= Seq("-Wnonunit-statement", "-Wvalue-discard"),
  // docker-java advertises Engine API 1.32 unless told otherwise, and any current Docker Engine
  // rejects anything below 1.40 — which aborted every testcontainers suite with
  // "client version 1.32 is too old". The lever is docker-java's `api.version` *system property*;
  // the DOCKER_API_VERSION environment variable is NOT read (measured: with the env var set the
  // client still requested /v1.32/info; with this property it requests /v1.43/info). Tests are
  // forked, so it has to be a Test/javaOption rather than an sbt JVM flag.
  Test / javaOptions += "-Dapi.version=1.43",
  dependencyOverrides ++= nettyOverrides
)

lazy val root = (project in file("."))
  .aggregate(core, server)
  .settings(
    name := "apollostorage",
    publish / skip := true
  )

// --- core: pure domain, no Pekko. -----------------------------------------
lazy val core = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "apollostorage-core",
    // Measures 96.88% under CI's whole-build `coverage test coverageReport`, but only 92.97% from
    // `core/test` alone — server tests also exercise core classes, so the number depends on how it
    // is invoked. The floor is set below *both* so it cannot fire spuriously on whichever command
    // someone happens to run; 95 looked right against the CI figure and broke `core/test`.
    coverageMinimumStmtTotal := 90,
    coverageFailOnMinimum := true,
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test,
      "org.scalacheck" %% "scalacheck" % "1.18.1" % Test
    )
  )

// --- server: Pekko runtime + persistence. ---------------------------------
lazy val server = (project in file("server"))
  .dependsOn(core)
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin, PekkoGrpcPlugin)
  .settings(commonSettings)
  .settings(
    name := "apollostorage-server",
    // Measured 72.61% once S3BlobStoreIT was restored (it was 66.49% while that suite was parked —
    // which is why this floor was deliberately set *after* the restoration, not before, or it would
    // have been pinned six points low). 70 leaves room for the integration suites' run-to-run
    // variation without being decorative.
    //
    // Statement only, on purpose. Branch coverage here is 56.74%, and gating two correlated metrics
    // doubles the false-failure surface for little extra signal. Raise this as coverage improves;
    // a floor that never moves is just a comment.
    coverageMinimumStmtTotal := 70,
    coverageFailOnMinimum := true,
    Compile / mainClass := Some("apollostorage.Main"),
    // The Apollo object API stubs come from the Lexicon artifact; the only .proto
    // generated locally is the standard health service (design D47). The plugin
    // stays enabled for health; power APIs keep parity with the Lexicon's codegen.
    pekkoGrpcGeneratedSources := Seq(PekkoGrpc.Server, PekkoGrpc.Client),
    pekkoGrpcCodeGeneratorSettings += "server_power_apis",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      // Clustering (design D27-D30): membership, sharded entities, formation.
      "org.apache.pekko" %% "pekko-cluster-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
      // S3 blob backend (add-s3-backend-and-rest-api): Alpakka S3, pinned to pekko 1.2.x. It
      // transitively pulls pekko-http-xml 1.1.0, so pin that to pekkoHttpVersion too — otherwise
      // Pekko's no-mixed-versions guard fails at runtime (spike-verified).
      "org.apache.pekko" %% "pekko-connectors-s3" % "1.2.0",
      "org.apache.pekko" %% "pekko-http-xml" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-r2dbc" % pekkoR2dbcVersion,
      // Apollo gRPC service stubs (server power API + client + messages), generated
      // from the shared contract in the Lexicon (design D46). Pinned SemVer.
      "io.codex" %% "lexicon-grpc" % "0.1.0",
      // Postgres r2dbc driver (transitive in r2dbc 1.0.0, explicit since 1.1.0).
      "org.postgresql" % "r2dbc-postgresql" % "1.0.7.RELEASE",
      // Prometheus metrics (design D40): app CollectorRegistry, JVM collectors, text exposition.
      "io.prometheus" % "simpleclient" % prometheusVersion,
      "io.prometheus" % "simpleclient_hotspot" % prometheusVersion,
      "io.prometheus" % "simpleclient_common" % prometheusVersion,
      // Read-side projections (design D21): fold the journal into query tables.
      "org.apache.pekko" %% "pekko-projection-r2dbc" % pekkoProjectionVersion,
      "org.apache.pekko" %% "pekko-projection-eventsourced" % pekkoProjectionVersion,
      // Align pekko-discovery (pulled transitively by pekko-grpc) with pekkoVersion;
      // Pekko forbids mixed artifact versions.
      "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
      // The kubernetes-api discovery implementation for Cluster Bootstrap in k8s
      // (DISCOVERY_METHOD=kubernetes-api). Without it, Bootstrap throws at startup — local/compose
      // use the `config` method so this path was never exercised until the first k8s deploy.
      "org.apache.pekko" %% "pekko-discovery-kubernetes-api" % pekkoManagementVersion,
      // Swagger UI assets served from the classpath by DocsRoutes (design D1). A webjar, not a
      // CDN: Apollo runs on a LAN with no assumed internet egress, so the docs must render offline.
      // The version is embedded in the resource path — keep it in step with DocsAssets.SwaggerUiVersion
      // (DocsPackagingSpec fails if they drift).
      "org.webjars" % "swagger-ui" % "5.25.3",
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      // Structured (JSON) logs for Loki + the self-healing loop (add-structured-logging).
      "net.logstash.logback" % "logstash-logback-encoder" % "8.0",
      // test
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
      "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVersion % Test,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-scalatest" % testcontainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersVersion % Test,
      // Test-only YAML parser so the docs spec test really parses the served document
      // (a substring check would pass on a malformed file).
      "org.yaml" % "snakeyaml" % "2.3" % Test,
      // JDBC driver used by tests to apply DDL and assert journal rows.
      "org.postgresql" % "postgresql" % "42.7.4" % Test
    ),
    // The served OpenAPI document is COPIED from docs/ at build time (design D2) rather than kept
    // as a second copy under resources/. docs/rest-api.openapi.yaml stays the single reviewed
    // source (the README links it and verify-docs.sh enforces that), and drift becomes impossible
    // instead of merely discouraged.
    Compile / resourceGenerators += Def.task {
      val source = (ThisBuild / baseDirectory).value / "docs" / "rest-api.openapi.yaml"
      val target = (Compile / resourceManaged).value / "openapi" / "rest-api.openapi.yaml"
      if (!source.exists)
        sys.error(s"OpenAPI document not found at $source — the docs portal cannot be packaged")
      IO.copyFile(source, target)
      Seq(target)
    }.taskValue,
    // BuildInfo exposes the dynver version to the running app (health endpoint).
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "apollostorage.build",
    buildInfoOptions += BuildInfoOption.ToJson,
    // --- Docker (sbt-native-packager) — service-runtime spec. ---
    dockerBaseImage := "eclipse-temurin:21-jre",
    dockerRepository := sys.env.get("DOCKERHUB_USERNAME"),
    dockerUpdateLatest := false, // release workflow controls :latest explicitly
    Docker / packageName := "apollostorage",
    dockerExposedPorts := Seq(8080, 8443),
    dockerEnvVars := Map("HTTP_PORT" -> "8080", "GRPC_PORT" -> "8443", "LOG_FORMAT" -> "json"),
    // Non-root user (packager default UID 1001) + HEALTHCHECK against /health.
    Docker / daemonUserUid := Some("1001"),
    Docker / daemonUser := "apollo",
    // HEALTHCHECK uses bash's /dev/tcp so no extra packages (wget/curl) are
    // needed in the JRE base image. Exec form ([...]) keeps the whole script as
    // a single argument to `bash -c`; bash expands the HTTP_PORT override.
    dockerCommands ++= Seq(
      com.typesafe.sbt.packager.docker.Cmd(
        "HEALTHCHECK",
        "--interval=10s --timeout=3s --start-period=20s --retries=5 CMD " +
          """["bash","-c","exec 3<>/dev/tcp/127.0.0.1/${HTTP_PORT:-8080}; """ +
          """printf 'GET /health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3; """ +
          """grep -q '200 OK' <&3"]"""
      )
    ),
    // Create the default blob root owned by the non-root user, as root, before
    // the USER switch. A fresh Docker volume mounted here inherits this ownership,
    // and with no mount the directory still exists and is writable — so the blob
    // readiness probe passes out of the box (blob-storage spec / design D14).
    dockerCommands := {
      val cmds = dockerCommands.value
      val mkBlobRoot = com.typesafe.sbt.packager.docker.Cmd(
        "RUN",
        "mkdir -p /var/lib/apollostorage/objects && chown -R 1001:0 /var/lib/apollostorage/objects"
      )
      // Insert before the final USER switch (the mainstage non-root user), so the
      // directory is created as root in the image that actually ships.
      val userIdx = cmds.lastIndexWhere {
        case com.typesafe.sbt.packager.docker.Cmd("USER", _*) => true
        case _ => false
      }
      if (userIdx >= 0) cmds.patch(userIdx, Seq(mkBlobRoot), 0) else cmds :+ mkBlobRoot
    }
  )
