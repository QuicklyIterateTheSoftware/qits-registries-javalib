package eu.wohlben.qits.registry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

/**
 * qits-702: the {@code @DefaultBean} every deployment that supplies no {@link ContentHashLedger}
 * of its own runs — {@code qits-mirror-service}, whose proxy routes have nowhere to store the
 * value and no business doing so.
 *
 * <p>Plain JUnit, no container: the class has no field and no dependency, so proving it is a no-op
 * is a direct call, not a wiring test. {@code MavenContentHashTest} and {@code NpmContentHashTest}
 * are the wiring tests, for the bean a service DOES supply.
 */
class NoContentHashLedgerTest {

  @Test
  void recordIsANoOpWhateverItIsHanded() {
    ContentHashLedger ledger = new NoContentHashLedger();

    assertDoesNotThrow(
        () -> {
          ledger.record("maven", "maven", "eu.wohlben.qits:x", "1.0.0", "v1:sha256:" + "a".repeat(64));
          // Called again with a DIFFERENT value for the same key: a real ledger would 409 here,
          // because that is exactly the conflict its immutability rule exists to catch. This one
          // has nothing to disagree with, so it must not either.
          ledger.record("maven", "maven", "eu.wohlben.qits:x", "1.0.0", "v1:sha256:" + "b".repeat(64));
        });
  }
}
