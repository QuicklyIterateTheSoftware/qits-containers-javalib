package eu.wohlben.qits.containers.driver.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.HostRunSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * qits-bootstrap-cli's hand-built {@code docker run -d}, {@code docker run --rm} and {@code docker
 * create} argvs, rendered from a {@link HostRunSpec}, element for element. Every expectation is
 * copied from that program's own tests where they pin the argv ({@code DockerBuildTest}, {@code
 * NpmSeedTest}, {@code ContainerRunTest}, {@code PipelinePhasesTest}) and from its source line
 * otherwise (cited per case), so that moving onto this jar is provably byte-identical (qits-2212).
 */
public class HostRunArgvTest {

  private static final String BUILDKIT = "moby/buildkit:v0.33.0";

  // --- detached: run -d --name ... ----------------------------------------------------------------

  /** {@code DockerBuildTest} :89-97 — {@code Docker.ensureBuildkitd}. */
  @Test
  public void theBootstrapBuilderIsTheCliBuildkitdElementForElement() {
    HostRunSpec spec =
        HostRunSpec.detached("qits-buildkitd", BUILDKIT)
            .privileged()
            .network("host")
            .restart("unless-stopped")
            .oomScoreAdj(500)
            .memory("9g")
            .cpuQuota(400000)
            .cpusetCpus("0-3")
            .volume("qits-buildkitd-state:/var/lib/buildkit")
            .args(
                "--addr",
                "unix:///run/buildkit/buildkitd.sock",
                "--addr",
                "tcp://0.0.0.0:1234",
                "--oci-worker-net=host")
            .build();

    assertEquals(
        List.of(
            "docker", "run", "-d", "--name", "qits-buildkitd",
            "--privileged", "--network", "host",
            "--restart", "unless-stopped",
            "--oom-score-adj", "500",
            "--memory", "9g", "--cpu-quota", "400000", "--cpuset-cpus", "0-3",
            "-v", "qits-buildkitd-state:/var/lib/buildkit",
            BUILDKIT,
            "--addr", "unix:///run/buildkit/buildkitd.sock",
            "--addr", "tcp://0.0.0.0:1234", "--oci-worker-net=host"),
        HostRunArgv.render("docker", spec));
  }

  private static final String RUNNER_ID = "7c1d2f0e-5a8b-4c3d-9e1f-0a2b3c4d5e6f";
  private static final String TG_PIN = "2026.1023.112622";
  private static final String TG_CONTAINER = "qits-system-runner-7c1d2f0e-" + TG_PIN;
  private static final String TG_IMAGE_REF =
      "registry.qits.qits-dev.eu/qits/qits-system-runner:" + TG_PIN;

  /**
   * {@code PipelinePhasesTest} :2569-2583 — {@code PipelinePhases.targetRunCommand}. The token by
   * NAME, and a container name that is not a DNS label (the version's dots).
   */
  @Test
  public void theTargetRunnerIsTheCliTargetRunCommandElementForElement() {
    HostRunSpec spec =
        HostRunSpec.detached(TG_CONTAINER, TG_IMAGE_REF)
            .restart("unless-stopped")
            .label("qits.system.runner.process", RUNNER_ID)
            .label("qits.system.runner.version", TG_PIN)
            .volume("/var/run/docker.sock:/var/run/docker.sock")
            .volume("qits-system-runner-state-7c1d2f0e:/var/lib/qits-system-runner")
            .env("QITS_SYSTEM_RUNNER_ID", RUNNER_ID)
            .env("QITS_SYSTEM_RUNNER_SLOTS", "3")
            .env("QITS_SYSTEM_RUNNER_URL", "https://system.qits.qits-dev.eu")
            .envName("QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN")
            .env("QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME", "qits_shared_dot_claude")
            .env("QITS_SYSTEM_RUNNER_M2_VOLUME", "qits_shared_m2")
            .env("QITS_SYSTEM_RUNNER_PNPM_VOLUME", "qits_shared_pnpm")
            .build();

    List<String> expected =
        new ArrayList<>(
            List.of(
                "docker", "run", "-d",
                "--name", TG_CONTAINER,
                "--restart", "unless-stopped",
                "--label", "qits.system.runner.process=" + RUNNER_ID,
                "--label", "qits.system.runner.version=" + TG_PIN,
                "-v", "/var/run/docker.sock:/var/run/docker.sock",
                "-v", "qits-system-runner-state-7c1d2f0e:/var/lib/qits-system-runner",
                "-e", "QITS_SYSTEM_RUNNER_ID=" + RUNNER_ID,
                "-e", "QITS_SYSTEM_RUNNER_SLOTS=3",
                "-e", "QITS_SYSTEM_RUNNER_URL=https://system.qits.qits-dev.eu",
                "-e", "QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN"));
    expected.addAll(
        List.of(
            "-e", "QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME=qits_shared_dot_claude",
            "-e", "QITS_SYSTEM_RUNNER_M2_VOLUME=qits_shared_m2",
            "-e", "QITS_SYSTEM_RUNNER_PNPM_VOLUME=qits_shared_pnpm"));
    expected.add(TG_IMAGE_REF);
    assertEquals(expected, HostRunArgv.render("docker", spec));
  }

  /** {@code SeedPhases} :3388-3399 — the seed postgres. */
  @Test
  public void theSeedPostgresIsTheCliRunElementForElement() {
    HostRunSpec spec =
        HostRunSpec.detached("prod-qits-oci-postgresql", "qits/oci-postgresql:latest")
            .network("qits-net")
            .volume("qits-oci-postgresql-data:/var/lib/postgresql")
            .env("POSTGRES_PASSWORD", "s3cr3t")
            .build();

    assertEquals(
        List.of(
            "docker", "run", "-d", "--name", "prod-qits-oci-postgresql",
            "--network", "qits-net",
            "-v", "qits-oci-postgresql-data:/var/lib/postgresql",
            "-e", "POSTGRES_PASSWORD=s3cr3t",
            "qits/oci-postgresql:latest"),
        HostRunArgv.render("docker", spec));
  }

  /** {@code HostLauncher} :188-192 — the progress supervisor. */
  @Test
  public void theProgressSupervisorIsTheCliRunElementForElement() {
    HostRunSpec spec =
        HostRunSpec.detached("qits-bootstrap-progress", "qits-bootstrap:0123456789ab")
            .restart("unless-stopped")
            .user("1000:1000")
            .volume("/home/dev/qits-qits:/home/dev/qits-qits")
            .workdir("/home/dev/qits-qits")
            .env("QITS_WEB_BIND", "true")
            .env("QITS_WEB_HOST", "0.0.0.0")
            .args("progress-supervisor", "--state", "/home/dev/qits-qits/.qits-bootstrap-progress.json")
            .build();

    assertEquals(
        List.of(
            "docker", "run", "-d", "--name", "qits-bootstrap-progress",
            "--restart", "unless-stopped", "--user", "1000:1000",
            "-v", "/home/dev/qits-qits:/home/dev/qits-qits",
            "-w", "/home/dev/qits-qits", "-e", "QITS_WEB_BIND=true", "-e", "QITS_WEB_HOST=0.0.0.0",
            "qits-bootstrap:0123456789ab", "progress-supervisor",
            "--state", "/home/dev/qits-qits/.qits-bootstrap-progress.json"),
        HostRunArgv.render("docker", spec));
  }

  /**
   * {@code BootstrapIngressLifecycle} :106-126 — the bootstrap edge, in its public TLS shape ({@code
   * -p} before {@code -v}) and its loopback one.
   */
  @Test
  public void theBootstrapEdgeIsTheCliRunInBothShapes() {
    HostRunSpec tls =
        edge()
            .publish("80:8080")
            .publish("443:8443")
            .volume("qits-edge-letsencrypt:/cert:ro")
            .envFile("/home/dev/qits-qits/.qits-bootstrap-edge.env")
            .args("bootstrap-edge")
            .build();
    HostRunSpec loopback =
        edge()
            .publish("127.0.0.1:8481:8080")
            .envFile("/home/dev/qits-qits/.qits-bootstrap-edge.env")
            .args("bootstrap-edge")
            .build();

    List<String> head =
        List.of(
            "docker", "run", "-d", "--name", "qits-bootstrap-edge",
            "--label", "qits.bootstrap.ingress=true",
            "--label", "qits.bootstrap.ingress.expires-at=1763300000",
            "--restart", "unless-stopped",
            "--network", "qits-net",
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges");
    List<String> tlsExpected = new ArrayList<>(head);
    tlsExpected.addAll(
        List.of(
            "-p", "80:8080", "-p", "443:8443", "-v", "qits-edge-letsencrypt:/cert:ro",
            "--env-file", "/home/dev/qits-qits/.qits-bootstrap-edge.env",
            "qits-bootstrap:0123456789ab", "bootstrap-edge"));
    List<String> loopbackExpected = new ArrayList<>(head);
    loopbackExpected.addAll(
        List.of(
            "-p", "127.0.0.1:8481:8080",
            "--env-file", "/home/dev/qits-qits/.qits-bootstrap-edge.env",
            "qits-bootstrap:0123456789ab", "bootstrap-edge"));
    assertEquals(tlsExpected, HostRunArgv.render("docker", tls));
    assertEquals(loopbackExpected, HostRunArgv.render("docker", loopback));
  }

  private static HostRunSpec.Builder edge() {
    return HostRunSpec.detached("qits-bootstrap-edge", "qits-bootstrap:0123456789ab")
        .label("qits.bootstrap.ingress", "true")
        .label("qits.bootstrap.ingress.expires-at", "1763300000")
        .restart("unless-stopped")
        .network("qits-net")
        .capDrop("ALL")
        .securityOpt("no-new-privileges");
  }

  // --- one-shot: run --rm ... ---------------------------------------------------------------------

  /** {@code ContainerRunTest} :47-63 — the payload run, attached, {@code -it} after the name. */
  @Test
  public void thePayloadRunIsTheCliContainerRunElementForElement() {
    HostRunSpec spec =
        HostRunSpec.removed("qits-bootstrap:0123456789ab")
            .name("qits-bootstrap-cli")
            .interactiveTty()
            .volume("/var/run/docker.sock:/var/run/docker.sock")
            .user("1000:1000")
            .groupAdd("988")
            .volume("/home/dev/qits-qits:/home/dev/qits-qits")
            .workdir("/home/dev/qits-qits")
            .env("HOME", "/tmp")
            .env("TZ", "Europe/Berlin")
            .env("QITS_IN_CONTAINER", "1")
            .env("QITS_WRAPPER_DIR", "/home/dev/qits-qits")
            .env("QITS_WEB_BIND", "false")
            .env("QITS_WEB_HOST", "0.0.0.0")
            .env("QITS_PROGRESS_FILE", "/home/dev/qits-qits/.qits-bootstrap-progress.json")
            .args("bootstrap")
            .build();

    assertEquals(
        List.of(
            "docker", "run", "--rm", "--name", "qits-bootstrap-cli",
            "-it",
            "-v", "/var/run/docker.sock:/var/run/docker.sock",
            "--user", "1000:1000",
            "--group-add", "988",
            "-v", "/home/dev/qits-qits:/home/dev/qits-qits",
            "-w", "/home/dev/qits-qits",
            "-e", "HOME=/tmp",
            "-e", "TZ=Europe/Berlin",
            "-e", "QITS_IN_CONTAINER=1",
            "-e", "QITS_WRAPPER_DIR=/home/dev/qits-qits",
            "-e", "QITS_WEB_BIND=false",
            "-e", "QITS_WEB_HOST=0.0.0.0",
            "-e", "QITS_PROGRESS_FILE=/home/dev/qits-qits/.qits-bootstrap-progress.json",
            "qits-bootstrap:0123456789ab", "bootstrap"),
        HostRunArgv.render("docker", spec));
  }

  /**
   * {@code ContainerRun.command} :128-142 — the pass-through variables by NAME after the valued
   * ones, and the one publish after them; no {@code -it} without a terminal.
   */
  @Test
  public void thePayloadRunPassesSecretsByNameAndPublishesLast() {
    HostRunSpec spec =
        HostRunSpec.removed("qits-bootstrap:0123456789ab")
            .name("qits-bootstrap-cli")
            .volume("/var/run/docker.sock:/var/run/docker.sock")
            .user("1000:1000")
            .groupAdd("988")
            .workdir("/home/dev/qits-qits")
            .env("HOME", "/tmp")
            .envName("QITS_DNS_HETZNER_TOKEN")
            .publish("127.0.0.1:8481:8481")
            .args("bootstrap")
            .build();

    assertEquals(
        List.of(
            "docker", "run", "--rm", "--name", "qits-bootstrap-cli",
            "-v", "/var/run/docker.sock:/var/run/docker.sock",
            "--user", "1000:1000", "--group-add", "988",
            "-w", "/home/dev/qits-qits",
            "-e", "HOME=/tmp", "-e", "QITS_DNS_HETZNER_TOKEN",
            "-p", "127.0.0.1:8481:8481",
            "qits-bootstrap:0123456789ab", "bootstrap"),
        HostRunArgv.render("docker", spec));
  }

  /** {@code DockerBuildTest} :300-314 — the buildctl build with a named context (paths filled in). */
  @Test
  public void theBuildctlBuildIsTheCliBuildkitBuildElementForElement() {
    HostRunSpec spec =
        HostRunSpec.removed(BUILDKIT)
            .network("host")
            .volume("/src/qits-ci:/ctx:ro")
            .volume("/tmp/qits-scratch/dockerfile-1:/dfdir:ro")
            .volume("/tmp/qits-scratch/image-2:/out")
            .volume("qits-maven-seed:/named/qits-m2:ro")
            .entrypoint("buildctl")
            .args(
                "--addr", "tcp://127.0.0.1:1234",
                "build", "--frontend", "dockerfile.v0",
                "--local", "context=/ctx",
                "--local", "dockerfile=/dfdir",
                "--opt", "filename=Dockerfile",
                "--local", "qits-m2=/named/qits-m2",
                "--opt", "context:qits-m2=local:qits-m2",
                "--output", "type=docker,name=qits/ci:latest,dest=/out/image.tar")
            .build();

    assertEquals(
        List.of(
            "docker", "run", "--rm", "--network", "host",
            "-v", "/src/qits-ci:/ctx:ro",
            "-v", "/tmp/qits-scratch/dockerfile-1:/dfdir:ro",
            "-v", "/tmp/qits-scratch/image-2:/out",
            "-v", "qits-maven-seed:/named/qits-m2:ro",
            "--entrypoint", "buildctl", BUILDKIT,
            "--addr", "tcp://127.0.0.1:1234",
            "build", "--frontend", "dockerfile.v0",
            "--local", "context=/ctx",
            "--local", "dockerfile=/dfdir",
            "--opt", "filename=Dockerfile",
            "--local", "qits-m2=/named/qits-m2",
            "--opt", "context:qits-m2=local:qits-m2",
            "--output", "type=docker,name=qits/ci:latest,dest=/out/image.tar"),
        HostRunArgv.render("docker", spec));
  }

  /** {@code DockerBuildTest} :366-368 and :411-413 — the layout volume's rm and cat one-shots. */
  @Test
  public void theLayoutOneShotsAreTheCliCommandsElementForElement() {
    assertEquals(
        List.of(
            "docker", "run", "--rm", "-v", "qits-oci-seed:/layouts", "--entrypoint", "rm",
            BUILDKIT, "-rf", "/layouts/workspace-base@1"),
        HostRunArgv.render(
            "docker",
            HostRunSpec.removed(BUILDKIT)
                .volume("qits-oci-seed:/layouts")
                .entrypoint("rm")
                .args("-rf", "/layouts/workspace-base@1")
                .build()));
    assertEquals(
        List.of(
            "docker", "run", "--rm", "-v", "qits-oci-seed:/layouts:ro", "--entrypoint", "cat",
            BUILDKIT, "/layouts/workspace-base@1/index.json"),
        HostRunArgv.render(
            "docker",
            HostRunSpec.removed(BUILDKIT)
                .volume("qits-oci-seed:/layouts:ro")
                .entrypoint("cat")
                .args("/layouts/workspace-base@1/index.json")
                .build()));
  }

  /**
   * {@code NpmSeedTest} :340-348 — the npm volume probe; {@code SeedPhases} :1222 renders the maven
   * one in the same shape.
   */
  @Test
  public void theVolumeProbesAreTheCliProbeCommandsElementForElement() {
    String script =
        "[ -f '/qits-npm/qits-ui-components-2026.1021.185351.tgz' ] && echo"
            + " 'qits-ui-components-2026.1021.185351.tgz'\n"
            + "[ -f '/qits-npm/qits-ci-golden-masters-2026.1026.163551.tgz' ] && echo"
            + " 'qits-ci-golden-masters-2026.1026.163551.tgz'\n"
            + "exit 0\n";
    assertEquals(
        List.of(
            "docker", "run", "--rm", "-v", "qits-npm-seed:/qits-npm:ro",
            "--entrypoint", "sh", "node:24-bookworm-slim", "-c", script),
        HostRunArgv.render(
            "docker",
            HostRunSpec.removed("node:24-bookworm-slim")
                .volume("qits-npm-seed:/qits-npm:ro")
                .entrypoint("sh")
                .args("-c", script)
                .build()));
    assertEquals(
        List.of(
            "docker", "run", "--rm", "-v", "qits-maven-seed:/repo:ro",
            "--entrypoint", "sh", "maven:3.9-eclipse-temurin-25", "-c", "exit 0\n"),
        HostRunArgv.render(
            "docker",
            HostRunSpec.removed("maven:3.9-eclipse-temurin-25")
                .volume("qits-maven-seed:/repo:ro")
                .entrypoint("sh")
                .args("-c", "exit 0\n")
                .build()));
  }

  /** {@code SeedPhases} :3879-3883 — the host-namespace helper, {@code --pid=host} joined. */
  @Test
  public void theNsenterHelperIsTheCliRunElementForElement() {
    assertEquals(
        List.of(
            "docker", "run", "--rm", "--privileged", "--pid=host",
            "--network", "host", "alpine:3",
            "nsenter", "-t", "1", "-m", "-n", "--",
            "sh", "-c", "echo ok"),
        HostRunArgv.render(
            "docker",
            HostRunSpec.removed("alpine:3")
                .privileged()
                .pidHost()
                .network("host")
                .args("nsenter", "-t", "1", "-m", "-n", "--", "sh", "-c", "echo ok")
                .build()));
  }

  /** {@code SeedPhases.dockerConfigWrite} :4208-4214 — stdin open with {@code -i}. */
  @Test
  public void theCredentialWriteKeepsStdinOpen() {
    String script =
        "cat > /cfg/config.json && chown 1001:0 /cfg/config.json && chmod 600 /cfg/config.json";
    assertEquals(
        List.of(
            "docker", "run", "--rm", "-i",
            "-v", "qits-ci-docker-config:/cfg",
            "--entrypoint", "sh", "alpine/git",
            "-c", script),
        HostRunArgv.render(
            "docker",
            HostRunSpec.removed("alpine/git")
                .interactive()
                .volume("qits-ci-docker-config:/cfg")
                .entrypoint("sh")
                .args("-c", script)
                .build()));
  }

  /**
   * {@code SeedPhases} :4465-4476 (certbot: {@code --network}, two volumes, five {@code -e}, then
   * the entrypoint) and {@code PipelinePhases} :2901-2904 (the runner's client clear: the entrypoint
   * BEFORE the volume).
   */
  @Test
  public void theEntrypointLandsWhereEachCliCallPutsIt() {
    assertEquals(
        List.of(
            "docker", "run", "--rm",
            "--network", "qits-net",
            "-v", "qits-edge-letsencrypt:/cert",
            "-v", "qits-edge-acme:/acme",
            "-e", "MODE=staging",
            "-e", "DIRECTORY=https://acme-staging-v02.api.letsencrypt.org/directory",
            "-e", "DOMAIN=qits-dev.eu",
            "-e", "EMAIL=ops@qits-dev.eu",
            "-e", "EDGE_URL=http://qits-edge:8081",
            "--entrypoint", "sh", "alpine/git", "-c", "certbot"),
        HostRunArgv.render(
            "docker",
            HostRunSpec.removed("alpine/git")
                .network("qits-net")
                .volume("qits-edge-letsencrypt:/cert")
                .volume("qits-edge-acme:/acme")
                .env("MODE", "staging")
                .env("DIRECTORY", "https://acme-staging-v02.api.letsencrypt.org/directory")
                .env("DOMAIN", "qits-dev.eu")
                .env("EMAIL", "ops@qits-dev.eu")
                .env("EDGE_URL", "http://qits-edge:8081")
                .entrypoint("sh")
                .args("-c", "certbot")
                .build()));
    assertEquals(
        List.of(
            "docker", "run", "--rm",
            "--entrypoint", "rm",
            "-v", "qits-system-runner-state-7c1d2f0e:/var/lib/qits-system-runner", TG_IMAGE_REF,
            "-f", "/var/lib/qits-system-runner/client.json"),
        HostRunArgv.render(
            "docker",
            HostRunSpec.removed(TG_IMAGE_REF)
                .entrypoint("rm")
                .volume("qits-system-runner-state-7c1d2f0e:/var/lib/qits-system-runner")
                .args("-f", "/var/lib/qits-system-runner/client.json")
                .build()));
  }

  // --- create ... ---------------------------------------------------------------------------------

  /**
   * {@code SeedPhases.create} :4736-4748 inserts the temporary label right after {@code create};
   * the callers' shapes follow — :1751-1756 (entrypoint before the bounds) and {@code
   * storeSeedCommand} :3143-3145 (the network first).
   */
  @Test
  public void theSeedCreatesAreTheCliCommandsElementForElement() {
    assertEquals(
        List.of(
            "docker", "create", "--label", "qits.bootstrap.temporary=true",
            "--user", "root", "--entrypoint", "bash",
            "--memory", "6g", "--cpu-quota", "400000", "--cpuset-cpus", "0-3",
            "-v", "qits-maven-cache:/cache", "qits/graalvmce-musl-builder:jdk-25",
            "-c", "cd /qits-build"),
        HostRunArgv.render(
            "docker",
            HostRunSpec.created("qits/graalvmce-musl-builder:jdk-25")
                .label("qits.bootstrap.temporary", "true")
                .user("root")
                .entrypoint("bash")
                .memory("6g")
                .cpuQuota(400000)
                .cpusetCpus("0-3")
                .volume("qits-maven-cache:/cache")
                .args("-c", "cd /qits-build")
                .build()));
    assertEquals(
        List.of(
            "docker", "create", "--label", "qits.bootstrap.temporary=true",
            "--network", "qits-net", "--user", "root",
            "--entrypoint", "sh", "-v", "qits-maven-seed:/repo:ro",
            "maven:3.9-eclipse-temurin-25", "-c", "upload"),
        HostRunArgv.render(
            "docker",
            HostRunSpec.created("maven:3.9-eclipse-temurin-25")
                .label("qits.bootstrap.temporary", "true")
                .network("qits-net")
                .user("root")
                .entrypoint("sh")
                .volume("qits-maven-seed:/repo:ro")
                .args("-c", "upload")
                .build()));
  }

  /** {@code SeedPhases} :2823 — {@code create <tag> /qits}, a container of a FROM-scratch image. */
  @Test
  public void aBareCreateIsTheImageAndItsArgument() {
    assertEquals(
        List.of("docker", "create", "qits/platform-access-binary:1", "/qits"),
        HostRunArgv.render(
            "docker", HostRunSpec.created("qits/platform-access-binary:1").args("/qits").build()));
  }

  // --- belts ------------------------------------------------------------------------------------

  @Test
  public void aDetachedContainerIsNamed() {
    assertThrows(NullPointerException.class, () -> HostRunSpec.detached(null, "alpine:3").build());
  }

  @Test
  public void everyCallerShapedElementIsBelted() {
    refused(HostRunSpec.removed("-alpine").build(), "Invalid image");
    refused(HostRunSpec.removed("alpine").name("-x").build(), "Invalid container reference");
    refused(HostRunSpec.removed("alpine").network("--privileged").build(), "Invalid network");
    refused(HostRunSpec.removed("alpine").volume("/host").build(), "Invalid mount");
    refused(HostRunSpec.removed("alpine").volume("vol:relative").build(), "Invalid mount");
    refused(HostRunSpec.removed("alpine").volume("a/b:/x").build(), "Invalid mount");
    refused(HostRunSpec.removed("alpine").volume("/h:/c:ro:x").build(), "Invalid mount");
    refused(HostRunSpec.removed("alpine").volume("/h:/c:R0").build(), "Invalid mount");
    refused(HostRunSpec.removed("alpine").label("a=b", "v").build(), "Invalid label key");
    refused(HostRunSpec.removed("alpine").label("a", "x\ny").build(), "Invalid label value");
    refused(HostRunSpec.removed("alpine").env("A-B", "v").build(), "Invalid environment key");
    refused(HostRunSpec.removed("alpine").envName("A=B").build(), "Invalid environment key");
    refused(HostRunSpec.removed("alpine").restart("sometimes").build(), "Invalid restart policy");
    refused(HostRunSpec.removed("alpine").capDrop("all").build(), "Invalid capability");
    refused(HostRunSpec.removed("alpine").securityOpt("-x").build(), "Invalid security option");
    refused(HostRunSpec.removed("alpine").publish("-p").build(), "Invalid publish");
    refused(HostRunSpec.removed("alpine").publish("80 :80").build(), "Invalid publish");
    refused(HostRunSpec.removed("alpine").memory("9 g").build(), "Invalid memory");
    refused(HostRunSpec.removed("alpine").cpuQuota(-1).build(), "Invalid cpu quota");
    refused(HostRunSpec.removed("alpine").cpusetCpus("-0").build(), "Invalid cpuset");
    refused(HostRunSpec.removed("alpine").oomScoreAdj(1001).build(), "Invalid oom score adj");
    refused(HostRunSpec.removed("alpine").envFile("-f").build(), "Invalid env file");
    refused(HostRunSpec.removed("alpine").user("root:-x").build(), "Invalid user");
    refused(HostRunSpec.removed("alpine").groupAdd("-1").build(), "Invalid group");
    refused(HostRunSpec.removed("alpine").workdir("relative").build(), "Invalid working directory");
    refused(HostRunSpec.removed("alpine").entrypoint("sh -c").build(), "Invalid entrypoint");
  }

  @Test
  public void aLegalValueOfEveryKindPasses() {
    List<String> argv =
        HostRunArgv.render(
            "docker",
            HostRunSpec.removed("alpine")
                .restart("on-failure:3")
                .publish("[::1]:80:80/tcp")
                .memory("512m")
                .oomScoreAdj(-1000)
                .user("root")
                .volume("/a/b:/c:ro,z")
                .build());
    assertTrue(argv.contains("on-failure:3"), argv.toString());
    assertTrue(argv.contains("[::1]:80:80/tcp"), argv.toString());
    assertTrue(argv.contains("-1000"), argv.toString());
  }

  private static void refused(HostRunSpec spec, String message) {
    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> HostRunArgv.render("docker", spec));
    assertTrue(refused.getMessage().startsWith(message), refused.getMessage());
  }
}
