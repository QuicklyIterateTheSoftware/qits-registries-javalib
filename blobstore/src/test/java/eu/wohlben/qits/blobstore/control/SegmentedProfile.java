package eu.wohlben.qits.blobstore.control;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * The store at its production chunk size, with segments small enough that a test fills several.
 *
 * <p>The rest of the suite runs 64-byte chunks, which never leave the heap: a value that small is
 * stored inline, so the TOAST relation — where blob bytes really live, and where the disk that
 * compaction returns really is — would stay empty and every size figure would be noise. At 1 MiB the
 * chunks go out of line exactly as in production, and an 8 MiB segment rolls every four 2 MiB blobs.
 */
public class SegmentedProfile implements QuarkusTestProfile {

  static final int CHUNK = 1 << 20;

  static final long SEGMENT = 8L << 20;

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "qits.artifacts.blob-chunk-size", String.valueOf(CHUNK),
        "qits.artifacts.blobs.segment-size", String.valueOf(SEGMENT));
  }
}
