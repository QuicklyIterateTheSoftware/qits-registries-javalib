package eu.wohlben.qits.artifacts.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The outcome of an upload: {@code id} is the content id (the blob's SHA-256), {@code existing} is
 * whether those exact bytes were already stored (dedupe — a new metadata record is created either
 * way).
 *
 * <p>{@link RegisterForReflection} because this is the one DTO Quarkus' build-time scan cannot see:
 * {@code BlobController#upload} sets its status explicitly and so returns {@code Response}, not
 * {@code UploadResult}, and a type that appears in no resource-method signature gets no reflection
 * registration. The sibling DTOs are returned through typed wrapper records and need no annotation.
 * Without it the JVM build is green and the native binary answers <b>500</b> to every upload — "No
 * serializer found for class ... UploadResult and no properties discovered" — which is the whole
 * write surface of this service.
 */
@RegisterForReflection
public record UploadResult(String id, boolean existing) {}
