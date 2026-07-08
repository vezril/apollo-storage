# domain-model — Spec Delta

All requirements in this capability live in the pure `core` module: no Pekko dependencies, immutable data, ADTs, smart constructors returning `Either[DomainError, A]`. Exhaustively unit-tested.

## ADDED Requirements

### Requirement: Validated bucket names

`BucketName` SHALL be constructible only through a smart constructor enforcing GCS-inspired rules: 3–63 characters, lowercase letters, digits, hyphens; must start and end with a letter or digit. Invalid input returns a typed error, never throws.

#### Scenario: Valid name accepted
- **Given** the input `"media-archive-01"`
- **When** `BucketName.from` is called
- **Then** the result is `Right(BucketName("media-archive-01"))`

#### Scenario: Edge case — uppercase rejected
- **Given** the input `"MediaArchive"`
- **When** `BucketName.from` is called
- **Then** the result is `Left(InvalidBucketName)` with a message naming the violated rule

#### Scenario: Edge case — boundary lengths
- **Given** inputs of length 2, 3, 63, and 64
- **When** `BucketName.from` is called on each
- **Then** lengths 3 and 63 are `Right` and lengths 2 and 64 are `Left(InvalidBucketName)`

### Requirement: Validated, traversal-safe object names

`ObjectName` SHALL be constructible only through a smart constructor: 1–1024 bytes UTF-8, no `NUL`, and SHALL reject any name that could escape the bucket directory on a filesystem backend — path segments `.` or `..`, leading `/`, or backslashes.

#### Scenario: Nested key accepted
- **Given** the input `"photos/2026/07/dive-log.jpg"`
- **When** `ObjectName.from` is called
- **Then** the result is `Right`

#### Scenario: Edge case — path traversal rejected
- **Given** the inputs `"../../etc/passwd"`, `"a/../b"`, and `"/absolute"`
- **When** `ObjectName.from` is called on each
- **Then** every result is `Left(InvalidObjectName)`

#### Scenario: Edge case — oversized name rejected
- **Given** an input of 1025 bytes (including multi-byte UTF-8 measured in bytes, not chars)
- **When** `ObjectName.from` is called
- **Then** the result is `Left(InvalidObjectName)`

### Requirement: Command and event ADTs for the bucket aggregate

The domain SHALL define sealed command and event hierarchies sufficient for this milestone — commands: `CreateBucket`, `DeleteBucket`, `CommitObject` (metadata + checksums + blob reference), `DeleteObject`; events: `BucketCreated`, `BucketDeleted`, `ObjectCommitted`, `ObjectDeleted` — each event carrying the data needed to rebuild state (names, `Generation`, `ObjectMetadata`, `Checksums(crc32c, md5)`, timestamps).

#### Scenario: Exhaustive handling enforced by the compiler
- **Given** the sealed hierarchies
- **When** a state-transition function pattern-matches on them
- **Then** the match is exhaustive (compiler-verified; a test asserts handlers exist for every constructor)

#### Scenario: Edge case — events are self-contained
- **Given** any event instance
- **When** state is folded from the event alone plus prior state
- **Then** no external lookup is required to apply it (verified by rebuilding state purely from an event list)

#### Scenario: Edge case — commands carry validated types only
- **Given** the command constructors
- **When** inspected by type
- **Then** they accept `BucketName`/`ObjectName`/`Checksums` value types, never raw `String`s (unvalidated input cannot enter the aggregate)

### Requirement: Pure state transitions with invariants

Bucket state transitions SHALL be pure functions `(State, Command) => Either[DomainError, Seq[Event]]` and `(State, Event) => State`, enforcing: no operations on non-existent or deleted buckets, no duplicate bucket creation, per-object generation numbers that start at 1 and increase monotonically on each commit, and no deletion of non-existent objects.

#### Scenario: Object commit increments generation
- **Given** a bucket where `"a.txt"` exists at generation 1
- **When** `CommitObject("a.txt", …)` is handled
- **Then** an `ObjectCommitted` event with generation 2 is produced

#### Scenario: Edge case — duplicate bucket creation rejected
- **Given** state where the bucket already exists
- **When** `CreateBucket` is handled
- **Then** the result is `Left(BucketAlreadyExists)` and no event is produced

#### Scenario: Edge case — command after bucket deletion rejected
- **Given** state folded from `[BucketCreated, BucketDeleted]`
- **When** `CommitObject` is handled
- **Then** the result is `Left(BucketNotFound)` and generation counters from the previous life are not resurrected
