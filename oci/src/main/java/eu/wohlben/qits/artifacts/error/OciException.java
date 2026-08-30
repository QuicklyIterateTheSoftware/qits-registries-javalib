package eu.wohlben.qits.artifacts.error;

import eu.wohlben.qits.blobstore.error.ArtifactsException;
import java.util.Map;

/**
 * A registry error carrying the spec's error code, and optionally the {@code detail} object clients
 * print alongside it.
 *
 * <p>Extends {@link ArtifactsException} so {@code statusCode()} stays meaningful and the type sits
 * in the same hierarchy as everything else this module throws — but note it is never mapped by
 * {@code ArtifactsExceptionMapper}. That is a JAX-RS provider, and these are thrown on raw Vert.x
 * routes; {@code RegistryErrors} renders them instead, in the spec's envelope rather than the JSON
 * API's {@code {"message": …}}.
 */
public class OciException extends ArtifactsException {

  private final transient OciCode code;
  private final transient Map<String, Object> detail;

  public OciException(OciCode code, String message) {
    this(code, message, Map.of());
  }

  public OciException(OciCode code, String message, Map<String, Object> detail) {
    this(code, code.status(), message, detail);
  }

  /**
   * The same envelope under a status the code does not imply.
   *
   * <p>{@code UNSUPPORTED} is a 404 for a route this registry does not serve and a <b>405</b> for an
   * operation it declines — a push to a mirror namespace, a client {@code DELETE}. {@code
   * RegistryErrors} already accepts an explicit status for the second case when it writes the
   * response itself; this is the same thing for the cases that are thrown from a service instead,
   * so the status is chosen where the refusal is understood rather than at the edge.
   */
  public OciException(OciCode code, int status, String message, Map<String, Object> detail) {
    super(status, message);
    this.code = code;
    this.detail = detail == null ? Map.of() : Map.copyOf(detail);
  }

  public OciCode code() {
    return code;
  }

  /** Extra context for the {@code detail} member; empty means the member is omitted. */
  public Map<String, Object> detail() {
    return detail;
  }
}
