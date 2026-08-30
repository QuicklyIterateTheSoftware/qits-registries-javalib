package eu.wohlben.qits.blobstore.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one header reading every metadata-accepting wire shares. */
class ArtifactMetadataHeadersTest {

  @Test
  void prefixedHeadersLandInTheMapWithTheirSentSpelling() {
    Map<String, String> metadata =
        ArtifactMetadataHeaders.from(
            entries(
                "X-Artifacts-Meta-git.branch.name", "main",
                "Content-Type", "application/gzip",
                "x-artifacts-meta-git.commit.hash", "abc123"));
    assertEquals(Map.of("git.branch.name", "main", "git.commit.hash", "abc123"), metadata);
  }

  @Test
  void firstOccurrenceOfARepeatedHeaderWins() {
    Map<String, String> metadata =
        ArtifactMetadataHeaders.from(
            List.of(
                Map.entry("X-Artifacts-Meta-k", "first"), Map.entry("X-Artifacts-Meta-k", "second")));
    assertEquals("first", metadata.get("k"));
  }

  @Test
  void serverOwnedKeysAreDroppedSilently() {
    Map<String, String> metadata =
        ArtifactMetadataHeaders.from(
            entries(
                "X-Artifacts-Meta-mediatype", "text/evil",
                "X-Artifacts-Meta-created-at", "1970-01-01T00:00:00Z",
                "X-Artifacts-Meta-kept", "yes"));
    assertEquals(Map.of("kept", "yes"), metadata);
  }

  @Test
  void theCapsRefuseRatherThanTruncate() {
    Map<String, String> tooMany = new LinkedHashMap<>();
    for (int i = 0; i <= ArtifactMetadataHeaders.MAX_KEYS; i++) {
      tooMany.put("X-Artifacts-Meta-key-" + i, "v");
    }
    IllegalArgumentException keys =
        assertThrows(
            IllegalArgumentException.class,
            () -> ArtifactMetadataHeaders.from(tooMany.entrySet()));
    assertTrue(keys.getMessage().contains(String.valueOf(ArtifactMetadataHeaders.MAX_KEYS)));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ArtifactMetadataHeaders.from(
                entries("X-Artifacts-Meta-" + "k".repeat(256), "v")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ArtifactMetadataHeaders.from(
                entries("X-Artifacts-Meta-k", "v".repeat(4001))));
  }

  @Test
  void theMultiValuedFormTakesTheFirstValuePerName() {
    Map<String, String> metadata =
        ArtifactMetadataHeaders.fromLists(
            Map.of("X-Artifacts-Meta-k", List.of("first", "second")));
    assertEquals(Map.of("k", "first"), metadata);
  }

  private static List<Map.Entry<String, String>> entries(String... namesAndValues) {
    List<Map.Entry<String, String>> out = new java.util.ArrayList<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      out.add(Map.entry(namesAndValues[i], namesAndValues[i + 1]));
    }
    return out;
  }
}
