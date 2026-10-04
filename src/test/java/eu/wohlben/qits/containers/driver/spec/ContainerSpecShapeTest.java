package eu.wohlben.qits.containers.driver.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Freezes {@link ContainerSpec}'s component list: names and order.
 *
 * <p>qits-containers-service stores a {@code spec_hash} per container — SHA-256 over the JSON of
 * the whole spec — and recreates a container whose hash moved on its next ensure. A renamed
 * component renames a JSON property, and a new one serialises as {@code null}; either moves every
 * stored hash and recreates every workspace, agent and refinement container on the estate.
 */
public class ContainerSpecShapeTest {

  private static final List<String> FROZEN =
      List.of(
          "image",
          "entrypoint",
          "args",
          "env",
          "extraLabels",
          "network",
          "aliases",
          "addHosts",
          "volumeMounts",
          "sharedMounts",
          "hostDockerSocket",
          "security",
          "pullPolicy",
          "explicitName",
          "user",
          "init");

  @Test
  public void theComponentsAreFrozenInNameAndOrder() {
    List<String> actual =
        Arrays.stream(ContainerSpec.class.getRecordComponents()).map(RecordComponent::getName).toList();
    assertEquals(
        FROZEN,
        actual,
        "ContainerSpec's components are frozen. qits-containers-service's SpecFingerprintGoldenTest"
            + " (core/src/test/java/eu/wohlben/qits/containers/control/) decides every stored"
            + " spec_hash, and a renamed, reordered or added component moves it and recreates every"
            + " container on its next ensure. A new component must be null-elided in CS's"
            + " SpecFingerprint before it may be added here; only then update this list, with CS's"
            + " golden test green on the release that carries it.");
  }
}
