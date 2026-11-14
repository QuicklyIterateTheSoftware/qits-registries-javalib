-- The blob store's tables. PostgreSQL only — this library has no other backend.
--
-- COPY THIS FILE VERBATIM INTO YOUR OWN FLYWAY LINEAGE. A library owns no schema and carries no
-- migrations: the service that owns the database owns the migration chain. Paste these statements
-- into a migration of your own (keeping the text identical makes a later diff readable), point
-- `qits.artifacts.blobs-datasource` at the datasource that reaches them, and you are done.
--
-- THIS IS THE FRESH-INSTALL SHAPE. A database that already holds the pre-segment tables (blob_chunk
-- as one plain table, no blob_segment) does not run this file: it runs
-- db/blobstore-segments-upgrade.sql, which ends in exactly this shape without rewriting a byte.
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

-- One row per 1 MiB slice, list-partitioned by SEGMENT so that disk can be given back.
--
-- Why partitions at all: PostgreSQL never returns the space a delete frees to the filesystem — it
-- marks it reusable and keeps the file, so a store that deleted 80% of its blobs still occupies its
-- peak. VACUUM FULL and pg_repack get the space back only by rewriting the whole table, which needs
-- free disk equal to the live data and, for VACUUM FULL, an ACCESS EXCLUSIVE lock for as long as the
-- copy takes. A partition is different: DROP TABLE on it unlinks its files, instantly and whole. So
-- chunks are written into one OPEN segment at a time (blob_segment names it); the store rolls to a
-- fresh partition once the open one passes `qits.artifacts.blobs.segment-size`; and compaction
-- (BlobCompaction) moves the few live chunks out of a mostly-dead closed segment and drops it.
--
-- The primary key carries `segment` because PostgreSQL requires the partition key in every unique
-- constraint of a partitioned table — and it goes LAST, so each partition's key index still serves
-- `where content_id = ? and seq = ?` by its prefix. Reads name no segment: they probe every
-- partition, one index lookup each, and compaction is what keeps that list short.
--
-- STORAGE EXTERNAL skips TOAST compression: blob content is already compressed (OCI layers, npm
-- tarballs, git packs), so compressing again costs CPU for nothing. It is set on the parent, and
-- every partition inherits it — whether created as PARTITION OF, as below, or as LIKE ... INCLUDING
-- ALL and attached, which is how the store rolls.
create table blob_chunk (
    content_id  uuid not null,
    seq         integer not null,
    bytes       bytea not null,
    segment     integer not null,
    constraint blob_chunk_pkey primary key (content_id, seq, segment),
    constraint blob_chunk_content_id_fkey foreign key (content_id)
        references blob_content (content_id) on delete cascade
) partition by list (segment);
alter table blob_chunk alter column bytes set storage external;

-- The open segment: the one partition every new chunk is written into. Exactly one row, which the
-- `singleton` key and its check make structural rather than a convention. A chunk insert reads it in
-- the same statement that writes the chunk; the store's roll moves it forward, in the transaction
-- that attaches the next partition.
--
-- Partitions are named blob_chunk_<segment> and the store relies on that name when it rolls.
-- Segment 0 is reserved for the chunks an upgraded database held before segmentation (see
-- db/blobstore-segments-upgrade.sql), so a fresh install opens at 1 — the two shapes then differ
-- only in whether a legacy segment 0 exists, and the store never needs to know which it is on.
create table blob_segment (
    singleton  boolean primary key default true check (singleton),
    segment    integer not null
);
create table blob_chunk_1 partition of blob_chunk for values in (1);
insert into blob_segment (segment) values (1);

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
