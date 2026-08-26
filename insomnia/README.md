# Insomnia collection — ApolloStorage API

`apollostorage.insomnia_collection.json` is an Insomnia v4 export targeting a running
ApolloStorage instance. It covers the whole HTTP surface — the **REST object API** (`/v1/…`),
the operational endpoints (health, metrics, admin blob-GC), and the built-in API docs — plus the
gRPC `ObjectApi` (with `object_api.proto` embedded, so no reflection or external file is needed).

It is kept in step with [`docs/rest-api.openapi.yaml`](../docs/rest-api.openapi.yaml): every
documented path is exercised by at least one request, and `scripts/verify-docs.sh` checks that.

## Import

Insomnia → **Create / Import → File**, pick `apollostorage.insomnia_collection.json`.
Select the **Local (dev)** environment (top-left environment dropdown).

## Environment variables

Nothing is hard-coded — retargeting is an environment change, never a per-request edit.

| Var | Default | Notes |
|-----|---------|-------|
| `base_url` | `http://localhost:8080` | HTTP host (`HTTP_PORT`) |
| `grpc_url` | `localhost:8443` | gRPC host (`GRPC_PORT`, h2c cleartext) |
| `auth_token` | *(empty)* | Bearer token; only needed when `AUTH_ENABLED=true` |
| `bucket` | `demo-bucket` | Sample bucket name used by the `/v1` requests |
| `object` | `hello.txt` | Sample object name used by the `/v1` requests |

**Retargeting.** Point `base_url` at whichever deployment you mean and everything follows:

| Target | `base_url` |
|---|---|
| Local | `http://localhost:8080` |
| In-cluster (port-forward) | `http://localhost:8080` after `kubectl -n apollo port-forward svc/apollo 8080:8080` |
| LAN / tailnet | `http://<host>:8080` (or `https://…` when `TLS_ENABLED=true`) |

## Folders

**REST object API (`/v1`)** — the object lifecycle over HTTP:

| Request | Notes |
|---|---|
| List buckets | `pageSize` / `pageToken` params are present but disabled |
| Create / Delete bucket | Write scope. Delete requires the bucket to be **empty** |
| List objects | Served from the read model — **eventually consistent**, so a fresh upload takes a moment to appear |
| Upload object | Streams the **raw body** (not multipart). Ships a small text body; switch the body type to **File** to upload a real file |
| Download / Head object | Metadata comes back as `X-Apollo-*` headers |
| Delete object | Write scope. Destructive |

To have the server verify an upload, enable the `X-Apollo-Crc32c` **and** `X-Apollo-Md5` headers on
*Upload object* — a mismatch is rejected with `412` and nothing is stored.

**HTTP (runnable)** — health, metrics, blob-GC, and the API docs. Open the folder → **Run** to
execute the safe ones in order. The runner does not drive gRPC.

- `Blob GC — dry run` / `delete` return **404** unless the server runs with `BLOB_GC_ENABLED=true`.
  The `delete` variant is destructive; the dry run only reports.
- `API docs (Swagger UI)` returns HTML — open `{base_url}/docs` in a browser instead of reading the
  response here. `OpenAPI document` returns the spec itself.
- Health, metrics, and both docs requests need **no** token.

**gRPC — ObjectApi** — the full object lifecycle. `PutObject` is client-streaming (first message is
the `header` shown; then send `chunk` messages — bytes are base64) and `GetObject` is
server-streaming (a metadata `header`, then `chunk`s). To authenticate, enable the request's
`authorization` metadata row.

## Auth

With `AUTH_ENABLED=true`, set `auth_token` and every `/v1` and admin request sends
`Authorization: Bearer …`. Mutations need a **write**-scoped token (`401` if missing/invalid,
`403` if read-scoped); reads accept a read-scoped one. With auth disabled the header is ignored.

## Keeping it current

Adding, removing, or reshaping an HTTP endpoint means updating this collection **in the same
change** — otherwise it silently presents a subset of the API as though it were the whole, which is
exactly how it fell a release behind before. See [`AGENTS.md`](../AGENTS.md).
