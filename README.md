# qits-containers-javalib

The platform's **docker layer as a library**, published as the jar
`eu.wohlben.qits:qits-containers-driver`. Whatever holds a docker socket embeds it: the spec of a
container, the labels that mark it, the exact `docker` argv that starts, inspects and collects it,
and a driver that runs that argv as a subprocess of the docker CLI.

It was cut out of qits-containers-service (CS), which runs on it unchanged. The workspaces runner
and the front-desk runner are the next embedders.

    <dependency>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-containers-driver</artifactId>
        <version><!-- a released calver --></version>
    </dependency>

Plain Java 25. No CDI, no Quarkus, no Jackson, no config file: you construct what you use, and pass
it everything it needs. The one runtime dependency is `org.jboss.logging:jboss-logging`. Nothing
here is serialised, so a native embedder registers reflection for whatever **it** serialises (CS
keeps its own `SpecReflection` for the spec it stores).

## Packages

Everything lives under `eu.wohlben.qits.containers.driver`, a package CS does not use, so there is
no split package with it.

| | |
|---|---|
| `.spec` | `ContainerSpec` (with `VolumeMount`, `SharedMount`, `SecurityPosture`, `PullPolicy`, `Builder`), `LifecyclePolicy`, `VolumeSpec`, `InteractiveRunSpec`, `HostRunSpec`, `ContainersIdentifiers` (the charset belts), `LabelNamespace` |
| `.docker` | `DockerArgv`, `SwarmArgv`, `ComposeArgv`, `InteractiveArgv`, `HostRunArgv`, `ExecArgv`, `ContainerProcess` |
| `.swarm` | `ServiceSpec` (with `NetworkAttachment`, `Mount`, `Publish`, `Health`, `UpdatePolicy`, `Builder`), `ServiceUpdate`, `SwarmIdentifiers` (the swarm belts) |
| root | `ContainersDriver` and its records, `DockerContainersDriver`, `DockerSocketGroup`, `ContainersTimeouts` |
| `.gc` | `ImageKeepRules`, `VolumeClassifier` — pure GC decisions over driver answers |

## The label namespace is yours, and only yours

Every container and volume the driver makes carries labels under one prefix, a `LabelNamespace`
you choose and pass in:

    LabelNamespace ns = new LabelNamespace("qits.workspaces.runner.");
    ns.forContainer(owner, workload, ref, rowId, instanceId);   // <ns>managed, <ns>owner, ...

**Never pass another embedder's namespace. CS owns `qits.containers.`.** This is a safety rule,
not style: CS's volume GC removes every dangling volume labelled `qits.containers.managed=volume`
that none of its rows claims. A runner on the same host that labelled its volumes under CS's
namespace would hand them to that sweep.

The prefix must have at least two dot-separated segments and end in a dot
(`^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+\.$`), so `qits.` — which would swallow callers' own
`qits.workspace` / `qits.project` labels — is refused.

Owner-supplied labels (`ContainerSpec.extraLabels`, `VolumeSpec.extraLabels`) may not sit inside
your namespace: `ns.requireOwnerLabels(extra)` refuses them, and the driver applies it to every
`run` and volume create. Your own labels travel in the driver call's `labels` argument, never in
`extraLabels`.

## `ContainerSpec` is frozen

`ContainerSpec` has eighteen components, in a fixed order with fixed names, and that is enforced by
`ContainerSpecShapeTest`. The reason lives in CS: its `spec_hash` is a SHA-256 over the JSON of
the whole spec, and it is the identity of every running workspace, agent and refinement container.
A changed hash recreates the container on its next ensure.

**The golden-hash rule.** CS's `SpecFingerprintGoldenTest`
(`core/src/test/java/eu/wohlben/qits/containers/control/` in qits-containers-service) pins that
hash and the persisted JSON with literals. A new component serialises as `null` and moves every
hash, so it may be added only after CS's fingerprint elides it when null — and the golden test must
stay green on every release of this jar. Renaming or reordering a component is never a refactor.

## Swarm services

`SwarmArgv` renders the `docker service` family — create, update (with a `ServiceUpdate` delta for
`--env-rm`, `--network-rm`/`--network-add` and `--dns-rm`/`--dns-add`), scale, forced restart,
inspect, ps, logs, ls, rm — plus the overlay networks services live on and the task-container reads
beside them. `DockerArgv` gained `wait` and `volumeLsByName` for the same embedder.

    ServiceSpec spec = ServiceSpec.builder("dev-qits-gateway", image)
        .noResolveImage(true).network("qits-net").restartCondition("any").init(true)
        .labels(labels).health(cmd, Duration.ofSeconds(3), 3, Duration.ofSeconds(60))
        .updatePolicy(new ServiceSpec.UpdatePolicy("start-first", "start-first",
            Duration.ofSeconds(60), "rollback", Duration.ofSeconds(30)))
        .env(env).build();
    SwarmArgv.serviceCreate("docker", spec);

- **Values arrive computed.** Labels, environment, health command and mounts are the embedder's;
  `ServiceSpec` holds them and decides only their order. Labels are passed through exactly as given
  — no `LabelNamespace` applies to a service.
- **The flag order is qits-deployments-service's**, which hand-built these argvs: `SwarmArgvTest`
  pins it element for element, so that service moves onto the jar without changing a command.
- **Two env lists.** `env` is the embedder's own; `envOverrides` is rendered last on a create and
  right after `env` on an update, so configuration outranks the default either way.
- **Belts** (`SwarmIdentifiers`) are lifted from that service's own last-line checks and are never
  narrower: mount source/target of a declared volume (`Mount.volume`), the health command,
  capability and sysctl charsets (the allowlists stay the embedder's policy), groups, aliases, ports
  and the closed vocabularies. Service, task, network and container references, labels, environment
  strings and resolvers are refused only when null — they are read off the daemon or configuration,
  and docker's own "no such service" is the answer callers handle.
- **Templates and filters are the caller's**: every `--format` is a parameter; `labelFilter` spells
  `label=<k>[=<v>]`.

## Operator reads and terminal sessions

qits-system-service's console reads the host for a person and opens terminals on it (qits-2211).
Its argvs live here too, element for element as that service renders them:

- **Reads with a caller's `--format`** (a null format states none): `DockerArgv.info`, `version`,
  `ps`, `containerInspect`, and `(binary, format)` overloads of `systemDf`, `imageLs` (no `--all`)
  and `volumeLs`; `SwarmArgv.nodeLs`, `serviceLs`, `configLs`, `secretLs`, and `serviceInspect`
  with a null format.
- **References a person typed** go through `ContainersIdentifiers.requireReference` — docker's own
  name charset, no leading `-`, at most 128 characters — in `DockerArgv.containerInspect`,
  `logsTailByReference`, `rm(binary, List)`, and `SwarmArgv.nodeInspect`, `configInspect` and the
  two-argument `serviceInspect`. It is looser than `requireContainerName`, which stays the belt on
  a name this library gives a container. `requireFullId` (64 lowercase hex) guards an exec target.
- **`InteractiveArgv` is a sanctioned exception** to "no `--rm`" and "one `exec`": `run`
  (`run --rm -it`, from an `InteractiveRunSpec`) and `exec` (`exec -it` on a canonical id) for
  sessions attached to the caller's pseudo-terminal, whose teardown is the caller's. Owner decision,
  2026-11-13. It is not a `ContainerSpec`, nothing in `ContainersDriver` runs it, and a PTY session
  is the embedder's to spawn — `ContainerProcess` never runs one.

## Host commands of a bootstrap

qits-bootstrap-cli brings a platform up on a bare host and takes it down again, and every docker
argv it renders lives here too (qits-2212), element for element as that program renders it — its
tests are where the expectations in `DockerArgvTest`, `SwarmArgvTest`, `ComposeArgvTest` and
`HostRunArgvTest` were copied from.

- **`HostRunArgv` is the second sanctioned exception to "no `--rm`"**, under the same owner decision
  (2026-11-13): it renders a `HostRunSpec` as `run -d --name N ...`, `run --rm ...` or
  `create ...`. The `--rm` runs are one-shot helpers (a probe, a `buildctl build`, a file written
  onto a volume, an `nsenter` into the host) and the attached payload run whose exit code is the
  program's, and their teardown is docker's `--rm`: no registry row names them and nothing adopts
  them. It is not a `ContainerSpec` and not an `InteractiveRunSpec`.
- **Its options are an ordered list.** The argvs it was cut to disagree on flag order, and each is
  pinned by a test, so every builder call appends one typed option (`network`, `volume`, `env`,
  `envName` — a variable by NAME, its value in the docker CLI's environment —, `label`, `publish`,
  `user`, `memory`, ...) and the argv renders them in call order. Spelling and belt per option are
  the library's; only the sequence is the caller's.
- **The rest are builders beside their siblings**: `DockerArgv.inspect`/`inspectTypeContainer`/
  `imageInspect`/`networkInspect(binary, network, format)` (the short `-f`), `ps(binary, all,
  filters, format)`, `images`, `logsSince`, `startByReference`, `startAttached` (`start -a`),
  `rmStopped` (`rm` without `-f`), `rmiForce`, `tag`, `load`, `build`, `cpIn`/`cpOut`,
  `push`/`pull` with docker's global `--config <dir>` before the verb, `volumeCreate(binary,
  name)`, `volumeInspect`, `volumeLsDanglingShort`, `networkDisconnect`, a belted `networkRm`,
  `buildxLs`/`buildxRm`; `SwarmArgv.swarmInit`, `stackDeploy` (`--resolve-image never -c`),
  `stackRm`, `secretInspect`, `secretCreateFromStdin` (`secret create <n> -`), `serviceEnvAdd`
  (one change, no `--detach`) and a belted `serviceRmReferences`; `ComposeArgv.version` and `down`.
  Host paths, `--since`, publishes and resource bounds are belted by the package-private
  `HostArgvBelts`; names a person or a listing produced go through `requireReference`.
- **`ContainerProcess.stream`** is that program's process host: every line of the merged output is
  handed raw to a callback as it is read (masking and cleaning are the caller's), stdin text is
  written on its own thread, the deadline is mandatory and force-kills (`timedOut`, exit -1), a
  binary that cannot start is exit 127 with `cannot start <argv0>: <reason>` as a line, and an
  interrupt kills and reaps exactly as `run` does (qits-894). Nothing is kept, so nothing needs a
  bound; `output` carries only that diagnosis or `interrupted`.
- **`ContainerProcess.runSeparate`** also takes an environment to add and stdin text to feed, the
  same way `stream` does (qits-2213: the qits CLI's `network-access` runs on it). A binary that
  cannot start answers exit 127, the reason in `stderrTail`, the same wording as `stream`'s line.

## Commands of the system runner

qits-system-runner-daemon runs every docker call through this library too (qits-2289), and the
spellings it had that no builder matched were added beside their siblings, element for element as
that daemon renders them:

- **`DockerArgv`**: `create` (the 6- and 7-argument `run` as `docker create`, one renderer behind
  both), `bridgeNetworkCreate` (`network create --driver bridge`, labels then `--opt`s in the
  caller's order), `inspectTypeContainerFormat` and `imageInspectFormat` (the long `--format`),
  `inspectTypeContainer(binary, references)` (no template, several references), `stopByReference`
  (`stop -t N`), `logsTailSince` (`logs --tail N [--since S]`), `psFormatFirst`, `psAllQuiet`
  (`ps -aq`), `psAllQuietSeparate` (`ps -a -q [--no-trunc]`), `psAncestor`, `volumeLs(binary,
  filters, format)` and `volumeInspect(binary, name, format)`.
- **`HostRunSpec.Builder.dns`** (`--dns <IP literal>`, belted by `requireDnsServer`) lets the
  runner's privileged build-plane builder render through `HostRunArgv`.
- **`ExecArgv` is the second sanctioned exception to "one `exec`"**, beside `InteractiveArgv`:
  `exec <reference> <command...>` with no `-i`/`-t`, used only by the system runner's DOCKER exec
  relay (owner rule 2026-11-13). The reference is `requireReference`d; the command is non-empty, free
  of control characters, and its first element cannot open with `-`.

## Building

    ./mvnw verify

A clone of this repository alone builds and tests green: no docker (tests fake the runtime with a
script), no platform registry, no prior install.
