package eu.wohlben.qits.blobstore.control;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Production-size chunks, 4 MiB segments and an 8 MiB shrink budget: small enough that forty 1 MiB
 * blobs staged into one segment make it ten times the segment size — the shape segment 0 has after
 * the upgrade — and that one budget is a handful of chunks.
 */
public class ShrinkProfile implements QuarkusTestProfile {

  static final int CHUNK = 1 << 20;

  static final long SEGMENT = 4L << 20;

  static final long BUDGET = 8L << 20;

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "qits.artifacts.blob-chunk-size", String.valueOf(CHUNK),
        "qits.artifacts.blobs.segment-size", String.valueOf(SEGMENT),
        "qits.artifacts.blobs.compaction.shrink-budget", String.valueOf(BUDGET));
  }
}
