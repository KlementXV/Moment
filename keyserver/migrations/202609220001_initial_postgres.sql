-- All timestamps use Unix seconds, matching the signed Android protocol.
-- Only encrypted post keys and SHA-256 session hashes are persisted.
CREATE TABLE metadata (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    marker BYTEA NOT NULL
);
CREATE TABLE challenges (
    nonce TEXT PRIMARY KEY,
    wallet TEXT NOT NULL,
    message TEXT NOT NULL,
    expires BIGINT NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX challenges_expires ON challenges (expires);
CREATE TABLE sessions (
    token_hash BYTEA PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    wallet TEXT NOT NULL,
    expires BIGINT NOT NULL
);
CREATE INDEX sessions_expires ON sessions (expires);
CREATE TABLE posts (
    commitment TEXT COLLATE "C" PRIMARY KEY,
    wallet TEXT NOT NULL,
    day BIGINT NOT NULL,
    blob_ref TEXT NOT NULL UNIQUE,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    wrapped_key BYTEA NOT NULL CHECK (octet_length(wrapped_key) = 60),
    created BIGINT NOT NULL,
    UNIQUE (wallet, day)
);
CREATE INDEX posts_feed ON posts (day, commitment) WHERE published;
