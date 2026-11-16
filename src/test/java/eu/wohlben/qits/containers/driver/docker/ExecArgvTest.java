package eu.wohlben.qits.containers.driver.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * qits-system-runner-daemon's DOCKER exec relay, {@code docker exec <lease> <argv...>} with no
 * {@code -i}/{@code -t}, element for element as that daemon renders it today ({@code
 * DockerOperationsTest} :403, {@code DockerOperations.exec} :272) — qits-2289.
 */
public class ExecArgvTest {

  private static final String LEASE = "qits-system-runner-lease-l1-r1";

  @Test
  public void theRelayedExecIsTheRunnersElementForElement() {
    assertEquals(
        List.of("docker", "exec", LEASE, "sh", "-c", "env; exit 3"),
        ExecArgv.exec("docker", LEASE, List.of("sh", "-c", "env; exit 3")));
    // A reference is docker's name charset: case, underscores and dots included.
    assertEquals(
        List.of("/usr/bin/docker", "exec", "Legacy_Name.1", "cat", "/etc/hostname"),
        ExecArgv.exec("/usr/bin/docker", "Legacy_Name.1", List.of("cat", "/etc/hostname")));
    // Later elements may open with a dash: they are the program's own options.
    assertEquals(
        List.of("docker", "exec", LEASE, "ls", "-la", "--color=never"),
        ExecArgv.exec("docker", LEASE, List.of("ls", "-la", "--color=never")));
  }

  @Test
  public void itIsAPlainExecNeverATerminal() {
    List<String> argv = ExecArgv.exec("docker", LEASE, List.of("true"));
    assertFalse(argv.contains("-i"), argv.toString());
    assertFalse(argv.contains("-t"), argv.toString());
    assertFalse(argv.contains("-it"), argv.toString());
    assertEquals("exec", argv.get(1));
    assertEquals(LEASE, argv.get(2));
  }

  @Test
  public void anEmptyCommandIsRefused() {
    refused("Invalid exec: no command", () -> ExecArgv.exec("docker", LEASE, List.of()));
    refused("Invalid exec: no command", () -> ExecArgv.exec("docker", LEASE, null));
  }

  @Test
  public void theProgramCannotPassAsAnExecOption() {
    refused("Invalid exec command", () -> ExecArgv.exec("docker", LEASE, List.of("-u", "root")));
    refused(
        "Invalid exec command", () -> ExecArgv.exec("docker", LEASE, List.of("--privileged")));
    refused("Invalid exec command", () -> ExecArgv.exec("docker", LEASE, List.of("", "x")));
  }

  @Test
  public void noElementCarriesANulAndTheProgramNoControlCharacter() {
    refused("Invalid exec argument", () -> ExecArgv.exec("docker", LEASE, List.of("a\u0000")));
    refused("Invalid exec command", () -> ExecArgv.exec("docker", LEASE, List.of("s\th")));
    refused(
        "Invalid exec argument",
        () -> ExecArgv.exec("docker", LEASE, Arrays.asList("sh", null)));
  }

  @Test
  public void aMultiLineScriptPassesVerbatim() {
    assertEquals(
        List.of("docker", "exec", LEASE, "sh", "-c", "set -e\n\tls /\n"),
        ExecArgv.exec("docker", LEASE, List.of("sh", "-c", "set -e\n\tls /\n")));
  }

  @Test
  public void theReferenceIsBelted() {
    refused(
        "Invalid container reference",
        () -> ExecArgv.exec("docker", "--privileged", List.of("sh")));
    refused("Invalid container reference", () -> ExecArgv.exec("docker", "a/b", List.of("sh")));
    refused("Invalid container reference", () -> ExecArgv.exec("docker", null, List.of("sh")));
  }

  private static void refused(String message, Runnable call) {
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, call::run);
    assertTrue(refused.getMessage().startsWith(message), refused.getMessage());
  }
}
