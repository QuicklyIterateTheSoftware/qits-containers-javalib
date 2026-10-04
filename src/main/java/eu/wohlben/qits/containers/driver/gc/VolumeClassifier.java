package eu.wohlben.qits.containers.driver.gc;

import eu.wohlben.qits.containers.driver.ContainersDriver.VolumeDetail;
import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Which class a dangling volume is in, as a pure decision — only three classes are removable.
 *
 * <p>The embedder lists the dangling volumes, inspects each one and says whether its own records
 * claim it; this class decides. The classes, checked in this order:
 *
 * <table>
 *   <caption>Verdicts, and the reason string qits-containers-service reports for each</caption>
 *   <tr><th>Verdict</th><th>CS reason</th><th>Removable</th><th>When</th></tr>
 *   <tr><td>{@link Verdict#VANISHED}</td><td>{@code vanished}</td><td>no</td>
 *       <td>the inspect found nothing: it went between the listing and the inspect</td></tr>
 *   <tr><td>{@link Verdict#CLAIMED}</td><td>{@code live-row}</td><td>no</td>
 *       <td>managed under this namespace, and the embedder's records claim it</td></tr>
 *   <tr><td>{@link Verdict#TOO_YOUNG}</td><td>{@code too-young}</td><td>no</td>
 *       <td>managed-unclaimed or anonymous, created after {@code youngest}</td></tr>
 *   <tr><td>{@link Verdict#MANAGED_UNCLAIMED}</td><td>{@code managed-no-row}</td><td>yes</td>
 *       <td>managed under this namespace, and nothing claims it</td></tr>
 *   <tr><td>{@link Verdict#BUILDX_LIVE}</td><td>{@code buildx-live}</td><td>no</td>
 *       <td>a buildx state volume a builder container still references</td></tr>
 *   <tr><td>{@link Verdict#BUILDX_STATE}</td><td>{@code buildx-state}</td><td>yes</td>
 *       <td>a buildx state volume no builder container references</td></tr>
 *   <tr><td>{@link Verdict#ANONYMOUS}</td><td>{@code anonymous}</td><td>yes</td>
 *       <td>a bare 64-hex name docker made for a container that named none</td></tr>
 *   <tr><td>{@link Verdict#UNMANAGED}</td><td>{@code unmanaged}</td><td>no</td>
 *       <td>everything else — including a volume managed under <b>another</b> namespace</td></tr>
 * </table>
 *
 * <p><b>"Managed" is this namespace's {@code managed} label equal to {@value
 * LabelNamespace#MANAGED_VOLUME}, and nothing else.</b> A volume another embedder labelled under
 * its own namespace is {@link Verdict#UNMANAGED} here: that is the safety property that keeps one
 * embedder's sweep off another's volumes.
 */
public final class VolumeClassifier {

  /** What was decided about one dangling volume. */
  public enum Verdict {
    VANISHED,
    CLAIMED,
    TOO_YOUNG,
    MANAGED_UNCLAIMED,
    BUILDX_LIVE,
    BUILDX_STATE,
    ANONYMOUS,
    UNMANAGED;

    /** Whether this is one of the three classes that may be removed. */
    public boolean removable() {
      return this == MANAGED_UNCLAIMED || this == BUILDX_STATE || this == ANONYMOUS;
    }
  }

  /** A builder's state volume: the builder container's name with {@code _state} after it. */
  private static final Pattern BUILDX_STATE_NAME =
      Pattern.compile("^" + Pattern.quote(ContainersIdentifiers.BUILDER_PREFIX) + ".+_state$");

  /** What docker names a volume it made for a container that named none. */
  private static final Pattern ANONYMOUS_NAME = Pattern.compile("^[0-9a-f]{64}$");

  private VolumeClassifier() {}

  /**
   * Which class this volume is in — the removable ones first, {@link Verdict#UNMANAGED} as the
   * fall-through.
   *
   * @param name the volume's name, from the dangling listing
   * @param detail the volume's inspect; empty when docker no longer has it
   * @param claimed whether the embedder's records claim it
   * @param youngest the instant a managed or anonymous volume must have been created after to be
   *     kept {@link Verdict#TOO_YOUNG}; {@code null} protects none by age
   * @param namespace the embedder's own label namespace
   * @param holdersOf the containers referencing a volume — asked only for a buildx state volume, and
   *     expected to throw rather than degrade (a builder that could not be asked about keeps its
   *     cache); its throw propagates
   */
  public static Verdict classify(
      String name,
      Optional<VolumeDetail> detail,
      boolean claimed,
      Instant youngest,
      LabelNamespace namespace,
      Function<String, List<String>> holdersOf) {
    if (detail.isEmpty()) {
      return Verdict.VANISHED;
    }
    VolumeDetail volume = detail.get();
    if (LabelNamespace.MANAGED_VOLUME.equals(volume.labels().get(namespace.managed()))) {
      if (claimed) {
        return Verdict.CLAIMED;
      }
      return tooYoung(volume, youngest) ? Verdict.TOO_YOUNG : Verdict.MANAGED_UNCLAIMED;
    }
    if (BUILDX_STATE_NAME.matcher(name).matches()) {
      List<String> holders = holdersOf.apply(name);
      boolean builderHolds =
          holders.stream()
              .anyMatch(holder -> holder.startsWith(ContainersIdentifiers.BUILDER_PREFIX));
      return builderHolds ? Verdict.BUILDX_LIVE : Verdict.BUILDX_STATE;
    }
    if (ANONYMOUS_NAME.matcher(name).matches()) {
      return tooYoung(volume, youngest) ? Verdict.TOO_YOUNG : Verdict.ANONYMOUS;
    }
    return Verdict.UNMANAGED;
  }

  /** Whether docker made it inside the caller's grace. A volume with no time reads as old. */
  private static boolean tooYoung(VolumeDetail volume, Instant youngest) {
    return youngest != null && volume.createdAt() != null && volume.createdAt().isAfter(youngest);
  }
}
