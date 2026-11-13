# Change: fix-netty-cve

> Override the transitive Netty to `4.1.137.Final`, clearing **CVE-2026-75595** (CRITICAL) which
> blocked the `v0.15.0` publish.

## Why

The release Trivy scan refused to publish and was right to:

```
io.netty:netty-handler   CVE-2026-75595   CRITICAL   fixed
  installed: 4.1.112.Final
  fixed in:  4.2.17.Final, 4.1.137.Final
  Netty: SNI Routing Bypass via Fragmented TLS ClientHello Causing Fallback to…
```

This is not base-image noise and not dormant. Apollo **terminates TLS** (`TLS_ENABLED`,
`TlsContext`) and serves **gRPC**, and Netty arrives transitively under that machinery — an SNI
routing bypass via fragmented ClientHello sits directly on a live request path. Trivy is configured
`ignore-unfixed`, so a finding reaching the gate means a fix exists.

Worth recording: this was the gate's first real execution, and it earned its place immediately. The
previous release attempt never got this far — it died resolving a removed action ref.

## What Changes

- `build.sbt`: `dependencyOverrides` pinning **16** Netty artifacts to `4.1.137.Final`.

## Impact

- **Overridden as a whole set, not just `netty-handler`.** Netty tolerates a mixed-version classpath
  no better than Pekko does, and 16 artifacts resolve here. Bumping only the vulnerable module would
  trade a CVE for a skew bug.
- **Including the two native, classifier-carrying artifacts** — `netty-transport-native-epoll`
  (`linux-x86_64`) and `netty-resolver-dns-native-macos`. Neither is the vulnerable module, but they
  ship JNI binaries paired with the Java classes, and **epoll is what actually runs in the Linux
  image**. A first pass missed these and left them a minor version behind the core; verified
  resolved afterwards.
- Staying on the **4.1.x** line rather than jumping to 4.2.x keeps this patch-level. 4.2 is a
  different minor with its own API surface, and nothing here needs it.
- No source change, no API change, no behaviour change.

## Verification

- All 16 artifacts resolve at `4.1.137.Final`; **zero** references to `4.1.112` remain on the
  runtime classpath.
- Gates clean: compile and `Test/compile` under `-Werror`, `scalafixAll --check`, `scalafmtCheckAll`.
- 215 non-container tests green locally.
- **The assertion that matters is CI**, which runs the gRPC and TLS integration suites against the
  new Netty, and then the release Trivy scan, which must now come back clean.
