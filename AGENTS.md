# testautomation — Agent Guide

## Rule zero: pull before you touch anything

`git pull` **before doing anything else in this repository**, every session, without being asked.
Changes may have been pushed from another machine or session in between (review commits, IDE edits,
manual fixes). Sequence:

```bash
cd /opt/data/projects/testautomation     # clone path on the Hermes VM
git pull --ff-only                       # stop and report if this is not a fast-forward
git status -sb                           # working tree must be clean before starting work
git log --oneline -5                     # see what arrived
```

If the pull is not a fast-forward, or the working tree has uncommitted changes that are not yours:
**stop and ask** — do not rebase, stash or force anything on your own.

## Directory Layout

| Path | Language | Purpose |
|---|---|---|
| `pom.xml` | Maven | Parent + aggregator (`testautomation-parent`, `packaging=pom`, parent `spring-boot-starter-parent` 4.1.1) |
| `service/` | Java 21 | `testautomation-service` — owns the job queue, later the REST API for distributing test cases |
| `agent/` | Java 21 | `testautomation-agent` — claims test cases and executes them |
| `docs/` | Markdown | Design documentation (`postgres-job-queue.md`) |
| `mvnw`, `.mvn/wrapper/` | shell | Maven wrapper (script-only, Maven 3.9.9) |

Package roots: `ch.mmar.testautomation.service` and `ch.mmar.testautomation.agent`.
Both modules are Spring Boot applications; they share no code yet (a `shared/` module is the
intended home for the wire DTOs once the claim protocol is fixed).

## Commands

```bash
./mvnw verify                        # both modules: compile, tests, executable boot jars
./mvnw -pl service verify            # single module
./mvnw -pl agent -am verify          # module plus its parent chain
./mvnw -pl service spring-boot:run   # run from source
java -jar service/target/testautomation-service-0.1.0-SNAPSHOT.jar
java -jar agent/target/testautomation-agent-0.1.0-SNAPSHOT.jar
```

Toolchain on the Hermes VM: JDK 21 at `/opt/data/tools/jdk-21`, Maven at
`/opt/data/tools/maven-3.9.9` (both already on `PATH`). `./mvnw` also works with no local Maven.

## Critical Gotchas

- **Branch `main` only.** No PR workflow, no CI, no formatter or lint config in this repo yet.
  Push directly to `main` after `./mvnw verify` passes.
- **Do not pin dependency versions in `service/pom.xml` / `agent/pom.xml`** — versions come from
  the parent chain (Spring Boot BOM). Add a dependency without `<version>` and let the BOM decide.
- **`spring-boot-maven-plugin` belongs in each module, not in the root POM** — declaring it in the
  aggregator makes the `repackage` goal run for the `pom`-packaging root and fail.
- **No REST layer and no database schema exist yet, on purpose.** Read
  `docs/postgres-job-queue.md` first; the open decisions (§7) and the agent wake-up section (§8)
  define what gets built next. Do not invent a schema or endpoints ahead of those decisions.
- The repository was renamed from `testautomation-service` to `testautomation` on GitHub. The old
  URL redirects, but always use the current one in remotes and docs. Local clone path:
  `/opt/data/projects/testautomation`.
