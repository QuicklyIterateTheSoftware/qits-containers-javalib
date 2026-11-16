package eu.wohlben.qits.containers.driver.docker;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
 * a general capability of this service. A further use is a decision, not a refactor — and two have
 * been decided, below.
 *
 * <p>That stays true of {@link DockerArgv}, {@code ContainersDriver} and {@code ContainerSpec}.
 * <b>The first sanctioned exception is {@link InteractiveArgv}</b>, decided by the owner on
 * 2026-11-13 when every component that talks to docker moved onto this library (qits-2211): {@code exec -it} and {@code run --rm -it} for attached terminal sessions,
 * whose program the embedder picks from its own closed set, whose target is a canonical id the
 * daemon printed, and whose teardown is the caller's. Those argvs are never run through this class
 * — a PTY session has no deadline and no output bound, which is exactly what this class refuses.
 * {@link HostRunArgv}'s one-shot {@code run --rm} helpers (qits-2212) are a different matter: they
 * have a deadline and an end, and they run through {@link #stream}; its attached payload run, whose
 * terminal is inherited, is spawned by its embedder and never here.
 *
 * <p><b>The second sanctioned exception is {@link ExecArgv}</b>, under the same owner decision of
 * 2026-11-13 (qits-2289): {@code exec <reference> <command...>} with no {@code -i}/{@code -t}, used
 * only by the system runner's DOCKER exec relay ({@code qits targets docker exec}), whose command
 * is its caller's. It has a deadline and an end, so unlike a terminal session it runs through this
 * class, bounded like every other call.
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
 * never blocks on a full pipe, and the deadline is mandatory and force-kills exactly as above. It
 * also takes an environment and stdin text (qits-2213, for the qits CLI's {@code network-access}),
 * added to and fed exactly as {@link #stream} does it; a binary that cannot be started is exit 127,
 * worded the same way.
 *
 * <p><b>The fourth entry point, {@link #stream}, keeps nothing at all</b>, and that is how it
 * honours the bound rather than an exception to it: each line of the merged output is handed to the
 * caller's callback as it is read, so what is held — a line on screen, a log, a capped list — is the
 * caller's to bound, and this class holds no buffer to grow. It exists for qits-bootstrap-cli
 * (qits-2212), whose four-hour {@code buildctl build} and {@code start -a} seed builds are read out
 * line by line to a console and a run log, and some of whose commands take a value on stdin (a
 * {@code secret create <name> -}, a credential written onto a volume). It also takes a working
 * directory, an environment and stdin text; the deadline is mandatory and force-kills, an interrupt
 * kills and reaps as above, and a binary that cannot be started is exit 127. That program runs its
 * git through the same entry point — one process host per embedder — which is why {@link #stream}
 * is not documented as docker-only.
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
   * Exit code ({@code -1} when the deadline killed it or it was interrupted, {@code 127} when it
   * could not start), stdout from its head and whole up to the cap, stderr's bounded tail, whether
   * stdout went past the cap, and whether the deadline expired.
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
   * Run the argv and hand every line of its merged output to {@code line} as it is read — see the
   * class javadoc's last paragraph.
   *
   * <p>What the {@link Result} says:
   *
   * <ul>
   *   <li>the command ran to its end: its exit code, {@code output} empty, nothing timed out;
   *   <li>the deadline passed: the child is force-killed and reaped, {@code exitCode} -1 and {@code
   *       timedOut} true — the caller says so in its own words;
   *   <li>it could not be started at all (the binary is missing, the directory is not one): exit
   *       {@code 127}, the shell's own number for it, and the message {@code cannot start <argv0>:
   *       <reason>} both delivered as a line and returned as {@code output};
   *   <li>the calling thread was interrupted (before or during): the child is force-killed and
   *       reaped exactly as {@link #run} does it (qits-894), the interrupt flag is set again, exit
   *       -1 and {@code output} {@code interrupted}.
   * </ul>
   *
   * <p>{@code output} never carries the command's output — every line went to {@code line}, and
   * keeping a copy would be the unbounded capture this class refuses. {@code truncated} is always
   * false.
   *
   * <p>Lines are split as {@link BufferedReader#readLine} splits them, decoded as UTF-8 and handed
   * over raw: no trimming, no ANSI cleaning, no masking — those are the caller's, who knows which
   * values are secrets. They arrive on one reader thread, in order, and every one of them has been
   * delivered before this returns, unless a descendant of the child keeps the pipe open past five
   * seconds after the child exited (then the rest arrive late, on that thread). A read failure is
   * delivered as {@code output stream ended: <reason>}.
   *
   * @param cwd working directory, or null for this process's own
   * @param argv the command, already assembled element by element — never a shell string
   * @param env variables to ADD to this process's own environment, or null; never a replacement
   * @param stdin text written to the child's stdin on its own thread, UTF-8, then closed — or null
   *     to close stdin at once, so a child waiting on input sees EOF
   * @param timeout the hard deadline; mandatory
   * @param line receives each line; null discards them
   */
  public static Result stream(
      Path cwd,
      List<String> argv,
      Map<String, String> env,
      String stdin,
      Duration timeout,
      Consumer<String> line) {
    if (timeout == null) {
      throw new IllegalArgumentException("A deadline is mandatory");
    }
    Consumer<String> sink = line == null ? ignored -> {} : line;
    ProcessBuilder pb = new ProcessBuilder(argv);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    if (env != null && !env.isEmpty()) {
      pb.environment().putAll(env);
    }
    pb.redirectErrorStream(true);
    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      String message = "cannot start " + argv.getFirst() + ": " + e.getMessage();
      sink.accept(message);
      return new Result(127, message, false, false);
    }
    try {
      feed(process, stdin);
      Thread reader =
          Thread.startVirtualThread(
              () -> {
                try (BufferedReader lines =
                    new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                  String raw;
                  while ((raw = lines.readLine()) != null) {
                    sink.accept(raw);
                  }
                } catch (IOException e) {
                  sink.accept("output stream ended: " + e.getMessage());
                }
              });
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor();
      }
      // A join, not a race: the last lines are the ones a failure is diagnosed by.
      reader.join(TimeUnit.SECONDS.toMillis(5));
      return new Result(finished ? process.exitValue() : -1, "", !finished, false);
    } catch (InterruptedException e) {
      killAndReap(process);
      Thread.currentThread().interrupt();
      return new Result(-1, "interrupted", false, false);
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  /**
   * stdin on its own thread: a text larger than the pipe buffer, written by the thread that also
   * waits on a child that is already printing, would deadlock against it. A null text closes stdin
   * at once.
   */
  private static void feed(Process process, String stdin) {
    if (stdin == null) {
      try {
        process.getOutputStream().close();
      } catch (IOException ignored) {
        // already gone is as good as closed
      }
      return;
    }
    Thread.startVirtualThread(
        () -> {
          try (OutputStream out = process.getOutputStream()) {
            out.write(stdin.getBytes(StandardCharsets.UTF_8));
          } catch (IOException ignored) {
            // a child that stops reading before the text ends is its own business
          }
        });
  }

  /**
   * Run the argv with stdout and stderr kept apart — see the class javadoc.
   *
   * @param argv the command, already assembled element by element — never a shell string
   * @param deadline the hard deadline; past it the process is force-killed
   * @param stdoutCapBytes how many bytes of stdout are kept, from the head ({@link #STDOUT_CAP})
   */
  public static Separated runSeparate(List<String> argv, Duration deadline, int stdoutCapBytes) {
    return runSeparate(argv, null, null, deadline, stdoutCapBytes);
  }

  /**
   * The same run, with an environment added to the child's and stdin text fed to it.
   *
   * @param argv the command, already assembled element by element — never a shell string
   * @param env variables to ADD to this process's own environment, or null; never a replacement
   * @param stdin text written to the child's stdin on its own thread, UTF-8, then closed — or null
   *     to close stdin at once, so a child waiting on input sees EOF
   * @param deadline the hard deadline; mandatory, past it the process is force-killed
   * @param stdoutCapBytes how many bytes of stdout are kept, from the head ({@link #STDOUT_CAP})
   */
  public static Separated runSeparate(
      List<String> argv,
      Map<String, String> env,
      String stdin,
      Duration deadline,
      int stdoutCapBytes) {
    if (deadline == null) {
      throw new IllegalArgumentException("A deadline is mandatory");
    }
    ProcessBuilder pb = new ProcessBuilder(argv);
    if (env != null && !env.isEmpty()) {
      pb.environment().putAll(env);
    }
    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      String message = "cannot start " + argv.getFirst() + ": " + e.getMessage();
      return new Separated(127, "", message, false, false);
    }
    try {
      feed(process, stdin);
      Process started = process;
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
      killAndReap(process);
      Thread.currentThread().interrupt();
      return new Separated(-1, "", "interrupted", false, false);
    } finally {
      if (process.isAlive()) {
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
