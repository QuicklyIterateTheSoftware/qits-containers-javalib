package eu.wohlben.qits.containers.driver.docker;

import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.driver.spec.InteractiveRunSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * The attached terminal sessions — {@code docker run --rm -it} and {@code docker exec -it} — as pure
 * functions, {@link DockerArgv}'s discipline: values in, a {@code List<String>} out, asserted
 * element for element in a docker-free suite.
 *
 * <p><b>This class is the one sanctioned exception to two rules written down elsewhere</b>, and the
 * exception is stated here so it is not discovered later. {@link DockerArgv} renders {@code --rm}
 * nowhere, and {@link ContainerProcess} carries {@code exec} for exactly one constant command. Both
 * stay true of {@code DockerArgv}, {@code ContainersDriver} and {@code ContainerSpec}. What this class
 * renders is different in kind: a session attached to its caller's pseudo-terminal, with no
 * deadline and no output bound, which no registry row names and whose teardown is the caller's —
 * {@code --rm} for the exit, an explicit {@link DockerArgv#rm(String, List)} by name when the caller
 * ends it first. Decided by the owner on 2026-11-13, when every component that talks to docker
 * moved onto this library (qits-2211); qits-system-service's host monitor and container shell are
 * what it was cut to. The same decision covers {@link HostRunArgv}'s {@code run --rm} (qits-2212),
 * the only other {@code --rm}; a further exception is a decision, not a refactor.
 *
 * <p><b>Nothing here runs a process.</b> A PTY session is the embedder's to spawn; {@link
 * ContainerProcess}'s bounded captures are the wrong tool for one, by design.
 *
 * <p><b>The argv IS the sandbox</b>, so the flag order is fixed and is qits-system-service's
 * ({@code InteractiveArgvTest} copies its expectations from that service's {@code
 * TerminalArgvTest}).
 */
public final class InteractiveArgv {

  private InteractiveArgv() {}

  /**
   * The whole attached run, in this order:
   *
   * <pre>
   * run --rm -it --name N (--label K=V)* [--pid host] [--network NET] (-v BIND)*
   *   [--cap-drop ALL] [--security-opt no-new-privileges] [--memory M] [--memory-swap S]
   *   [--pids-limit P] [--oom-score-adj O] (-e K=V)* IMAGE ARGS...
   * </pre>
   *
   * Belted on the way out: the name ({@link ContainersIdentifiers#requireContainerName}), every
   * label key, the network, every bind, every environment key and the image. The args follow the
   * image, so docker reads none of them as its own.
   */
  public static List<String> run(String runtimeBinary, InteractiveRunSpec spec) {
    List<String> argv = new ArrayList<>();
    argv.add(runtimeBinary);
    argv.add("run");
    argv.add("--rm");
    argv.add("-it");
    argv.add("--name");
    argv.add(ContainersIdentifiers.requireContainerName(spec.name()));
    for (Map.Entry<String, String> label : spec.labels().entrySet()) {
      argv.add("--label");
      argv.add(ContainersIdentifiers.requireExtraLabelKey(label.getKey()) + "=" + label.getValue());
    }
    if (spec.hostPid()) {
      argv.add("--pid");
      argv.add("host");
    }
    if (spec.network() != null) {
      argv.add("--network");
      argv.add(ContainersIdentifiers.requireNetwork(spec.network()));
    }
    for (String bind : spec.binds()) {
      argv.add("-v");
      argv.add(requireBind(bind));
    }
    if (spec.capDropAll()) {
      argv.add("--cap-drop");
      argv.add("ALL");
    }
    if (spec.noNewPrivileges()) {
      argv.add("--security-opt");
      argv.add("no-new-privileges");
    }
    if (spec.memory() != null) {
      argv.add("--memory");
      argv.add(spec.memory());
    }
    if (spec.memorySwap() != null) {
      argv.add("--memory-swap");
      argv.add(spec.memorySwap());
    }
    if (spec.pidsLimit() != null) {
      argv.add("--pids-limit");
      argv.add(String.valueOf(spec.pidsLimit()));
    }
    if (spec.oomScoreAdj() != null) {
      argv.add("--oom-score-adj");
      argv.add(String.valueOf(spec.oomScoreAdj()));
    }
    env(argv, spec.env());
    argv.add(ContainersIdentifiers.requireImage(spec.image()));
    argv.addAll(spec.args());
    return List.copyOf(argv);
  }

  /**
   * {@code exec -it (-e K=V)* <id> <command...>} — a program on a pseudo-terminal inside a running
   * container.
   *
   * <p>{@code containerId} is the CANONICAL 64-hex id the daemon printed when the caller's reference
   * was resolved, never the string the caller sent: {@link ContainersIdentifiers#requireFullId} is
   * the belt on that, so a name or a prefix arriving here at all means the resolution was bypassed,
   * and no terminal opens. Which program runs is the caller's closed set; an empty command is
   * refused.
   */
  public static List<String> exec(
      String runtimeBinary,
      String containerId,
      SequencedMap<String, String> env,
      List<String> command) {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("Invalid exec: no command");
    }
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "exec", "-it"));
    env(argv, env);
    argv.add(ContainersIdentifiers.requireFullId(containerId));
    argv.addAll(command);
    return List.copyOf(argv);
  }

  private static void env(List<String> argv, SequencedMap<String, String> env) {
    if (env == null) {
      return;
    }
    for (Map.Entry<String, String> variable : env.entrySet()) {
      argv.add("-e");
      argv.add(
          ContainersIdentifiers.requireEnvKey(variable.getKey())
              + "="
              + (variable.getValue() == null ? "" : variable.getValue()));
    }
  }

  /**
   * One {@code -v} bind, {@code <host path>:<container path>[:<mode>]}. The host path is absolute —
   * so the element cannot open with a {@code -} or name a volume — and no part carries a control
   * character. Which host paths are bound is the embedder's decision, made in its own code.
   */
  static String requireBind(String bind) {
    if (bind == null
        || !bind.startsWith("/")
        || bind.length() > ContainersIdentifiers.NAME_MAX * 2
        || bind.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid bind: " + ContainersIdentifiers.safely(bind));
    }
    return bind;
  }
}
