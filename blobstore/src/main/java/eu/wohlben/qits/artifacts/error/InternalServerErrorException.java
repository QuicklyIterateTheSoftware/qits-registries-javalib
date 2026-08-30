package eu.wohlben.qits.artifacts.error;

/** Artifacts error mapped to HTTP 500 by the web layer. */
public class InternalServerErrorException extends ArtifactsException {

  public InternalServerErrorException(String message) {
    super(500, message);
  }

  public InternalServerErrorException(String message, Throwable cause) {
    super(500, message, cause);
  }
}
