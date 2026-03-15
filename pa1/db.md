# PA1 Database Submission Instructions

## Final Submission Requirement
The final submission in the `pa1` directory should only contain the `pa1/db` file, which is a Custom pgAdmin export of the `crawldb` database.

## Export Instructions
1. Open pgAdmin and connect to your PostgreSQL server.
2. Right-click on the `crawldb` database and select `Backup...`.
3. In the `Filename` field, choose the name `db` and place it in the `pa1/` directory.
4. Set the `Format` to `Custom`.
5. Under the `Dump options` tab, ensure that you **do not include images or binary data** (e.g., set `Only data` to `No`, but ensure `No data` is not selected; specifically, the assignment says: "The database dump you submit should not contain images or binary data").
6. Click `Backup` to export the file.

## Submission Note
This `db.md` file is for development purposes only and should be removed before final submission. The `pa1/db` file must be exported after the crawler has finished its crawl.
