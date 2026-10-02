package eu.wohlben.qits.maven;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * A synthetic maven client: PUT, GET, HEAD — about as many lines as it takes to say what the
 * protocol is.
 *
 * <p>It exists for the reason {@code npm/NpmClient} does: {@code mvn verify} may not assume maven
 * is installed, and this repo's suite has no network at all, so the deploy/resolve round trip still
 * has to be proved on every build. A plain JDK {@link HttpClient} rather than RestAssured, and
 * HTTP/1.1 pinned, for the same fidelity reasons — the path grammar and the percent-encoding
 * questions are the point, and RestAssured re-encodes a path.
 */
public final class MavenClient implements AutoCloseable {

  private final URI base;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  public MavenClient(URI base) {
    this.base = base;
  }

  @Override
  public void close() {
    http.close();
  }

  /** {@code PUT /<repo>/<path>} — a deploy, a checksum claim, or a client metadata document. */
  public HttpResponse<String> put(String repository, String path, byte[] bytes) {
    return send(
        request(repository, path).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)),
        HttpResponse.BodyHandlers.ofString());
  }

  /** The same PUT, with extra headers — name, value, name, value, … — such as the content hash. */
  public HttpResponse<String> put(
      String repository, String path, byte[] bytes, String... headers) {
    HttpRequest.Builder request =
        request(repository, path).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
    for (int i = 0; i < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    return send(request, HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<byte[]> get(String repository, String path) {
    return send(request(repository, path).GET(), HttpResponse.BodyHandlers.ofByteArray());
  }

  public HttpResponse<String> getText(String repository, String path) {
    return send(request(repository, path).GET(), HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<Void> head(String repository, String path) {
    return send(
        request(repository, path).method("HEAD", HttpRequest.BodyPublishers.noBody()),
        HttpResponse.BodyHandlers.discarding());
  }

  public HttpResponse<String> delete(String repository, String path) {
    return send(request(repository, path).DELETE(), HttpResponse.BodyHandlers.ofString());
  }

  /** {@code GET} of an absolute path under the test root — the catch-all cases. */
  public HttpResponse<String> getAbsolute(String path) {
    return send(
        HttpRequest.newBuilder(URI.create(base + path.substring(1))).GET(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String repository, String path) {
    // Built by hand rather than through URI.resolve: the path must reach the server exactly as
    // written, and every convenience API in sight would either decode or re-encode it.
    return HttpRequest.newBuilder(
        URI.create(base + "artifacts/maven/" + repository + "/" + path));
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
