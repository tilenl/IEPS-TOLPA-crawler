# IEPS-TOLPA Crawler (PA1)

## Project Description

This project is a preferential web crawler designed to explore the `github.com` domain, specifically focusing on repositories related to **image segmentation**. The crawler follows the `robots.txt` rules of the target domains and implements a multi-worker architecture with a database-backed URL frontier.

## Installation and Setup

1. **Prerequisites**:
   - **JDK 21**: Required for Java 21 Virtual Threads and modern language features.
   - **Docker**: Recommended for running the PostgreSQL database (see `project-plan.md` for Docker commands).
   - **Chrome/Chromium**: Required for Selenium headless rendering.
   - **Gradle Wrapper**: No global Gradle installation is needed; the project uses `./gradlew`.

2. **Database Setup**:
   - The project uses PostgreSQL. A Docker-based setup is recommended:
    ```bash
    # From the repository root
    mkdir -p .docker/pgdata .docker/init-scripts
    cp pa1/db/crawldb.sql .docker/init-scripts/01-crawldb.sql
    docker run --name postgresql-wier \
        -e POSTGRES_PASSWORD=SecretPassword \
        -e POSTGRES_USER=user \
        -e POSTGRES_DB=crawldb \
        -v "$PWD/.docker/pgdata:/var/lib/postgresql/data" \
        -v "$PWD/.docker/init-scripts:/docker-entrypoint-initdb.d" \
        -p 5432:5432 \
        -d pgvector/pgvector:pg17
    ```
   - Alternatively, manually run `pa1/db/crawldb.sql` on a local PostgreSQL instance.

3. **Building and Running**:
   - **Build**: `./gradlew build`
   - **Run**: `./gradlew run --args="--n-crawlers 5"` (example with 5 workers)

## Database Import Instructions (pgAdmin)

1. Open pgAdmin and connect to your PostgreSQL server.
2. Right-click on the `Databases` node and select `Create` > `Database...`. Name it `crawldb`.
3. Right-click on the newly created `crawldb` database and select `Restore...`.
4. In the `Filename` field, select the `pa1/db` file (Custom format).
5. Click `Restore` to import the data.
