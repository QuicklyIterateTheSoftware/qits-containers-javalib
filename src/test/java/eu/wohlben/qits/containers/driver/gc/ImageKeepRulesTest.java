package eu.wohlben.qits.containers.driver.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.ContainersDriver.ImageSummary;
import eu.wohlben.qits.containers.driver.gc.ImageKeepRules.Inputs;
import eu.wohlben.qits.containers.driver.gc.ImageKeepRules.Keep;
import eu.wohlben.qits.containers.driver.gc.ImageKeepRules.Removal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The keep rules, their order, and the generous matching they rest on — the same cases CS's {@code
 * CtImageGcTest} drives through a whole sweep, here against the pure decision.
 */
class ImageKeepRulesTest {

  private static final String HEX =
      "4f2a9c1e7b3d5a6f8e0c2b4d6f8a0c2e4b6d8f0a2c4e6b8d0f2a4c6e8b0d2f4a";
  private static final String ID = "sha256:" + HEX;
  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
  private static final Instant OLD = NOW.minusSeconds(86_400 * 30);

  private static final ImageSummary CI =
      new ImageSummary(ID, List.of("registry:8080/qits/qits-ci:cafe"), 100, OLD);
  private static final ImageSummary DANGLER = new ImageSummary(ID, List.of(), 50, OLD);

  private static Inputs inputs(
      Set<String> inUse, Set<String> protectedRefs, List<String> pins, List<String> prefixes) {
    return new Inputs(inUse, protectedRefs, pins, prefixes, null);
  }

  private static Inputs nothing() {
    return inputs(Set.of(), Set.of(), List.of(), List.of());
  }

  @Test
  void anImageAContainerNamesIsKeptInUse() {
    assertEquals(
        Optional.of(Keep.IN_USE),
        ImageKeepRules.whyKeep(CI, inputs(Set.of("qits/qits-ci:cafe"), Set.of(), List.of(), List.of())));
  }

  @Test
  void anImageTheEmbedderProtectsIsKeptProtected() {
    assertEquals(
        Optional.of(Keep.PROTECTED),
        ImageKeepRules.whyKeep(
            CI, inputs(Set.of(), Set.of("qits/qits-ci:cafe"), List.of(), List.of())));
  }

  @Test
  void aPinnedTagIsKeptPinned() {
    assertEquals(
        Optional.of(Keep.PINNED),
        ImageKeepRules.whyKeep(
            CI, inputs(Set.of(), Set.of(), List.of("qits/qits-ci:cafe"), List.of())));
  }

  @Test
  void aPinPrefixKeepsTheImagePinned() {
    assertEquals(
        Optional.of(Keep.PINNED),
        ImageKeepRules.whyKeep(CI, inputs(Set.of(), Set.of(), List.of(), List.of("qits/qits-ci:"))));
  }

  @Test
  void anImageCreatedAfterYoungestIsKeptTooYoung() {
    ImageSummary fresh = new ImageSummary(ID, List.of(), 1, NOW);
    Inputs in = new Inputs(Set.of(), Set.of(), List.of(), List.of(), NOW.minusSeconds(3600));
    assertEquals(Optional.of(Keep.TOO_YOUNG), ImageKeepRules.whyKeep(fresh, in));
  }

  @Test
  void anImageCreatedBeforeYoungestIsNotProtectedByAge() {
    Inputs in = new Inputs(Set.of(), Set.of(), List.of(), List.of(), NOW.minusSeconds(3600));
    assertEquals(Optional.empty(), ImageKeepRules.whyKeep(DANGLER, in));
  }

  @Test
  void aNullYoungestProtectsNothingByAge() {
    ImageSummary fresh = new ImageSummary(ID, List.of(), 1, NOW);
    assertEquals(Optional.empty(), ImageKeepRules.whyKeep(fresh, nothing()));
  }

  @Test
  void anImageWithNoCreationTimeReadsAsOld() {
    ImageSummary timeless = new ImageSummary(ID, List.of(), 1, null);
    Inputs in = new Inputs(Set.of(), Set.of(), List.of(), List.of(), NOW);
    assertEquals(Optional.empty(), ImageKeepRules.whyKeep(timeless, in));
  }

  @Test
  void theFirstRuleThatSpeaksWins() {
    ImageSummary fresh = new ImageSummary(ID, List.of("qits/qits-ci:cafe"), 1, NOW);
    Set<String> ref = Set.of("qits/qits-ci:cafe");
    List<String> pin = List.of("qits/qits-ci:cafe");
    Instant young = NOW.minusSeconds(3600);
    assertEquals(
        Optional.of(Keep.IN_USE),
        ImageKeepRules.whyKeep(fresh, new Inputs(ref, ref, pin, List.of(), young)));
    assertEquals(
        Optional.of(Keep.PROTECTED),
        ImageKeepRules.whyKeep(fresh, new Inputs(Set.of(), ref, pin, List.of(), young)));
    assertEquals(
        Optional.of(Keep.PINNED),
        ImageKeepRules.whyKeep(fresh, new Inputs(Set.of(), Set.of(), pin, List.of(), young)));
    assertEquals(
        Optional.of(Keep.TOO_YOUNG),
        ImageKeepRules.whyKeep(fresh, new Inputs(Set.of(), Set.of(), List.of(), List.of(), young)));
  }

  @Test
  void nothingSpeakingMeansRemovalDanglingOrUnpinned() {
    assertEquals(Optional.empty(), ImageKeepRules.whyKeep(DANGLER, nothing()));
    assertEquals(Optional.empty(), ImageKeepRules.whyKeep(CI, nothing()));
    assertEquals(Removal.DANGLING, ImageKeepRules.removal(DANGLER));
    assertEquals(Removal.UNPINNED, ImageKeepRules.removal(CI));
  }

  @Test
  void aReferenceMatchesAnExactTag() {
    assertTrue(ImageKeepRules.references(CI, "registry:8080/qits/qits-ci:cafe"));
    assertTrue(ImageKeepRules.references(CI, "  registry:8080/qits/qits-ci:cafe  "));
  }

  @Test
  void aReferenceMatchesWithTheRegistryHostOnEitherSide() {
    assertTrue(ImageKeepRules.references(CI, "qits/qits-ci:cafe"));
    ImageSummary shortTag = new ImageSummary(ID, List.of("qits/qits-ci:cafe"), 1, OLD);
    assertTrue(ImageKeepRules.references(shortTag, "registry:8080/qits/qits-ci:cafe"));
  }

  @Test
  void aReferenceDoesNotMatchAPartialName() {
    assertFalse(ImageKeepRules.references(CI, "ci:cafe"));
    assertFalse(ImageKeepRules.references(CI, "qits/qits-ci:caf"));
    // any whole path segment after a slash is a match — the tolerance is in the keeping direction
    assertTrue(ImageKeepRules.references(CI, "qits-ci:cafe"));
  }

  @Test
  void aBlankOrNullReferenceMatchesNothing() {
    assertFalse(ImageKeepRules.references(CI, null));
    assertFalse(ImageKeepRules.references(CI, "   "));
  }

  @Test
  void aHexIdPrefixOfTwelveOrMoreMatches() {
    assertTrue(ImageKeepRules.references(DANGLER, HEX.substring(0, 12)));
    assertTrue(ImageKeepRules.references(DANGLER, "sha256:" + HEX.substring(0, 20)));
    assertTrue(ImageKeepRules.references(DANGLER, ID));
    assertTrue(ImageKeepRules.references(DANGLER, "qits/qits-ci@sha256:" + HEX));
    assertTrue(ImageKeepRules.references(DANGLER, "SHA256:" + HEX.toUpperCase()));
  }

  @Test
  void aHexIdShorterThanTwelveDoesNotMatch() {
    assertFalse(ImageKeepRules.references(DANGLER, HEX.substring(0, 11)));
  }

  @Test
  void aHexIdThisImageDoesNotStartWithDoesNotMatch() {
    assertFalse(ImageKeepRules.references(DANGLER, "0123456789ab"));
  }

  @Test
  void anImageWithNoIdMatchesNoHex() {
    ImageSummary idless = new ImageSummary(null, List.of(), 1, OLD);
    assertFalse(ImageKeepRules.references(idless, HEX.substring(0, 12)));
  }

  @Test
  void aPinnedIdKeepsADanglingImage() {
    assertTrue(ImageKeepRules.pinned(DANGLER, List.of(ID), List.of()));
  }

  @Test
  void aPinPrefixMatchesTheTagStartOrThePartAfterASlash() {
    ImageSummary build =
        new ImageSummary(ID, List.of("registry:8080/qits/build-images/java:21"), 1, OLD);
    assertTrue(ImageKeepRules.pinned(build, List.of(), List.of("qits/build-images/")));
    assertTrue(ImageKeepRules.pinned(build, List.of(), List.of("registry:8080/")));
    assertTrue(ImageKeepRules.pinned(build, List.of(), List.of("build-images/")));
    assertFalse(ImageKeepRules.pinned(build, List.of(), List.of("images/")));
    assertFalse(ImageKeepRules.pinned(DANGLER, List.of(), List.of("qits/")));
  }

  @Test
  void oneTagOfTwoIsEnoughForAPin() {
    ImageSummary twoTags =
        new ImageSummary(ID, List.of("qits/qits-ci:cafe", "qits/qits-ci:latest"), 1, OLD);
    assertTrue(ImageKeepRules.pinned(twoTags, List.of("qits/qits-ci:latest"), List.of()));
  }

  @Test
  void cleanDropsNullsAndBlanksAndStrips() {
    assertEquals(List.of(), ImageKeepRules.clean(null));
    assertEquals(List.of("a", "b"), ImageKeepRules.clean(Arrays.asList(" a ", null, "  ", "b")));
  }
}
