package eu.wohlben.qits.containers.driver.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.containers.driver.spec.InteractiveRunSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;

/**
 * The attached terminal argvs, element for element. Every expectation is qits-system-service's:
 * written out from its TerminalArgv.runGlances flag by flag (its TerminalArgvTest asserts each flag
 * and its value; the whole list here pins their order too), and its execShell expectation copied
 * from TerminalArgvTest.theExecArgvCarriesTheCanonicalIdAndAShellFromTheEnum literally — so that
 * service moves onto this class with its fake-docker fixtures unchanged.
 */
public class InteractiveArgvTest {

  private static final String SESSION = "11111111-2222-3333-4444-555555555555";

  private static final String IMAGE = "mirror.dev.localhost:8080/hub/nicolargo/glances:4.5.6-full";

  private static final String ID =
      "b248472f7a8e00bba8cd036232373baed8856d90eb77b06c53f3eb0b321fdb17";

  /** What TerminalArgv.runGlances states, as values the service passes in. */
  private static InteractiveRunSpec.Builder glances() {
    return InteractiveRunSpec.builder("qits-system-glances-" + SESSION, IMAGE)
        .label("qits.system.session", SESSION)
        .label("qits.system.kind", "glances")
        .label("qits.system.owner", "qits-platform-system")
        .hostPid(true)
        .network("host")
        .bind("/var/run/docker.sock:/var/run/docker.sock:ro")
        .capDropAll(true)
        .noNewPrivileges(true)
        .memory("512m")
        .memorySwap("512m")
        .pidsLimit(256L)
        .oomScoreAdj(500)
        .env("TERM", "xterm-256color");
  }

  @Test
  public void theGlancesRunIsTheSystemServicesElementForElement() {
    assertEquals(
        List.of(
            "docker",
            "run",
            "--rm",
            "-it",
            "--name",
            "qits-system-glances-" + SESSION,
            "--label",
            "qits.system.session=" + SESSION,
            "--label",
            "qits.system.kind=glances",
            "--label",
            "qits.system.owner=qits-platform-system",
            "--pid",
            "host",
            "--network",
            "host",
            "-v",
            "/var/run/docker.sock:/var/run/docker.sock:ro",
            "--cap-drop",
            "ALL",
            "--security-opt",
            "no-new-privileges",
            "--memory",
            "512m",
            "--memory-swap",
            "512m",
            "--pids-limit",
            "256",
            "--oom-score-adj",
            "500",
            "-e",
            "TERM=xterm-256color",
            IMAGE),
        InteractiveArgv.run("docker", glances().build()));
  }

  @Test
  public void theProgramsOwnFlagsFollowTheImage() {
    // TerminalArgvTest.theImageIsTheLastElementUnlessGlancesWasGivenFlags.
    List<String> flagged =
        InteractiveArgv.run(
            "docker", glances().args(List.of("--disable-plugin", "cloud")).build());
    assertEquals(
        List.of(IMAGE, "--disable-plugin", "cloud"),
        flagged.subList(flagged.size() - 3, flagged.size()));
  }

  @Test
  public void anUnsetOptionRendersNothing() {
    assertEquals(
        List.of("docker", "run", "--rm", "-it", "--name", "t", "img"),
        InteractiveArgv.run("docker", InteractiveRunSpec.builder("t", "img").build()));
  }

  @Test
  public void labelsAndEnvironmentRenderInTheCallersOrder() {
    List<String> argv =
        InteractiveArgv.run(
            "docker",
            InteractiveRunSpec.builder("t", "img")
                .label("z.key", "1")
                .label("a.key", "2")
                .env("ZED", "1")
                .env("ALPHA", "2")
                .build());
    assertEquals(
        List.of(
            "docker", "run", "--rm", "-it", "--name", "t", "--label", "z.key=1", "--label",
            "a.key=2", "-e", "ZED=1", "-e", "ALPHA=2", "img"),
        argv);
  }

  @Test
  public void aHostileValueNeverReachesTheRun() {
    assertThrows(
        IllegalArgumentException.class,
        () -> InteractiveArgv.run("docker", InteractiveRunSpec.builder("-x", IMAGE).build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> InteractiveArgv.run("docker", InteractiveRunSpec.builder("t", "--privileged").build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            InteractiveArgv.run(
                "docker", InteractiveRunSpec.builder("t", IMAGE).label("a=b", "c").build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            InteractiveArgv.run(
                "docker", InteractiveRunSpec.builder("t", IMAGE).env("A=B", "c").build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            InteractiveArgv.run(
                "docker", InteractiveRunSpec.builder("t", IMAGE).bind("--privileged").build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            InteractiveArgv.run(
                "docker", InteractiveRunSpec.builder("t", IMAGE).bind("vol:/x").build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            InteractiveArgv.run(
                "docker", InteractiveRunSpec.builder("t", IMAGE).network("-x").build()));
  }

  @Test
  public void theExecIsTheSystemServicesElementForElement() {
    // TerminalArgvTest.theExecArgvCarriesTheCanonicalIdAndAShellFromTheEnum, literally.
    SequencedMap<String, String> env = new LinkedHashMap<>();
    env.put("TERM", "xterm-256color");
    env.put("QITS_SYSTEM_SESSION", SESSION);
    assertEquals(
        List.of(
            "docker",
            "exec",
            "-it",
            "-e",
            "TERM=xterm-256color",
            "-e",
            "QITS_SYSTEM_SESSION=" + SESSION,
            ID,
            "sh"),
        InteractiveArgv.exec("docker", ID, env, List.of("sh")));
  }

  @Test
  public void anExecTargetThatIsNotACanonicalIdNeverOpensATerminal() {
    // TerminalArgvTest.anExecTargetThatIsNotACanonicalIdNeverOpensATerminal.
    SequencedMap<String, String> env = new LinkedHashMap<>();
    assertThrows(
        IllegalArgumentException.class,
        () -> InteractiveArgv.exec("docker", "dev-qits-ci", env, List.of("bash")));
    assertThrows(
        IllegalArgumentException.class,
        () -> InteractiveArgv.exec("docker", "b248472f7a8e", env, List.of("bash")));
    assertThrows(
        IllegalArgumentException.class, () -> InteractiveArgv.exec("docker", ID, env, List.of()));
    assertEquals(
        List.of("docker", "exec", "-it", ID, "bash"),
        InteractiveArgv.exec("docker", ID, null, List.of("bash")));
  }
}
