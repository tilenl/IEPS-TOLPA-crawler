# IEPS-TOLPA Crawler (PA1)

## Project Description
This project is a preferential web crawler designed to explore the `github.com` domain, specifically focusing on repositories related to **image segmentation**. The crawler follows the `robots.txt` rules of the target domains and implements a multi-worker architecture with a database-backed URL frontier.

## Installation and Setup
1. **Prerequisites**:
   - Java 21+
   - PostgreSQL
   - Chrome/Chromium browser (for Selenium headless rendering)
   - Maven/Gradle (depending on the final build system choice)

2. **Database Setup**:
   - Create a PostgreSQL database named `crawldb`.
   - Run the provided `crawldb.sql` script to initialize the schema.

3. **Running the Crawler**:
   - Build the project using your build tool.
   - Run the main class with the required parameters (e.g., number of workers).

## Database Import Instructions (pgAdmin)
1. Open pgAdmin and connect to your PostgreSQL server.
2. Right-click on the `Databases` node and select `Create` > `Database...`. Name it `crawldb`.
3. Right-click on the newly created `crawldb` database and select `Restore...`.
4. In the `Filename` field, select the `pa1/db` file (Custom format).
5. Click `Restore` to import the data.
