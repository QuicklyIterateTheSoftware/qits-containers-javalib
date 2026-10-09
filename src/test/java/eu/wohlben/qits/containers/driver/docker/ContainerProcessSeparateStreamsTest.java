package eu.wohlben.qits.containers.driver.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The separate-streams entry: stdout is a document read whole from its head, stderr a bounded
 * diagnosis tail, and the two never meet. The merged entry's own cases are {@link
 * ContainerProcessTest}, unchanged.
 */
class ContainerProcessSeparateStreamsTest {

  /** A JSON array of {@code entries} objects, the shape {@code docker inspect} answers. */
  private static String json(int entries) {
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < entries; i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append("{\"Id\":\"").append(String.format("%064d", i)).append("\",\"Name\":\"/c-").append(i).append("\"}");
    }
    return out.append("]\n").toString();
  }

  @Test
  void aWholeDocumentArrivesOnStdoutWithNoStderrInIt(@TempDir Path dir) throws IOException {
    String document = json(200);
    assertTrue(document.getBytes(StandardCharsets.UTF_8).length > 8 * 1024, "8 KiB+ of JSON");
    Path file = Files.writeString(dir.resolve("doc.json"), document);

    // stderr is written before, between and after stdout's chunks, so a merge anywhere shows.
    ContainerProcess.Separated result =
        ContainerProcess.runSeparate(
            List.of(
                "bash",
                "-c",
                "echo noise-before >&2; head -c 4096 "
                    + file
                    + "; echo noise-between >&2; tail -c +4097 "
                    + file
                    + "; echo noise-after >&2; exit 0"),
            Duration.ofSeconds(30),
            ContainerProcess.STDOUT_CAP);

    assertEquals(0, result.exitCode());
    assertEquals(document, result.stdout());
    assertFalse(result.stdout().contains("noise"), "no stderr on stdout");
    assertFalse(result.stdoutTruncated());
    assertFalse(result.timedOut());
    assertTrue(result.stderrTail().contains("noise-before"), result.stderrTail());
    assertTrue(result.stderrTail().contains("noise-after"), result.stderrTail());
    assertFalse(result.stderrTail().contains("\"Id\""), "no stdout on stderr");
  }

  @Test
  void stdoutOverTheCapIsMarkedTruncatedAndKeepsItsHead() {
    ContainerProcess.Separated result =
        ContainerProcess.runSeparate(
            List.of("bash", "-c", "echo HEAD-FIRST; for i in $(seq 1 20000); do echo line-$i; done"),
            Duration.ofSeconds(60),
            64);

    assertEquals(0, result.exitCode(), "the child was drained to its end, never blocked");
    assertTrue(result.stdoutTruncated());
    assertEquals(64, result.stdout().length());
    assertTrue(result.stdout().startsWith("HEAD-FIRST\n"), result.stdout());
  }

  @Test
  void aLargeStderrDoesNotDeadlockAndKeepsItsBoundedTail() {
    // Well past a pipe buffer on stderr while stdout is still open: both pipes are drained at once.
    ContainerProcess.Separated result =
        ContainerProcess.runSeparate(
            List.of(
                "bash",
                "-c",
                "for i in $(seq 1 50000); do echo err-$i >&2; done; echo THE-END >&2; echo out"),
            Duration.ofSeconds(60),
            ContainerProcess.STDOUT_CAP);

    assertFalse(result.timedOut());
    assertEquals(0, result.exitCode());
    assertEquals("out\n", result.stdout());
    assertTrue(result.stderrTail().length() <= ContainerProcess.STDERR_TAIL_CHARS);
    assertTrue(result.stderrTail().contains("THE-END"), "the tail is where the diagnosis is");
  }

  @Test
  void theDeadlineForceKillsAndReportsTimedOut() {
    long started = System.nanoTime();
    ContainerProcess.Separated result =
        ContainerProcess.runSeparate(
            List.of("bash", "-c", "echo SAID-THIS-FIRST; echo err >&2; exec sleep 30"),
            Duration.ofMillis(500),
            ContainerProcess.STDOUT_CAP);

    assertTrue(result.timedOut());
    assertEquals(-1, result.exitCode());
    assertTrue(result.stdout().contains("SAID-THIS-FIRST"), result.stdout());
    assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 20, "it was killed");
  }

  @Test
  void theDeadlineIsMandatory() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerProcess.runSeparate(List.of("true"), null, ContainerProcess.STDOUT_CAP));
  }

  @Test
  void aBinaryThatIsNotThereIsAnExitOfMinusOneWithTheReasonOnStderr() {
    ContainerProcess.Separated result =
        ContainerProcess.runSeparate(
            List.of("/no/such/docker"), Duration.ofSeconds(5), ContainerProcess.STDOUT_CAP);
    assertEquals(-1, result.exitCode());
    assertEquals("", result.stdout());
    assertFalse(result.stderrTail().isEmpty());
  }

  @Test
  void interruptedMidCallForceKillsTheChildAndStillReportsInterrupted(@TempDir Path tmp)
      throws InterruptedException {
    // Same hole as ContainerProcessTest's twin, but on the separate-streams entry point: before the
    // fix, the child declared inside the old try could not be reached from the InterruptedException
    // catch, so it kept running past the "interrupted" answer.
    Path marker = tmp.resolve("marker");
    AtomicReference<ContainerProcess.Separated> resultRef = new AtomicReference<>();
    AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              ContainerProcess.Separated result =
                  ContainerProcess.runSeparate(
                      List.of("bash", "-c", "sleep 3; touch " + marker),
                      Duration.ofSeconds(30),
                      ContainerProcess.STDOUT_CAP);
              resultRef.set(result);
              interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
            });
    worker.start();
    Thread.sleep(500);
    worker.interrupt();
    worker.join(TimeUnit.SECONDS.toMillis(15));
    assertFalse(worker.isAlive(), "the interrupted call must return promptly, not after sleep 3");

    assertInterruptedResultAndNoSurvivingChild(resultRef.get(), interruptedAfterReturn.get(), marker);
  }

  @Test
  void alreadyInterruptedBeforeTheCallForceKillsTheChildAndReportsInterrupted(@TempDir Path tmp)
      throws InterruptedException {
    // The other way the InterruptedException path is reached: the thread's flag is set before the
    // call, so waitFor throws immediately with no window to interrupt mid-call.
    Path marker = tmp.resolve("marker-already");
    AtomicReference<ContainerProcess.Separated> resultRef = new AtomicReference<>();
    AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              ContainerProcess.Separated result =
                  ContainerProcess.runSeparate(
                      List.of("bash", "-c", "sleep 3; touch " + marker),
                      Duration.ofSeconds(30),
                      ContainerProcess.STDOUT_CAP);
              resultRef.set(result);
              interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
            });
    worker.start();
    worker.join(TimeUnit.SECONDS.toMillis(15));
    assertFalse(worker.isAlive(), "an already-interrupted call must return promptly");

    assertInterruptedResultAndNoSurvivingChild(resultRef.get(), interruptedAfterReturn.get(), marker);
  }

  /** Shared assertions for both interruption tests above. */
  private static void assertInterruptedResultAndNoSurvivingChild(
      ContainerProcess.Separated result, boolean interruptedFlagAfterReturn, Path marker)
      throws InterruptedException {
    assertEquals(-1, result.exitCode());
    assertTrue(result.stderrTail().contains("interrupted"), result.stderrTail());
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
    assertFalse(childSurvives, "the sleep/touch child carrying the marker must not survive");

    // The child was killed well before its "sleep 3" would have let it touch the marker.
    Thread.sleep(TimeUnit.SECONDS.toMillis(4));
    assertFalse(Files.exists(marker), "a killed child must never have created the marker");
  }
}
