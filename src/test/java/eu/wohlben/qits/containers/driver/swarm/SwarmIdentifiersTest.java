package eu.wohlben.qits.containers.driver.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The accept/reject table for the swarm belts. The accepted values are what qits-deployments-service
 * renders today — its own DeploymentIdentifiersTest values and its allowlists — because a belt that
 * refused one of them would make the move onto this jar a behaviour change.
 */
public class SwarmIdentifiersTest {

  private static void refuses(Consumer<String> belt, String... values) {
    for (String value : values) {
      IllegalArgumentException refused =
          assertThrows(
              IllegalArgumentException.class, () -> belt.accept(value), "accepted " + value);
      assertTrue(refused.getMessage().startsWith("Invalid "), refused.getMessage());
    }
    assertThrows(IllegalArgumentException.class, () -> belt.accept(null), "accepted null");
  }

  @Test
  public void mountSourcesAreTheDeployersVolumeSources() {
    for (String ok :
        List.of("qits-gateway-data", "qits_shared_m2", "qits-edge-letsencrypt", "a", "a".repeat(64))) {
      assertEquals(ok, SwarmIdentifiers.requireMountSource(ok));
    }
    refuses(
        SwarmIdentifiers::requireMountSource,
        "", "-x", "_x", "a,b", "a=b", "a b", "/abs", "a".repeat(65), "a\nb");
  }

  @Test
  public void mountTargetsAreAbsoluteAndNeverTheRoot() {
    for (String ok : List.of("/data", "/m2", "/caches/m2", "/var/lib/postgresql/data", "/a.b_c-d")) {
      assertEquals(ok, SwarmIdentifiers.requireMountTarget(ok));
    }
    assertEquals(
        "/" + "a".repeat(254),
        SwarmIdentifiers.requireMountTarget("/" + "a".repeat(254)));
    refuses(
        SwarmIdentifiers::requireMountTarget,
        "", "/", "data", "/a,b", "/a=b", "/a b", "/a//b", "/a/", "/" + "a".repeat(255), "/a:ro");
  }

  @Test
  public void aHealthCommandIsOneNonBlankLine() {
    for (String ok :
        List.of(
            "pg_isready -U postgres || exit 1",
            "curl -fsS http://localhost:8080/q/health/ready || exit 1",
            // the curl template over the longest health path the deployer accepts
            "curl -fsS http://localhost:8080/" + "a".repeat(254) + " || exit 1",
            "x".repeat(512))) {
      assertEquals(ok, SwarmIdentifiers.requireHealthCmd(ok));
    }
    refuses(SwarmIdentifiers::requireHealthCmd, "", "   ", "a\nb", "a\tb", "x".repeat(513));
  }

  @Test
  public void capabilitiesAndSysctlsAreCheckedForShapeNotPolicy() {
    assertEquals("NET_ADMIN", SwarmIdentifiers.requireCapability("NET_ADMIN"));
    assertEquals("CAP_NET_ADMIN", SwarmIdentifiers.requireCapability("CAP_NET_ADMIN"));
    refuses(SwarmIdentifiers::requireCapability, "", "net_admin", "-NET", "NET ADMIN", "A,B");

    for (String ok :
        List.of(
            "net.ipv4.ip_forward=0",
            "net.ipv4.conf.all.forwarding=0",
            "net.ipv6.conf.all.forwarding=0")) {
      assertEquals(ok, SwarmIdentifiers.requireSysctl(ok));
    }
    refuses(
        SwarmIdentifiers::requireSysctl,
        "", "net.ipv4.ip_forward", "=0", "net.ipv4.ip_forward=", "a=b c", "-a=0", "a=0\n");
  }

  @Test
  public void groupsAreANameOrAGid() {
    for (String ok : List.of("992", "989", "docker", "some_group-1")) {
      assertEquals(ok, SwarmIdentifiers.requireGroup(ok));
    }
    refuses(SwarmIdentifiers::requireGroup, "", "a b", "a:b", "a,b", "a.b");
  }

  @Test
  public void anAttachmentAliasIsOneName() {
    for (String ok : List.of("registry.dev.localhost", "*.dev.localhost", "dev-qits-ci")) {
      assertEquals(ok, SwarmIdentifiers.requireAttachmentAlias(ok));
    }
    refuses(SwarmIdentifiers::requireAttachmentAlias, "", "a,alias=b", "a b", "a\nb");
  }

  @Test
  public void theClosedVocabularies() {
    for (String ok : List.of("none", "on-failure", "any")) {
      assertEquals(ok, SwarmIdentifiers.requireRestartCondition(ok));
    }
    refuses(SwarmIdentifiers::requireRestartCondition, "always", "ANY", "");
    for (String ok : List.of("start-first", "stop-first")) {
      assertEquals(ok, SwarmIdentifiers.requireUpdateOrder(ok));
    }
    refuses(SwarmIdentifiers::requireUpdateOrder, "START_FIRST", "first", "");
    for (String ok : List.of("pause", "continue", "rollback")) {
      assertEquals(ok, SwarmIdentifiers.requireFailureAction(ok));
    }
    refuses(SwarmIdentifiers::requireFailureAction, "abort", "");
    refuses(SwarmIdentifiers::requireMountType, "tmpfs", "VOLUME", "");
    refuses(SwarmIdentifiers::requireProtocol, "sctp", "TCP", "");
    refuses(SwarmIdentifiers::requirePublishMode, "global", "HOST", "");
  }

  @Test
  public void portsAreOneTo65535() {
    assertEquals(1, SwarmIdentifiers.requirePort(1));
    assertEquals(65535, SwarmIdentifiers.requirePort(65535));
    assertThrows(IllegalArgumentException.class, () -> SwarmIdentifiers.requirePort(0));
    assertThrows(IllegalArgumentException.class, () -> SwarmIdentifiers.requirePort(65536));
  }

  @Test
  public void aRefusalNamesTheFieldAndEchoesTheValueSafely() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class, () -> SwarmIdentifiers.requireMountSource("a\nb"));
    assertEquals("Invalid mount source: 'a?b'", refused.getMessage());
  }

  @Test
  public void theSpecTypesRunTheBelts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ServiceSpec.Mount.volume("qits-data,readonly", "/data", false));
    assertThrows(
        IllegalArgumentException.class, () -> ServiceSpec.Mount.volume("qits-data", "/", false));
    // A configured mount is rendered as configuration states it: only its type is checked.
    assertEquals(
        "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock,readonly",
        new ServiceSpec.Mount("bind", "/var/run/docker.sock", "/var/run/docker.sock", true)
            .spelling());
    assertThrows(
        IllegalArgumentException.class, () -> new ServiceSpec.Mount("tmpfs", "x", "/x", false));
    assertThrows(
        IllegalArgumentException.class, () -> new ServiceSpec.Publish(0, 8080, null, "host"));
    assertThrows(
        IllegalArgumentException.class, () -> new ServiceSpec.Publish(80, 8080, "sctp", "host"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServiceSpec.NetworkAttachment("qits-net", List.of("a,alias=b")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServiceSpec.Health("a\nb", Duration.ofSeconds(3), 3, Duration.ofSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ServiceSpec.UpdatePolicy(
                "start-first", "sideways", Duration.ofSeconds(60), "rollback",
                Duration.ofSeconds(30)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ServiceSpec.builder("svc", "img").capAdd("net admin").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ServiceSpec.builder("svc", "img").sysctl("kernel.foo").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ServiceSpec.builder("svc", "img").group("a b").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ServiceSpec.builder("svc", "img").restartCondition("always").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ServiceSpec.builder("svc", "img").replicas(-1).build());
    assertThrows(IllegalArgumentException.class, () -> ServiceSpec.builder("svc", "-img").build());
    assertThrows(NullPointerException.class, () -> ServiceSpec.builder(null, "img").build());
  }

  @Test
  public void theSpecIsImmutable() {
    ServiceSpec spec = ServiceSpec.builder("svc", "img").label("a=b").env("A=1").build();
    assertThrows(UnsupportedOperationException.class, () -> spec.labels().add("c=d"));
    assertThrows(UnsupportedOperationException.class, () -> spec.env().clear());
  }
}
