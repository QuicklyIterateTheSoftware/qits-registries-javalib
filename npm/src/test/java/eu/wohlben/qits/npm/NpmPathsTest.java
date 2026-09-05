package eu.wohlben.qits.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The route grammar, as plain JUnit — no Quarkus, because this is a property of the regexes and the
 * cases that matter are cheap to be exhaustive about. {@code NpmRegistryTest} then proves the router
 * actually behaves this way over the wire, which is the half a regex test cannot reach.
 *
 * <p>The first test is the load-bearing one and it was written first, before any route existed: a
 * scoped package arrives as {@code /@qits%2fangular}, and everything downstream depends on whether
 * the router sees that escape or a decoded slash. Vert.x' {@code normalizedPath()} collapses
 * dot-segments and leaves every other escape exactly as received — so the grammar must match the
 * <em>encoded</em> form, and the handler decodes. Getting that backwards produces a stack that works
 * for unscoped packages and 404s for every scoped one, which is precisely the platform's own case.
 */
class NpmPathsTest {

  @Test
  void aScopedNameIsMatchedInBothSpellings() {
    // Encoded: what npm sends for a packument or a publish.
    assertEquals("@qits%2fangular", pkg(NpmPaths.PACKUMENT, "/artifacts/npm/npm/@qits%2fangular"));
    assertEquals("@qits%2Fangular", pkg(NpmPaths.PACKUMENT, "/artifacts/npm/npm/@qits%2Fangular"));
    // Decoded: what a client sends when it follows the dist.tarball url this registry emits, and
    // what npmjs' own layout uses — so both have to work or half the protocol does.
    assertEquals("@qits/angular", pkg(NpmPaths.PACKUMENT, "/artifacts/npm/npm/@qits/angular"));
    assertEquals("left-pad", pkg(NpmPaths.PACKUMENT, "/artifacts/npm/npmjs/left-pad"));
  }

  @Test
  void theRepositoryIsTheFirstSegmentAfterTheBase() {
    assertEquals("npm", group(NpmPaths.PACKUMENT, "/artifacts/npm/npm/left-pad", "repository"));
    assertEquals("npmjs", group(NpmPaths.PACKUMENT, "/artifacts/npm/npmjs/left-pad", "repository"));
    // The base itself is not a repository, and neither is a repository with nothing after it.
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npm"));
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npm/"));
  }

  @Test
  void aTarballPathSplitsIntoPackageAndFile() {
    String scoped = "/artifacts/npm/npm/@qits/angular/-/angular-1.2.3.tgz";
    assertEquals("@qits/angular", group(NpmPaths.TARBALL, scoped, "pkg"));
    assertEquals("angular-1.2.3.tgz", group(NpmPaths.TARBALL, scoped, "file"));

    String encoded = "/artifacts/npm/npm/@qits%2fangular/-/angular-1.2.3.tgz";
    assertEquals("@qits%2fangular", group(NpmPaths.TARBALL, encoded, "pkg"));

    String unscoped = "/artifacts/npm/npmjs/left-pad/-/left-pad-1.3.0.tgz";
    assertEquals("left-pad", group(NpmPaths.TARBALL, unscoped, "pkg"));
    assertEquals("left-pad-1.3.0.tgz", group(NpmPaths.TARBALL, unscoped, "file"));

    // A prerelease with build metadata is a legal version and therefore a legal file name.
    assertEquals(
        "app-1.0.0-rc.1+build.2.tgz",
        group(
            NpmPaths.TARBALL,
            "/artifacts/npm/npm/app/-/app-1.0.0-rc.1+build.2.tgz",
            "file"));
  }

  @Test
  void theTwoRoutesDoNotOverlap() {
    // Unlike OCI's, this needs no greedy trick: a package name is at most two components and
    // neither may contain a slash, so nothing under /-/ can be read as a name. Pinned because it is
    // exactly the kind of property that stays true until someone loosens a character class.
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npm/left-pad/-/left-pad-1.3.0.tgz"));
    assertFalse(
        matches(NpmPaths.PACKUMENT, "/artifacts/npm/npm/@qits/angular/-/angular-1.0.0.tgz"));
    assertFalse(matches(NpmPaths.TARBALL, "/artifacts/npm/npm/left-pad"));
    // A version-addressed packument (npm sometimes tries /<pkg>/<version>) matches neither, falls
    // to the catch-all 404, and npm falls back to the full document. That is the intended answer.
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npm/left-pad/1.3.0"));
  }

  // --- dist-tags --------------------------------------------------------------------------------

  @Test
  void aDistTagPathSplitsIntoRepositoryPackageAndTag() {
    // npm puts the tag surface under the registry-level /-/ namespace, not under the package's own
    // path — so this is the one route whose package name arrives in the MIDDLE of the path, and the
    // %2f spelling still has to survive it.
    String encoded = "/artifacts/npm/npm/-/package/@qits%2fangular/dist-tags/main";
    assertEquals("npm", group(NpmPaths.DIST_TAG, encoded, "repository"));
    assertEquals("@qits%2fangular", group(NpmPaths.DIST_TAG, encoded, "pkg"));
    assertEquals("main", group(NpmPaths.DIST_TAG, encoded, "tag"));

    String decoded = "/artifacts/npm/npm/-/package/@qits/angular/dist-tags/latest";
    assertEquals("@qits/angular", group(NpmPaths.DIST_TAG, decoded, "pkg"));
    assertEquals("latest", group(NpmPaths.DIST_TAG, decoded, "tag"));

    String unscoped = "/artifacts/npm/npm/-/package/left-pad/dist-tags";
    assertEquals("left-pad", group(NpmPaths.DIST_TAGS, unscoped, "pkg"));
    assertEquals("@qits%2Fangular", pkg(NpmPaths.DIST_TAGS,
        "/artifacts/npm/npm/-/package/@qits%2Fangular/dist-tags"));
  }

  @Test
  void theDistTagRoutesOverlapNothingInEitherDirection() {
    // The load-bearing claim of the whole addition: a package name may not begin with `-`, so no
    // path starting <repo>/-/ can be read as a package or a tarball, and no package or tarball path
    // carries the two literal segments these need. Both directions, because "they cannot collide"
    // is a statement about four regexes and only two of them are new.
    String tags = "/artifacts/npm/npm/-/package/@qits%2fangular/dist-tags";
    String tag = tags + "/main";
    assertFalse(matches(NpmPaths.PACKUMENT, tags));
    assertFalse(matches(NpmPaths.PACKUMENT, tag));
    assertFalse(matches(NpmPaths.TARBALL, tags));
    assertFalse(matches(NpmPaths.TARBALL, tag));

    assertFalse(matches(NpmPaths.DIST_TAGS, "/artifacts/npm/npm/@qits%2fangular"));
    assertFalse(matches(NpmPaths.DIST_TAG, "/artifacts/npm/npm/@qits%2fangular"));
    assertFalse(
        matches(NpmPaths.DIST_TAGS, "/artifacts/npm/npm/@qits/angular/-/angular-1.0.0.tgz"));
    assertFalse(matches(NpmPaths.DIST_TAGS, "/artifacts/npm/npmjs/-/v1/search?text=x"));
    assertFalse(matches(NpmPaths.DIST_TAGS, "/artifacts/npm/npmjs/-/whoami"));

    // The two dist-tag routes do not overlap each other either: the list has no tag segment and the
    // move has exactly one, so a trailing slash or a second segment reaches neither.
    assertFalse(matches(NpmPaths.DIST_TAGS, tag));
    assertFalse(matches(NpmPaths.DIST_TAG, tags));
    assertFalse(matches(NpmPaths.DIST_TAG, tags + "/"));
    assertFalse(matches(NpmPaths.DIST_TAG, tags + "/main/extra"));
    // And a tag outside the grammar reaches the catch-all rather than a handler.
    assertFalse(matches(NpmPaths.DIST_TAG, tags + "/-leading-dash"));
  }

  @Test
  void theProtocolSideEndpointsMissBothRoutesAndReachTheCatchAll() {
    // Search, audit, whoami, login. npm degrades gracefully on a 404 for every one of them, which
    // is why they are absent rather than stubbed — but they must not accidentally look like a
    // package named `-`.
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npmjs/-/v1/search?text=x"));
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npmjs/-/whoami"));
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npmjs/-/npm/v1/security/audits/quick"));
    assertFalse(matches(NpmPaths.TARBALL, "/artifacts/npm/npmjs/-/v1/search"));
  }

  @Test
  void namesOutsideTheGrammarNeverReachAHandler() {
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npm/.hidden"), "leading dot");
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npm/_private"), "leading underscore");
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npm/@qits"), "a scope is not a package");
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npm/@qits/"), "no name after the scope");
    assertFalse(matches(NpmPaths.PACKUMENT, "/artifacts/npm/NPM/left-pad"), "uppercase repository");
    // Uppercase in a PACKAGE name is legal, though: JSONStream and its generation predate the rule
    // against it, and the proxy has to be able to fetch them.
    assertTrue(matches(NpmPaths.PACKUMENT, "/artifacts/npm/npmjs/JSONStream"));
  }

  @Test
  void everyGroupIsNamedOrNonCapturing() {
    // vertx-web compares Matcher.groupCount() against the named groups it scraped from the pattern
    // and falls back to positional param0..paramN when they disagree — so ONE bare (...) anywhere in
    // these patterns breaks pathParam("pkg") on that route, at runtime, silently. Counting the
    // groups is the cheapest possible guard.
    assertGroupsAllNamed(NpmPaths.PACKUMENT, 2);
    assertGroupsAllNamed(NpmPaths.TARBALL, 3);
    assertGroupsAllNamed(NpmPaths.DIST_TAGS, 2);
    assertGroupsAllNamed(NpmPaths.DIST_TAG, 3);
  }

  private static void assertGroupsAllNamed(String regex, int expectedNamed) {
    long named = Pattern.compile("\\(\\?<[a-zA-Z][a-zA-Z0-9]*>").matcher(regex).results().count();
    assertEquals(expectedNamed, named, "named group count changed in: " + regex);
    assertEquals(
        expectedNamed,
        Pattern.compile(regex).matcher("").groupCount(),
        "a bare capturing group crept into: " + regex);
  }

  private static boolean matches(String regex, String path) {
    return Pattern.compile(regex).matcher(path).matches();
  }

  private static String pkg(String regex, String path) {
    return group(regex, path, "pkg");
  }

  private static String group(String regex, String path, String groupName) {
    Matcher matcher = Pattern.compile(regex).matcher(path);
    assertTrue(matcher.matches(), regex + " should match " + path);
    return matcher.group(groupName);
  }
}
