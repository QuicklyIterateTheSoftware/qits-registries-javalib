package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;

/**
 * A repository as the API spells it. {@code type} is the <b>kebab wire form</b> ({@code
 * ci-screenshots}), not the stored key — the entity carries the stored one and the mapper converts.
 */
public record ArtifactRepositoryDto(String name, String type, Instant createdAt) {}
