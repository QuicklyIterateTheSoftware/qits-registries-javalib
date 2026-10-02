package eu.wohlben.qits.maven;

import eu.wohlben.qits.artifacts.error.MavenException;
import eu.wohlben.qits.registry.ContentHashLedger;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * A fake {@link ContentHashLedger}, in test sources so it is a CDI bean of the application under
 * test — the {@code BodyCeilingProbeRoutes} shape, for this seam.
 *
 * <p>No {@code @DefaultBean} and no {@code @Alternative}: a plain bean of the interface simply
 * outranks {@code NoContentHashLedger}, which IS one, so this is what every test in this module
 * runs against — exactly what proves the default bean's own no-op behaviour would be exercised by
 * an application that supplies nothing, which this one does not.
 *
 * <p>Records every call rather than only the latest, so a test can tell "recorded once" from
 * "recorded twice with the same value" when that distinction matters, and enforces the ledger's own
 * immutability rule — first write wins, a different value for the same key is the ledger's 409 —
 * the same shape a real implementation ({@code JpaContentHashLedger}, in qits-artifacts-service)
 * is specified to have.
 */
@ApplicationScoped
class RecordingContentHashLedger implements ContentHashLedger {

  record Call(String ecosystem, String repository, String name, String version, String value) {}

  private final List<Call> calls = new CopyOnWriteArrayList<>();
  private final Map<String, String> byKey = new ConcurrentHashMap<>();

  @Override
  public void record(String ecosystem, String repository, String name, String version, String value) {
    String key = ecosystem + ":" + repository + ":" + name + ":" + version;
    String stored = byKey.putIfAbsent(key, value);
    if (stored != null && !stored.equals(value)) {
      throw new MavenException(
          409,
          name + "@" + version + " already records content hash " + stored + "; this upload says "
              + value);
    }
    calls.add(new Call(ecosystem, repository, name, version, value));
  }

  int callCountFor(String name, String version) {
    return (int) calls.stream().filter(c -> c.name().equals(name) && c.version().equals(version)).count();
  }

  String valueFor(String name, String version) {
    return calls.stream()
        .filter(c -> c.name().equals(name) && c.version().equals(version))
        .map(Call::value)
        .findFirst()
        .orElse(null);
  }
}
