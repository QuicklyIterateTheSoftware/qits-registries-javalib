package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.error.InternalServerErrorException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * npm's two content hashes, which are neither of them the one {@link BlobStore} addresses by.
 *
 * <p>A tarball's <em>storage</em> key here is its sha256, like every other blob in this service.
 * npm never sees that: a packument carries {@code dist.shasum} (sha1 hex, legacy but still read) and
 * {@code dist.integrity} (an <a href="https://www.w3.org/TR/SRI/">SRI</a> string, {@code
 * sha512-<base64>}), and the client verifies the download against them. So all three are computed
 * on publish — sha256 for the store, these two for the wire — and the two npm ones are stored as
 * columns rather than derived, because for a proxied version they are <b>upstream's values</b> and
 * must be re-emitted untouched.
 */
public final class NpmIntegrity {

  private NpmIntegrity() {}

  /** {@code dist.shasum}: the sha1 of the tarball, hex. */
  public static String shasum(byte[] tarball) {
    return HexFormat.of().formatHex(digest("SHA-1", tarball));
  }

  /** {@code dist.integrity}: {@code sha512-<base64>}, the SRI form npm verifies against. */
  public static String integrity(byte[] tarball) {
    return "sha512-" + Base64.getEncoder().encodeToString(digest("SHA-512", tarball));
  }

  private static byte[] digest(String algorithm, byte[] bytes) {
    try {
      return MessageDigest.getInstance(algorithm).digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new InternalServerErrorException(algorithm + " unavailable", e);
    }
  }
}
