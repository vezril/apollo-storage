# Tasks — fix-netty-cve

## 1. Triage

- [x] 1.1 Confirm the finding is real and actionable: CVE-2026-75595, CRITICAL, **fixed** status,
  in `netty-handler` — not unfixable base-image noise (Trivy runs `ignore-unfixed`)
- [x] 1.2 Confirm it is on a live path, not dormant: Apollo terminates TLS and serves gRPC, and the
  CVE is an SNI routing bypass via fragmented TLS ClientHello
- [x] 1.3 Choose the 4.1.x fix (`4.1.137.Final`) over 4.2.x, keeping the change patch-level

## 2. Fix

- [x] 2.1 Enumerate the Netty artifacts actually on the runtime classpath — 14 plain, plus 2 native
  with classifiers
- [x] 2.2 `dependencyOverrides` for the **whole set**, not just `netty-handler`; a mixed-version
  Netty classpath trades a CVE for a skew bug
- [x] 2.3 Catch the native artifacts a first pass missed — `netty-transport-native-epoll`
  (`linux-x86_64`, the one that runs in the image) and `netty-resolver-dns-native-macos`

## 3. Verify

- [x] 3.1 All 16 artifacts at `4.1.137.Final`; zero `4.1.112` references on the runtime classpath
- [x] 3.2 Gates clean: `-Werror` compile, `scalafixAll --check`, `scalafmtCheckAll`
- [x] 3.3 215 non-container tests green locally
- [x] 3.4 CI: gRPC and TLS integration suites pass against the new Netty — green on #50
- [x] 3.5 Release: the Trivy scan comes back clean and the image publishes — v0.16.0, scan clean
  with zero CRITICALs, `calvinference/apollostorage:0.16.0` published and `latest` moved. First
  successful publish since 2026-08-28
