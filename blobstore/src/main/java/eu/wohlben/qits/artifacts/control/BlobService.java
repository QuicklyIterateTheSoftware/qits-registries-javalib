package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.dto.UploadResult;
import eu.wohlben.qits.artifacts.entity.ArtifactRecord;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.error.BadRequestException;
import eu.wohlben.qits.artifacts.error.InternalServerErrorException;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The upload and serve paths over {@link BlobStore} + the metadata rows. Upload streams the
 * provided body to disk (the per-type cap enforced while streaming), sniffs the true media type,
 * enforces the repository type's profile, server-stamps {@code created-at}, dedupes by content, and
 * records a distinct metadata row. Staging/promotion happen outside the metadata-row transaction so
 * a slow large upload can't time one out (an orphaned content file would be harmless — it is
 * content-addressed — but the row insert is a short separate transaction anyway).
 */
@ApplicationScoped
public class BlobService {

  /** Enough to carry the PNG IHDR (width/height at offsets 16/20) and every magic signature. */
  private static final int SNIFF_BYTES = 32;

  @Inject ArtifactRepositoryService repositoryService;

  @Inject RepositoryTypeProfiles repositoryTypes;

  @Inject BlobStore blobStore;

  @Inject ArtifactRecordRepository records;

  @Inject ArtifactAccessTracker accessTracker;

  /** The bytes + resolved mediatype for a serve response. */
  public record BlobContent(String mediatype, long size, InputStream stream) {}

  public UploadResult upload(
      String repoName,
      String claimedContentType,
      Map<String, String> clientMetadata,
      InputStream body) {
    ArtifactRepository repo = repositoryService.require(repoName);
    String type = repo.type;
    RepositoryTypeProfile profile = repositoryTypes.require(type);

    BufferedInputStream in = new BufferedInputStream(body);
    byte[] head = peek(in, SNIFF_BYTES);

    String mediatype = MediaTypeSniffer.sniff(head, claimedContentType);
    if (mediatype == null) {
      throw new BadRequestException(
          "Could not determine media type (no magic-byte signature and no usable Content-Type)");
    }
    // A protocol type refuses outright: accepts() is false whatever the bytes are, so a stray POST
    // here never reaches the cap. A validated type refuses anything outside its allowed set.
    if (!profile.accepts(mediatype)) {
      throw new BadRequestException(
          "Media type "
              + mediatype
              + " is not accepted by a "
              + type
              + " repository (allowed: "
              + new TreeSet<>(profile.allowedMediaTypes())
              + ")");
    }

    // The metadata to store: client-supplied keys minus the ones the server owns (mediatype,
    // created-at) — a wire-supplied value for those is discarded and re-stamped below.
    Map<String, String> metadata = new HashMap<>();
    if (clientMetadata != null) {
      clientMetadata.forEach(
          (k, v) -> {
            if (k != null && !k.isBlank() && !MetadataKeys.SERVER_OWNED.contains(k)) {
              metadata.put(k, v);
            }
          });
    }
    requireProfileKeys(profile, metadata);
    if ("image/png".equals(mediatype)) {
      verifyPngDimensions(head, metadata);
    }

    BlobStore.StagedBlob staged = blobStore.stage(in, profile.maxBytes());
    boolean existing = blobStore.promote(staged);

    // Truncate to microseconds so the stored column (H2 timestamp(6)) and the created-at metadata
    // string stay byte-identical across a round-trip.
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    metadata.put(MetadataKeys.MEDIATYPE, mediatype);
    metadata.put(MetadataKeys.CREATED_AT, now.toString());

    ArtifactRecord record = new ArtifactRecord();
    record.id = UUID.randomUUID().toString();
    record.repository = repoName;
    record.blobId = staged.sha256();
    record.mediatype = mediatype;
    record.size = staged.size();
    record.createdAt = now;
    record.metadata = metadata;
    QuarkusTransaction.requiringNew().run(() -> records.persist(record));

    return new UploadResult(staged.sha256(), existing);
  }

  /**
   * Resolves a blob for serving within a repository (mediatype from any record for that content).
   */
  public BlobContent serve(String repoName, String blobId) {
    repositoryService.require(repoName);
    ArtifactRecord record =
        records
            .findByRepositoryAndBlob(repoName, blobId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "No such blob in repository " + repoName + ": " + blobId));
    InputStream stream = blobStore.open(blobId);
    accessTracker.touchArtifact(repoName, blobId, Instant.now().truncatedTo(ChronoUnit.MICROS));
    // Size comes from the record (stored at upload) — no need to re-stat the file; open() is the
    // single filesystem touch (and its own id-shape/existence check) on this hot serve path.
    return new BlobContent(record.mediatype, record.size, stream);
  }

  private static void requireProfileKeys(
      RepositoryTypeProfile profile, Map<String, String> metadata) {
    for (String required : profile.requiredMetadataKeys()) {
      String value = metadata.get(required);
      if (value == null || value.isBlank()) {
        throw new BadRequestException(
            "Missing required metadata key for a " + profile.key() + " upload: " + required);
      }
    }
  }

  private static void verifyPngDimensions(byte[] head, Map<String, String> metadata) {
    Integer claimedW = parseInt(metadata.get(MetadataKeys.RESOLUTION_WIDTH));
    Integer claimedH = parseInt(metadata.get(MetadataKeys.RESOLUTION_HEIGHT));
    if (claimedW == null || claimedH == null || head.length < 24) {
      return; // required-key validation already ran; nothing cheap to check here
    }
    int actualW = readIntBE(head, 16);
    int actualH = readIntBE(head, 20);
    if (actualW != claimedW || actualH != claimedH) {
      throw new BadRequestException(
          "PNG dimensions "
              + actualW
              + "x"
              + actualH
              + " do not match the supplied media.resolution "
              + claimedW
              + "x"
              + claimedH);
    }
  }

  private static Integer parseInt(String s) {
    if (s == null) {
      return null;
    }
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static int readIntBE(byte[] b, int offset) {
    return ((b[offset] & 0xFF) << 24)
        | ((b[offset + 1] & 0xFF) << 16)
        | ((b[offset + 2] & 0xFF) << 8)
        | (b[offset + 3] & 0xFF);
  }

  /**
   * Reads up to {@code n} leading bytes without consuming them (mark/reset on the buffered stream).
   */
  private static byte[] peek(BufferedInputStream in, int n) {
    in.mark(n + 1);
    byte[] buf = new byte[n];
    int read = 0;
    try {
      int r;
      while (read < n && (r = in.read(buf, read, n - read)) != -1) {
        read += r;
      }
      in.reset();
    } catch (IOException e) {
      throw new InternalServerErrorException("Failed to read upload", e);
    }
    return read == n ? buf : java.util.Arrays.copyOf(buf, read);
  }
}
