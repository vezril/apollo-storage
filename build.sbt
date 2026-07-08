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
  "-Wunused:all",
  "-Wvalue-discard",
  "-source:3.3",
  "-Yretain-trees"
)

// Aligned so pekko-projection (which pulls pekko 1.2.x / r2dbc 1.1.x) does not
// create a mixed-version classpath (Pekko forbids that).
lazy val pekkoVersion = "1.2.0"
lazy val pekkoHttpVersion = "1.2.0"
lazy val pekkoR2dbcVersion = "1.1.0"
lazy val pekkoProjectionVersion = "1.1.0"
lazy val pekkoManagementVersion = "1.2.1"
lazy val scalaTestVersion = "3.2.19"
lazy val testcontainersVersion = "0.41.4"
lazy val logbackVersion = "1.5.12"
lazy val prometheusVersion = "0.16.0"

// Fork tests so Pekko/JVM system properties and testcontainers behave predictably.
lazy val commonSettings = Seq(
  Test / fork := true,
  Test / testForkedParallel := false,
  scalafmtOnCompile := false
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
    Compile / mainClass := Some("apollostorage.Main"),
    // Generate both the server powerapi and the client (client used by tests).
    pekkoGrpcGeneratedSources := Seq(PekkoGrpc.Server, PekkoGrpc.Client),
    // Power APIs expose request metadata to handlers (for bearer-token auth, D35).
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
      "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-r2dbc" % pekkoR2dbcVersion,
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
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      // test
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
      "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVersion % Test,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-scalatest" % testcontainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersVersion % Test,
      // JDBC driver used by tests to apply DDL and assert journal rows.
      "org.postgresql" % "postgresql" % "42.7.4" % Test
    ),
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
    dockerEnvVars := Map("HTTP_PORT" -> "8080", "GRPC_PORT" -> "8443"),
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
