package eu.wohlben.qits.registry;

import eu.wohlben.qits.blobstore.error.ArtifactsException;
import eu.wohlben.qits.blobstore.error.BadRequestException;
import eu.wohlben.qits.blobstore.error.NotFoundException;
import eu.wohlben.qits.artifacts.error.OciCode;
import eu.wohlben.qits.artifacts.error.OciException;
import eu.wohlben.qits.blobstore.error.PayloadTooLargeException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The Distribution spec's error envelope: {@code {"errors":[{"code","message","detail"}]}}.
 *
 * <p>Docker and podman print {@code code} and {@code message} to the user verbatim, so this is not
 * an internal representation — it is the debugging story for anyone whose push failed.
 *
 * <p>Built with {@link JsonObject}/{@link JsonArray} rather than a DTO, deliberately. A type
 * serialised only inside a raw Vert.x handler is invisible to the native-image build — there is no
 * JAX-RS provider chain for it to be found through — so it would need
 * {@code @RegisterForReflection} and, without it, produce a green build that 500s in the binary.
 * That is exactly what {@code dto/UploadResult} once did. Map-backed JSON has no such failure mode.
 *
 * <p>{@code ArtifactsExceptionMapper} cannot help here and does not conflict either: it is a JAX-RS
 * {@code @Provider}, and RESTEasy consults mappers only for exceptions thrown inside a resource
 * invocation. These are two independent error surfaces on two independent stacks — the same
 * relationship {@code /artifacts/git/*} already has with the JSON API.
 */
final class RegistryErrors {

  private static final Logger LOG = Logger.getLogger(RegistryErrors.class);

  private RegistryErrors() {}

  static void send(RoutingContext rc, OciCode code, String message) {
    send(rc, code, message, Map.of());
  }

  static void send(RoutingContext rc, OciCode code, String message, Map<String, Object> detail) {
    send(rc, code.status(), code, message, detail);
  }

  /**
   * The same envelope under a status the code does not imply — {@code UNSUPPORTED} is a 404 for an
   * unknown route but a 405 for a method this registry declines to implement. Passing the status
   * explicitly is what keeps that from being silently overwritten by the code's default.
   */
  static void send(RoutingContext rc, int status, OciCode code, String message) {
    send(rc, status, code, message, Map.of());
  }

  private static void send(
      RoutingContext rc, int status, OciCode code, String message, Map<String, Object> detail) {
    // A response may already be on its way: VertxInputStream writes its own 413 and ends the
    // exchange, and a client that hung up mid-upload leaves nothing to answer. Writing again throws
    // IllegalStateException and buries the real cause.
    if (rc.response().ended() || rc.response().headWritten()) {
      return;
    }
    JsonObject error = new JsonObject().put("code", code.name()).put("message", message);
    if (detail != null && !detail.isEmpty()) {
      error.put("detail", new JsonObject(detail));
    }
    rc.response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .end(new JsonObject().put("errors", new JsonArray().add(error)).encode());
  }

  /**
   * The safety net at the edge of every handler. Prefer throwing an {@link OciException} with the
   * right code at the point the problem is understood — the translations below are for what
   * legitimately escapes {@code BlobStore}, which predates the registry and speaks its own
   * vocabulary.
   *
   * <p>Nothing here calls {@code rc.fail()}: Quarkus installs {@code QuarkusErrorHandler} as the
   * router's failure handler, and it would answer with an HTML page or a {@code {"details": …}}
   * body that no registry client can read.
   */
  static void fail(RoutingContext rc, String what, Throwable thrown) {
    switch (thrown) {
      // The exception's status, not the code's: they differ wherever a code carries more than one
      // meaning — UNSUPPORTED is a 404 for an unserved route and a 405 for a refused operation.
      case OciException e -> send(rc, e.statusCode(), e.code(), e.getMessage(), e.detail());
      case PayloadTooLargeException e -> send(rc, OciCode.SIZE_INVALID, e.getMessage());
      case NotFoundException e -> send(rc, OciCode.BLOB_UNKNOWN, e.getMessage());
      case BadRequestException e -> send(rc, OciCode.BLOB_UPLOAD_INVALID, e.getMessage());
      case ArtifactsException e -> {
        LOG.warnf(e, "registry: %s", what);
        send(rc, e.statusCode(), OciCode.UNSUPPORTED, e.getMessage(), Map.of());
      }
      default -> {
        LOG.errorf(thrown, "registry: %s", what);
        send(rc, 500, OciCode.UNSUPPORTED, "internal registry error", Map.of());
      }
    }
  }
}
