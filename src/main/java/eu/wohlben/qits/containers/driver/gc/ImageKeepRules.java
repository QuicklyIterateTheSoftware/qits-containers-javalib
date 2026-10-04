package eu.wohlben.qits.containers.driver.gc;

import eu.wohlben.qits.containers.driver.ContainersDriver.ImageSummary;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The image store's keep rules, as a pure decision: four reasons to keep, checked in order, with
 * removal as the fall-through. The embedder reads the host and its own records; this class only
 * decides, so every embedder decides identically.
 *
 * <p>The rules, in order, each one a keep:
 *
 * <ol>
 *   <li>{@link Keep#IN_USE} — a container references it, running or not.
 *   <li>{@link Keep#PROTECTED} — the embedder's own records name it (CS: a live {@code
 *       ct_container} row, reported {@code live-row}).
 *   <li>{@link Keep#PINNED} — a reference in {@code pins} names it, or a tag matches one of {@code
 *       pinPrefixes}.
 *   <li>{@link Keep#TOO_YOUNG} — created after {@code youngest}. It protects an image a CI step has
 *       built and not yet pushed, which nothing else can see.
 * </ol>
 *
 * <p>Everything else is removed, {@link Removal#DANGLING} (no tags) or {@link Removal#UNPINNED}.
 *
 * <p><b>Matching is deliberately generous in the keeping direction.</b> A reference matches an
 * image when it equals one of its tags, when either side is the other with a registry host in
 * front, or when it carries a hex id (at least twelve digits) this image's id starts with. Every one
 * of those tolerances can only move an image from the removed list to the kept one.
 */
public final class ImageKeepRules {

  /** Why an image is kept: the first rule that spoke for it. */
  public enum Keep {
    IN_USE,
    PROTECTED,
    PINNED,
    TOO_YOUNG
  }

  /** Why an image nothing spoke for is removed. */
  public enum Removal {
    DANGLING,
    UNPINNED
  }

  /**
   * What the embedder read before deciding.
   *
   * @param inUse every image reference a container (running or not) names
   * @param protectedRefs every image reference the embedder's own records name
   * @param pins exact references, already {@link #clean cleaned}: a tag, a tag with a registry host
   *     in front of it, or an id
   * @param pinPrefixes the same, matched as a prefix of a tag or of the part after a {@code /}
   * @param youngest the instant an image must have been created after to be kept {@link
   *     Keep#TOO_YOUNG} — {@code now - minAge}, or {@code null} when the min-age is null, zero or
   *     negative (which protects nothing by age)
   */
  public record Inputs(
      Set<String> inUse,
      Set<String> protectedRefs,
      List<String> pins,
      List<String> pinPrefixes,
      Instant youngest) {}

  /** How a hex image id appears inside a reference, whichever shape docker printed it in. */
  private static final Pattern HEX_ID = Pattern.compile("(?:^|@|sha256:)([0-9a-f]{12,64})$");

  /** The shortest id prefix a match may be made on. Docker's own short form is twelve. */
  private static final int SHORT_ID = 12;

  private ImageKeepRules() {}

  /** The first rule that says keep, or empty for an image nothing spoke for. */
  public static Optional<Keep> whyKeep(ImageSummary image, Inputs in) {
    if (referencedByAny(image, in.inUse())) {
      return Optional.of(Keep.IN_USE);
    }
    if (referencedByAny(image, in.protectedRefs())) {
      return Optional.of(Keep.PROTECTED);
    }
    if (pinned(image, in.pins(), in.pinPrefixes())) {
      return Optional.of(Keep.PINNED);
    }
    Instant youngest = in.youngest();
    if (youngest != null && image.createdAt() != null && image.createdAt().isAfter(youngest)) {
      return Optional.of(Keep.TOO_YOUNG);
    }
    return Optional.empty();
  }

  /** Why an image nothing kept is removed: no {@code repository:tag} names it, or none was pinned. */
  public static Removal removal(ImageSummary image) {
    return image.tags().isEmpty() ? Removal.DANGLING : Removal.UNPINNED;
  }

  /** Whether any of these references names this image. */
  private static boolean referencedByAny(ImageSummary image, Set<String> references) {
    for (String reference : references) {
      if (references(image, reference)) {
        return true;
      }
    }
    return false;
  }

  /**
   * One reference against one image: a tag, a tag under a registry host, or a hex id prefix.
   *
   * <p>The two suffix forms are both here because a reference and a local tag disagree about the
   * registry host in either direction — a row asking for {@code qits/qits-ci:sha} names an image
   * tagged {@code registry:8080/qits/qits-ci:sha}, and a container created from the long form names
   * one tagged with the short.
   */
  public static boolean references(ImageSummary image, String reference) {
    String ref = reference == null ? "" : reference.strip();
    if (ref.isEmpty()) {
      return false;
    }
    for (String tag : image.tags()) {
      if (tag.equals(ref) || tag.endsWith("/" + ref) || ref.endsWith("/" + tag)) {
        return true;
      }
    }
    return idMatches(image.id(), ref);
  }

  /** Whether a reference carries a hex id this image's id starts with. */
  private static boolean idMatches(String id, String reference) {
    Matcher matcher = HEX_ID.matcher(reference.toLowerCase(Locale.ROOT));
    if (!matcher.find()) {
      return false;
    }
    String hex = matcher.group(1);
    String own = hexOf(id);
    return hex.length() >= SHORT_ID && !own.isEmpty() && own.startsWith(hex);
  }

  /** An id with any {@code sha256:} in front of it taken off. */
  private static String hexOf(String id) {
    String value = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
    int colon = value.lastIndexOf(':');
    return colon < 0 ? value : value.substring(colon + 1);
  }

  /**
   * Whether the caller pinned this image.
   *
   * <p>An exact entry matches a tag equal to it or ending in {@code /} plus it, and a {@code
   * sha256:} entry matches the id. A prefix entry matches a tag that starts with it, or a tag whose
   * part after any {@code /} does — which is what lets {@code qits/build-images/} pin everything
   * under a registry host nobody wants to spell.
   */
  public static boolean pinned(ImageSummary image, List<String> keep, List<String> keepPrefixes) {
    for (String entry : keep) {
      if (references(image, entry)) {
        return true;
      }
    }
    for (String prefix : keepPrefixes) {
      for (String tag : image.tags()) {
        if (tag.startsWith(prefix) || tag.contains("/" + prefix)) {
          return true;
        }
      }
    }
    return false;
  }

  /** A caller's list, with the nulls and the blanks it may have sent taken out. */
  public static List<String> clean(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .filter(Objects::nonNull)
        .map(String::strip)
        .filter(v -> !v.isEmpty())
        .toList();
  }
}
