package eu.wohlben.qits.artifacts.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} here, as the three keys a
 * deployment would supply for the {@code blobs} datasource.
 *
 * <p>It is a config source rather than three lines in {@code src/test/resources/application
 * .properties} because the port is chosen at run time — the instance takes a free one, so nothing
 * can be written down ahead of the JVM that starts it. The ordinal sits above application.properties
 * (250), and it joins the application through {@code META-INF/services}, which is how a config
 * source does that without being a bean.
 *
 * <p><b>The datasource is named, not the default one, and that is the point.</b> Every consumer of
 * this library reaches the blob tables through a datasource of its own — {@code artifacts}, {@code
 * mirror}, {@code githost} — resolved by name from {@code qits.artifacts.blobs-datasource}. Testing
 * against the default datasource would leave that lookup, the one genuinely new piece of wiring,
 * unexercised.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  private final Map<String, String> values =
      Map.of(
          "quarkus.datasource.blobs.jdbc.url", BlobTables.url(),
          "quarkus.datasource.blobs.username", EmbeddedPg.USER,
          "quarkus.datasource.blobs.password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
