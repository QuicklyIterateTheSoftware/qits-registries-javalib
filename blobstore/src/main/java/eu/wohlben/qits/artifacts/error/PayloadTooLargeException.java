package eu.wohlben.qits.artifacts.error;

/** Artifacts error mapped to HTTP 413 by the web layer (per-type upload size cap exceeded). */
public class PayloadTooLargeException extends ArtifactsException {

  public PayloadTooLargeException(String message) {
    super(413, message);
  }
}
