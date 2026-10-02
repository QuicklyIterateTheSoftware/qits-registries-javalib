package eu.wohlben.qits.registry;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The {@link ContentHashLedger} every deployment gets unless it supplies its own.
 *
 * <p>{@code @DefaultBean}, so {@code qits-artifacts-service}'s {@code JpaContentHashLedger} simply
 * out-ranks this one by existing — no priority or alternative to wire, the same shape as every
 * other default this platform leaves a service to override. {@code qits-mirror-service}, which has
 * nowhere to store the value and no business doing so, never supplies one and runs this.
 */
@DefaultBean
@ApplicationScoped
class NoContentHashLedger implements ContentHashLedger {

  @Override
  public void record(String ecosystem, String repository, String name, String version, String value) {
    // Nothing to do: this deployment has no table to write it to, and the caller already staged
    // and stored the version regardless of whether a hash arrived with it.
  }
}
