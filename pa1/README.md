# IEPS-TOLPA Crawler (PA1)

## Project Description

This project is a preferential web crawler designed to explore the `github.com` domain, specifically focusing on repositories related to **image segmentation**. The crawler follows the `robots.txt` rules of the target domains and implements a multi-worker architecture with a database-backed URL frontier.

## Installation and Setup

1. **Prerequisites**:
   - **JDK 21**: Required for Java 21 Virtual Threads and modern language features.
   - **Docker**: Recommended for running the PostgreSQL database (see `pa1/db/database-setup.md` for the full setup flow).
   - **Chrome/Chromium**: Required for Selenium headless rendering.
   - **Gradle Wrapper**: No global Gradle installation is needed; the project uses `./gradlew`.

2. **Database Setup**:
   - The project uses PostgreSQL.
   - For the full staged setup process (prerequisites, Docker startup, manual import path, troubleshooting, and verification checks), see `pa1/db/database-setup.md`.
   - The schema source file remains `pa1/db/crawldb.sql`.

3. **Building and Running**:
   - **Build**: `./gradlew build`
   - **Run**: `./gradlew run --args="--n-crawlers 5"` (example with 5 workers)

## Database Import Instructions (pgAdmin)

1. Open pgAdmin and connect to your PostgreSQL server.
2. Right-click on the `Databases` node and select `Create` > `Database...`. Name it `crawldb`.
3. Right-click on the newly created `crawldb` database and select `Restore...`.
4. In the `Filename` field, select the `pa1/db` file (Custom format).
5. Click `Restore` to import the data.
