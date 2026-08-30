package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MediaTypeSnifferTest {

  @Test
  void sniffsPngByMagic() {
    assertEquals("image/png", MediaTypeSniffer.sniff(TestMedia.png(4, 4, 1), null));
  }

  @Test
  void sniffsJpeg() {
    assertEquals(
        "image/jpeg", MediaTypeSniffer.sniff(TestMedia.jpeg(1), "application/octet-stream"));
  }

  @Test
  void sniffsMp4ByFtypBox() {
    assertEquals("video/mp4", MediaTypeSniffer.sniff(TestMedia.mp4(1), null));
  }

  @Test
  void sniffsWebmByEbmlHeader() {
    assertEquals("video/webm", MediaTypeSniffer.sniff(TestMedia.webm(1), null));
  }

  @Test
  void sniffOverridesALyingContentType() {
    // PNG bytes claimed as JPEG — the sniff wins.
    assertEquals("image/png", MediaTypeSniffer.sniff(TestMedia.png(4, 4, 1), "image/jpeg"));
  }

  @Test
  void svgDetectedAsXmlTextEvenWhenClaimedPng() {
    assertEquals("image/svg+xml", MediaTypeSniffer.sniff(TestMedia.svg(1), "image/png"));
  }

  @Test
  void fallsBackToNormalizedClaimWhenUnsniffable() {
    byte[] opaque = {1, 2, 3, 4, 5, 6, 7, 8};
    assertEquals("image/png", MediaTypeSniffer.sniff(opaque, "image/png; charset=binary"));
  }

  @Test
  void nullWhenNeitherSniffableNorClaimed() {
    byte[] opaque = {1, 2, 3, 4, 5, 6, 7, 8};
    assertNull(MediaTypeSniffer.sniff(opaque, null));
  }
}
