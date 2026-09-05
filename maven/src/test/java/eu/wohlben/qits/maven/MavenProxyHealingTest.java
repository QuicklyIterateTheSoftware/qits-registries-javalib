package eu.wohlben.qits.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The bound on self-healing — a plain unit test, because the thing under test is a decision and not
 * a route.
 *
 * <p>Self-healing without a bound is a loop, and the loop is not hypothetical: a fault that
 * re-fetching does not fix (a store that cannot be written, a disk that is full) would have every
 * request delete a row, pull the artifact across the network again, fail in the same place and
 * answer the same error. The only things that would change are the upstream's bill and the size of
 * the log.
 *
 * <p>These four claims are the whole contract {@link MavenRoutes} relies on, and none of them is
 * observable from the wire without arranging a fault that survives a refetch — which is why they are
 * asserted here directly rather than through a fixture that could only ever approximate one.
 */
class MavenProxyHealingTest {

  private static final String REPOSITORY = "central";
  private static final String PATH = "org/example/a/1.0.0/a-1.0.0.jar";

  @Test
  void anEntryThatHasNeverFailedMayHeal() {
    assertTrue(new MavenProxyHealing().mayHeal(REPOSITORY, PATH));
  }

  @Test
  void theBudgetIsSpentAfterTheConfiguredNumberOfFailedAttempts() {
    MavenProxyHealing healing = new MavenProxyHealing();
    for (int attempt = 1; attempt < MavenProxyHealing.ATTEMPTS; attempt++) {
      healing.healingFailed(REPOSITORY, PATH);
      assertTrue(
          healing.mayHeal(REPOSITORY, PATH),
          "a fault that might be transient is worth another try: attempt " + attempt);
    }
    healing.healingFailed(REPOSITORY, PATH);
    assertFalse(
        healing.mayHeal(REPOSITORY, PATH),
        "past the budget the entry is left alone and the real failure is answered");
  }

  @Test
  void aPathThatServedAgainIsForgivenAtOnce() {
    MavenProxyHealing healing = new MavenProxyHealing();
    for (int attempt = 0; attempt < MavenProxyHealing.ATTEMPTS; attempt++) {
      healing.healingFailed(REPOSITORY, PATH);
    }
    assertFalse(healing.mayHeal(REPOSITORY, PATH));

    healing.healed(REPOSITORY, PATH);
    assertTrue(
        healing.mayHeal(REPOSITORY, PATH),
        "a history of failures is not evidence about a path that works now");
  }

  @Test
  void oneExhaustedPathDoesNotStopAnother() {
    // The ledger is per entry and must stay that way: a single poisoned coordinate must not switch
    // healing off for the rest of the cache.
    MavenProxyHealing healing = new MavenProxyHealing();
    for (int attempt = 0; attempt < MavenProxyHealing.ATTEMPTS; attempt++) {
      healing.healingFailed(REPOSITORY, PATH);
    }
    assertFalse(healing.mayHeal(REPOSITORY, PATH));
    assertTrue(healing.mayHeal(REPOSITORY, "org/example/b/1.0.0/b-1.0.0.jar"));
    assertTrue(healing.mayHeal("another-proxy", PATH));
  }
}
