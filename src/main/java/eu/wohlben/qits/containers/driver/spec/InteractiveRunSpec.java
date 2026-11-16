package eu.wohlben.qits.containers.driver.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * An attached terminal container — what {@code InteractiveArgv.run} renders as {@code docker run
 * --rm -it}. qits-system-service's host monitor is the shape it was cut to (qits-2211).
 *
 * <p><b>Not a {@link ContainerSpec}, and it must never become one.</b> A {@code ContainerSpec} is a
 * workload the driver owns: detached, never self-removing, adopted by a registry row and torn down
 * by an explicit {@code rm}. This is the opposite in every respect — attached to its caller's
 * pseudo-terminal, gone when it exits, and removed by its caller when the caller ends it first — so
 * the two share no type, and nothing that takes one can be handed the other.
 *
 * <p><b>Only the options an embedder renders today.</b> Each is a privilege or a fence, written as
 * a flag; a field nobody sets is a flag nobody reviewed. Unset stays a different statement from off,
 * as in {@link ContainerSpec}: a null or false field renders nothing.
 *
 * <p>Labels and environment are {@link SequencedMap}s and render in their own order — the caller's
 * argv is asserted element for element, and a map whose order is not a thing to assert against
 * would make it unassertable. The values are the caller's: label keys, environment keys, the name
 * and the image are belted ({@link ContainersIdentifiers}) when the argv is rendered.
 *
 * @param name the container name, so a teardown that has only the caller's session can find it
 * @param labels {@code --label k=v}, in order
 * @param hostPid {@code --pid host}
 * @param network {@code --network <network>}, or null for docker's default
 * @param binds {@code -v <host>:<container>[:<mode>]}, in order — absolute host paths only
 * @param capDropAll {@code --cap-drop ALL}
 * @param noNewPrivileges {@code --security-opt no-new-privileges}
 * @param memory {@code --memory}, or null
 * @param memorySwap {@code --memory-swap}, or null; equal to {@code memory} is docker's "no swap"
 * @param pidsLimit {@code --pids-limit}, or null
 * @param oomScoreAdj {@code --oom-score-adj}, or null
 * @param env {@code -e K=V}, in order
 * @param image the image, a positional argument
 * @param args what follows the image — the program's own flags, never docker's
 */
public record InteractiveRunSpec(
    String name,
    SequencedMap<String, String> labels,
    boolean hostPid,
    String network,
    List<String> binds,
    boolean capDropAll,
    boolean noNewPrivileges,
    String memory,
    String memorySwap,
    Long pidsLimit,
    Integer oomScoreAdj,
    SequencedMap<String, String> env,
    String image,
    List<String> args) {

  public InteractiveRunSpec {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(image, "image");
    labels = frozen(labels);
    env = frozen(env);
    binds = binds == null ? List.of() : List.copyOf(binds);
    args = args == null ? List.of() : List.copyOf(args);
  }

  public static Builder builder(String name, String image) {
    return new Builder(name, image);
  }

  private static SequencedMap<String, String> frozen(SequencedMap<String, String> map) {
    SequencedMap<String, String> copy = new LinkedHashMap<>();
    if (map != null) {
      map.forEach((k, v) -> copy.put(Objects.requireNonNull(k, "key"), v == null ? "" : v));
    }
    return Collections.unmodifiableSequencedMap(copy);
  }

  /** Fills the record field by field; every option starts unset. */
  public static final class Builder {
    private final String name;
    private final String image;
    private final SequencedMap<String, String> labels = new LinkedHashMap<>();
    private boolean hostPid;
    private String network;
    private final List<String> binds = new ArrayList<>();
    private boolean capDropAll;
    private boolean noNewPrivileges;
    private String memory;
    private String memorySwap;
    private Long pidsLimit;
    private Integer oomScoreAdj;
    private final SequencedMap<String, String> env = new LinkedHashMap<>();
    private final List<String> args = new ArrayList<>();

    private Builder(String name, String image) {
      this.name = name;
      this.image = image;
    }

    public Builder label(String key, String value) {
      labels.put(key, value);
      return this;
    }

    public Builder hostPid(boolean hostPid) {
      this.hostPid = hostPid;
      return this;
    }

    public Builder network(String network) {
      this.network = network;
      return this;
    }

    public Builder bind(String bind) {
      binds.add(bind);
      return this;
    }

    public Builder capDropAll(boolean capDropAll) {
      this.capDropAll = capDropAll;
      return this;
    }

    public Builder noNewPrivileges(boolean noNewPrivileges) {
      this.noNewPrivileges = noNewPrivileges;
      return this;
    }

    public Builder memory(String memory) {
      this.memory = memory;
      return this;
    }

    public Builder memorySwap(String memorySwap) {
      this.memorySwap = memorySwap;
      return this;
    }

    public Builder pidsLimit(Long pidsLimit) {
      this.pidsLimit = pidsLimit;
      return this;
    }

    public Builder oomScoreAdj(Integer oomScoreAdj) {
      this.oomScoreAdj = oomScoreAdj;
      return this;
    }

    public Builder env(String key, String value) {
      env.put(key, value);
      return this;
    }

    public Builder args(List<String> args) {
      this.args.addAll(args);
      return this;
    }

    public InteractiveRunSpec build() {
      return new InteractiveRunSpec(
          name,
          labels,
          hostPid,
          network,
          binds,
          capDropAll,
          noNewPrivileges,
          memory,
          memorySwap,
          pidsLimit,
          oomScoreAdj,
          env,
          image,
          args);
    }
  }
}
