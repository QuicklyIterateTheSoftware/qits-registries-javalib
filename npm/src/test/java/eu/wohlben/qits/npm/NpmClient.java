package eu.wohlben.qits.npm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A synthetic npm client: publish, packument, tarball — about as many lines as it takes to say what
 * the protocol is.
 *
 * <p>It exists for the reason {@code registry/OciClient} does: {@code mvn verify} may not assume npm
 * or pnpm is installed, and this repo's suite has no network at all, so the round trip still has to
 * be proved on every build. A plain JDK {@link HttpClient} rather than RestAssured, and HTTP/1.1
 * pinned, for the same fidelity reasons — plus one specific to npm: RestAssured percent-encodes a
 * path, and half the point here is which spelling of {@code @scope%2fname} the router matches.
 *
 * <p>The division of labour follows the registry's: this client is for <b>protocol shape</b>, while
 * RestAssured stays the tool for status codes and headers.
 */
public final class NpmClient implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final URI base;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private final Map<String, String> headers = new LinkedHashMap<>();

  public NpmClient(URI base) {
    this.base = base;
  }

  /** Adds a header to every subsequent request — how the {@code X-Forwarded-*} cases are driven. */
  public NpmClient header(String name, String value) {
    headers.put(name, value);
    return this;
  }

  @Override
  public void close() {
    http.close();
  }

  /** {@code PUT /<repo>/<pkg>} with a publish document. */
  public HttpResponse<String> publish(String repository, String pkgPath, byte[] document) {
    return send(
        request(repository, pkgPath)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(document)),
        HttpResponse.BodyHandlers.ofString());
  }

  /** {@code GET /<repo>/<pkg>} with the abbreviated Accept both real clients send. */
  public HttpResponse<String> packument(String repository, String pkgPath) {
    return send(
        request(repository, pkgPath)
            .header("Accept", "application/vnd.npm.install-v1+json, application/json")
            .GET(),
        HttpResponse.BodyHandlers.ofString());
  }

  public JsonNode packumentJson(String repository, String pkgPath) {
    return parse(packument(repository, pkgPath).body());
  }

  /** Follows a {@code dist.tarball} URL verbatim, which is the only way a real client reaches one. */
  public HttpResponse<byte[]> tarball(String url) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
    headers.forEach(builder::header);
    return send(builder, HttpResponse.BodyHandlers.ofByteArray());
  }

  public HttpResponse<Void> head(String url) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody());
    headers.forEach(builder::header);
    return send(builder, HttpResponse.BodyHandlers.discarding());
  }

  /** {@code GET /<repo>/-/package/<pkg>/dist-tags} — what {@code npm dist-tag ls} reads. */
  public HttpResponse<String> distTags(String repository, String pkgPath) {
    return send(
        request(repository, "-/package/" + pkgPath + "/dist-tags").GET(),
        HttpResponse.BodyHandlers.ofString());
  }

  /**
   * {@code PUT /<repo>/-/package/<pkg>/dist-tags/<tag>} — what {@code npm dist-tag add} sends, body
   * included: the version as a JSON string, quotes and all.
   */
  public HttpResponse<String> setDistTag(
      String repository, String pkgPath, String tag, String version) {
    return distTagBody(repository, pkgPath, tag, "\"" + version + "\"");
  }

  /** The same PUT with an arbitrary body — for the shapes a hand-written client sends. */
  public HttpResponse<String> distTagBody(
      String repository, String pkgPath, String tag, String body) {
    return send(
        request(repository, "-/package/" + pkgPath + "/dist-tags/" + tag)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)),
        HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<String> delete(String repository, String pkgPath) {
    return send(
        request(repository, pkgPath).DELETE(), HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<String> get(String path) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).GET();
    headers.forEach(builder::header);
    return send(builder, HttpResponse.BodyHandlers.ofString());
  }

  /** The absolute tarball url a packument advertises for one version. */
  public static String tarballUrl(JsonNode packument, String version) {
    return packument.path("versions").path(version).path("dist").path("tarball").asText();
  }

  public static JsonNode parse(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      throw new IllegalStateException("not JSON: " + body, e);
    }
  }

  private HttpRequest.Builder request(String repository, String pkgPath) {
    // Built by hand rather than through URI.resolve: `@qits%2fangular` must reach the server with
    // its escape intact, and every convenience API in sight would either decode or re-encode it.
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base + "artifacts/npm/" + repository + "/" + pkgPath));
    headers.forEach(builder::header);
    return builder;
  }

  private <T> HttpResponse<T> send(
      HttpRequest.Builder builder, HttpResponse.BodyHandler<T> bodyHandler) {
    try {
      return http.send(builder.build(), bodyHandler);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
