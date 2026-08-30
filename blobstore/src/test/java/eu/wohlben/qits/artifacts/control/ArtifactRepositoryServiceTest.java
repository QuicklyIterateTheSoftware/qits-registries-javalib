package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.artifacts.error.BadRequestException;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ArtifactRepositoryServiceTest extends ArtifactsTestSupport {

  @Inject ArtifactRepositoryService service;

  @Test
  void ensureIsIdempotent() {
    var first = service.ensure("shots", CiScreenshotsProfile.KEY);
    var second = service.ensure("shots", CiScreenshotsProfile.KEY);
    assertEquals(first.name, second.name);
    assertEquals(1, service.list().size());
  }

  @Test
  void ensureRejectsATypeChange() {
    service.ensure("shots", CiScreenshotsProfile.KEY);
    assertThrows(
        BadRequestException.class, () -> service.ensure("shots", CiVideosProfile.KEY));
  }

  @Test
  void requireFailsOnUnknownRepository() {
    assertThrows(NotFoundException.class, () -> service.require("nope"));
  }
}
