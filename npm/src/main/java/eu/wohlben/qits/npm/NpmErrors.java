package eu.wohlben.qits.npm;

import eu.wohlben.qits.blobstore.error.ArtifactsException;
import eu.wohlben.qits.blobstore.error.NotFoundException;
import eu.wohlben.qits.artifacts.error.NpmException;
import eu.wohlben.qits.blobstore.error.PayloadTooLargeException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

/**
 * npm's error envelope: {@code {"error": "…"}}, which the CLI prints to the user verbatim.
 *
 * <p>Built with {@link JsonObject} rather than a DTO, deliberately, and for the reason spelled out
 * at length in {@code RegistryErrors}: a type serialised only inside a raw Vert.x handler is
 * invisible to the native-image build, so it would need {@code @RegisterForReflection} and, without
 * it, produce a green build that 500s in the binary. Map-backed JSON has no such failure mode, and
 * the npm stack — like the registry — adds <b>zero</b> native-image configuration.
 *
 * <p>Nothing here calls {@code rc.fail()}: Quarkus installs {@code QuarkusErrorHandler} as the
 * router's failure handler, and it answers with an HTML page or a {@code {"details": …}} body that
 * npm reads as a corrupt response rather than as a message.
 */
final class NpmErrors {

  private static final Logger LOG = Logger.getLogger(NpmErrors.class);

  private NpmErrors() {}

  static void send(RoutingContext rc, int status, String message) {
    // A response may already be on its way: a BodyHandler over its limit writes its own 413 and
    // ends the exchange, and a client that hung up mid-publish leaves nothing to answer. Writing
    // again throws IllegalStateException and buries the real cause.
    if (rc.response().ended() || rc.response().headWritten()) {
      return;
    }
    rc.response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .end(new JsonObject().put("error", message).encode());
  }

  /**
   * The safety net at the edge of every handler. Prefer throwing an {@link NpmException} with the
   * right status at the point the problem is understood — the translations below are for what
   * legitimately escapes {@code BlobStore}, which predates all of this and speaks its own
   * vocabulary.
   */
  static void fail(RoutingContext rc, String what, Throwable thrown) {
    switch (thrown) {
      case NpmException e -> send(rc, e.statusCode(), e.getMessage());
      case PayloadTooLargeException e -> send(rc, 413, e.getMessage());
      case NotFoundException e -> send(rc, 404, e.getMessage());
      case ArtifactsException e -> {
        LOG.warnf(e, "npm: %s", what);
        send(rc, e.statusCode(), e.getMessage());
      }
      default -> {
        LOG.errorf(thrown, "npm: %s", what);
        send(rc, 500, "internal npm registry error");
      }
    }
  }
}
