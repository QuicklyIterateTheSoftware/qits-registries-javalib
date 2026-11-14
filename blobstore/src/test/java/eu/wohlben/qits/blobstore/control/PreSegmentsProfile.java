package eu.wohlben.qits.blobstore.control;

import eu.wohlben.qits.blobstore.testdb.BlobTables;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link SegmentedProfile}'s sizes, with the blob datasource pointed at a database that holds the
 * blob tables as they stood BEFORE segments — a consumer that has bumped the library and not yet run
 * {@code db/blobstore-segments-upgrade.sql}.
 */
public class PreSegmentsProfile extends SegmentedProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
    overrides.put("quarkus.datasource.blobs.jdbc.url", BlobTables.preSegmentsUrl());
    return overrides;
  }
}
