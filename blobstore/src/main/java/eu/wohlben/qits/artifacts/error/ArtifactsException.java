package eu.wohlben.qits.artifacts.error;

/**
 * Base for artifacts errors. Carries an HTTP-ish status code so the web layer can map it to a
 * response without artifacts depending on JAX-RS (the same framework-free stance as {@code
 * domain.error} — but artifacts must not depend on {@code domain}, so it owns its own). The {@code
 * service} module maps these via {@code ArtifactsExceptionMapper}.
 */
public class ArtifactsException extends RuntimeException {

  private final int statusCode;

  public ArtifactsException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public ArtifactsException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
