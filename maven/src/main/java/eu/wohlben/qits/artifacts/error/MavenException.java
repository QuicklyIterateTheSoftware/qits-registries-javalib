package eu.wohlben.qits.artifacts.error;

import eu.wohlben.qits.blobstore.error.ArtifactsException;

/**
 * A maven repository error, carrying the status the wire should answer with.
 *
 * <p>Maven has no error-code vocabulary and no JSON contract: a client reads the status and logs
 * the body, so the status <em>is</em> the code and the message is plain text, exactly like {@link
 * NpmException}. It is never mapped by {@code ArtifactsExceptionMapper} — that is a JAX-RS provider
 * and these are thrown on raw Vert.x routes, where {@code MavenErrors} renders them instead.
 */
public class MavenException extends ArtifactsException {

  public MavenException(int statusCode, String message) {
    super(statusCode, message);
  }
}
