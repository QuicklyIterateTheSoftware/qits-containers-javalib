package eu.wohlben.qits.containers.driver.swarm;

import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import java.util.Set;

/**
 * The belts on the values a swarm service argv carries — {@link ContainersIdentifiers}' discipline,
 * for the {@code docker service} and overlay-network vocabulary.
 *
 * <p><b>Each belt is lifted from the embedder that renders the value today, and never narrower.</b>
 * qits-deployments-service (dep) hand-built these argvs and checked some of their values at the last
 * line before the argv; those checks are what is here, regex for regex, so a value dep renders today
 * passes here unchanged. Where dep's own check is a closed policy list (the capabilities and sysctls
 * a repository may ask for), the policy stays the embedder's and the belt here is the wider charset
 * that keeps the value one argv element — an embedder's allowlist must always sit inside it.
 *
 * <p><b>What is deliberately not belted</b>: service, task, network and container references, label
 * and environment strings, and resolver addresses. dep renders those as it reads them — off the
 * daemon, out of its own configuration, or composed from values checked where they were stored — and
 * a belt here that refused one would turn docker's own "no such service" answer, which every caller
 * handles, into an exception none of them expects. Those are refused only when {@code null}, which
 * is what {@code List.of} already did to them.
 *
 * <p>Refusals have the one shape {@code Invalid <what>: '<value>'}, the value echoed {@link
 * ContainersIdentifiers#safely safely}, so a refusal names the field the way every other belt in
 * this jar does.
 */
public final class SwarmIdentifiers {

  /**
   * A volume name as it reaches {@code --mount source=}: a derived private one, or one of the
   * platform's shared volumes, spelled with underscores ({@code qits_shared_m2}). dep's {@code
   * DeploymentIdentifiers.VOLUME_SOURCE}. No comma and no {@code =}, the two characters that would
   * forge a field of the mount it is spliced into.
   */
  private static final String MOUNT_SOURCE = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

  /**
   * Where a volume is mounted in the container: absolute, and never the bare root. dep's {@code
   * DeploymentIdentifiers.MOUNT_TARGET}.
   */
  private static final String MOUNT_TARGET = "(?:/[A-Za-z0-9._-]+)+";

  /** dep's {@code DeploymentIdentifiers.MOUNT_TARGET_MAX_CHARS}. */
  public static final int MOUNT_TARGET_MAX_CHARS = 255;

  /** How long a {@code --health-cmd} may be. dep's {@code DeploymentIdentifiers.HEALTH_CMD_MAX_CHARS}. */
  public static final int HEALTH_CMD_MAX_CHARS = 512;

  /**
   * A capability name, with or without docker's {@code CAP_} prefix. The charset only — which
   * capabilities may be granted is the embedder's policy (dep's is {@code NET_ADMIN} alone).
   */
  private static final String CAPABILITY = "[A-Z][A-Z0-9_]{0,63}";

  /**
   * One {@code <name>=<value>} kernel parameter. The charset only — which ones may be set is the
   * embedder's policy (dep's three all turn forwarding off).
   */
  private static final String SYSCTL = "[a-z0-9][a-z0-9._/-]{0,127}=[A-Za-z0-9._:/-]{1,128}";

  /** A supplementary group, name or gid. dep's {@code ServiceExtras.group} charset. */
  private static final String GROUP = "[A-Za-z0-9_-]+";

  private static final Set<String> RESTART_CONDITIONS = Set.of("none", "on-failure", "any");

  private static final Set<String> UPDATE_ORDERS = Set.of("start-first", "stop-first");

  private static final Set<String> FAILURE_ACTIONS = Set.of("pause", "continue", "rollback");

  private static final Set<String> MOUNT_TYPES = Set.of("volume", "bind");

  private static final Set<String> PROTOCOLS = Set.of("tcp", "udp");

  private static final Set<String> PUBLISH_MODES = Set.of("host", "ingress");

  private SwarmIdentifiers() {}

  /** A named volume as a {@code --mount source=} — see {@link #MOUNT_SOURCE}. */
  public static String requireMountSource(String source) {
    if (source == null || !source.matches(MOUNT_SOURCE)) {
      throw refuse("mount source", source);
    }
    return source;
  }

  /** A {@code --mount target=} — an absolute container path, never {@code /} alone. */
  public static String requireMountTarget(String target) {
    if (target == null || target.length() > MOUNT_TARGET_MAX_CHARS || !target.matches(MOUNT_TARGET)) {
      throw refuse("mount target", target);
    }
    return target;
  }

  /**
   * A {@code --health-cmd}. <b>No charset</b>, dep's {@code requireHealthCmd} reasoning: it is a shell
   * command docker hands to {@code /bin/sh -c} inside the container, and one argv element that is
   * never re-split. What it cannot be is blank, longer than {@link #HEALTH_CMD_MAX_CHARS}, or more
   * than one line.
   */
  public static String requireHealthCmd(String command) {
    if (command == null
        || command.isBlank()
        || command.length() > HEALTH_CMD_MAX_CHARS
        || command.chars().anyMatch(Character::isISOControl)) {
      throw refuse("health command", command);
    }
    return command;
  }

  /** A {@code --cap-add} value — the charset, not the policy. */
  public static String requireCapability(String capability) {
    if (capability == null || !capability.matches(CAPABILITY)) {
      throw refuse("capability", capability);
    }
    return capability;
  }

  /** A {@code --sysctl}/{@code --sysctl-add} pair — the charset, not the policy. */
  public static String requireSysctl(String sysctl) {
    if (sysctl == null || !sysctl.matches(SYSCTL)) {
      throw refuse("sysctl", sysctl);
    }
    return sysctl;
  }

  /** A {@code --group} value, a group name or a gid. */
  public static String requireGroup(String group) {
    if (group == null || !group.matches(GROUP)) {
      throw refuse("group", group);
    }
    return group;
  }

  /**
   * A network alias inside a {@code name=<net>,alias=<a>} attachment: dep's {@code
   * ServiceExtras.alias}. A comma would forge a second field of the attachment and whitespace makes a
   * name nothing resolves; everything else is the daemon's to judge.
   */
  public static String requireAttachmentAlias(String alias) {
    if (alias == null
        || alias.isEmpty()
        || alias.indexOf(',') >= 0
        || alias.chars().anyMatch(Character::isWhitespace)) {
      throw refuse("network alias", alias);
    }
    return alias;
  }

  /** {@code --restart-condition}: {@code none}, {@code on-failure} or {@code any}. */
  public static String requireRestartCondition(String condition) {
    return oneOf(condition, RESTART_CONDITIONS, "restart condition");
  }

  /** {@code --update-order} / {@code --rollback-order}: {@code start-first} or {@code stop-first}. */
  public static String requireUpdateOrder(String order) {
    return oneOf(order, UPDATE_ORDERS, "update order");
  }

  /** {@code --update-failure-action}: {@code pause}, {@code continue} or {@code rollback}. */
  public static String requireFailureAction(String action) {
    return oneOf(action, FAILURE_ACTIONS, "update failure action");
  }

  /** A {@code --mount type=}: {@code volume} or {@code bind}. */
  public static String requireMountType(String type) {
    return oneOf(type, MOUNT_TYPES, "mount type");
  }

  /** A {@code --publish protocol=}: {@code tcp} or {@code udp}. */
  public static String requireProtocol(String protocol) {
    return oneOf(protocol, PROTOCOLS, "publish protocol");
  }

  /** A {@code --publish mode=}: {@code host} or {@code ingress}. */
  public static String requirePublishMode(String mode) {
    return oneOf(mode, PUBLISH_MODES, "publish mode");
  }

  /** A port on either side of a {@code --publish}: 1 to 65535, dep's {@code ServiceExtras.port}. */
  public static int requirePort(int port) {
    if (port < 1 || port > 65535) {
      throw refuse("port", String.valueOf(port));
    }
    return port;
  }

  private static String oneOf(String value, Set<String> allowed, String what) {
    if (value == null || !allowed.contains(value)) {
      throw refuse(what, value);
    }
    return value;
  }

  private static IllegalArgumentException refuse(String what, String value) {
    return new IllegalArgumentException(
        "Invalid " + what + ": " + ContainersIdentifiers.safely(value));
  }
}
