package eu.wohlben.qits.registry;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.control.CiScreenshotsProfile;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The OCI Distribution API at {@code /v2}.
 *
 * <p>The paths are spelled out absolutely on purpose. These routes are raw Vert.x and carry the
 * protocol root as a literal — {@code /v2} moves with nothing, not {@code quarkus.rest.path} and not
 * {@code quarkus.http.non-application-root-path} — so nothing in the JAX-RS configuration would
 * catch them drifting. This suite is the only thing that does, exactly as {@code GitHostTest} is for
 * the git host.
 */
@QuarkusTest
class RegistryTest {

  @Inject OciManifestRepository manifests;
  @Inject OciTagRepository tags;

  /** The repository row: the JSON admin endpoint is a service's, so the row comes from here. */
  @Inject ArtifactRepositoryService repositoryService;

  private static final String REPO = "qits";
  private static final String ABSENT_DIGEST = "sha256:" + "0".repeat(64);

  @TestHTTPResource("/")
  URL root;

  /**
   * A distinct image per test method. Nothing here truncates the registry between cases, and it
   * should not have to: tags and manifests are scoped to an image, so giving each test its own
   * makes the suite order-independent by construction rather than by a cleanup somebody has to
   * remember to extend when a table is added. Blobs are content-addressed and shared on purpose —
   * every test that cares uses its own salt.
   */
  private String image;

  @BeforeEach
  void ensureRepository(org.junit.jupiter.api.TestInfo test) {
    image =
        REPO
            + "/"
            + test.getTestMethod().orElseThrow().getName().toLowerCase(java.util.Locale.ROOT);

    // Repositories are NOT created implicitly. The monolith's copy of this suite made the row
    // through the JSON admin endpoint; that surface is a service's, not this lib's, so the row is
    // made through the service the endpoint is thin over — same immutable-type rule.
    repositoryService.ensure(REPO, OciImagesProfile.KEY);
  }

  private OciClient client() {
    return new OciClient(URI.create(root.toString()));
  }

  /**
   * RestAssured with path encoding off.
   *
   * <p>A digest is {@code sha256:<hex>} and the colon is a legal path character that docker, podman
   * and skopeo all send raw. RestAssured percent-encodes it by default, which produces a path no
   * real client ever sends and which therefore matches no route — so leaving encoding on would make
   * these assertions test RestAssured rather than the registry.
   */
  private static io.restassured.specification.RequestSpecification http() {
    return given().urlEncodingEnabled(false);
  }

  // --- the surface --------------------------------------------------------------------------

  @Test
  void theVersionProbeIsAnsweredAnonymously() {
    // Every client pings this before anything else, and it stays 200 so an anonymous `docker pull`
    // works with no login — and since the registry carries no write guard, so does a push.
    given()
        .when()
        .get("/v2/")
        .then()
        .statusCode(200)
        .header("Docker-Distribution-Api-Version", "registry/2.0");
    given().when().get("/v2").then().statusCode(200);
  }

  @Test
  void theRegistryHasExactlyOneAddress() {
    // /v2 is a literal at the HOST root. A prefixed spelling is not a second address, and if the
    // routes ever drifted under quarkus.rest.path this is what would notice.
    given().when().get("/artifacts/v2/").then().statusCode(404);
    given().when().get("/artifacts/api/v2/").then().statusCode(404);
  }

  @Test
  void thePrivatePostureIsNotAccidentallyAnApi() {
    // Both of these are deliberate omissions, and both are pinned so they cannot be filled in by
    // accident: enumeration is what the private posture avoids, and nothing should come to depend
    // on deletion semantics before a garbage-collection story exists.
    given().when().get("/v2/_catalog").then().statusCode(404).body("errors[0].code", equalTo("UNSUPPORTED"));
    given()
        .when()
        .delete("/v2/" + image + "/manifests/latest")
        .then()
        .statusCode(405)
        .body("errors[0].code", equalTo("UNSUPPORTED"));
    http().when().get("/v2/" + image + "/referrers/" + ABSENT_DIGEST).then().statusCode(404);
  }

  @Test
  void anUnknownRepositoryIsNameUnknownAndSaysHowToCreateIt() {
    given()
        .when()
        .post("/v2/no-such-repo/alpine/blobs/uploads/")
        .then()
        .statusCode(404)
        .contentType(containsString("json"))
        .body("errors[0].code", equalTo("NAME_UNKNOWN"))
        .body("errors[0].message", containsString("oci-images"));
  }

  @Test
  void aRepositoryOfTheWrongTypeIsAlsoNameUnknown() {
    repositoryService.ensure("shots", CiScreenshotsProfile.KEY);
    given()
        .when()
        .get("/v2/shots/alpine/manifests/latest")
        .then()
        .statusCode(404)
        .body("errors[0].code", equalTo("NAME_UNKNOWN"));
  }

  @Test
  void aSingleSegmentReferenceExplainsItselfRatherThan404ing() {
    // `docker push <host>/alpine:latest` is a reference docker will happily emit and this
    // deployment cannot serve. The message is the whole debugging story.
    given()
        .when()
        .post("/v2/alpine/blobs/uploads/")
        .then()
        .statusCode(400)
        .body("errors[0].code", equalTo("NAME_INVALID"))
        .body("errors[0].message", containsString("<repository>/<image>"));
  }

  @Test
  void anUnknownBlobIsTheSpecEnvelopeNotABareStatus() {
    http()
        .when()
        .get("/v2/" + image + "/blobs/" + ABSENT_DIGEST)
        .then()
        .statusCode(404)
        .contentType(containsString("json"))
        .body("errors[0].code", equalTo("BLOB_UNKNOWN"));
    http().when().head("/v2/" + image + "/blobs/" + ABSENT_DIGEST).then().statusCode(404);
  }

  @Test
  void anUnknownTagIsManifestUnknown() {
    given()
        .when()
        .get("/v2/" + image + "/manifests/nope")
        .then()
        .statusCode(404)
        .body("errors[0].code", equalTo("MANIFEST_UNKNOWN"));
  }

  /**
   * "Absent" and "unusable" are different answers, and the difference is the whole point: a 404 says
   * the manifest is not here, which invites a client to push it. These references cannot address a
   * manifest at all.
   *
   * <p>Both cases answered 404 until the upstream conformance suite failed on the digest one — the
   * reference matched neither the tag nor the digest alternative of the route regex, so it fell to
   * the catch-all. See {@code RegistryPaths.REF}.
   */
  @Test
  void aMalformedReferenceIsRejectedRatherThanReportedAbsent() {
    // Digest-shaped, so the complaint is about the digest. This exact request is the conformance
    // suite's `invalid-digest-format/manifest-put`.
    http()
        .contentType("application/vnd.oci.image.manifest.v1+json")
        .body("{}")
        .when()
        .put("/v2/" + image + "/manifests/sha256:baddigeststring")
        .then()
        .statusCode(400)
        .body("errors[0].code", equalTo("DIGEST_INVALID"));

    // An algorithm we do not implement is the same answer, not a 404 and not a 501.
    http()
        .when()
        .get("/v2/" + image + "/manifests/sha512:" + "a".repeat(128))
        .then()
        .statusCode(400)
        .body("errors[0].code", equalTo("DIGEST_INVALID"));

    // Not digest-shaped, so the complaint is about the tag.
    http()
        .when()
        .get("/v2/" + image + "/manifests/-latest")
        .then()
        .statusCode(400)
        .body("errors[0].code", equalTo("MANIFEST_INVALID"));
  }

  /**
   * The spec makes 416 a <b>MUST</b> for an out-of-order final chunk, and the {@code PUT} skipped the
   * check that {@code PATCH} already had — so the bytes were appended and the failure surfaced as
   * {@code 400 DIGEST_INVALID}. Same rejection, wrong diagnosis: a resumable client was told its
   * content was corrupt when its offset was stale, so it retried the upload instead of resyncing
   * against the {@code Range} header.
   */
  @Test
  void theFinalChunkOfAnUploadMustStartWhereTheSessionStands() {
    String session =
        given()
            .when()
            .post("/v2/" + image + "/blobs/uploads/")
            .then()
            .statusCode(202)
            .extract()
            .header("location");

    byte[] first = "0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    http()
        .header("Content-Range", "0-" + (first.length - 1))
        .body(first)
        .when()
        .patch(session)
        .then()
        .statusCode(202);

    // The session stands at 10; claim to continue at 4096.
    http()
        .header("Content-Range", "4096-4100")
        .body("world")
        .when()
        .put(session + (session.contains("?") ? "&" : "?") + "digest=" + ABSENT_DIGEST)
        .then()
        .statusCode(416)
        .header("Range", equalTo("0-" + (first.length - 1)));
  }

  // --- the roundtrip ------------------------------------------------------------------------

  @Test
  void anImagePushedOnceIsPulledBackByteForByte() {
    try (OciClient client = client()) {
      TinyImage pushed = TinyImage.of("roundtrip");
      client.push(image, "latest", pushed);

      TinyImage pulled = client.pull(image, "latest");
      assertArrayEquals(pushed.manifest(), pulled.manifest(), "manifest bytes must survive exactly");
      assertArrayEquals(pushed.layer().bytes(), pulled.layer().bytes());
      assertArrayEquals(pushed.config().bytes(), pulled.config().bytes());

      // And by digest, which is how an index's children are fetched.
      assertArrayEquals(pushed.manifest(), client.pull(image, pushed.manifestDigest()).manifest());
    }
  }

  @Test
  void onlyManifestReadsTouchRepositoryScopedReachabilityRoots() {
    try (OciClient client = client()) {
      TinyImage pushed = TinyImage.of("access");
      client.push(image, "latest", pushed);
      String imageName = image.substring((REPO + "/").length());

      http().when().head("/v2/" + image + "/manifests/latest").then().statusCode(200);
      String manifestHex = pushed.manifestDigest().substring("sha256:".length());
      assertTrue(manifests.findOne(REPO, imageName, manifestHex).orElseThrow().accessedAt != null);
      assertTrue(tags.findOne(REPO, imageName, "latest").orElseThrow().accessedAt != null);

      QuarkusTransaction.requiringNew().run(() -> {
        manifests.findOne(REPO, imageName, manifestHex).orElseThrow().accessedAt = null;
        tags.findOne(REPO, imageName, "latest").orElseThrow().accessedAt = null;
      });
      tags.getEntityManager().clear();
      http().when().get("/v2/" + image + "/manifests/" + pushed.manifestDigest())
          .then().statusCode(200);
      assertTrue(manifests.findOne(REPO, imageName, manifestHex).orElseThrow().accessedAt != null);
      assertEquals(null, tags.findOne(REPO, imageName, "latest").orElseThrow().accessedAt);

      QuarkusTransaction.requiringNew().run(() ->
          manifests.findOne(REPO, imageName, manifestHex).orElseThrow().accessedAt = null);
      manifests.getEntityManager().clear();
      http().when().get("/v2/" + image + "/blobs/" + pushed.layer().digest())
          .then().statusCode(200);
      assertEquals(null,
          manifests.findOne(REPO, imageName, manifestHex).orElseThrow().accessedAt);
    }
  }

  @Test
  void aMultiSlashImageNameRoundTrips() {
    // The document's own example: repository `qits`, image `build-images/ci-base`. Nothing but a
    // real request proves the greedy route split survives contact with Vert.x.
    try (OciClient client = client()) {
      String name = REPO + "/build-images/ci-base";
      TinyImage subject = TinyImage.of("nested");
      client.push(name, "latest", subject);
      assertArrayEquals(subject.manifest(), client.pull(name, "latest").manifest());
    }
  }

  @Test
  void aChunkedSinglePatchUploadIsWhatTheClientActuallySends() {
    // The whole upload path in one request each way, over the encoding docker uses. A short read
    // here — the symptom of pausing the request after the worker handoff instead of before it —
    // shows up as a digest mismatch on finalize.
    try (OciClient client = client()) {
      TinyImage subject = TinyImage.of("chunked");
      // No "the blob is absent first" precondition: the store is content-addressed and its
      // directory outlives the suite under target/, so a rerun would already have these bytes.
      // What this test is about is the upload round-tripping, not the starting state.
      String session = client.startUpload(image);
      assertEquals(202, client.patchUpload(session, subject.layer().bytes()).statusCode());
      HttpResponse<String> finished = client.finishUpload(session, subject.layer().digest());
      assertEquals(201, finished.statusCode());
      assertEquals(
          subject.layer().digest(), finished.headers().firstValue("docker-content-digest").orElseThrow());

      assertTrue(client.blobExists(image, subject.layer().digest()));
      assertArrayEquals(subject.layer().bytes(), client.getBlob(image, subject.layer().digest()));
    }
  }

  @Test
  void aMonolithicUploadStoresTheBlobInOneRequest() {
    try (OciClient client = client()) {
      TinyImage subject = TinyImage.of("monolithic");
      assertEquals(
          201,
          client
              .monolithicUpload(image, subject.layer().digest(), subject.layer().bytes())
              .statusCode());
      assertArrayEquals(subject.layer().bytes(), client.getBlob(image, subject.layer().digest()));
    }
  }

  @Test
  void headBeforeUploadReportsTheRealLengthSoALayerIsNotSkipped() {
    // Docker HEADs every blob before uploading it. A HEAD answering Content-Length: 0 makes it
    // believe it already has a layer it does not have, and the pull then fails on a missing blob.
    try (OciClient client = client()) {
      TinyImage subject = TinyImage.of("head");
      client.monolithicUpload(image, subject.layer().digest(), subject.layer().bytes());

      http()
          .when()
          .head("/v2/" + image + "/blobs/" + subject.layer().digest())
          .then()
          .statusCode(200)
          .header("Content-Length", String.valueOf(subject.layer().bytes().length))
          .header("Docker-Content-Digest", subject.layer().digest());
    }
  }

  @Test
  void aSecondRepositoryMountsTheSameLayerWithoutReuploading() {
    repositoryService.ensure("other", OciImagesProfile.KEY);

    try (OciClient client = client()) {
      TinyImage subject = TinyImage.of("mount");
      client.monolithicUpload(image, subject.layer().digest(), subject.layer().bytes());

      // A hit is 201 with the blob's location: dedupe is global and content-addressed, so there is
      // literally nothing to copy.
      HttpResponse<String> hit = client.mountBlob("other/alpine", subject.layer().digest(), image);
      assertEquals(201, hit.statusCode());
      assertEquals(
          subject.layer().digest(), hit.headers().firstValue("docker-content-digest").orElseThrow());

      // A MISS must open an ordinary session — 202 with a Location, exactly as if `mount` had not
      // been sent. Answering 4xx here is the classic way to break `docker push`.
      HttpResponse<String> miss = client.mountBlob("other/alpine", ABSENT_DIGEST, image);
      assertEquals(202, miss.statusCode());
      assertTrue(miss.headers().firstValue("location").isPresent());
    }
  }

  // --- validation ---------------------------------------------------------------------------

  @Test
  void aTamperedFinalizeDigestIsRejectedAndStoresNothingUnderThatName() {
    try (OciClient client = client()) {
      TinyImage subject = TinyImage.of("tampered");
      String session = client.startUpload(image);
      client.patchUpload(session, subject.layer().bytes());

      HttpResponse<String> response = client.finishUpload(session, ABSENT_DIGEST);
      assertEquals(400, response.statusCode());
      assertTrue(response.body().contains("DIGEST_INVALID"), response.body());
      assertFalse(
          client.blobExists(image, ABSENT_DIGEST), "nothing may be stored under a digest it is not");
    }
  }

  @Test
  void aManifestNamingAnAbsentBlobIsRejectedBeforeAnythingIsBound() {
    try (OciClient client = client()) {
      TinyImage subject = TinyImage.of("unmet");
      HttpResponse<String> response =
          client.putManifest(image, "latest", subject.manifest(), subject.manifestMediaType());
      assertEquals(404, response.statusCode(), response.body());
      assertTrue(response.body().contains("MANIFEST_BLOB_UNKNOWN"), response.body());

      // A truncated push must leave no half-resolvable tag behind.
      given().when().get("/v2/" + image + "/manifests/latest").then().statusCode(404);
    }
  }

  @Test
  void schemaVersionOneIsRejectedByName() {
    byte[] legacy =
        ("{\"schemaVersion\":1,\"name\":\"qits/alpine\",\"tag\":\"latest\",\"fsLayers\":[],"
                + "\"signatures\":[]}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    given()
        .contentType("application/vnd.docker.distribution.manifest.v1+json")
        .body(legacy)
        .when()
        .put("/v2/" + image + "/manifests/legacy")
        .then()
        .statusCode(400)
        .body("errors[0].code", equalTo("MANIFEST_INVALID"))
        .body("errors[0].message", containsString("schema version 1"));
  }

  @Test
  void aManifestPutUnderTheWrongDigestIsRejected() {
    // The manifest half of "digest verification is non-negotiable" — the half it is tempting to
    // skip because the bytes are small enough to trust.
    try (OciClient client = client()) {
      TinyImage subject = TinyImage.of("wrongdigest");
      client.monolithicUpload(image, subject.config().digest(), subject.config().bytes());
      client.monolithicUpload(image, subject.layer().digest(), subject.layer().bytes());

      HttpResponse<String> response =
          client.putManifest(image, ABSENT_DIGEST, subject.manifest(), subject.manifestMediaType());
      assertEquals(400, response.statusCode());
      assertTrue(response.body().contains("DIGEST_INVALID"), response.body());
    }
  }

  @Test
  void anIndexIsServedBackWithItsOwnMediaType() {
    // How buildx and podman actually push a multi-arch image: each child manifest BY DIGEST and
    // untagged, then the index by tag. Without the oci_manifest table the children would be
    // unresolvable and this pull would 404.
    try (OciClient client = client()) {
      TinyImage amd64 = TinyImage.of("amd64");
      TinyImage arm64 = TinyImage.of("arm64");
      client.push(image, amd64.manifestDigest(), amd64);
      client.push(image, arm64.manifestDigest(), arm64);

      byte[] index = TinyImage.index(amd64, arm64);
      assertEquals(
          201,
          client.putManifest(image, "multi", index, TinyImage.INDEX_TYPE).statusCode());

      HttpResponse<byte[]> pulled = client.getManifest(image, "multi");
      assertEquals(200, pulled.statusCode());
      assertArrayEquals(index, pulled.body());
      assertEquals(
          TinyImage.INDEX_TYPE,
          pulled.headers().firstValue("content-type").orElseThrow(),
          "clients dispatch on this; an index served as an image manifest is unusable");

      // And every child resolves by digest, which is what the runtime asks for next.
      assertArrayEquals(amd64.manifest(), client.pull(image, amd64.manifestDigest()).manifest());
    }
  }

  @Test
  void anIndexNamingAnUnpushedChildIsRejected() {
    try (OciClient client = client()) {
      TinyImage orphan = TinyImage.of("orphan");
      HttpResponse<String> response =
          client.putManifest(image, "multi", TinyImage.index(orphan), TinyImage.INDEX_TYPE);
      assertEquals(404, response.statusCode(), response.body());
      assertTrue(response.body().contains("MANIFEST_UNKNOWN"), response.body());
    }
  }

  @Test
  void retaggingMovesTheTagAndLeavesTheOldManifestPullableByDigest() {
    try (OciClient client = client()) {
      TinyImage first = TinyImage.of("first");
      TinyImage second = TinyImage.of("second");
      client.push(image, "latest", first);
      client.push(image, "latest", second);

      assertArrayEquals(second.manifest(), client.pull(image, "latest").manifest());
      assertArrayEquals(first.manifest(), client.pull(image, first.manifestDigest()).manifest());
    }
  }

  // --- tags ---------------------------------------------------------------------------------

  @Test
  void tagsAreListedLexicallyAndPaged() {
    try (OciClient client = client()) {
      TinyImage subject = TinyImage.of("tags");
      client.push(image, "v2", subject);
      client.push(image, "latest", subject);
      client.push(image, "v1", subject);

      assertEquals(List.of("latest", "v1", "v2"), client.listTags(image, null));
      assertEquals(List.of("latest", "v1"), client.listTags(image, "?n=2"));
      assertEquals(List.of("v2"), client.listTags(image, "?n=2&last=v1"));

      given()
          .when()
          .get("/v2/" + image + "/tags/list?n=2")
          .then()
          .statusCode(200)
          .body("name", equalTo(image))
          .header("Link", containsString("last=v1"));
    }
  }

  @Test
  void anUploadSessionReportsHowFarItGot() {
    try (OciClient client = client()) {
      TinyImage subject = TinyImage.of("resume");
      String session = client.startUpload(image);
      client.patchUpload(session, subject.layer().bytes());

      given()
          .when()
          .get(session)
          .then()
          .statusCode(204)
          .header("Range", "0-" + (subject.layer().bytes().length - 1));
    }
  }

  @Test
  void aChunkedUploadCanBeSentInSeveralPatchesAndResumedFromTheReportedOffset() {
    // Multi-chunk resumption. Every PATCH answers with the Range it has accepted so far, and a
    // chunk that does not start there is 416 with the real extent rather than being appended at the
    // wrong offset — which would corrupt the blob silently and only surface as a digest mismatch at
    // the very end, after the whole layer had been transferred.
    try (OciClient client = client()) {
      TinyImage subject = TinyImage.of("resumable");
      byte[] bytes = subject.layer().bytes();
      int half = bytes.length / 2;

      String session = client.startUpload(image);
      HttpResponse<String> first =
          client.patchUploadAt(session, java.util.Arrays.copyOfRange(bytes, 0, half), 0);
      assertEquals(202, first.statusCode());
      assertEquals("0-" + (half - 1), first.headers().firstValue("range").orElseThrow());

      // A chunk claiming the wrong start is refused, and the response says where to resume.
      HttpResponse<String> misaligned =
          client.patchUploadAt(session, java.util.Arrays.copyOfRange(bytes, half, bytes.length), 0);
      assertEquals(416, misaligned.statusCode());
      assertEquals("0-" + (half - 1), misaligned.headers().firstValue("range").orElseThrow());

      // Resuming at the reported offset completes the blob, byte for byte.
      assertEquals(
          202,
          client
              .patchUploadAt(session, java.util.Arrays.copyOfRange(bytes, half, bytes.length), half)
              .statusCode());
      assertEquals(201, client.finishUpload(session, subject.layer().digest()).statusCode());
      assertArrayEquals(bytes, client.getBlob(image, subject.layer().digest()));
    }
  }

  @Test
  void aDeadSessionIsBlobUploadUnknownWhateverItLooksLike() {
    // A restart drops every session, and a malformed id is the same thing to a client. Both must
    // reach the handler and be told which thing is missing — not miss the route and be told the
    // repository is unknown, which would send someone looking in the wrong place.
    for (String session : List.of("b3f0c2de-0000-4000-8000-000000000000", "not-a-uuid")) {
      given()
          .when()
          .patch("/v2/" + image + "/blobs/uploads/" + session)
          .then()
          .statusCode(404)
          .body("errors[0].code", equalTo("BLOB_UPLOAD_UNKNOWN"));
    }
  }
}
