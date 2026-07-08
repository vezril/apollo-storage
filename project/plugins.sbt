// Version derived from git tags (design D6) — no version literal in source.
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.0")

// Docker image build (service-runtime spec: non-root, EXPOSE, HEALTHCHECK).
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.1")

// Formatting (project-scaffolding spec: CI runs scalafmtCheck).
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

// Static analysis / linting.
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.13.0")

// Build-time version info exposed to the app (health endpoint reports version).
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
