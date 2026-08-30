package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;
import java.util.Map;

/**
 * A metadata record as returned by the query API. {@code id} is the content id (the blob's SHA-256)
 * — the value the caller GETs to render the artifact. The distinct row identity is carried by the
 * metadata (branch, flow, createdAt).
 */
public record ArtifactRecordDto(
    String id,
    String repository,
    String mediatype,
    long size,
    Instant createdAt,
    Instant accessedAt,
    Map<String, String> metadata) {}
