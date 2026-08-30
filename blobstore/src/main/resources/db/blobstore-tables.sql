-- The blob store's three tables. PostgreSQL only — this library has no other backend.
--
-- COPY THIS FILE VERBATIM INTO YOUR OWN FLYWAY LINEAGE. A library owns no schema and carries no
-- migrations: the service that owns the database owns the migration chain. Paste these statements
-- into a migration of your own (keeping the text identical makes a later diff readable), point
-- `qits.artifacts.blobs-datasource` at the datasource that reaches them, and you are done.
--
-- This library's own test suite applies THIS FILE, unedited, so the DDL below is exercised on every
-- build rather than being prose that drifts.

-- Content addressed by a surrogate id, so STAGING and PROMOTED bytes share one chunk table and
-- promote is a state flip rather than a copy. The cascade makes "discard a staging area" one
-- statement.
create table blob_content (
    content_id  uuid primary key,
    state       varchar(16) not null check (state in ('STAGING', 'PROMOTED')),
    started_at  timestamptz not null
);

-- One row per 1 MiB slice. STORAGE EXTERNAL skips TOAST compression: blob content is already
-- compressed (OCI layers, npm tarballs, git packs), so compressing again costs CPU for nothing.
create table blob_chunk (
    content_id  uuid not null references blob_content (content_id) on delete cascade,
    seq         integer not null,
    bytes       bytea not null,
    primary key (content_id, seq)
);
alter table blob_chunk alter column bytes set storage external;

-- The identity row: a SHA-256 content address bound to one content. `stored_at` replaces the file
-- mtime the store used to read for the garbage-collection grace window, and a dedupe does NOT
-- refresh it — parity with the old promote(), which was a no-op when the file already existed.
--
-- varchar, not char: PostgreSQL pads char to its full width. The check restates the store's
-- path-traversal defence at the table; the same rule stays in code.
create table blob (
    id          varchar(64) primary key check (id ~ '^[0-9a-f]{64}$'),
    content_id  uuid not null unique references blob_content (content_id),
    size_bytes  bigint not null,
    chunk_size  integer not null,
    stored_at   timestamptz not null
);

-- The staging sweep reads only STAGING rows, and they are a tiny minority of this table.
create index idx_blob_content_staging on blob_content (started_at) where state = 'STAGING';
