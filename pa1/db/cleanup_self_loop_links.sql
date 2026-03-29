-- One-time cleanup: remove self-referential link rows (from_page = to_page).
-- Run on existing databases (e.g. postgresql-wier) after deploying the crawler fix, or before applying
-- pa1/db/migrations/V005__link_no_self_loop_schema_v5.sql if those rows would violate ck_link_no_self_loop.

DELETE FROM crawldb.link WHERE from_page = to_page;
