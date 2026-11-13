package eu.wohlben.qits.containers.driver.swarm;

import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A swarm service, declared at once — everything {@code docker service create} states and what
 * {@code docker service update} restates of it. {@code SwarmArgv.serviceCreate} and {@code
 * SwarmArgv.serviceUpdate} render it.
 *
 * <p><b>Values arrive already computed.</b> The embedder decides what a service's labels, its
 * environment, its health command and its mounts are; this type holds them as strings, ints and
 * durations and decides nothing but their order on the argv. Labels in particular are passed through
 * exactly as given — no {@link eu.wohlben.qits.containers.driver.spec.LabelNamespace} is applied to a
 * service, because a deployer's labels and swarm's own ({@code com.docker.swarm.service.name}) are
 * not this jar's to rewrite.
 *
 * <p><b>Two environment lists, and the order between them is the point.</b> {@link #env} is the
 * embedder's own variables; {@link #envOverrides} is what configuration states on top of them. A
 * create renders the overrides <b>last</b> (after the groups, right before the image), an update
 * renders them right after {@link #env}: in both the last assignment of a key wins, so an override
 * outranks the embedder's default.
 *
 * <p>Unlike {@link eu.wohlben.qits.containers.driver.spec.ContainerSpec} this is not frozen and is
 * not hashed by anybody — nothing here is serialised. A new component is a new statement on the argv
 * and nothing else.
 *
 * @param name the service name — {@code --name} on a create, the target of an update
 * @param image the image reference, as {@code docker pull} would take it
 * @param replicas {@code --replicas}
 * @param noResolveImage {@code --no-resolve-image}: keep the tag as written instead of asking a
 *     registry for its digest
 * @param withRegistryAuth {@code --with-registry-auth}
 * @param networks the attachments, in order — a create's whole membership; an update states none
 * @param dns {@code --dns} resolvers in order, create only
 * @param restartCondition {@code --restart-condition}, create only; null states none
 * @param init {@code --init}
 * @param capAdd {@code --cap-add}, in order
 * @param sysctls {@code --sysctl} on a create, {@code --sysctl-add} on an update, {@code k=v}
 * @param labels {@code k=v}, each on the service AND its task container
 * @param health the healthcheck; null states none
 * @param updatePolicy the cutover; null states none
 * @param env {@code K=V}, the embedder's own variables
 * @param mounts {@code --mount}, in order, create only
 * @param publishes {@code --publish}, in order, create only
 * @param groups {@code --group}, in order, create only
 * @param envOverrides {@code K=V} stated on top of {@link #env}
 */
public record ServiceSpec(
    String name,
    String image,
    int replicas,
    boolean noResolveImage,
    boolean withRegistryAuth,
    List<NetworkAttachment> networks,
    List<String> dns,
    String restartCondition,
    boolean init,
    List<String> capAdd,
    List<String> sysctls,
    List<String> labels,
    Health health,
    UpdatePolicy updatePolicy,
    List<String> env,
    List<Mount> mounts,
    List<Publish> publishes,
    List<String> groups,
    List<String> envOverrides) {

  public ServiceSpec {
    Objects.requireNonNull(name, "service name");
    ContainersIdentifiers.requireImage(image);
    if (replicas < 0) {
      throw new IllegalArgumentException("Invalid replicas: " + replicas);
    }
    networks = copy(networks);
    dns = copy(dns);
    if (restartCondition != null) {
      SwarmIdentifiers.requireRestartCondition(restartCondition);
    }
    capAdd = copy(capAdd);
    capAdd.forEach(SwarmIdentifiers::requireCapability);
    sysctls = copy(sysctls);
    sysctls.forEach(SwarmIdentifiers::requireSysctl);
    labels = copy(labels);
    env = copy(env);
    mounts = copy(mounts);
    publishes = copy(publishes);
    groups = copy(groups);
    groups.forEach(SwarmIdentifiers::requireGroup);
    envOverrides = copy(envOverrides);
  }

  public static Builder builder(String name, String image) {
    return new Builder(name, image);
  }

  /**
   * One network the service is attached to, and the aliases it answers to there.
   *
   * <p>A create spells an attachment with no aliases in the short form ({@code --network <net>}) and
   * one with aliases as {@code name=<net>,alias=<a>,...}; a {@code --network-add} always uses the
   * long form. Both mean the same to swarm, and the short form is kept because it is what every
   * service on the platform was created with.
   */
  public record NetworkAttachment(String network, List<String> aliases) {

    public NetworkAttachment {
      Objects.requireNonNull(network, "network");
      aliases = copy(aliases);
      aliases.forEach(SwarmIdentifiers::requireAttachmentAlias);
    }

    public static NetworkAttachment of(String network) {
      return new NetworkAttachment(network, List.of());
    }

    /** {@code name=<net>[,alias=<a>]*} — the long form, always. */
    public String longForm() {
      StringBuilder attachment = new StringBuilder("name=").append(network);
      for (String alias : aliases) {
        attachment.append(",alias=").append(alias);
      }
      return attachment.toString();
    }

    /** What a create writes: the bare network when there is no alias, else {@link #longForm}. */
    public String createForm() {
      return aliases.isEmpty() ? network : longForm();
    }
  }

  /**
   * One {@code --mount type=<type>,source=<source>,target=<target>[,readonly]}.
   *
   * <p>The canonical constructor checks the type and nothing else of the two paths: a configured
   * mount (a host bind of the docker socket, say) is rendered as configuration states it, which is
   * what its embedder does today. {@link #volume} is the belted form for a named volume a repository
   * declared — source and target both checked, because both are spliced into one comma-separated
   * element where a stray comma would forge a field.
   */
  public record Mount(String type, String source, String target, boolean readOnly) {

    public Mount {
      SwarmIdentifiers.requireMountType(type);
      Objects.requireNonNull(source, "mount source");
      Objects.requireNonNull(target, "mount target");
    }

    /** A named volume, source and target belted ({@link SwarmIdentifiers#requireMountSource}). */
    public static Mount volume(String source, String target, boolean readOnly) {
      return new Mount(
          "volume",
          SwarmIdentifiers.requireMountSource(source),
          SwarmIdentifiers.requireMountTarget(target),
          readOnly);
    }

    /** The {@code --mount} element. */
    public String spelling() {
      return "type="
          + type
          + ",source="
          + source
          + ",target="
          + target
          + (readOnly ? ",readonly" : "");
    }
  }

  /**
   * One {@code --publish published=<p>,target=<t>[,protocol=<proto>],mode=<mode>}. Swarm's publish
   * has no ip field in either mode, so there is none here — a caller asked for one has to refuse.
   *
   * @param protocol {@code tcp} or {@code udp}, or null to state none (docker's default, tcp)
   * @param mode {@code host} or {@code ingress}
   */
  public record Publish(int published, int target, String protocol, String mode) {

    public Publish {
      SwarmIdentifiers.requirePort(published);
      SwarmIdentifiers.requirePort(target);
      if (protocol != null) {
        SwarmIdentifiers.requireProtocol(protocol);
      }
      SwarmIdentifiers.requirePublishMode(mode);
    }

    /** The {@code --publish} element. */
    public String spelling() {
      return "published="
          + published
          + ",target="
          + target
          + (protocol == null ? "" : ",protocol=" + protocol)
          + ",mode="
          + mode;
    }
  }

  /**
   * {@code --health-cmd <command> --health-interval <i> --health-retries <n> --health-start-period
   * <p>}. The command is one argv element, belted by {@link SwarmIdentifiers#requireHealthCmd}.
   */
  public record Health(String command, Duration interval, int retries, Duration startPeriod) {

    public Health {
      SwarmIdentifiers.requireHealthCmd(command);
      Objects.requireNonNull(interval, "health interval");
      Objects.requireNonNull(startPeriod, "health start period");
    }
  }

  /**
   * The cutover: {@code --update-order <o> --rollback-order <r> --update-monitor <m>
   * --update-failure-action <a> --stop-grace-period <g>}, valid on a create and an update alike.
   */
  public record UpdatePolicy(
      String order,
      String rollbackOrder,
      Duration monitor,
      String failureAction,
      Duration stopGracePeriod) {

    public UpdatePolicy {
      SwarmIdentifiers.requireUpdateOrder(order);
      SwarmIdentifiers.requireUpdateOrder(rollbackOrder);
      Objects.requireNonNull(monitor, "update monitor");
      SwarmIdentifiers.requireFailureAction(failureAction);
      Objects.requireNonNull(stopGracePeriod, "stop grace period");
    }
  }

  private static <T> List<T> copy(List<T> list) {
    return list == null ? List.of() : List.copyOf(list);
  }

  /** Fluent construction; every list setter appends, in call order. */
  public static final class Builder {

    private final String name;
    private final String image;
    private int replicas = 1;
    private boolean noResolveImage;
    private boolean withRegistryAuth;
    private final List<NetworkAttachment> networks = new ArrayList<>();
    private final List<String> dns = new ArrayList<>();
    private String restartCondition;
    private boolean init;
    private final List<String> capAdd = new ArrayList<>();
    private final List<String> sysctls = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private Health health;
    private UpdatePolicy updatePolicy;
    private final List<String> env = new ArrayList<>();
    private final List<Mount> mounts = new ArrayList<>();
    private final List<Publish> publishes = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private final List<String> envOverrides = new ArrayList<>();

    private Builder(String name, String image) {
      this.name = name;
      this.image = image;
    }

    public Builder replicas(int replicas) {
      this.replicas = replicas;
      return this;
    }

    public Builder noResolveImage(boolean noResolveImage) {
      this.noResolveImage = noResolveImage;
      return this;
    }

    public Builder withRegistryAuth(boolean withRegistryAuth) {
      this.withRegistryAuth = withRegistryAuth;
      return this;
    }

    public Builder network(String network) {
      networks.add(NetworkAttachment.of(network));
      return this;
    }

    public Builder network(String network, List<String> aliases) {
      networks.add(new NetworkAttachment(network, aliases));
      return this;
    }

    public Builder network(NetworkAttachment attachment) {
      networks.add(Objects.requireNonNull(attachment));
      return this;
    }

    public Builder dns(String server) {
      dns.add(Objects.requireNonNull(server, "dns server"));
      return this;
    }

    public Builder dns(List<String> servers) {
      servers.forEach(this::dns);
      return this;
    }

    public Builder restartCondition(String restartCondition) {
      this.restartCondition = restartCondition;
      return this;
    }

    public Builder init(boolean init) {
      this.init = init;
      return this;
    }

    public Builder capAdd(String capability) {
      capAdd.add(capability);
      return this;
    }

    public Builder capAdd(List<String> capabilities) {
      capAdd.addAll(capabilities);
      return this;
    }

    public Builder sysctl(String sysctl) {
      sysctls.add(sysctl);
      return this;
    }

    public Builder sysctls(List<String> pairs) {
      sysctls.addAll(pairs);
      return this;
    }

    /** One {@code key=value} label, as given. */
    public Builder label(String label) {
      labels.add(Objects.requireNonNull(label, "label"));
      return this;
    }

    public Builder labels(List<String> pairs) {
      pairs.forEach(this::label);
      return this;
    }

    public Builder health(Health health) {
      this.health = health;
      return this;
    }

    public Builder health(String command, Duration interval, int retries, Duration startPeriod) {
      return health(new Health(command, interval, retries, startPeriod));
    }

    public Builder updatePolicy(UpdatePolicy updatePolicy) {
      this.updatePolicy = updatePolicy;
      return this;
    }

    /** One {@code KEY=VALUE}, as given. */
    public Builder env(String variable) {
      env.add(Objects.requireNonNull(variable, "env"));
      return this;
    }

    public Builder env(List<String> variables) {
      variables.forEach(this::env);
      return this;
    }

    public Builder mount(Mount mount) {
      mounts.add(Objects.requireNonNull(mount));
      return this;
    }

    public Builder publish(Publish publish) {
      publishes.add(Objects.requireNonNull(publish));
      return this;
    }

    public Builder group(String group) {
      groups.add(group);
      return this;
    }

    public Builder groups(List<String> values) {
      groups.addAll(values);
      return this;
    }

    /** One {@code KEY=VALUE} stated on top of {@link #env}, as given. */
    public Builder envOverride(String variable) {
      envOverrides.add(Objects.requireNonNull(variable, "env override"));
      return this;
    }

    public Builder envOverrides(List<String> variables) {
      variables.forEach(this::envOverride);
      return this;
    }

    public ServiceSpec build() {
      return new ServiceSpec(
          name,
          image,
          replicas,
          noResolveImage,
          withRegistryAuth,
          networks,
          dns,
          restartCondition,
          init,
          capAdd,
          sysctls,
          labels,
          health,
          updatePolicy,
          env,
          mounts,
          publishes,
          groups,
          envOverrides);
    }
  }
}
