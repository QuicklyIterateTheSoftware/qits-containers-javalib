package eu.wohlben.qits.containers.driver.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Argv assembly only — the real docker calls arrive in WP4 and are proved by an integration test
 * that opts in. Worth asserting <b>element for element</b> for the reason {@code
 * CiDaemonLauncherTest} states: the argv IS the sandbox, and a flag lost in a refactor is invisible
 * everywhere else until it is invisible in production.
 */
public class DockerArgvTest {

  /** CS's own namespace, so every asserted argv stays literally the one CS renders. */
  private static final LabelNamespace NS = new LabelNamespace("qits.containers.");

  private static final String BOOTSTRAP = "set -e\nexec /tmp/qits-ci-daemon\n";

  /** A full-featured ci-step-shaped spec: every sandbox flag, both kinds of mount, the socket on. */
  private static ContainerSpec.Builder ciStep() {
    return ContainerSpec.builder("maven:3.9")
        .network("qits-net")
        .alias("ci-step-1")
        .addHost("host.docker.internal:host-gateway")
        .label("qits.ci.run", "run-1")
        .mount("qits-ci-run-1", "/work")
        .shared("qits_shared_m2", "/caches/m2")
        .hostDockerSocket(true)
        .security(new ContainerSpec.SecurityPosture(true, true, "4g", "4g", 2048L, "2", 1000))
        .env("QITS_CI_SHA", "cafebabe")
        .env("QITS_CI_DAEMON_ID", "daemon-7")
        .entrypoint("/bin/sh")
        .args("-c", BOOTSTRAP)
        .name("qits-ci-abc-0")
        .user("build");
  }

  private static Map<String, String> labels() {
    return NS.forContainer("qits-ci", "step", "run-1", "row-1", "instance-1");
  }

  private static List<String> render(ContainerSpec spec, LifecyclePolicy policy) {
    return DockerArgv.run("docker", NS, "qits-ci-abc-0", spec, labels(), policy);
  }

  @Test
  public void rendersTheWholeEphemeralStepArgv() {
    assertEquals(
        List.of(
            "docker",
            "run",
            "-d",
            "--name",
            "qits-ci-abc-0",
            "--network",
            "qits-net",
            "--network-alias",
            "ci-step-1",
            "--add-host=host.docker.internal:host-gateway",
            "--user",
            "build",
            "--label",
            "qits.ci.run=run-1",
            "--label",
            "qits.containers.instance=instance-1",
            "--label",
            "qits.containers.managed=container",
            "--label",
            "qits.containers.owner=qits-ci",
            "--label",
            "qits.containers.ref=run-1",
            "--label",
            "qits.containers.row=row-1",
            "--label",
            "qits.containers.workload=step",
            "--security-opt=no-new-privileges",
            "--cap-drop=ALL",
            "--memory",
            "4g",
            "--memory-swap",
            "4g",
            "--pids-limit",
            "2048",
            "--cpus",
            "2",
            "--oom-score-adj",
            "1000",
            "-v",
            "qits-ci-run-1:/work",
            "-v",
            "qits_shared_m2:/caches/m2",
            "-v",
            "/var/run/docker.sock:/var/run/docker.sock",
            "-e",
            "QITS_CI_DAEMON_ID=daemon-7",
            "-e",
            "QITS_CI_SHA=cafebabe",
            "--entrypoint",
            "/bin/sh",
            "maven:3.9",
            "-c",
            BOOTSTRAP),
        render(ciStep().build(), LifecyclePolicy.ephemeral(Duration.ofHours(6))));
  }

  @Test
  public void dnsServersRenderOneFlagPerEntryRightAfterAddHost() {
    List<String> argv =
        render(
            ciStep().dns("8.8.8.8", "2001:4860:4860::8888").build(), LifecyclePolicy.ephemeral(null));
    int addHost = argv.indexOf("--add-host=host.docker.internal:host-gateway");
    assertEquals("--dns", argv.get(addHost + 1));
    assertEquals("8.8.8.8", argv.get(addHost + 2));
    assertEquals("--dns", argv.get(addHost + 3));
    assertEquals("2001:4860:4860::8888", argv.get(addHost + 4));
    assertEquals("--user", argv.get(addHost + 5), "nothing else shifts, dns is the only insertion");

    // Exactly those four elements on top of the dns-less rendering — nothing else moves.
    List<String> without = render(ciStep().build(), LifecyclePolicy.ephemeral(null));
    List<String> minusDns = new ArrayList<>(argv);
    minusDns.subList(addHost + 1, addHost + 5).clear();
    assertEquals(without, minusDns, "dns must add --dns pairs and change nothing else");
  }

  @Test
  public void aSpecWithNoDnsRendersNoDnsFlagAtAll() {
    // The absence is the claim, as it is for --init, --user and the socket: a spec that named no
    // resolver must not silently acquire one.
    assertFalse(
        render(ciStep().build(), LifecyclePolicy.ephemeral(null)).contains("--dns"),
        "a spec with no dns entries must render no --dns flag");
  }

  @Test
  public void theSocketMountIsTheOnlyDifferenceBetweenAskingAndNotAsking() {
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(null);
    List<String> withSocket = render(ciStep().build(), policy);
    List<String> without = render(ciStep().hostDockerSocket(false).build(), policy);

    int mount = withSocket.indexOf("/var/run/docker.sock:/var/run/docker.sock");
    assertTrue(mount > 0, withSocket.toString());
    assertEquals("-v", withSocket.get(mount - 1));

    // Exactly the pair, and nothing else: the sandbox does not relax for a workload that asked for
    // the socket, because cap-drop and no-new-privileges cost a socket CLIENT nothing and keeping
    // them unconditional is what keeps them meaning something for the workloads that never opt in.
    List<String> minusThePair = new ArrayList<>(withSocket);
    minusThePair.subList(mount - 1, mount + 1).clear();
    assertEquals(without, minusThePair, "the socket must add a mount and change nothing else");
    assertTrue(withSocket.contains("--cap-drop=ALL"), withSocket.toString());
    assertTrue(withSocket.contains("--security-opt=no-new-privileges"), withSocket.toString());
  }

  @Test
  public void theSocketsGroupIsJoinedBesideTheBindAndNowhereElse() {
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(null);
    List<String> joined =
        DockerArgv.run("docker", NS, "qits-ci-abc-0", ciStep().build(), labels(), policy, "993");

    // Beside the bind, in that order: the bind is what the membership is for, and a --group-add
    // that could drift away from it would be a membership nothing justifies.
    int mount = joined.indexOf("/var/run/docker.sock:/var/run/docker.sock");
    assertEquals("-v", joined.get(mount - 1));
    assertEquals("--group-add", joined.get(mount + 1));
    assertEquals("993", joined.get(mount + 2));

    // And it is exactly that pair on top of the ungrouped rendering — nothing else moves.
    List<String> ungrouped = render(ciStep().build(), policy);
    List<String> minusTheGroup = new ArrayList<>(joined);
    minusTheGroup.subList(mount + 1, mount + 3).clear();
    assertEquals(ungrouped, minusTheGroup, "the group must add two elements and change nothing else");
  }

  @Test
  public void aWorkloadWithNoSocketJoinsNoGroupWhateverTheHostsIs() {
    // The security assertion of this pair: the deployment's group is a fact about the host and is
    // held for every launch, so a workload that did not declare the bind must never pick it up.
    List<String> argv =
        DockerArgv.run(
            "docker",
            NS,
            "qits-ci-abc-0",
            ciStep().hostDockerSocket(false).build(),
            labels(),
            LifecyclePolicy.ephemeral(null),
            "993");
    assertFalse(argv.contains("--group-add"), argv.toString());
    assertFalse(argv.contains("993"), argv.toString());
  }

  @Test
  public void aGroupThatCouldReadAsAFlagIsRefused() {
    // The second belt, on a value that reaches an argv element. Nothing a caller sends can arrive
    // here — the group is read off the socket — but "no caller can reach it" is exactly the claim
    // that stops being true one refactor later.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DockerArgv.run(
                "docker",
                NS,
                "qits-ci-abc-0",
                ciStep().build(),
                labels(),
                LifecyclePolicy.ephemeral(null),
                "-rf"));
  }

  @Test
  public void aSpecThatAskedForNoSocketSeesNoSocketAtAll() {
    // THIS is the security assertion of the pair — the absence, not the presence. The docker socket
    // is root on the host, so "no socket unless the spec declared one" is the invariant, and an
    // accidental unconditional mount would be invisible until it was invisible in production.
    List<String> argv = render(ciStep().hostDockerSocket(false).build(), LifecyclePolicy.ephemeral(null));
    for (String element : argv) {
      assertFalse(element.contains("docker.sock"), "no workload may see a socket it did not ask for: " + element);
    }
  }

  @Test
  public void aSpecThatNamedNoUserRendersNoUserFlagAtAll() {
    // The absence is the claim. An unset user means the image's own default, and a --user that
    // appeared unasked would run every existing workload as somebody it was never built for.
    List<String> argv = render(ciStep().user("").build(), LifecyclePolicy.ephemeral(null));
    assertFalse(argv.contains("--user"), argv.toString());
    assertFalse(argv.contains("build"), argv.toString());
  }

  @Test
  public void theUserFlagIsTheOnlyDifferenceBetweenNamingOneAndNot() {
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(null);
    List<String> withUser = render(ciStep().build(), policy);
    List<String> without = render(ciStep().user("").build(), policy);

    int flag = withUser.indexOf("--user");
    assertTrue(flag > 0, withUser.toString());
    assertEquals("build", withUser.get(flag + 1));

    List<String> minusThePair = new ArrayList<>(withUser);
    minusThePair.subList(flag, flag + 2).clear();
    assertEquals(without, minusThePair, "naming a user must add a flag and change nothing else");
  }

  @Test
  public void aHostileUserNeverReachesTheArgv() {
    // --user's own `user:group` form is what a colon here would forge, and a leading dash is the
    // usual option-shaped argument. Neither is a thing to leave to the CLI's parser.
    for (String hostile : List.of("build:root", "-u", "Build", "root user", "")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ContainersIdentifiers.requireUser(hostile),
          hostile);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSpec.builder("img").network("qits-net").user("build:root").build());
    // A bare uid is fine — it is what a passwd-less image is addressed by.
    assertEquals("1001", ContainersIdentifiers.requireUser("1001"));
  }

  @Test
  public void aSecurityWithNoOomScoreAdjRendersNoFlagAtAll() {
    // The absence is the claim: oom-score-adj is a host-survival hint a consumer opts into, and a
    // value that appeared unasked would move a workload up or down the kernel's kill order it was
    // never meant to sit in. Null on the wire (an old consumer) has to render nothing.
    ContainerSpec noOom =
        ciStep()
            .security(new ContainerSpec.SecurityPosture(true, true, "4g", "4g", 2048L, "2", null))
            .build();
    List<String> argv = render(noOom, LifecyclePolicy.ephemeral(null));
    assertFalse(argv.contains("--oom-score-adj"), argv.toString());
  }

  @Test
  public void aSpecThatAskedForNoInitRendersNoInitFlagAtAll() {
    // The absence is the claim, as it is for the socket and the user: --init inserts a process
    // between docker and the workload's own PID 1, so every container that never asked has to keep
    // starting exactly the process its image declares. The whole-argv test above is the other half
    // — it lists every element of a spec that asked for nothing, and --init is not among them.
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(null);
    assertFalse(render(ciStep().build(), policy).contains("--init"));
    assertFalse(render(ciStep().init(false).build(), policy).contains("--init"));
  }

  @Test
  public void theInitFlagIsTheOnlyDifferenceBetweenAskingForItAndNot() {
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(null);
    List<String> withInit = render(ciStep().init(true).build(), policy);
    List<String> without = render(ciStep().build(), policy);

    // Immediately after -d, which is where the argv keeps what kind of run this is. The position is
    // asserted rather than left to the list comparison because the whole argv is asserted literally
    // elsewhere, and a flag that wandered would move every element after it.
    assertEquals("--init", withInit.get(3));
    assertEquals("-d", withInit.get(2));

    List<String> minusTheFlag = new ArrayList<>(withInit);
    minusTheFlag.remove(3);
    assertEquals(without, minusTheFlag, "asking for tini must add a flag and change nothing else");
  }

  @Test
  public void ephemeralRendersNoRestartPolicyAndRefusesRecreate() {
    // Its containers dial once and exit by design, so a restart policy would bring back a process
    // whose peer is gone — and a second container would redo work that happened once.
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(Duration.ofHours(6));
    assertFalse(render(ciStep().build(), policy).contains("--restart"));
    assertFalse(policy.restartsUnlessStopped());
    assertFalse(policy.recreatable());
  }

  @Test
  public void theLongLivedPoliciesRenderUnlessStopped() {
    // Their containers outlive this service AND a dockerd restart, which is the point of them.
    // `unless-stopped` rather than `always`, so a container stopped on purpose does not race its
    // own restart back up.
    for (LifecyclePolicy policy :
        List.of(LifecyclePolicy.idleStop(Duration.ofHours(4)), LifecyclePolicy.explicitLifetime())) {
      List<String> argv = render(ciStep().build(), policy);
      int flag = argv.indexOf("--restart");
      assertTrue(flag > 0, policy + " -> " + argv);
      assertEquals("unless-stopped", argv.get(flag + 1));
      assertTrue(policy.restartsUnlessStopped());
      assertTrue(policy.recreatable());
    }
  }

  @Test
  public void nothingThisClassRendersEverCarriesRm() {
    // A self-removing container races the `docker logs` capture that is the only diagnosis a
    // container which died on its first breath can offer — and it would delete a container whose
    // registry row still names it, which is the one state the adoption rule forbids.
    List<List<String>> everything =
        List.of(
            render(ciStep().build(), LifecyclePolicy.ephemeral(null)),
            render(ciStep().build(), LifecyclePolicy.idleStop(Duration.ofHours(4))),
            render(ciStep().build(), LifecyclePolicy.explicitLifetime()),
            DockerArgv.inspectState("docker", "c"),
            DockerArgv.inspectStartedAt("docker", "c"),
            DockerArgv.stop("docker", "c"),
            DockerArgv.rm("docker", "c"),
            DockerArgv.logsTail("docker", "c", 200),
            DockerArgv.psByLabels("docker", Map.of(NS.owner(), "qits-ci")),
            DockerArgv.pull("docker", "maven:3.9"),
            DockerArgv.volumeCreate("docker", NS, new VolumeSpec("v"), Map.of()),
            DockerArgv.volumeRm("docker", "v"),
            DockerArgv.volumeLs("docker", Map.of(NS.owner(), "qits-ci")),
            DockerArgv.volumeInspectLabels("docker", "v"),
            DockerArgv.networkInspect("docker", "qits-net"));
    for (List<String> argv : everything) {
      assertFalse(argv.contains("--rm"), argv.toString());
    }
  }

  @Test
  public void theLabelSetIsCompleteAndSorted() {
    List<String> argv = render(ciStep().build(), LifecyclePolicy.ephemeral(null));
    List<String> rendered = new ArrayList<>();
    for (int i = 0; i < argv.size() - 1; i++) {
      if ("--label".equals(argv.get(i))) {
        rendered.add(argv.get(i + 1));
      }
    }
    assertEquals(
        List.of(
            "qits.ci.run=run-1",
            "qits.containers.instance=instance-1",
            "qits.containers.managed=container",
            "qits.containers.owner=qits-ci",
            "qits.containers.ref=run-1",
            "qits.containers.row=row-1",
            "qits.containers.workload=step"),
        rendered,
        "every namespace label, the owner's own beside them, sorted");
  }

  @Test
  public void theInspectFormatsAreTheMeasuredOnes() {
    // Copied from DockerDeploymentDriver.observe unchanged. The if/else is not decoration: a bare
    // {{.State.Health.Status}} prints Go's `<no value>` for an image with no healthcheck, and
    // `<no value>` reads back as a health state no container has.
    assertEquals(
        List.of(
            "docker",
            "inspect",
            "--format",
            "{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
            "c"),
        DockerArgv.inspectState("docker", "c"));
    assertEquals(
        List.of("docker", "inspect", "--format", "{{.State.StartedAt}}", "c"),
        DockerArgv.inspectStartedAt("docker", "c"));
    // The same caveat again, on the other side: an `index` of an empty label map prints
    // `<no value>`, a range over it prints nothing — which is what an unlabelled volume means.
    assertTrue(
        DockerArgv.volumeInspectLabels("docker", "v").contains("{{range $k, $v := .Labels}}{{$k}}={{$v}}{{\"\\n\"}}{{end}}"),
        DockerArgv.volumeInspectLabels("docker", "v").toString());
  }

  @Test
  public void theObservationFormatIsTheMapSafeOneAndNotTheThreeAbovePastedTogether() {
    // MEASURED on docker 29.7.2, and the whole reason this constant is spelled out rather than
    // composed: `{{.Id}}` is not a field of the typed inspect object (the Go field is ID), so it
    // forces the CLI onto its raw-JSON fallback — and on a map, `{{if .State.Health}}` is the ERROR
    // `map has no entry for key "Health"` rather than a false. `index` answers the zero value
    // instead, so a container with no healthcheck reads `running/none` and one with a check reads
    // `running/healthy`. Pasting the two single-field formats after `{{.Id}}` compiles, renders in
    // a unit test, and fails against every container the platform runs without a healthcheck.
    assertEquals(
        List.of(
            "docker",
            "inspect",
            "--format",
            "{{.Id}}|{{.State.Status}}/"
                + "{{if index .State \"Health\"}}{{(index .State \"Health\").Status}}"
                + "{{else}}none{{end}}"
                + "|{{.State.StartedAt}}",
            "c"),
        DockerArgv.inspectObservation("docker", "c"));
    assertFalse(
        DockerArgv.OBSERVATION_FORMAT.contains("{{if .State.Health}}"),
        "the typed-path health belt does not work on the map path this format is on");
  }

  @Test
  public void theSmallCommandsAreWhatTheySay() {
    // A start takes the name and nothing else: what the container is was decided by the run that
    // made it, and a flag rendered here would be the sandbox written in a second place.
    assertEquals(List.of("docker", "start", "c"), DockerArgv.start("docker", "c"));
    assertEquals(List.of("docker", "stop", "c"), DockerArgv.stop("docker", "c"));
    assertEquals(List.of("docker", "rm", "-f", "c"), DockerArgv.rm("docker", "c"));
    assertEquals(
        List.of("docker", "logs", "--tail", "200", "c"), DockerArgv.logsTail("docker", "c", 200));
    assertEquals(List.of("docker", "pull", "maven:3.9"), DockerArgv.pull("docker", "maven:3.9"));
    assertEquals(
        List.of("docker", "network", "inspect", "qits-net"),
        DockerArgv.networkInspect("docker", "qits-net"));
    assertEquals(List.of("docker", "volume", "rm", "v"), DockerArgv.volumeRm("docker", "v"));
  }

  // --- qits-system-service's reads (qits-2211) -------------------------------------------------
  //
  // Every expected list below is qits-system-service's: copied from its DockerArgvTest where that
  // test spells the whole list, and otherwise written out from its DockerArgv builder element for
  // element — the service moves onto these with its fake-docker fixtures unchanged.

  /** The service's {@code ls} template: one JSON object per line. */
  private static final String JSON_LINES = "{{json .}}";

  @Test
  public void theHostReadsAreTheSystemServicesElementForElement() {
    assertEquals(
        List.of("docker", "info", "--format", JSON_LINES), DockerArgv.info("docker", JSON_LINES));
    assertEquals(
        List.of("docker", "system", "df", "--format", JSON_LINES),
        DockerArgv.systemDf("docker", JSON_LINES));
    assertEquals(
        List.of("/tmp/fake-docker/docker", "version", "--format", "{{.Server.Version}}"),
        DockerArgv.version("/tmp/fake-docker/docker", "{{.Server.Version}}"));
    // A null format states none.
    assertEquals(List.of("docker", "info"), DockerArgv.info("docker", null));
    assertEquals(List.of("docker", "version"), DockerArgv.version("docker", null));
    // The GC overload keeps its own template.
    assertEquals(
        List.of("docker", "system", "df", "--format", DockerArgv.DISK_USAGE_FORMAT),
        DockerArgv.systemDf("docker"));
  }

  @Test
  public void theNodeListingsAreTheSystemServicesElementForElement() {
    assertEquals(
        List.of("docker", "ps", "-a", "--no-trunc", "--format", JSON_LINES),
        DockerArgv.ps("docker", true, true, JSON_LINES));
    assertEquals(
        List.of("docker", "ps", "--no-trunc", "--format", JSON_LINES),
        DockerArgv.ps("docker", false, true, JSON_LINES));
    assertEquals(List.of("docker", "ps"), DockerArgv.ps("docker", false, false, null));
    assertEquals(
        List.of("docker", "image", "ls", "--no-trunc", "--format", JSON_LINES),
        DockerArgv.imageLs("docker", JSON_LINES));
    assertFalse(DockerArgv.imageLs("docker", JSON_LINES).contains("--all"));
    assertEquals(
        List.of("docker", "volume", "ls", "--format", JSON_LINES),
        DockerArgv.volumeLs("docker", JSON_LINES));
    // network ls is SwarmArgv's, with no filter.
    assertEquals(
        List.of("docker", "network", "ls", "--format", JSON_LINES),
        SwarmArgv.networkLs("docker", List.of(), JSON_LINES));
  }

  @Test
  public void aContainerIsInspectedByAReferenceSomebodyTyped() {
    // DockerArgvTest.inspectsAskForNoFormatAtAll, literally.
    assertEquals(
        List.of("docker", "container", "inspect", "cid"),
        DockerArgv.containerInspect("docker", "cid", null));
    // ...theExecProbeAsksForExactlyTheThreeFactsTheTerminalNeeds, literally.
    assertEquals(
        List.of(
            "docker",
            "container",
            "inspect",
            "--format",
            "{{.Id}}|{{.State.Running}}|{{.Name}}",
            "dev-qits-ci.1.abc"),
        DockerArgv.containerInspect(
            "docker", "dev-qits-ci.1.abc", "{{.Id}}|{{.State.Running}}|{{.Name}}"));
    // ...logsCarryTheTailAsTwoElements, literally.
    assertEquals(
        List.of("docker", "logs", "--tail", "50", "cid"),
        DockerArgv.logsTailByReference("docker", "cid", 50));
    // A reference may be what a name may not: docker's own names carry upper case and underscores.
    assertEquals(
        List.of("docker", "logs", "--tail", "10", "My_Container"),
        DockerArgv.logsTailByReference("docker", "My_Container", 10));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.logsTailByReference("docker", "cid", 0));
  }

  @Test
  public void theSweepsListingAndRemovalAreTheSystemServices() {
    // ...theSweepFiltersOnTheOwnerLabelAndNotOnANamePrefix: psByOwner is psByLabels on one label.
    assertEquals(
        List.of("docker", "ps", "-aq", "--filter", "label=qits.system.owner=qits-platform-system"),
        DockerArgv.psByLabels("docker", Map.of("qits.system.owner", "qits-platform-system")));
    // ...removalTakesEveryIdInOneCall, literally.
    assertEquals(
        List.of("docker", "rm", "-f", "a", "b"), DockerArgv.rm("docker", List.of("a", "b")));
    // The glances teardown: one name, the list form.
    assertEquals(
        List.of("docker", "rm", "-f", "qits-system-glances-11111111-2222-3333-4444-555555555555"),
        DockerArgv.rm(
            "docker", List.of("qits-system-glances-11111111-2222-3333-4444-555555555555")));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.rm("docker", List.<String>of()));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.rm("docker", List.of("a", "-f")));
    // The image pull is the same argv the service builds.
    String glances = "mirror.dev.localhost:8080/hub/nicolargo/glances:4.5.6-full";
    assertEquals(List.of("docker", "pull", glances), DockerArgv.pull("docker", glances));
  }

  @Test
  public void anImplausibleReferenceNeverReachesAnArgv() {
    // ...anImplausibleReferenceNeverReachesAnArgv, against the library's belt.
    assertThrows(
        IllegalArgumentException.class,
        () -> DockerArgv.containerInspect("docker", "--privileged", null));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.logsTailByReference("docker", "a b", 10));
    assertThrows(IllegalArgumentException.class, () -> SwarmArgv.nodeInspect("docker", "a/b"));
    assertThrows(IllegalArgumentException.class, () -> SwarmArgv.serviceInspect("docker", ""));
  }

  // --- qits-bootstrap-cli's commands (qits-2212) ----------------------------------------------
  //
  // Each expectation is the argv qits-bootstrap-cli renders today, copied from its tests where they
  // pin it (DockerBuildTest, DockerNetworkTest) and from the source line otherwise (cited).

  /** {@code Docker} :128, :157, :177, :207, :256, :272, :278, :284, :299 — the reads. */
  @Test
  public void theBootstrapReadsAreTheClisElementForElement() {
    assertEquals(List.of("docker", "version"), DockerArgv.version("docker", null));
    // DockerNetworkTest :81-82.
    assertEquals(
        List.of("docker", "info", "--format", "{{.Swarm.LocalNodeState}} {{.Swarm.ControlAvailable}}"),
        DockerArgv.info("docker", "{{.Swarm.LocalNodeState}} {{.Swarm.ControlAvailable}}"));
    assertEquals(
        List.of("docker", "image", "inspect", "-f", "{{.Id}}", "qits/ci:latest"),
        DockerArgv.imageInspect("docker", "qits/ci:latest", "{{.Id}}"));
    assertEquals(
        List.of("docker", "inspect", "--type", "container", "-f", "{{.Name}}", "0123456789ab"),
        DockerArgv.inspectTypeContainer("docker", "0123456789ab", "{{.Name}}"));
    String networks = "{{range $net, $_ := .NetworkSettings.Networks}}{{$net}} {{end}}";
    assertEquals(
        List.of("docker", "inspect", "-f", networks, "qits-bootstrap-progress"),
        DockerArgv.inspect("docker", "qits-bootstrap-progress", networks));
    assertEquals(
        List.of("docker", "network", "inspect", "-f", "{{.Driver}}", "qits-net"),
        DockerArgv.networkInspect("docker", "qits-net", "{{.Driver}}"));
    // Docker :407, :412, :417 — the existing ps renders them.
    assertEquals(
        List.of("docker", "ps", "-a", "--format", "{{.Names}}"),
        DockerArgv.ps("docker", true, false, "{{.Names}}"));
    // PipelinePhases :2849-2851, UnwrapPhases :494-495, BootstrapIngressLifecycle :196-197.
    assertEquals(
        List.of(
            "docker", "ps", "-a", "--filter", "label=qits.system.runner.process=7c1d2f0e",
            "--format", "{{.Names}} {{.State}}"),
        DockerArgv.ps(
            "docker",
            true,
            List.of("label=qits.system.runner.process=7c1d2f0e"),
            "{{.Names}} {{.State}}"));
    // Docker :435.
    assertEquals(
        List.of(
            "docker", "logs", "--timestamps", "--since", "2026-11-16T10:00:00Z", "qits_qits-ci.1.x"),
        DockerArgv.logsSince("docker", "qits_qits-ci.1.x", "2026-11-16T10:00:00Z"));
    // UnwrapPhases :434.
    assertEquals(
        List.of("docker", "images", "--format", "{{.Repository}}:{{.Tag}}"),
        DockerArgv.images("docker", "{{.Repository}}:{{.Tag}}"));
    // Docker :818, :888.
    assertEquals(List.of("docker", "buildx", "ls"), DockerArgv.buildxLs("docker"));
    assertEquals(
        List.of("docker", "volume", "ls", "-q", "-f", "dangling=true"),
        DockerArgv.volumeLsDanglingShort("docker"));
    // UnwrapPhases :297 — the existing label listing with no labels.
    assertEquals(List.of("docker", "volume", "ls", "-q"), DockerArgv.volumeLs("docker", Map.of()));
    // PipelinePhases :2889.
    assertEquals(
        List.of("docker", "volume", "inspect", "qits-system-runner-state-7c1d2f0e"),
        DockerArgv.volumeInspect("docker", "qits-system-runner-state-7c1d2f0e"));
  }

  /** The containers a bootstrap creates, fills, starts attached and removes. */
  @Test
  public void theBootstrapContainerLifecycleIsTheClisElementForElement() {
    String cid = "4f3c2b1a0e9d8c7b6a5f4e3d2c1b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b";
    // SeedPhases :4772 and :4781 (cp in), :2828 (cp out).
    assertEquals(
        List.of("docker", "cp", "/src/.exports/qits-runner@1/.", cid + ":/src@1"),
        DockerArgv.cpIn("docker", "/src/.exports/qits-runner@1/.", cid, "/src@1"));
    assertEquals(
        List.of("docker", "cp", cid + ":/qits", "/w/.qits-bootstrap/qits"),
        DockerArgv.cpOut("docker", cid, "/qits", "/w/.qits-bootstrap/qits"));
    // SeedPhases :4786, :4791.
    assertEquals(List.of("docker", "start", "-a", cid), DockerArgv.startAttached("docker", cid));
    assertEquals(List.of("docker", "rm", cid), DockerArgv.rmStopped("docker", cid));
    // Docker :425 / UnwrapPhases :231 — rm -f through the reference-belted list form.
    assertEquals(List.of("docker", "rm", "-f", cid), DockerArgv.rm("docker", List.of(cid)));
    // PipelinePhases :2865 — a name read off ps, with the version's dots.
    assertEquals(
        List.of("docker", "start", "qits-system-runner-7c1d2f0e-2026.1023.112622"),
        DockerArgv.startByReference("docker", "qits-system-runner-7c1d2f0e-2026.1023.112622"));
    // Docker :748.
    assertEquals(
        List.of("docker", "start", "qits-buildkitd"), DockerArgv.start("docker", "qits-buildkitd"));
    // PipelinePhases :3410.
    assertEquals(
        List.of("docker", "logs", "--tail", "20", "qits-system-runner-7c1d2f0e-2026.1023.112622"),
        DockerArgv.logsTailByReference(
            "docker", "qits-system-runner-7c1d2f0e-2026.1023.112622", 20));
  }

  /** The images a bootstrap builds, tags, loads, pushes and pulls. */
  @Test
  public void theBootstrapImageCommandsAreTheClisElementForElement() {
    // DockerBuildTest :188 (paths filled in) and :342-343.
    assertEquals(
        List.of("docker", "load", "-i", "/tmp/qits-scratch/image-1/image.tar"),
        DockerArgv.load("docker", "/tmp/qits-scratch/image-1/image.tar"));
    assertEquals(
        List.of(
            "docker", "build", "-f", "/src/cli/docker/Dockerfile", "-t", "qits-bootstrap:abc",
            "/src/cli"),
        DockerArgv.build("docker", "/src/cli/docker/Dockerfile", "qits-bootstrap:abc", "/src/cli"));
    // SeedPhases :1266-1268.
    assertEquals(
        List.of(
            "docker", "tag", "qits/build-images/ci-slot:latest",
            "registry.qits.qits-dev.eu/qits/build-images/ci-slot:latest"),
        DockerArgv.tag(
            "docker",
            "qits/build-images/ci-slot:latest",
            "registry.qits.qits-dev.eu/qits/build-images/ci-slot:latest"));
    // PipelinePhases :917 and :3301 — the global --config BEFORE the verb (PipelinePhasesTest
    // :1619-1622 pins five elements: docker --config <dir> push <ref>).
    String ref = "registry.qits.qits-dev.eu/qits/build-images/ci-base:latest";
    assertEquals(
        List.of("docker", "--config", "/tmp/qits-push-1", "push", ref),
        DockerArgv.push("docker", "/tmp/qits-push-1", ref));
    assertEquals(
        List.of("docker", "--config", "/tmp/qits-pull-1", "pull", ref),
        DockerArgv.pull("docker", "/tmp/qits-pull-1", ref));
    assertEquals(List.of("docker", "push", ref), DockerArgv.push("docker", null, ref));
    assertEquals(DockerArgv.pull("docker", ref), DockerArgv.pull("docker", null, ref));
    // UnwrapPhases :445 — forced, one reference per call there.
    assertEquals(
        List.of("docker", "rmi", "-f", "qits/ci:latest"),
        DockerArgv.rmiForce("docker", List.of("qits/ci:latest")));
    // Docker :871.
    assertEquals(
        List.of("docker", "buildx", "rm", "qits-bootstrap-builder-v4"),
        DockerArgv.buildxRm("docker", "qits-bootstrap-builder-v4"));
  }

  /** The volumes and networks a bootstrap makes and an unwrap takes away. */
  @Test
  public void theBootstrapVolumeAndNetworkCommandsAreTheClisElementForElement() {
    // DockerBuildTest :88.
    assertEquals(
        List.of("docker", "volume", "create", "qits-buildkitd-state"),
        DockerArgv.volumeCreate("docker", "qits-buildkitd-state"));
    assertEquals(
        List.of("docker", "volume", "rm", "qits_shared_m2"),
        DockerArgv.volumeRm("docker", "qits_shared_m2"));
    // SeedPhases :418 / BootstrapIngressLifecycle :104.
    assertEquals(
        List.of("docker", "network", "connect", "qits-net", "0123456789ab"),
        DockerArgv.networkConnect("docker", "qits-net", "0123456789ab"));
    // UnwrapPhases :360-361.
    assertEquals(
        List.of("docker", "network", "disconnect", "qits-net", "qits-bootstrap-progress"),
        DockerArgv.networkDisconnect("docker", "qits-net", "qits-bootstrap-progress"));
    // UnwrapPhases :406.
    assertEquals(
        List.of("docker", "network", "rm", "qits_default"),
        DockerArgv.networkRm("docker", "qits_default"));
  }

  @Test
  public void theBootstrapCommandsBeltWhatTheyAreHanded() {
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.imageInspect("docker", "-x", "{{.Id}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DockerArgv.inspectTypeContainer("docker", "a b", "{{.Id}}"));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.inspect("docker", "--all", "{{.Id}}"));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.networkInspect("docker", "a:b", "x"));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.ps("docker", true, List.of("a\nb"), null));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.logsSince("docker", "x", "--follow"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.startAttached("docker", "-x"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.startByReference("docker", "a/b"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.rmStopped("docker", "-f"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.tag("docker", "a", "-b"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.load("docker", "-i"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.load("docker", "evil:/x"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.build("docker", "f", "t", "-"));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.push("docker", "--debug", "a/b:c"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.pull("docker", "/d", "a b"));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.cpIn("docker", "/h", "c", "rel"));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.cpIn("docker", "/h", "c", "/a:b"));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.cpOut("docker", "-c", "/a", "/h"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.cpOut("docker", "c", "/a", ""));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.rmiForce("docker", List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.rmiForce("docker", List.of("-a")));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.buildxRm("docker", "-f"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.volumeCreate("docker", "a/b"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.volumeInspect("docker", "-v"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DockerArgv.networkDisconnect("docker", "net", "-f"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.networkRm("docker", "-f"));
    // A relative host path is fine where it carries no colon, and a colon is fine on an absolute or
    // dot-relative path: docker's own rule for telling a local path from container:path.
    assertEquals("./a:b", DockerArgv.load("docker", "./a:b").getLast());
    assertEquals("/a:b", DockerArgv.load("docker", "/a:b").getLast());
    assertEquals("image.tar", DockerArgv.load("docker", "image.tar").getLast());
  }

  @Test
  public void listingsRenderOneFilterPerLabelInASortedOrder() {
    assertEquals(
        List.of(
            "docker",
            "ps",
            "-aq",
            "--filter",
            "label=qits.containers.owner=qits-ci",
            "--filter",
            "label=qits.containers.workload=step"),
        DockerArgv.psByLabels(
            "docker",
            Map.of(NS.workload(), "step", NS.owner(), "qits-ci")));
    assertEquals(
        List.of("docker", "volume", "ls", "-q", "--filter", "label=qits.containers.owner=qits-ci"),
        DockerArgv.volumeLs("docker", Map.of(NS.owner(), "qits-ci")));
  }

  @Test
  public void aVolumeIsCreatedWithItsLabelsSorted() {
    assertEquals(
        List.of(
            "docker",
            "volume",
            "create",
            "--label",
            "qits.containers.managed=volume",
            "--label",
            "qits.containers.owner=qits-ci",
            "--label",
            "qits.containers.ref=ws-1",
            "--label",
            "qits.containers.workload=workspace",
            "qits-ws-1"),
        DockerArgv.volumeCreate(
            "docker",
            NS,
            new VolumeSpec("qits-ws-1"),
            NS.forVolume("qits-ci", "workspace", "ws-1")));
  }

  @Test
  public void aHostileOwnerNeverReachesALabel() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> NS.forContainer("qits ci\n--privileged", "step", "r", "r", "i"));
    assertTrue(refused.getMessage().contains("owner"), refused.getMessage());
    // Safely echoed: the control character is gone, so a refusal cannot forge a second log line.
    assertFalse(refused.getMessage().contains("\n"), refused.getMessage());
  }

  @Test
  public void aHostileRefNeverReachesALabel() {
    assertThrows(
        IllegalArgumentException.class,
        () -> NS.forContainer("qits-ci", "step", "../../etc", "r", "i"));
  }

  @Test
  public void anOptionShapedImageIsRefusedBeforeAnyDockerCall() {
    // The image is a positional argument. Nothing is known to get through it — ProcessBuilder does
    // not shell-split — but an argument that can be read as an option is not a thing to leave to
    // the parser's good manners.
    assertThrows(
        IllegalArgumentException.class, () -> ContainerSpec.builder("--privileged").build());
    assertThrows(IllegalArgumentException.class, () -> ContainerSpec.builder("  ").build());
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.pull("docker", "--privileged"));
  }

  @Test
  public void aHostileEnvironmentKeyIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSpec.builder("img").network("qits-net").env("PATH=/evil ANY", "x").build());
  }

  @Test
  public void anExtraLabelMayNotCollideWithTheServicesOwnNamespace() {
    // An owner that could write a qits.containers.* label could claim somebody else's workload as
    // its own, or disown its own. The namespace is what a listing narrows on, so it is this
    // service's alone.
    //
    // The belt now sits in the argv (LabelNamespace#requireOwnerLabels) rather than in the spec,
    // because the namespace is the embedder's: the spec cannot know whose it is.
    ContainerSpec spec =
        ContainerSpec.builder("img").network("qits-net").label(NS.owner(), "somebody-else").build();
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DockerArgv.run(
                    "docker", NS, "qits-ct-x", spec, labels(), LifecyclePolicy.ephemeral(null)));
    assertTrue(refused.getMessage().contains("qits.containers."), refused.getMessage());
  }

  @Test
  public void rendersTheWholePlatformBuilderArgv() {
    // The one privileged run this service makes, asserted whole for the same reason the step argv
    // is: --privileged appearing anywhere else, or a caller-shaped word appearing here, is the
    // regression.
    List<String> argv =
        DockerArgv.runBuildkitd(
            "docker", NS, "moby/buildkit:v0.33.0", "qits-net", "qits-buildkitd-state",
            "[worker.oci]\n  gc = true\n", "cafe01", "4", "8g", 4096, 1000);

    assertEquals(
        List.of(
            "docker",
            "run",
            "-d",
            "--name",
            "qits-buildkitd",
            "--network",
            "qits-net",
            "--network-alias",
            "qits-buildkitd",
            "--label",
            "qits.containers.buildkit.config" + "=cafe01",
            "--privileged",
            "--restart",
            "unless-stopped",
            "--cpus",
            "4",
            "--memory",
            "8g",
            "--memory-swap",
            "8g",
            "--pids-limit",
            "4096",
            "--oom-score-adj",
            "1000",
            "-v",
            "qits-buildkitd-state:/var/lib/buildkit",
            "-e",
            "BUILDKITD_TOML=[worker.oci]\n  gc = true\n",
            "--entrypoint",
            "/bin/sh",
            "moby/buildkit:v0.33.0",
            "-c",
            DockerArgv.BUILDKITD_BOOTSTRAP),
        argv);
  }

  @Test
  public void thePlatformBuilderGetsNoSwapWhateverTheMemoryBoundIs() {
    // --memory-swap equal to --memory is docker's spelling of "no swap at all", and it is the half
    // of this bound that keeps a build that reached the cap from thrashing the host instead of
    // dying. A value of its own here — or an absent flag, which lets docker default swap to twice
    // the memory — is the regression, so the two are asserted against each other rather than
    // against a literal.
    List<String> argv =
        DockerArgv.runBuildkitd(
            "docker", NS, "moby/buildkit:v0.33.0", "qits-net", "qits-buildkitd-state",
            "[worker.oci]\n  gc = true\n", "cafe01", "1.5", "12g", 4096, 500);

    assertEquals("12g", argv.get(argv.indexOf("--memory") + 1));
    assertEquals(
        argv.get(argv.indexOf("--memory") + 1),
        argv.get(argv.indexOf("--memory-swap") + 1),
        "memory-swap must be the memory bound, which is how swap is switched off");
    assertEquals("1.5", argv.get(argv.indexOf("--cpus") + 1), "a decimal survives the rendering");
  }

  @Test
  public void thePlatformBuilderBootstrapInterpolatesNothing() {
    // The toml travels as an environment value the shell reads; a word of it in the -c text would
    // be the interpolation the whole BOOTSTRAP idiom exists to rule out. What the text may hold is
    // the variable's NAME, exactly once, quoted — the printf that copies it into the file.
    assertEquals(1, DockerArgv.BUILDKITD_BOOTSTRAP.split("BUILDKITD_TOML", -1).length - 1);
    assertTrue(DockerArgv.BUILDKITD_BOOTSTRAP.contains("\"$BUILDKITD_TOML\""));
  }

  @Test
  public void privilegedAppearsInThePlatformBuilderArgvAndNowhereElse() {
    List<String> step =
        DockerArgv.run(
            "docker",
            NS,
            "qits-ct-x",
            ciStep().build(),
            Map.of(),
            LifecyclePolicy.ephemeral(Duration.ofHours(1)),
            null);
    assertFalse(step.contains("--privileged"), "no spec can express the builder's privilege");
  }

  @Test
  public void theStampInspectReadsTheConfigLabel() {
    assertEquals(
        List.of(
            "docker",
            "inspect",
            "--format",
            "{{index .Config.Labels \"" + "qits.containers.buildkit.config" + "\"}}",
            "qits-buildkitd"),
        DockerArgv.inspectBuildkitdStamp("docker", NS, "qits-buildkitd"));
  }

  @Test
  public void thePlatformBuilderIsAnAllowedExecTargetAndStrangersAreNot() {
    // The exec belt stays closed: the buildx prefix, the one platform constant, nothing else.
    assertEquals(
        "qits-buildkitd", ContainersIdentifiers.requireBuilderContainer("qits-buildkitd"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainersIdentifiers.requireBuilderContainer("qits-buildkitd-2"));
  }

  @Test
  public void aMountPathMayNotForgeAMountsOwnFields() {
    // `-v <volume>:<path>` is one argv element; a colon in either half would move the boundary and
    // could append a third field docker reads as a mode.
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContainerSpec.VolumeMount("v", "/work:ro"));
    assertThrows(
        IllegalArgumentException.class, () -> new ContainerSpec.VolumeMount("v", "relative/path"));
  }

  @Test
  public void aHostnameIsRefusedAsADnsServerAndLiteralsAreNot() {
    // No DNS lookup ever happens here: the belt is a shape check, so a hostname is refused rather
    // than resolved.
    for (String literal :
        List.of("8.8.8.8", "1.1.1.1", "::1", "2001:4860:4860::8888", "fe80::1", "::ffff:10.0.0.1")) {
      assertEquals(literal, ContainersIdentifiers.requireDnsServer(literal));
    }
    for (String hostname :
        List.of("resolver.example.com", "localhost", "dns", "8.8.8.8.", "not-an-address", "")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ContainersIdentifiers.requireDnsServer(hostname),
          "dns server accepted " + hostname);
    }
    assertThrows(
        IllegalArgumentException.class, () -> ContainersIdentifiers.requireDnsServer(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSpec.builder("img").network("qits-net").dns("resolver.example.com").build());
  }

  @Test
  public void anExtraNetworkMayNotRepeatTheNetworkOrAnotherExtraNetwork() {
    // A repeat of `network` would ask the driver to connect a container to the network its own
    // `docker run` already put it on; a repeat within extraNetworks would be the same join issued
    // twice for no membership gained. Both are refused rather than silently deduplicated.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContainerSpec.builder("img")
                .network("qits-net")
                .extraNetwork("qits-net")
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContainerSpec.builder("img")
                .network("qits-net")
                .extraNetwork("qits-build")
                .extraNetwork("qits-build")
                .build());
    // Two distinct extra networks, neither equal to the primary one, build without complaint.
    ContainerSpec spec =
        ContainerSpec.builder("img")
            .network("qits-net")
            .extraNetwork("qits-build")
            .extraNetwork("qits-agents")
            .build();
    assertEquals(List.of("qits-build", "qits-agents"), spec.extraNetworks());
  }

  @Test
  public void networkConnectRendersTheNetworkAndTheContainerName() {
    assertEquals(
        List.of("docker", "network", "connect", "qits-build", "qits-ct-x"),
        DockerArgv.networkConnect("docker", "qits-build", "qits-ct-x"));
  }

  // --- qits-system-runner-daemon's commands (qits-2289) ----------------------------------------
  //
  // Each expectation is the argv qits-system-runner-daemon renders today, copied from its tests
  // where they pin the list (BuildPlaneTest, LeaseNetworkTest, DockerOperationsTest,
  // LeaseLauncherCreateArgvTest) and from its source line otherwise (cited).

  private static final LabelNamespace SYSTEM_RUNNER = new LabelNamespace("qits.system.runner.");
  private static final String LEASE = "qits-system-runner-lease-l1-r1";
  private static final String OWNER_FILTER = "label=qits.system.runner=r1";

  private static java.util.SequencedMap<String, String> ordered(String... pairs) {
    java.util.SequencedMap<String, String> map = new java.util.LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return map;
  }

  /** {@code BuildPlaneTest} :76-78 and {@code LeaseNetworkTest} :72-82, the two bridges. */
  @Test
  public void theRunnersBridgesAreItsNetworkCreatesElementForElement() {
    assertEquals(
        List.of(
            "docker", "network", "create", "--driver", "bridge", "--label",
            "qits.system.runner=r1", "qits-system-runner-build-r1"),
        DockerArgv.bridgeNetworkCreate(
            "docker", ordered("qits.system.runner", "r1"), null, "qits-system-runner-build-r1"));
    assertEquals(
        List.of(
            "docker", "network", "create", "--driver", "bridge", "--label",
            "qits.system.runner=r1", "--opt", "com.docker.network.bridge.enable_icc=false",
            "qits-system-runner-leases-r1"),
        DockerArgv.bridgeNetworkCreate(
            "docker",
            ordered("qits.system.runner", "r1"),
            ordered("com.docker.network.bridge.enable_icc", "false"),
            "qits-system-runner-leases-r1"));
    // Labels then options, each in the caller's order rather than sorted.
    assertEquals(
        List.of(
            "docker", "network", "create", "--driver", "bridge", "--label", "z=1", "--label",
            "a=", "--opt", "y=2", "--opt", "b=3", "n"),
        DockerArgv.bridgeNetworkCreate(
            "docker", ordered("z", "1", "a", null), ordered("y", "2", "b", "3"), "n"));
  }

  @Test
  public void aBridgeCreateBeltsItsNameItsKeysAndItsValues() {
    refusedWith(
        "Invalid network",
        () -> DockerArgv.bridgeNetworkCreate("docker", null, null, "--internal"));
    refusedWith(
        "Invalid label key",
        () -> DockerArgv.bridgeNetworkCreate("docker", ordered("a=b", "v"), null, "n"));
    refusedWith(
        "Invalid label value",
        () -> DockerArgv.bridgeNetworkCreate("docker", ordered("a", "x\ny"), null, "n"));
    refusedWith(
        "Invalid network option",
        () -> DockerArgv.bridgeNetworkCreate("docker", null, ordered("-o", "v"), "n"));
    refusedWith(
        "Invalid network option",
        () -> DockerArgv.bridgeNetworkCreate("docker", null, ordered("k=v", "v"), "n"));
    refusedWith(
        "Invalid network option value",
        () -> DockerArgv.bridgeNetworkCreate("docker", null, ordered("k", "\u0000"), "n"));
  }

  /** {@code LeaseLauncherCreateArgvTest}'s spec, and its rewrite of the run argv. */
  @Test
  public void theLeaseCreateIsTheRunnersRewrittenRunArgvElementForElement() {
    ContainerSpec spec =
        ContainerSpec.builder("registry.example/ci-slot:1")
            .network("bridge")
            .dns("100.96.0.1", "10.255.255.254")
            .init(true)
            .env("TZ", "UTC")
            .build();
    Map<String, String> labels = Map.of("qits.system.runner.spec-hash", "h1");
    LifecyclePolicy lifecycle = LifecyclePolicy.explicitLifetime();

    List<String> create =
        DockerArgv.create("docker", SYSTEM_RUNNER, LEASE, spec, labels, lifecycle);

    assertEquals(
        List.of(
            "docker", "create", "--init", "--name", LEASE, "--network", "bridge",
            "--dns", "100.96.0.1", "--dns", "10.255.255.254",
            "--label", "qits.system.runner.spec-hash=h1",
            "--restart", "unless-stopped",
            "-e", "TZ=UTC",
            "registry.example/ci-slot:1"),
        create);
    // The runner's rewrite: [bin, "create", run[3..]].
    List<String> run = DockerArgv.run("docker", SYSTEM_RUNNER, LEASE, spec, labels, lifecycle);
    List<String> rewritten = new ArrayList<>(List.of("docker", "create"));
    rewritten.addAll(run.subList(3, run.size()));
    assertEquals(rewritten, create);
  }

  @Test
  public void createIsRunWithCreateForRunMinusDetachForEveryShape() {
    for (LifecyclePolicy policy :
        List.of(
            LifecyclePolicy.ephemeral(null),
            LifecyclePolicy.idleStop(Duration.ofHours(4)),
            LifecyclePolicy.explicitLifetime())) {
      List<String> run =
          DockerArgv.run("docker", NS, "qits-ci-abc-0", ciStep().build(), labels(), policy, "998");
      List<String> create =
          DockerArgv.create(
              "docker", NS, "qits-ci-abc-0", ciStep().build(), labels(), policy, "998");
      List<String> rewritten = new ArrayList<>(List.of("docker", "create"));
      rewritten.addAll(run.subList(3, run.size()));
      assertEquals(rewritten, create);
      assertTrue(create.contains("--group-add"), create.toString());
      assertFalse(create.contains("--rm"), create.toString());
      assertFalse(create.contains("-d"), create.toString());
    }
    assertEquals(
        render(ciStep().build(), LifecyclePolicy.explicitLifetime()).subList(3, 10),
        DockerArgv.create(
                "docker",
                NS,
                "qits-ci-abc-0",
                ciStep().build(),
                labels(),
                LifecyclePolicy.explicitLifetime())
            .subList(2, 9));
    // The run's belts hold for the create.
    refusedWith(
        "Invalid container name",
        () ->
            DockerArgv.create(
                "docker", NS, "-x", ciStep().build(), labels(), LifecyclePolicy.ephemeral(null)));
  }

  /**
   * {@code BuildPlaneTest} :615 / {@code BuildPlane.users} :534 — the template first;
   * {@code DockerOperationsTest} :165-175 is the existing filters-first overload, unchanged.
   */
  @Test
  public void theBuildNetworkListingIsTheRunnersPsElementForElement() {
    assertEquals(
        List.of(
            "docker", "ps", "--format", "{{.Names}}", "--filter",
            "network=qits-system-runner-build-r1"),
        DockerArgv.psFormatFirst(
            "docker", false, "{{.Names}}", List.of("network=qits-system-runner-build-r1")));
    assertEquals(
        List.of("docker", "ps", "-a", "--format", "{{.Names}}"),
        DockerArgv.psFormatFirst("docker", true, "{{.Names}}", null));
    assertEquals(
        List.of("docker", "ps", "-a", "--filter", OWNER_FILTER, "--format", "{{json .}}"),
        DockerArgv.ps("docker", true, List.of(OWNER_FILTER), "{{json .}}"));
    assertThrows(
        NullPointerException.class,
        () -> DockerArgv.psFormatFirst("docker", false, null, List.of()));
    refusedWith(
        "Invalid filter",
        () -> DockerArgv.psFormatFirst("docker", false, "{{.Names}}", List.of("a\nb")));
  }

  /**
   * {@code LeaseContainers.list} :240-249 ({@code -aq}, a presence-only label filter),
   * {@code DockerOperationsTest} :514-521 ({@code -a -q --no-trunc}) and qits-runner-javalib's
   * {@code LifecycleArgv.psAncestor} ({@code -a -q --filter ancestor=}).
   */
  @Test
  public void theRunnersQuietListingsAreItsPsElementForElement() {
    assertEquals(
        List.of(
            "docker", "ps", "-aq", "--filter", OWNER_FILTER, "--filter",
            "label=qits.system.runner.lease"),
        DockerArgv.psAllQuiet(
            "docker",
            List.of(OWNER_FILTER, SwarmArgv.labelFilter("qits.system.runner.lease"))));
    assertEquals(
        List.of("docker", "ps", "-a", "-q", "--no-trunc", "--filter", OWNER_FILTER),
        DockerArgv.psAllQuietSeparate("docker", true, List.of(OWNER_FILTER)));
    assertEquals(
        List.of("docker", "ps", "-a", "-q", "--filter", "ancestor=moby/buildkit:v0.33.0"),
        DockerArgv.psAllQuietSeparate(
            "docker", false, List.of("ancestor=moby/buildkit:v0.33.0")));
    assertEquals(
        List.of("docker", "ps", "-a", "-q", "--filter", "ancestor=sha256:" + "a".repeat(64)),
        DockerArgv.psAncestor("docker", "sha256:" + "a".repeat(64)));
    assertEquals(List.of("docker", "ps", "-aq"), DockerArgv.psAllQuiet("docker", null));
    refusedWith("Invalid filter", () -> DockerArgv.psAllQuiet("docker", List.of("a\rb")));
    refusedWith(
        "Invalid filter",
        () -> DockerArgv.psAllQuietSeparate("docker", true, java.util.Arrays.asList("x", null)));
    refusedWith("Invalid image", () -> DockerArgv.psAncestor("docker", "-q"));
  }

  /** {@code DockerOperationsTest} :505-523 and {@code TargetVolumes.inspect} :265. */
  @Test
  public void theRunnersVolumeReadsAreItsElementForElement() {
    assertEquals(
        List.of("docker", "volume", "ls", "--filter", OWNER_FILTER, "--format", "{{json .}}"),
        DockerArgv.volumeLs("docker", List.of(OWNER_FILTER), "{{json .}}"));
    assertEquals(
        List.of("docker", "volume", "ls", "--format", "{{json .}}"),
        DockerArgv.volumeLs("docker", List.of(), "{{json .}}"));
    assertEquals(
        List.of("docker", "volume", "ls", "--filter", OWNER_FILTER),
        DockerArgv.volumeLs("docker", List.of(OWNER_FILTER), null));
    assertEquals(
        List.of(
            "docker", "volume", "inspect", "--format", "{{.Name}}",
            "qits-system-runner-work-ws-42-r1"),
        DockerArgv.volumeInspect("docker", "qits-system-runner-work-ws-42-r1", "{{.Name}}"));
    refusedWith(
        "Invalid filter", () -> DockerArgv.volumeLs("docker", List.of("a\nb"), "{{json .}}"));
    refusedWith(
        "Invalid volume name", () -> DockerArgv.volumeInspect("docker", "a/b", "{{.Name}}"));
    assertThrows(
        NullPointerException.class, () -> DockerArgv.volumeInspect("docker", "v", null));
  }

  /** {@code DockerOperationsTest} :330-334, both shapes; {@code DockerOperations.logs} :229. */
  @Test
  public void theRunnersLogsAreItsElementForElement() {
    assertEquals(
        List.of("docker", "logs", "--tail", "5000", "--since", "10m", LEASE),
        DockerArgv.logsTailSince("docker", LEASE, 5000, "10m"));
    assertEquals(
        List.of("docker", "logs", "--tail", "42", "--since", "2026-10-26T10:00:00Z", LEASE),
        DockerArgv.logsTailSince("docker", LEASE, 42, "2026-10-26T10:00:00Z"));
    assertEquals(
        DockerArgv.logsTailByReference("docker", LEASE, 200),
        DockerArgv.logsTailSince("docker", LEASE, 200, null));
    assertEquals(
        List.of("docker", "logs", "--tail", "1", "--since", "2026-10-26T10:00:00+02:00", "C_1"),
        DockerArgv.logsTailSince("docker", "C_1", 1, "2026-10-26T10:00:00+02:00"));
  }

  @Test
  public void aLogsSinceIsTheCallersValueButNeverAnOptionOrALine() {
    refusedWith("Invalid since", () -> DockerArgv.logsTailSince("docker", LEASE, 5, "-10m"));
    refusedWith("Invalid since", () -> DockerArgv.logsTailSince("docker", LEASE, 5, "10m\n"));
    refusedWith("Invalid since", () -> DockerArgv.logsTailSince("docker", LEASE, 5, ""));
    refusedWith("Invalid log tail", () -> DockerArgv.logsTailSince("docker", LEASE, 0, "10m"));
    refusedWith(
        "Invalid container reference", () -> DockerArgv.logsTailSince("docker", "a b", 5, null));
  }

  /**
   * {@code BuildPlane.builder} :730, {@code LeaseContainers.inspectArgv} :172 and {@code .exists}
   * :211, {@code BuildPlane.imagePresent} :652.
   */
  @Test
  public void theRunnersFormattedInspectsAreItsElementForElement() {
    String stamp = "{{index .Config.Labels \"qits.system.runner.buildkitd\"}}|{{.State.Status}}";
    assertEquals(
        List.of(
            "docker", "inspect", "--type", "container", "--format", stamp,
            "qits-system-runner-buildkitd-r1"),
        DockerArgv.inspectTypeContainerFormat("docker", "qits-system-runner-buildkitd-r1", stamp));
    assertEquals(
        List.of("docker", "inspect", "--type", "container", "--format", "{{.Id}}", LEASE),
        DockerArgv.inspectTypeContainerFormat("docker", LEASE, "{{.Id}}"));
    assertEquals(
        List.of("docker", "image", "inspect", "--format", "{{.Id}}", "moby/buildkit:v0.33.0"),
        DockerArgv.imageInspectFormat("docker", "moby/buildkit:v0.33.0", "{{.Id}}"));
    // The short -f spellings stay qits-bootstrap-cli's.
    assertEquals(
        List.of("docker", "inspect", "--type", "container", "-f", "{{.Id}}", LEASE),
        DockerArgv.inspectTypeContainer("docker", LEASE, "{{.Id}}"));
    refusedWith(
        "Invalid container reference",
        () -> DockerArgv.inspectTypeContainerFormat("docker", "-x", "{{.Id}}"));
    refusedWith(
        "Invalid image", () -> DockerArgv.imageInspectFormat("docker", "a b", "{{.Id}}"));
    assertThrows(
        NullPointerException.class,
        () -> DockerArgv.inspectTypeContainerFormat("docker", LEASE, null));
  }

  /** {@code DockerOperationsTest} :314 (one) and :522 (the owned ids, in one call). */
  @Test
  public void theRunnersWholeInspectsAreItsElementForElement() {
    assertEquals(
        List.of("docker", "inspect", "--type", "container", LEASE),
        DockerArgv.inspectTypeContainer("docker", List.of(LEASE)));
    String id = "a".repeat(64);
    assertEquals(
        List.of("docker", "inspect", "--type", "container", id, "Legacy_Name.1"),
        DockerArgv.inspectTypeContainer("docker", List.of(id, "Legacy_Name.1")));
    refusedWith(
        "Invalid inspect: no container named",
        () -> DockerArgv.inspectTypeContainer("docker", List.<String>of()));
    refusedWith(
        "Invalid inspect: no container named",
        () -> DockerArgv.inspectTypeContainer("docker", (List<String>) null));
    refusedWith(
        "Invalid container reference",
        () -> DockerArgv.inspectTypeContainer("docker", List.of(LEASE, "--size")));
  }

  /** {@code DockerOperationsTest} :451. */
  @Test
  public void theRunnersStopIsItsElementForElement() {
    assertEquals(
        List.of("docker", "stop", "-t", "10", LEASE),
        DockerArgv.stopByReference("docker", LEASE, 10));
    assertEquals(
        List.of("docker", "stop", "-t", "0", "My_Container"),
        DockerArgv.stopByReference("docker", "My_Container", 0));
    refusedWith(
        "Invalid stop timeout", () -> DockerArgv.stopByReference("docker", LEASE, -1));
    refusedWith(
        "Invalid container reference", () -> DockerArgv.stopByReference("docker", "a/b", 10));
    // The name form is unchanged.
    assertEquals(List.of("docker", "stop", "c"), DockerArgv.stop("docker", "c"));
  }

  @Test
  public void nothingTheRunnersBuildersRenderCarriesRmOrExec() {
    List<List<String>> everything =
        List.of(
            DockerArgv.create(
                "docker", NS, "c", ciStep().build(), labels(), LifecyclePolicy.ephemeral(null)),
            DockerArgv.bridgeNetworkCreate("docker", ordered("k", "v"), ordered("o", "p"), "n"),
            DockerArgv.psFormatFirst("docker", true, "{{.Names}}", List.of("x=y")),
            DockerArgv.psAllQuiet("docker", List.of("x=y")),
            DockerArgv.psAllQuietSeparate("docker", true, List.of("x=y")),
            DockerArgv.psAncestor("docker", "alpine"),
            DockerArgv.volumeLs("docker", List.of("x=y"), "{{json .}}"),
            DockerArgv.volumeInspect("docker", "v", "{{.Name}}"),
            DockerArgv.logsTailSince("docker", "c", 5, "10m"),
            DockerArgv.inspectTypeContainerFormat("docker", "c", "{{.Id}}"),
            DockerArgv.imageInspectFormat("docker", "alpine", "{{.Id}}"),
            DockerArgv.inspectTypeContainer("docker", List.of("c")),
            DockerArgv.stopByReference("docker", "c", 10));
    for (List<String> argv : everything) {
      assertFalse(argv.contains("--rm"), argv.toString());
      assertFalse(argv.contains("exec"), argv.toString());
    }
  }

  private static void refusedWith(String message, Runnable call) {
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, call::run);
    assertTrue(refused.getMessage().startsWith(message), refused.getMessage());
  }

  // --- the namespace is the embedder's -------------------------------------------------------

  /** A second embedder's namespace — a runner on the same host as CS. */
  private static final LabelNamespace RUNNER = new LabelNamespace("qits.workspaces.runner.");

  private static List<String> labelsOf(List<String> argv) {
    List<String> rendered = new ArrayList<>();
    for (int i = 0; i < argv.size() - 1; i++) {
      if ("--label".equals(argv.get(i))) {
        rendered.add(argv.get(i + 1));
      }
    }
    return rendered;
  }

  @Test
  public void aRunnerNamespaceRendersItsOwnLabelsAndNoneOfCss() {
    // The safety property: a runner's containers and volumes carry ITS namespace, so CS's volume
    // sweep (qits.containers.managed=volume) can never read one of them as its own.
    List<String> run =
        DockerArgv.run(
            "docker",
            RUNNER,
            "qits-ci-abc-0",
            ciStep().build(),
            RUNNER.forContainer("qits-ci", "step", "run-1", "row-1", "instance-1"),
            LifecyclePolicy.ephemeral(null));
    assertEquals(
        List.of(
            "qits.ci.run=run-1",
            "qits.workspaces.runner.instance=instance-1",
            "qits.workspaces.runner.managed=container",
            "qits.workspaces.runner.owner=qits-ci",
            "qits.workspaces.runner.ref=run-1",
            "qits.workspaces.runner.row=row-1",
            "qits.workspaces.runner.workload=step"),
        labelsOf(run));

    assertEquals(
        List.of(
            "docker",
            "volume",
            "create",
            "--label",
            "qits.workspaces.runner.managed=volume",
            "--label",
            "qits.workspaces.runner.owner=qits-ci",
            "qits-ws-1"),
        DockerArgv.volumeCreate(
            "docker", RUNNER, new VolumeSpec("qits-ws-1"), RUNNER.forOwnerVolume("qits-ci")));
  }

  @Test
  public void anOwnerLabelInsideTheDriversNamespaceIsRefusedAndOneOutsideItIsNot() {
    // An owner label is refused when it sits inside the namespace the driver was HANDED — whichever
    // that is — and passes when it sits in somebody else's.
    ContainerSpec forging =
        ContainerSpec.builder("img")
            .network("qits-net")
            .label("qits.workspaces.runner.managed", "volume")
            .build();
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DockerArgv.run(
                    "docker",
                    RUNNER,
                    "qits-ct-x",
                    forging,
                    Map.of(),
                    LifecyclePolicy.ephemeral(null)));
    assertEquals(
        "Invalid label key (the qits.workspaces.runner.namespace is this service's):"
            + " 'qits.workspaces.runner.managed'",
        refused.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DockerArgv.volumeCreate(
                "docker",
                RUNNER,
                new VolumeSpec("v", Map.of("QITS.WORKSPACES.RUNNER.owner", "x")),
                Map.of()));

    // The same label is just an owner's bookkeeping to CS.
    assertTrue(
        labelsOf(
                DockerArgv.run(
                    "docker", NS, "qits-ct-x", forging, Map.of(), LifecyclePolicy.ephemeral(null)))
            .contains("qits.workspaces.runner.managed=volume"));
  }

  @Test
  public void theBuilderStampLabelFollowsTheNamespace() {
    List<String> argv =
        DockerArgv.runBuildkitd(
            "docker", RUNNER, "moby/buildkit:v0.33.0", "qits-net", "qits-buildkitd-state",
            "", "cafe01", "4", "8g", 4096, 1000);
    assertEquals(List.of("qits.workspaces.runner.buildkit.config=cafe01"), labelsOf(argv));
    assertEquals(
        List.of(
            "docker",
            "inspect",
            "--format",
            "{{index .Config.Labels \"qits.workspaces.runner.buildkit.config\"}}",
            "qits-buildkitd"),
        DockerArgv.inspectBuildkitdStamp("docker", RUNNER, "qits-buildkitd"));
    assertEquals("qits.containers.buildkit.config", DockerArgv.buildkitdStampLabel(NS));
  }

  @Test
  public void waitIsTheBareCommandOnABeltedName() {
    // qits-deployments-service's DockerDatabaseContainers, byte for byte: `docker wait <name>`.
    assertEquals(
        List.of("docker", "wait", "qits-pd-db-migrate-0123abcd"),
        DockerArgv.wait("docker", "qits-pd-db-migrate-0123abcd"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.wait("docker", "-rm"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.wait("docker", null));
  }

  @Test
  public void volumeLsByNameAnchorsTheName() {
    // SwarmDeploymentDriverTest's literal, element for element.
    assertEquals(
        List.of("docker", "volume", "ls", "-q", "--filter", "name=^qits-edge-letsencrypt$"),
        DockerArgv.volumeLsByName("docker", "qits-edge-letsencrypt"));
    assertEquals(
        List.of("docker", "volume", "ls", "-q", "--filter", "name=^qits_shared_m2$"),
        DockerArgv.volumeLsByName("docker", "qits_shared_m2"));
    for (String hostile : new String[] {null, "", "-x", "a,b", "a b", "a$"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> DockerArgv.volumeLsByName("docker", hostile),
          String.valueOf(hostile));
    }
  }

  // --- qits-runner-javalib's runner toolkit (qits-2302) ------------------------------------------

  @Test
  public void inspectWithNoFormatIsTheRunnersWholeContainerDocument() {
    // LifecycleArgv.inspectSelf: no --format at all, so the answer is the daemon's own document
    // rather than a template's narrowing of it (a Go template refuses to render a key the
    // container's shape omits).
    assertEquals(List.of("docker", "inspect", "c"), DockerArgv.inspect("docker", "c"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.inspect("docker", "-x"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.inspect("docker", "a\nb"));
  }

  @Test
  public void imageInspectWithNoFormatIsTheRunnersImageConfig() {
    // LifecycleArgv.imageConfig: the image the runner's own container was started from, whole.
    assertEquals(
        List.of("docker", "image", "inspect", "alpine:3"),
        DockerArgv.imageInspect("docker", "alpine:3"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.imageInspect("docker", "-x"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.imageInspect("docker", "a\nb"));
  }

  @Test
  public void updateRestartNoTakesTheRestartPolicyOff() {
    // LifecycleArgv.noRestart: the retiring runner's own step out of the way of its successor.
    assertEquals(
        List.of("docker", "update", "--restart=no", "c"), DockerArgv.updateRestartNo("docker", "c"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.updateRestartNo("docker", "-x"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.updateRestartNo("docker", "a\nb"));
  }

  @Test
  public void updateMemoryRaisesTheCapAndSwapInPlace() {
    // qits-system-runner-daemon BuildPlane.raiseInPlace (qits-2503): copied from that runner's
    // BuildPlaneTest.aKeptStaleBuilderUnderALowerCapGetsTheConfiguredOneInPlace.
    assertEquals(
        List.of(
            "docker",
            "update",
            "--memory",
            "8589934592",
            "--memory-swap",
            "17179869184",
            "qits-buildkitd"),
        DockerArgv.updateMemory("docker", "qits-buildkitd", 8589934592L, 17179869184L));
    // -1 is docker's unlimited swap; a swap equal to the memory is "no swap" and allowed.
    assertEquals(
        List.of("docker", "update", "--memory", "1024", "--memory-swap", "-1", "c"),
        DockerArgv.updateMemory("docker", "c", 1024, -1));
    assertEquals(
        List.of("docker", "update", "--memory", "1024", "--memory-swap", "1024", "c"),
        DockerArgv.updateMemory("docker", "c", 1024, 1024));

    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.updateMemory("docker", "c", 0, 1024));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.updateMemory("docker", "c", -1, -1));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.updateMemory("docker", "c", 1024, 1023));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.updateMemory("docker", "c", 1024, 0));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.updateMemory("docker", "c", 1024, -2));
    assertThrows(
        IllegalArgumentException.class,
        () -> DockerArgv.updateMemory("docker", "-x", 1024, 2048));
    assertThrows(
        IllegalArgumentException.class,
        () -> DockerArgv.updateMemory("docker", "a\nb", 1024, 2048));
  }

  @Test
  public void imagePruneDanglingIsTheSanctionedExceptionAndNeverCarriesAll() {
    // DanglingImagesPass: the one sanctioned image prune in this file, and it can never widen to
    // -a — only the dangling set, never every image no container happens to be using right now.
    List<String> argv = DockerArgv.imagePruneDangling("docker");
    assertEquals(List.of("docker", "image", "prune", "-f"), argv);
    assertFalse(argv.contains("-a"), argv.toString());
  }
}
