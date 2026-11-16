package eu.wohlben.qits.containers.driver.docker;

import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import java.util.regex.Pattern;

/**
 * The belts on the values only an embedder's own host commands carry — a host path, a {@code
 * --config} directory, a {@code --since}, a publish, a resource bound — beside {@link
 * ContainersIdentifiers}, which keeps the ones every argv shares. Package-private: they guard argv
 * elements {@link DockerArgv}, {@link SwarmArgv}, {@link ComposeArgv} and {@link HostRunArgv}
 * render, and a caller has no reason to call them.
 *
 * <p>Same refusal shape as {@link ContainersIdentifiers}: {@code Invalid <what>: '<value>'}, the
 * value echoed {@link ContainersIdentifiers#safely safely}.
 */
final class HostArgvBelts {

  /** How long a host path may be: PATH_MAX on Linux. */
  static final int PATH_MAX = 4096;

  /** A short value with no shape of its own (an entrypoint, a security option, a timestamp). */
  private static final int WORD_MAX = 256;

  private static final Pattern MEMORY = Pattern.compile("[0-9]+[bkmgBKMG]?");
  private static final Pattern DIGITS = Pattern.compile("[0-9]+");
  private static final Pattern CPUSET = Pattern.compile("[0-9][0-9,-]*");
  private static final Pattern CAPABILITY = Pattern.compile("[A-Z][A-Z_]*");
  private static final Pattern RESTART = Pattern.compile("no|always|unless-stopped|on-failure(:[0-9]+)?");
  private static final Pattern PUBLISH = Pattern.compile("[0-9A-Za-z\\[][0-9A-Za-z.:\\[\\]/-]*");
  private static final Pattern USER = Pattern.compile("[a-z0-9_][a-z0-9_-]*(:[a-z0-9_][a-z0-9_-]*)?");
  private static final Pattern OPTION_KEY = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]*");
  private static final Pattern MOUNT_MODE = Pattern.compile("[a-z][a-z,]*");

  private HostArgvBelts() {}

  /**
   * A path on the host the docker CLI reads or writes — a {@code cp} side, a {@code load -i}
   * archive, a build context, a {@code --config} directory. Not blank, no control character, and no
   * leading {@code -}. A {@code :} is allowed only on a path that starts with {@code /} or {@code
   * .}: that is docker's own rule for telling a local path from {@code <container>:<path>} in a
   * {@code cp}, so anything else carrying one would be read as a container.
   */
  static String requireHostPath(String what, String path) {
    if (path == null
        || path.isBlank()
        || path.length() > PATH_MAX
        || path.startsWith("-")
        || path.chars().anyMatch(Character::isISOControl)
        || (path.contains(":") && !path.startsWith("/") && !path.startsWith("."))) {
      throw refuse(what, path);
    }
    return path;
  }

  /** An absolute path with no control character — a {@code -w} or one side of a bind. */
  static String requireAbsolutePath(String what, String path) {
    if (path == null
        || !path.startsWith("/")
        || path.length() > PATH_MAX
        || path.chars().anyMatch(Character::isISOControl)) {
      throw refuse(what, path);
    }
    return path;
  }

  /**
   * One word handed to an option — not blank, no whitespace, no control character, no leading
   * {@code -}, so it cannot be read as an option of its own or split into two.
   */
  static String requireWord(String what, String value) {
    if (value == null
        || value.isEmpty()
        || value.length() > WORD_MAX
        || value.startsWith("-")
        || value.chars().anyMatch(Character::isWhitespace)
        || value.chars().anyMatch(Character::isISOControl)) {
      throw refuse(what, value);
    }
    return value;
  }

  /**
   * A value handed to an option verbatim and parsed by docker — a {@code --since} — that is not one
   * word by shape: not empty, no control character, no leading {@code -}, so it stays the option's
   * value and one element.
   */
  static String requireValue(String what, String value) {
    if (value == null
        || value.isEmpty()
        || value.length() > WORD_MAX
        || value.startsWith("-")
        || value.chars().anyMatch(Character::isISOControl)) {
      throw refuse(what, value);
    }
    return value;
  }

  /**
   * The key of a {@code key=value} option — a network {@code --opt}: dotted segments, the label-key
   * charset, so neither the {@code =} that splits the element nor a leading {@code -} can be in it.
   */
  static String requireOptionKey(String what, String key) {
    return matching(what, key, OPTION_KEY);
  }

  /** A value with no control character — a label value; the newline is what would forge a line. */
  static String requireNoControl(String what, String value) {
    if (value == null || value.chars().anyMatch(Character::isISOControl)) {
      throw refuse(what, value);
    }
    return value;
  }

  /**
   * One {@code -v}: {@code <source>:<container path>[:<mode>]}, where the source is an absolute host
   * path or a {@link ContainersIdentifiers#requireVolumeName volume name}. Split on {@code :}, so a
   * host path carrying one is refused — docker would split it there too.
   */
  static String requireMount(String mount) {
    if (mount == null) {
      throw refuse("mount", null);
    }
    String[] parts = mount.split(":", -1);
    if (parts.length < 2 || parts.length > 3 || mount.chars().anyMatch(Character::isISOControl)) {
      throw refuse("mount", mount);
    }
    String source = parts[0];
    if (source.startsWith("/")) {
      requireAbsolutePath("mount", source);
    } else {
      try {
        ContainersIdentifiers.requireVolumeName(source);
      } catch (IllegalArgumentException e) {
        throw refuse("mount", mount);
      }
    }
    try {
      ContainersIdentifiers.requireContainerPath(parts[1]);
    } catch (IllegalArgumentException e) {
      throw refuse("mount", mount);
    }
    if (parts.length == 3 && !MOUNT_MODE.matcher(parts[2]).matches()) {
      throw refuse("mount", mount);
    }
    return mount;
  }

  static String requireMemory(String memory) {
    return matching("memory", memory, MEMORY);
  }

  static String requireCpuQuota(String quota) {
    return matching("cpu quota", quota, DIGITS);
  }

  static String requireCpuset(String cpus) {
    return matching("cpuset", cpus, CPUSET);
  }

  static String requireCapability(String capability) {
    return matching("capability", capability, CAPABILITY);
  }

  static String requireRestart(String policy) {
    return matching("restart policy", policy, RESTART);
  }

  static String requirePublish(String publish) {
    return matching("publish", publish, PUBLISH);
  }

  /**
   * {@code --user}: a name or uid, and optionally {@code :<group>}. Wider than {@link
   * ContainersIdentifiers#requireUser} by exactly the group: that belt guards a spec an owner sent,
   * where a group nobody declared is the risk; this one guards the embedder's own command, which
   * runs as the person who started it ({@code 1000:1000}).
   */
  static String requireUserAndGroup(String user) {
    return matching("user", user, USER);
  }

  static String requireOomScoreAdj(String score) {
    try {
      int value = Integer.parseInt(score);
      if (value >= -1000 && value <= 1000) {
        return score;
      }
    } catch (NumberFormatException ignored) {
      // refused below
    }
    throw refuse("oom score adj", score);
  }

  private static String matching(String what, String value, Pattern pattern) {
    if (value == null || value.length() > WORD_MAX || !pattern.matcher(value).matches()) {
      throw refuse(what, value);
    }
    return value;
  }

  static IllegalArgumentException refuse(String what, String value) {
    return new IllegalArgumentException(
        "Invalid " + what + ": " + ContainersIdentifiers.safely(value));
  }
}
