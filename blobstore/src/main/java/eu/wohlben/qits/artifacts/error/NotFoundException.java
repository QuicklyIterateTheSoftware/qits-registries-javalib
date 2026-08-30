package eu.wohlben.qits.artifacts.error;

/** Artifacts error mapped to HTTP 404 by the web layer. */
public class NotFoundException extends ArtifactsException {

  public NotFoundException(String message) {
    super(404, message);
  }
}
