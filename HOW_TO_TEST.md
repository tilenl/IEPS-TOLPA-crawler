# How to run tests

Tests are run with **Gradle** from the `**pa1/`** directory (`IEPS-TOLPA-crawler/pa1`). Always use the **Gradle Wrapper** (`./gradlew`), not a system-wide `gradle` from Homebrew — other versions can break the build (for example JUnit Platform on the test classpath).

On first run, the wrapper downloads the version pinned in `[pa1/gradle/wrapper/gradle-wrapper.properties](pa1/gradle/wrapper/gradle-wrapper.properties)` (currently **Gradle 8.10.2**).

If `./gradlew` is not executable on Unix: `chmod +x pa1/gradlew`.

---

## Requirements

### Java 21 for the Gradle process

The build sets `languageVersion = 21` in `pa1/build.gradle.kts`, but **Gradle itself** still starts on whatever JVM `JAVA_HOME` / `PATH` selects. If that is **Java 25** (common with Homebrew’s default `openjdk`), configuration can fail immediately with `IllegalArgumentException: 25.0.2`. The Java toolchain does **not** replace a compatible **launcher** JVM.

On macOS, point at JDK 21 and confirm:

```bash
/usr/libexec/java_home -V
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version              # expect 21.x
cd pa1 && ./gradlew -version   # Launcher JVM and Daemon JVM should show 21.x
```

**Shell exports are not persistent.** Each new terminal (or IDE task) starts without your `export`s unless you add them to `~/.zshrc` or use a tool like `direnv`, SDKMAN, or jEnv.

### Docker (integration tests only)

Tests under `pa1/crawler/src/test/java/.../integration/` use [Testcontainers](https://java.testcontainers.org/) to start a temporary PostgreSQL container. You need a working Docker environment (`docker ps`) and a socket the JVM can use. **Unit tests** under `.../unit/...` do not need Docker.

---

## Commands

Working directory: **repository root** unless you are already in `pa1/`.

### Full suite (unit + integration)

```bash
cd pa1
./gradlew test
```

Useful variants:

- **Force re-execution:** `./gradlew test --rerun-tasks`
- **Avoid long-lived daemon** (picks up fresh `DOCKER_`* env in the same shell): add `--no-daemon`. If you use the daemon, run `./gradlew --stop` after changing Docker-related exports, or export `DOCKER_HOST` before the first daemon start.

### Unit tests only (no Docker)

```bash
cd pa1
./gradlew test --tests 'si.uni_lj.fri.wier.unit.**'
```

### Integration tests only (`PageRepositoryIntegrationTest`)

**Colima (macOS, typical path):** copy-paste as one block from the repo root (skip `cd pa1` if you are already in `pa1`):

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_RYUK_DISABLED=true
cd pa1

# test different integration tests
./gradlew test --tests '*PageRepositoryIntegrationTest' --rerun-tasks --no-daemon
./gradlew test --tests '*UrlCanonicalizationPipelineIntegrationTest' --rerun-tasks --no-daemon
```


| Line                                                            | Why                                                                                                                                                                                                                      |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `JAVA_HOME` … `-v 21`                                           | Gradle must run on JDK 21; avoids launcher failures on newer JDKs.                                                                                                                                                       |
| `PATH` … `$JAVA_HOME/bin`                                       | Ensures `java` and tools match that JDK.                                                                                                                                                                                 |
| `DOCKER_HOST` … `.colima/.../docker.sock`                       | Docker API on Colima listens on this Unix socket, not necessarily `/var/run/docker.sock`.                                                                                                                                |
| `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` … same socket           | Testcontainers may need a real on-disk socket path when `/var/run/docker.sock` does not exist (common on Colima-only setups).                                                                                            |
| `TESTCONTAINERS_RYUK_DISABLED=true`                             | Ryuk (Testcontainers’ cleanup sidecar) often fails on Colima; failure **skips** all integration tests while Gradle still reports success. Disabling Ryuk fixes that for local runs; the Postgres container still starts. |
| `cd pa1`                                                        | Wrapper and build file live here.                                                                                                                                                                                        |
| `./gradlew test --tests '...PageRepositoryIntegrationTest' ...` | Runs only that class; `--rerun-tasks` forces the test task; `--no-daemon` avoids a daemon started without your exports.                                                                                                  |


**Docker Desktop** (default socket, JDK 21 already on `PATH`):

```bash
cd pa1
./gradlew test --tests '*PageRepositoryIntegrationTest' --rerun-tasks --no-daemon
```

If Testcontainers cannot find Docker, set `DOCKER_HOST` from your context (see **Troubleshooting**).

---

## Colima: details and checks

- **Colima running:** `colima status` → `Running` (`colima start` if needed).
- **Sockets:**
  ```bash
  test -S "${HOME}/.colima/default/docker.sock" && echo "Colima socket OK" || echo "Colima socket missing"
  test -S /var/run/docker.sock && echo "/var/run/docker.sock exists" || echo "/var/run/docker.sock missing (normal on Colima-only)"
  ```
  If you **do not** have `/var/run/docker.sock`, do **not** set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` to that path. Use the Colima socket path (as in the block above), or create a symlink:
  ```bash
  sudo ln -sf "${HOME}/.colima/default/docker.sock" /var/run/docker.sock
  ```
- **Do not** default to `TESTCONTAINERS_HOST_OVERRIDE=localhost` on Colima — it often breaks Ryuk and Docker-Java routing. Use a host override only if tests **run** but fail to reach published ports; prefer the address from `colima list` / `colima ls -j` or `docker context inspect`.
- **Debug Testcontainers:** `export TESTCONTAINERS_DEBUG=true` then run `./gradlew test` to see socket strategy and errors.

---

## How to know tests really passed

- Exit code **0** and **BUILD SUCCESSFUL**.
- Console lines should show **PASSED**, not **SKIPPED**, for methods you care about (this repo enables test logging in `pa1/build.gradle.kts`).
- **Integration tests** that actually start Postgres take **noticeably longer** than a few seconds the first time (image pull). A **very fast** run with all integration methods **SKIPPED** usually means Docker/Testcontainers did not run the tests (fix JDK 21 and Colima env, especially `TESTCONTAINERS_RYUK_DISABLED=true`).
- **JUnit XML:** `pa1/build/test-results/test/` — e.g. `TEST-si.uni_lj.fri.wier.integration.storage.postgres.PageRepositoryIntegrationTest.xml` should have `skipped="0"` when integration tests ran.
- **HTML report:** `pa1/build/reports/tests/test/index.html`.

---

## Troubleshooting


| Problem                                              | What to try                                                                                                                                                                                                                                                                             |
| ---------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Integration tests skipped** (`skipped="5"` in XML) | Same terminal: `docker ps` must work. Use the Colima block above; fix socket override if it points at a missing `/var/run/docker.sock`; set `TESTCONTAINERS_RYUK_DISABLED=true`; avoid `TESTCONTAINERS_HOST_OVERRIDE=localhost` unless needed. `TESTCONTAINERS_DEBUG=true` for details. |
| **Testcontainers cannot find Docker**                | `docker context show` and `docker context inspect "$(docker context show)"` — align `DOCKER_HOST` with `Endpoints.docker.Host` if required.                                                                                                                                             |
| **Configure fails with `25.0.2`**                    | Launcher JVM must be JDK 21 — set `JAVA_HOME` and `PATH` as in **Requirements**, then `./gradlew -version`.                                                                                                                                                                             |
| **Build fails before tests**                         | Often wrong JDK or network; separate from Docker.                                                                                                                                                                                                                                       |
| **Clean rebuild**                                    | `pa1/build/` is gitignored: `rm -rf pa1/build` if you need a clean run.                                                                                                                                                                                                                 |


