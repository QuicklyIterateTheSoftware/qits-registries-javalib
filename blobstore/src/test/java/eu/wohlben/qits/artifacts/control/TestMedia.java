package eu.wohlben.qits.artifacts.control;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Minimal, valid-enough media byte fixtures for the artifacts tests (header/magic + a salt). */
final class TestMedia {

  private TestMedia() {}

  /**
   * A PNG whose IHDR encodes {@code width}x{@code height} (so {@code BlobService}'s cheap dimension
   * check can validate it), with {@code salt} appended for content uniqueness (distinct sha).
   */
  static byte[] png(int width, int height, int salt) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (DataOutputStream d = new DataOutputStream(out)) {
      d.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}); // signature
      d.writeInt(13); // IHDR length
      d.writeBytes("IHDR");
      d.writeInt(width); // offset 16
      d.writeInt(height); // offset 20
      d.writeByte(8); // bit depth
      d.writeByte(6); // colour type (RGBA)
      d.writeByte(0); // compression
      d.writeByte(0); // filter
      d.writeByte(0); // interlace
      d.writeInt(0); // (bogus) CRC — the store never validates it
      d.writeInt(salt); // uniqueness
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  static byte[] jpeg(int salt) {
    return concat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, intBytes(salt));
  }

  static byte[] mp4(int salt) {
    return concat(
        new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}, intBytes(salt));
  }

  static byte[] webm(int salt) {
    return concat(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}, intBytes(salt));
  }

  static byte[] svg(int salt) {
    return ("<svg xmlns=\"http://www.w3.org/2000/svg\"><!-- " + salt + " --></svg>")
        .getBytes(StandardCharsets.UTF_8);
  }

  static byte[] intBytes(int v) {
    return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] r = new byte[a.length + b.length];
    System.arraycopy(a, 0, r, 0, a.length);
    System.arraycopy(b, 0, r, a.length, b.length);
    return r;
  }
}
