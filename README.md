# qits-registries

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
mappings. The three blob tables have no entities — the store speaks plain JDBC — so they ship as
reference DDL at `blobstore/src/main/resources/db/blobstore-tables.sql`, which each consumer copies
into its own lineage.

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
