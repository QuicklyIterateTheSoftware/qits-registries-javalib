package eu.wohlben.qits.npm;

/**
 * The route grammar for {@code /artifacts/npm}.
 *
 * <p>Regexes rather than Vert.x path params, for the same reason {@code RegistryPaths} uses them and
 * one more. The OCI reason: a name can contain a {@code /} and {@code :param} never spans one. The
 * npm reason: a <b>scoped</b> name arrives percent-encoded — {@code GET /@qits%2fangular} — and
 * Vert.x' {@code normalizedPath()} collapses dot-segments but leaves every other escape exactly as
 * it was received, so the router sees {@code %2f} and never a slash. {@code NpmPathsTest} pins that
 * empirically rather than trusting this paragraph.
 *
 * <p>So {@link #PACKAGE} accepts <b>both</b> spellings of the scope separator, which is not
 * belt-and-braces: npm sends the encoded form for a packument, and the unencoded form for whatever
 * absolute URL we put in {@code dist.tarball} — which is npmjs' own layout and therefore the one
 * every consumer already expects to work.
 *
 * <p><b>Every group here is either {@code (?<name>…)} or {@code (?:…)}, never a bare {@code (…)}.</b>
 * vertx-web compares {@code Matcher.groupCount()} against the named groups it scraped out of the
 * pattern and silently falls back to positional {@code param0…paramN} when the two disagree — so one
 * stray capturing group breaks every {@code pathParam(…)} on that route, at runtime, with no error
 * anywhere.
 *
 * <p>The name/tarball split needs no greedy trick, unlike OCI's: a package name is at most two
 * components and neither may contain a {@code /}, so {@code @qits/angular/-/angular-1.0.0.tgz}
 * cannot be read as a package name however hard the matcher tries. {@code NpmPathsTest} pins that
 * the two routes do not overlap, because it is the kind of property that is true until someone
 * loosens a character class.
 */
final class NpmPaths {

  private NpmPaths() {}

  /**
   * The mount point — a literal in the code exactly as {@code /artifacts/git} and {@code /v2} are.
   * No config key moves it and no JAX-RS test would notice if it drifted.
   *
   * <p>Unlike {@code /v2} it needs no root-level exception: npm accepts a registry URL of any depth,
   * so this sits inside the {@code /artifacts} segment the gateway already routes here.
   */
  static final String BASE = "/artifacts/npm";

  /** The {@code artifact_repository} row, the first segment after {@link #BASE}. */
  private static final String REPOSITORY = "(?<repository>[a-z0-9][a-z0-9._-]{0,63})";

  /**
   * One component of a package name. Permissive about case on purpose: new packages may not carry
   * uppercase, but {@code JSONStream} and its generation predate that rule and the proxy has to be
   * able to fetch them.
   */
  private static final String COMPONENT = "[A-Za-z0-9][A-Za-z0-9._~-]{0,213}";

  /** {@code <pkg>} — {@code name}, {@code @scope/name}, or {@code @scope%2fname}. */
  static final String PACKAGE = "(?<pkg>(?:@" + COMPONENT + "(?:/|%2[fF]))?" + COMPONENT + ")";

  /** {@code <unscoped>-<version>.tgz}; the handler splits it, because it knows the package. */
  private static final String TARBALL_FILE = "(?<file>[A-Za-z0-9][A-Za-z0-9._~+-]{0,255}\\.tgz)";

  /**
   * A dist-tag name. Deliberately the same shape as one component of a package name, minus the
   * length: npm has no published grammar for a tag beyond "not a valid semver range", and every tag
   * anyone actually writes — {@code latest}, {@code main}, {@code next}, {@code v2.x-lts} — fits
   * this. Anything outside it reaches the catch-all 404 rather than a handler, which is the right
   * failure for a name this registry would not be able to spell back in a packument.
   */
  private static final String TAG = "(?<tag>[A-Za-z0-9][A-Za-z0-9._~-]{0,63})";

  static final String PACKUMENT = route(REPOSITORY + "/" + PACKAGE);
  static final String TARBALL = route(REPOSITORY + "/" + PACKAGE + "/-/" + TARBALL_FILE);

  /**
   * {@code /-/package/<pkg>/dist-tags} — npm's tag surface, which is <b>not</b> under the package's
   * own path but under the registry-level {@code /-/} namespace, next to {@code /-/v1/search} and
   * {@code /-/whoami}. That is npm's layout, not a choice available here: {@code npm dist-tag ls}
   * and {@code npm dist-tag add} dial exactly these two URLs and nothing else.
   *
   * <p>Which is also why they cannot collide with {@link #PACKUMENT} or {@link #TARBALL}: a package
   * name may not begin with {@code -}, so no path starting {@code <repo>/-/} is readable as one.
   * {@code NpmPathsTest} pins it, in both directions.
   */
  static final String DIST_TAGS = route(REPOSITORY + "/-/package/" + PACKAGE + "/dist-tags");

  /** {@link #DIST_TAGS} plus the tag being moved — the {@code npm dist-tag add} target. */
  static final String DIST_TAG = DIST_TAGS + "/" + TAG;

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
