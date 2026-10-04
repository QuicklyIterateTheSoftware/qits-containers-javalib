package eu.wohlben.qits.containers.driver.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.ContainersDriver.VolumeDetail;
import eu.wohlben.qits.containers.driver.gc.VolumeClassifier.Verdict;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Every verdict, the moment {@code holdersOf} is asked, and the namespace safety property: a
 * volume managed under somebody else's namespace is never this embedder's to remove.
 */
class VolumeClassifierTest {

  private static final LabelNamespace CS = new LabelNamespace("qits.containers.");
  private static final LabelNamespace RUNNER = new LabelNamespace("qits.workspaces.runner.");
  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
  private static final Instant OLD = NOW.minusSeconds(86_400 * 30);
  private static final Instant YOUNGEST = NOW.minusSeconds(86_400);
  private static final String ANON = "a".repeat(64);
  private static final String BUILDER_STATE = "buildx_buildkit_qits0_state";

  private final List<String> asked = new ArrayList<>();

  private Function<String, List<String>> holders(List<String> answer) {
    return name -> {
      asked.add(name);
      return answer;
    };
  }

  private static Optional<VolumeDetail> volume(
      String name, Map<String, String> labels, Instant createdAt) {
    return Optional.of(new VolumeDetail(name, labels, createdAt));
  }

  private static Map<String, String> managedUnder(LabelNamespace ns) {
    return Map.of(ns.managed(), LabelNamespace.MANAGED_VOLUME);
  }

  private Verdict classify(String name, Optional<VolumeDetail> detail, boolean claimed) {
    return VolumeClassifier.classify(name, detail, claimed, YOUNGEST, CS, holders(List.of()));
  }

  @Test
  void aVolumeTheInspectNoLongerFindsVanished() {
    assertEquals(Verdict.VANISHED, classify(ANON, Optional.empty(), false));
  }

  @Test
  void aManagedVolumeAClaimKeepsIsClaimed() {
    assertEquals(Verdict.CLAIMED, classify("v", volume("v", managedUnder(CS), NOW), true));
  }

  @Test
  void aManagedUnclaimedVolumeIsRemovable() {
    assertEquals(
        Verdict.MANAGED_UNCLAIMED, classify("v", volume("v", managedUnder(CS), OLD), false));
  }

  @Test
  void aManagedUnclaimedVolumeMadeInsideTheGraceIsTooYoung() {
    assertEquals(Verdict.TOO_YOUNG, classify("v", volume("v", managedUnder(CS), NOW), false));
  }

  @Test
  void aNullYoungestProtectsNothingByAge() {
    assertEquals(
        Verdict.MANAGED_UNCLAIMED,
        VolumeClassifier.classify(
            "v", volume("v", managedUnder(CS), NOW), false, null, CS, holders(List.of())));
  }

  @Test
  void aVolumeWithNoCreationTimeReadsAsOld() {
    assertEquals(Verdict.ANONYMOUS, classify(ANON, volume(ANON, Map.of(), null), false));
  }

  @Test
  void aManagedVolumeWinsOverItsNameShape() {
    assertEquals(
        Verdict.MANAGED_UNCLAIMED,
        classify(BUILDER_STATE, volume(BUILDER_STATE, managedUnder(CS), OLD), false));
    assertEquals(List.of(), asked);
  }

  @Test
  void aBuilderStateNoBuilderHoldsIsRemovable() {
    Verdict verdict =
        VolumeClassifier.classify(
            BUILDER_STATE,
            volume(BUILDER_STATE, Map.of(), OLD),
            false,
            YOUNGEST,
            CS,
            holders(List.of("someone-else")));
    assertEquals(Verdict.BUILDX_STATE, verdict);
    assertEquals(List.of(BUILDER_STATE), asked);
  }

  @Test
  void aBuilderStateABuilderStillHoldsIsLive() {
    Verdict verdict =
        VolumeClassifier.classify(
            BUILDER_STATE,
            volume(BUILDER_STATE, Map.of(), NOW),
            false,
            YOUNGEST,
            CS,
            holders(List.of("buildx_buildkit_qits0")));
    assertEquals(Verdict.BUILDX_LIVE, verdict);
    assertEquals(List.of(BUILDER_STATE), asked);
  }

  @Test
  void aHolderListingThatThrowsPropagates() {
    IllegalStateException boom = new IllegalStateException("docker did not answer");
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                VolumeClassifier.classify(
                    BUILDER_STATE,
                    volume(BUILDER_STATE, Map.of(), OLD),
                    false,
                    YOUNGEST,
                    CS,
                    name -> {
                      throw boom;
                    }));
    assertSame(boom, thrown);
  }

  @Test
  void anOldAnonymousVolumeIsRemovable() {
    assertEquals(Verdict.ANONYMOUS, classify(ANON, volume(ANON, Map.of(), OLD), false));
  }

  @Test
  void aFreshAnonymousVolumeIsTooYoung() {
    assertEquals(Verdict.TOO_YOUNG, classify(ANON, volume(ANON, Map.of(), NOW), false));
  }

  @Test
  void anythingElseIsUnmanaged() {
    assertEquals(
        Verdict.UNMANAGED,
        classify("some-composed-postgres-data", volume("x", Map.of(), OLD), false));
    assertEquals(Verdict.UNMANAGED, classify("buildx_buildkit_state", volume("x", Map.of(), OLD), false));
    assertEquals(
        Verdict.UNMANAGED,
        classify("v", volume("v", Map.of(CS.managed(), "container"), OLD), false));
  }

  @Test
  void holdersOfIsNeverAskedForANameThatIsNoBuilderState() {
    classify(ANON, volume(ANON, Map.of(), OLD), false);
    classify("plain", volume("plain", Map.of(), OLD), false);
    classify("v", volume("v", managedUnder(CS), OLD), false);
    classify("buildx_buildkit_qits0", volume("x", Map.of(), OLD), false);
    classify(BUILDER_STATE, Optional.empty(), false);
    assertEquals(List.of(), asked);
  }

  @Test
  void aRunnersVolumeIsUnmanagedUnderTheServicesNamespace() {
    Optional<VolumeDetail> runners = volume("v", managedUnder(RUNNER), OLD);
    assertEquals(
        Verdict.UNMANAGED,
        VolumeClassifier.classify("v", runners, false, YOUNGEST, CS, holders(List.of())));
  }

  @Test
  void theServicesVolumeIsUnmanagedUnderARunnersNamespace() {
    Optional<VolumeDetail> services = volume("v", managedUnder(CS), OLD);
    assertEquals(
        Verdict.UNMANAGED,
        VolumeClassifier.classify("v", services, false, YOUNGEST, RUNNER, holders(List.of())));
    assertEquals(
        Verdict.MANAGED_UNCLAIMED,
        VolumeClassifier.classify(
            "v", volume("v", managedUnder(RUNNER), OLD), false, YOUNGEST, RUNNER, holders(List.of())));
  }

  @Test
  void exactlyThreeVerdictsAreRemovable() {
    EnumSet<Verdict> removable = EnumSet.noneOf(Verdict.class);
    for (Verdict verdict : Verdict.values()) {
      if (verdict.removable()) {
        removable.add(verdict);
      }
    }
    assertEquals(
        EnumSet.of(Verdict.MANAGED_UNCLAIMED, Verdict.BUILDX_STATE, Verdict.ANONYMOUS), removable);
    assertFalse(Verdict.CLAIMED.removable());
    assertTrue(Verdict.ANONYMOUS.removable());
  }
}
