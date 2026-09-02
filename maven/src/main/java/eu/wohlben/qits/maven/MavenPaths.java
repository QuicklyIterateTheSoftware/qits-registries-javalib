package eu.wohlben.qits.maven;

/**
 * The route grammar for {@code /artifacts/maven}.
 *
 * <p>One regex per method with a named {@code (?<path>…)} tail, the {@code RegistryPaths}/{@code
 * NpmPaths} pattern. The tail is deliberately loose — anything that is not a path separator —
 * because well-formedness is the handler's job and the answer for a malformed path is a {@code
 * 400} there, not a {@code 404} here: the {@code REF} lesson, where an unusable request that missed
 * the route was reported as an absent resource and sent the client debugging the wrong thing.
 *
 * <p><b>Every group here is either {@code (?<name>…)} or {@code (?:…)}, never a bare {@code (…)}.</b>
 * vertx-web compares {@code Matcher.groupCount()} against the named groups it scraped out of the
 * pattern and silently falls back to positional {@code param0…paramN} when the two disagree — so one
 * stray capturing group breaks every {@code pathParam(…)} on that route, at runtime, with no error
 * anywhere.
 *
 * <p>Matching runs against {@code normalizedPath()}, which collapses dot-segments before routing —
 * so {@code ..} never reaches a handler, the same property {@code NpmPathsTest} pins for npm.
 */
final class MavenPaths {

  private MavenPaths() {}

  /**
   * The mount point — a literal in the code exactly as {@code /artifacts/git} and {@code
   * /artifacts/npm} are. No config key moves it and no JAX-RS test would notice if it drifted.
   *
   * <p>Like npm and unlike {@code /v2} it needs no root-level exception: maven accepts a repository
   * URL of any depth (it is the base of every path it appends), so this sits inside the {@code
   * /artifacts} segment the gateway already routes here.
   */
  static final String BASE = "/artifacts/maven";

  /** The {@code artifact_repository} row, the first segment after {@link #BASE}. */
  private static final String REPOSITORY = "(?<repository>[a-z0-9][a-z0-9._-]{0,63})";

  /**
   * The whole repository-relative path: {@code <group/path>/<artifact>/<version>/<file>}. Loose on
   * purpose — see the class javadoc; {@code MavenLayout.parse} is where shape becomes a verdict.
   */
  static final String ARTIFACT = route(REPOSITORY + "/(?<path>.+)");

  /**
   * The same artifact grammar under an arbitrary mount, for a deployment that answers on a SECOND
   * base beside {@link #BASE}. The pull-through mirror uses it to serve its caches under its own
   * {@code /mirror} prefix — the edge routes {@code /artifacts} to the hosted registry that owns
   * that route, so the mirror is unreachable there through the edge, while {@code /mirror} is its
   * own. The named groups are identical to {@link #ARTIFACT}, so every {@code pathParam} in the
   * handlers is mount-agnostic and one set of handlers serves both. A method, not a constant, for
   * the inlining reason on {@link #route}.
   */
  static String artifactRoute(String mount) {
    return mount + "/" + REPOSITORY + "/(?<path>.+)";
  }

  /**
   * Builds a route regex under {@link #BASE}.
   *
   * <p>A method call rather than string concatenation, and that is not styling — the reason is
   * {@code RegistryPaths.route}'s verbatim: a {@code static final String} initialised from a
   * constant expression is inlined by javac into every class that reads it, including the test,
   * which would then keep asserting against whatever the value was when it was last compiled.
   */
  private static String route(String suffix) {
    return BASE + "/" + suffix;
  }
}
