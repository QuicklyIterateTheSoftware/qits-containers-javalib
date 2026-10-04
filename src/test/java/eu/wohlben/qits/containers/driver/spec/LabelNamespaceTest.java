package eu.wohlben.qits.containers.driver.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The embedder-chosen label namespace. The literal maps below are what qits-containers-service's
 * {@code ContainerLabels} wrote before the extraction — with {@code new
 * LabelNamespace("qits.containers.")} they must stay exactly that, key order included.
 */
public class LabelNamespaceTest {

  private static final LabelNamespace CS = new LabelNamespace("qits.containers.");

  @Test
  public void thePrefixHasAtLeastTwoSegmentsAndATrailingDot() {
    for (String accepted :
        List.of("qits.containers.", "qits.workspaces.runner.", "a.b.", "qits-x.front-desk1.")) {
      assertEquals(accepted, new LabelNamespace(accepted).prefix());
    }
    for (String refused :
        List.of(
            "qits.",
            "Qits.x.",
            "qits.containers",
            "",
            ".qits.containers.",
            "qits..containers.",
            "1qits.containers.",
            "qits.containers..",
            "qits.con tainers.",
            "qits.containers.\n")) {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class,
              () -> new LabelNamespace(refused),
              "namespace accepted " + refused);
      assertTrue(e.getMessage().startsWith("Invalid label namespace: "), e.getMessage());
      assertFalse(e.getMessage().contains("\n"), e.getMessage());
    }
    assertThrows(IllegalArgumentException.class, () -> new LabelNamespace(null));
  }

  @Test
  public void aKeyIsThePrefixAndABeltedSuffix() {
    assertEquals("qits.containers.buildkit.config", CS.key("buildkit.config"));
    assertEquals("qits.workspaces.runner.item", new LabelNamespace("qits.workspaces.runner.").key("item"));
    for (String refused : List.of("", "Upper", ".lead", "trail.", "a..b", "a=b", "a b", "-x")) {
      assertThrows(
          IllegalArgumentException.class, () -> CS.key(refused), "suffix accepted " + refused);
    }
    assertThrows(IllegalArgumentException.class, () -> CS.key(null));
  }

  @Test
  public void theNamedKeysAreTodaysUnderCsNamespace() {
    assertEquals("qits.containers.managed", CS.managed());
    assertEquals("qits.containers.owner", CS.owner());
    assertEquals("qits.containers.workload", CS.workload());
    assertEquals("qits.containers.ref", CS.ref());
    assertEquals("qits.containers.row", CS.row());
    assertEquals("qits.containers.instance", CS.instance());
    assertEquals("container", LabelNamespace.MANAGED_CONTAINER);
    assertEquals("volume", LabelNamespace.MANAGED_VOLUME);
  }

  @Test
  public void forContainerIsTodaysMapInTodaysOrder() {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("qits.containers.managed", "container");
    expected.put("qits.containers.owner", "qits-ci");
    expected.put("qits.containers.workload", "step");
    expected.put("qits.containers.ref", "run-1");
    expected.put("qits.containers.row", "row-1");
    expected.put("qits.containers.instance", "instance-1");
    Map<String, String> actual = CS.forContainer("qits-ci", "step", "run-1", "row-1", "instance-1");
    assertEquals(expected, actual);
    assertEquals(List.copyOf(expected.keySet()), List.copyOf(actual.keySet()));
  }

  @Test
  public void forOwnerVolumeIsTodaysMapInTodaysOrder() {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("qits.containers.managed", "volume");
    expected.put("qits.containers.owner", "qits-workspaces");
    Map<String, String> actual = CS.forOwnerVolume("qits-workspaces");
    assertEquals(expected, actual);
    assertEquals(List.copyOf(expected.keySet()), List.copyOf(actual.keySet()));
  }

  @Test
  public void forVolumeIsTodaysMapInTodaysOrder() {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("qits.containers.managed", "volume");
    expected.put("qits.containers.owner", "qits-ci");
    expected.put("qits.containers.workload", "workspace");
    expected.put("qits.containers.ref", "ws-1");
    Map<String, String> actual = CS.forVolume("qits-ci", "workspace", "ws-1");
    assertEquals(expected, actual);
    assertEquals(List.copyOf(expected.keySet()), List.copyOf(actual.keySet()));
  }

  @Test
  public void theForMapsKeepTodaysValueBelts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CS.forContainer("qits ci\n--privileged", "step", "r", "r", "i"));
    assertThrows(
        IllegalArgumentException.class, () -> CS.forContainer("qits-ci", "step", "../../etc", "r", "i"));
    assertThrows(
        IllegalArgumentException.class, () -> CS.forContainer("qits-ci", "Step", "r", "r", "i"));
    assertThrows(
        IllegalArgumentException.class, () -> CS.forContainer("qits-ci", "step", "r", "r", "a:b"));
    assertThrows(IllegalArgumentException.class, () -> CS.forOwnerVolume("UPPER"));
    assertThrows(IllegalArgumentException.class, () -> CS.forVolume("qits-ci", "w", "a/b"));
  }

  @Test
  public void aRunnerNamespaceLabelsWithItsOwnPrefix() {
    LabelNamespace runner = new LabelNamespace("qits.workspaces.runner.");
    assertEquals(
        Map.of(
            "qits.workspaces.runner.managed", "volume",
            "qits.workspaces.runner.owner", "qits-workspaces"),
        runner.forOwnerVolume("qits-workspaces"));
  }

  @Test
  public void ownsIsACaseInsensitivePrefixMatch() {
    assertTrue(CS.owns("qits.containers.owner"));
    assertTrue(CS.owns("QITS.CONTAINERS.x"));
    assertTrue(CS.owns("Qits.Containers.anything"));
    assertFalse(CS.owns("qits.containersfoo"));
    assertFalse(CS.owns("qits.workspace"));
    assertFalse(CS.owns("qits.workspaces.runner.x"));
    assertFalse(CS.owns(null));
  }

  @Test
  public void anOwnerLabelInsideTheNamespaceIsRefusedWithTodaysMessage() {
    // Byte-identical to what CS's ContainersIdentifiers.requireExtraLabelKey answered: CS maps it
    // to a 400 body, so the wording is part of CS's behaviour.
    IllegalArgumentException owner =
        assertThrows(
            IllegalArgumentException.class,
            () -> CS.requireOwnerLabels(Map.of("qits.containers.owner", "somebody-else")));
    assertEquals(
        "Invalid label key (the qits.containers.namespace is this service's): 'qits.containers.owner'",
        owner.getMessage());
    IllegalArgumentException upper =
        assertThrows(
            IllegalArgumentException.class,
            () -> CS.requireOwnerLabels(Map.of("QITS.CONTAINERS.x", "v")));
    assertEquals(
        "Invalid label key (the qits.containers.namespace is this service's): 'QITS.CONTAINERS.x'",
        upper.getMessage());
  }

  @Test
  public void theRefusalNamesTheEmbeddersOwnPrefix() {
    LabelNamespace runner = new LabelNamespace("qits.workspaces.runner.");
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> runner.requireOwnerLabels(Map.of("qits.workspaces.runner.item", "1")));
    assertEquals(
        "Invalid label key (the qits.workspaces.runner.namespace is this service's):"
            + " 'qits.workspaces.runner.item'",
        refused.getMessage());
    // ...and CS's namespace is just another owner label to a runner.
    assertEquals(
        Map.of("qits.containers.owner", "x"),
        runner.requireOwnerLabels(Map.of("qits.containers.owner", "x")));
  }

  @Test
  public void ownerLabelsOutsideTheNamespaceAreAcceptedSortedAndImmutable() {
    Map<String, String> extra = new LinkedHashMap<>();
    extra.put("qits.workspaces.runner.x", "3");
    extra.put("qits.workspace", "1");
    extra.put("qits.project", null);
    Map<String, String> checked = CS.requireOwnerLabels(extra);
    assertEquals(
        List.of("qits.project", "qits.workspace", "qits.workspaces.runner.x"),
        List.copyOf(checked.keySet()));
    assertEquals("", checked.get("qits.project"));
    assertThrows(UnsupportedOperationException.class, () -> checked.put("a.b", "c"));
    assertEquals(Map.of(), CS.requireOwnerLabels(null));
    assertEquals(Map.of(), CS.requireOwnerLabels(Map.of()));
  }

  @Test
  public void theCharsetBeltStillRunsOnOwnerLabels() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class, () -> CS.requireOwnerLabels(Map.of("with=equals", "v")));
    assertEquals("Invalid label key: 'with=equals'", refused.getMessage());
  }
}
