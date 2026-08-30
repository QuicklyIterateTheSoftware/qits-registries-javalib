package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.dto.UploadResult;
import eu.wohlben.qits.artifacts.entity.ArtifactRecord;
import eu.wohlben.qits.artifacts.error.BadRequestException;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BlobServiceTest extends ArtifactsTestSupport {

  @Inject BlobService blobService;

  @Inject ArtifactRepositoryService repositoryService;

  @Inject ArtifactRecordRepository recordRepository;

  @BeforeEach
  void seedRepos() {
    repositoryService.ensure("ci-screenshots", CiScreenshotsProfile.KEY);
    repositoryService.ensure("ci-videos", CiVideosProfile.KEY);
  }

  private UploadResult uploadPng(int w, int h, int salt, String branch, String flow) {
    return blobService.upload(
        "ci-screenshots",
        "image/png",
        screenshotMeta(branch, flow, w, h),
        new ByteArrayInputStream(TestMedia.png(w, h, salt)));
  }

  @Test
  void identicalBytesDedupeButKeepDistinctRecords() {
    var first = uploadPng(100, 50, 1, "main", "checkout");
    var second = uploadPng(100, 50, 1, "main", "checkout");

    assertFalse(first.existing(), "first upload stores fresh content");
    assertTrue(second.existing(), "identical bytes dedupe");
    assertEquals(first.id(), second.id());
    assertEquals(2, recordRepository.count(), "two distinct metadata rows over one blob");
  }

  @Test
  void metadataRoundTripsIncludingUnknownKeysPlusServerStamps() {
    Map<String, String> meta = screenshotMeta("main", "checkout", 100, 50);
    meta.put("custom.experiment", "blue-button");
    meta.put("created-at", "1999-01-01T00:00:00Z"); // must be ignored (server-stamped)

    var result =
        blobService.upload(
            "ci-screenshots",
            "image/png",
            meta,
            new ByteArrayInputStream(TestMedia.png(100, 50, 5)));

    ArtifactRecord record =
        recordRepository.findByRepositoryAndBlob("ci-screenshots", result.id()).orElseThrow();
    assertEquals("blue-button", record.metadata.get("custom.experiment"));
    assertEquals("image/png", record.metadata.get("mediatype"));
    assertNotNull(record.metadata.get("created-at"));
    assertFalse(
        record.metadata.get("created-at").startsWith("1999"), "wire created-at is discarded");
    assertEquals(record.createdAt.toString(), record.metadata.get("created-at"));
  }

  @Test
  void sniffOverridesALyingContentType() {
    var result =
        blobService.upload(
            "ci-screenshots",
            "image/jpeg", // lie — the bytes are PNG
            screenshotMeta("main", "checkout", 8, 8),
            new ByteArrayInputStream(TestMedia.png(8, 8, 2)));
    ArtifactRecord record =
        recordRepository.findByRepositoryAndBlob("ci-screenshots", result.id()).orElseThrow();
    assertEquals("image/png", record.mediatype);
  }

  @Test
  void rejectsADisallowedMediatype() {
    assertThrows(
        BadRequestException.class,
        () ->
            blobService.upload(
                "ci-screenshots",
                "video/mp4",
                videoMeta("main", "checkout"),
                new ByteArrayInputStream(TestMedia.mp4(1))));
  }

  @Test
  void rejectsAMissingRequiredKey() {
    Map<String, String> meta = screenshotMeta("main", "checkout", 8, 8);
    meta.remove("qits.diff.hash");
    assertThrows(
        BadRequestException.class,
        () ->
            blobService.upload(
                "ci-screenshots",
                "image/png",
                meta,
                new ByteArrayInputStream(TestMedia.png(8, 8, 3))));
  }

  @Test
  void rejectsPngWhoseDimensionsDoNotMatchMetadata() {
    Map<String, String> meta = screenshotMeta("main", "checkout", 100, 999);
    assertThrows(
        BadRequestException.class,
        () ->
            blobService.upload(
                "ci-screenshots",
                "image/png",
                meta,
                new ByteArrayInputStream(TestMedia.png(100, 50, 4)))); // actual IHDR is 100x50
  }

  @Test
  void acceptsAVideoWithItsProfile() {
    var result =
        blobService.upload(
            "ci-videos",
            "video/mp4",
            videoMeta("main", "checkout"),
            new ByteArrayInputStream(TestMedia.mp4(7)));
    assertFalse(result.existing());
    ArtifactRecord record =
        recordRepository.findByRepositoryAndBlob("ci-videos", result.id()).orElseThrow();
    assertEquals("video/mp4", record.mediatype);
  }

  @Test
  void uploadToUnknownRepositoryIsNotFound() {
    assertThrows(
        NotFoundException.class,
        () ->
            blobService.upload(
                "nope",
                "image/png",
                screenshotMeta("main", "checkout", 8, 8),
                new ByteArrayInputStream(TestMedia.png(8, 8, 8))));
  }
}
