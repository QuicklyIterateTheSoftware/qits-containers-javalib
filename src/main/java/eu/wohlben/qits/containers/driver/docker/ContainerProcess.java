package eu.wohlben.qits.containers.driver.docker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * This service's own tiny process shell-out — the {@code CiProcess} / {@code PdProcess} shape,
 * deliberately its own third copy rather than a shared jar so this repository stays clone-alone.
 * Combined stdout+stderr, drained on a virtual thread; on timeout the process is {@link
 * Process#destroyForcibly() force-killed} and whatever output was captured so far is returned with
 * {@code timedOut=true}. A call that is interrupted — or was already interrupted when it started —
 * force-kills the child the same way before returning, so a cancelled caller never leaves the
 * process running behind it.
 *
 * <p><b>What it is used for is the docker CLI and nothing else.</b> The vocabulary is container
 * lifecycle plus the host's own stores — {@code run}, {@code inspect}, {@code logs}, {@code stop},
 * {@code rm}, {@code ps}, {@code pull}, {@code volume}, {@code network inspect}, {@code image ls},
 * {@code image rm}, {@code system df}, {@code builder prune}, {@code buildx du}. Nothing an owner
 * sends becomes a host command line: an argv is assembled element by element by {@link DockerArgv}
 * and handed to {@link ProcessBuilder}, which never re-splits.
 *
 * <p><b>{@code exec} is in the vocabulary, for exactly one command, and the exception is written
 * down here rather than discovered later.</b> A bootstrap builder keeps its cache inside its own
 * container, so pruning it is {@code docker exec buildx_buildkit_<name> buildctl prune} — and both
 * words of that are constants. The container is a name a {@code ps} filtered on the builder prefix
 * produced and {@code ContainersIdentifiers.requireBuilderContainer} re-checks; the command is two
 * literals and a number. No caller may name either, which is what keeps {@code exec} from becoming
 * a general capability of this service. A second use is a decision, not a refactor.
 *
 * <p><b>Every entry point takes both a timeout and a bound.</b> That is the whole
 * design, and it is the repository's second invariant (AGENTS.md): a docker call with no deadline is
 * a worker held forever by a daemon that stopped answering, and a capture with no bound is a heap
 * whose size the caller chose. Container output is attacker-influenced — an owner picks the image
 * and a workload prints what it likes — so an overload without either would be an unbounded read
 * that compiles. Making the bound unavoidable in the API is the security property; a convenience
 * overload is the regression.
 *
 * <p>Output is <b>bounded while reading</b>: the buffer keeps only the trailing {@code maxChars} (a
 * failure's tail is where the diagnosis is) and reports {@code truncated}.
 *
 * <p><b>The second entry point adds an environment and takes both guards anyway</b>, which is what
 * keeps it from being the convenience overload the paragraph above forbids. It exists for one
 * measured reason: the CLI's buildx plugin writes its state under {@code $DOCKER_CONFIG}, and this
 * service is deployed with a READ-ONLY config volume — so a host build-cache prune answered
 * {@code mkdir /work/config/buildx: permission denied} on the platform's first real collection run.
 * {@code BUILDX_CONFIG} points that state somewhere writable. The entries are added to this
 * process's own environment rather than replacing it, because a docker CLI stripped of
 * {@code DOCKER_CONFIG} would lose the registry credentials the same deployment mounts.
 *
 * <p><b>The third entry point, {@link #runSeparate}, keeps the two streams apart</b>, for a caller
 * that reads a whole document off stdout — {@code docker inspect} with no {@code --format} answers
 * a JSON array, and a tail of one is not a short answer but invalid JSON. Ported from
 * qits-ci-runner-daemon's {@code Docker}. stdout is kept from its <b>head</b>, whole up to a byte
 * cap ({@link #STDOUT_CAP} is the default a caller passes) and marked {@code stdoutTruncated} past
 * it rather than cut silently; stderr keeps the merged entry's bounded tail, because docker's
 * diagnosis is a line. Both pipes are drained concurrently to their end whatever is kept, so a child
 * never blocks on a full pipe, and the deadline is mandatory and force-kills exactly as above.
 */
public final class ContainerProcess {

  /** Rolling buffer slack: trim back to {@code maxChars} once it grows past this multiple. */
  private static final int TRIM_FACTOR = 2;

  /**
   * Exit code, bounded combined output, whether the hard timeout expired ({@code exitCode} is -1
   * then), and whether output was dropped from the front.
   */
  public record Result(int exitCode, String output, boolean timedOut, boolean truncated) {}

  /** The default stdout cap for {@link #runSeparate}: 1 MiB, past any document docker answers. */
  public static final int STDOUT_CAP = 1024 * 1024;

  /** How much of stderr's tail {@link #runSeparate} keeps — a diagnosis is a line, not a document. */
  public static final int STDERR_TAIL_CHARS = 8192;

  /**
   * Exit code ({@code -1} when the deadline killed it or it never ran), stdout from its head and
   * whole up to the cap, stderr's bounded tail, whether stdout went past the cap, and whether the
   * deadline expired.
   */
  public record Separated(
      int exitCode,
      String stdout,
      String stderrTail,
      boolean stdoutTruncated,
      boolean timedOut) {}

  private ContainerProcess() {}

  /**
   * Run the argv, capturing the trailing {@code maxChars} of its merged output.
   *
   * @param cwd working directory, or null for this process's own
   * @param argv the command, already assembled element by element — never a shell string
   * @param timeout the hard deadline; past it the process is force-killed
   * @param maxChars how much of the output tail is kept
   */
  public static Result run(Path cwd, List<String> argv, Duration timeout, int maxChars) {
    return run(cwd, argv, Map.of(), timeout, maxChars);
  }

  /**
   * The same run, with these variables added to the environment the child sees.
   *
   * @param env variables to ADD to this process's own environment; never a replacement for it
   */
  public static Result run(
      Path cwd, List<String> argv, Map<String, String> env, Duration timeout, int maxChars) {
    Process process = null;
    try {
      ProcessBuilder pb = new ProcessBuilder(argv);
      if (cwd != null) {
        pb.directory(cwd.toFile());
      }
      if (env != null && !env.isEmpty()) {
        pb.environment().putAll(env);
      }
      pb.redirectErrorStream(true);
      process = pb.start();
      Process started = process;
      Tail tail = new Tail(maxChars);
      Thread reader =
          Thread.startVirtualThread(
              () -> {
                try (InputStream stream = started.getInputStream()) {
                  byte[] buffer = new byte[8192];
                  int n;
                  while ((n = stream.read(buffer)) >= 0) {
                    tail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                  }
                } catch (Exception ignored) {
                  // stream closes when the process dies — nothing to report beyond the exit code
                }
              });
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor();
      }
      reader.join(TimeUnit.SECONDS.toMillis(5));
      return new Result(
          finished ? process.exitValue() : -1, tail.text(), !finished, tail.truncated());
    } catch (InterruptedException e) {
      if (process != null) {
        killAndReap(process);
      }
      Thread.currentThread().interrupt();
      return new Result(-1, "interrupted", false, false);
    } catch (Exception e) {
      return new Result(-1, String.valueOf(e.getMessage()), false, false);
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  /**
   * Run the argv with stdout and stderr kept apart — see the class javadoc.
   *
   * @param argv the command, already assembled element by element — never a shell string
   * @param deadline the hard deadline; past it the process is force-killed
   * @param stdoutCapBytes how many bytes of stdout are kept, from the head ({@link #STDOUT_CAP})
   */
  public static Separated runSeparate(List<String> argv, Duration deadline, int stdoutCapBytes) {
    if (deadline == null) {
      throw new IllegalArgumentException("A deadline is mandatory");
    }
    Process process = null;
    try {
      process = new ProcessBuilder(argv).start();
      Process started = process;
      try {
        // Nothing here is interactive; a child waiting on a terminal must see EOF.
        process.getOutputStream().close();
      } catch (IOException ignored) {
        // already closed is as good as closed
      }
      Head head = new Head(stdoutCapBytes);
      Tail tail = new Tail(STDERR_TAIL_CHARS);
      Thread outReader =
          Thread.startVirtualThread(
              () -> {
                try (InputStream stream = started.getInputStream()) {
                  byte[] buffer = new byte[8192];
                  int n;
                  while ((n = stream.read(buffer)) >= 0) {
                    head.append(buffer, n);
                  }
                } catch (Exception ignored) {
                  // stream closes when the process dies — nothing to report beyond the exit code
                }
              });
      Thread errReader =
          Thread.startVirtualThread(
              () -> {
                try (InputStream stream = started.getErrorStream()) {
                  byte[] buffer = new byte[8192];
                  int n;
                  while ((n = stream.read(buffer)) >= 0) {
                    tail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                  }
                } catch (Exception ignored) {
                  // as above
                }
              });
      boolean finished = process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor();
      }
      outReader.join(TimeUnit.SECONDS.toMillis(5));
      errReader.join(TimeUnit.SECONDS.toMillis(5));
      return new Separated(
          finished ? process.exitValue() : -1,
          head.text(),
          tail.text(),
          head.truncated(),
          !finished);
    } catch (InterruptedException e) {
      if (process != null) {
        killAndReap(process);
      }
      Thread.currentThread().interrupt();
      return new Separated(-1, "", "interrupted", false, false);
    } catch (Exception e) {
      return new Separated(-1, "", String.valueOf(e.getMessage()), false, false);
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  /**
   * Force-kills an interrupted call's child and waits up to five seconds total for it to actually
   * die, so the catch that calls this never hands a live process back to the caller. {@code
   * waitFor} throws immediately on a thread whose interrupt flag is already set, so this swallows
   * any further {@link InterruptedException} while it waits — the caller re-asserts the flag once
   * this returns.
   */
  private static void killAndReap(Process process) {
    process.destroyForcibly();
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      long remainingNanos = deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0) {
        return;
      }
      try {
        process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
        return;
      } catch (InterruptedException ignored) {
        // keep waiting out the bound; the caller re-asserts the interrupt flag once we return
      }
    }
  }

  /**
   * A synchronized head of a byte stream: the first {@code capBytes} are kept and everything after
   * them is read and dropped, so the child never blocks on a full pipe. Decoded once at the end, so
   * a multi-byte character is never split across two reads.
   */
  private static final class Head {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final int capBytes;
    private boolean truncated;

    Head(int capBytes) {
      this.capBytes = Math.max(0, capBytes);
    }

    synchronized void append(byte[] buffer, int n) {
      int room = capBytes - bytes.size();
      if (n > room) {
        truncated = true;
      }
      if (room > 0) {
        bytes.write(buffer, 0, Math.min(n, room));
      }
    }

    synchronized String text() {
      return bytes.toString(StandardCharsets.UTF_8);
    }

    synchronized boolean truncated() {
      return truncated;
    }
  }

  /** A synchronized rolling tail — appends are trimmed so memory stays O(maxChars). */
  private static final class Tail {

    private final StringBuilder buffer = new StringBuilder();
    private final int maxChars;
    private boolean truncated;

    Tail(int maxChars) {
      this.maxChars = Math.max(1, maxChars);
    }

    synchronized void append(String chunk) {
      buffer.append(chunk);
      if (buffer.length() > (long) maxChars * TRIM_FACTOR) {
        buffer.delete(0, buffer.length() - maxChars);
        truncated = true;
      }
    }

    synchronized String text() {
      if (buffer.length() > maxChars) {
        buffer.delete(0, buffer.length() - maxChars);
        truncated = true;
      }
      return buffer.toString();
    }

    synchronized boolean truncated() {
      return truncated;
    }
  }
}
