package eu.wohlben.qits.containers.driver.docker;

import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code docker exec <reference> <command...>} — a caller's command inside a running container, with
 * no {@code -i} and no {@code -t}, as a pure function: {@link DockerArgv}'s discipline, values in and
 * a {@code List<String>} out, asserted element for element in a docker-free suite.
 *
 * <p><b>This class is the second sanctioned exception to "one {@code exec}"</b>, beside {@link
 * InteractiveArgv}'s {@code exec -it}, and the exception is stated here so it is not discovered
 * later. {@link ContainerProcess} carries {@code exec} for one constant command, {@link
 * DockerArgv}'s {@code buildctl}, and that stays true of {@code DockerArgv}, {@code
 * ContainersDriver} and {@code ContainerSpec}. Decided by the owner on 2026-11-13 — every component
 * that talks to docker uses this library — and taken as that decision for qits-2289: the system
 * runner's DOCKER exec relay ({@code qits targets docker exec}, relayed by qits-system-service to
 * qits-system-runner-daemon) is what it was cut to, and it is its only use. The capability already
 * existed there; only where its argv is spelled moved. A third exception is a decision, not a
 * refactor.
 *
 * <p>Unlike a terminal session it has a deadline and an end, so it runs through {@link
 * ContainerProcess}'s bounded captures like any other call. Which container may be exec'd into, and
 * which caller may ask, is the embedder's to decide before this is reached: the relay execs only
 * into a container its own owner label names.
 */
public final class ExecArgv {

  private ExecArgv() {}

  /**
   * {@code exec <reference> <command...>}. Belted: the reference ({@link
   * ContainersIdentifiers#requireReference}, docker's name charset — the relay passes a name it
   * derived); the command is refused when empty, when any element carries a control character, and
   * when its first element is empty or opens with {@code -}, which docker would read as an option
   * of the exec rather than the program to run. The rest of the command is the caller's, verbatim.
   */
  public static List<String> exec(String runtimeBinary, String reference, List<String> command) {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("Invalid exec: no command");
    }
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "exec"));
    argv.add(ContainersIdentifiers.requireReference(reference));
    for (String element : command) {
      argv.add(HostArgvBelts.requireNoControl("exec argument", element));
    }
    if (command.getFirst().isEmpty() || command.getFirst().startsWith("-")) {
      throw HostArgvBelts.refuse("exec command", command.getFirst());
    }
    return List.copyOf(argv);
  }
}
