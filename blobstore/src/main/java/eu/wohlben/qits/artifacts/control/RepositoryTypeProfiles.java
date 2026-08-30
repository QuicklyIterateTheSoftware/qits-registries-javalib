package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.error.BadRequestException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves a stored {@code artifact_repository.type} to the {@link RepositoryTypeProfile} that
 * claims it, over every profile bean on the classpath.
 *
 * <p>This is the whole of the open registration: the core knows the <em>shape</em> of a type and
 * nothing about which types exist. A format module contributes its profiles as beans and needs no
 * edit here; a deployment that leaves a module out simply has no rows of its keys.
 *
 * <p><b>An unknown key is a hard error at use</b>, not a shrug. It means a row carries a type
 * nothing on this classpath can enforce — so the validation that row's blobs were accepted under is
 * unavailable, and serving or extending it would be guessing. Two profiles claiming one key is a
 * startup failure for the same reason: a type must mean one thing.
 */
@ApplicationScoped
public class RepositoryTypeProfiles {

  @Inject Instance<RepositoryTypeProfile> contributed;

  private Map<String, RepositoryTypeProfile> byKey;

  @PostConstruct
  void index() {
    Map<String, RepositoryTypeProfile> index = new TreeMap<>();
    for (RepositoryTypeProfile profile : contributed) {
      RepositoryTypeProfile clash = index.put(profile.key(), profile);
      if (clash != null) {
        throw new IllegalStateException(
            "Two repository type profiles claim the key "
                + profile.key()
                + ": "
                + clash.getClass().getName()
                + " and "
                + profile.getClass().getName());
      }
    }
    byKey = Collections.unmodifiableMap(index);
  }

  /** The profile for a stored key, or a 400 naming what is registered. */
  public RepositoryTypeProfile require(String key) {
    RepositoryTypeProfile profile = key == null ? null : byKey.get(key);
    if (profile == null) {
      throw new BadRequestException(
          "Unknown repository type: " + key + " (registered: " + keys() + ")");
    }
    return profile;
  }

  /** The profile for a kebab wire form ({@code ci-screenshots}), or a 400. */
  public RepositoryTypeProfile requireWireName(String wireName) {
    return require(RepositoryTypeProfile.keyOfWireName(wireName));
  }

  public Optional<RepositoryTypeProfile> find(String key) {
    return Optional.ofNullable(key == null ? null : byKey.get(key));
  }

  /** Every registered key, sorted — what a deployment can actually enforce. */
  public Set<String> keys() {
    return byKey.keySet();
  }
}
