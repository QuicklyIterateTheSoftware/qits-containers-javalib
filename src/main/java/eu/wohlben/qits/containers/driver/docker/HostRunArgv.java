package eu.wohlben.qits.containers.driver.docker;

import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.driver.spec.HostRunSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * The containers an embedder runs on its own host outside any registry — {@code docker run -d},
 * {@code docker run --rm} and {@code docker create} from a {@link HostRunSpec} — as a pure function,
 * {@link DockerArgv}'s discipline: a spec in, a {@code List<String>} out, asserted element for
 * element in a docker-free suite. qits-bootstrap-cli is what it was cut to (qits-2212); every argv
 * that program renders by hand is reproduced here element for element ({@code HostRunArgvTest}
 * copies its expectations from that program's tests and source).
 *
 * <p><b>This class is the second sanctioned exception to "no {@code --rm}"</b>, beside {@link
 * InteractiveArgv}, under the same owner decision (2026-11-13: every component that talks to docker
 * runs on this library). What it renders with {@code --rm} is a one-shot helper — a probe, a {@code
 * buildctl build}, a file written onto a volume, an {@code nsenter} into the host — or the attached
 * payload run whose exit code is its caller's, and in both the teardown IS docker's {@code --rm}:
 * nothing adopts them and no registry row names them. {@link DockerArgv}, {@code ContainersDriver}
 * and {@code ContainerSpec} still render no {@code --rm}; a third exception is a decision, not a
 * refactor.
 *
 * <p>The argv is
 *
 * <pre>
 * run -d --name N   OPTION* IMAGE ARGS...
 * run --rm [--name N] OPTION* IMAGE ARGS...
 * create [--name N] OPTION* IMAGE ARGS...
 * </pre>
 *
 * with the options in the order the caller added them ({@link HostRunSpec} says why). Belted on the
 * way out: the name ({@link ContainersIdentifiers#requireReference docker's own name charset} — a
 * name like {@code qits-system-runner-7c1d2f0e-2026.1116.1} is not a DNS label), the image, every
 * network, mount, label key and value, environment key, user, group and working directory, the
 * restart policy, the capability, the resource bounds, the publish and every single-word value. The
 * environment values and the args are the caller's — masking a secret among them is the caller's
 * too, and nothing here logs an argv.
 */
public final class HostRunArgv {

  private HostRunArgv() {}

  /** The whole argv — see the class javadoc for its shape. */
  public static List<String> render(String runtimeBinary, HostRunSpec spec) {
    List<String> argv = new ArrayList<>();
    argv.add(runtimeBinary);
    switch (spec.mode()) {
      case DETACHED -> {
        argv.add("run");
        argv.add("-d");
      }
      case REMOVE -> {
        argv.add("run");
        argv.add("--rm");
      }
      case CREATE -> argv.add("create");
    }
    if (spec.name() != null) {
      argv.add("--name");
      argv.add(ContainersIdentifiers.requireReference(spec.name()));
    }
    for (HostRunSpec.Option option : spec.options()) {
      option(argv, option);
    }
    argv.add(ContainersIdentifiers.requireImage(spec.image()));
    argv.addAll(spec.args());
    return List.copyOf(argv);
  }

  private static void option(List<String> argv, HostRunSpec.Option option) {
    String value = option.value();
    switch (option.kind()) {
      case INTERACTIVE -> argv.add("-i");
      case INTERACTIVE_TTY -> argv.add("-it");
      case PRIVILEGED -> argv.add("--privileged");
      case PID_HOST -> argv.add("--pid=host");
      case NETWORK -> pair(argv, "--network", ContainersIdentifiers.requireNetwork(value));
      case LABEL ->
          pair(
              argv,
              "--label",
              ContainersIdentifiers.requireExtraLabelKey(option.key())
                  + "="
                  + HostArgvBelts.requireNoControl("label value", value));
      case RESTART -> pair(argv, "--restart", HostArgvBelts.requireRestart(value));
      case CAP_DROP -> pair(argv, "--cap-drop", HostArgvBelts.requireCapability(value));
      case SECURITY_OPT ->
          pair(argv, "--security-opt", HostArgvBelts.requireWord("security option", value));
      case PUBLISH -> pair(argv, "-p", HostArgvBelts.requirePublish(value));
      case MEMORY -> pair(argv, "--memory", HostArgvBelts.requireMemory(value));
      case CPU_QUOTA -> pair(argv, "--cpu-quota", HostArgvBelts.requireCpuQuota(value));
      case CPUSET_CPUS -> pair(argv, "--cpuset-cpus", HostArgvBelts.requireCpuset(value));
      case OOM_SCORE_ADJ -> pair(argv, "--oom-score-adj", HostArgvBelts.requireOomScoreAdj(value));
      case VOLUME -> pair(argv, "-v", HostArgvBelts.requireMount(value));
      case ENV ->
          pair(
              argv,
              "-e",
              ContainersIdentifiers.requireEnvKey(option.key()) + "=" + (value == null ? "" : value));
      case ENV_NAME -> pair(argv, "-e", ContainersIdentifiers.requireEnvKey(value));
      case ENV_FILE -> pair(argv, "--env-file", HostArgvBelts.requireHostPath("env file", value));
      case USER -> pair(argv, "--user", HostArgvBelts.requireUserAndGroup(value));
      case GROUP_ADD -> pair(argv, "--group-add", ContainersIdentifiers.requireGroup(value));
      case WORKDIR -> pair(argv, "-w", HostArgvBelts.requireAbsolutePath("working directory", value));
      case ENTRYPOINT -> pair(argv, "--entrypoint", HostArgvBelts.requireWord("entrypoint", value));
    }
  }

  private static void pair(List<String> argv, String flag, String value) {
    argv.add(flag);
    argv.add(value);
  }
}
