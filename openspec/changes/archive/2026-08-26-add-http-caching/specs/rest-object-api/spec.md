# rest-object-api

## ADDED Requirements

### Requirement: Cache validators on object reads

Object `GET` and `HEAD` responses SHALL carry a strong `ETag` derived from the object's stored md5,
so a client can revalidate a copy it already holds. The same object SHALL yield the same validator on
`GET` and on `HEAD`, and overwriting an object with different content SHALL change it.

#### Scenario: A successful read carries a validator

- **WHEN** an object is fetched with `GET`
- **THEN** the response is `200` and carries an `ETag` derived from the object's md5

#### Scenario: HEAD and GET agree

- **WHEN** the same object is fetched with `HEAD` and with `GET`
- **THEN** both responses carry the same `ETag`

#### Scenario: Overwriting an object changes its validator

- **GIVEN** an object that has been read once
- **WHEN** it is overwritten with different content and read again
- **THEN** the `ETag` differs from the one observed before the overwrite

#### Scenario: Edge case — an overwrite with identical bytes keeps the validator

- **GIVEN** an object that has been read once
- **WHEN** it is overwritten with byte-identical content and read again
- **THEN** the `ETag` is unchanged, because the representation a client holds is still current

### Requirement: Conditional object reads

Object `GET` and `HEAD` SHALL honour `If-None-Match`. When a supplied validator matches the object's
current validator, the service SHALL respond `304 Not Modified` with no body, and SHALL resolve the
answer from object metadata **without reading the blob store**. When it does not match, the request
SHALL be served normally.

#### Scenario: A matching validator is answered without a body

- **GIVEN** a client holding the current `ETag` for an object
- **WHEN** it sends `GET` with `If-None-Match` set to that value
- **THEN** the response is `304` with no body, and no blob-store read is performed

#### Scenario: A stale validator is served normally

- **GIVEN** a client holding an `ETag` from before the object was overwritten
- **WHEN** it sends `GET` with `If-None-Match` set to that value
- **THEN** the response is `200` with the current content and the current `ETag`

#### Scenario: A conditional read of a missing object is not found

- **WHEN** `GET` with `If-None-Match` names an object that does not exist
- **THEN** the response is `404`, not `304`

#### Scenario: Edge case — an unconditional read is unaffected

- **WHEN** an object is fetched with no `If-None-Match` header
- **THEN** the response is `200` with the full body, exactly as before this capability existed

### Requirement: Truthful cache directives on the object API

Object responses SHALL declare cache directives consistent with Apollo's mutability: a stored object
is reachable at a stable path whose content can be replaced, so responses SHALL require revalidation
before reuse and SHALL NOT be marked immutable or given a positive freshness lifetime. Responses
SHALL be marked as not storable by shared caches, since object reads are authenticated when
authentication is enabled.

Error responses SHALL be marked non-storable, so that a client which reads an object before it exists
does not retain that failure.

#### Scenario: Object reads require revalidation

- **WHEN** an object is fetched
- **THEN** the response's `Cache-Control` requires revalidation before reuse and is scoped to a
  private cache, and declares neither `immutable` nor a positive `max-age`

#### Scenario: A not-found response is not storable

- **WHEN** a read names an object that does not exist
- **THEN** the `404` response declares that it must not be stored
