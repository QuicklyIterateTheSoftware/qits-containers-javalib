package eu.wohlben.qits.containers.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The driver hands its constructed namespace to every argv, and an owner label inside it is refused
 * <b>before any process starts</b> — asserted against a stand-in binary that writes down every time
 * it is run.
 */
class DockerContainersDriverNamespaceTest {

  private static final LabelNamespace RUNNER = new LabelNamespace("qits.workspaces.runner.");

  /** A docker that records its argv, one element per line, and answers a container id. */
  private static DockerContainersDriver recording(Path dir, Path seen) throws IOException {
    Path script = dir.resolve("docker");
    Files.writeString(
        script, "#!/bin/bash\nprintf '%s\\n' \"$@\" >> " + seen + "\necho cafe01\nexit 0\n");
    script.toFile().setExecutable(true);
    return new DockerContainersDriver(script.toString(), RUNNER, () -> "");
  }

  @Test
  void anOwnerLabelInsideTheDriversNamespaceIsRefusedBeforeAnyProcessStarts(@TempDir Path dir)
      throws IOException {
    Path seen = dir.resolve("seen");
    DockerContainersDriver driver = recording(dir, seen);
    ContainerSpec forging =
        ContainerSpec.builder("img")
            .network("qits-net")
            .label("qits.workspaces.runner.owner", "somebody-else")
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            driver.run(
                forging, "qits-ct-x", Map.of(), LifecyclePolicy.ephemeral(null), Duration.ofSeconds(30)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            driver.ensureVolume(
                new VolumeSpec("v", Map.of("qits.workspaces.runner.managed", "volume")),
                Map.of(),
                Duration.ofSeconds(30)));

    assertFalse(Files.exists(seen), "no docker process may have started");
  }

  @Test
  void theDriverRendersItsOwnNamespace(@TempDir Path dir) throws IOException {
    Path seen = dir.resolve("seen");
    DockerContainersDriver driver = recording(dir, seen);

    ContainersDriver.Started started =
        driver.run(
            ContainerSpec.builder("img").network("qits-net").build(),
            "qits-ct-x",
            RUNNER.forContainer("qits-ci", "step", "run-1", "row-1", "instance-1"),
            LifecyclePolicy.ephemeral(null),
            Duration.ofSeconds(30));
    assertTrue(started.started());
    assertEquals("cafe01", started.containerId());
    assertTrue(driver.buildkitdStamp("qits-buildkitd", Duration.ofSeconds(30)).isPresent());

    String argv = Files.readString(seen);
    assertTrue(argv.contains("qits.workspaces.runner.managed=container\n"), argv);
    assertTrue(argv.contains("qits.workspaces.runner.buildkit.config"), argv);
    assertFalse(argv.contains("qits.containers."), argv);
  }
}
