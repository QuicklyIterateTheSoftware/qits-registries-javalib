package eu.wohlben.qits.maven;

import eu.wohlben.qits.blobstore.error.ArtifactsException;
import eu.wohlben.qits.artifacts.error.MavenException;
import eu.wohlben.qits.blobstore.error.NotFoundException;
import eu.wohlben.qits.blobstore.error.PayloadTooLargeException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

/**
 * The maven error envelope: a status code plus a short plain-text body.
 *
 * <p>There is no JSON contract to honour — maven clients read the status and log the body — so a
 * string is the whole envelope, and nothing here needs {@code @RegisterForReflection}: the maven
 * stack, like {@code registry} and {@code npm}, adds <b>zero</b> native-image configuration.
 *
 * <p>Nothing here calls {@code rc.fail()}: Quarkus installs {@code QuarkusErrorHandler} as the
 * router's failure handler, and it answers with an HTML page or a {@code {"details": …}} body that
 * a maven client reads as a corrupt response rather than as a message.
 */
final class MavenErrors {

  private static final Logger LOG = Logger.getLogger(MavenErrors.class);

  private MavenErrors() {}

  /** How much of an unexpected failure a client is told: the type, its message, one line, clipped. */
  private static final int CAUSE_LIMIT = 400;

  /**
   * The one-line account of an unexpected throwable. Newlines are folded so the answer stays one
   * line in a resolver's output, and the whole thing is clipped so a driver that puts a query into
   * its message cannot make the body the size of the artifact.
   */
  private static String describe(Throwable thrown) {
    String message = thrown.getMessage();
    String line =
        thrown.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message.replaceAll("\\s+", " "));
    return line.length() <= CAUSE_LIMIT ? line : line.substring(0, CAUSE_LIMIT) + "…";
  }

  static void send(RoutingContext rc, int status, String message) {
    // A response may already be on its way: a client that hung up mid-deploy leaves nothing to
    // answer. Writing again throws IllegalStateException and buries the real cause.
    if (rc.response().ended() || rc.response().headWritten()) {
      return;
    }
    rc.response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        .end(message);
  }

  /**
   * The safety net at the edge of every handler. Prefer throwing a {@link MavenException} with the
   * right status at the point the problem is understood — the translations below are for what
   * legitimately escapes {@code BlobStore}, which predates all of this and speaks its own
   * vocabulary.
   */
  static void fail(RoutingContext rc, String what, Throwable thrown) {
    switch (thrown) {
      case MavenException e -> send(rc, e.statusCode(), e.getMessage());
      case PayloadTooLargeException e -> send(rc, 413, e.getMessage());
      case NotFoundException e -> send(rc, 404, e.getMessage());
      case ArtifactsException e -> {
        LOG.warnf(e, "maven: %s", what);
        send(rc, e.statusCode(), e.getMessage());
      }
      default -> {
        LOG.errorf(thrown, "maven: %s", what);
        // THE BODY CARRIES THE CAUSE, and that is a change made for a reason. This used to answer a
        // flat "internal maven repository error" — 31 bytes that say only "something", which is
        // exactly as much as a bare 500 already said. On 2026-09-05 one cached pom answered that
        // string to every request for four days, and nobody could tell from the wire whether the
        // fault was the blob store, the upstream or the row; it took the service's own log to learn
        // it was `duplicate key value violates unique constraint "maven_artifact_pkey"` on the
        // access-tracking UPDATE. The client is a maven resolver, which prints this line into a
        // failing build's output — the one place somebody is already looking.
        //
        // Bounded and shaped, because it is still an internal fault reaching a client: the exception
        // TYPE and its message, on one line, clipped. No stack, which belongs in the log beside it,
        // and no chain, which is where connection strings tend to live.
        send(rc, 500, "internal maven repository error: " + describe(thrown));
      }
    }
  }
}
