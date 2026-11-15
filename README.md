# qits-registries-javalib

The platform's **byte plane**: the content-addressed blob store, and the npm, maven and OCI
registry protocols written over it. Five library jars, one reactor, one version.

Both halves came out of `qits-platform-artifacts` (phase 1 of `byte-plane-split-plan.md`) — the
store into a repository of its own, the formats into this one. **The store merged back in on
2026-08-30**, with its history, as phase 3 of `wrapper-reorganization-plan.md`. The split had put
the storage layer one repository and one version pin away from the only code that uses it; it is
one component again.

## The modules

| module | artifactId | what it is |
|---|---|---|
| `blobstore` | `qits-blobstore` | named, typed repositories; SHA-256 content-addressed blobs in chunked PostgreSQL rows; flat string metadata. No web, no JAX-RS, nothing that names a package format. |
| `common` | `qits-registries-common` | what more than one format needs and no format owns: `OciRequestBody` (bounded body read) and `BlobSender` (backpressured blob write-out). |
| `npm` | `qits-registries-npm` | the npm registry, both sides: hosted publish and pull-through cache. |
| `maven` | `qits-registries-maven` | the maven repository, both sides. |
| `oci` | `qits-registries-oci` | the OCI Distribution registry, both sides. Routes mount at the host root (`/v2`). |

**One module per format, and each carries BOTH sides** — hosted and pull-through cache. A consuming
service wires only the repository types it owns: `qits-mirror-platform-service` takes the
proxy/mirror types, `qits-artifacts-service` the hosted ones. The two sides share a table in all
three (`npm_version`, `maven_artifact`, `oci_manifest`) and are told apart by the repository row's
type, so splitting them would cut every format down the middle.

`blobstore` is first in the reactor: every other module writes through it.

### A pull-through cache holds nothing it cannot get again

Which is why, since 2026-09-05, the **maven** proxy repairs itself: a cached entry whose serve
fails on a storage-side fault — before a byte of the response is written — is evicted and pulled
through again inside the same request, bounded to a few attempts per path so a fault that
re-fetching does not fix becomes an honest error instead of a loop (`MavenProxyHealing`). A
**hosted** repository never does this and must not: there is no upstream to ask, so an eviction
there could only delete a jar this platform published. The decision is the repository's type and
nothing else.

It is written from an incident. A cached `quarkus-proxy-registry-3.34.6.pom` answered `500` to
every request for four days while its bytes, its upstream and its neighbours were all fine: the
access-tracking `UPDATE` that every read performs raised `duplicate key value violates unique
constraint "maven_artifact_pkey"` — an `UPDATE` that touches no key column, so the primary key had
stopped agreeing with the heap. The row got into that state through `recordProxiedArtifact`'s
check-then-insert, which is a race, and which two builds resolving one new dependency together
walk into as a matter of course. Both halves are fixed here: the write lets the key decide, and the
read no longer keeps an entry it cannot serve.

**`NpmRegistryService.recordProxiedVersion` and `OciRegistryService.recordMirrorTagCheck` still
carry the identical shape** — `findOne(...).isPresent()` guarding a `persist` — and are the obvious
next repair. They are deliberately not in this change: the maven one is the fault that was measured,
and each of the other two needs its own fixture to prove the concurrent write is a quiet no-op
rather than a rollback.

## Coordinates

`eu.wohlben.qits:<artifactId>`. **`qits-blobstore` keeps the coordinate it always had** — not
`qits-registries-blobstore` — because consumers pin it and a maven coordinate is a platform-wide
contract. What changed at the merge is only where it is built and what version it carries: the
reactor's, stamped at release, published by one `deploy` at the root.

## Packages, and the one that moved

    eu.wohlben.qits.blobstore.*          the store            (blobstore)
    eu.wohlben.qits.artifacts.*          the store-side halves of each format
    eu.wohlben.qits.{npm,maven,registry} the wires

The store's classes used to be `eu.wohlben.qits.artifacts.*` too — dead weight from the extraction,
classes with no business in an `artifacts` package. **The merge renamed them to
`eu.wohlben.qits.blobstore.*`.** The format modules kept their names: they are still the code a
service switches to by a dependency change rather than a rewrite.

**A consumer bumping to the first merged release must repoint its imports**: every
`eu.wohlben.qits.artifacts.<X>` that resolves to a blobstore type becomes
`eu.wohlben.qits.blobstore.<X>`. Nothing else about the jar moved.

**The CONFIG KEYS did not move.** `qits.artifacts.blobs-datasource`,
`qits.artifacts.gc.blob-grace-period`, `qits.artifacts.staging-ttl` and
`qits.artifacts.blob-chunk-size` keep their spelling: they are set in deployed services'
environments, so a rename there is a live-config break rather than a source one.

## No schema here

**No Flyway migrations, in any module.** A library does not own a schema; each consuming service
does. The JPA entities travel as they are, so a service that owns the tables gets the same
mappings. The blob tables have no entities — the store speaks plain JDBC — so they ship as
reference DDL at `blobstore/src/main/resources/db/blobstore-tables.sql`, which each consumer copies
into its own lineage.

### Segments, and the one upgrade script (qits-2248)

PostgreSQL never gives the space a delete frees back to the filesystem, so a store that reclaimed
most of its blobs used to sit at its peak on disk for good. Since qits-2248 `blob_chunk` is
list-partitioned by a `segment` number: chunks go into one open segment, the store rolls to a fresh
partition once the open one passes `qits.artifacts.blobs.segment-size` (1 GiB), and `BlobCompaction`
— called by a consumer's gc sweep after its deletions — moves the live chunks out of mostly-dead
closed segments and drops their partitions, which is what returns the disk.

A closed segment past twice the segment size is never drained whole. That is, in practice, segment 0
— the whole pre-upgrade table, which the upgrade below attaches as it is — and the ratio rule alone
failed on it live: on dev it was 20 GB and 92% live, so compaction left it alone, every blob the gc
deleted from it gave back nothing, and since new chunks go only into the open segment the space those
deletes freed was not even reused any more, as it had been before the upgrade. Waiting for it to fall
under half live would also have needed half of it free at once for the drain. So each run shrinks it
from the tail instead, by at most `qits.artifacts.blobs.compaction.shrink-budget` (1 GiB): the owned
chunks whose bytes sit in the last budget's worth of its TOAST relation (found with a TID range scan,
mapped through `pg_column_toast_chunk_id`, PostgreSQL 17+) move into the open segment, and a
`VACUUM (INDEX_CLEANUP ON, TRUNCATE true, SKIP_LOCKED true)` of the partition cuts the emptied pages
off its end. The disk comes back a budget per gc run, at the price of moving about that much; once
the segment is down to twice the segment size the ratio rule takes it over. A STAGING upload in the
tail, a long-running transaction elsewhere (whose snapshot keeps the moved chunks' old copies alive)
or a truncation lock VACUUM could not get only delays the return to a later run. `CompactionResult`
reports it as `segmentsShrunk` and `bytesTruncated`.

`INDEX_CLEANUP` is forced `ON`, not left at its `AUTO` default: found live on segment 0, a tail the
shrink had just emptied of live TOAST values still truncated nothing — `0 live values, ... toast
dead/live tuples 0/4663910 ...; truncated 0 bytes; vacuum: ... index scan bypassed: 23352 pages from
table (0.98% of total) have 93333 dead item identifiers`. Fewer than `BYPASS_THRESHOLD_PAGES` (2% of
the relation's pages) carried an `LP_DEAD` line pointer, so `AUTO` skipped index vacuuming; the
skipped pointers were never turned `LP_UNUSED`, and truncation treats any page with a used line
pointer, dead or live, as nondeletable, so the emptied tail — about the 186 MB a previous run had
moved out of it — stayed on disk. Forcing `INDEX_CLEANUP ON` retires those pointers so the genuinely
empty tail truncates; the cost is a full scan of the TOAST relation's index on every shrink that has
anything to remove, paid once per gc run.

The shrink needs the exact TOAST position — a stand-in failed live: choosing the newest heap rows,
which needs no access to `pg_toast`, moved 1.07 GB out of segment 0 per run and truncated 770 kB, so
each gc run grew the disk by about 1.1 GB, because new chunks reuse low heap slots while their TOAST
values append at the relation's end. Reading the TOAST relation needs `USAGE` on schema `pg_toast`,
which a table's owner is not granted, so the service role needs
`grant usage on schema pg_toast to <role>` in its own database (the platform's deployer makes it).
The grant is narrow: `USAGE` only lets the role name the TOAST relations, each of which keeps its
table's ACL, so the role reads only its own tables' TOAST. Without it compaction leaves every
oversized segment untouched — moves nothing, vacuums nothing — and logs at INFO, once per run, the
grant to make.

A consumer whose tables predate this copies
`blobstore/src/main/resources/db/blobstore-segments-upgrade.sql` verbatim into a migration of its
own. It rewrites no chunk: the existing table becomes partition 0, attached as it is. **The bump and
the migration may ship in either order, or together.** The library detects the old schema and
behaves exactly as before on it (compaction answers `UNSEGMENTED`); and a library older than
qits-2248 keeps writing to an upgraded schema — which a start-first deploy needs, since the previous
instance serves uploads after the new one's Flyway has run — because `blob_chunk.segment` defaults to
`blob_open_segment()`, the open segment. Only the new library rolls and compacts.

## Building

    ./mvnw clean verify

**A clone of this repository alone builds and tests green** — no docker, no prior `mvn install`,
no monorepo. One address is the exception: `qits-db-core` (it carries `DbRetry`) comes from the
platform Maven repository declared in the root pom; since the merge it is the only dependency
outside this reactor that is not on Maven Central.

Real postgres, no container: the blob store's only backend is PostgreSQL — advisory locks, `bytea`,
a partial index and an on-conflict promote exist on no other engine — so every suite that stores a
byte runs against zonky's embedded binaries, resolved as ordinary Maven artifacts and spawned as a
child process. The JPA entities run on in-memory H2 beside it, because they are engine-neutral and
each consumer maps them onto a store of its own.

Each module keeps its own copy of `EmbeddedPg`, `BlobTables` and `ArtifactsTestSupport`, with a
database name of its own. They share no test classpath, and one host may run several of these
suites at once.
