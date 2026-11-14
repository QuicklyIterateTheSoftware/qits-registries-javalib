-- Upgrade: the pre-segment blob tables to the segmented shape of db/blobstore-tables.sql.
-- PostgreSQL only.
--
-- COPY THIS FILE VERBATIM INTO A MIGRATION OF YOUR OWN, after the one that created the blob tables
-- from the earlier db/blobstore-tables.sql. A fresh install never runs it: it runs the current
-- db/blobstore-tables.sql instead, and both end in the same shape — except that an upgraded
-- database carries one extra partition, blob_chunk_0, holding every chunk it stored before.
--
-- RELEASE ORDER: either. A library from qits-2248 on detects whether blob_segment exists and, while
-- it does not, writes and reads exactly as before — so the bump can ship ahead of this migration. A
-- library OLDER than that keeps working on an upgraded database too, which a start-first deploy
-- depends on (the previous instance serves uploads for a while after the new one's Flyway ran): it
-- inserts chunks without a segment, and the column's default, blob_open_segment(), puts them in the
-- open segment. What an old library cannot do is roll or compact — only the new one does either.
--
-- WHAT IT COSTS. Nothing here rewrites a chunk. Blob bytes live in blob_chunk's TOAST relation; the
-- heap holds one small row per MiB (key plus TOAST pointer), and that heap is all any statement
-- below reads:
--
--   * the rename, the constraint renames and the drop default are catalog updates;
--   * `add column ... not null default 0` is metadata only (PostgreSQL 11+ stores the default once
--     in the catalog and every existing row reads it), so no row is touched;
--   * the new key index is built from the heap — one index entry per MiB of blob;
--   * ATTACH PARTITION checks every row against `segment = 0`, again a heap scan.
--
-- A 100 GiB store is ~100k heap rows, which is well under a second for each scan. The whole file
-- holds ACCESS EXCLUSIVE on the chunk table for that long, so blob reads and writes pause for it and
-- nothing else does.
--
-- NAMES. The constraint names below are what the earlier DDL's statements generated
-- (blob_chunk_pkey, blob_chunk_content_id_fkey). A database whose chunk table carries different
-- names fails at the first statement that names one, inside the migration's transaction — loudly,
-- with nothing half-done.

-- The existing table becomes segment 0, the partition for everything stored before segmentation.
-- Its key and foreign key are renamed out of the way: the parent created below takes the original
-- names, so the two shapes agree on them, and an index name must be unique across the schema.
alter table blob_chunk rename to blob_chunk_0;
alter table blob_chunk_0 rename constraint blob_chunk_content_id_fkey to blob_chunk_0_content_id_fkey;
alter table blob_chunk_0 add column segment integer not null default 0;
alter table blob_chunk_0 alter column segment drop default;

-- The key gains `segment` (last, so `where content_id = ? and seq = ?` still uses it by prefix).
-- Built as an index first and promoted to the primary key, because ATTACH adopts a partition's key
-- only when it is a constraint of the same kind as the parent's.
create unique index blob_chunk_0_pkey on blob_chunk_0 (content_id, seq, segment);
alter table blob_chunk_0 drop constraint blob_chunk_pkey;
alter table blob_chunk_0 add constraint blob_chunk_0_pkey primary key using index blob_chunk_0_pkey;

-- First the open segment and its default, as in db/blobstore-tables.sql: the parent below names the
-- function, so both must exist before it. The open segment is the one partition every new chunk is
-- written into. Exactly one row, which the `singleton` key and its check make structural rather than
-- a convention. The store's roll moves it forward, in the transaction that attaches the next
-- partition.
create table blob_segment (
    singleton  boolean primary key default true check (singleton),
    segment    integer not null
);

-- What blob_chunk.segment defaults to: the open segment, read when the row is inserted. VOLATILE, so
-- it is evaluated per row and never folded into a plan.
--
-- The default exists for writers that do not name a segment, and the one that matters is a library
-- older than qits-2248: consumers deploy start-first, so the previous instance keeps serving uploads
-- for a minute or more after the new instance's Flyway has migrated, inserting
-- `(content_id, seq, bytes)` as it always did. With this default those rows land in the open segment
-- like any other. The current library names the segment explicitly and gets the same answer.
create function blob_open_segment() returns integer language sql volatile as $$
    select segment from blob_segment
$$;

-- The parent, statement for statement as in db/blobstore-tables.sql — see the reasoning there.
create table blob_chunk (
    content_id  uuid not null,
    seq         integer not null,
    bytes       bytea not null,
    segment     integer not null default blob_open_segment(),
    constraint blob_chunk_pkey primary key (content_id, seq, segment),
    constraint blob_chunk_content_id_fkey foreign key (content_id)
        references blob_content (content_id) on delete cascade
) partition by list (segment);
alter table blob_chunk alter column bytes set storage external;

-- ATTACH adopts blob_chunk_0's key index and its foreign key as the partition's halves of the
-- parent's — same columns, same referenced table, same cascade — so neither is rebuilt or
-- revalidated. It scans the heap once to prove every row belongs to segment 0, which the missing
-- value of the new column guarantees.
alter table blob_chunk attach partition blob_chunk_0 for values in (0);

-- And the first open segment, exactly as a fresh install opens it. Segment 0 is closed from here:
-- nothing writes there again — an old library's inserts included, since the parent's default sends
-- them to the open segment — and compaction drains and drops it once its live share falls.
create table blob_chunk_1 partition of blob_chunk for values in (1);
insert into blob_segment (segment) values (1);
