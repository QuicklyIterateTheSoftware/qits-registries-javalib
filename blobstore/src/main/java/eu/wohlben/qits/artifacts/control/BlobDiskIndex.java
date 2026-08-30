package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * What the store actually holds: every promoted blob, with its size.
 *
 * <p>The identity rows cannot answer this. 96% of the stored bytes have no identity row of any kind
 * — OCI layers and configs get none by design — and some of what is stored has no row and no
 * manifest either: the blob-upload session accepts bytes before a manifest binds them, so an upload
 * that never finished the handshake leaves content reachable from nothing. A view built only on
 * identity rows under-reports the store by that much and cannot say so; this index is what lets a
 * summary name the gap instead.
 *
 * <p><b>Its name outlived the disk.</b> "On disk" now means "a promoted {@code blob} row", and the
 * census contract is unchanged by that: a blob no identity row names is exactly what a row-less file
 * used to be, and staging content is invisible here exactly as the old temp directory was.
 *
 * <p><b>No cache, no staleness, no invalidation.</b> This used to be a two-level directory walk
 * behind a 60-second snapshot that {@code BlobStore.promote} had to invalidate. One indexed query
 * replaces all of it, and a query cannot go stale — so the snapshot, the age ceiling and {@code
 * invalidate()} are gone rather than ported.
 */
@ApplicationScoped
public class BlobDiskIndex {

  @Inject BlobDb db;

  /**
   * Digest (bare hex) to stored bytes, for every promoted blob.
   *
   * @return unmodifiable, and freshly read on every call
   */
  public Map<String, Long> sizes() {
    return db.autocommit(
        "Failed to read the blob index",
        connection -> {
          try (PreparedStatement select =
                  connection.prepareStatement("select id, size_bytes from blob");
              ResultSet rows = select.executeQuery()) {
            Map<String, Long> sizes = new HashMap<>();
            while (rows.next()) {
              sizes.put(rows.getString(1), rows.getLong(2));
            }
            return Map.copyOf(sizes);
          }
        });
  }
}
