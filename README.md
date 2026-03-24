# IEPS-TOLPA-crawler

The crawler represents the end submission for our faculty subject: Searching and extracting data from the web.

The first programming assignment can be found inside the `pa1/` subfolder.

## Running and testing the crawler

All Gradle commands below are run from the **`pa1/`** directory (where `build.gradle.kts` lives). The project targets **Java 21** (see the Gradle Java toolchain).

### Tests

**Using Docker (recommended if you do not have JDK 21 / Gradle installed locally):**

```bash
cd pa1
docker run --rm -v "$(pwd):/workspace" -w /workspace gradle:8.10.2-jdk21 gradle test --no-daemon
```

This image bundles Gradle 8.10.2 and JDK 21; dependencies are resolved from Maven Central on first run.

**Using a local Gradle installation:**

```bash
cd pa1
gradle test
```

You need a compatible JDK (21) available to Gradle, either as `JAVA_HOME` or on the `PATH`.

### Running the application

The application entry point is `si.uni_lj.fri.wier.cli.Main`. It loads `crawler/src/main/resources/application.properties` from the classpath. Adjust database and other settings there (or override via mechanisms you add later) before expecting a full crawl.

**Docker:**

```bash
cd pa1
docker run --rm -v "$(pwd):/workspace" -w /workspace gradle:8.10.2-jdk21 gradle run --no-daemon
```

**Local:**

```bash
cd pa1
gradle run
```

There is no Gradle wrapper (`gradlew`) in this repository yet; use a system `gradle` or the Docker command above.
