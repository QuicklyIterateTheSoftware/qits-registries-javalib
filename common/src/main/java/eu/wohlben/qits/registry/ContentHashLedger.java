package eu.wohlben.qits.registry;

import java.util.regex.Pattern;

/**
 * Records the content hash a publisher claims for one published maven GAV or npm version.
 *
 * <p>{@code NpmVersion}, {@code MavenArtifact} and the routes in front of them ship in this
 * library, and {@code qits-mirror-service} depends on the same jars (the {@code
 * qits-registries-npm} and {@code qits-registries-maven} modules) for its pull-through caches. A
 * mapped column on either shared entity would need a schema change there too, for a value the
 * mirror never holds — so the library owns only this port, with a no-op {@linkplain
 * NoContentHashLedger default}, and the one consumer that actually stores the value ({@code
 * qits-artifacts-service}) supplies its own implementation and its own table.
 *
 * <p>{@link #record} is called from the deploy/publish route, after the version itself is already
 * stored: the hash is metadata about a version that exists, never a precondition for creating one.
 */
public interface ContentHashLedger {

  /** The one header both registries read it from: one name, maven and npm alike. */
  String HEADER = "X-Artifacts-Content-Hash";

  /**
   * The v1 shape: {@code v<algorithm version>:<algorithm name>:<hex digest>}, e.g. {@code
   * v1:sha256:…}. The algorithm name is lower-case ASCII so the value is safe as a header and as a
   * primary-key column without normalising case first; the hex half is bounded at 128 characters —
   * room for sha512 twice over — so nothing unbounded reaches a column sized for it.
   */
  Pattern FORMAT = Pattern.compile("^v[0-9]+:[a-z0-9-]+:[0-9a-f]{1,128}$");

  /**
   * Records {@code value} as the content hash of {@code ecosystem}'s {@code name}@{@code version}
   * in {@code repository}.
   *
   * <p><b>First write wins.</b> A key with no row yet inserts. An equal value is a no-op — a retried
   * upload must not fail on its own account. A different value for an already-recorded key is
   * refused, answered as the caller's own {@code 409} (a {@code MavenException} or {@code
   * NpmException}, never this interface's own type): the route lets it through unchanged, the same
   * way every other registry refusal in these routes is thrown and caught.
   *
   * @param ecosystem {@code "maven"} or {@code "npm"}
   * @param repository the {@code artifact_repository} row the version was deployed or published
   *     into
   * @param name the maven {@code groupId:artifactId}, or the npm package's full name
   * @param version the version the hash describes
   * @param value the claimed hash, already validated against {@link #FORMAT} by the caller
   */
  void record(String ecosystem, String repository, String name, String version, String value);
}
