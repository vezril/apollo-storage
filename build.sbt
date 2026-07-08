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

lazy val pekkoVersion = "1.1.3"
lazy val pekkoHttpVersion = "1.1.0"
lazy val pekkoR2dbcVersion = "1.0.0"
lazy val scalaTestVersion = "3.2.19"
lazy val testcontainersVersion = "0.41.4"
lazy val logbackVersion = "1.5.12"

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
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    name := "apollostorage-server",
    Compile / mainClass := Some("apollostorage.Main"),
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-r2dbc" % pekkoR2dbcVersion,
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
    dockerExposedPorts := Seq(8080),
    dockerEnvVars := Map("HTTP_PORT" -> "8080"),
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
    )
  )
