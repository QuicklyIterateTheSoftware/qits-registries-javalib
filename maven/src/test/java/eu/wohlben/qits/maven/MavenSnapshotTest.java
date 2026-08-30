package eu.wohlben.qits.maven;

import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import jakarta.inject.Inject;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SNAPSHOT support, driven the way a real client drives it (maven-repository-plan.md §3.4/§3.6, ⚖1
 * as ruled).
 *
 * <p>The server is a dumb path store: the <b>client</b> computes the timestamped names and PUTs
 * them as ordinary files, so the whole of the server's snapshot machinery is the version-level
 * metadata derivation plus the three path-class immutability rules. Every case below is that flow
 * over the wire — a timestamped deploy, the derived {@code <snapshotVersions>} a resolver maps the
 * coordinate through, and the literal-filename fallback.
 *
 * <p>Every case names its own artifact, for the same reason {@code MavenRegistryTest} does: no
 * table reset between tests in this module.
 */
@QuarkusTest
class MavenSnapshotTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  /** A fixed timestamped name per case, so the deploy is reproducible run over run. */
  private static final String TS1 = "20260802.123456-3";
  private static final String TS2 = "20260802.124501-4";

  @TestHTTPResource("/")
  URL root;

  /** The repository row through the service — the admin endpoint stayed with the service module. */
  @Inject ArtifactRepositoryService repositoryService;

  @BeforeEach
  void ensureRepositories() {
    repositoryService.ensure("maven", MavenPackagesProfile.KEY);
  }

  @Test
  void aTimestampedSnapshotDeploysAndItsVersionMetadataIsDerived() {
    String artifact = "snap-" + UNIQUE.incrementAndGet();
    String ga = "eu/wohlben/qits/" + artifact;
    String dir = ga + "/1.0.1-SNAPSHOT";
    byte[] jar3 = TinyArtifact.jar(artifact + " build 3");
    byte[] jar4 = TinyArtifact.jar(artifact + " build 4");

    try (MavenClient maven = client()) {
      // The client's own flow: unique, timestamped filenames, computed client-side, PUT as ordinary
      // files — jar and pom per build, plus a classifier on the newer one.
      assertEquals(201, maven.put("maven", dir + "/" + artifact + "-1.0.1-" + TS1 + ".jar", jar3).statusCode());
      assertEquals(
          201,
          maven.put(
                  "maven",
                  dir + "/" + artifact + "-1.0.1-" + TS1 + ".pom",
                  TinyArtifact.pom("eu.wohlben.qits", artifact, "1.0.1-SNAPSHOT"))
              .statusCode());
      assertEquals(201, maven.put("maven", dir + "/" + artifact + "-1.0.1-" + TS2 + ".jar", jar4).statusCode());
      assertEquals(
          201,
          maven.put(
                  "maven",
                  dir + "/" + artifact + "-1.0.1-" + TS2 + "-sources.jar",
                  TinyArtifact.jar(artifact + " sources 4"))
              .statusCode());

      // The version-level document: what a resolver reads to map 1.0.1-SNAPSHOT to a real file.
      HttpResponse<String> metadata = maven.getText("maven", dir + "/maven-metadata.xml");
      assertEquals(200, metadata.statusCode(), metadata.body());
      assertTrue(metadata.body().contains("<timestamp>20260802.124501</timestamp>"), metadata.body());
      assertTrue(metadata.body().contains("<buildNumber>4</buildNumber>"), metadata.body());
      assertTrue(
          metadata.body().contains("<value>1.0.1-20260802.123456-3</value>"), metadata.body());
      assertTrue(
          metadata.body().contains("<value>1.0.1-20260802.124501-4</value>"), metadata.body());
      assertTrue(metadata.body().contains("<classifier>sources</classifier>"), metadata.body());
      assertTrue(
          metadata.body().contains("<updated>20260802124501</updated>"), metadata.body());

      // And the file the document names resolves to the exact bytes that were deployed.
      assertArrayEquals(
          jar4, maven.get("maven", dir + "/" + artifact + "-1.0.1-" + TS2 + ".jar").body());
    }
  }

  @Test
  void artifactLevelMetadataDistinguishesLatestFromRelease() {
    String artifact = "latest-" + UNIQUE.incrementAndGet();
    String ga = "eu/wohlben/qits/" + artifact;

    try (MavenClient maven = client()) {
      assertEquals(
          201,
          maven.put(
                  "maven",
                  ga + "/1.0.0/" + artifact + "-1.0.0.jar",
                  TinyArtifact.jar(artifact + " release"))
              .statusCode());
      assertEquals(
          201,
          maven.put(
                  "maven",
                  ga + "/1.0.1-SNAPSHOT/" + artifact + "-1.0.1-" + TS1 + ".jar",
                  TinyArtifact.jar(artifact + " snapshot"))
              .statusCode());

      // With full snapshot support the two genuinely differ, and both are served: latest counts the
      // snapshot, release does not.
      HttpResponse<String> metadata = maven.getText("maven", ga + "/maven-metadata.xml");
      assertEquals(200, metadata.statusCode(), metadata.body());
      assertTrue(metadata.body().contains("<latest>1.0.1-SNAPSHOT</latest>"), metadata.body());
      assertTrue(metadata.body().contains("<release>1.0.0</release>"), metadata.body());
    }
  }

  @Test
  void timestampedNamesAreUniqueByConstructionAndTakeTheReleaseRule() {
    String artifact = "unique-" + UNIQUE.incrementAndGet();
    String path =
        "eu/wohlben/qits/" + artifact + "/1.0.1-SNAPSHOT/" + artifact + "-1.0.1-" + TS1 + ".jar";
    byte[] jar = TinyArtifact.jar(artifact + " original");

    try (MavenClient maven = client()) {
      assertEquals(201, maven.put("maven", path, jar).statusCode());
      // Identical is a no-op, exactly like a release retry.
      assertEquals(201, maven.put("maven", path, jar).statusCode(), "identical redeploy");
      // Different bytes at the same timestamped name is a collision worth saying loudly about.
      HttpResponse<String> refused =
          maven.put("maven", path, TinyArtifact.jar(artifact + " overwritten"));
      assertEquals(403, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("unique by construction"), refused.body());
    }
  }

  @Test
  void aLiteralSnapshotFilenameIsTheOneMutablePath() {
    String artifact = "moving-" + UNIQUE.incrementAndGet();
    String dir = "eu/wohlben/qits/" + artifact + "/1.0.1-SNAPSHOT";
    String path = dir + "/" + artifact + "-1.0.1-SNAPSHOT.jar";
    byte[] first = TinyArtifact.jar(artifact + " first");
    byte[] second = TinyArtifact.jar(artifact + " second");

    try (MavenClient maven = client()) {
      // What a client with uniqueVersion=false deploys: the coordinate is a moving target by
      // definition, and a 403 here would break a legitimate redeploy while buying nothing.
      assertEquals(201, maven.put("maven", path, first).statusCode());
      assertEquals(201, maven.put("maven", path, second).statusCode(), "the redeploy");
      assertArrayEquals(second, maven.get("maven", path).body(), "the new bytes win");

      HttpResponse<byte[]> served = maven.get("maven", path);
      assertEquals(
          "no-cache",
          served.headers().firstValue("cache-control").orElseThrow(),
          "the one moving target says so rather than claiming immutable");

      // And the version-level document 404s DELIBERATELY: a directory with nothing but literal
      // files has nothing to derive, and the resolver's defined fallback for a missing document is
      // exactly that literal filename — an empty document would pre-empt the fallback.
      HttpResponse<String> metadata = maven.getText("maven", dir + "/maven-metadata.xml");
      assertEquals(404, metadata.statusCode(), metadata.body());
    }
  }

  private MavenClient client() {
    return new MavenClient(URI.create(root.toString()));
  }
}
