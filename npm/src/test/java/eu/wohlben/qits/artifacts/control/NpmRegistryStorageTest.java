package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.error.NpmException;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.control.CiScreenshotsProfile;
import eu.wohlben.qits.blobstore.control.RepositoryTypeProfiles;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.blobstore.error.BadRequestException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The npm side's storage layer: the two new repository types, and the three tables V3 adds.
 * Everything here is about persistence — the wire protocol lives in {@code eu.wohlben.qits.npm}.
 */
@QuarkusTest
class NpmRegistryStorageTest extends ArtifactsTestSupport {

  private static final String BLOB_A = "a".repeat(64);
  private static final String BLOB_B = "b".repeat(64);

  @Inject ArtifactRepositoryService repositoryService;

  @Inject NpmRegistryService npm;

  @Inject RepositoryTypeProfiles repositoryTypes;

  @Test
  void bothNpmTypesAreEnsuredLikeAnyOtherAndStayImmutable() {
    // V3 widens artifact_repository's check constraint; without it these inserts fail at the
    // database rather than at validation. And the two npm types are as immutable as every other —
    // which is what makes "a proxy rejects publishes" a property of the row rather than of config.
    assertEquals(
        NpmPackagesProfile.KEY, repositoryService.ensure("npm", NpmPackagesProfile.KEY).type);
    assertEquals(NpmProxyProfile.KEY, repositoryService.ensure("npmjs", NpmProxyProfile.KEY).type);
    assertThrows(
        BadRequestException.class, () -> repositoryService.ensure("npm", NpmProxyProfile.KEY));
  }

  @Test
  void theNpmProfilesAcceptNothingThroughTheValidatingUploadPath() {
    // Why a zero cap is safe on a protocol type, restated for npm: BlobService checks accepts()
    // before it ever reads maxBytes(), and both profiles refuse that path outright, so a stray POST
    // to the JSON blob API cannot reach the cap at all. The real cap is
    // qits.artifacts.npm.max-publish-size.
    for (String key : List.of(NpmPackagesProfile.KEY, NpmProxyProfile.KEY)) {
      RepositoryTypeProfile profile = repositoryTypes.require(key);
      assertEquals(false, profile.allowsValidatedUploads());
      assertTrue(profile.allowedMediaTypes().isEmpty());
      assertTrue(profile.requiredMetadataKeys().isEmpty());
      assertEquals(0L, profile.maxBytes());
      assertEquals(false, profile.accepts("application/octet-stream"));
    }
  }

  @Test
  void theWireNamesAreTheKebabFormsTheApiAndTheSchemaUse() {
    // And the stored keys are what the type column has always carried, which is what keeps V3's
    // check constraint valid now that the column is an open string.
    assertEquals("NPM_PACKAGES", NpmPackagesProfile.KEY);
    assertEquals("NPM_PROXY", NpmProxyProfile.KEY);
    assertEquals("npm-packages", repositoryTypes.require(NpmPackagesProfile.KEY).wireName());
    assertEquals("npm-proxy", repositoryTypes.require(NpmProxyProfile.KEY).wireName());
    assertEquals(NpmPackagesProfile.KEY, RepositoryTypeProfile.keyOfWireName("npm-packages"));
    assertEquals(NpmProxyProfile.KEY, RepositoryTypeProfile.keyOfWireName("npm-proxy"));
  }

  @Test
  void requiringARepositoryIsByTypeAndNotMerelyByExistence() {
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    // A row of a type this module does not serve. It used to be oci-images; the npm module no
    // longer sees OCI's profiles at all, which is the point of the split — any other registered
    // type makes the same case.
    repositoryService.ensure("qits", CiScreenshotsProfile.KEY);

    assertEquals(NpmPackagesProfile.KEY, npm.requireNpmRepository("npm"));
    assertThrows(NpmException.class, () -> npm.requireNpmRepository("qits"));
    assertThrows(NpmException.class, () -> npm.requireNpmRepository("no-such-row"));
    assertThrows(NpmException.class, () -> npm.requireNpmRepository(null));
  }

  @Test
  void publishingWritesTheVersionAndMovesTheTagsThatNameIt() {
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    npm.publish("npm", "@qits/angular", "1.0.0", BLOB_A, "sha512-aaa", "aaa", "{\"a\":1}",
        Map.of("latest", "1.0.0"));

    assertEquals(Map.of("latest", "1.0.0"), npm.distTags("npm", "@qits/angular"));
    NpmRegistryService.StoredVersion stored =
        npm.findVersion("npm", "@qits/angular", "1.0.0").orElseThrow();
    assertEquals(BLOB_A, stored.tarballBlobId());
    assertEquals("sha512-aaa", stored.integrity());
    assertEquals("{\"a\":1}", stored.manifestJson(), "the manifest survives the CLOB round trip");
  }

  @Test
  void aVersionIsImmutableButATagIsNot() {
    // The npm restatement of the registry's append-only stance: exactly one mutable table, and it
    // is the dist-tag one. Publishing over a version is refused; `latest` moving is the normal case.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    npm.publish("npm", "left-pad", "1.0.0", BLOB_A, "sha512-a", "a", "{}", Map.of("latest", "1.0.0"));

    assertThrows(
        NpmException.class,
        () ->
            npm.publish(
                "npm", "left-pad", "1.0.0", BLOB_B, "sha512-b", "b", "{}", Map.of("latest", "1.0.0")));

    npm.publish("npm", "left-pad", "1.1.0", BLOB_B, "sha512-b", "b", "{}", Map.of("latest", "1.1.0"));
    assertEquals(Map.of("latest", "1.1.0"), npm.distTags("npm", "left-pad"));
    assertEquals(
        List.of("1.0.0", "1.1.0"),
        npm.listVersions("npm", "left-pad").stream()
            .map(NpmRegistryService.StoredVersion::version)
            .toList(),
        "the version latest used to name stays installable");
  }

  @Test
  void aCollectedVersionKeepsItsNameForeverAndSaysSoRatherThanClaimingImmutability() {
    // What the tombstone is for. Immutability is enforced by looking for the row, so deleting a row
    // would re-open its name for a publish carrying DIFFERENT bytes — one coordinate, two tarballs
    // over its lifetime, which is the mutability this registry exists to refuse. The version is gone
    // from the packument and still unpublishable, and the message says which of the two it is.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    npm.publish("npm", "@qits/ui", "1.0.0", BLOB_A, "sha512-a", "a", "{}", Map.of("latest", "1.0.0"));
    npm.publish("npm", "@qits/ui", "1.0.1-main.gab854a1", BLOB_B, "sha512-b", "b", "{}", Map.of());

    npm.collect("npm", "@qits/ui", "1.0.1-main.gab854a1");
    // A version that was never published is untouched by any of this. Written before the refusal
    // below, because that one rolls its transaction back and this suite shares one session.
    npm.publish("npm", "@qits/ui", "1.0.2", BLOB_B, "sha512-d", "d", "{}", Map.of("latest", "1.0.2"));

    assertTrue(
        npm.findVersion("npm", "@qits/ui", "1.0.1-main.gab854a1").isEmpty(),
        "the row is gone, so the assembled packument no longer lists the version");
    assertEquals("1.0.2", npm.findVersion("npm", "@qits/ui", "1.0.2").orElseThrow().version());
    NpmException refused =
        assertThrows(
            NpmException.class,
            () ->
                npm.publish(
                    "npm", "@qits/ui", "1.0.1-main.gab854a1", BLOB_A, "sha512-c", "c", "{}",
                    Map.of()));
    assertEquals(403, refused.statusCode());
    assertTrue(
        refused.getMessage().contains("removed by garbage collection"),
        "not 'immutable' — there is no version to look at, and saying so sends a pusher looking for"
            + " one: " + refused.getMessage());
  }

  @Test
  void collectingRefusesAVersionADistTagStillNamesAndTouchesNoLiveRowsImmutability() {
    // Two halves of "the mechanism, not the policy". A dist-tag naming a version the packument no
    // longer lists is a broken package to every npm client, so the primitive refuses it rather than
    // trusting the strategy that drives it never to ask. And a live row's 403 is the one it always
    // was: the tombstone adds a second refusal, it does not reword the first.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    npm.publish("npm", "left-pad", "1.0.0", BLOB_A, "sha512-a", "a", "{}", Map.of("latest", "1.0.0"));

    NpmException tagged =
        assertThrows(NpmException.class, () -> npm.collect("npm", "left-pad", "1.0.0"));
    assertEquals(409, tagged.statusCode());
    assertTrue(tagged.getMessage().contains("latest dist-tag names it"), tagged.getMessage());
    assertTrue(npm.findVersion("npm", "left-pad", "1.0.0").isPresent(), "and nothing was deleted");

    NpmException live =
        assertThrows(
            NpmException.class,
            () ->
                npm.publish(
                    "npm", "left-pad", "1.0.0", BLOB_B, "sha512-b", "b", "{}", Map.of()));
    assertTrue(live.getMessage().contains("published versions are immutable"), live.getMessage());
  }

  @Test
  void aBarePublishOfAPrereleaseCannotTakeLatestBackwards() {
    // The foot-gun this rule closes: a bare `npm publish` means --tag latest, so a main build
    // publishing <release>-main.g<sha> would move latest onto a prerelease permanently and every
    // consumer installing without a range would get a main build.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    npm.publish("npm", "@qits/ui", "2026.801.63140", BLOB_A, "sha512-a", "a", "{}",
        Map.of("latest", "2026.801.63140"));

    NpmException refused =
        assertThrows(
            NpmException.class,
            () ->
                npm.publish(
                    "npm", "@qits/ui", "2026.801.63140-main.g0fe7780", BLOB_B, "sha512-b", "b", "{}",
                    Map.of("latest", "2026.801.63140-main.g0fe7780")));
    assertEquals(403, refused.statusCode());
    assertTrue(refused.getMessage().contains("--tag main"), refused.getMessage());

    // The refusal takes the whole publish with it — it is thrown inside publish()'s transaction,
    // which is the same shape the immutability refusal has and the reason nothing half-lands.
    assertTrue(npm.findVersion("npm", "@qits/ui", "2026.801.63140-main.g0fe7780").isEmpty());
    assertEquals(Map.of("latest", "2026.801.63140"), npm.distTags("npm", "@qits/ui"));
  }

  @Test
  void everyOtherTagMovesFreelyAndANewerReleaseStillMovesLatest() {
    // Every write first, then ONE read — the shape the rest of this suite already keeps, and here
    // it is load-bearing rather than tidy. A @QuarkusTest already has a request context, so all
    // these calls share one Hibernate session; a read between two writes creates that session
    // outside any transaction, and a dist-tag row it loaded is then never flushed by the
    // transaction that moves it. That is a property of the test — a route handler activates a fresh
    // context, and therefore a fresh session, per call — but it looks exactly like a lost update.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    npm.publish("npm", "@qits/ui", "2026.801.63140", BLOB_A, "sha512-a", "a", "{}",
        Map.of("latest", "2026.801.63140"));

    // The prerelease under its own tag is exactly what the pipelines should be doing, and it is
    // unguarded: only `latest` has an ordering rule.
    npm.publish("npm", "@qits/ui", "2026.801.63140-main.g0fe7780", BLOB_B, "sha512-b", "b", "{}",
        Map.of("main", "2026.801.63140-main.g0fe7780"));

    // And the next release moves latest forward, as it always did.
    npm.publish("npm", "@qits/ui", "2026.802.100000", BLOB_A, "sha512-c", "c", "{}",
        Map.of("latest", "2026.802.100000"));

    assertEquals(
        Map.of("latest", "2026.802.100000", "main", "2026.801.63140-main.g0fe7780"),
        npm.distTags("npm", "@qits/ui"));
  }

  @Test
  void theFirstLatestIsAlwaysAllowedHoweverItIsSpelled() {
    // There is nothing to move backwards from, so a package whose very first publish is a
    // prerelease still gets a resolvable `latest` — refusing that would make a package nobody can
    // install by name.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    npm.publish("npm", "first-pre", "0.0.1-main.gabc1234", BLOB_A, "sha512-a", "a", "{}",
        Map.of("latest", "0.0.1-main.gabc1234"));
    assertEquals(Map.of("latest", "0.0.1-main.gabc1234"), npm.distTags("npm", "first-pre"));
  }

  @Test
  void theLatestRuleAllowsEqualAndRefusesWhatItCannotOrder() {
    // Called directly: the equal case is unreachable through publish(), because a second publish of
    // the same version dies on immutability first — which is precisely why equal is allowed here
    // rather than guarded.
    NpmRegistryService.requireLatestMayMoveTo("p", "1.2.3", "1.2.3");
    NpmRegistryService.requireLatestMayMoveTo("p", "1.2.3", "1.2.4");
    NpmRegistryService.requireLatestMayMoveTo("p", "1.2.3-rc.1", "1.2.3");

    assertThrows(
        NpmException.class, () -> NpmRegistryService.requireLatestMayMoveTo("p", "1.2.3", "1.2.2"));
    // Strict beats silent: a version that cannot be ordered cannot be proved not to be a step
    // backwards, and this platform publishes semver only.
    NpmException candidate =
        assertThrows(
            NpmException.class,
            () -> NpmRegistryService.requireLatestMayMoveTo("p", "1.2.3", "nightly"));
    assertTrue(candidate.getMessage().contains("nightly is not a semver version"),
        candidate.getMessage());
    NpmException current =
        assertThrows(
            NpmException.class,
            () -> NpmRegistryService.requireLatestMayMoveTo("p", "nightly", "1.2.3"));
    assertTrue(current.getMessage().contains("nightly is not a semver version"),
        current.getMessage());
  }

  @Test
  void aProxiedVersionIsRecordedOnceAndIsIdempotentAfterThat() {
    // Two concurrent installs of the same dependency are the normal case rather than the edge.
    repositoryService.ensure("npmjs", NpmProxyProfile.KEY);
    npm.recordProxiedVersion("npmjs", "left-pad", "1.3.0", BLOB_A, "sha512-up", "up", "{}");
    npm.recordProxiedVersion("npmjs", "left-pad", "1.3.0", BLOB_B, "sha512-other", "other", "{}");

    NpmRegistryService.StoredVersion stored =
        npm.findVersion("npmjs", "left-pad", "1.3.0").orElseThrow();
    assertEquals(BLOB_A, stored.tarballBlobId(), "the first write wins; a tarball is immutable");
    assertEquals("sha512-up", stored.integrity(), "upstream's integrity, re-emitted unmodified");
    assertTrue(npm.distTags("npmjs", "left-pad").isEmpty(), "a proxy stores no dist-tags of its own");
  }

  @Test
  void aCachedPackumentIsStoredVerbatimAndRevalidationOnlyMovesItsClock() {
    repositoryService.ensure("npmjs", NpmProxyProfile.KEY);
    Instant fetched = Instant.now().minusSeconds(600);
    npm.storeProxyPackument("npmjs", "left-pad", "{\"name\":\"left-pad\"}", "\"v1\"", fetched);

    // A 304 means the document did not change, so the revalidation path moves the clock and the
    // validator without touching the CLOB at all.
    npm.touchProxyPackument("npmjs", "left-pad", "\"v2\"", Instant.now());

    // Read ONCE, and only after the write. A read before it would be served from the session bound
    // to this test's request context and would still hold the pre-update row — which says nothing
    // about this service, since a route handler activates a fresh context (and therefore a fresh
    // session) per call. Asserting after the touch is also the stronger claim: the document is
    // proved to have survived a revalidation rather than merely to have been stored.
    NpmRegistryService.CachedPackument after =
        npm.findProxyPackument("npmjs", "left-pad").orElseThrow();
    assertEquals("{\"name\":\"left-pad\"}", after.doc(), "revalidation must not rewrite the document");
    assertEquals("\"v2\"", after.etag());
    assertTrue(
        after.fetchedAt().isAfter(fetched.plusSeconds(300)),
        "revalidating must move the clock, or every later request revalidates again: "
            + after.fetchedAt());
  }
}
