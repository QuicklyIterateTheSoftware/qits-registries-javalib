package eu.wohlben.qits.npm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The hosted npm registry, end to end over the wire.
 *
 * <p>Absolute paths throughout, deliberately: {@code /artifacts/npm} is a literal in {@code NpmPaths}
 * and no config key moves it, so — exactly like {@code GitHostTest} and {@code RegistryTest} — this
 * suite is the only thing that would notice it drifting.
 *
 * <p>Every case names its own package. The service module's suite has no table reset between tests
 * (the {@code artifacts} module's {@code ArtifactsTestSupport} is not on this classpath, on purpose),
 * and versions here are immutable, so a shared name would make these tests order-dependent in the
 * one way this registry is specifically designed to refuse.
 */
@QuarkusTest
class NpmRegistryTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  @Inject NpmVersionRepository versions;

  @Inject ArtifactRepositoryService repositoryService;

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepositories() {
    ensure("npm", "npm-packages");
    ensure("npmjs", "npm-proxy");
  }

  // --- the round trip ---------------------------------------------------------------------------

  @Test
  void aScopedPackagePublishesAndInstalls() {
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");

    try (NpmClient npm = client()) {
      HttpResponse<String> published =
          npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
      assertEquals(201, published.statusCode(), published.body());

      JsonNode packument = npm.packumentJson("npm", encoded(subject.name()));
      assertEquals(subject.name(), packument.path("name").asText());
      assertEquals("1.0.0", packument.path("dist-tags").path("latest").asText());

      JsonNode dist = packument.path("versions").path("1.0.0").path("dist");
      assertEquals(subject.shasum(), dist.path("shasum").asText(), "shasum is recomputed, not echoed");
      assertEquals(subject.integrity(), dist.path("integrity").asText());

      String tarballUrl = dist.path("tarball").asText();
      assertTrue(
          tarballUrl.startsWith("http://"),
          "npm refuses a relative tarball url; got " + tarballUrl);
      assertTrue(
          tarballUrl.endsWith("/artifacts/npm/npm/" + subject.name() + "/-/" + subject.tarballFile()),
          "the tarball url uses npmjs' own layout; got " + tarballUrl);

      HttpResponse<byte[]> tarball = npm.tarball(tarballUrl);
      assertEquals(200, tarball.statusCode());
      assertArrayEquals(subject.tarball(), tarball.body(), "the send must return the exact bytes");
      assertEquals(
          "public, max-age=31536000, immutable",
          tarball.headers().firstValue("cache-control").orElseThrow(),
          "a published version is immutable, so its tarball is cacheable forever");
    }
  }

  @Test
  void anUnscopedPackagePublishesAndInstalls() {
    TinyPackage subject = TinyPackage.of("plain-pkg-" + UNIQUE.incrementAndGet(), "2.1.0");

    try (NpmClient npm = client()) {
      assertEquals(
          201,
          npm.publish("npm", subject.name(), subject.publishDocument("latest")).statusCode());
      JsonNode packument = npm.packumentJson("npm", subject.name());
      assertEquals("2.1.0", packument.path("dist-tags").path("latest").asText());
      assertArrayEquals(
          subject.tarball(),
          npm.tarball(NpmClient.tarballUrl(packument, "2.1.0")).body());
    }
  }

  @Test
  void bothSpellingsOfAScopedNameReachTheSamePackage() {
    // The trap this whole grammar is shaped around. Vert.x leaves %2f in the path it matches
    // against, so the encoded form is what npm's packument request looks like on the wire — while
    // the tarball url this registry emits carries a real slash, and a client follows it verbatim.
    // Both have to resolve or half the protocol works.
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");

    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));

      assertEquals(200, npm.packument("npm", encoded(subject.name())).statusCode(), "encoded");
      assertEquals(200, npm.packument("npm", subject.name()).statusCode(), "decoded");
      assertEquals(
          npm.packument("npm", encoded(subject.name())).body(),
          npm.packument("npm", subject.name()).body(),
          "the two spellings must produce the same document, not merely both succeed");
    }
  }

  @Test
  void aVersionIsAlsoPublishableWithTheUnencodedName() {
    // pnpm and some CI shims send the decoded form on the publish PUT too.
    TinyPackage subject = TinyPackage.of(scopedName(), "0.9.0");
    try (NpmClient npm = client()) {
      assertEquals(
          201,
          npm.publish("npm", subject.name(), subject.publishDocument("latest")).statusCode());
      assertEquals(200, npm.packument("npm", encoded(subject.name())).statusCode());
    }
  }

  // --- the absolute url -------------------------------------------------------------------------

  @Test
  void theTarballUrlFollowsTheGatewaysForwardedHeaders() {
    // Through qits-gateway the host this process sees is not the host the client dialled, and npm
    // refuses a relative tarball url — so the X-Forwarded-* set the gateway emits by default is what
    // makes the document usable from outside. No config key names this: a configured value would be
    // right for the gateway and quietly wrong for a qits-net client, or the reverse.
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");

    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
    }
    try (NpmClient viaGateway =
        client().header("X-Forwarded-Host", "qits.example").header("X-Forwarded-Proto", "https")) {
      assertEquals(
          "https://qits.example/artifacts/npm/npm/"
              + subject.name()
              + "/-/"
              + subject.tarballFile(),
          NpmClient.tarballUrl(
              viaGateway.packumentJson("npm", encoded(subject.name())), "1.0.0"));
    }
  }

  @Test
  void theForwardedPortIsPartOfTheTarballUrl() {
    // The gateway splits the dialled authority across two headers — X-Forwarded-Host is the host
    // alone, the port travels as X-Forwarded-Port. A local deployment's gateway sits on 8080, so
    // dropping the port header rewrites localhost:8080 into localhost and every tarball dials 80.
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");
    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
    }
    try (NpmClient viaGateway =
        client()
            .header("X-Forwarded-Host", "localhost")
            .header("X-Forwarded-Proto", "http")
            .header("X-Forwarded-Port", "8080")) {
      assertEquals(
          "http://localhost:8080/artifacts/npm/npm/" + subject.name() + "/-/" + subject.tarballFile(),
          NpmClient.tarballUrl(viaGateway.packumentJson("npm", encoded(subject.name())), "1.0.0"));
    }
  }

  @Test
  void aSchemeDefaultForwardedPortStaysOutOfTheUrl() {
    // 443 on https (and 80 on http) re-appended would be harmless but ugly; canonical urls omit it.
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");
    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
    }
    try (NpmClient viaGateway =
        client()
            .header("X-Forwarded-Host", "qits.example")
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Port", "443")) {
      assertEquals(
          "https://qits.example/artifacts/npm/npm/" + subject.name() + "/-/" + subject.tarballFile(),
          NpmClient.tarballUrl(viaGateway.packumentJson("npm", encoded(subject.name())), "1.0.0"));
    }
  }

  @Test
  void withNoForwardingHopTheUrlIsTheAuthorityTheClientActuallyDialled() {
    // A qits-net client dials qits-artifacts:8080 directly and there is no gateway to ask.
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");
    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
      String url = NpmClient.tarballUrl(npm.packumentJson("npm", encoded(subject.name())), "1.0.0");
      assertTrue(
          url.startsWith("http://" + root.getHost() + ":" + root.getPort() + "/"),
          "expected the dialled authority in " + url);
    }
  }

  // --- immutability and verification ------------------------------------------------------------

  @Test
  void republishingAVersionIsRefused() {
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");
    try (NpmClient npm = client()) {
      assertEquals(
          201,
          npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"))
              .statusCode());
      HttpResponse<String> again =
          npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
      assertEquals(403, again.statusCode(), again.body());
      assertTrue(
          NpmClient.parse(again.body()).path("error").asText().contains("immutable"),
          "the message is what npm prints: " + again.body());
    }
  }

  @Test
  void aBarePublishOfAPrereleaseCannotStealLatest() {
    // The live foot-gun, over the wire: a main build publishes <release>-main.g<sha> with no --tag,
    // npm turns that into --tag latest, and every consumer installing without a range would get a
    // main build from then on. The registry refuses the whole publish rather than the tag move
    // alone, so the pipeline goes red with a message telling it what to do instead.
    String name = scopedName();
    TinyPackage release = TinyPackage.of(name, "2026.801.63140");
    TinyPackage mainBuild = TinyPackage.of(name, "2026.801.63140-main.g0fe7780");

    try (NpmClient npm = client()) {
      assertEquals(201, npm.publish("npm", encoded(name), release.publishDocument("latest"))
          .statusCode());

      HttpResponse<String> refused =
          npm.publish("npm", encoded(name), mainBuild.publishDocument("latest"));
      assertEquals(403, refused.statusCode(), refused.body());
      String message = NpmClient.parse(refused.body()).path("error").asText();
      assertTrue(message.contains("2026.801.63140"), message);
      assertTrue(message.contains("2026.801.63140-main.g0fe7780"), message);
      assertTrue(message.contains("--tag main"), "the message says how to publish it: " + message);

      JsonNode packument = npm.packumentJson("npm", encoded(name));
      assertEquals("2026.801.63140", packument.path("dist-tags").path("latest").asText());
      assertTrue(
          packument.path("versions").path("2026.801.63140-main.g0fe7780").isMissingNode(),
          "the refusal rolls the version back too: " + packument.path("versions"));
    }
  }

  @Test
  void thePrereleaseIsPublishableUnderItsOwnTagAndTheNextReleaseStillMovesLatest() {
    // The other half of the same rule, and the one the release pipelines rely on: only `latest` is
    // ordered, so `main` takes the prerelease with no argument, and a higher release moves `latest`
    // forward exactly as it always did.
    String name = scopedName();
    TinyPackage release = TinyPackage.of(name, "2026.801.63140");
    TinyPackage mainBuild = TinyPackage.of(name, "2026.801.63140-main.g0fe7780");
    TinyPackage next = TinyPackage.of(name, "2026.802.100000");

    try (NpmClient npm = client()) {
      assertEquals(201, npm.publish("npm", encoded(name), release.publishDocument("latest"))
          .statusCode());
      assertEquals(
          201,
          npm.publish("npm", encoded(name), mainBuild.publishDocument("main")).statusCode(),
          "npm publish --tag main");
      assertEquals(201, npm.publish("npm", encoded(name), next.publishDocument("latest"))
          .statusCode());

      JsonNode packument = npm.packumentJson("npm", encoded(name));
      assertEquals("2026.802.100000", packument.path("dist-tags").path("latest").asText());
      assertEquals(
          "2026.801.63140-main.g0fe7780", packument.path("dist-tags").path("main").asText());
      assertTrue(
          packument.path("versions").path("2026.801.63140-main.g0fe7780").isObject(),
          "the prerelease is installable by exact version and by its own tag");
    }
  }

  // --- dist-tags --------------------------------------------------------------------------------

  @Test
  void aReleasedVersionCanBeGivenTheMainTagAfterItsPublish() {
    // THE CASE THIS ENDPOINT EXISTS FOR. `npm publish` names exactly one dist-tag, so a release that
    // wants its version under both `latest` and `main` cannot say so in the publish document, and
    // cannot publish twice — versions are immutable. Moving the tag afterwards is the operation npm
    // has for it, and until now this registry served neither of its two URLs.
    String name = scopedName();
    TinyPackage released = TinyPackage.of(name, "2026.905.120000");

    try (NpmClient npm = client()) {
      assertEquals(
          201, npm.publish("npm", encoded(name), released.publishDocument("latest")).statusCode());
      assertEquals(
          "{\"latest\":\"2026.905.120000\"}",
          npm.distTags("npm", encoded(name)).body(),
          "a bare publish claims latest and nothing else");

      HttpResponse<String> moved =
          npm.setDistTag("npm", encoded(name), "main", "2026.905.120000");
      assertEquals(200, moved.statusCode(), moved.body());
      assertEquals(
          "2026.905.120000",
          NpmClient.parse(moved.body()).path("main").asText(),
          "the answer is the package's whole tag map: " + moved.body());

      // And the packument — the document every install resolves against — carries both.
      JsonNode tags = npm.packumentJson("npm", encoded(name)).path("dist-tags");
      assertEquals("2026.905.120000", tags.path("latest").asText());
      assertEquals("2026.905.120000", tags.path("main").asText());
    }
  }

  @Test
  void theMainTagMovesForwardWithEachRelease() {
    // The whole point of the release recipes' `npm dist-tag add … main`: `main` follows released
    // mains, so it moves onto the newest one every time, over a tag that already exists — the branch
    // in moveTag that `latest`'s ordering rule deliberately does not guard.
    String name = scopedName();
    TinyPackage first = TinyPackage.of(name, "2026.905.120000");
    TinyPackage second = TinyPackage.of(name, "2026.906.130000");

    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(name), first.publishDocument("latest"));
      npm.setDistTag("npm", encoded(name), "main", "2026.905.120000");
      npm.publish("npm", encoded(name), second.publishDocument("latest"));
      assertEquals(
          200, npm.setDistTag("npm", encoded(name), "main", "2026.906.130000").statusCode());

      JsonNode tags = NpmClient.parse(npm.distTags("npm", encoded(name)).body());
      assertEquals("2026.906.130000", tags.path("latest").asText());
      assertEquals("2026.906.130000", tags.path("main").asText());
    }
  }

  @Test
  void anOlderMainIsStillAllowedButAnOlderLatestIsNot() {
    // The ordering rule belongs to the TAG, not to the route that moves it — so this endpoint
    // inherits it exactly as the publish path does. `main` moves anywhere, which is what makes it a
    // useful pointer; `latest` may not step backwards, which is the guard that stops a consumer
    // installing without a range from silently getting an older build.
    String name = scopedName();
    TinyPackage older = TinyPackage.of(name, "2026.905.120000");
    TinyPackage newer = TinyPackage.of(name, "2026.906.130000");

    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(name), older.publishDocument("latest"));
      npm.publish("npm", encoded(name), newer.publishDocument("latest"));

      assertEquals(
          200,
          npm.setDistTag("npm", encoded(name), "main", "2026.905.120000").statusCode(),
          "an unordered tag may point anywhere");

      HttpResponse<String> backwards =
          npm.setDistTag("npm", encoded(name), "latest", "2026.905.120000");
      assertEquals(403, backwards.statusCode(), backwards.body());
      assertTrue(
          NpmClient.parse(backwards.body()).path("error").asText().contains("2026.906.130000"),
          "the message names the version latest keeps: " + backwards.body());
      assertEquals(
          "2026.906.130000",
          NpmClient.parse(npm.distTags("npm", encoded(name)).body()).path("latest").asText(),
          "the refusal left the tag where it was");
    }
  }

  @Test
  void aTagMayNotNameAVersionThatIsNotPublishedHere() {
    // A dist-tag naming a version the packument does not contain is what every npm client reads as
    // a broken package — the same invariant garbage collection refuses a deletion to protect,
    // stated from the other side.
    String name = scopedName();
    TinyPackage published = TinyPackage.of(name, "1.0.0");

    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(name), published.publishDocument("latest"));
      HttpResponse<String> refused = npm.setDistTag("npm", encoded(name), "main", "9.9.9");
      assertEquals(404, refused.statusCode(), refused.body());
      assertTrue(
          NpmClient.parse(refused.body()).path("error").asText().contains("no such version"),
          refused.body());
      assertTrue(
          NpmClient.parse(npm.distTags("npm", encoded(name)).body()).path("main").isMissingNode(),
          "nothing was written");
    }
  }

  @Test
  void aBareVersionBodyIsAcceptedAlongsideNpmsJsonString() {
    // npm sends `"1.0.0"` with the quotes; a hand-written `curl --data 1.0.0` sends it without. Both
    // are unambiguous — a version never starts with a quote — and the curl form is how this endpoint
    // gets exercised from a shell.
    String name = scopedName();
    TinyPackage subject = TinyPackage.of(name, "1.0.0");

    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(name), subject.publishDocument("latest"));
      assertEquals(200, npm.distTagBody("npm", encoded(name), "main", "1.0.0").statusCode());
      assertEquals(
          "1.0.0",
          NpmClient.parse(npm.distTags("npm", encoded(name)).body()).path("main").asText());

      HttpResponse<String> empty = npm.distTagBody("npm", encoded(name), "next", "");
      assertEquals(400, empty.statusCode(), empty.body());
      HttpResponse<String> notAString = npm.distTagBody("npm", encoded(name), "next", "\"\"");
      assertEquals(400, notAString.statusCode(), notAString.body());
    }
  }

  @Test
  void bothSpellingsOfAScopedNameReachTheSameTags() {
    // Same trap as the packument's, on a route where the package name arrives in the MIDDLE of the
    // path rather than at its end.
    String name = scopedName();
    TinyPackage subject = TinyPackage.of(name, "1.0.0");

    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(name), subject.publishDocument("latest"));
      assertEquals(200, npm.setDistTag("npm", name, "main", "1.0.0").statusCode(), "decoded PUT");
      assertEquals(200, npm.distTags("npm", encoded(name)).statusCode(), "encoded GET");
      assertEquals(
          npm.distTags("npm", encoded(name)).body(), npm.distTags("npm", name).body());
    }
  }

  @Test
  void theTagsOfAnUnpublishedPackageAreA404AndAProxysAreNotWritable() {
    try (NpmClient npm = client()) {
      HttpResponse<String> unknown = npm.distTags("npm", encoded("@qits/never-published"));
      assertEquals(404, unknown.statusCode(), unknown.body());
      assertTrue(NpmClient.parse(unknown.body()).has("error"));

      // Refused by TYPE, exactly as a publish is: cached upstream content is not this deployment's
      // to re-tag.
      HttpResponse<String> proxy = npm.setDistTag("npmjs", "left-pad", "main", "1.3.0");
      assertEquals(405, proxy.statusCode(), proxy.body());
      assertTrue(proxy.body().contains("pull-through"), proxy.body());
    }
  }

  @Test
  void removingATagIsRefusedRatherThanUnknown() {
    // `npm dist-tag rm` lands on the same 405 unpublish gets, and for a related reason: a tag this
    // registry served yesterday and does not serve today is a consumer's install breaking. Pinned
    // because a DELETE route that started 404ing would read as "no such tag" and send someone
    // looking for the wrong problem.
    try (NpmClient npm = client()) {
      HttpResponse<String> refused = npm.delete("npm", "-/package/left-pad/dist-tags/main");
      assertEquals(405, refused.statusCode(), refused.body());
      assertEquals("application/json; charset=utf-8", contentType(refused));
    }
  }

  @Test
  void aTarballThatDoesNotMatchItsClaimedIntegrityIsRefused() {
    // The npm restatement of "a blob that does not hash to its name is not a blob". Both hashes are
    // recomputed from the decoded attachment and compared with the client's claim.
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");
    byte[] lying =
        subject.publishDocument("latest", subject.shasum(), "sha512-" + "A".repeat(86) + "==");

    try (NpmClient npm = client()) {
      HttpResponse<String> refused = npm.publish("npm", encoded(subject.name()), lying);
      assertEquals(400, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("integrity"), refused.body());
      // and nothing was written
      assertEquals(404, npm.packument("npm", encoded(subject.name())).statusCode());
    }
  }

  @Test
  void aTarballThatDoesNotMatchItsClaimedShasumIsRefused() {
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");
    byte[] lying = subject.publishDocument("latest", "0".repeat(40), subject.integrity());

    try (NpmClient npm = client()) {
      HttpResponse<String> refused = npm.publish("npm", encoded(subject.name()), lying);
      assertEquals(400, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("shasum"), refused.body());
    }
  }

  @Test
  void aDocumentPublishedUnderTheWrongNameIsRefused() {
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");
    try (NpmClient npm = client()) {
      HttpResponse<String> refused =
          npm.publish("npm", "some-other-package", subject.publishDocument("latest"));
      assertEquals(400, refused.statusCode(), refused.body());
    }
  }

  // --- what is deliberately not served ----------------------------------------------------------

  @Test
  void deleteIsUnsupportedRatherThanUnknown() {
    // 405 rather than 404, exactly as on /v2: there is no garbage collection, and 404 would read as
    // "unknown package" and send a client looking for the wrong problem.
    try (NpmClient npm = client()) {
      HttpResponse<String> refused = npm.delete("npm", "left-pad");
      assertEquals(405, refused.statusCode(), refused.body());
      assertEquals("application/json; charset=utf-8", contentType(refused));
    }
  }

  @Test
  void theProtocolEndpointsNpmProbesAnswerAJson404() {
    // Search, audit, whoami. npm degrades gracefully on every one of these — a search 404s to "no
    // results", an audit 404s to "not audited" — which is why they are absent rather than stubbed.
    // What matters is the SHAPE: Vert.x' default HTML page is what npm reads as a broken registry.
    try (NpmClient npm = client()) {
      for (String path :
          new String[] {
            "artifacts/npm/npmjs/-/v1/search?text=left-pad",
            "artifacts/npm/npmjs/-/whoami",
            "artifacts/npm/npm/-/npm/v1/security/audits/quick",
            "artifacts/npm/"
          }) {
        HttpResponse<String> answered = npm.get(path);
        assertEquals(404, answered.statusCode(), path);
        assertEquals("application/json; charset=utf-8", contentType(answered), path);
        assertTrue(NpmClient.parse(answered.body()).has("error"), path + ": " + answered.body());
      }
    }
  }

  @Test
  void publishingToAProxyRepositoryIsRefusedByType() {
    // Refused because of what the repository IS, not how it is configured — the rule that keeps
    // cached upstream content and published content out of one namespace.
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");
    try (NpmClient npm = client()) {
      HttpResponse<String> refused =
          npm.publish("npmjs", encoded(subject.name()), subject.publishDocument("latest"));
      assertEquals(405, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("pull-through"), refused.body());
    }
  }

  @Test
  void anUnknownRepositoryNamesTheEndpointThatWouldCreateIt() {
    try (NpmClient npm = client()) {
      HttpResponse<String> answered = npm.packument("no-such-npm-repo", "left-pad");
      assertEquals(404, answered.statusCode());
      assertTrue(
          NpmClient.parse(answered.body()).path("error").asText().contains("/artifacts/api/repositories/"),
          answered.body());
    }
  }

  @Test
  void aRepositoryOfAnotherTypeIsNotAnNpmRepository() {
    // The first-segment rule is by TYPE, not merely by existence: `qits` is a real row and must not
    // become an npm namespace by accident. It used to be an oci-images row; the npm module no
    // longer sees OCI's profiles, so any other registered type makes the same case.
    ensure("qits", "ci-screenshots");
    try (NpmClient npm = client()) {
      assertEquals(404, npm.packument("qits", "left-pad").statusCode());
    }
  }

  @Test
  void anUnpublishedPackageIs404SoNpmPublishCanProceed() {
    try (NpmClient npm = client()) {
      HttpResponse<String> answered = npm.packument("npm", encoded("@qits/never-published"));
      assertEquals(404, answered.statusCode());
      assertTrue(NpmClient.parse(answered.body()).has("error"));
    }
  }

  // --- HEAD -------------------------------------------------------------------------------------

  @Test
  void headCarriesTheSameContentLengthAsGet() {
    // Vert.x does not derive HEAD from GET — each needs its own route — and a HEAD reporting 0 is
    // how a caching client convinces itself it already has a tarball it does not have.
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");
    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
      String url = NpmClient.tarballUrl(npm.packumentJson("npm", encoded(subject.name())), "1.0.0");

      HttpResponse<Void> head = npm.head(url);
      assertEquals(200, head.statusCode());
      assertEquals(
          Long.toString(subject.tarball().length),
          head.headers().firstValue("content-length").orElseThrow());

      HttpResponse<Void> packumentHead =
          npm.head(root + "artifacts/npm/npm/" + encoded(subject.name()));
      assertEquals(200, packumentHead.statusCode());
      assertTrue(
          packumentHead.headers().firstValueAsLong("content-length").orElseThrow() > 0,
          "a HEAD packument must still declare its length");
    }
  }

  @Test
  void theAbbreviatedAcceptGetsTheFullDocument() {
    // Spec-legal: the abbreviated type is an optimization a registry may decline, and declining it
    // is the honest first implementation — trimming it wrong silently breaks installs that need a
    // field we dropped. NpmClient sends that Accept on every packument request, so this asserts the
    // consequence: a field the abbreviated form omits is present anyway.
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");
    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
      JsonNode manifest =
          npm.packumentJson("npm", encoded(subject.name())).path("versions").path("1.0.0");
      assertEquals("MIT", manifest.path("license").asText(), "an abbreviated packument omits this");
      assertEquals("a synthetic package for " + subject.name(), manifest.path("description").asText());
    }
  }

  // --- access tracking --------------------------------------------------------------------------

  @Test
  void aTarballReadTouchesItsVersionRowAndTheNextReadInsideTheHourWritesNothing() {
    // The npm half of the GC's access basis, asserted on the row rather than on a response: nothing
    // a client can see says whether the write happened, so a route that quietly stopped touching
    // would pass every other case in this file.
    TinyPackage subject = TinyPackage.of(scopedName(), "1.0.0");

    try (NpmClient npm = client()) {
      npm.publish("npm", encoded(subject.name()), subject.publishDocument("latest"));
      String url = NpmClient.tarballUrl(npm.packumentJson("npm", encoded(subject.name())), "1.0.0");

      versions.getEntityManager().clear();
      assertNull(
          versions.findOne("npm", subject.name(), "1.0.0").orElseThrow().accessedAt,
          "a publish is not an access, and neither is reading the packument");

      assertEquals(200, npm.tarball(url).statusCode());
      versions.getEntityManager().clear();
      Instant first = versions.findOne("npm", subject.name(), "1.0.0").orElseThrow().accessedAt;
      assertTrue(first != null, "a tarball GET must record the access");

      assertEquals(200, npm.tarball(url).statusCode());
      assertEquals(200, npm.head(url).statusCode());
      versions.getEntityManager().clear();
      assertEquals(
          first,
          versions.findOne("npm", subject.name(), "1.0.0").orElseThrow().accessedAt,
          "writes are coalesced to one per row per hour");
    }
  }

  // --- plumbing ---------------------------------------------------------------------------------

  private NpmClient client() {
    return new NpmClient(URI.create(root.toString()));
  }

  private static String contentType(HttpResponse<?> response) {
    return response.headers().firstValue("content-type").orElse("");
  }

  /** A fresh scoped name per case — versions are immutable, so no two tests may share one. */
  private static String scopedName() {
    return "@qits/pkg-" + UNIQUE.incrementAndGet();
  }

  /** What npm actually puts on the wire for a scoped name. */
  private static String encoded(String name) {
    return name.replace("/", "%2f");
  }

  /**
   * The repository row, made through the service rather than the admin endpoint the monolith's copy
   * of this suite used: the JSON admin surface is a service's, not this lib's. The wire form of the
   * type is kept so the cases still read as the API spells them.
   */
  private void ensure(String name, String type) {
    repositoryService.ensure(name, RepositoryTypeProfile.keyOfWireName(type));
  }
}
