package eu.wohlben.qits.npm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.artifacts.control.NpmPackageName;
import eu.wohlben.qits.artifacts.control.NpmRegistryService;
import eu.wohlben.qits.blobstore.error.InternalServerErrorException;
import eu.wohlben.qits.artifacts.error.NpmException;
import java.util.List;
import java.util.Map;

/**
 * Packument assembly, for both repository types.
 *
 * <p>A packument is npm's index of a package: {@code dist-tags}, and every version's manifest under
 * {@code versions}. For the hosted type it is <b>derived state</b>, built here per request from
 * {@code npm_version} + {@code npm_dist_tag} rows and never stored — the same reasoning that keeps
 * OCI tags in one table, so a packument cannot become a second source of truth that drifts from the
 * rows. For the proxy it is upstream's document with one field per version rewritten.
 *
 * <p>{@code dist.tarball} is the reason both paths run through here rather than one being a stored
 * blob. npm refuses a relative tarball URL, and the absolute one it needs depends on <b>the
 * request</b> — the gateway's {@code X-Forwarded-Host} from outside, the authority actually dialled
 * on qits-net from inside — so it cannot be computed once and kept. It is not a config key either:
 * naming a deployment fact in configuration is how the wrong host ends up in a document nobody
 * notices until an install fails.
 *
 * <p>Everything here is {@link JsonNode} in and {@link ObjectNode} out. No DTO is bound, ever: a
 * type serialised only inside a raw Vert.x handler is invisible to the native-image build, which is
 * the {@code dto/UploadResult} scar this whole stack is written to avoid re-earning.
 */
final class NpmPackuments {

  private NpmPackuments() {}

  /**
   * The absolute URL a client will fetch a tarball from — npmjs' own layout, {@code
   * <base>/<name>/-/<unscoped>-<version>.tgz}, with the scope spelled out rather than encoded.
   *
   * <p>The <b>unscoped</b> file name is what makes the tarball route able to recover the version
   * from the path, since the package name is already known from the segments before it. Emitting a
   * different shape here would still install, but would break that recovery — so this function and
   * {@link NpmPackageName#versionOfTarball} are two halves of one decision.
   */
  static String tarballUrl(String base, NpmPackageName pkg, String version) {
    return base + "/" + pkg.full() + "/-/" + pkg.tarballFile(version);
  }

  /** The hosted packument: assembled from rows, with {@code dist} rebuilt from stored hashes. */
  static ObjectNode hosted(
      ObjectMapper json,
      NpmPackageName pkg,
      List<NpmRegistryService.StoredVersion> versions,
      Map<String, String> distTags,
      String tarballBase) {

    ObjectNode root = json.createObjectNode();
    root.put("_id", pkg.full());
    root.put("name", pkg.full());

    ObjectNode tags = root.putObject("dist-tags");
    distTags.forEach(tags::put);

    ObjectNode versionsNode = root.putObject("versions");
    for (NpmRegistryService.StoredVersion stored : versions) {
      ObjectNode manifest = parseObject(json, stored.manifestJson(), pkg.full());
      // The manifest is stored exactly as published, so it may carry the publishing client's own
      // guess at dist.tarball. Overriding the three fields we own — rather than replacing `dist`
      // wholesale — keeps fileCount/unpackedSize and anything else npm has added since.
      ObjectNode dist =
          manifest.path("dist").isObject()
              ? (ObjectNode) manifest.get("dist")
              : manifest.putObject("dist");
      dist.put("tarball", tarballUrl(tarballBase, pkg, stored.version()));
      dist.put("shasum", stored.shasum());
      dist.put("integrity", stored.integrity());
      manifest.put("name", pkg.full());
      manifest.put("version", stored.version());
      versionsNode.set(stored.version(), manifest);
    }
    return root;
  }

  /**
   * Upstream's packument with every {@code dist.tarball} pointed back at this proxy.
   *
   * <p>Only that one field per version moves. Everything else — and {@code integrity} in
   * particular — is re-emitted <b>unmodified</b>, which is what makes the proxy incapable of
   * silently corrupting a package even in principle: the client verifies the bytes it downloads
   * against a hash this service never computed and cannot forge without upstream's cooperation.
   */
  static ObjectNode rewritten(JsonNode upstream, NpmPackageName pkg, String tarballBase) {
    if (!upstream.isObject()) {
      throw new NpmException(502, "upstream returned a packument that is not a JSON object");
    }
    ObjectNode root = (ObjectNode) upstream.deepCopy();
    JsonNode versions = root.path("versions");
    if (versions.isObject()) {
      versions
          .properties()
          .forEach(
              entry -> {
                if (!entry.getValue().isObject()) {
                  return;
                }
                ObjectNode manifest = (ObjectNode) entry.getValue();
                ObjectNode dist =
                    manifest.path("dist").isObject()
                        ? (ObjectNode) manifest.get("dist")
                        : manifest.putObject("dist");
                dist.put("tarball", tarballUrl(tarballBase, pkg, entry.getKey()));
              });
    }
    return root;
  }

  private static ObjectNode parseObject(ObjectMapper json, String text, String what) {
    try {
      JsonNode node = json.readTree(text);
      if (node != null && node.isObject()) {
        return (ObjectNode) node;
      }
    } catch (Exception unreadable) {
      // fall through to the same error: a stored manifest that will not parse is a 500 either way
    }
    throw new InternalServerErrorException(
        "stored manifest for " + what + " is not a JSON object");
  }
}
