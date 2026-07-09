-- Canonical Pekko Persistence R2DBC (1.0.0) Postgres schema — the single source of
-- truth, bundled in the image and on the test classpath. Apollo applies this itself at
-- startup (self-migration via DB_AUTO_MIGRATE, default on) and integration tests apply it too.
-- All statements are idempotent (CREATE ... IF NOT EXISTS) so re-running is a no-op.
-- NOTE the boot migrator splits on the statement terminator, so no statement may contain
-- that character inside a string literal or comment.

CREATE TABLE IF NOT EXISTS event_journal (
  slice INT NOT NULL,
  entity_type VARCHAR(255) NOT NULL,
  persistence_id VARCHAR(255) NOT NULL,
  seq_nr BIGINT NOT NULL,
  db_timestamp timestamp with time zone NOT NULL,

  event_ser_id INTEGER NOT NULL,
  event_ser_manifest VARCHAR(255) NOT NULL,
  event_payload BYTEA NOT NULL,

  deleted BOOLEAN DEFAULT FALSE NOT NULL,
  writer VARCHAR(255) NOT NULL,
  adapter_manifest VARCHAR(255),
  tags TEXT ARRAY,

  meta_ser_id INTEGER,
  meta_ser_manifest VARCHAR(255),
  meta_payload BYTEA,

  PRIMARY KEY (persistence_id, seq_nr)
);

CREATE INDEX IF NOT EXISTS event_journal_slice_idx
  ON event_journal (slice, entity_type, db_timestamp, seq_nr, persistence_id);

CREATE TABLE IF NOT EXISTS snapshot (
  slice INT NOT NULL,
  entity_type VARCHAR(255) NOT NULL,
  persistence_id VARCHAR(255) NOT NULL,
  seq_nr BIGINT NOT NULL,
  write_timestamp BIGINT NOT NULL,
  ser_id INTEGER NOT NULL,
  ser_manifest VARCHAR(255) NOT NULL,
  snapshot BYTEA NOT NULL,

  meta_ser_id INTEGER,
  meta_ser_manifest VARCHAR(255),
  meta_payload BYTEA,

  PRIMARY KEY (persistence_id)
);

CREATE TABLE IF NOT EXISTS durable_state (
  slice INT NOT NULL,
  entity_type VARCHAR(255) NOT NULL,
  persistence_id VARCHAR(255) NOT NULL,
  revision BIGINT NOT NULL,
  db_timestamp timestamp with time zone NOT NULL,

  state_ser_id INTEGER NOT NULL,
  state_ser_manifest VARCHAR(255),
  state_payload BYTEA NOT NULL,
  tags TEXT ARRAY,

  PRIMARY KEY (persistence_id, revision)
);

CREATE INDEX IF NOT EXISTS durable_state_slice_idx
  ON durable_state (slice, entity_type, db_timestamp, revision, persistence_id);
-- ---------------------------------------------------------------------------
-- Read side (add-read-projections): Pekko Projection offset store + read model.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS projection_offset_store (
  projection_name VARCHAR(255) NOT NULL,
  projection_key VARCHAR(255) NOT NULL,
  current_offset VARCHAR(255) NOT NULL,
  manifest VARCHAR(32) NOT NULL,
  mergeable BOOLEAN NOT NULL,
  last_updated BIGINT NOT NULL,
  PRIMARY KEY (projection_name, projection_key)
);

CREATE TABLE IF NOT EXISTS projection_timestamp_offset_store (
  slice INT NOT NULL,
  projection_name VARCHAR(255) NOT NULL,
  projection_key VARCHAR(255) NOT NULL,
  persistence_id VARCHAR(255) NOT NULL,
  seq_nr BIGINT NOT NULL,
  timestamp_offset timestamp with time zone NOT NULL,
  timestamp_consumed timestamp with time zone NOT NULL,
  PRIMARY KEY (slice, projection_name, timestamp_offset, persistence_id, seq_nr)
);

CREATE TABLE IF NOT EXISTS projection_management (
  projection_name VARCHAR(255) NOT NULL,
  projection_key VARCHAR(255) NOT NULL,
  paused BOOLEAN NOT NULL,
  last_updated BIGINT NOT NULL,
  PRIMARY KEY (projection_name, projection_key)
);

-- Read model: one row per bucket, one row per live object version.
CREATE TABLE IF NOT EXISTS bucket_index (
  bucket VARCHAR(63) PRIMARY KEY,
  created_at timestamp with time zone NOT NULL
);

CREATE TABLE IF NOT EXISTS object_index (
  bucket VARCHAR(63) NOT NULL,
  object_key VARCHAR(1024) NOT NULL,
  generation BIGINT NOT NULL,
  size_bytes BIGINT NOT NULL,
  content_type VARCHAR(255) NOT NULL,
  crc32c VARCHAR(64) NOT NULL,
  md5 VARCHAR(64) NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  PRIMARY KEY (bucket, object_key)
);
