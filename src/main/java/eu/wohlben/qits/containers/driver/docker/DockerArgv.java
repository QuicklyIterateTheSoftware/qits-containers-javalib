package eu.wohlben.qits.containers.driver.docker;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.TreeMap;

/**
 * Every docker command line this service will ever run, as pure functions.
 *
 * <p>No I/O, no {@link ProcessBuilder}, no config, no clock — a spec goes in and a {@code List
 * <String>} comes out. That is what lets the argvs be asserted <b>element for element</b> in a
 * docker-free suite, and it is why they are assembled here rather than inside the driver: the argv
 * <b>is</b> the sandbox, and a flag lost in a refactor is invisible everywhere else until it is
 * invisible in production.
 *
 * <p><b>The belts run here too.</b> Validation at the API layer is the first checkpoint and this is
 * the second, unconditionally, on every value that reaches an element — because the API layer is one
 * loosened check away from being no checkpoint at all, and the point of two is that neither has to
 * be trusted alone.
 *
 * <p><b>{@code --rm} appears in nothing here, ever.</b> A self-removing container races the {@code
 * docker logs} capture that is the only diagnosis a container which died on its first breath can
 * offer, and it would delete a container whose registry row still names it — which is the one state
 * the adoption rule exists to make impossible. Every teardown is an explicit {@link #rm}.
 *
 * <p>That stays true of this class, of {@code ContainersDriver} and of {@link ContainerSpec}. The
 * two sanctioned exceptions live elsewhere, both under the owner's decision of 2026-11-13, when
 * every component that talks to docker moved onto this library. {@link InteractiveArgv}: an
 * attached terminal session ({@code run --rm -it}) whose teardown is its caller's (qits-2211). And
 * {@link HostRunArgv}: an embedder's one-shot helpers and its attached payload run ({@code run
 * --rm}), whose teardown is docker's {@code --rm} (qits-2212). Neither is named by a registry row,
 * and nothing here adopts them; a third exception is a decision, not a refactor.
 *
 * <p><b>Nor is there a general {@code exec} here</b>: the only ones are {@link #buildctlPrune} and
 * {@link #buildctlDu}, whose container and command are constants. {@code exec} of a caller's
 * command has two sanctioned exceptions, both outside this class: {@link InteractiveArgv}'s {@code
 * exec -it} for a terminal session (qits-2211), and {@link ExecArgv}'s plain {@code exec}, used only
 * by the system runner's DOCKER exec relay (owner rule 2026-11-13, qits-2289).
 *
 * <p><b>qits-bootstrap-cli's commands</b> (qits-2212) are here too, element for element as that
 * program renders them, short {@code -f} spellings included: {@link #inspect}, {@link
 * #inspectTypeContainer}, {@link #imageInspect}, the three-argument {@link #networkInspect(String,
 * String, String)}, {@link #images}, the {@code --config} {@link #pull(String, String, String)} and
 * {@link #push}, {@link #tag}, {@link #load}, {@link #build}, {@link #cpIn}/{@link #cpOut}, {@link
 * #startAttached}, {@link #rmStopped}, {@link #rmiForce} and the rest. Their host paths and
 * one-word values are belted by {@code HostArgvBelts}.
 *
 * <p><b>The reads with a caller's {@code --format}</b> — {@link #info}, {@link #version}, {@link
 * #ps}, {@link #containerInspect}, the {@code (binary, format)} overloads of {@link #systemDf},
 * {@link #imageLs} and {@link #volumeLs} — are qits-system-service's, which reads the host for an
 * operator. Their element order is that service's ({@code DockerArgvTest} copies its expectations),
 * and a {@code null} format states none.
 */
public final class DockerArgv {

  /**
   * The host docker socket, on both sides of the bind. It is a constant rather than a parameter for
   * the reason {@link ContainerSpec#hostDockerSocket()} is a boolean: what gets mounted must not be
   * something a caller chooses.
   */
  public static final String DOCKER_SOCKET = "/var/run/docker.sock";

  /**
   * The container state, as one line: {@code <status>/<health>}. Copied from
   * {@code DockerDeploymentDriver.observe} unchanged, including the {@code if}/{@code else} —
   * a bare {@code {{.State.Health.Status}}} prints Go's {@code <no value>} for an image that
   * declares no healthcheck, and {@code <no value>} would read back as a health state no container
   * has. The {@code else none} arm answers the absence as an absence.
   */
  public static final String STATE_FORMAT =
      "{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}";

  /** When the current run of the container began — what an adoption records as its start. */
  public static final String STARTED_AT_FORMAT = "{{.State.StartedAt}}";

  /**
   * A volume's labels, one {@code k=v} per line. <b>Ranged rather than indexed</b>, the same
   * measured reason the state format carries its {@code if}: on docker 29.5.3 an {@code index} of an
   * empty label map prints {@code <no value>}, which reads back as a label nothing set. A range over
   * an empty map prints nothing, which is what an unlabelled volume means.
   */
  public static final String VOLUME_LABELS_FORMAT =
      "{{range $k, $v := .Labels}}{{$k}}={{$v}}{{\"\\n\"}}{{end}}";

  private DockerArgv() {}

  /**
   * The whole {@code docker run}. Detached, never self-removing, and in a fixed order so the list is
   * assertable.
   *
   * <p>{@code labels} is the embedder's own namespace ({@link LabelNamespace#forContainer}); the
   * spec's {@code extraLabels} are merged in and the union is rendered sorted. The two cannot
   * collide — an owner may not write inside {@code namespace} ({@link
   * LabelNamespace#requireOwnerLabels}) — so the merge needs no precedence rule and has none.
   */
  public static List<String> run(
      String runtimeBinary,
      LabelNamespace namespace,
      String name,
      ContainerSpec spec,
      Map<String, String> labels,
      LifecyclePolicy policy) {
    return run(runtimeBinary, namespace, name, spec, labels, policy, "");
  }

  /**
   * The same run, told which group owns the host's docker socket.
   *
   * <p>{@code socketGroup} is rendered as a {@code --group-add} <b>only beside the socket bind</b>,
   * and it is blank for every workload that did not ask for one. It exists because a bind a workload
   * cannot open is not a privilege, it is a puzzle: the socket is {@code srw-rw----} owned by the
   * host's docker group, so a container running as anybody but root can hold the mount and still be
   * refused by the kernel on {@code connect}. qits-ci never met this — its opted-in steps run as the
   * image's own root — and qits-workspaces' admin workspaces do, because a workspace container runs
   * as the host uid.
   *
   * <p><b>It is not a spec field, deliberately.</b> The value is the socket's own group, read off the
   * socket by the process that holds it, so no caller can name a group and nothing can join one
   * without the bind that makes it mean anything. A spec field would be exactly the assembled
   * privilege {@link ContainerSpec} exists to prevent.
   */
  public static List<String> run(
      String runtimeBinary,
      LabelNamespace namespace,
      String name,
      ContainerSpec spec,
      Map<String, String> labels,
      LifecyclePolicy policy,
      String socketGroup) {
    return render(
        List.of("run", "-d"), runtimeBinary, namespace, name, spec, labels, policy, socketGroup);
  }

  /**
   * {@link #run(String, LabelNamespace, String, ContainerSpec, Map, LifecyclePolicy)} as a {@code
   * docker create}: the same container, every flag in the same order, made and not started — so
   * the caller can {@link #networkConnect} it onto further networks before its first process runs,
   * and then {@link #start} it. qits-system-runner-daemon's lease launch (qits-2289), which spelled
   * this by rewriting the run argv's {@code run -d} into {@code create}; this is that list, element
   * for element. Never self-removing, as every argv here.
   */
  public static List<String> create(
      String runtimeBinary,
      LabelNamespace namespace,
      String name,
      ContainerSpec spec,
      Map<String, String> labels,
      LifecyclePolicy policy) {
    return create(runtimeBinary, namespace, name, spec, labels, policy, "");
  }

  /**
   * {@link #create(String, LabelNamespace, String, ContainerSpec, Map, LifecyclePolicy)}, told
   * which group owns the host's docker socket — exactly as the seven-argument {@link #run}.
   */
  public static List<String> create(
      String runtimeBinary,
      LabelNamespace namespace,
      String name,
      ContainerSpec spec,
      Map<String, String> labels,
      LifecyclePolicy policy,
      String socketGroup) {
    return render(
        List.of("create"), runtimeBinary, namespace, name, spec, labels, policy, socketGroup);
  }

  /**
   * The body of {@link #run} and {@link #create}: one container, two ways of making it. {@code
   * leading} is the words after the binary — {@code run -d} or {@code create} — and is everything
   * the two argvs differ by.
   */
  private static List<String> render(
      List<String> leading,
      String runtimeBinary,
      LabelNamespace namespace,
      String name,
      ContainerSpec spec,
      Map<String, String> labels,
      LifecyclePolicy policy,
      String socketGroup) {
    ContainersIdentifiers.requireContainerName(name);
    ContainersIdentifiers.requireImage(spec.image());
    ContainersIdentifiers.requireNetwork(spec.network());

    List<String> argv = new ArrayList<>();
    argv.add(runtimeBinary);
    argv.addAll(leading);
    // tini as PID 1, beside -d because both say what SHAPE of run this is rather than what it
    // contains. Only when the spec asked: PID 1 in a container inherits every orphan and reaps
    // none of them unless it is written to, so a container hosting a long-lived session that
    // spawns builds fills up with zombies — and a container that runs one process and exits pays
    // for a second process it never needed.
    if (spec.init()) {
      argv.add("--init");
    }
    argv.add("--name");
    argv.add(name);
    argv.add("--network");
    argv.add(spec.network());
    for (String alias : spec.aliases()) {
      argv.add("--network-alias");
      argv.add(ContainersIdentifiers.requireAlias(alias));
    }
    for (String addHost : spec.addHosts()) {
      argv.add("--add-host=" + ContainersIdentifiers.requireAddHost(addHost));
    }
    // One --dns per resolver, in the spec's own order. Unlike --network-alias and --add-host,
    // docker writes these straight into /etc/resolv.conf rather than into anything this service
    // reads back, so there is nothing here to parse later — only the belt on the way in.
    for (String server : spec.dns()) {
      argv.add("--dns");
      argv.add(ContainersIdentifiers.requireDnsServer(server));
    }
    // Who the first process is, rendered only when the spec named somebody — an unset user means
    // the image's own default, and the two must stay different statements. It is decided HERE
    // because it cannot be decided later: a container run with --cap-drop=ALL has no CAP_SETUID and
    // no CAP_SETGID, so `su` inside its script fails whatever the script does. Measured 2026-08-12
    // on qits-ci's own step containers, where the adduser/chown/su pattern could never have worked.
    if (!spec.user().isEmpty()) {
      argv.add("--user");
      argv.add(ContainersIdentifiers.requireUser(spec.user()));
    }
    // Sorted, because the whole argv is asserted literally and a map's iteration order is not a
    // thing to assert against.
    for (Map.Entry<String, String> label : merged(namespace, labels, spec.extraLabels()).entrySet()) {
      argv.add("--label");
      argv.add(label.getKey() + "=" + label.getValue());
    }
    // The sandbox. Each flag is rendered only when the spec asked for it, so "unset" stays a
    // different statement from "off" — a spec that says nothing must not silently acquire a fence,
    // and a spec that dropped one must be readable as having dropped it.
    ContainerSpec.SecurityPosture security = spec.security();
    if (security.noNewPrivileges()) {
      argv.add("--security-opt=no-new-privileges");
    }
    if (security.capDropAll()) {
      argv.add("--cap-drop=ALL");
    }
    if (security.memory() != null) {
      argv.add("--memory");
      argv.add(security.memory());
    }
    if (security.memorySwap() != null) {
      argv.add("--memory-swap");
      argv.add(security.memorySwap());
    }
    if (security.pidsLimit() != null) {
      argv.add("--pids-limit");
      argv.add(String.valueOf(security.pidsLimit()));
    }
    if (security.cpus() != null) {
      argv.add("--cpus");
      argv.add(security.cpus());
    }
    // Not a container limit but a host-survival hint: a spawned workload is more expendable than the
    // platform, so under memory pressure the kernel reaps it first. A reaped workload retries; a
    // crashed host does not. Consumers set the value (ci highest, then agents, then workspaces).
    if (security.oomScoreAdj() != null) {
      argv.add("--oom-score-adj");
      argv.add(String.valueOf(security.oomScoreAdj()));
    }
    // EPHEMERAL renders nothing here — see LifecyclePolicy, where the reason is argued.
    if (policy.restartsUnlessStopped()) {
      argv.add("--restart");
      argv.add("unless-stopped");
    }
    for (ContainerSpec.VolumeMount mount : spec.volumeMounts()) {
      argv.add("-v");
      argv.add(mount.volumeName() + ":" + mount.containerPath());
    }
    for (ContainerSpec.SharedMount mount : spec.sharedMounts()) {
      argv.add("-v");
      argv.add(mount.sharedName() + ":" + mount.containerPath());
    }
    // The one bind, and the only privilege escalation this service can perform. A container holding
    // it is root-equivalent on the host, so it is here because the spec DECLARED it — nothing else
    // in this method can add a mount, and no path anywhere comes from a caller.
    if (spec.hostDockerSocket()) {
      argv.add("-v");
      argv.add(DOCKER_SOCKET + ":" + DOCKER_SOCKET);
      // …and the group that makes the bind usable, when the deployment could read one off the
      // socket. It is rendered here and nowhere else, so a workload that did not ask for the socket
      // joins no group whatever the deployment holds.
      if (socketGroup != null && !socketGroup.isBlank()) {
        argv.add("--group-add");
        argv.add(ContainersIdentifiers.requireGroup(socketGroup.trim()));
      }
    }
    for (Map.Entry<String, String> variable : new TreeMap<>(spec.env()).entrySet()) {
      argv.add("-e");
      argv.add(ContainersIdentifiers.requireEnvKey(variable.getKey()) + "=" + variable.getValue());
    }
    // Docker's --entrypoint takes one word; a longer list spends its tail as leading arguments after
    // the image, which is the CLI's own convention (`--entrypoint /bin/sh img -c '…'`).
    List<String> entrypoint = spec.entrypoint();
    if (!entrypoint.isEmpty()) {
      argv.add("--entrypoint");
      argv.add(entrypoint.getFirst());
    }
    argv.add(spec.image());
    if (entrypoint.size() > 1) {
      argv.addAll(entrypoint.subList(1, entrypoint.size()));
    }
    argv.addAll(spec.args());
    return List.copyOf(argv);
  }

  /**
   * How the platform's own buildkitd writes its configuration and comes up. The file arrives as an
   * environment value and becomes {@code /etc/buildkit/buildkitd.toml} inside the container —
   * qits-ci's {@code BOOTSTRAP} shape, and for the same reason: this service shares no volume with
   * the container and the wire between them is an argv, so a small file can only be a value the
   * container writes for itself. Zero interpolation: the value is a variable the shell reads, never
   * a word in this string.
   *
   * <p>Two listen addresses, each with its own reader. {@code tcp://0.0.0.0:1234} binds the
   * network the container is on — the platform network, where every step container dials the
   * {@code qits-buildkitd} alias; 1234 is buildkitd's own conventional port, spelled once here and
   * once in the injected address default. {@code unix:///run/buildkit/buildkitd.sock} is
   * buildkitd's DEFAULT socket, and it is what the build-cache sweep's {@code docker exec buildctl
   * prune}/{@code du} finds — an exec'd buildctl knows no tcp address, and without the socket the
   * sweep answered {@code dial unix …: no such file or directory} on the live platform (measured
   * 2026-09-05, the first gc dry-run against the owned builder).
   */
  public static final String BUILDKITD_BOOTSTRAP =
      """
      set -e
      mkdir -p /etc/buildkit
      printf '%s' "$BUILDKITD_TOML" > /etc/buildkit/buildkitd.toml
      exec buildkitd --addr unix:///run/buildkit/buildkitd.sock --addr tcp://0.0.0.0:1234
      """;

  /** Where buildkitd keeps its content store — what the state volume is mounted over. */
  public static final String BUILDKITD_STATE_PATH = "/var/lib/buildkit";

  /**
   * The label the platform builder's whole configuration is stamped into, as a hash — {@code
   * <namespace>buildkit.config}, so it follows the embedder's {@link LabelNamespace}. The boot pass
   * compares it to decide whether the running container IS the configured one — the image pin, the
   * rendered toml, the bounds and the network all live in the stamp, so changing any of them
   * replaces the container (the state volume rides across). A builder with no stamp — the
   * bootstrap's host-net one, or anything hand-made — reads as "not the configured one" and is
   * replaced too, which is exactly the cutover the bootstrap hands over with.
   */
  public static String buildkitdStampLabel(LabelNamespace namespace) {
    return namespace.key("buildkit.config");
  }

  /**
   * The one privileged {@code docker run} this service makes, and the reason it is its own argv
   * rather than a {@link ContainerSpec}: {@code --privileged} must never be something a spec can
   * express — a caller-assembled privilege is exactly what {@link ContainerSpec}'s shape exists to
   * prevent — and buildkitd cannot mount overlayfs for its build sandboxes without it. The whole
   * argv is this method's, with nothing caller-shaped in it: the name is the
   * {@link ContainersIdentifiers#PLATFORM_BUILDER} constant, the image and the toml come from this
   * service's own configuration, and every bound is rendered unconditionally.
   *
   * <p>{@code --restart unless-stopped} because the builder outlives this service and a dockerd
   * restart — the {@code EXPLICIT} lifecycle's rendering, without the row (the builder is platform
   * infrastructure in {@code SharedResources}' sense: ensured at boot, claimed by nobody).
   * {@code --oom-score-adj} is the same number a step container's carries, not a lower one, because
   * a build process inherits it: this score is what every native-image compile on the host runs
   * under, so a smaller value would make image builds the last thing the kernel takes rather than
   * the first. Under memory pressure a killed build is a red step that re-runs; a killed platform
   * service, or a node the kernel had no candidate left on, is not.
   *
   * <p><b>The four bounds are {@code --cpus}, {@code --memory}, {@code --memory-swap} and
   * {@code --pids-limit}</b>, and the cpu/memory pair is not a duplicate of the step container's: a
   * service's native compile runs in its Dockerfile's build stage, which under this builder
   * executes <em>here</em>, so the step's own {@code --cpus}/{@code --memory} bound nothing about
   * it and an unbounded builder was an unbounded machine. {@code --memory-swap} is rendered equal
   * to {@code --memory}, which is docker's spelling of "no swap": a build that reaches the cap is
   * killed promptly instead of thrashing the host it shares. Both values are configuration's, where
   * the floor the heaviest native-image heap sets is written down; {@code cpus} is a String because
   * docker takes a decimal there.
   */
  public static List<String> runBuildkitd(
      String runtimeBinary,
      LabelNamespace namespace,
      String image,
      String network,
      String stateVolume,
      String toml,
      String configStamp,
      String cpus,
      String memory,
      long pidsLimit,
      int oomScoreAdj) {
    ContainersIdentifiers.requireImage(image);
    ContainersIdentifiers.requireNetwork(network);
    ContainersIdentifiers.requireVolumeName(stateVolume);
    return List.of(
        runtimeBinary,
        "run",
        "-d",
        "--name",
        ContainersIdentifiers.PLATFORM_BUILDER,
        "--network",
        network,
        "--network-alias",
        ContainersIdentifiers.PLATFORM_BUILDER,
        "--label",
        buildkitdStampLabel(namespace) + "=" + (configStamp == null ? "" : configStamp),
        "--privileged",
        "--restart",
        "unless-stopped",
        "--cpus",
        cpus,
        "--memory",
        memory,
        // Equal to --memory: docker reads that as "no swap", which is the point — see the javadoc.
        "--memory-swap",
        memory,
        "--pids-limit",
        String.valueOf(pidsLimit),
        "--oom-score-adj",
        String.valueOf(oomScoreAdj),
        "-v",
        stateVolume + ":" + BUILDKITD_STATE_PATH,
        "-e",
        "BUILDKITD_TOML=" + (toml == null ? "" : toml),
        "--entrypoint",
        "/bin/sh",
        image,
        "-c",
        BUILDKITD_BOOTSTRAP);
  }

  /**
   * The configuration stamp a container was started with — {@link #buildkitdStampLabel}. The
   * boot pass asks "is the running builder the CONFIGURED builder", and the stamp is the whole
   * answer: image, toml, the bounds (cpus, memory, pids, oom score) and network in one hash, so any
   * moved value replaces the container rather than adopting one that no longer matches what a
   * deployment says.
   */
  public static List<String> inspectBuildkitdStamp(
      String runtimeBinary, LabelNamespace namespace, String name) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        "{{index .Config.Labels \"" + buildkitdStampLabel(namespace) + "\"}}",
        ContainersIdentifiers.requireContainerName(name));
  }

  /** One observation of the container's state — see {@link #STATE_FORMAT}. */
  public static List<String> inspectState(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        STATE_FORMAT,
        ContainersIdentifiers.requireContainerName(name));
  }

  /** When the container's current run started. */
  public static List<String> inspectStartedAt(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        STARTED_AT_FORMAT,
        ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * The whole of one observation, in one line: {@code <id>|<status>/<health>|<startedAt>}.
   *
   * <p>It is one call rather than three because the observation pass costs one {@code docker
   * inspect} <b>per row, per pass, forever</b>, and {@code ContainersDriver.Observed} carries all
   * three fields.
   *
   * <p><b>It is not {@code "{{.Id}}|" + STATE_FORMAT + "|" + STARTED_AT_FORMAT}, and the reason is
   * measured rather than stylistic.</b> On docker 29.7.2 that composition fails on any container
   * without a healthcheck, with {@code map has no entry for key "Health"} — while
   * {@link #STATE_FORMAT} on its own answers {@code running/none} perfectly. The difference is
   * {@code .Id}: the CLI renders a template against the typed inspect object first and falls back
   * to the <b>raw JSON map</b> when that fails, and the Go field is named {@code ID} while the JSON
   * key is {@code Id}. So asking for the id at all moves the whole template onto the map path,
   * where {@code {{if .State.Health}}} is an error rather than a false — a missing map key is not a
   * zero value in Go's templates, which is the same lesson the volume label format learned from
   * {@code index}.
   *
   * <p>{@code index} is what a map path has instead: {@code index .State "Health"} answers the zero
   * value for a container that declares no check, so the {@code else} arm renders and the health
   * reads {@code none}. Measured both ways on docker 29.7.2 — {@code running/none} for a container
   * without a check, {@code running/healthy} for one with.
   *
   * <p>The two single-field spellings beside it stay: they are the typed-path forms, they are what
   * a caller that needs one field should use, and this one is deliberately not built out of them.
   */
  public static final String OBSERVATION_FORMAT =
      "{{.Id}}|{{.State.Status}}/"
          + "{{if index .State \"Health\"}}{{(index .State \"Health\").Status}}{{else}}none{{end}}"
          + "|{{.State.StartedAt}}";

  /** One inspect answering everything an observation records — see {@link #OBSERVATION_FORMAT}. */
  public static List<String> inspectObservation(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        OBSERVATION_FORMAT,
        ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * {@code container inspect [--format <template>] <reference>} — any container on the host, by a
   * {@link ContainersIdentifiers#requireReference reference} somebody typed rather than a name this
   * library gave it. With no format it prints the daemon's own document, a JSON array.
   *
   * <p>{@code container inspect}, not the bare {@code inspect} the methods above (and {@link
   * SwarmArgv#containerInspect}) spell: the bare one answers for an image or a volume of the same
   * name too, and a reference a person typed may well be one.
   */
  public static List<String> containerInspect(
      String runtimeBinary, String reference, String format) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "container", "inspect"));
    if (format != null) {
      argv.add("--format");
      argv.add(format);
    }
    argv.add(ContainersIdentifiers.requireReference(reference));
    return List.copyOf(argv);
  }

  /**
   * {@code inspect --type container -f <template> <reference>} — qits-bootstrap-cli's spelling
   * (qits-2212): the bare {@code inspect}, narrowed to containers by {@code --type} rather than by
   * the {@code container} subcommand {@link #containerInspect} uses, and the short {@code -f}.
   */
  public static List<String> inspectTypeContainer(
      String runtimeBinary, String reference, String format) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--type",
        "container",
        "-f",
        Objects.requireNonNull(format, "format"),
        ContainersIdentifiers.requireReference(reference));
  }

  /**
   * {@code inspect --type container --format <template> <reference>} — {@link
   * #inspectTypeContainer(String, String, String)} with the long {@code --format}:
   * qits-system-runner-daemon's spelling (qits-2289), for its build-plane builder and its lease
   * containers, read by a name it derived or an id docker listed.
   */
  public static List<String> inspectTypeContainerFormat(
      String runtimeBinary, String reference, String format) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--type",
        "container",
        "--format",
        Objects.requireNonNull(format, "format"),
        ContainersIdentifiers.requireReference(reference));
  }

  /**
   * {@code inspect --type container <reference>...} — no template, so the daemon's own document: a
   * JSON array with one object per container, in one call. qits-system-runner-daemon's (qits-2289)
   * DOCKER relay, which inspects one lease container or every container its owner listing named.
   * Each is a {@link ContainersIdentifiers#requireReference reference}; an empty list is refused,
   * because an inspect of nothing is docker's usage error, not an empty array.
   */
  public static List<String> inspectTypeContainer(String runtimeBinary, List<String> references) {
    if (references == null || references.isEmpty()) {
      throw new IllegalArgumentException("Invalid inspect: no container named");
    }
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "inspect", "--type", "container"));
    for (String reference : references) {
      argv.add(ContainersIdentifiers.requireReference(reference));
    }
    return List.copyOf(argv);
  }

  /**
   * {@code inspect -f <template> <reference>} — the bare inspect with the short {@code -f},
   * qits-bootstrap-cli's (qits-2212). It answers for whatever object carries the reference; the
   * template decides what is asked.
   */
  public static List<String> inspect(String runtimeBinary, String reference, String format) {
    return List.of(
        runtimeBinary,
        "inspect",
        "-f",
        Objects.requireNonNull(format, "format"),
        ContainersIdentifiers.requireReference(reference));
  }

  /**
   * {@code inspect <reference>} — the bare inspect with no format at all, so the answer is the
   * daemon's own document rather than a template's narrowing of it. qits-runner-javalib's runner
   * toolkit (qits-2302), {@code LifecycleArgv.inspectSelf}: the runner's whole container document,
   * read this way because a Go template refuses to render a key the container's shape omits (
   * {@code HostConfig.Mounts} is absent on a container started with {@code -v} binds only), where
   * the reader parses the document defensively instead.
   */
  public static List<String> inspect(String runtimeBinary, String reference) {
    return List.of(
        runtimeBinary, "inspect", ContainersIdentifiers.requireReference(reference));
  }

  /**
   * Start the container that already carries this name. It takes no spec and no flag: everything a
   * container is was decided by the {@link #run} that created it, and a start that could re-state
   * any of it would be a second place for the sandbox to be written.
   */
  public static List<String> start(String runtimeBinary, String name) {
    return List.of(runtimeBinary, "start", ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * {@link #start}, of a container by a {@link ContainersIdentifiers#requireReference reference} —
   * one an embedder read off a {@code ps}, whose name need not be a DNS label
   * ({@code qits-system-runner-7c1d2f0e-2026.1116.41632}).
   */
  public static List<String> startByReference(String runtimeBinary, String reference) {
    return List.of(runtimeBinary, "start", ContainersIdentifiers.requireReference(reference));
  }

  /**
   * {@code start -a <reference>} — start a {@code create}d container and stay attached to its
   * output until it exits; the exit code is the container's. qits-bootstrap-cli's seed builds
   * (qits-2212), run through {@link ContainerProcess#stream} so every line arrives as it is printed.
   */
  public static List<String> startAttached(String runtimeBinary, String reference) {
    return List.of(runtimeBinary, "start", "-a", ContainersIdentifiers.requireReference(reference));
  }

  /** Stop it, leaving it restartable. */
  public static List<String> stop(String runtimeBinary, String name) {
    return List.of(runtimeBinary, "stop", ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * {@code stop -t <seconds> <reference>} — {@link #stop}, of a container by a {@link
   * ContainersIdentifiers#requireReference reference}, with docker's grace period before its
   * {@code SIGKILL} stated rather than defaulted. qits-system-runner-daemon's DOCKER relay
   * (qits-2289), whose caller's deadline is the grace period plus its own. A negative grace is
   * refused; zero is docker's "kill at once".
   */
  public static List<String> stopByReference(String runtimeBinary, String reference, int seconds) {
    if (seconds < 0) {
      throw new IllegalArgumentException("Invalid stop timeout: " + seconds + " seconds");
    }
    return List.of(
        runtimeBinary,
        "stop",
        "-t",
        String.valueOf(seconds),
        ContainersIdentifiers.requireReference(reference));
  }

  /**
   * {@code update --restart=no <reference>} — take the restart policy off a container, so the
   * exit it is about to make is its last. qits-runner-javalib's runner toolkit (qits-2302), {@code
   * LifecycleArgv.noRestart}: the retiring runner's own step out of the way of its successor,
   * before it removes itself.
   */
  public static List<String> updateRestartNo(String runtimeBinary, String reference) {
    return List.of(
        runtimeBinary, "update", "--restart=no", ContainersIdentifiers.requireReference(reference));
  }

  /** Remove it, running or not. Every teardown ends here, and never at a {@code --rm}. */
  public static List<String> rm(String runtimeBinary, String name) {
    return List.of(runtimeBinary, "rm", "-f", ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * {@code rm -f <reference>...}, in the order given and in one call — a sweep's ids, read off a
   * {@code ps -aq}, or the names of an embedder's own terminal containers. Each is a {@link
   * ContainersIdentifiers#requireReference reference}, so a short id passes; an empty list is
   * refused, because {@code rm -f} with nothing after it is docker's usage error, not a no-op.
   */
  public static List<String> rm(String runtimeBinary, List<String> references) {
    if (references == null || references.isEmpty()) {
      throw new IllegalArgumentException("Invalid rm: no container named");
    }
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "rm", "-f"));
    for (String reference : references) {
      argv.add(ContainersIdentifiers.requireReference(reference));
    }
    return List.copyOf(argv);
  }

  /**
   * {@code rm <reference>}, <b>without</b> {@code -f}: a container that has exited — a {@code
   * create}d one after its {@link #startAttached} returned. Docker refuses a running one, which is
   * the point of leaving the force out.
   */
  public static List<String> rmStopped(String runtimeBinary, String reference) {
    return List.of(runtimeBinary, "rm", ContainersIdentifiers.requireReference(reference));
  }

  /** A bounded tail of the container's own output — the diagnosis, captured before any removal. */
  public static List<String> logsTail(String runtimeBinary, String name, int lines) {
    if (lines <= 0) {
      throw new IllegalArgumentException("Invalid log tail: " + lines + " lines");
    }
    return List.of(
        runtimeBinary,
        "logs",
        "--tail",
        String.valueOf(lines),
        ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * {@link #logsTail}, of any container on the host by a {@link
   * ContainersIdentifiers#requireReference reference} rather than a name this library gave it. The
   * count is two elements, never {@code --tail=N}, and bounding it is the caller's.
   */
  public static List<String> logsTailByReference(String runtimeBinary, String reference, int lines) {
    if (lines <= 0) {
      throw new IllegalArgumentException("Invalid log tail: " + lines + " lines");
    }
    return List.of(
        runtimeBinary,
        "logs",
        "--tail",
        String.valueOf(lines),
        ContainersIdentifiers.requireReference(reference));
  }

  /**
   * {@code logs --tail <lines> [--since <since>] <reference>} — {@link #logsTailByReference}, from
   * a moment on when {@code since} is given; a null {@code since} renders exactly that method's
   * list. qits-system-runner-daemon's DOCKER relay (qits-2289). {@code since} is the caller's
   * verbatim value — a relative duration ({@code 10m}) or an RFC 3339 instant — which docker
   * parses; belted as non-empty, free of control characters and not opening with a {@code -}, so
   * it stays the flag's value and one element.
   */
  public static List<String> logsTailSince(
      String runtimeBinary, String reference, int lines, String since) {
    if (lines <= 0) {
      throw new IllegalArgumentException("Invalid log tail: " + lines + " lines");
    }
    List<String> argv =
        new ArrayList<>(List.of(runtimeBinary, "logs", "--tail", String.valueOf(lines)));
    if (since != null) {
      argv.add("--since");
      argv.add(HostArgvBelts.requireValue("since", since));
    }
    argv.add(ContainersIdentifiers.requireReference(reference));
    return List.copyOf(argv);
  }

  /**
   * {@code logs --timestamps --since <since> <reference>} — what a container printed from a moment
   * on, each line stamped. {@code since} is docker's own: an RFC 3339 time, a unix timestamp or a
   * relative duration ({@code 10m}); it is belted as one word that cannot open with a {@code -}.
   */
  public static List<String> logsSince(String runtimeBinary, String reference, String since) {
    return List.of(
        runtimeBinary,
        "logs",
        "--timestamps",
        "--since",
        HostArgvBelts.requireWord("since", since),
        ContainersIdentifiers.requireReference(reference));
  }

  /**
   * Block until the container exits and print its exit code — {@code docker wait}. The caller's
   * timeout is the only bound; a container that never exits is the caller's to remove.
   */
  public static List<String> wait(String runtimeBinary, String name) {
    return List.of(runtimeBinary, "wait", ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * Container ids matching every one of the label filters.
   *
   * <p>This narrows a listing; it never decides a removal. Which containers may be touched is a
   * question the registry rows answer, and a host-wide label sweep is the regression this repository
   * exists to remove.
   */
  public static List<String> psByLabels(String runtimeBinary, Map<String, String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "ps", "-aq"));
    for (Map.Entry<String, String> filter : new TreeMap<>(filters).entrySet()) {
      argv.add("--filter");
      argv.add("label=" + filter.getKey() + "=" + filter.getValue());
    }
    return List.copyOf(argv);
  }

  /**
   * {@code ps -aq (--filter F)*} — {@link #psByLabels}' spelling with whole filters, in the order
   * given: a presence-only {@code label=<key>} ({@link SwarmArgv#labelFilter(String)}) is one, which
   * a map of label values cannot say. qits-system-runner-daemon's lease listing (qits-2289). Each
   * filter is belted free of control characters; none at all lists every container.
   */
  public static List<String> psAllQuiet(String runtimeBinary, List<String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "ps", "-aq"));
    filters(argv, filters);
    return List.copyOf(argv);
  }

  /**
   * {@code ps -a -q [--no-trunc] (--filter F)*} — {@link #psAllQuiet} with the two flags spelled
   * apart, and {@code --no-trunc} when asked so an id is the whole id. qits-system-runner-daemon's
   * (qits-2289): its DOCKER relay's owned-container ids ({@code noTrunc}), and its image pass's
   * {@link #psAncestor}. Filters as {@link #psAllQuiet}.
   */
  public static List<String> psAllQuietSeparate(
      String runtimeBinary, boolean noTrunc, List<String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "ps", "-a", "-q"));
    if (noTrunc) {
      argv.add("--no-trunc");
    }
    filters(argv, filters);
    return List.copyOf(argv);
  }

  /**
   * {@code ps -a -q --filter ancestor=<image>} — the containers, running or not, created from an
   * image: what a runner asks before it removes one (qits-runner-javalib's {@code
   * LifecycleArgv.psAncestor}, element for element; qits-system-runner-daemon, qits-2289). The
   * image is a belted reference.
   */
  public static List<String> psAncestor(String runtimeBinary, String image) {
    return psAllQuietSeparate(
        runtimeBinary, false, List.of("ancestor=" + ContainersIdentifiers.requireImage(image)));
  }

  /**
   * {@code ps [-a] [--no-trunc] [--format <template>]} — the host's containers, for a person to
   * read. {@code all} adds the stopped ones; {@code noTrunc} keeps an id the whole id.
   */
  public static List<String> ps(
      String runtimeBinary, boolean all, boolean noTrunc, String format) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "ps"));
    if (all) {
      argv.add("-a");
    }
    if (noTrunc) {
      argv.add("--no-trunc");
    }
    if (format != null) {
      argv.add("--format");
      argv.add(format);
    }
    return List.copyOf(argv);
  }

  /**
   * {@code ps [-a] (--filter F)* [--format <template>]} — the containers a filter picks, in the
   * caller's template. qits-bootstrap-cli's (qits-2212): a filter is spelled whole by the caller
   * ({@link SwarmArgv#labelFilter} spells a label one).
   */
  public static List<String> ps(
      String runtimeBinary, boolean all, List<String> filters, String format) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "ps"));
    if (all) {
      argv.add("-a");
    }
    if (filters != null) {
      for (String filter : filters) {
        argv.add("--filter");
        argv.add(HostArgvBelts.requireNoControl("filter", filter));
      }
    }
    if (format != null) {
      argv.add("--format");
      argv.add(format);
    }
    return List.copyOf(argv);
  }

  /**
   * {@code ps [-a] --format <template> (--filter F)*} — {@link #ps(String, boolean, List, String)}
   * with the template FIRST and required: qits-system-runner-daemon's spelling (qits-2289), which
   * asks which containers are on its build network. Filters as there.
   */
  public static List<String> psFormatFirst(
      String runtimeBinary, boolean all, String format, List<String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "ps"));
    if (all) {
      argv.add("-a");
    }
    argv.add("--format");
    argv.add(Objects.requireNonNull(format, "format"));
    filters(argv, filters);
    return List.copyOf(argv);
  }

  /** {@code (--filter F)*}, each whole filter belted free of control characters; null is none. */
  private static void filters(List<String> argv, List<String> filters) {
    if (filters == null) {
      return;
    }
    for (String filter : filters) {
      argv.add("--filter");
      argv.add(HostArgvBelts.requireNoControl("filter", filter));
    }
  }

  /** Fetch the image, so "the registry has no such image" is its own outcome rather than a run. */
  public static List<String> pull(String runtimeBinary, String imageRef) {
    return List.of(runtimeBinary, "pull", ContainersIdentifiers.requireImage(imageRef));
  }

  // --- images built, moved and copied (qits-bootstrap-cli, qits-2212) ---------------------------

  /**
   * {@code [--config <dir>] pull <image>} — the pull with the docker CLI pointed at a config
   * directory of the caller's, where the registry credential is a file and never an argument. The
   * {@code --config} is docker's GLOBAL option, so it comes before the subcommand; a null directory
   * states none and renders exactly {@link #pull(String, String)}.
   */
  public static List<String> pull(String runtimeBinary, String configDir, String imageRef) {
    return withConfig(runtimeBinary, configDir, "pull", imageRef);
  }

  /** {@code [--config <dir>] push <image>} — the push, as {@link #pull(String, String, String)}. */
  public static List<String> push(String runtimeBinary, String configDir, String imageRef) {
    return withConfig(runtimeBinary, configDir, "push", imageRef);
  }

  /** {@code tag <source> <target>} — a second local name for an image. Both are belted refs. */
  public static List<String> tag(String runtimeBinary, String source, String target) {
    return List.of(
        runtimeBinary,
        "tag",
        ContainersIdentifiers.requireImage(source),
        ContainersIdentifiers.requireImage(target));
  }

  /** {@code load -i <archive>} — an image tarball on the host into the daemon. */
  public static List<String> load(String runtimeBinary, String archive) {
    return List.of(
        runtimeBinary, "load", "-i", HostArgvBelts.requireHostPath("image archive", archive));
  }

  /**
   * {@code build -f <dockerfile> -t <tag> <context>} — a build on the host daemon's own builder.
   * The paths are the docker CLI's to read, so they are this process's paths.
   */
  public static List<String> build(
      String runtimeBinary, String dockerfile, String tag, String context) {
    return List.of(
        runtimeBinary,
        "build",
        "-f",
        HostArgvBelts.requireHostPath("dockerfile", dockerfile),
        "-t",
        ContainersIdentifiers.requireImage(tag),
        HostArgvBelts.requireHostPath("build context", context));
  }

  /**
   * {@code cp <host path> <container>:<path>} — into a container, typically a {@code create}d one
   * before its {@link #startAttached}. The host path is read by the docker CLI, so it is this
   * process's; the container path is absolute and carries no {@code :}.
   */
  public static List<String> cpIn(
      String runtimeBinary, String hostPath, String container, String containerPath) {
    return List.of(
        runtimeBinary,
        "cp",
        HostArgvBelts.requireHostPath("host path", hostPath),
        ContainersIdentifiers.requireReference(container)
            + ":"
            + ContainersIdentifiers.requireContainerPath(containerPath));
  }

  /** {@code cp <container>:<path> <host path>} — out of a container; {@link #cpIn} reversed. */
  public static List<String> cpOut(
      String runtimeBinary, String container, String containerPath, String hostPath) {
    return List.of(
        runtimeBinary,
        "cp",
        ContainersIdentifiers.requireReference(container)
            + ":"
            + ContainersIdentifiers.requireContainerPath(containerPath),
        HostArgvBelts.requireHostPath("host path", hostPath));
  }

  private static List<String> withConfig(
      String runtimeBinary, String configDir, String verb, String imageRef) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary));
    if (configDir != null) {
      argv.add("--config");
      argv.add(HostArgvBelts.requireHostPath("docker config directory", configDir));
    }
    argv.add(verb);
    argv.add(ContainersIdentifiers.requireImage(imageRef));
    return List.copyOf(argv);
  }

  /** Create the named volume, labelled. Creating one that exists is docker's own no-op. */
  public static List<String> volumeCreate(
      String runtimeBinary, LabelNamespace namespace, VolumeSpec spec, Map<String, String> labels) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "volume", "create"));
    for (Map.Entry<String, String> label : merged(namespace, labels, spec.extraLabels()).entrySet()) {
      argv.add("--label");
      argv.add(label.getKey() + "=" + label.getValue());
    }
    argv.add(spec.name());
    return List.copyOf(argv);
  }

  /**
   * {@code volume create <name>}, unlabelled — a volume an embedder names and owns by the name
   * alone, with no {@link LabelNamespace} in it (qits-bootstrap-cli, qits-2212). Creating one that
   * exists is docker's own no-op.
   */
  public static List<String> volumeCreate(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary, "volume", "create", ContainersIdentifiers.requireVolumeName(name));
  }

  /** Remove the named volume. Only ever an explicit ask — nothing sweeps a volume. */
  public static List<String> volumeRm(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary, "volume", "rm", ContainersIdentifiers.requireVolumeName(name));
  }

  /** Volume names matching every one of the label filters. */
  public static List<String> volumeLs(String runtimeBinary, Map<String, String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "volume", "ls", "-q"));
    for (Map.Entry<String, String> filter : new TreeMap<>(filters).entrySet()) {
      argv.add("--filter");
      argv.add("label=" + filter.getKey() + "=" + filter.getValue());
    }
    return List.copyOf(argv);
  }

  /** {@code volume ls [--format <template>]} — every volume on the host, for a person to read. */
  public static List<String> volumeLs(String runtimeBinary, String format) {
    return withFormat(List.of(runtimeBinary, "volume", "ls"), format);
  }

  /**
   * {@code volume ls (--filter F)* [--format <template>]} — the volumes a filter picks, in the
   * caller's template; filters whole and first, as qits-system-runner-daemon's DOCKER relay
   * (qits-2289) lists its owner-labelled volumes. Filters as {@link #psAllQuiet}; a null format
   * states none.
   */
  public static List<String> volumeLs(String runtimeBinary, List<String> filters, String format) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "volume", "ls"));
    filters(argv, filters);
    return withFormat(argv, format);
  }

  /**
   * Whether one named volume exists: {@code volume ls -q --filter name=^<name>$}.
   *
   * <p>A list rather than {@code volume inspect}, because the list keeps the exit code for the
   * daemon's own trouble: exit zero is an answer, and the name on a line of its own is "present".
   * docker's {@code name} filter is a substring/regex match, so the caller still checks for the exact
   * line — the anchors only keep the output short.
   */
  public static List<String> volumeLsByName(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "volume",
        "ls",
        "-q",
        "--filter",
        "name=^" + ContainersIdentifiers.requireVolumeName(name) + "$");
  }

  /** A volume's labels, one per line — see {@link #VOLUME_LABELS_FORMAT}. */
  public static List<String> volumeInspectLabels(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "volume",
        "inspect",
        "--format",
        VOLUME_LABELS_FORMAT,
        ContainersIdentifiers.requireVolumeName(name));
  }

  /** {@code volume inspect <name>} — the whole document; exit zero is "it exists". */
  public static List<String> volumeInspect(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary, "volume", "inspect", ContainersIdentifiers.requireVolumeName(name));
  }

  /**
   * {@code volume inspect --format <template> <name>} — one volume, in the caller's template; exit
   * zero is still "it exists". qits-system-runner-daemon's (qits-2289), which asks for {@code
   * {{.Name}}} and reads the answer as the name docker knows.
   */
  public static List<String> volumeInspect(String runtimeBinary, String name, String format) {
    return List.of(
        runtimeBinary,
        "volume",
        "inspect",
        "--format",
        Objects.requireNonNull(format, "format"),
        ContainersIdentifiers.requireVolumeName(name));
  }

  /**
   * {@code network create --driver bridge (--label K=V)* (--opt K=V)* <network>} — a node-local
   * bridge an embedder makes for its own containers and owns by its label: qits-system-runner-
   * daemon's build and lease networks (qits-2289). Labels then options, each in the map's own
   * order (a {@link SequencedMap}, so the order is the caller's and assertable); null is none.
   *
   * <p><b>Not something a {@link ContainerSpec} ever asks for</b>, and nothing in {@code
   * ContainersDriver} calls it — see {@link #networkInspect(String, String)} for why the driver
   * never invents a network. The embedder that calls this decided the network is its own.
   *
   * <p>Belted: the name ({@link ContainersIdentifiers#requireNetwork}), every label key ({@link
   * ContainersIdentifiers#requireExtraLabelKey}) and option key (the same charset), and every value
   * free of control characters.
   */
  public static List<String> bridgeNetworkCreate(
      String runtimeBinary,
      SequencedMap<String, String> labels,
      SequencedMap<String, String> options,
      String network) {
    List<String> argv =
        new ArrayList<>(List.of(runtimeBinary, "network", "create", "--driver", "bridge"));
    if (labels != null) {
      for (Map.Entry<String, String> label : labels.entrySet()) {
        argv.add("--label");
        argv.add(
            ContainersIdentifiers.requireExtraLabelKey(label.getKey())
                + "="
                + HostArgvBelts.requireNoControl("label value", valueOrEmpty(label.getValue())));
      }
    }
    if (options != null) {
      for (Map.Entry<String, String> option : options.entrySet()) {
        argv.add("--opt");
        argv.add(
            HostArgvBelts.requireOptionKey("network option", option.getKey())
                + "="
                + HostArgvBelts.requireNoControl(
                    "network option value", valueOrEmpty(option.getValue())));
      }
    }
    argv.add(ContainersIdentifiers.requireNetwork(network));
    return List.copyOf(argv);
  }

  private static String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }

  /**
   * Ask whether the network exists. <b>There is no {@code network create} for a {@link
   * ContainerSpec}.</b> Creating a bridge is refused on a swarm-initialized host, and a network this
   * service invented would be one no other module's containers are on — so a missing network is
   * something a deployment answers, not something an orchestrator papers over. ({@link
   * #bridgeNetworkCreate} is an embedder's own network, made by its own decision.)
   */
  public static List<String> networkInspect(String runtimeBinary, String network) {
    return List.of(
        runtimeBinary, "network", "inspect", ContainersIdentifiers.requireNetwork(network));
  }

  /**
   * {@code network inspect -f <template> <network>} — the short {@code -f}, qits-bootstrap-cli's
   * (qits-2212); {@link SwarmArgv#networkInspect(String, String, String)} spells {@code --format}
   * and leaves the name unbelted. This one belts it.
   */
  public static List<String> networkInspect(String runtimeBinary, String network, String format) {
    return List.of(
        runtimeBinary,
        "network",
        "inspect",
        "-f",
        Objects.requireNonNull(format, "format"),
        ContainersIdentifiers.requireNetwork(network));
  }

  /**
   * Join an already-running container onto one further network — {@code docker network connect}.
   *
   * <p>Run once per {@link ContainerSpec#extraNetworks} entry, right after the {@link #run} that
   * created the container: {@code network connect} takes a container that already exists, which a
   * {@code docker run} argv itself cannot do for more than the one network {@code --network}
   * names. Docker records the membership against the container rather than against the run, so a
   * later {@code docker start} needs no matching reconnect — the one thing that would undo this is
   * a {@code network disconnect} nothing in this service ever issues.
   */
  public static List<String> networkConnect(String runtimeBinary, String network, String name) {
    return List.of(
        runtimeBinary,
        "network",
        "connect",
        ContainersIdentifiers.requireNetwork(network),
        ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * {@code network disconnect <network> <container>} — the one {@code network disconnect}, and it is
   * an embedder's own (qits-bootstrap-cli's unwrap, qits-2212): taking its own containers off a
   * network it is about to remove. Nothing a {@link ContainerSpec} describes is ever disconnected.
   */
  public static List<String> networkDisconnect(
      String runtimeBinary, String network, String container) {
    return List.of(
        runtimeBinary,
        "network",
        "disconnect",
        ContainersIdentifiers.requireNetwork(network),
        ContainersIdentifiers.requireReference(container));
  }

  /**
   * {@code network rm <network>}, belted — {@link SwarmArgv#networkRm} renders the name as given,
   * this one refuses a name that is not docker's network charset (qits-bootstrap-cli's unwrap,
   * qits-2212, removes names read off {@code network ls}).
   */
  public static List<String> networkRm(String runtimeBinary, String network) {
    return List.of(runtimeBinary, "network", "rm", ContainersIdentifiers.requireNetwork(network));
  }

  // --- garbage collection -------------------------------------------------------------------
  //
  // The host's own stores, which are NOT rows. Everything above this line is addressed to a
  // container a registry row names; the argvs below read and remove images, dangling volumes and
  // build cache, none of which any row has ever named. What keeps them inside this repository's
  // first invariant is that the DECIDING is the caller's and is made of keep rules — see control's
  // ImageGc and VolumeGc — and that nothing here is a `prune -a`, a `rm -f` or a label sweep.

  /**
   * One line per store of {@code docker system df}:
   * {@code <type>|<count>|<active>|<size>|<reclaimable>}.
   *
   * <p><b>Not {@code {{json .}}}</b>, for the reason every other read here is a template: this
   * module binds no docker output to a record, so a JSON shape would be a second serialization
   * contract to register for reflection and to keep in step with a CLI nobody here versions.
   * Measured on docker 29.7.2 — the four types are {@code Images}, {@code Containers},
   * {@code Local Volumes} and {@code Build Cache}, and the sizes are human ({@code 308.3GB}), which
   * is what the reader parses.
   */
  public static final String DISK_USAGE_FORMAT =
      "{{.Type}}|{{.TotalCount}}|{{.Active}}|{{.Size}}|{{.Reclaimable}}";

  /** What the host's four stores hold — the before and after of a collection run. */
  public static List<String> systemDf(String runtimeBinary) {
    return List.of(runtimeBinary, "system", "df", "--format", DISK_USAGE_FORMAT);
  }

  /** {@code system df [--format <template>]} — the same four stores, in the caller's template. */
  public static List<String> systemDf(String runtimeBinary, String format) {
    return withFormat(List.of(runtimeBinary, "system", "df"), format);
  }

  /** {@code info [--format <template>]} — the daemon and its machine, swarm block included. */
  public static List<String> info(String runtimeBinary, String format) {
    return withFormat(List.of(runtimeBinary, "info"), format);
  }

  /** {@code version [--format <template>]} — the cheapest question a daemon answers. */
  public static List<String> version(String runtimeBinary, String format) {
    return withFormat(List.of(runtimeBinary, "version"), format);
  }

  /**
   * One line per {@code repository:tag}: {@code <id>|<repository>|<tag>|<createdAt>|<size>}.
   *
   * <p>An image with two tags prints twice under one id, and an untagged one prints once with
   * {@code <none>} in both fields — which is what "dangling" is. The reader folds the lines back
   * onto the id.
   *
   * <p><b>{@code .Digest} is deliberately not asked for.</b> On the containerd image store it
   * prints the manifest digest, which is the same value as {@code .ID} — measured on docker 29.7.2
   * — so a column for it would carry no information and would read like a repo digest, which it is
   * not.
   */
  public static final String IMAGE_FORMAT =
      "{{.ID}}|{{.Repository}}|{{.Tag}}|{{.CreatedAt}}|{{.Size}}";

  /**
   * Every image the host holds, tags folded per id by the reader.
   *
   * <p><b>{@code --all}, and it took a live run to learn why.</b> The reasoning this call shipped
   * with was the classic image store's: a plain {@code image ls} hides intermediate layers and
   * shows dangling top-level images, so {@code --all} would only add rows nobody may remove. On the
   * <b>containerd</b> image store that is not true. Measured on the platform's own host, docker 29
   * with containerd: a plain {@code image ls} printed <b>zero</b> {@code <none>} rows while
   * {@code -a} printed 62 and {@code -f dangling=true} 55 — so 55 dangling images, about 12 GB,
   * were invisible to two whole collection runs.
   *
   * <p>What {@code --all} can add on a classic store is a layer another image is built on. It has
   * no tags, so it reaches the candidate set as a dangling image — and docker refuses to remove one
   * with {@code image has dependent child images}, which lands on the caller's {@code failed} list
   * where a person can read it. That is the safe direction, and it is why this is not paid for with
   * an {@code -f}: a parent that really is garbage becomes removable the run after its last child
   * goes.
   */
  public static List<String> imageLs(String runtimeBinary) {
    return List.of(
        runtimeBinary, "image", "ls", "--all", "--no-trunc", "--format", IMAGE_FORMAT);
  }

  /**
   * {@code image ls --no-trunc [--format <template>]} — the images a person reads, <b>without</b>
   * {@code --all}: the intermediate and dangling rows {@link #imageLs(String)} needs for a
   * collection are noise in a listing. {@code --no-trunc} so an id is the id and not a prefix of it.
   */
  public static List<String> imageLs(String runtimeBinary, String format) {
    return withFormat(List.of(runtimeBinary, "image", "ls", "--no-trunc"), format);
  }

  /**
   * {@code images [--format <template>]} — the plain listing, with neither {@code --all} nor {@code
   * --no-trunc}: qits-bootstrap-cli's unwrap (qits-2212) reads {@code repository:tag} off it.
   */
  public static List<String> images(String runtimeBinary, String format) {
    return withFormat(List.of(runtimeBinary, "images"), format);
  }

  /**
   * {@code image inspect -f <template> <image>} — whether a reference is on this daemon (exit zero)
   * and, through the template, what it is. The short {@code -f} is qits-bootstrap-cli's.
   */
  public static List<String> imageInspect(String runtimeBinary, String imageRef, String format) {
    return List.of(
        runtimeBinary,
        "image",
        "inspect",
        "-f",
        Objects.requireNonNull(format, "format"),
        ContainersIdentifiers.requireImage(imageRef));
  }

  /**
   * {@code image inspect <image>} — no format, so the daemon's own document. qits-runner-javalib's
   * runner toolkit (qits-2302), {@code LifecycleArgv.imageConfig}: the image the runner's own
   * container was started from, whole — see {@link #inspect(String, String)} for why this family
   * of reads takes no template.
   */
  public static List<String> imageInspect(String runtimeBinary, String imageRef) {
    return List.of(
        runtimeBinary, "image", "inspect", ContainersIdentifiers.requireImage(imageRef));
  }

  /**
   * {@code image inspect --format <template> <image>} — {@link #imageInspect} with the long {@code
   * --format}: qits-system-runner-daemon's spelling (qits-2289), which asks whether its builder
   * image, or a lease's, is on the node.
   */
  public static List<String> imageInspectFormat(
      String runtimeBinary, String imageRef, String format) {
    return List.of(
        runtimeBinary,
        "image",
        "inspect",
        "--format",
        Objects.requireNonNull(format, "format"),
        ContainersIdentifiers.requireImage(imageRef));
  }

  /**
   * What every container on the host — running or not — was created from.
   *
   * <p><b>It is {@code .Image} and not {@code .ImageID}, and that is measured rather than
   * preferred.</b> Docker 29.7.2 answers {@code can't evaluate field ImageID in type
   * *formatter.ContainerContext} — the ps formatter has no such field, whatever the docs of a
   * neighbouring command suggest. {@code .Image} prints what the container was created from: a
   * reference ({@code registry:8080/qits/qits-ci:<sha>}), or a bare {@code sha256:} id when the
   * reference no longer resolves. The reader matches BOTH shapes against an image, and a line it
   * cannot read protects nothing — which is why the caller refuses to collect at all when this
   * listing did not answer whole.
   */
  public static List<String> psImageReferences(String runtimeBinary) {
    return List.of(runtimeBinary, "ps", "-a", "--no-trunc", "--format", "{{.Image}}");
  }

  /**
   * Remove one <b>untagged</b> image, by id and never forced.
   *
   * <p>No {@code -f}, here or in {@link #imageRmRefs}: a forced remove untags an image other
   * references still name and can take one a container is holding. The refusal docker answers
   * instead is the last belt under the keep rules, and it lands on the caller's {@code failed} list
   * where a person can read it.
   *
   * <p><b>An id is only the right argument for a DANGLING image</b> — measured on the platform's
   * first real collection run, where 20 of 32 candidates came back as {@code conflict: unable to
   * delete <id> (must be forced) - image is referenced in multiple repositories}. Docker refuses to
   * delete an id that more than one reference names, and two tags of the SAME repository are two
   * references ({@code projects-daemon:2026.820.154053} beside {@code projects-daemon:863933e…}).
   * A tagged image is removed by {@link #imageRmRefs} instead.
   *
   * <p><b>{@link #imagePruneDangling} is the one sanctioned exception to "no {@code image prune}
   * anywhere in this file"</b>, and it is sanctioned for the opposite reason a prune is usually
   * refused here: a prune is docker deciding, and the whole point of the sweep above is that this
   * SERVICE decides, image by image, with the rows and the pins in front of it. What {@link
   * #imagePruneDangling} renders is an EMBEDDER's own prune — qits-runner-javalib's runner toolkit
   * (qits-2302), {@code DanglingImagesPass} — never {@code -a}, so it can only take an image no
   * container anywhere uses; the service's own collection keeps deciding image by image and never
   * prunes.
   */
  public static List<String> imageRm(String runtimeBinary, String id) {
    return List.of(runtimeBinary, "image", "rm", ContainersIdentifiers.requireImageId(id));
  }

  /**
   * Remove a tagged image by <b>every reference that names it</b>, in one call.
   *
   * <p>Each argument untags; the last one takes the image itself, which is docker's own arithmetic
   * and is why this is not a forced delete under another name. It is one call rather than one per
   * tag so the image cannot be left half-untagged by a failure between two calls — and if docker
   * refuses partway, the next run sees whatever references are left and asks again.
   *
   * <p><b>The references are the tags {@code image ls} just printed, not anything a caller sent</b>,
   * and they are belt-checked as image references all the same: this is the one removal in the
   * service that takes a name, so the check that a name cannot open with a {@code -} or carry
   * whitespace is doing real work.
   */
  public static List<String> imageRmRefs(String runtimeBinary, List<String> references) {
    if (references == null || references.isEmpty()) {
      throw new IllegalArgumentException("Invalid image references: none");
    }
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "image", "rm"));
    for (String reference : references) {
      argv.add(ContainersIdentifiers.requireImage(reference));
    }
    return List.copyOf(argv);
  }

  /**
   * {@code rmi -f <image>...} — FORCED, and so not a collection: everything above refuses
   * {@code -f} because a garbage collection must lose to a reference somebody else holds. This is a
   * teardown that means to win — qits-bootstrap-cli's unwrap (qits-2212), removing the images of a
   * platform it is taking off the machine. Each reference is belted; an empty list is refused.
   */
  public static List<String> rmiForce(String runtimeBinary, List<String> references) {
    if (references == null || references.isEmpty()) {
      throw new IllegalArgumentException("Invalid image references: none");
    }
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "rmi", "-f"));
    for (String reference : references) {
      argv.add(ContainersIdentifiers.requireImage(reference));
    }
    return List.copyOf(argv);
  }

  /**
   * {@code image prune -f} — dangling images only, and never {@code -a}: see {@link #imageRm} for
   * why this is the sanctioned exception to this file's rule against a prune. qits-runner-javalib's
   * runner toolkit (qits-2302), {@code DanglingImagesPass}: an embedder's own housekeeping pass,
   * which may only take an image no container anywhere uses, never one this service's own
   * collection is still deciding about.
   */
  public static List<String> imagePruneDangling(String runtimeBinary) {
    return List.of(runtimeBinary, "image", "prune", "-f");
  }

  /** Volumes no container references — the only volumes a collection may even consider. */
  public static List<String> volumeLsDangling(String runtimeBinary) {
    return List.of(runtimeBinary, "volume", "ls", "-q", "--filter", "dangling=true");
  }

  /**
   * {@link #volumeLsDangling}, with the filter spelled {@code -f} — qits-bootstrap-cli's
   * {@code volume ls -q -f dangling=true} (qits-2212), element for element.
   */
  public static List<String> volumeLsDanglingShort(String runtimeBinary) {
    return List.of(runtimeBinary, "volume", "ls", "-q", "-f", "dangling=true");
  }

  /**
   * When docker made the volume, then its labels one {@code k=v} per line.
   *
   * <p>Two facts in one call, separated by a newline the template emits, because a collection asks
   * both of every candidate. {@code CreatedAt} is RFC 3339 with an offset
   * ({@code 2026-08-11T17:03:01+02:00}) — measured — and the labels are ranged rather than indexed
   * for the reason {@link #VOLUME_LABELS_FORMAT} gives.
   */
  public static final String VOLUME_DETAIL_FORMAT =
      "{{.CreatedAt}}{{\"\\n\"}}{{range $k, $v := .Labels}}{{$k}}={{$v}}{{\"\\n\"}}{{end}}";

  /** One volume's creation time and labels — see {@link #VOLUME_DETAIL_FORMAT}. */
  public static List<String> volumeInspectDetail(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "volume",
        "inspect",
        "--format",
        VOLUME_DETAIL_FORMAT,
        ContainersIdentifiers.requireVolumeName(name));
  }

  /**
   * The containers that reference this volume, by name. Dangling already says there are none; this
   * is asked of a builder's state volume anyway, because the two questions have different answers
   * often enough to matter — a builder container that exists and is stopped holds its state volume
   * without keeping it out of a dangling listing.
   */
  public static List<String> psByVolume(String runtimeBinary, String volumeName) {
    return List.of(
        runtimeBinary,
        "ps",
        "-a",
        "--filter",
        "volume=" + ContainersIdentifiers.requireVolumeName(volumeName),
        "--format",
        "{{.Names}}");
  }

  /** The builder containers on this host. Docker's name filter is a substring match. */
  public static List<String> psBuildxBuilders(String runtimeBinary) {
    return List.of(
        runtimeBinary,
        "ps",
        "-a",
        "--filter",
        "name=" + ContainersIdentifiers.BUILDER_PREFIX,
        "--format",
        "{{.Names}}");
  }

  /**
   * Prune the host builder's cache down to {@code keepStorageBytes}.
   *
   * <p><b>{@code --all}, because without it a prune only considers DANGLING cache records.</b>
   * Measured on the platform's second real collection run: the host prune freed 4 GB of a 40 GB
   * cache while {@code buildx du} reported 18 GB reclaimable. {@code --all} widens the candidate
   * set to every record no build is using; the keep-storage still decides how much survives, LRU,
   * and a record an in-flight build holds is untouchable either way. So the flag changes what may
   * be considered and never what may be taken.
   *
   * <p><b>{@code --keep-storage} takes BYTES here and megabytes in {@link #buildctlPrune}</b>, which
   * is the one trap in this family. Docker 29.7.2 renamed the flag to {@code --reserved-space} and
   * keeps {@code --keep-storage} as a deprecated alias that still takes bytes — it prints
   * {@code Flag --keep-storage has been deprecated} and works. The alias is what is spelled here,
   * because it is the flag every docker the platform runs understands.
   */
  public static List<String> builderPrune(String runtimeBinary, long keepStorageBytes) {
    return List.of(
        runtimeBinary,
        "builder",
        "prune",
        "--all",
        "--force",
        "--keep-storage",
        String.valueOf(ContainersIdentifiers.requireKeepStorageBytes(keepStorageBytes)));
  }

  /** What the host builder's cache holds. A read; it removes nothing. */
  public static List<String> buildxDu(String runtimeBinary) {
    return List.of(runtimeBinary, "buildx", "du");
  }

  /** {@code buildx ls} — the host's buildx builders, one table. A read. */
  public static List<String> buildxLs(String runtimeBinary) {
    return List.of(runtimeBinary, "buildx", "ls");
  }

  /**
   * {@code buildx rm <builder>} — one builder by name; qits-bootstrap-cli (qits-2212) retires its
   * legacy {@code docker-container} builders with it. The name is belted as a reference.
   */
  public static List<String> buildxRm(String runtimeBinary, String builder) {
    return List.of(
        runtimeBinary, "buildx", "rm", ContainersIdentifiers.requireReference("builder", builder));
  }

  /** Where the buildx plugin is told to keep its own state — see {@link #buildxEnvironment}. */
  public static final String BUILDX_CONFIG_DIR = "/tmp/qits-buildx";

  /**
   * The environment the two host build-cache calls are made with.
   *
   * <p><b>Measured on the platform's first real collection run:</b> {@code docker builder prune}
   * answered {@code mkdir /work/config/buildx: permission denied}. The buildx plugin keeps state
   * under {@code $DOCKER_CONFIG}, and this service is deployed with that pointed at a READ-ONLY
   * config volume — the one carrying its registry credentials — so the plugin cannot create its
   * directory and the prune fails before it starts.
   *
   * <p>{@code BUILDX_CONFIG} moves that state and nothing else: {@code DOCKER_CONFIG} stays exactly
   * as the deployment set it, so the credentials are still found. The directory is left for buildx
   * to create, which it can, because {@value #BUILDX_CONFIG_DIR} is under a writable {@code /tmp}
   * in every container this service runs in.
   *
   * <p>It is a map beside the argvs rather than a variable set on this process, because a
   * {@code setenv} would apply to every docker call this service ever makes — including the
   * {@code exec}s into a builder, where it would mean nothing, and any future call where it would
   * mean something nobody decided.
   */
  public static Map<String, String> buildxEnvironment() {
    return Map.of("BUILDX_CONFIG", BUILDX_CONFIG_DIR);
  }

  /**
   * Prune one builder container's own cache, from inside it.
   *
   * <p><b>This is the only {@code docker exec} in the service, and both its words are constants.</b>
   * The container is a name a {@code ps} filtered on {@link ContainersIdentifiers#BUILDER_PREFIX} answered, re-
   * checked here by {@link ContainersIdentifiers#requireBuilderContainer}; the command is
   * {@code buildctl prune} and a number. Nothing an owner sends reaches it, and no caller can name
   * the container or the command — which is what keeps {@code exec} from becoming a general
   * capability of this service.
   *
   * <p><b>buildctl's {@code --keep-storage} is in MEGABYTES</b>, measured against the buildctl
   * inside a live builder: {@code --keep-storage float  Keep data below this limit (in MB)}. Handing
   * it the byte count the wire carries would ask a builder to keep a million times what was meant,
   * which prunes nothing and reads like a working call. The conversion rounds UP, so a rounding
   * error keeps cache rather than deleting it.
   */
  public static List<String> buildctlPrune(
      String runtimeBinary, String container, long keepStorageBytes) {
    return List.of(
        runtimeBinary,
        "exec",
        ContainersIdentifiers.requireBuilderContainer(container),
        "buildctl",
        "prune",
        "--keep-storage",
        String.valueOf(keepStorageMegabytes(keepStorageBytes)));
  }

  /** What one builder container's cache holds. A read; it removes nothing. */
  public static List<String> buildctlDu(String runtimeBinary, String container) {
    return List.of(
        runtimeBinary,
        "exec",
        ContainersIdentifiers.requireBuilderContainer(container),
        "buildctl",
        "du");
  }

  /** Bytes as the whole megabytes buildctl asks for, rounded up — see {@link #buildctlPrune}. */
  static long keepStorageMegabytes(long keepStorageBytes) {
    long bytes = ContainersIdentifiers.requireKeepStorageBytes(keepStorageBytes);
    return (bytes + MEGABYTE - 1) / MEGABYTE;
  }

  /** buildctl's megabyte, which is the decimal one its own help text means. */
  private static final long MEGABYTE = 1_000_000L;

  /** {@code head} then {@code --format <format>}, or {@code head} alone when the format is null. */
  private static List<String> withFormat(List<String> head, String format) {
    if (format == null) {
      return List.copyOf(head);
    }
    List<String> argv = new ArrayList<>(head);
    argv.add("--format");
    argv.add(format);
    return List.copyOf(argv);
  }

  /**
   * This service's labels and the owner's, in one sorted map. The owner's keys are re-checked here
   * rather than trusted from the spec — the second checkpoint, on the value that would forge a
   * namespace label. The namespace is the embedder's ({@link LabelNamespace#requireOwnerLabels}).
   */
  private static Map<String, String> merged(
      LabelNamespace namespace, Map<String, String> labels, Map<String, String> extraLabels) {
    Map<String, String> all = new TreeMap<>(labels == null ? Map.of() : labels);
    all.putAll(namespace.requireOwnerLabels(extraLabels));
    return all;
  }
}
