package eu.wohlben.qits.artifacts.error;

/** Artifacts error mapped to HTTP 400 by the web layer. */
public class BadRequestException extends ArtifactsException {

  public BadRequestException(String message) {
    super(400, message);
  }
}
