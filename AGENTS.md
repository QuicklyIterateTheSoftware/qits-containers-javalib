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
- **Packages stay under `eu.wohlben.qits.containers.driver`.** No split package with CS.
- **Tests are plain JUnit 5 and need no docker.**

## Building

Every maven invocation on the shared host goes through the lock:

    flock /tmp/qits-maven.lock ./mvnw -B -ntp -s .qits-maven-settings.xml verify
