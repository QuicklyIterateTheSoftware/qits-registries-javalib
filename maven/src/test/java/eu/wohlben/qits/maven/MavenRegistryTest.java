package eu.wohlben.qits.maven;

import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The hosted maven repository, end to end over the wire.
 *
 * <p>Absolute paths throughout, deliberately: {@code /artifacts/maven} is a literal in {@code
 * MavenPaths} and no config key moves it, so — exactly like {@code GitHostTest}, {@code
 * RegistryTest} and {@code NpmRegistryTest} — this suite is the only thing that would notice it
 * drifting.
 *
 * <p>Every case names its own artifact. The service module's suite has no table reset between
 * tests (the {@code artifacts} module's {@code ArtifactsTestSupport} is not on this classpath, on
 * purpose), and releases here are immutable, so a shared coordinate would make these tests
 * order-dependent in the one way this registry is specifically designed to refuse.
 */
@QuarkusTest
class MavenRegistryTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  @Inject MavenArtifactRepository artifacts;

  @Inject
  @DataSource("blobs")
  AgroalDataSource blobs;

  @TestHTTPResource("/")
  URL root;

  /**
   * The repository row, made through the service rather than the admin endpoint the monolith's copy
   * of this suite used: the JSON admin surface is a service's, not this lib's.
   */
  @Inject ArtifactRepositoryService repositoryService;

  @BeforeEach
  void ensureRepositories() {
    repositoryService.ensure("maven", MavenPackagesProfile.KEY);
  }

  // --- the round trip ---------------------------------------------------------------------------

  @Test
  void aReleaseDeploysAndResolves() {
    String artifact = "round-trip-" + UNIQUE.incrementAndGet();
    String base = "eu/wohlben/qits/" + artifact + "/1.0.0/" + artifact + "-1.0.0";
    byte[] jar = TinyArtifact.jar("round trip " + artifact);
    byte[] pom = TinyArtifact.pom("eu.wohlben.qits", artifact, "1.0.0");

    try (MavenClient maven = client()) {
      assertEquals(201, maven.put("maven", base + ".jar", jar).statusCode());
      assertEquals(201, maven.put("maven", base + ".pom", pom).statusCode());

      HttpResponse<byte[]> served = maven.get("maven", base + ".jar");
      assertEquals(200, served.statusCode());
      assertArrayEquals(jar, served.body(), "the send must return the exact bytes");
      assertEquals(
          "public, max-age=31536000, immutable",
          served.headers().firstValue("cache-control").orElseThrow(),
          "a release is immutable, so its bytes are cacheable forever");
      assertEquals(
          Long.toString(jar.length),
          served.headers().firstValue("content-length").orElseThrow());

      assertArrayEquals(pom, maven.get("maven", base + ".pom").body());

      // HEAD is the GET's twin, not a derivation: same status, same length, no body.
      HttpResponse<Void> head = maven.head("maven", base + ".jar");
      assertEquals(200, head.statusCode());
      assertEquals(
          Long.toString(jar.length), head.headers().firstValue("content-length").orElseThrow());
    }
  }

  @Test
  void checksumsAreDerivedAtGetTime() {
    String artifact = "checksums-" + UNIQUE.incrementAndGet();
    String base = "eu/wohlben/qits/" + artifact + "/2.1.0/" + artifact + "-2.1.0";
    byte[] jar = TinyArtifact.jar("checksums " + artifact);

    try (MavenClient maven = client()) {
      assertEquals(201, maven.put("maven", base + ".jar", jar).statusCode());

      assertEquals(TinyArtifact.hex(jar, "MD5"), getText(maven, base + ".jar.md5"));
      assertEquals(TinyArtifact.hex(jar, "SHA-1"), getText(maven, base + ".jar.sha1"));
      assertEquals(TinyArtifact.hex(jar, "SHA-256"), getText(maven, base + ".jar.sha256"));
      assertEquals(TinyArtifact.hex(jar, "SHA-512"), getText(maven, base + ".jar.sha512"));
    }
  }

  @Test
  void aChecksumPutIsVerifiedAgainstTheBlobAndNeverStored() {
    String artifact = "verify-" + UNIQUE.incrementAndGet();
    String base = "eu/wohlben/qits/" + artifact + "/1.0.0/" + artifact + "-1.0.0";
    byte[] jar = TinyArtifact.jar("verify " + artifact);

    try (MavenClient maven = client()) {
      assertEquals(201, maven.put("maven", base + ".jar", jar).statusCode());

      // The deploy plugin's own flow: artifact first, then each checksum claim. A true claim is a
      // 201 and stores nothing — the checksum is derivable, so a stored copy could only disagree.
      assertEquals(
          201,
          maven.put("maven", base + ".jar.sha1", TinyArtifact.hex(jar, "SHA-1").getBytes())
              .statusCode());
      assertEquals(
          201,
          maven.put("maven", base + ".jar.sha256", TinyArtifact.hex(jar, "SHA-256").getBytes())
              .statusCode());

      // A false claim is a 400 naming both values — a blob that does not hash to its claim is a
      // corrupt deploy, not a stored disagreement.
      HttpResponse<String> mismatch =
          maven.put("maven", base + ".jar.sha1", "0".repeat(40).getBytes());
      assertEquals(400, mismatch.statusCode(), mismatch.body());
      assertTrue(mismatch.body().contains("claimed"), mismatch.body());

      // And a checksum for an artifact that was never deployed is a 400: the deploy plugin sends
      // the artifact first, so a missing one means the client got the order wrong.
      assertEquals(
          400,
          maven.put(
                  "maven",
                  "eu/wohlben/qits/" + artifact + "/9.9.9/" + artifact + "-9.9.9.jar.sha1",
                  "0".repeat(40).getBytes())
              .statusCode());
    }
  }

  @Test
  void aClientMetadataPutIsAcceptedAndDiscarded() {
    String artifact = "metadata-" + UNIQUE.incrementAndGet();
    String ga = "eu/wohlben/qits/" + artifact;
    String base = ga + "/1.0.0/" + artifact + "-1.0.0";
    byte[] jar = TinyArtifact.jar("metadata " + artifact);

    try (MavenClient maven = client()) {
      assertEquals(201, maven.put("maven", base + ".jar", jar).statusCode());

      // What mvn deploy sends last: ITS merge of the metadata, claiming a version nothing deployed.
      // Accepted — refusing would break the deploy on its final request — and discarded, because
      // storing it would serve a second source of truth that goes stale on the next deploy.
      String clientMerge =
          "<metadata><groupId>eu.wohlben.qits</groupId><artifactId>"
              + artifact
              + "</artifactId><versioning><versions><version>9.9.9</version></versions></versioning></metadata>";
      assertEquals(
          201,
          maven.put("maven", ga + "/maven-metadata.xml", clientMerge.getBytes()).statusCode());
      assertEquals(
          201,
          maven.put("maven", ga + "/maven-metadata.xml.sha1", "0".repeat(40).getBytes())
              .statusCode());

      // GET serves the DERIVED document: the deployed version, not the client's merge.
      HttpResponse<String> derived = maven.getText("maven", ga + "/maven-metadata.xml");
      assertEquals(200, derived.statusCode());
      assertTrue(derived.body().contains("<version>1.0.0</version>"), derived.body());
      assertFalse(derived.body().contains("9.9.9"), derived.body());

      // And a metadata checksum GET is derived from the served document, not the discarded claim.
      HttpResponse<String> checksum = maven.getText("maven", ga + "/maven-metadata.xml.sha1");
      assertEquals(200, checksum.statusCode());
      assertEquals(
          TinyArtifact.hex(derived.body().getBytes(), "SHA-1"), checksum.body());
    }

    // Discarded means discarded: the body was staged so the size cap applied to it too, and the
    // staging has to go with it. Every other path through this suite promotes what it stages, so a
    // leak anywhere would show up here as well.
    assertEquals(0, stagingCount(), "a discarded upload must leave no staging behind");
  }

  @Test
  void derivedMetadataListsVersionsLatestAndRelease() {
    String artifact = "versions-" + UNIQUE.incrementAndGet();
    String ga = "eu/wohlben/qits/" + artifact;

    try (MavenClient maven = client()) {
      for (String version : new String[] {"1.0.0", "1.0.10", "1.0.9"}) {
        String path = ga + "/" + version + "/" + artifact + "-" + version + ".jar";
        assertEquals(
            201,
            maven.put("maven", path, TinyArtifact.jar(artifact + " " + version)).statusCode(),
            version);
      }

      HttpResponse<String> metadata = maven.getText("maven", ga + "/maven-metadata.xml");
      assertEquals(200, metadata.statusCode());
      // Numeric ordering, not lexical: 1.0.10 outranks 1.0.9.
      assertTrue(metadata.body().contains("<latest>1.0.10</latest>"), metadata.body());
      assertTrue(metadata.body().contains("<release>1.0.10</release>"), metadata.body());
      assertTrue(
          metadata.body()
              .contains(
                  "<version>1.0.0</version>\n      <version>1.0.9</version>\n"
                      + "      <version>1.0.10</version>"),
          metadata.body());
      assertTrue(metadata.body().contains("<groupId>eu.wohlben.qits</groupId>"), metadata.body());
      assertTrue(metadata.body().contains("<lastUpdated>"), metadata.body());
    }
  }

  // --- immutability -----------------------------------------------------------------------------

  @Test
  void aRedeployOfIdenticalBytesIsAnIdempotentNoOp() {
    String artifact = "retry-" + UNIQUE.incrementAndGet();
    String path = "eu/wohlben/qits/" + artifact + "/1.0.0/" + artifact + "-1.0.0.jar";
    byte[] jar = TinyArtifact.jar("retry " + artifact);

    try (MavenClient maven = client()) {
      assertEquals(201, maven.put("maven", path, jar).statusCode());
      // Deploy retries are normal — a flaky network, a repeated pipeline step — and content
      // addressing makes the retry free.
      assertEquals(201, maven.put("maven", path, jar).statusCode(), "identical bytes redeploy");
      assertArrayEquals(jar, maven.get("maven", path).body());
    }
  }

  @Test
  void aRedeployWithDifferentBytesIsForbidden() {
    String artifact = "immutable-" + UNIQUE.incrementAndGet();
    String path = "eu/wohlben/qits/" + artifact + "/1.0.0/" + artifact + "-1.0.0.jar";

    try (MavenClient maven = client()) {
      assertEquals(
          201, maven.put("maven", path, TinyArtifact.jar("first " + artifact)).statusCode());

      HttpResponse<String> refused =
          maven.put("maven", path, TinyArtifact.jar("second " + artifact));
      assertEquals(403, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("1.0.0"), refused.body());
      assertTrue(refused.body().contains("immutable"), refused.body());
    }
  }

  // --- the grammar's refusals -------------------------------------------------------------------

  @Test
  void anUnparseableDeployPathIsABadRequest() {
    try (MavenClient maven = client()) {
      // The file does not start with <artifact>-: a store that accepted this would serve
      // unanswerable metadata later, so the refusal happens at the door.
      HttpResponse<String> wrongFile =
          maven.put(
              "maven",
              "eu/wohlben/qits/misfiled/1.0.0/something-else-1.0.0.jar",
              TinyArtifact.jar("misfiled"));
      assertEquals(400, wrongFile.statusCode(), wrongFile.body());

      // Too few segments to hold a coordinate at all.
      assertEquals(
          400,
          maven.put("maven", "eu/wohlben/lonely.jar", TinyArtifact.jar("lonely")).statusCode());
    }
  }

  @Test
  void deleteIsNotImplemented() {
    try (MavenClient maven = client()) {
      HttpResponse<String> refused =
          maven.delete("maven", "eu/wohlben/qits/anything/1.0.0/anything-1.0.0.jar");
      assertEquals(405, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("undeploy"), refused.body());
    }
  }

  @Test
  void anUnknownRepositoryIsNotFoundAndNamesTheEnsureEndpoint() {
    try (MavenClient maven = client()) {
      HttpResponse<String> missing =
          maven.getText("no-such-repo", "eu/wohlben/qits/x/1.0.0/x-1.0.0.jar");
      assertEquals(404, missing.statusCode(), missing.body());
      assertTrue(missing.body().contains("no such maven repository"), missing.body());
      assertTrue(missing.body().contains("PUT /artifacts/api/repositories/"), missing.body());
    }
  }

  @Test
  void anythingElseUnderTheBaseIsAShortText404NeverTheSpa() {
    try (MavenClient maven = client()) {
      // A repository with nothing after it, and a path no row has: both must be plain-text 404s.
      // The SPA's catch-all answers 200 text/html to anything it swallows — a maven client told
      // that reports anything but "no such path".
      for (String path : new String[] {"/artifacts/maven/maven", "/artifacts/maven/maven/"}) {
        HttpResponse<String> missed = maven.getAbsolute(path);
        assertEquals(404, missed.statusCode(), path);
        assertFalse(missed.body().contains("<html"), path);
      }
      HttpResponse<String> unknown =
          maven.getText("maven", "eu/wohlben/qits/never/1.0.0/never-1.0.0.jar");
      assertEquals(404, unknown.statusCode(), unknown.body());
      assertTrue(unknown.body().contains("no such artifact"), unknown.body());
    }
  }

  @Test
  void dotSegmentsAreCollapsedBeforeRoutingAndNeverReachTheStore() {
    try (MavenClient maven = client()) {
      // Vert.x normalizedPath() collapses dot-segments BEFORE the grammar runs, so this resolves to
      // /artifacts/maven/secret — a repository with nothing after it, which the catch-all answers —
      // rather than walking out of the repository root. The traversal defence is structural, not a
      // check somebody has to remember: were the dots NOT collapsed the path would match the route
      // and 404 with "no such artifact" instead.
      HttpResponse<String> traversed = maven.getText("maven", "../secret");
      assertEquals(404, traversed.statusCode(), traversed.body());
      assertTrue(
          traversed.body().contains("not a route this maven repository serves"), traversed.body());
    }
  }

  @Test
  void aFileReadTouchesItsRowWhileTheDerivedDocumentsTouchNothing() {
    // The maven half of the GC's access basis. The second half of the name is the load-bearing one:
    // maven-metadata.xml and every checksum are computed per request from rows, so treating them as
    // accesses would keep a jar alive on the strength of a resolver listing versions.
    String artifact = "access-" + UNIQUE.incrementAndGet();
    String base = "eu/wohlben/qits/" + artifact + "/1.0.0/" + artifact + "-1.0.0";
    byte[] jar = TinyArtifact.jar("access " + artifact);

    try (MavenClient maven = client()) {
      assertEquals(201, maven.put("maven", base + ".jar", jar).statusCode());
      artifacts.getEntityManager().clear();
      assertNull(
          artifacts.findOne("maven", base + ".jar").orElseThrow().accessedAt,
          "a deploy is not an access");

      // The derived paths first, so a stray touch on either shows up before the real read can hide
      // it: the version listing, then the jar's derived sha1.
      assertEquals(
          200, maven.getText("maven", "eu/wohlben/qits/" + artifact + "/maven-metadata.xml")
              .statusCode());
      assertEquals(200, maven.getText("maven", base + ".jar.sha1").statusCode());
      artifacts.getEntityManager().clear();
      assertNull(
          artifacts.findOne("maven", base + ".jar").orElseThrow().accessedAt,
          "derived documents and derived checksums are not this row's bytes");

      assertEquals(200, maven.get("maven", base + ".jar").statusCode());
      artifacts.getEntityManager().clear();
      Instant first = artifacts.findOne("maven", base + ".jar").orElseThrow().accessedAt;
      assertTrue(first != null, "a file GET must record the access");

      assertEquals(200, maven.get("maven", base + ".jar").statusCode());
      assertEquals(200, maven.head("maven", base + ".jar").statusCode());
      artifacts.getEntityManager().clear();
      assertEquals(
          first,
          artifacts.findOne("maven", base + ".jar").orElseThrow().accessedAt,
          "writes are coalesced to one per row per hour");
    }
  }

  private String getText(MavenClient maven, String path) {
    HttpResponse<String> response = maven.getText("maven", path);
    assertEquals(200, response.statusCode(), path + ": " + response.body());
    return response.body();
  }

  private MavenClient client() {
    return new MavenClient(URI.create(root.toString()));
  }

  /**
   * How many staging areas the blob store is holding.
   *
   * <p>Staging lives in {@code blob_content} rows now, not in a temp file, so a caller that forgets
   * to discard one leaks a row and its chunks instead of a file. Nothing else in this suite would
   * notice.
   */
  private long stagingCount() {
    try (Connection connection = blobs.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("select count(*) from blob_content where state = 'STAGING'")) {
      rows.next();
      return rows.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException("could not count blob staging", e);
    }
  }
}
