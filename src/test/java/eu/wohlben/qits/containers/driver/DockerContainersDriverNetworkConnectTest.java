package eu.wohlben.qits.containers.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DockerContainersDriver#run} joining every one of {@code spec.extraNetworks()} with its own
 * {@code network connect} right after the run that created the container — against a stand-in
 * binary scripted to answer the two shapes a real daemon would, the same technique {@link
 * DockerContainersDriverNamespaceTest} uses.
 */
class DockerContainersDriverNetworkConnectTest {

  private static final LabelNamespace NS = new LabelNamespace("qits.containers.");

  /**
   * A docker that records every argv, one element per line with a blank line between calls, answers
   * {@code run} with a fixed container id, and refuses a {@code network connect} naming {@code
   * bad-net} — everything else (including {@code rm}) succeeds.
   */
  private static DockerContainersDriver recording(Path dir, Path seen) throws IOException {
    Path script = dir.resolve("docker");
    Files.writeString(
        script,
        "#!/bin/bash\n"
            + "printf '%s\\n' \"$@\" >> "
            + seen
            + "\n"
            + "printf '\\n' >> "
            + seen
            + "\n"
            + "if [ \"$1\" = network ] && [ \"$2\" = connect ] && [ \"$3\" = bad-net ]; then\n"
            + "  echo 'Error response from daemon: network bad-net not found' >&2\n"
            + "  exit 1\n"
            + "fi\n"
            + "if [ \"$1\" = run ]; then\n"
            + "  echo cafe01\n"
            + "fi\n"
            + "exit 0\n");
    script.toFile().setExecutable(true);
    return new DockerContainersDriver(script.toString(), NS, () -> "");
  }

  private static List<List<String>> calls(Path seen) throws IOException {
    if (!Files.exists(seen)) {
      return List.of();
    }
    List<List<String>> calls = new java.util.ArrayList<>();
    List<String> current = new java.util.ArrayList<>();
    for (String line : Files.readString(seen).split("\n", -1)) {
      if (line.isEmpty()) {
        if (!current.isEmpty()) {
          calls.add(List.copyOf(current));
          current = new java.util.ArrayList<>();
        }
        continue;
      }
      current.add(line);
    }
    return calls;
  }

  @Test
  void everyExtraNetworkIsJoinedRightAfterTheRunThatCreatedTheContainer(@TempDir Path dir)
      throws IOException {
    Path seen = dir.resolve("seen");
    DockerContainersDriver driver = recording(dir, seen);
    ContainerSpec spec =
        ContainerSpec.builder("img")
            .network("qits-net")
            .extraNetwork("qits-build")
            .extraNetwork("qits-agents")
            .build();

    ContainersDriver.Started started =
        driver.run(spec, "qits-ct-x", Map.of(), LifecyclePolicy.ephemeral(null), Duration.ofSeconds(30));

    assertTrue(started.started(), started.detail());
    assertEquals("cafe01", started.containerId());

    // What a script sees as "$@" never includes argv[0] — the runtime binary itself — so a
    // recorded call opens on docker's own subcommand ("run", "network"…), never on "docker".
    List<List<String>> calls = calls(seen);
    assertEquals(3, calls.size(), calls.toString());
    assertEquals("run", calls.get(0).get(0));
    assertEquals(List.of("network", "connect", "qits-build", "qits-ct-x"), calls.get(1));
    assertEquals(List.of("network", "connect", "qits-agents", "qits-ct-x"), calls.get(2));
  }

  @Test
  void noExtraNetworkMeansNoConnectCallAtAll(@TempDir Path dir) throws IOException {
    Path seen = dir.resolve("seen");
    DockerContainersDriver driver = recording(dir, seen);
    ContainerSpec spec = ContainerSpec.builder("img").network("qits-net").build();

    ContainersDriver.Started started =
        driver.run(spec, "qits-ct-x", Map.of(), LifecyclePolicy.ephemeral(null), Duration.ofSeconds(30));

    assertTrue(started.started());
    assertEquals(1, calls(seen).size(), "a spec with no extra network makes exactly the run call");
  }

  @Test
  void aFailedJoinUnwindsTheRunAndReportsItAsFailed(@TempDir Path dir) throws IOException {
    Path seen = dir.resolve("seen");
    DockerContainersDriver driver = recording(dir, seen);
    ContainerSpec spec =
        ContainerSpec.builder("img").network("qits-net").extraNetwork("bad-net").build();

    ContainersDriver.Started started =
        driver.run(spec, "qits-ct-x", Map.of(), LifecyclePolicy.ephemeral(null), Duration.ofSeconds(30));

    // The run itself answered, but a container half joined to what its spec asked for is reported
    // the same way a refused `docker run` is: not started, no id, docker's own text as the detail.
    assertFalse(started.started());
    assertEquals("", started.containerId());
    assertTrue(started.detail().contains("bad-net"), started.detail());

    // And the container the failed run made is removed rather than left behind.
    List<List<String>> calls = calls(seen);
    assertEquals(3, calls.size(), calls.toString());
    assertEquals("run", calls.get(0).get(0));
    assertEquals(List.of("network", "connect", "bad-net", "qits-ct-x"), calls.get(1));
    assertEquals(List.of("rm", "-f", "qits-ct-x"), calls.get(2));
  }
}
