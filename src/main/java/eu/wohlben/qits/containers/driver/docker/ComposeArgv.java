package eu.wohlben.qits.containers.driver.docker;

import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import java.util.List;

/**
 * The {@code docker compose} plugin, as pure functions — {@link DockerArgv}'s discipline. Two
 * commands, both qits-bootstrap-cli's (qits-2212): whether the plugin is there at all, and the
 * teardown of a project an older bootstrap brought up with compose before the seed moved onto a
 * swarm stack. Nothing on the platform brings anything UP with compose any more, so there is no
 * {@code up} here.
 */
public final class ComposeArgv {

  private ComposeArgv() {}

  /** {@code compose version} — the plugin answers, or the CLI says it has no such command. */
  public static List<String> version(String runtimeBinary) {
    return List.of(runtimeBinary, "compose", "version");
  }

  /**
   * {@code compose -p <project> -f <file> down}. The project is belted as a {@link
   * ContainersIdentifiers#requireReference reference} (compose names its containers and networks
   * after it), the file as a host path.
   */
  public static List<String> down(String runtimeBinary, String project, String composeFile) {
    return List.of(
        runtimeBinary,
        "compose",
        "-p",
        ContainersIdentifiers.requireReference("compose project", project),
        "-f",
        HostArgvBelts.requireHostPath("compose file", composeFile),
        "down");
  }
}
