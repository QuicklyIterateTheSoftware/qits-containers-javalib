package eu.wohlben.qits.containers.driver.swarm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a {@code docker service update} removes and adds beyond restating its {@link ServiceSpec} —
 * the deltas only the embedder can compute, because each one is a diff against the live service.
 *
 * <p>Rendered after the restated spec and before the target, in this order: {@code --env-rm}, {@code
 * --network-rm}, {@code --network-add} (always the long {@code name=<net>[,alias=<a>]} form), {@code
 * --dns-rm}, {@code --dns-add}. {@link #NONE} renders nothing, so an update with no delta is the
 * restated spec alone.
 *
 * <p>The order is load-bearing for the resolvers: the CLI does not promise to keep a {@code
 * --dns-add} list's order against what it removes, so a caller that cares states every live one in
 * {@link #dnsRemovals} and the whole wanted list in {@link #dnsAdditions}.
 *
 * @param envRemovals {@code --env-rm <KEY>}, as given — sort them if a stable argv matters
 * @param networkRemovals {@code --network-rm <net>}
 * @param networkAdditions {@code --network-add name=<net>[,alias=<a>]*}
 * @param dnsRemovals {@code --dns-rm <server>}
 * @param dnsAdditions {@code --dns-add <server>}
 */
public record ServiceUpdate(
    List<String> envRemovals,
    List<String> networkRemovals,
    List<ServiceSpec.NetworkAttachment> networkAdditions,
    List<String> dnsRemovals,
    List<String> dnsAdditions) {

  /** No delta: the update restates the spec and nothing else. */
  public static final ServiceUpdate NONE = new ServiceUpdate(null, null, null, null, null);

  public ServiceUpdate {
    envRemovals = copy(envRemovals);
    networkRemovals = copy(networkRemovals);
    networkAdditions = copy(networkAdditions);
    dnsRemovals = copy(dnsRemovals);
    dnsAdditions = copy(dnsAdditions);
  }

  public static Builder builder() {
    return new Builder();
  }

  private static <T> List<T> copy(List<T> list) {
    return list == null ? List.of() : List.copyOf(list);
  }

  /** Fluent construction; every setter appends, in call order. */
  public static final class Builder {

    private final List<String> envRemovals = new ArrayList<>();
    private final List<String> networkRemovals = new ArrayList<>();
    private final List<ServiceSpec.NetworkAttachment> networkAdditions = new ArrayList<>();
    private final List<String> dnsRemovals = new ArrayList<>();
    private final List<String> dnsAdditions = new ArrayList<>();

    private Builder() {}

    public Builder envRemoval(String key) {
      envRemovals.add(Objects.requireNonNull(key, "env key"));
      return this;
    }

    public Builder envRemovals(List<String> keys) {
      keys.forEach(this::envRemoval);
      return this;
    }

    public Builder networkRemoval(String network) {
      networkRemovals.add(Objects.requireNonNull(network, "network"));
      return this;
    }

    public Builder networkAddition(String network, List<String> aliases) {
      networkAdditions.add(new ServiceSpec.NetworkAttachment(network, aliases));
      return this;
    }

    public Builder dnsRemoval(String server) {
      dnsRemovals.add(Objects.requireNonNull(server, "dns server"));
      return this;
    }

    public Builder dnsRemovals(List<String> servers) {
      servers.forEach(this::dnsRemoval);
      return this;
    }

    public Builder dnsAddition(String server) {
      dnsAdditions.add(Objects.requireNonNull(server, "dns server"));
      return this;
    }

    public Builder dnsAdditions(List<String> servers) {
      servers.forEach(this::dnsAddition);
      return this;
    }

    public ServiceUpdate build() {
      return new ServiceUpdate(
          envRemovals, networkRemovals, networkAdditions, dnsRemovals, dnsAdditions);
    }
  }
}
