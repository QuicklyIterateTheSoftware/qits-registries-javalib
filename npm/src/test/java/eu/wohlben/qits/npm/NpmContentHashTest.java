package eu.wohlben.qits.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.registry.ContentHashLedger;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-702: the {@code X-Artifacts-Content-Hash} header on an npm publish, and what {@code
 * NpmRoutes.publish} does with it through {@link ContentHashLedger}.
 *
 * <p>{@link RecordingContentHashLedger} stands in for the real {@code JpaContentHashLedger}
 * ({@code qits-artifacts-service}, qits-636) — this suite proves the route's side of the contract,
 * never a table.
 */
@QuarkusTest
class NpmContentHashTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String VALUE = "v1:sha256:" + "a".repeat(64);

  @TestHTTPResource("/")
  URL root;

  @Inject ArtifactRepositoryService repositoryService;
  @Inject RecordingContentHashLedger ledger;

  @BeforeEach
  void ensureRepository() {
    repositoryService.ensure("npm", RepositoryTypeProfile.keyOfWireName("npm-packages"));
  }

  @Test
  void aPublishRecordsTheHash() {
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");

    try (NpmClient npm = client()) {
      npm.header(ContentHashLedger.HEADER, VALUE);
      HttpResponse<String> published =
          npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
      assertEquals(201, published.statusCode(), published.body());
    }

    assertEquals(VALUE, ledger.valueFor(subject.name(), "1.0.0"));
  }

  @Test
  void aMalformedHeaderIsA400AndNothingIsStaged() {
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");

    try (NpmClient npm = client()) {
      npm.header(ContentHashLedger.HEADER, "not-a-content-hash");
      HttpResponse<String> response =
          npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
      assertEquals(400, response.statusCode(), response.body());
      assertTrue(response.body().contains(ContentHashLedger.HEADER), response.body());

      // Nothing was staged: the name is still unpublished, so the packument 404s exactly as it did
      // before the attempt.
      assertEquals(404, npm.packument("npm", encoded(subject.name())).statusCode());
    }

    assertNull(ledger.valueFor(subject.name(), "1.0.0"));
  }

  @Test
  void noHeaderRecordsNothing() {
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");

    try (NpmClient npm = client()) {
      HttpResponse<String> published =
          npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
      assertEquals(201, published.statusCode(), published.body());
    }

    assertNull(ledger.valueFor(subject.name(), "1.0.0"));
  }

  private NpmClient client() {
    return new NpmClient(URI.create(root.toString()));
  }

  /** A fresh scoped name per case — versions are immutable, so no two tests may share one. */
  private static String scopedName() {
    return "@qits/content-hash-" + UNIQUE.incrementAndGet();
  }

  /** What npm actually puts on the wire for a scoped name. */
  private static String encoded(String name) {
    return name.replace("/", "%2f");
  }
}
