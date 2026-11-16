package eu.wohlben.qits.containers.driver.docker;

import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.driver.swarm.ServiceSpec;
import eu.wohlben.qits.containers.driver.swarm.ServiceUpdate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every swarm command line — {@code docker service ...}, the overlay networks services live on, and
 * the task-container reads beside them — as pure functions, {@link DockerArgv}'s discipline: a spec
 * goes in, a {@code List<String>} comes out, asserted element for element in a docker-free suite.
 *
 * <p>The runtime binary comes first, as in {@link DockerArgv}. Go templates ({@code --format}) and
 * filters ({@code --filter}) are the caller's: a template is a question only the caller knows it is
 * asking, and the parse of its answer lives beside it. {@link #labelFilter} spells the one filter
 * every caller writes.
 *
 * <p><b>Byte-identical with qits-deployments-service's hand-built argvs</b>, which these replace:
 * every flag order here is that driver's, so moving onto them changes no command it issues. The
 * belts are {@link eu.wohlben.qits.containers.driver.swarm.SwarmIdentifiers}' and run inside the
 * spec types; references (service, task, network, container) are rendered as given — see that class
 * for why.
 *
 * <p><b>The operator's reads are the exception, and say so.</b> {@link #nodeInspect}, {@link
 * #configInspect} and the two-argument {@link #serviceInspect(String, String)} take a reference a
 * person typed into qits-system-service's console rather than one read off the daemon, so they run
 * {@link ContainersIdentifiers#requireReference} on it — that service's own belt, lifted. Their
 * flag order is that service's, as the deployer's is everywhere else here.
 */
public final class SwarmArgv {

  private SwarmArgv() {}

  // --- services --------------------------------------------------------------------------------

  /**
   * {@code service create}, the whole service at once:
   *
   * <pre>
   * service create --detach --name N --replicas R [--no-resolve-image] [--with-registry-auth]
   *   (--network A)* (--dns S)* [--restart-condition C] [--init] (--cap-add C)* (--sysctl K=V)*
   *   (--label L --container-label L)* [health] [update policy] (--env E)* (--mount M)*
   *   (--publish P)* (--group G)* (--env override)* IMAGE
   * </pre>
   *
   * {@code --detach} always: the caller reads the verdict itself, and without it the CLI blocks on
   * convergence so the caller's timeout would be the CLI's.
   */
  public static List<String> serviceCreate(String runtimeBinary, ServiceSpec spec) {
    List<String> argv =
        new ArrayList<>(
            List.of(
                runtimeBinary,
                "service",
                "create",
                "--detach",
                "--name",
                spec.name(),
                "--replicas",
                String.valueOf(spec.replicas())));
    if (spec.noResolveImage()) {
      argv.add("--no-resolve-image");
    }
    if (spec.withRegistryAuth()) {
      argv.add("--with-registry-auth");
    }
    for (ServiceSpec.NetworkAttachment network : spec.networks()) {
      argv.add("--network");
      argv.add(network.createForm());
    }
    for (String server : spec.dns()) {
      argv.add("--dns");
      argv.add(server);
    }
    if (spec.restartCondition() != null) {
      argv.add("--restart-condition");
      argv.add(spec.restartCondition());
    }
    if (spec.init()) {
      argv.add("--init");
    }
    pairs(argv, "--cap-add", spec.capAdd());
    pairs(argv, "--sysctl", spec.sysctls());
    for (String label : spec.labels()) {
      argv.add("--label");
      argv.add(label);
      argv.add("--container-label");
      argv.add(label);
    }
    health(argv, spec);
    updatePolicy(argv, spec);
    pairs(argv, "--env", spec.env());
    for (ServiceSpec.Mount mount : spec.mounts()) {
      argv.add("--mount");
      argv.add(mount.spelling());
    }
    for (ServiceSpec.Publish publish : spec.publishes()) {
      argv.add("--publish");
      argv.add(publish.spelling());
    }
    pairs(argv, "--group", spec.groups());
    pairs(argv, "--env", spec.envOverrides());
    argv.add(spec.image());
    return List.copyOf(argv);
  }

  /** {@link #serviceUpdate(String, ServiceSpec, ServiceUpdate)} with no delta. */
  public static List<String> serviceUpdate(String runtimeBinary, ServiceSpec spec) {
    return serviceUpdate(runtimeBinary, spec, ServiceUpdate.NONE);
  }

  /**
   * {@code service update}, restating what an update can restate of the spec, then the delta, then
   * the target ({@link ServiceSpec#name}):
   *
   * <pre>
   * service update --detach [--no-resolve-image] --image IMAGE --replicas R [--with-registry-auth]
   *   (--label-add L --container-label-add L)* [health] [update policy] [--init] (--cap-add C)*
   *   (--sysctl-add K=V)* (--env-add E)* (--env-add override)*
   *   (--env-rm K)* (--network-rm N)* (--network-add name=N[,alias=A]*)* (--dns-rm S)* (--dns-add S)*
   *   NAME
   * </pre>
   *
   * <p><b>Not restated</b>: networks, resolvers, mounts, publishes, groups and the restart
   * condition. An update states what changes, and re-stating a mount would append a second copy of
   * it; membership and resolvers move only through the explicit {@link ServiceUpdate} delta.
   *
   * <p>The replica count IS restated: a service an operator scaled to zero takes an image update
   * without complaint and converges at once with nothing running.
   */
  public static List<String> serviceUpdate(
      String runtimeBinary, ServiceSpec spec, ServiceUpdate delta) {
    List<String> argv =
        new ArrayList<>(List.of(runtimeBinary, "service", "update", "--detach"));
    if (spec.noResolveImage()) {
      argv.add("--no-resolve-image");
    }
    argv.add("--image");
    argv.add(spec.image());
    argv.add("--replicas");
    argv.add(String.valueOf(spec.replicas()));
    if (spec.withRegistryAuth()) {
      argv.add("--with-registry-auth");
    }
    for (String label : spec.labels()) {
      argv.add("--label-add");
      argv.add(label);
      argv.add("--container-label-add");
      argv.add(label);
    }
    health(argv, spec);
    updatePolicy(argv, spec);
    if (spec.init()) {
      argv.add("--init");
    }
    pairs(argv, "--cap-add", spec.capAdd());
    pairs(argv, "--sysctl-add", spec.sysctls());
    pairs(argv, "--env-add", spec.env());
    pairs(argv, "--env-add", spec.envOverrides());
    pairs(argv, "--env-rm", delta.envRemovals());
    pairs(argv, "--network-rm", delta.networkRemovals());
    for (ServiceSpec.NetworkAttachment network : delta.networkAdditions()) {
      argv.add("--network-add");
      argv.add(network.longForm());
    }
    pairs(argv, "--dns-rm", delta.dnsRemovals());
    pairs(argv, "--dns-add", delta.dnsAdditions());
    argv.add(spec.name());
    return List.copyOf(argv);
  }

  /**
   * {@code service update --detach --replicas N <service>} — what {@code docker service scale} is
   * underneath, spelled as an update because {@code scale} blocks on convergence by default.
   */
  public static List<String> serviceScale(String runtimeBinary, String service, int replicas) {
    if (replicas < 0) {
      throw new IllegalArgumentException("Invalid replicas: " + replicas);
    }
    return List.of(
        runtimeBinary,
        "service",
        "update",
        "--detach",
        "--replicas",
        String.valueOf(replicas),
        Objects.requireNonNull(service, "service"));
  }

  /** {@code service update --detach --force <service>}: swarm's bounce — same spec, new tasks. */
  public static List<String> serviceForceUpdate(String runtimeBinary, String service) {
    return List.of(
        runtimeBinary,
        "service",
        "update",
        "--detach",
        "--force",
        Objects.requireNonNull(service, "service"));
  }

  /**
   * {@code service inspect [--format <template>] <service>}. A null format states none — the
   * daemon's own document, a JSON array.
   */
  public static List<String> serviceInspect(String runtimeBinary, String service, String format) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "service", "inspect"));
    if (format != null) {
      argv.add("--format");
      argv.add(format);
    }
    argv.add(Objects.requireNonNull(service, "service"));
    return List.copyOf(argv);
  }

  /**
   * {@code service inspect <reference>} — the whole document, of a service a person named; the
   * reference is belted ({@link ContainersIdentifiers#requireReference}).
   */
  public static List<String> serviceInspect(String runtimeBinary, String reference) {
    return serviceInspect(
        runtimeBinary, ContainersIdentifiers.requireReference("service", reference), null);
  }

  /**
   * {@code service ps <service> (--filter F)* [--no-trunc] [--format <template>]}. A null format
   * states none — docker's own table, which is what a person reads.
   */
  public static List<String> servicePs(
      String runtimeBinary, String service, List<String> filters, boolean noTrunc, String format) {
    List<String> argv =
        new ArrayList<>(
            List.of(runtimeBinary, "service", "ps", Objects.requireNonNull(service, "service")));
    filters(argv, filters);
    if (noTrunc) {
      argv.add("--no-trunc");
    }
    if (format != null) {
      argv.add("--format");
      argv.add(format);
    }
    return List.copyOf(argv);
  }

  /** {@code service logs --tail N <service-or-task>} — a service's output, or one task's. */
  public static List<String> serviceLogsTail(String runtimeBinary, String serviceOrTask, int lines) {
    if (lines <= 0) {
      throw new IllegalArgumentException("Invalid log tail: " + lines + " lines");
    }
    return List.of(
        runtimeBinary,
        "service",
        "logs",
        "--tail",
        String.valueOf(lines),
        Objects.requireNonNull(serviceOrTask, "service"));
  }

  /** {@code service ls (--filter F)* [--format <template>]} — the services, for a person to read. */
  public static List<String> serviceLs(String runtimeBinary, List<String> filters, String format) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "service", "ls"));
    filters(argv, filters);
    format(argv, format);
    return List.copyOf(argv);
  }

  /** {@code service ls -q (--filter F)*} — the ids of the matching services. */
  public static List<String> serviceLsQuiet(String runtimeBinary, List<String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "service", "ls", "-q"));
    filters(argv, filters);
    return List.copyOf(argv);
  }

  /** {@code service rm <service>}. */
  public static List<String> serviceRm(String runtimeBinary, String service) {
    return serviceRm(runtimeBinary, List.of(Objects.requireNonNull(service, "service")));
  }

  /** {@code service rm <service>...}, in the order given. An empty list is refused. */
  public static List<String> serviceRm(String runtimeBinary, List<String> services) {
    if (services.isEmpty()) {
      throw new IllegalArgumentException("Invalid service rm: no service named");
    }
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "service", "rm"));
    argv.addAll(services);
    return List.copyOf(argv);
  }

  // --- nodes, configs and secrets ----------------------------------------------------------------

  /** {@code node ls [--format <template>]}. */
  public static List<String> nodeLs(String runtimeBinary, String format) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "node", "ls"));
    format(argv, format);
    return List.copyOf(argv);
  }

  /** {@code node inspect <reference>} — the whole document; the reference is belted. */
  public static List<String> nodeInspect(String runtimeBinary, String reference) {
    return List.of(
        runtimeBinary, "node", "inspect", ContainersIdentifiers.requireReference("node", reference));
  }

  /** {@code config ls [--format <template>]}. */
  public static List<String> configLs(String runtimeBinary, String format) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "config", "ls"));
    format(argv, format);
    return List.copyOf(argv);
  }

  /** {@code config inspect <reference>} — the whole document; the reference is belted. */
  public static List<String> configInspect(String runtimeBinary, String reference) {
    return List.of(
        runtimeBinary,
        "config",
        "inspect",
        ContainersIdentifiers.requireReference("config", reference));
  }

  /**
   * {@code secret ls [--format <template>]}. A listing only: there is no {@code secret inspect}
   * here, and a secret's value is something no docker command prints anyway.
   */
  public static List<String> secretLs(String runtimeBinary, String format) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "secret", "ls"));
    format(argv, format);
    return List.copyOf(argv);
  }

  // --- overlay networks -------------------------------------------------------------------------

  /**
   * {@code network create -d overlay [--attachable] [--opt encrypted] [--subnet S] (--label L)* N}.
   * {@code --attachable} is what lets plain {@code docker run} containers join a network services
   * live on. Labels are {@code key=value}, as given.
   *
   * @param subnet a CIDR, or null to let docker pick one
   */
  public static List<String> overlayNetworkCreate(
      String runtimeBinary,
      String network,
      boolean attachable,
      boolean encrypted,
      String subnet,
      List<String> labels) {
    List<String> argv =
        new ArrayList<>(List.of(runtimeBinary, "network", "create", "-d", "overlay"));
    if (attachable) {
      argv.add("--attachable");
    }
    if (encrypted) {
      argv.add("--opt");
      argv.add("encrypted");
    }
    if (subnet != null) {
      argv.add("--subnet");
      argv.add(subnet);
    }
    pairs(argv, "--label", labels);
    argv.add(Objects.requireNonNull(network, "network"));
    return List.copyOf(argv);
  }

  /** {@code network rm <network>}. */
  public static List<String> networkRm(String runtimeBinary, String network) {
    return List.of(runtimeBinary, "network", "rm", Objects.requireNonNull(network, "network"));
  }

  /** {@code network ls (--filter F)* [--format <template>]}. */
  public static List<String> networkLs(String runtimeBinary, List<String> filters, String format) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "network", "ls"));
    filters(argv, filters);
    if (format != null) {
      argv.add("--format");
      argv.add(format);
    }
    return List.copyOf(argv);
  }

  /**
   * {@code network inspect [--format <template>] <network>}. Unlike {@link
   * DockerArgv#networkInspect} the name is not belted: it is a configured or daemon-read reference.
   */
  public static List<String> networkInspect(String runtimeBinary, String network, String format) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "network", "inspect"));
    if (format != null) {
      argv.add("--format");
      argv.add(format);
    }
    argv.add(Objects.requireNonNull(network, "network"));
    return List.copyOf(argv);
  }

  // --- task containers -------------------------------------------------------------------------

  /**
   * {@code inspect --format <template> <container>} — a task container, typically this process's
   * own, by the id its hostname carries.
   */
  public static List<String> containerInspect(
      String runtimeBinary, String container, String format) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        Objects.requireNonNull(format, "format"),
        Objects.requireNonNull(container, "container"));
  }

  /**
   * {@code ps --quiet (--filter F)*} — RUNNING containers only (no {@code -a}): what is asked is
   * whether a service's tasks are still up, and an exited one is not.
   */
  public static List<String> psQuiet(String runtimeBinary, List<String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "ps", "--quiet"));
    filters(argv, filters);
    return List.copyOf(argv);
  }

  // --- spellings -------------------------------------------------------------------------------

  /** {@code label=<key>=<value>}, the filter for one label's value. */
  public static String labelFilter(String key, String value) {
    return "label=" + Objects.requireNonNull(key, "label key") + "=" + value;
  }

  /** {@code label=<key>}, the filter for a label's presence. */
  public static String labelFilter(String key) {
    return "label=" + Objects.requireNonNull(key, "label key");
  }

  /**
   * A duration as docker's flag parser reads it ({@code time.ParseDuration}): whole seconds as
   * {@code <n>s}, anything finer as {@code <n>ms}. So {@code Duration.ofSeconds(30)} is {@code 30s}.
   */
  public static String goDuration(Duration duration) {
    return duration.toMillisPart() == 0
        ? duration.toSeconds() + "s"
        : duration.toMillis() + "ms";
  }

  private static void health(List<String> argv, ServiceSpec spec) {
    ServiceSpec.Health health = spec.health();
    if (health == null) {
      return;
    }
    argv.add("--health-cmd");
    argv.add(health.command());
    argv.add("--health-interval");
    argv.add(goDuration(health.interval()));
    argv.add("--health-retries");
    argv.add(String.valueOf(health.retries()));
    argv.add("--health-start-period");
    argv.add(goDuration(health.startPeriod()));
  }

  private static void updatePolicy(List<String> argv, ServiceSpec spec) {
    ServiceSpec.UpdatePolicy policy = spec.updatePolicy();
    if (policy == null) {
      return;
    }
    argv.add("--update-order");
    argv.add(policy.order());
    argv.add("--rollback-order");
    argv.add(policy.rollbackOrder());
    argv.add("--update-monitor");
    argv.add(goDuration(policy.monitor()));
    argv.add("--update-failure-action");
    argv.add(policy.failureAction());
    argv.add("--stop-grace-period");
    argv.add(goDuration(policy.stopGracePeriod()));
  }

  private static void pairs(List<String> argv, String flag, List<String> values) {
    for (String value : values) {
      argv.add(flag);
      argv.add(value);
    }
  }

  private static void filters(List<String> argv, List<String> filters) {
    pairs(argv, "--filter", filters == null ? List.of() : filters);
  }

  private static void format(List<String> argv, String format) {
    if (format != null) {
      argv.add("--format");
      argv.add(format);
    }
  }
}
