package eu.wohlben.qits.containers.driver.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.containers.driver.swarm.ServiceSpec;
import eu.wohlben.qits.containers.driver.swarm.ServiceUpdate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The swarm argvs, element for element. They replace qits-deployments-service's hand-built ones
 * (SwarmDeploymentDriver, DockerDatabaseContainers), so every expectation here is either copied from
 * that service's SwarmDeploymentDriverTest or written out from its builder's flag order — the move
 * onto this jar has to be provably byte-identical.
 */
public class SwarmArgvTest {

  private static final String IMAGE = "qits-platform-artifacts:8080/qits/qits-gateway:abc1234";

  private static final String HEALTH = "curl -fsS http://localhost:8080/q/health/ready || exit 1";

  private static final List<String> LABELS =
      List.of(
          "qits.platform.deployments.environment=env-id",
          "qits.platform.deployments.application=app-id",
          "qits.platform.deployments.deployment=dep-id",
          "qits.platform.deployments.available-on-env=true",
          "qits.platform.deployments.app-name=qits-gateway");

  private static final List<String> ENV =
      List.of(
          "QITS_ENVIRONMENT=dev",
          "QITS_APPLICATION=qits-gateway",
          "OTEL_RESOURCE_ATTRIBUTES=service.version=abc1234"
              + ",deployment.environment.name=dev"
              + ",service.instance.id=dev-qits-gateway");

  /** The deployer's shape: what its buildCreateArgv states for the test suite's spec(). */
  private static ServiceSpec.Builder gateway() {
    return ServiceSpec.builder("dev-qits-gateway", IMAGE)
        .replicas(1)
        .noResolveImage(true)
        .network("qits-net")
        .restartCondition("any")
        .init(true)
        .labels(LABELS)
        .health(HEALTH, Duration.ofSeconds(3), 3, Duration.ofSeconds(60))
        .updatePolicy(
            new ServiceSpec.UpdatePolicy(
                "start-first",
                "start-first",
                Duration.ofSeconds(60),
                "rollback",
                Duration.ofSeconds(30)))
        .env(ENV);
  }

  /** The labels as the create interleaves them: service label, then the same as a task label. */
  private static List<String> labelFlags(String serviceFlag, String containerFlag) {
    return LABELS.stream()
        .flatMap(label -> java.util.stream.Stream.of(serviceFlag, label, containerFlag, label))
        .toList();
  }

  private static List<String> concat(List<?>... parts) {
    return java.util.Arrays.stream(parts)
        .flatMap(List::stream)
        .map(String::valueOf)
        .toList();
  }

  private static final List<String> HEALTH_FLAGS =
      List.of(
          "--health-cmd", HEALTH,
          "--health-interval", "3s",
          "--health-retries", "3",
          "--health-start-period", "60s");

  private static final List<String> UPDATE_FLAGS =
      List.of(
          "--update-order", "start-first",
          "--rollback-order", "start-first",
          "--update-monitor", "60s",
          "--update-failure-action", "rollback",
          "--stop-grace-period", "30s");

  @Test
  public void theWholeCreateInTheDeployersFlagOrder() {
    List<String> argv = SwarmArgv.serviceCreate("docker", gateway().build());

    assertEquals(
        concat(
            List.of(
                "docker", "service", "create", "--detach",
                "--name", "dev-qits-gateway",
                "--replicas", "1",
                "--no-resolve-image",
                "--network", "qits-net",
                "--restart-condition", "any",
                "--init"),
            labelFlags("--label", "--container-label"),
            HEALTH_FLAGS,
            UPDATE_FLAGS,
            List.of(
                "--env", ENV.get(0),
                "--env", ENV.get(1),
                "--env", ENV.get(2),
                IMAGE)),
        argv);
  }

  @Test
  public void everyOptionalCreateStatementLandsWhereTheDeployerPutsIt() {
    ServiceSpec spec =
        gateway()
            .withRegistryAuth(true)
            .network("qits-private", List.of("registry.dev.localhost", "mirror.dev.localhost"))
            .dns(List.of("10.231.0.5", "9.9.9.9", "1.1.1.1"))
            .capAdd("NET_ADMIN")
            .sysctls(
                List.of(
                    "net.ipv4.ip_forward=0",
                    "net.ipv4.conf.all.forwarding=0",
                    "net.ipv6.conf.all.forwarding=0"))
            .mount(ServiceSpec.Mount.volume("qits-gateway-data", "/data", false))
            .mount(ServiceSpec.Mount.volume("qits_shared_m2", "/m2", true))
            .mount(
                new ServiceSpec.Mount(
                    "bind", "/var/run/docker.sock", "/var/run/docker.sock", false))
            .publish(new ServiceSpec.Publish(8081, 8080, null, "host"))
            .publish(new ServiceSpec.Publish(5353, 8053, "udp", "host"))
            .group("992")
            .envOverride("FOO=bar")
            .build();

    assertEquals(
        concat(
            List.of(
                "docker", "service", "create", "--detach",
                "--name", "dev-qits-gateway",
                "--replicas", "1",
                "--no-resolve-image",
                "--with-registry-auth",
                "--network", "qits-net",
                "--network", "name=qits-private,alias=registry.dev.localhost,alias=mirror.dev.localhost",
                "--dns", "10.231.0.5",
                "--dns", "9.9.9.9",
                "--dns", "1.1.1.1",
                "--restart-condition", "any",
                "--init",
                "--cap-add", "NET_ADMIN",
                "--sysctl", "net.ipv4.ip_forward=0",
                "--sysctl", "net.ipv4.conf.all.forwarding=0",
                "--sysctl", "net.ipv6.conf.all.forwarding=0"),
            labelFlags("--label", "--container-label"),
            HEALTH_FLAGS,
            UPDATE_FLAGS,
            List.of(
                "--env", ENV.get(0),
                "--env", ENV.get(1),
                "--env", ENV.get(2),
                "--mount", "type=volume,source=qits-gateway-data,target=/data",
                "--mount", "type=volume,source=qits_shared_m2,target=/m2,readonly",
                "--mount", "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock",
                "--publish", "published=8081,target=8080,mode=host",
                "--publish", "published=5353,target=8053,protocol=udp,mode=host",
                "--group", "992",
                "--env", "FOO=bar",
                IMAGE)),
        SwarmArgv.serviceCreate("docker", spec));
  }

  @Test
  public void aBareSpecStatesNothingItWasNotGiven() {
    assertEquals(
        List.of("docker", "service", "create", "--detach", "--name", "svc", "--replicas", "1", "img"),
        SwarmArgv.serviceCreate("docker", ServiceSpec.builder("svc", "img").build()));
    assertEquals(
        List.of(
            "docker", "service", "update", "--detach", "--image", "img", "--replicas", "1", "svc"),
        SwarmArgv.serviceUpdate("docker", ServiceSpec.builder("svc", "img").build()));
  }

  @Test
  public void aSubSecondGraceIsMillisecondsAndAWholeOneIsSeconds() {
    assertEquals("30s", SwarmArgv.goDuration(Duration.ofSeconds(30)));
    assertEquals("1500ms", SwarmArgv.goDuration(Duration.ofMillis(1500)));
    assertEquals("0s", SwarmArgv.goDuration(Duration.ZERO));
  }

  @Test
  public void theUpdateRestatesWhatAnUpdateOwnsAndNothingOfTheMembership() {
    ServiceSpec spec =
        gateway()
            .withRegistryAuth(true)
            .network("qits-private", List.of("registry.dev.localhost"))
            .dns("10.231.0.5")
            .capAdd("NET_ADMIN")
            .sysctl("net.ipv4.ip_forward=0")
            .mount(ServiceSpec.Mount.volume("qits-gateway-data", "/data", false))
            .publish(new ServiceSpec.Publish(8081, 8080, null, "host"))
            .group("992")
            .envOverride("FOO=bar")
            .build();
    ServiceUpdate delta =
        ServiceUpdate.builder()
            .envRemoval("QITS_GONE_FROM_CONFIG")
            .envRemoval("QITS_RETIRED_TOKEN")
            .build();

    assertEquals(
        concat(
            List.of(
                "docker", "service", "update", "--detach",
                "--no-resolve-image",
                "--image", IMAGE,
                "--replicas", "1",
                "--with-registry-auth"),
            labelFlags("--label-add", "--container-label-add"),
            HEALTH_FLAGS,
            UPDATE_FLAGS,
            List.of(
                "--init",
                "--cap-add", "NET_ADMIN",
                "--sysctl-add", "net.ipv4.ip_forward=0",
                "--env-add", ENV.get(0),
                "--env-add", ENV.get(1),
                "--env-add", ENV.get(2),
                "--env-add", "FOO=bar",
                "--env-rm", "QITS_GONE_FROM_CONFIG",
                "--env-rm", "QITS_RETIRED_TOKEN",
                "dev-qits-gateway")),
        SwarmArgv.serviceUpdate("docker", spec, delta));
  }

  @Test
  public void anUpdateNeverStatesAMountANetworkOrAPort() {
    // SwarmDeploymentDriverTest: "Mounts, networks and published ports are the create's and stay
    // the create's: an update states what changes".
    List<String> update =
        SwarmArgv.serviceUpdate(
            "docker",
            gateway()
                .mount(ServiceSpec.Mount.volume("qits-gateway-data", "/data", false))
                .publish(new ServiceSpec.Publish(8081, 8080, null, "host"))
                .build());

    assertEquals(List.of("docker", "service", "update", "--detach"), update.subList(0, 4));
    assertEquals("dev-qits-gateway", update.get(update.size() - 1));
    assertFalse(update.contains("--network"), update.toString());
    assertFalse(update.stream().anyMatch(a -> a.startsWith("--mount")), update.toString());
    assertFalse(update.contains("--publish"), update.toString());
    assertFalse(update.contains("--restart-condition"), update.toString());
    assertFalse(update.contains("--env-rm"), update.toString());
  }

  @Test
  public void theSelfUpdateMovesItsNetworksAndResolversBeforeTheTarget() {
    ServiceUpdate delta =
        ServiceUpdate.builder()
            .envRemoval("QITS_GONE")
            .networkRemoval("qits-net")
            .networkAddition("qits-private", List.of("registry.dev.localhost"))
            .networkAddition("qits-other", List.of())
            .dnsRemoval("127.0.0.11")
            .dnsAdditions(List.of("10.231.0.5", "1.1.1.1"))
            .build();

    List<String> argv = SwarmArgv.serviceUpdate("docker", gateway().build(), delta);
    List<String> tail = argv.subList(argv.indexOf("--env-rm"), argv.size());

    assertEquals(
        List.of(
            "--env-rm", "QITS_GONE",
            "--network-rm", "qits-net",
            // SwarmDeploymentDriverTest's two literals: the long form always, aliases or none.
            "--network-add", "name=qits-private,alias=registry.dev.localhost",
            "--network-add", "name=qits-other",
            "--dns-rm", "127.0.0.11",
            "--dns-add", "10.231.0.5",
            "--dns-add", "1.1.1.1",
            "dev-qits-gateway"),
        tail);
    assertEquals(
        SwarmArgv.serviceUpdate("docker", gateway().build()).subList(0, argv.indexOf("--env-rm")),
        argv.subList(0, argv.indexOf("--env-rm")),
        "the delta only appends to the plain update");
  }

  @Test
  public void scaleAndRestartAreUpdates() {
    assertEquals(
        List.of("docker", "service", "update", "--detach", "--replicas", "0", "dev-qits-gateway"),
        SwarmArgv.serviceScale("docker", "dev-qits-gateway", 0));
    assertEquals(
        List.of("docker", "service", "update", "--detach", "--force", "dev-qits-gateway"),
        SwarmArgv.serviceForceUpdate("docker", "dev-qits-gateway"));
    assertThrows(
        IllegalArgumentException.class, () -> SwarmArgv.serviceScale("docker", "svc", -1));
  }

  @Test
  public void inspectPsLogsLsAndRm() {
    String runningImage =
        "{{.Spec.TaskTemplate.ContainerSpec.Image}}|{{if .UpdateStatus}}{{.UpdateStatus.State}}"
            + "|{{.UpdateStatus.StartedAt}}|{{.UpdateStatus.Message}}{{else}}||{{end}}";
    assertEquals(
        List.of("docker", "service", "inspect", "--format", runningImage, "dev-qits-gateway"),
        SwarmArgv.serviceInspect("docker", "dev-qits-gateway", runningImage));

    assertEquals(
        List.of(
            "docker", "service", "ps", "dev-qits-gateway",
            "--filter", "desired-state=running",
            "--no-trunc",
            "--format", "{{.CurrentState}}"),
        SwarmArgv.servicePs(
            "docker", "dev-qits-gateway", List.of("desired-state=running"), true,
            "{{.CurrentState}}"));
    assertEquals(
        List.of("docker", "service", "ps", "dev-qits-gateway", "--no-trunc"),
        SwarmArgv.servicePs("docker", "dev-qits-gateway", List.of(), true, null));
    String evidence = "{{.ID}}|{{.Image}}|{{.DesiredState}}|{{.CurrentState}}|{{.Error}}";
    assertEquals(
        List.of("docker", "service", "ps", "dev-qits-gateway", "--no-trunc", "--format", evidence),
        SwarmArgv.servicePs("docker", "dev-qits-gateway", null, true, evidence));
    // DockerDatabaseContainers.drained: no --no-trunc, the format straight after the service.
    assertEquals(
        List.of("docker", "service", "ps", "qits-database", "--format", "{{.CurrentState}}"),
        SwarmArgv.servicePs("docker", "qits-database", List.of(), false, "{{.CurrentState}}"));

    assertEquals(
        List.of("docker", "service", "logs", "--tail", "200", "dev-qits-gateway"),
        SwarmArgv.serviceLogsTail("docker", "dev-qits-gateway", 200));
    assertEquals(
        List.of("docker", "service", "logs", "--tail", "100", "x1y2z3"),
        SwarmArgv.serviceLogsTail("docker", "x1y2z3", 100));
    assertThrows(
        IllegalArgumentException.class, () -> SwarmArgv.serviceLogsTail("docker", "svc", 0));

    assertEquals(
        List.of(
            "docker", "service", "ls", "-q",
            "--filter", "label=qits.platform.deployments.environment=env-id"),
        SwarmArgv.serviceLsQuiet(
            "docker",
            List.of(SwarmArgv.labelFilter("qits.platform.deployments.environment", "env-id"))));

    assertEquals(
        List.of("docker", "service", "rm", "dev-qits-gateway"),
        SwarmArgv.serviceRm("docker", "dev-qits-gateway"));
    assertEquals(
        List.of("docker", "service", "rm", "svc-a", "svc-b"),
        SwarmArgv.serviceRm("docker", List.of("svc-a", "svc-b")));
    assertThrows(
        IllegalArgumentException.class, () -> SwarmArgv.serviceRm("docker", List.<String>of()));
  }

  @Test
  public void aNullInspectFormatStatesNoneAndAFormatStaysWhereItWas() {
    assertEquals(
        List.of("docker", "service", "inspect", "dev-qits-gateway"),
        SwarmArgv.serviceInspect("docker", "dev-qits-gateway", null));
    assertEquals(
        List.of("docker", "service", "inspect", "--format", "{{.ID}}", "dev-qits-gateway"),
        SwarmArgv.serviceInspect("docker", "dev-qits-gateway", "{{.ID}}"));
  }

  @Test
  public void serviceInspectReadsSeveralServicesInOneCall() {
    assertEquals(
        List.of(
            "docker", "service", "inspect", "--format", "{{.Spec.Name}}",
            "dev-qits-gateway", "dev-qits-ci"),
        SwarmArgv.serviceInspect(
            "docker", List.of("dev-qits-gateway", "dev-qits-ci"), "{{.Spec.Name}}"));
    // A null format states none, as the single-service form does.
    assertEquals(
        List.of("docker", "service", "inspect", "dev-qits-gateway", "dev-qits-ci"),
        SwarmArgv.serviceInspect("docker", List.of("dev-qits-gateway", "dev-qits-ci"), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> SwarmArgv.serviceInspect("docker", List.<String>of(), "{{.ID}}"));
    assertThrows(
        NullPointerException.class,
        () -> SwarmArgv.serviceInspect("docker", (List<String>) null, "{{.ID}}"));
    for (String hostile : new String[] {null, "", "a/b", "-x", "a b", "a\nb"}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              SwarmArgv.serviceInspect(
                  "docker", Arrays.asList("dev-qits-gateway", hostile), "{{.ID}}"),
          String.valueOf(hostile));
    }
  }

  // --- qits-system-service's reads (qits-2211) -------------------------------------------------
  //
  // Copied from qits-system-service's DockerArgvTest (everyListAsksForOneJsonObjectPerLine,
  // inspectsAskForNoFormatAtAll, serviceTasksAreNotTruncated, anImplausibleReferenceNeverReachesAnArgv)
  // and written out from its DockerArgv builder element for element.

  @Test
  public void theSwarmListingsAreTheSystemServicesElementForElement() {
    String jsonLines = "{{json .}}";
    assertEquals(
        List.of("docker", "node", "ls", "--format", jsonLines),
        SwarmArgv.nodeLs("docker", jsonLines));
    assertEquals(
        List.of("docker", "service", "ls", "--format", jsonLines),
        SwarmArgv.serviceLs("docker", List.of(), jsonLines));
    assertEquals(
        List.of("docker", "config", "ls", "--format", jsonLines),
        SwarmArgv.configLs("docker", jsonLines));
    assertEquals(
        List.of("docker", "secret", "ls", "--format", jsonLines),
        SwarmArgv.secretLs("docker", jsonLines));
    assertEquals(
        List.of(
            "docker", "service", "ps", "dev-qits-ci", "--no-trunc", "--format", jsonLines),
        SwarmArgv.servicePs("docker", "dev-qits-ci", List.of(), true, jsonLines));
    // The optional parts.
    assertEquals(List.of("docker", "node", "ls"), SwarmArgv.nodeLs("docker", null));
    assertEquals(
        List.of("docker", "service", "ls", "--filter", "label=a=b", "--format", "{{.Name}}"),
        SwarmArgv.serviceLs("docker", List.of(SwarmArgv.labelFilter("a", "b")), "{{.Name}}"));
  }

  @Test
  public void theSwarmInspectsAskForNoFormatAndBeltTheReference() {
    assertEquals(
        List.of("docker", "node", "inspect", "abc"), SwarmArgv.nodeInspect("docker", "abc"));
    assertEquals(
        List.of("docker", "service", "inspect", "svc"), SwarmArgv.serviceInspect("docker", "svc"));
    assertEquals(
        List.of("docker", "config", "inspect", "cfg"), SwarmArgv.configInspect("docker", "cfg"));
    for (String hostile : new String[] {null, "", "a/b", "-x", "a b", "a\nb"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> SwarmArgv.nodeInspect("docker", hostile),
          String.valueOf(hostile));
      assertThrows(
          IllegalArgumentException.class,
          () -> SwarmArgv.serviceInspect("docker", hostile),
          String.valueOf(hostile));
      assertThrows(
          IllegalArgumentException.class,
          () -> SwarmArgv.configInspect("docker", hostile),
          String.valueOf(hostile));
    }
  }

  @Test
  public void nodePsListsTheTasksSwarmPlacedOnOneNodeAndBeltsTheReference() {
    assertEquals(
        List.of("docker", "node", "ps", "abc"), SwarmArgv.nodePs("docker", "abc", false, null));
    assertEquals(
        List.of("docker", "node", "ps", "--no-trunc", "abc"),
        SwarmArgv.nodePs("docker", "abc", true, null));
    assertEquals(
        List.of("docker", "node", "ps", "--format", "{{.ID}}", "abc"),
        SwarmArgv.nodePs("docker", "abc", false, "{{.ID}}"));
    assertEquals(
        List.of("docker", "node", "ps", "--no-trunc", "--format", "{{.ID}}", "abc"),
        SwarmArgv.nodePs("docker", "abc", true, "{{.ID}}"));
    assertEquals(
        List.of("docker", "node", "ps", "abc"), SwarmArgv.nodePs("docker", "abc", false, ""));
    for (String hostile : new String[] {null, "", "a/b", "-x", "a b", "a\nb"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> SwarmArgv.nodePs("docker", hostile, false, null),
          String.valueOf(hostile));
    }
  }

  // --- qits-bootstrap-cli's (qits-2212) -------------------------------------------------------
  //
  // Copied from that program's DockerStackTest and DockerNetworkTest where they pin the argv, and
  // from its source line otherwise (cited).

  @Test
  public void theBootstrapSwarmAndStackCommandsAreTheClisElementForElement() {
    // Docker :202.
    assertEquals(List.of("docker", "swarm", "init"), SwarmArgv.swarmInit("docker"));
    // DockerStackTest :35-36 and :56.
    assertEquals(
        List.of(
            "docker", "stack", "deploy", "--resolve-image", "never", "-c",
            "/w/docker-compose.qits.yml", "qits"),
        SwarmArgv.stackDeploy("docker", "/w/docker-compose.qits.yml", "qits"));
    assertEquals(List.of("docker", "stack", "rm", "qits"), SwarmArgv.stackRm("docker", "qits"));
    // DockerStackTest :66-67, :76-77 and :87-88 — the existing builders render them.
    assertEquals(
        List.of("docker", "service", "ls", "--format", "{{.Name}}"),
        SwarmArgv.serviceLs("docker", List.of(), "{{.Name}}"));
    assertEquals(
        List.of(
            "docker", "service", "ls", "--filter", "label=qits.platform.deployments.app-name",
            "--format", "{{.Name}}"),
        SwarmArgv.serviceLs(
            "docker", List.of("label=qits.platform.deployments.app-name"), "{{.Name}}"));
    assertEquals(
        List.of("docker", "service", "rm", "qits_prod-qits-ci", "qits_prod-qits-events"),
        SwarmArgv.serviceRm("docker", List.of("qits_prod-qits-ci", "qits_prod-qits-events")));
    assertEquals(
        List.of("docker", "service", "rm", "qits_prod-qits-ci", "qits_prod-qits-events"),
        SwarmArgv.serviceRmReferences(
            "docker", List.of("qits_prod-qits-ci", "qits_prod-qits-events")));
    // DockerNetworkTest :31-32 — the existing overlay create.
    assertEquals(
        List.of("docker", "network", "create", "-d", "overlay", "--attachable", "qits-net"),
        SwarmArgv.overlayNetworkCreate("docker", "qits-net", true, false, null, List.of()));
    // UnwrapPhases :387-397 — the existing listing, with and without a filter.
    assertEquals(
        List.of("docker", "network", "ls", "--format", "{{.Name}}"),
        SwarmArgv.networkLs("docker", List.of(), "{{.Name}}"));
    assertEquals(
        List.of("docker", "network", "ls", "--filter", "label=qits.cd.network", "--format", "{{.Name}}"),
        SwarmArgv.networkLs("docker", List.of("label=qits.cd.network"), "{{.Name}}"));
    // PipelinePhases :3489-3490 — one change, no --detach.
    assertEquals(
        List.of(
            "docker", "service", "update", "--env-add", "QITS_STARTUP_SEED_ENABLED=true",
            "qits_qits-projects"),
        SwarmArgv.serviceEnvAdd(
            "docker", "qits_qits-projects", List.of("QITS_STARTUP_SEED_ENABLED=true")));
  }

  @Test
  public void theBootstrapSecretCommandsAreTheClisElementForElement() {
    // SeedPhases :4256, :4268 and :4274.
    assertEquals(
        List.of("docker", "secret", "inspect", "qits-dns-hetzner-token-0a1b2c3d4e5f"),
        SwarmArgv.secretInspect("docker", "qits-dns-hetzner-token-0a1b2c3d4e5f"));
    assertEquals(
        List.of("docker", "secret", "create", "qits-dns-hetzner-token-0a1b2c3d4e5f", "-"),
        SwarmArgv.secretCreateFromStdin("docker", "qits-dns-hetzner-token-0a1b2c3d4e5f"));
  }

  @Test
  public void theBootstrapSwarmCommandsBeltWhatTheyAreHanded() {
    assertThrows(
        IllegalArgumentException.class, () -> SwarmArgv.stackDeploy("docker", "-c", "qits"));
    assertThrows(
        IllegalArgumentException.class, () -> SwarmArgv.stackDeploy("docker", "/w/a.yml", "-q"));
    assertThrows(IllegalArgumentException.class, () -> SwarmArgv.stackRm("docker", "a b"));
    assertThrows(IllegalArgumentException.class, () -> SwarmArgv.secretInspect("docker", "-f"));
    assertThrows(
        IllegalArgumentException.class, () -> SwarmArgv.secretCreateFromStdin("docker", "a/b"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SwarmArgv.serviceRmReferences("docker", List.of("ok", "-f")));
    assertThrows(
        IllegalArgumentException.class, () -> SwarmArgv.serviceRmReferences("docker", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> SwarmArgv.serviceEnvAdd("docker", "svc", List.of("NO_VALUE")));
    assertThrows(
        IllegalArgumentException.class,
        () -> SwarmArgv.serviceEnvAdd("docker", "svc", List.of("A-B=1")));
    assertThrows(
        IllegalArgumentException.class, () -> SwarmArgv.serviceEnvAdd("docker", "svc", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> SwarmArgv.serviceEnvAdd("docker", "-svc", List.of("A=1")));
  }

  @Test
  public void theOverlaysAreCreatedAsTheDeployerCreatesThem() {
    // SwarmDeploymentDriverTest.theFlatNetworksCreateArgvIsUnchanged, literally.
    assertEquals(
        List.of(
            "docker", "network", "create", "-d", "overlay", "--attachable", "--label",
            "qits.platform.deployments.network=bundle", "qits-net"),
        SwarmArgv.overlayNetworkCreate(
            "docker", "qits-net", true, false, null,
            List.of("qits.platform.deployments.network=bundle")));
    // ...thePrivateOverlayIsCreatedEncryptedAttachableAndOnItsFixedSubnet, literally.
    assertEquals(
        List.of(
            "docker", "network", "create", "-d", "overlay", "--attachable", "--opt", "encrypted",
            "--subnet", "10.231.0.0/20", "--label", "qits.platform.deployments.network=private",
            "qits-private"),
        SwarmArgv.overlayNetworkCreate(
            "docker", "qits-private", true, true, "10.231.0.0/20",
            List.of("qits.platform.deployments.network=private")));
    // An application's network carries its three labels in the deployer's order.
    assertEquals(
        List.of(
            "docker", "network", "create", "-d", "overlay", "--attachable",
            "--label", "qits.platform.deployments.network=application",
            "--label", "qits.platform.deployments.environment=env-id",
            "--label", "qits.platform.deployments.app-name=qits-gateway",
            "qits-env-dev-qits-gateway"),
        SwarmArgv.overlayNetworkCreate(
            "docker", "qits-env-dev-qits-gateway", true, false, null,
            List.of(
                "qits.platform.deployments.network=application",
                "qits.platform.deployments.environment=env-id",
                "qits.platform.deployments.app-name=qits-gateway")));
  }

  @Test
  public void networkRmLsAndInspect() {
    assertEquals(
        List.of("docker", "network", "rm", "qits-env-dev"),
        SwarmArgv.networkRm("docker", "qits-env-dev"));
    assertEquals(
        List.of(
            "docker", "network", "ls",
            "--filter", "label=qits.platform.deployments.network",
            "--format", "{{.Name}}|{{.Labels}}"),
        SwarmArgv.networkLs(
            "docker",
            List.of(SwarmArgv.labelFilter("qits.platform.deployments.network")),
            "{{.Name}}|{{.Labels}}"));
    assertEquals(
        List.of("docker", "network", "inspect", "qits-private"),
        SwarmArgv.networkInspect("docker", "qits-private", null));
    assertEquals(
        List.of("docker", "network", "inspect", "--format", "{{.Id}}", "qits-private"),
        SwarmArgv.networkInspect("docker", "qits-private", "{{.Id}}"));
  }

  @Test
  public void theTaskContainerReads() {
    // The label is docker's own and is passed through untouched — no namespace applies to it.
    String format = "{{index .Config.Labels \"com.docker.swarm.service.name\"}}";
    assertEquals(
        List.of("docker", "inspect", "--format", format, "3f2a1b0c9d8e"),
        SwarmArgv.containerInspect("docker", "3f2a1b0c9d8e", format));
    assertEquals(
        List.of(
            "docker", "ps", "--quiet",
            "--filter", "label=com.docker.swarm.service.name=qits_dev-qits-gateway"),
        SwarmArgv.psQuiet(
            "docker",
            List.of(
                SwarmArgv.labelFilter(
                    "com.docker.swarm.service.name", "qits_dev-qits-gateway"))));
  }

  @Test
  public void referencesAreRefusedOnlyWhenAbsent() {
    assertThrows(
        NullPointerException.class,
        () -> SwarmArgv.serviceInspect("docker", (String) null, "{{.ID}}"));
    assertThrows(NullPointerException.class, () -> SwarmArgv.networkRm("docker", null));
    // A reference read off the daemon goes through as read: docker answers for it.
    assertEquals(
        List.of("docker", "service", "rm", "qits_dev-qits-gateway"),
        SwarmArgv.serviceRm("docker", "qits_dev-qits-gateway"));
  }
}
