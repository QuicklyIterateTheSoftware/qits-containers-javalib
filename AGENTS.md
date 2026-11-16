# qits-containers-javalib — working notes

Read `README.md` first: it is the embedder's contract. These are the rules that bite when you change
the jar.

## Plain Java, and why

- **No CDI, no Quarkus dependency.** The embedders are not all Quarkus applications; the workspaces
  runner is a static musl binary. A bean annotation here would either do nothing or drag a container
  into every embedder. Configuration arrives through constructors.
- **No Jackson, no `@RegisterForReflection`.** Nothing here serialises. Whoever stores or sends a
  spec owns that format and its reflection: CS keeps `SpecFingerprint` (the `spec_hash`) and
  `SpecReflection`. Moving either here would make one embedder's wire format everybody's.
- **No config file** (`microprofile-config.properties`, `beans.xml`). A default shipped in a jar is a
  setting nobody chose; the runtime binary, the label namespace and the socket group are the
  embedder's to pass.
- **Runtime dependency: `jboss-logging` only.** CS's log lines are written through this jar and must
  stay byte-identical. `./mvnw dependency:tree` shows exactly one compile-scope dependency; keep it
  that way. `quarkus-bom` is imported in `dependencyManagement` only, for version alignment.

## The rules that bite

- **`ContainerSpec` is frozen** (`ContainerSpecShapeTest`). CS's `SpecFingerprintGoldenTest` decides
  every stored `spec_hash`; a new component must be null-elided in CS's fingerprint first.
- **The label namespace is never hard-coded.** No `qits.containers.` literal in main sources: it is
  CS's `LabelNamespace`, passed in like anybody else's.
- **Belts keep their wording.** Refusal messages reach CS's HTTP answers and logs; changing one is a
  behaviour change for CS.
- **`SwarmArgv`'s flag order is qits-deployments-service's.** `SwarmArgvTest` copies its expected
  argvs from that service; reordering a flag is a changed command on every deployment it issues.
  `SwarmIdentifiers` belts may never be narrower than what that service renders today.
- **`--rm` lives in `InteractiveArgv` and `HostRunArgv` only, `exec -it` in `InteractiveArgv`
  only** (owner, 2026-11-13). `InteractiveArgv` (qits-2211): attached terminal sessions whose
  teardown is the caller's, flag order qits-system-service's, pinned by `InteractiveArgvTest`.
  `HostRunArgv` (qits-2212): an embedder's one-shot helpers and its attached payload run, whose
  teardown is docker's `--rm`, rendered from a `HostRunSpec` whose options keep the caller's order;
  flag spellings are qits-bootstrap-cli's, pinned by `HostRunArgvTest`. `DockerArgv`,
  `ContainersDriver` and `ContainerSpec` still render neither. A third exception is an owner
  decision, not a refactor.
- **A caller's plain `exec` lives in `ExecArgv` only** (owner rule 2026-11-13, qits-2289): the
  system runner's DOCKER exec relay, `exec <reference> <command...>` with no `-i`/`-t`.
  `DockerArgv`'s only `exec`s stay the constant `buildctl` ones.
- **qits-bootstrap-cli's argvs are pinned like the deployer's.** The builders added for it in
  `DockerArgv`, `SwarmArgv` and `ComposeArgv` (qits-2212) have their expectations copied from that
  program's tests; a changed element is a changed command on every bootstrap.
- **`ContainerProcess.stream` keeps nothing.** Lines go to the caller's callback raw, so the bound is
  the caller's; never add a capture to it — use `run` for a bounded capture.
- **Packages stay under `eu.wohlben.qits.containers.driver`.** No split package with CS.
- **Tests are plain JUnit 5 and need no docker.**

## Building

Every maven invocation on the shared host goes through the lock:

    flock /tmp/qits-maven.lock ./mvnw -B -ntp -s .qits-maven-settings.xml verify
