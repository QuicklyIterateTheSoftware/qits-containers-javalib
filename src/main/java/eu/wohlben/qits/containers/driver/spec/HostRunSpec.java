package eu.wohlben.qits.containers.driver.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A container an embedder runs on its own host outside any registry — what {@code
 * HostRunArgv.render} renders as {@code docker run -d}, {@code docker run --rm} or {@code docker
 * create}. qits-bootstrap-cli is the shape it was cut to (qits-2212): the one-shot helpers it runs
 * and discards, the detached containers it leaves running for the platform to adopt, the
 * containers it creates, fills with {@code docker cp} and starts attached, and the payload run
 * whose exit code is the program's own.
 *
 * <p><b>Not a {@link ContainerSpec}, and it must never become one.</b> A {@code ContainerSpec} is a
 * workload the driver owns: detached, never self-removing, adopted by a registry row and torn down
 * by an explicit {@code rm}. Nothing here is adopted by a row, and a {@link Mode#REMOVE} run's
 * teardown is docker's own {@code --rm} — the second sanctioned exception to "no {@code --rm}",
 * beside {@link InteractiveRunSpec} (owner, 2026-11-13). Nor is it an {@code InteractiveRunSpec}:
 * that one is a pseudo-terminal session a person drives; these are commands a program runs.
 *
 * <p><b>The options are an ordered list, and that is deliberate.</b> Every argv this was cut to is
 * pinned element for element by its embedder's tests, and those argvs do not agree on an order:
 * one names {@code -p} before {@code -v}, another {@code -v} before {@code -e} before {@code
 * --entrypoint}, a third {@code --entrypoint} before {@code --memory}. A record with a field per
 * flag would have to pick one order and change every other command. So each builder call appends
 * one typed {@link Option} and the argv renders them in call order — the flag spelling and the belt
 * of every option are still this library's ({@link Kind}), only the sequence is the caller's.
 *
 * <p>The values are the caller's and are belted when the argv is rendered, not here: the name, the
 * image, every network, volume, label key, environment key, user and group, and the shape of the
 * rest ({@code HostRunArgv} lists them).
 *
 * @param mode what the argv opens with — {@code run -d}, {@code run --rm} or {@code create}
 * @param name {@code --name}, right after the mode; required for {@link Mode#DETACHED}, optional
 *     otherwise (null renders none)
 * @param options every further option, in the order the caller added them
 * @param image the image, a positional argument
 * @param args what follows the image — the program's own arguments, never docker's
 */
public record HostRunSpec(
    Mode mode, String name, List<Option> options, String image, List<String> args) {

  /** What the argv opens with. */
  public enum Mode {
    /** {@code run -d}: left running, and removed by an explicit {@code rm} when it goes. */
    DETACHED,
    /** {@code run --rm}: runs to its end, attached, and docker removes it then. */
    REMOVE,
    /** {@code create}: made and not started — filled by {@code cp}, started by {@code start -a}. */
    CREATE
  }

  /**
   * What one option is, and so how it is spelled and belted. The spelling is fixed per kind
   * because that is what each embedder's argv renders: {@code --pid=host} joined, {@code
   * --cap-drop ALL} as two elements, and so on.
   */
  public enum Kind {
    /** {@code -i}: stdin stays open — a value fed to the container on stdin. */
    INTERACTIVE,
    /** {@code -it}: a terminal handed on, one element. */
    INTERACTIVE_TTY,
    /** {@code --privileged}. */
    PRIVILEGED,
    /** {@code --pid=host}, one element. */
    PID_HOST,
    /** {@code --network <network>}. */
    NETWORK,
    /** {@code --label <key>=<value>}. */
    LABEL,
    /** {@code --restart <policy>}. */
    RESTART,
    /** {@code --cap-drop <capability>}. */
    CAP_DROP,
    /** {@code --security-opt <option>}. */
    SECURITY_OPT,
    /** {@code -p <publish>}. */
    PUBLISH,
    /** {@code --memory <bytes>}. */
    MEMORY,
    /** {@code --cpu-quota <microseconds>}. */
    CPU_QUOTA,
    /** {@code --cpuset-cpus <list>}. */
    CPUSET_CPUS,
    /** {@code --oom-score-adj <score>}. */
    OOM_SCORE_ADJ,
    /** {@code -v <volume or absolute host path>:<container path>[:<mode>]}. */
    VOLUME,
    /** {@code -e <key>=<value>}. */
    ENV,
    /** {@code -e <key>}: by NAME, the value copied from the docker CLI's own environment. */
    ENV_NAME,
    /** {@code --env-file <file>}. */
    ENV_FILE,
    /** {@code --user <user>[:<group>]}. */
    USER,
    /** {@code --group-add <group>}. */
    GROUP_ADD,
    /** {@code -w <absolute path>}. */
    WORKDIR,
    /** {@code --entrypoint <program>}. */
    ENTRYPOINT
  }

  /**
   * One option. {@code key} is used by {@link Kind#LABEL} and {@link Kind#ENV} only, {@code value}
   * by every kind that takes one; both are null where unused.
   */
  public record Option(Kind kind, String key, String value) {
    public Option {
      Objects.requireNonNull(kind, "kind");
    }
  }

  public HostRunSpec {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(image, "image");
    if (mode == Mode.DETACHED) {
      Objects.requireNonNull(name, "a detached container is named");
    }
    options = options == null ? List.of() : List.copyOf(options);
    args = args == null ? List.of() : List.copyOf(args);
  }

  /** {@code run -d --name <name> ... <image>}. */
  public static Builder detached(String name, String image) {
    return new Builder(Mode.DETACHED, image).name(name);
  }

  /** {@code run --rm ... <image>}; a {@link Builder#name} is optional. */
  public static Builder removed(String image) {
    return new Builder(Mode.REMOVE, image);
  }

  /** {@code create ... <image>}; a {@link Builder#name} is optional. */
  public static Builder created(String image) {
    return new Builder(Mode.CREATE, image);
  }

  /** Appends one option per call, in call order — see the record's javadoc for why. */
  public static final class Builder {
    private final Mode mode;
    private final String image;
    private String name;
    private final List<Option> options = new ArrayList<>();
    private final List<String> args = new ArrayList<>();

    private Builder(Mode mode, String image) {
      this.mode = mode;
      this.image = image;
    }

    /** {@code --name}, rendered right after the mode whenever it is called. */
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder interactive() {
      return add(Kind.INTERACTIVE, null, null);
    }

    public Builder interactiveTty() {
      return add(Kind.INTERACTIVE_TTY, null, null);
    }

    public Builder privileged() {
      return add(Kind.PRIVILEGED, null, null);
    }

    public Builder pidHost() {
      return add(Kind.PID_HOST, null, null);
    }

    public Builder network(String network) {
      return add(Kind.NETWORK, null, network);
    }

    public Builder label(String key, String value) {
      return add(Kind.LABEL, key, value == null ? "" : value);
    }

    public Builder restart(String policy) {
      return add(Kind.RESTART, null, policy);
    }

    public Builder capDrop(String capability) {
      return add(Kind.CAP_DROP, null, capability);
    }

    public Builder securityOpt(String option) {
      return add(Kind.SECURITY_OPT, null, option);
    }

    public Builder publish(String publish) {
      return add(Kind.PUBLISH, null, publish);
    }

    public Builder memory(String memory) {
      return add(Kind.MEMORY, null, memory);
    }

    public Builder cpuQuota(long microseconds) {
      return add(Kind.CPU_QUOTA, null, String.valueOf(microseconds));
    }

    public Builder cpusetCpus(String cpus) {
      return add(Kind.CPUSET_CPUS, null, cpus);
    }

    public Builder oomScoreAdj(int score) {
      return add(Kind.OOM_SCORE_ADJ, null, String.valueOf(score));
    }

    /** {@code -v}: {@code <volume>:<path>[:<mode>]} or {@code <absolute host path>:<path>[:<mode>]}. */
    public Builder volume(String mount) {
      return add(Kind.VOLUME, null, mount);
    }

    public Builder env(String key, String value) {
      return add(Kind.ENV, key, value == null ? "" : value);
    }

    /** {@code -e <key>} — the value never on the command line; the docker CLI's env carries it. */
    public Builder envName(String key) {
      return add(Kind.ENV_NAME, null, key);
    }

    public Builder envFile(String file) {
      return add(Kind.ENV_FILE, null, file);
    }

    public Builder user(String user) {
      return add(Kind.USER, null, user);
    }

    public Builder groupAdd(String group) {
      return add(Kind.GROUP_ADD, null, group);
    }

    public Builder workdir(String path) {
      return add(Kind.WORKDIR, null, path);
    }

    public Builder entrypoint(String program) {
      return add(Kind.ENTRYPOINT, null, program);
    }

    /** Appended after the image, in order. */
    public Builder args(List<String> args) {
      this.args.addAll(args);
      return this;
    }

    /** Appended after the image, in order. */
    public Builder args(String... args) {
      return args(List.of(args));
    }

    public HostRunSpec build() {
      return new HostRunSpec(mode, name, options, image, args);
    }

    private Builder add(Kind kind, String key, String value) {
      options.add(new Option(kind, key, value));
      return this;
    }
  }
}
