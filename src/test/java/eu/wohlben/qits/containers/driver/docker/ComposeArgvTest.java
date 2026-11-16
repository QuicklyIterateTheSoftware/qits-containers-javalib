package eu.wohlben.qits.containers.driver.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * qits-bootstrap-cli's two compose commands, element for element as it renders them ({@code
 * Docker} :132 and {@code UnwrapPhases} :161-162) — qits-2212.
 */
public class ComposeArgvTest {

  @Test
  public void thePluginProbeIsComposeVersion() {
    assertEquals(List.of("docker", "compose", "version"), ComposeArgv.version("docker"));
  }

  @Test
  public void theLegacyTeardownNamesTheProjectAndTheFileBeforeDown() {
    assertEquals(
        List.of(
            "docker", "compose", "-p", "qits", "-f", "/w/docker-compose.qits.yml", "down"),
        ComposeArgv.down("docker", "qits", "/w/docker-compose.qits.yml"));
  }

  @Test
  public void anOptionShapedProjectOrFileIsRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> ComposeArgv.down("docker", "-v", "/w/a.yml"));
    assertThrows(IllegalArgumentException.class, () -> ComposeArgv.down("docker", "qits", "-v"));
    assertThrows(IllegalArgumentException.class, () -> ComposeArgv.down("docker", "qits", ""));
  }
}
