CREATE SCHEMA IF NOT EXISTS crawldb;

CREATE TABLE crawldb.schema_version (
	id                   integer NOT NULL DEFAULT 1,
	version              integer NOT NULL,
	CONSTRAINT pk_schema_version_id PRIMARY KEY (id),
	CONSTRAINT ck_schema_version_singleton_id CHECK (id = 1)
);

CREATE TABLE crawldb.data_type ( 
	code                 varchar(20)  NOT NULL,
	CONSTRAINT pk_data_type_code PRIMARY KEY ( code )
 );

CREATE TABLE crawldb.page_type ( 
	code                 varchar(20)  NOT NULL,	
	CONSTRAINT pk_page_type_code PRIMARY KEY ( code )
 );

CREATE TABLE crawldb.site ( 
	id                   serial  NOT NULL,
	"domain"             varchar(500)  ,
	robots_content       text  ,
	sitemap_content      text  ,
	CONSTRAINT pk_site_id PRIMARY KEY ( id )
 );

CREATE TABLE crawldb.page ( 
	id                   serial  NOT NULL,
	site_id              integer  ,
	page_type_code       varchar(20)  ,
	url                  varchar(3000)  ,
	html_content         text  ,
	http_status_code     integer  ,
	accessed_time        timestamp  ,
	CONSTRAINT pk_page_id PRIMARY KEY ( id ),
	CONSTRAINT unq_url_idx UNIQUE ( url ) 
 );

CREATE INDEX "idx_page_site_id" ON crawldb.page ( site_id );

CREATE INDEX "idx_page_page_type_code" ON crawldb.page ( page_type_code );

ALTER TABLE crawldb.page
	ADD COLUMN relevance_score double precision NOT NULL DEFAULT 0.0;

ALTER TABLE crawldb.page
	ADD COLUMN next_attempt_at timestamp NOT NULL DEFAULT now();

ALTER TABLE crawldb.page
	ADD COLUMN attempt_count integer NOT NULL DEFAULT 0;

ALTER TABLE crawldb.page
	ADD COLUMN claimed_by varchar(128);

ALTER TABLE crawldb.page
	ADD COLUMN claimed_at timestamp;

ALTER TABLE crawldb.page
	ADD COLUMN claim_expires_at timestamp;

ALTER TABLE crawldb.page
	ADD COLUMN content_hash varchar(64);

ALTER TABLE crawldb.page
	ADD COLUMN last_error_category varchar(64);

ALTER TABLE crawldb.page
	ADD COLUMN last_error_message text;

ALTER TABLE crawldb.page
	ADD COLUMN last_error_at timestamp;

DROP INDEX IF EXISTS crawldb.idx_page_frontier_priority;
CREATE INDEX "idx_page_frontier_priority"
	ON crawldb.page ( page_type_code, relevance_score DESC, next_attempt_at ASC, accessed_time ASC, id ASC );

CREATE INDEX "idx_page_processing_lease"
	ON crawldb.page ( page_type_code, claim_expires_at ASC );

CREATE INDEX "idx_page_content_hash"
	ON crawldb.page ( content_hash );

CREATE TABLE crawldb.page_data ( 
	id                   serial  NOT NULL,
	page_id              integer  ,
	data_type_code       varchar(20)  ,
	"data"               bytea,
	CONSTRAINT pk_page_data_id PRIMARY KEY ( id )
 );

CREATE INDEX "idx_page_data_page_id" ON crawldb.page_data ( page_id );

CREATE INDEX "idx_page_data_data_type_code" ON crawldb.page_data ( data_type_code );

CREATE UNIQUE INDEX unq_page_data_page_id_data_type ON crawldb.page_data ( page_id, data_type_code );

CREATE TABLE crawldb.image ( 
	id                   serial  NOT NULL,
	page_id              integer  ,
	filename             varchar(255)  ,
	content_type         varchar(50)  ,
	"data"               bytea  ,
	accessed_time        timestamp  ,
	CONSTRAINT pk_image_id PRIMARY KEY ( id )
 );

CREATE INDEX "idx_image_page_id" ON crawldb.image ( page_id );

CREATE TABLE crawldb.link ( 
	from_page            integer  NOT NULL,
	to_page              integer  NOT NULL,
	CONSTRAINT _0 PRIMARY KEY ( from_page, to_page )
 );

CREATE INDEX "idx_link_from_page" ON crawldb.link ( from_page );

CREATE INDEX "idx_link_to_page" ON crawldb.link ( to_page );

CREATE TABLE crawldb.content_owner (
	content_hash         varchar(64) NOT NULL,
	owner_page_id        integer NOT NULL,
	created_at           timestamp NOT NULL,
	CONSTRAINT pk_content_owner_hash PRIMARY KEY ( content_hash )
);

ALTER TABLE crawldb.image ADD CONSTRAINT fk_image_page_data FOREIGN KEY ( page_id ) REFERENCES crawldb.page( id ) ON DELETE RESTRICT;

ALTER TABLE crawldb.link ADD CONSTRAINT fk_link_page FOREIGN KEY ( from_page ) REFERENCES crawldb.page( id ) ON DELETE RESTRICT;

ALTER TABLE crawldb.link ADD CONSTRAINT fk_link_page_1 FOREIGN KEY ( to_page ) REFERENCES crawldb.page( id ) ON DELETE RESTRICT;

ALTER TABLE crawldb.page ADD CONSTRAINT fk_page_site FOREIGN KEY ( site_id ) REFERENCES crawldb.site( id ) ON DELETE RESTRICT;

ALTER TABLE crawldb.page ADD CONSTRAINT fk_page_page_type FOREIGN KEY ( page_type_code ) REFERENCES crawldb.page_type( code ) ON DELETE RESTRICT;

ALTER TABLE crawldb.page_data ADD CONSTRAINT fk_page_data_page FOREIGN KEY ( page_id ) REFERENCES crawldb.page( id ) ON DELETE RESTRICT;

ALTER TABLE crawldb.page_data ADD CONSTRAINT fk_page_data_data_type FOREIGN KEY ( data_type_code ) REFERENCES crawldb.data_type( code ) ON DELETE RESTRICT;

ALTER TABLE crawldb.content_owner ADD CONSTRAINT fk_content_owner_page FOREIGN KEY ( owner_page_id ) REFERENCES crawldb.page( id ) ON DELETE RESTRICT;

-- PROCESSING rows must carry a full lease (stale-lease recovery uses claim_expires_at < now(); NULL never matches).
UPDATE crawldb.page
SET page_type_code = 'FRONTIER',
    claimed_by = NULL,
    claimed_at = NULL,
    claim_expires_at = NULL
WHERE page_type_code = 'PROCESSING'
  AND (claim_expires_at IS NULL OR claimed_at IS NULL OR claimed_by IS NULL);

ALTER TABLE crawldb.page
	ADD CONSTRAINT ck_page_processing_lease CHECK (
		page_type_code <> 'PROCESSING'
		OR (
			claim_expires_at IS NOT NULL
			AND claimed_at IS NOT NULL
			AND claimed_by IS NOT NULL
		)
	);

INSERT INTO crawldb.data_type VALUES 
	('PDF'),
	('DOC'),
	('DOCX'),
	('PPT'),
	('PPTX'),
	('TITLE'),
	('META_DESCRIPTION');

INSERT INTO crawldb.page_type VALUES 
	('HTML'),
	('BINARY'),
	('DUPLICATE'),
	('FRONTIER'),
	('PROCESSING'),
	('ERROR');

INSERT INTO crawldb.schema_version (id, version) VALUES (1, 3);