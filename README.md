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
| `.spec` | `ContainerSpec` (with `VolumeMount`, `SharedMount`, `SecurityPosture`, `PullPolicy`, `Builder`), `LifecyclePolicy`, `VolumeSpec`, `ContainersIdentifiers` (the charset belts), `LabelNamespace` |
| `.docker` | `DockerArgv`, `ContainerProcess` |
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

`ContainerSpec` has sixteen components, in a fixed order with fixed names, and that is enforced by
`ContainerSpecShapeTest`. The reason lives in CS: its `spec_hash` is a SHA-256 over the JSON of
the whole spec, and it is the identity of every running workspace, agent and refinement container.
A changed hash recreates the container on its next ensure.

**The golden-hash rule.** CS's `SpecFingerprintGoldenTest`
(`core/src/test/java/eu/wohlben/qits/containers/control/` in qits-containers-service) pins that
hash and the persisted JSON with literals. A new component serialises as `null` and moves every
hash, so it may be added only after CS's fingerprint elides it when null — and the golden test must
stay green on every release of this jar. Renaming or reordering a component is never a refactor.

## Building

    ./mvnw verify

A clone of this repository alone builds and tests green: no docker (tests fake the runtime with a
script), no platform registry, no prior install.
