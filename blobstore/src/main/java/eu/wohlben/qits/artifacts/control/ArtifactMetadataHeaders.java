package eu.wohlben.qits.artifacts.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one reading of {@code X-Artifacts-Meta-<key>} request headers, shared by every wire that
 * accepts metadata (the JAX-RS blob API, the docs bundle PUT). Deliberately framework-agnostic —
 * it takes header name/value pairs, so a JAX-RS resource folds its {@code HttpHeaders} and a
 * Vert.x route passes its {@code MultiMap} (which already iterates as entries) without this
 * library gaining either dependency.
 *
 * <p>Semantics, unchanged from the blob API where they originated: the prefix matches
 * case-insensitively, the key keeps the spelling the client sent (minus the prefix), and the
 * first occurrence of a repeated header wins. {@link MetadataKeys#SERVER_OWNED} keys are dropped
 * silently — the server stamps those itself where they apply.
 *
 * <p><b>The caps are the point.</b> Metadata arrives on wires that include unauthenticated ones,
 * so the map is bounded before it approaches a table: at most {@value #MAX_KEYS} keys, key length
 * ≤ {@value #MAX_KEY_LENGTH}, value length ≤ {@value #MAX_VALUE_LENGTH} — the last two mirror the
 * store's metadata column limits, so an oversized value is a clean 400 at the wire instead of a
 * JDBC truncation surprise. A violation throws {@link IllegalArgumentException} with a message
 * naming the cap; each wire maps that to its own 400 envelope.
 */
public final class ArtifactMetadataHeaders {

  /** The header prefix. {@code X-Artifacts-Meta-git.branch.name: main} → {@code git.branch.name}. */
  public static final String PREFIX = "X-Artifacts-Meta-";

  public static final int MAX_KEYS = 32;
  public static final int MAX_KEY_LENGTH = 255;
  public static final int MAX_VALUE_LENGTH = 4000;

  private static final String PREFIX_LC = PREFIX.toLowerCase(Locale.ROOT);

  private ArtifactMetadataHeaders() {}

  /**
   * Collect the metadata map from header name/value pairs (first occurrence of a name wins).
   *
   * @throws IllegalArgumentException when a cap is exceeded
   */
  public static Map<String, String> from(Iterable<Map.Entry<String, String>> headers) {
    Map<String, String> metadata = new LinkedHashMap<>();
    for (Map.Entry<String, String> header : headers) {
      String name = header.getKey();
      if (name == null || !name.toLowerCase(Locale.ROOT).startsWith(PREFIX_LC)) {
        continue;
      }
      String key = name.substring(PREFIX.length());
      String value = header.getValue();
      if (key.isBlank() || value == null || MetadataKeys.SERVER_OWNED.contains(key)) {
        continue;
      }
      if (key.length() > MAX_KEY_LENGTH) {
        throw new IllegalArgumentException(
            "metadata key longer than " + MAX_KEY_LENGTH + " characters: " + key);
      }
      if (value.length() > MAX_VALUE_LENGTH) {
        throw new IllegalArgumentException(
            "metadata value for '" + key + "' longer than " + MAX_VALUE_LENGTH + " characters");
      }
      metadata.putIfAbsent(key, value);
      if (metadata.size() > MAX_KEYS) {
        throw new IllegalArgumentException("more than " + MAX_KEYS + " metadata keys");
      }
    }
    return metadata;
  }

  /** The multi-valued form (JAX-RS {@code getRequestHeaders()} shape): first value per name. */
  public static Map<String, String> fromLists(Map<String, ? extends List<String>> headers) {
    Map<String, String> flat = new LinkedHashMap<>();
    headers.forEach(
        (name, values) -> {
          if (name != null && values != null && !values.isEmpty()) {
            flat.putIfAbsent(name, values.get(0));
          }
        });
    return from(flat.entrySet());
  }
}
