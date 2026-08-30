package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.entity.ArtifactRecord;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ArtifactQueryServiceTest extends ArtifactsTestSupport {

  @Inject ArtifactQueryService queryService;

  @Inject ArtifactRepositoryService repositoryService;

  @Inject ArtifactRecordRepository recordRepository;

  // Explicit, ordered timestamps → deterministic newest-first (no wall-clock flakiness).
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:01Z");
  private static final Instant T2 = Instant.parse("2026-01-01T00:00:02Z");
  private static final Instant T3 = Instant.parse("2026-01-01T00:00:03Z");
  private static final Instant T4 = Instant.parse("2026-01-01T00:00:04Z");

  @BeforeEach
  void seed() {
    repositoryService.ensure("shots", CiScreenshotsProfile.KEY);
    // Same (branch, flow) twice — T2 is the newer golden; plus other groups.
    persist("main", "checkout", T1, "b1");
    persist("main", "checkout", T2, "b2");
    persist("feature", "checkout", T3, "b3");
    persist("main", "login", T4, "b4");
  }

  @Test
  void predicatesComposeAsAnd() {
    List<ArtifactRecord> result =
        queryService.query(
            "shots", Map.of("git.branch.name", "main", "qits.userflow.name", "checkout"), false);
    // Both main/checkout records, newest-first.
    assertEquals(List.of("b2", "b1"), result.stream().map(r -> r.blobId).toList());
  }

  @Test
  void singlePredicateFiltersByBranch() {
    List<ArtifactRecord> result =
        queryService.query("shots", Map.of("git.branch.name", "main"), false);
    assertEquals(List.of("b4", "b2", "b1"), result.stream().map(r -> r.blobId).toList());
  }

  @Test
  void latestCollapsesToNewestPerBranchAndFlow() {
    List<ArtifactRecord> result = queryService.query("shots", Map.of(), true);
    // One per (branch, flow) group; main/checkout collapses to the newer b2, not b1.
    var ids = result.stream().map(r -> r.blobId).toList();
    assertEquals(3, ids.size());
    assertTrue(ids.contains("b2"));
    assertTrue(ids.contains("b3"));
    assertTrue(ids.contains("b4"));
    assertTrue(!ids.contains("b1"), "the older golden for main/checkout is collapsed away");
  }

  @Test
  void queryOnUnknownRepositoryIsNotFound() {
    assertThrows(NotFoundException.class, () -> queryService.query("nope", Map.of(), false));
  }

  @Test
  void typedBoundsAreInclusiveAndNeverAccessedIsExplicit() {
    QuarkusTransaction.requiringNew().run(() -> {
      ArtifactRecord row = recordRepository.find("blobId", "b2").firstResult();
      row.accessedAt = T3;
    });
    ArtifactListFilter accessed = new ArtifactListFilter(T3, T3, T2, T2, 10L, 10L, false);
    assertEquals(
        List.of("b2"),
        queryService.query("shots", Map.of(), false, accessed).stream().map(r -> r.blobId).toList());
    ArtifactListFilter never = new ArtifactListFilter(null, null, null, null, null, null, true);
    assertEquals(3, queryService.query("shots", Map.of(), false, never).size());
  }

  private void persist(String branch, String flow, Instant createdAt, String blobId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ArtifactRecord r = new ArtifactRecord();
              r.id = UUID.randomUUID().toString();
              r.repository = "shots";
              r.blobId = blobId;
              r.mediatype = "image/png";
              r.size = 10;
              r.createdAt = createdAt;
              r.metadata =
                  new java.util.HashMap<>(
                      Map.of("git.branch.name", branch, "qits.userflow.name", flow));
              recordRepository.persist(r);
            });
  }
}
