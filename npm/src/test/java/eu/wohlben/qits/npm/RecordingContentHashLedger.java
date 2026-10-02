package eu.wohlben.qits.npm;

import eu.wohlben.qits.artifacts.error.NpmException;
import eu.wohlben.qits.registry.ContentHashLedger;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake {@link ContentHashLedger}, in test sources so it is a CDI bean of the application under
 * test — the {@code maven/RecordingContentHashLedger} shape, restated for npm's own exception type.
 *
 * <p>No {@code @DefaultBean} and no {@code @Alternative}: a plain bean of the interface simply
 * outranks {@code NoContentHashLedger}, which IS one, so this is what every test in this module
 * runs against.
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
      throw new NpmException(
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
