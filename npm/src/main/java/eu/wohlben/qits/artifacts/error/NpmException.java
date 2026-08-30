package eu.wohlben.qits.artifacts.error;

import eu.wohlben.qits.blobstore.error.ArtifactsException;

/**
 * An npm registry error, carrying the status the wire should answer with.
 *
 * <p>npm has no error-code vocabulary the way the OCI Distribution spec does — a registry answers a
 * status and a body of the shape {@code {"error": "…"}}, and the npm CLI prints that string. So
 * there is no {@code NpmCode} enum to mirror {@link OciCode}: the status <em>is</em> the code, and
 * the message is the whole of what a person debugging a failed publish sees.
 *
 * <p>Extends {@link ArtifactsException} so {@code statusCode()} stays meaningful and the type sits
 * in the same hierarchy as everything else this module throws — but, exactly like {@link
 * OciException}, it is never mapped by {@code ArtifactsExceptionMapper}: that is a JAX-RS provider
 * and these are thrown on raw Vert.x routes, where {@code NpmErrors} renders them instead.
 */
public class NpmException extends ArtifactsException {

  public NpmException(int statusCode, String message) {
    super(statusCode, message);
  }
}
