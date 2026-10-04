package eu.wohlben.qits.containers.driver.spec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The one label namespace an embedder of this driver reads and writes — chosen by the embedder,
 * never by the driver.
 *
 * <p><b>This is a safety setting, not a naming preference.</b> The labels under a namespace are
 * what that embedder's sweeps read to decide whether a container or a volume is its own:
 * qits-containers-service's volume collection removes every dangling volume labelled {@code
 * qits.containers.managed=volume} that none of its rows claims. A second embedder on the same host
 * that wrote its labels under {@code qits.containers.} would hand its volumes to that sweep. So
 * <b>never pass another embedder's namespace</b>: CS owns {@code qits.containers.}, a runner passes
 * its own (for example {@code qits.workspaces.runner.}).
 *
 * <p><b>The prefix belt</b> is {@code ^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+\.$}: at least two
 * segments and a trailing dot. Two segments, because {@code qits.} would swallow the callers' own
 * {@code qits.workspace} / {@code qits.project} labels and make every one of them a refusal; the
 * trailing dot, because {@code qits.containers} would otherwise also own {@code
 * qits.containersfoo}.
 *
 * <p><b>A label is never the registry.</b> These labels are how a container describes itself to a
 * person reading {@code docker ps} and how a listing narrows; whether a label licenses a removal is
 * the embedder's rule, not this type's.
 *
 * @param prefix the namespace, ending in a dot — {@code qits.containers.}
 */
public record LabelNamespace(String prefix) {

  private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+\\.");

  /** What follows the prefix in one of this namespace's keys: dotted lowercase segments. */
  private static final Pattern SUFFIX = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*");

  /** The value of {@link #managed()} on a container. */
  public static final String MANAGED_CONTAINER = "container";

  /** The value of {@link #managed()} on a volume. */
  public static final String MANAGED_VOLUME = "volume";

  public LabelNamespace {
    if (prefix == null || !PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException(
          "Invalid label namespace: " + ContainersIdentifiers.safely(prefix));
    }
  }

  /** One key of this namespace: the prefix, then a suffix belted to dotted lowercase segments. */
  public String key(String suffix) {
    if (suffix == null || !SUFFIX.matcher(suffix).matches()) {
      throw new IllegalArgumentException(
          "Invalid label suffix: " + ContainersIdentifiers.safely(suffix));
    }
    return prefix + suffix;
  }

  /** {@value #MANAGED_CONTAINER} or {@value #MANAGED_VOLUME} — what kind of thing this is. */
  public String managed() {
    return prefix + "managed";
  }

  /** Which platform module asked for it. */
  public String owner() {
    return prefix + "owner";
  }

  /** What kind of workload it is, in the owner's own words. */
  public String workload() {
    return prefix + "workload";
  }

  /** The owner's identifier for the thing this workload belongs to. Opaque to the driver. */
  public String ref() {
    return prefix + "ref";
  }

  /** The embedder's record that named this container <b>before</b> it was started. */
  public String row() {
    return prefix + "row";
  }

  /**
   * Which run of the embedder started it — a diagnostic, never a filter a sweep acts on: "started by
   * an earlier instance" must not be readable as "removable".
   */
  public String instance() {
    return prefix + "instance";
  }

  /**
   * The full label set of one container, with every value belt-checked. Insertion order is the
   * declaration order; {@code DockerArgv} sorts before rendering, so nothing downstream depends on
   * it.
   */
  public Map<String, String> forContainer(
      String owner, String workload, String ref, String rowId, String instanceId) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put(managed(), MANAGED_CONTAINER);
    labels.put(owner(), ContainersIdentifiers.requireOwner(owner));
    labels.put(workload(), ContainersIdentifiers.requireWorkload(workload));
    labels.put(ref(), ContainersIdentifiers.requireRef(ref));
    labels.put(row(), ContainersIdentifiers.requireRef(rowId));
    labels.put(instance(), ContainersIdentifiers.requireRef(instanceId));
    return labels;
  }

  /**
   * The label set of a volume an owner asked for <b>by name</b>, with no workload behind it. Two
   * labels, because nothing mounts it yet and no ref names it; a listing narrowed on {@link
   * #managed()} alone finds it exactly as it finds a workload's own.
   */
  public Map<String, String> forOwnerVolume(String owner) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put(managed(), MANAGED_VOLUME);
    labels.put(owner(), ContainersIdentifiers.requireOwner(owner));
    return labels;
  }

  /**
   * The label set of one named volume. No row and no instance: a volume outlives the container that
   * mounted it and the process that made it, which is the whole reason it is a volume.
   */
  public Map<String, String> forVolume(String owner, String workload, String ref) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put(managed(), MANAGED_VOLUME);
    labels.put(owner(), ContainersIdentifiers.requireOwner(owner));
    labels.put(workload(), ContainersIdentifiers.requireWorkload(workload));
    labels.put(ref(), ContainersIdentifiers.requireRef(ref));
    return labels;
  }

  /** Whether a label key sits inside this namespace — case-insensitively, as docker keys are typed. */
  public boolean owns(String labelKey) {
    return labelKey != null && labelKey.toLowerCase(Locale.ROOT).startsWith(prefix);
  }

  /**
   * The owner-label belt: every key passes {@link ContainersIdentifiers#requireExtraLabelKey}'s
   * charset, and <b>none may sit inside this namespace</b>. An owner that could write one could
   * label somebody else's workload as its own, or its own as another owner's — the labels here are
   * what the embedder's sweeps read. Answers a sorted immutable copy, as {@link
   * ContainersIdentifiers#requireExtraLabels} does.
   *
   * @throws IllegalArgumentException {@code Invalid label key (the <prefix>namespace is this
   *     service's): '<key>'}
   */
  public Map<String, String> requireOwnerLabels(Map<String, String> extra) {
    if (extra == null || extra.isEmpty()) {
      return Map.of();
    }
    Map<String, String> checked = new TreeMap<>();
    extra.forEach(
        (k, v) -> {
          ContainersIdentifiers.requireExtraLabelKey(k);
          if (owns(k)) {
            throw new IllegalArgumentException(
                "Invalid label key (the "
                    + prefix
                    + "namespace is this service's): "
                    + ContainersIdentifiers.safely(k));
          }
          checked.put(k, v == null ? "" : v);
        });
    return Collections.unmodifiableMap(checked);
  }
}
