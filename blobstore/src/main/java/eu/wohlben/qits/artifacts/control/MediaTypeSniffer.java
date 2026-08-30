package eu.wohlben.qits.artifacts.control;

import java.nio.charset.StandardCharsets;

/**
 * Magic-byte media-type detection. The sniffed type <b>wins</b> over the claimed {@code
 * Content-Type} header (the same trust stance as the capture/OTLP gzip sniff — the bytes are the
 * truth, the header is a claim). Types that carry no reliable magic signature (SVG is XML text)
 * fall back to a claimed type, but only within the repository profile's allowed set (enforced by
 * the caller).
 */
public final class MediaTypeSniffer {

  private MediaTypeSniffer() {}

  /**
   * @param head the first bytes of the upload (≥16 recommended)
   * @param claimed the request's {@code Content-Type} (may be null)
   * @return the effective media type, or {@code null} if neither sniff nor a usable claim yields
   *     one
   */
  public static String sniff(byte[] head, String claimed) {
    String sniffed = sniffMagic(head);
    if (sniffed != null) {
      return sniffed;
    }
    // No binary signature. SVG is XML text — accept it if the head looks like XML/SVG, so a lying
    // "image/png" header on actual SVG bytes still can't masquerade as PNG.
    if (looksLikeSvg(head)) {
      return "image/svg+xml";
    }
    String normalized = normalize(claimed);
    return normalized == null || normalized.isBlank() ? null : normalized;
  }

  /** The binary signature only, or null if the head has none we recognise. */
  public static String sniffMagic(byte[] b) {
    if (b == null) {
      return null;
    }
    if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
      return "image/png";
    }
    if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
      return "image/jpeg";
    }
    // Matroska/WebM EBML header.
    if (startsWith(b, 0x1A, 0x45, 0xDF, 0xA3)) {
      return "video/webm";
    }
    // ISO base media (mp4): a 'ftyp' box marker at offset 4.
    if (b.length >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
      return "video/mp4";
    }
    return null;
  }

  private static boolean looksLikeSvg(byte[] b) {
    if (b == null || b.length == 0) {
      return false;
    }
    String head = new String(b, StandardCharsets.UTF_8).stripLeading();
    // Skip a leading XML declaration/doctype if present.
    return head.startsWith("<svg") || head.startsWith("<?xml") || head.contains("<svg");
  }

  private static String normalize(String contentType) {
    if (contentType == null) {
      return null;
    }
    int semi = contentType.indexOf(';'); // drop parameters like "; charset=utf-8"
    String type = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    // Locale.ROOT so a Turkish-locale JVM doesn't fold 'I' to 'ı' and miss the allowed-set entries
    // (the rest of the codebase — RepositoryTypeProfile.wireNameOf, BlobController.META_PREFIX_LC —
    // does the same).
    return type.toLowerCase(java.util.Locale.ROOT);
  }

  private static boolean startsWith(byte[] b, int... prefix) {
    if (b.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if ((b[i] & 0xFF) != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
