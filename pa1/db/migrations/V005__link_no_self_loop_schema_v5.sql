-- Upgrade crawldb schema from version 4 → 5.
-- Enforce no self-loop edges in crawldb.link (from_page = to_page).

DELETE FROM crawldb.link WHERE from_page = to_page;

ALTER TABLE crawldb.link
    ADD CONSTRAINT ck_link_no_self_loop CHECK (from_page <> to_page);

INSERT INTO crawldb.schema_version (id, version) VALUES (1, 5)
ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version;
