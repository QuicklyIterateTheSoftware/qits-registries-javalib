package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.error.BadRequestException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Open registration: what the core ships, how a key resolves, and what an unknown one does. */
@QuarkusTest
class RepositoryTypeProfilesTest extends ArtifactsTestSupport {

  @Inject RepositoryTypeProfiles repositoryTypes;

  @Inject ArtifactRepositoryService repositoryService;

  @Test
  void theCoreShipsTheTwoCiProfilesAndNamesNoPackageFormat() {
    // The point of the split: npm, maven and OCI profiles live in qits-registries, so a build of
    // this lib alone knows only the two types it enforces itself.
    assertEquals(Set.of(CiScreenshotsProfile.KEY, CiVideosProfile.KEY), repositoryTypes.keys());
  }

  @Test
  void aKeyResolvesToTheProfileThatClaimsIt() {
    RepositoryTypeProfile shots = repositoryTypes.require(CiScreenshotsProfile.KEY);
    assertEquals(CiScreenshotsProfile.KEY, shots.key());
    assertTrue(shots.allowsValidatedUploads());
    assertTrue(shots.accepts("image/png"));
    assertFalse(shots.accepts("video/mp4"));
    assertEquals(25L * 1024 * 1024, shots.maxBytes());
    assertTrue(shots.requiredMetadataKeys().contains("qits.diff.hash"));

    RepositoryTypeProfile videos = repositoryTypes.require(CiVideosProfile.KEY);
    assertTrue(videos.accepts("video/mp4"));
    assertEquals(64L * 1024 * 1024, videos.maxBytes());
  }

  @Test
  void theStoredKeysAreTheOnesTheColumnHasAlwaysCarried() {
    // Load-bearing: existing rows and the services' ck_artifact_repository_type constraint spell
    // the type this way, and the API spells it in kebab.
    assertEquals("CI_SCREENSHOTS", CiScreenshotsProfile.KEY);
    assertEquals("CI_VIDEOS", CiVideosProfile.KEY);
    assertEquals(
        "CI_SCREENSHOTS", repositoryService.ensure("stored", CiScreenshotsProfile.KEY).type);
    assertEquals("ci-screenshots", RepositoryTypeProfile.wireNameOf(CiScreenshotsProfile.KEY));
    assertEquals("ci-videos", RepositoryTypeProfile.wireNameOf(CiVideosProfile.KEY));
    assertEquals(CiScreenshotsProfile.KEY, RepositoryTypeProfile.keyOfWireName("ci-screenshots"));
    assertEquals(CiVideosProfile.KEY, RepositoryTypeProfile.keyOfWireName("ci-videos"));
    assertEquals(
        CiScreenshotsProfile.KEY,
        RepositoryTypeProfile.keyOfWireName(" CI_SCREENSHOTS "),
        "tolerant of the stored form itself, as the enum's fromWire was");
    assertEquals(CiScreenshotsProfile.KEY, repositoryTypes.requireWireName("ci-screenshots").key());
  }

  @Test
  void anUnknownKeyIsAHardErrorAtUse() {
    assertThrows(BadRequestException.class, () -> repositoryTypes.require("NO_SUCH_TYPE"));
    assertThrows(BadRequestException.class, () -> repositoryTypes.require(null));
    assertThrows(BadRequestException.class, () -> repositoryTypes.requireWireName("no-such-type"));
    // And a row is never written with one: ensure refuses before it persists.
    assertThrows(
        BadRequestException.class, () -> repositoryService.ensure("nope", "NPM_PACKAGES"));
    assertThrows(BadRequestException.class, () -> repositoryService.ensure("nope", ""));
    assertTrue(repositoryTypes.find("NO_SUCH_TYPE").isEmpty());
  }
}
