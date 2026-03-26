# How to run tests

This project’s Gradle build lives under `pa1/`. All commands below assume your current directory is the repository root (`IEPS-TOLPA-crawler`) unless noted.

## Use the Gradle Wrapper, not Homebrew `gradle`

**Do not run tests with a system-wide Gradle from Homebrew** (e.g. `brew install gradle`). Homebrew may install a **new** Gradle (for example **9.4.1**). That version **does not work** with this project’s build (you can see failures such as JUnit Platform not loading on the test classpath).

**Always use the Gradle Wrapper** from `pa1/`:

```bash
cd pa1
./gradlew test
```

On first run, `./gradlew` downloads the distribution pinned in [`pa1/gradle/wrapper/gradle-wrapper.properties`](pa1/gradle/wrapper/gradle-wrapper.properties) — currently **Gradle 8.10.2** — which is the version this app build is intended to use. That gives everyone the same Gradle regardless of what is installed on the machine.

If `./gradlew` is not executable on Unix, run `chmod +x pa1/gradlew` once.

## Prerequisites

- **Java 21** — The build uses `languageVersion = 21` in `pa1/build.gradle.kts`. Point `JAVA_HOME` at a JDK 21 install.

  ```bash
  /usr/libexec/java_home -V
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export PATH="$JAVA_HOME/bin:$PATH"
  java -version   # should show 21.x
  ```

- **Docker for Testcontainers** — Integration tests under `pa1/crawler/src/test/java/.../integration/` use [Testcontainers](https://java.testcontainers.org/) to start a temporary PostgreSQL container. The Docker CLI must work (`docker ps`), and the JVM must be able to reach the Docker API socket.

## Running the full test suite

```bash
cd pa1
./gradlew test
```

### Colima (common on macOS)

If you use Colima, Testcontainers often needs the same socket Docker CLI uses. Your context may point at `~/.colima/default/docker.sock` instead of `/var/run/docker.sock`. In that case, set:

```bash
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
export TESTCONTAINERS_HOST_OVERRIDE="localhost"
```

Then:

```bash
cd pa1
./gradlew test --rerun-tasks --no-daemon
```

`--rerun-tasks` forces tests to execute even when Gradle thinks nothing changed; `--no-daemon` avoids stale JVM/Docker detection issues in some setups.

### Docker Desktop (default socket)

If Docker Desktop exposes the usual Unix socket and `docker ps` works without extra env vars, you can usually run:

```bash
cd pa1
./gradlew test
```

If Testcontainers still reports that it cannot find Docker, set `DOCKER_HOST` to match `docker context inspect` (see **Troubleshooting** below).

## Running only the PostgreSQL integration tests

```bash
cd pa1
./gradlew test --tests '*PageRepositoryIntegrationTest' --rerun-tasks --no-daemon
```

(Use the same `DOCKER_*` / `TESTCONTAINERS_*` exports as above if required on your machine.)

## What “passing” looks like

- The command exits with code **0** and Gradle prints **BUILD SUCCESSFUL**.
- JUnit XML under `pa1/build/test-results/test/` should show the integration class with `skipped="0"` when Docker is available, for example:

  - `TEST-si.uni_lj.fri.wier.integration.storage.postgres.PageRepositoryIntegrationTest.xml`

HTML report: `pa1/build/reports/tests/test/index.html`.

## Troubleshooting

- **Integration tests skipped (`skipped="5"`)** — Testcontainers could not detect Docker (no socket / wrong context). Fix `docker ps`, then align environment variables with your Docker context:

  ```bash
  docker context show
  docker context inspect "$(docker context show)"
  ```

  Use the `Endpoints.docker.Host` value as `DOCKER_HOST` if needed.

- **Wrong Java version** — If the build uses Java 25 (or 11) while the project targets 21, set `JAVA_HOME` to JDK 21 before invoking `./gradlew`, as in Prerequisites.

- **Build output directories** — `pa1/build/` is gitignored; delete it if you need a completely clean test run (`rm -rf pa1/build`).
