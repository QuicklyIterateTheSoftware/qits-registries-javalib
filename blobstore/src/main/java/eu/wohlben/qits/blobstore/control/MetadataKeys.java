package eu.wohlben.qits.blobstore.control;

/**
 * The well-known metadata keys (docs/epics/qits-artifacts/). The store validates what a repository
 * type requires and stores the rest opaquely; these are the keys with defined server behaviour or a
 * role in the golden-pairing query.
 */
public final class MetadataKeys {

  private MetadataKeys() {}

  /** The blob's media type — server-controlled (sniffed), mirrored into the map for query. */
  public static final String MEDIATYPE = "mediatype";

  /** Upload timestamp — <b>server-stamped</b>, never trusted from the wire. ISO-8601. */
  public static final String CREATED_AT = "created-at";

  /** Half of the golden pairing key: which branch the artifact was produced from. */
  public static final String GIT_BRANCH_NAME = "git.branch.name";

  /** The commit the artifact was produced from. */
  public static final String GIT_COMMIT_HASH = "git.commit.hash";

  /** The repository the artifact was produced from (e.g. {@code qits-githost}). */
  public static final String GIT_REPOSITORY_NAME = "git.repository.name";

  /** Half of the golden pairing key: which logical user flow was recorded. */
  public static final String USERFLOW_NAME = "qits.userflow.name";

  public static final String RESOLUTION_WIDTH = "media.resolution.width";
  public static final String RESOLUTION_HEIGHT = "media.resolution.height";

  /** Keys the server owns and overwrites — a wire-supplied value for any of these is discarded. */
  public static final java.util.Set<String> SERVER_OWNED = java.util.Set.of(MEDIATYPE, CREATED_AT);
}
