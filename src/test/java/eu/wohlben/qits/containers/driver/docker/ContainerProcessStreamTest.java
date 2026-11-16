package eu.wohlben.qits.containers.driver.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ContainerProcess#stream} — qits-bootstrap-cli's process host (qits-2212): every line to
 * the callback as it is read, stdin fed on its own thread, the deadline, exit 127 for a binary that
 * cannot start, and the interrupt's kill-and-reap (qits-894). Real processes, no docker.
 */
public class ContainerProcessStreamTest {

  private static final Duration LONG = Duration.ofSeconds(30);

  @Test
  public void everyLineOfTheMergedOutputArrivesInOrderAndNothingIsKept() {
    List<String> lines = new CopyOnWriteArrayList<>();
    ContainerProcess.Result result =
        ContainerProcess.stream(
            null,
            List.of("bash", "-c", "echo one; echo two >&2; sleep 0.1; echo three; exit 4"),
            null,
            null,
            LONG,
            lines::add);

    assertEquals(4, result.exitCode());
    assertEquals(List.of("one", "two", "three"), lines);
    assertEquals("", result.output(), "the output went to the callback, not into a buffer");
    assertFalse(result.timedOut());
    assertFalse(result.truncated());
  }

  @Test
  public void linesArriveRawAndNotTrimmed() {
    List<String> lines = new CopyOnWriteArrayList<>();
    ContainerProcess.stream(
        null,
        List.of("printf", "  padded  \\n\\033[1mbold\\033[0m\\n"),
        null,
        null,
        LONG,
        lines::add);

    assertEquals(List.of("  padded  ", "\033[1mbold\033[0m"), lines);
  }

  @Test
  public void everyLineHasBeenDeliveredWhenTheCallReturns() {
    List<String> lines = new CopyOnWriteArrayList<>();
    ContainerProcess.Result result =
        ContainerProcess.stream(
            null, List.of("seq", "1", "20000"), null, null, LONG, lines::add);

    assertEquals(0, result.exitCode());
    assertEquals(20000, lines.size(), "the reader is joined before the call returns");
    assertEquals("20000", lines.getLast());
  }

  @Test
  public void stdinTextReachesTheChildAndIsClosedAfter() {
    List<String> lines = new CopyOnWriteArrayList<>();
    ContainerProcess.Result result =
        ContainerProcess.stream(
            null, List.of("cat"), null, "{\"auths\":{}}\nsecond line\n", LONG, lines::add);

    assertEquals(0, result.exitCode());
    assertEquals(List.of("{\"auths\":{}}", "second line"), lines);
  }

  @Test
  public void stdinLargerThanThePipeBufferDoesNotDeadlock() {
    // Written on its own thread: a single-threaded write would block on the full pipe while the
    // child blocks on its full stdout.
    String big = "x".repeat(1024 * 1024);
    List<String> lines = new CopyOnWriteArrayList<>();
    ContainerProcess.Result result =
        ContainerProcess.stream(null, List.of("cat"), null, big, LONG, lines::add);

    assertEquals(0, result.exitCode());
    assertEquals(big, String.join("", lines));
  }

  @Test
  public void noStdinIsAnImmediateEndOfInput() {
    // A child that reads stdin must see EOF rather than wait for a terminal nobody is at.
    ContainerProcess.Result result =
        ContainerProcess.stream(null, List.of("cat"), null, null, Duration.ofSeconds(10), null);

    assertEquals(0, result.exitCode());
    assertFalse(result.timedOut());
  }

  @Test
  public void theEnvironmentIsAddedAndTheDirectoryIsTheChildsOwn(@TempDir Path tmp)
      throws java.io.IOException {
    List<String> lines = new CopyOnWriteArrayList<>();
    ContainerProcess.stream(
        tmp,
        List.of("bash", "-c", "echo \"$QITS_TOKEN_NAME ${PATH:+path} $(pwd -P)\""),
        Map.of("QITS_TOKEN_NAME", "set"),
        null,
        LONG,
        lines::add);

    assertEquals(List.of("set path " + tmp.toRealPath()), lines);
  }

  @Test
  public void theDeadlineKillsTheChildAndSaysSo() {
    List<String> lines = new CopyOnWriteArrayList<>();
    long started = System.nanoTime();
    ContainerProcess.Result result =
        ContainerProcess.stream(
            null,
            List.of("bash", "-c", "echo SAID-FIRST; exec sleep 30"),
            null,
            null,
            Duration.ofMillis(500),
            lines::add);

    assertTrue(result.timedOut());
    assertEquals(-1, result.exitCode());
    assertEquals(List.of("SAID-FIRST"), lines, "what was said before the deadline is kept");
    assertTrue(
        System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10), "the deadline is a deadline");
  }

  @Test
  public void aBinaryThatCannotStartIsExit127WithItsReasonAsALine() {
    List<String> lines = new CopyOnWriteArrayList<>();
    ContainerProcess.Result result =
        ContainerProcess.stream(
            null, List.of("/no/such/qits-binary", "x"), null, null, LONG, lines::add);

    assertEquals(127, result.exitCode());
    assertFalse(result.timedOut());
    assertEquals(1, lines.size(), lines.toString());
    assertTrue(lines.getFirst().startsWith("cannot start /no/such/qits-binary: "), lines.getFirst());
    assertEquals(lines.getFirst(), result.output());
  }

  @Test
  public void aDeadlineIsMandatory() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerProcess.stream(null, List.of("true"), null, null, null, null));
  }

  @Test
  public void interruptedMidCallForceKillsAndReapsTheChild(@TempDir Path tmp)
      throws InterruptedException {
    Path marker = tmp.resolve("marker");
    AtomicReference<ContainerProcess.Result> resultRef = new AtomicReference<>();
    AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              resultRef.set(
                  ContainerProcess.stream(
                      null,
                      List.of("bash", "-c", "sleep 3; touch " + marker),
                      null,
                      null,
                      LONG,
                      null));
              interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
            });
    worker.start();
    Thread.sleep(500);
    worker.interrupt();
    worker.join(TimeUnit.SECONDS.toMillis(15));
    assertFalse(worker.isAlive(), "the interrupted call must return promptly, not after sleep 3");

    assertInterruptedAndReaped(resultRef.get(), interruptedAfterReturn.get(), marker);
  }

  @Test
  public void alreadyInterruptedBeforeTheCallForceKillsAndReapsTheChild(@TempDir Path tmp)
      throws InterruptedException {
    Path marker = tmp.resolve("marker-already");
    AtomicReference<ContainerProcess.Result> resultRef = new AtomicReference<>();
    AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              resultRef.set(
                  ContainerProcess.stream(
                      null,
                      List.of("bash", "-c", "sleep 3; touch " + marker),
                      null,
                      null,
                      LONG,
                      null));
              interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
            });
    worker.start();
    worker.join(TimeUnit.SECONDS.toMillis(15));
    assertFalse(worker.isAlive(), "an already-interrupted call must return promptly");

    assertInterruptedAndReaped(resultRef.get(), interruptedAfterReturn.get(), marker);
  }

  private static void assertInterruptedAndReaped(
      ContainerProcess.Result result, boolean interruptedFlagAfterReturn, Path marker)
      throws InterruptedException {
    assertEquals(-1, result.exitCode());
    assertEquals("interrupted", result.output());
    assertFalse(result.timedOut());
    assertTrue(interruptedFlagAfterReturn, "the interrupt flag must still be set after return");

    boolean childSurvives =
        ProcessHandle.current()
            .descendants()
            .anyMatch(
                handle ->
                    handle
                        .info()
                        .commandLine()
                        .map(cmd -> cmd.contains(marker.toString()))
                        .orElse(false));
    assertFalse(childSurvives, "the child carrying the marker must have been reaped");

    Thread.sleep(TimeUnit.SECONDS.toMillis(4));
    assertFalse(Files.exists(marker), "a killed child must never have created the marker");
  }
}
