import crypto from "node:crypto";
import { createPool } from "../server/db.js";

function pick(rng, arr) {
  return arr[Math.floor(rng() * arr.length)];
}

function mulberry32(seed) {
  let t = seed >>> 0;
  return function rng() {
    t += 0x6d2b79f5;
    let x = t;
    x = Math.imul(x ^ (x >>> 15), x | 1);
    x ^= x + Math.imul(x ^ (x >>> 7), x | 61);
    return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
  };
}

function sha256hex(s) {
  return crypto.createHash("sha256").update(s).digest("hex");
}

function shortNow() {
  return new Date().toISOString();
}

async function truncateAll(client) {
  await client.query(`
    TRUNCATE TABLE
      crawldb.link,
      crawldb.image,
      crawldb.page_data,
      crawldb.content_owner,
      crawldb.page,
      crawldb.site
    RESTART IDENTITY CASCADE;
  `);
}

async function insertSite(client, domain) {
  const res = await client.query(
    `INSERT INTO crawldb.site(domain) VALUES ($1) RETURNING id`,
    [domain]
  );
  return res.rows[0].id;
}

async function insertPages(client, pages) {
  const values = [];
  const params = [];
  let i = 1;
  for (const p of pages) {
    values.push(
      `($${i++}, $${i++}, $${i++}, $${i++}, $${i++}, $${i++}, $${i++}, $${i++}, $${i++}, $${i++})`
    );
    params.push(
      p.site_id,
      p.page_type_code,
      p.url,
      p.html_content,
      p.http_status_code,
      p.accessed_time,
      p.relevance_score,
      p.next_attempt_at,
      p.attempt_count,
      p.content_hash
    );
  }

  const res = await client.query(
    `
    INSERT INTO crawldb.page(
      site_id, page_type_code, url, html_content, http_status_code, accessed_time,
      relevance_score, next_attempt_at, attempt_count, content_hash
    )
    VALUES ${values.join(",")}
    RETURNING id, url, page_type_code, site_id, content_hash
    `,
    params
  );
  return res.rows;
}

async function insertContentOwners(client, owners) {
  if (owners.length === 0) return;
  const values = [];
  const params = [];
  let i = 1;
  for (const o of owners) {
    values.push(`($${i++}, $${i++}, $${i++})`);
    params.push(o.content_hash, o.owner_page_id, o.created_at);
  }
  await client.query(
    `
    INSERT INTO crawldb.content_owner(content_hash, owner_page_id, created_at)
    VALUES ${values.join(",")}
    `,
    params
  );
}

async function insertLinks(client, links) {
  if (links.length === 0) return;
  const values = [];
  const params = [];
  let i = 1;
  for (const l of links) {
    values.push(`($${i++}, $${i++})`);
    params.push(l.from_page, l.to_page);
  }
  await client.query(
    `
    INSERT INTO crawldb.link(from_page, to_page)
    VALUES ${values.join(",")}
    ON CONFLICT DO NOTHING
    `,
    params
  );
}

async function main() {
  const seed = Number.parseInt(process.env.VIZ_SEED ?? "42", 10);
  const rng = mulberry32(Number.isFinite(seed) ? seed : 42);

  const domains = (process.env.VIZ_DOMAINS ?? "example.com,foo.si,bar.gov.si")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);

  const perDomainHtml = Number.parseInt(process.env.VIZ_HTML_PER_DOMAIN ?? "80", 10);
  const perDomainDup = Number.parseInt(process.env.VIZ_DUP_PER_DOMAIN ?? "15", 10);
  const perDomainFrontier = Number.parseInt(process.env.VIZ_FRONTIER_PER_DOMAIN ?? "10", 10);
  const perDomainError = Number.parseInt(process.env.VIZ_ERROR_PER_DOMAIN ?? "5", 10);
  const perDomainBinary = Number.parseInt(process.env.VIZ_BINARY_PER_DOMAIN ?? "5", 10);
  const crossDomainLinks = Number.parseInt(process.env.VIZ_CROSS_DOMAIN_LINKS ?? "60", 10);
  const intraDomainLinksPerPage = Number.parseInt(process.env.VIZ_INTRA_LINKS_PER_PAGE ?? "3", 10);

  const pool = createPool();
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await truncateAll(client);

    const siteIds = new Map();
    for (const d of domains) {
      const id = await insertSite(client, d);
      siteIds.set(d, id);
    }

    const allPages = [];
    const canonicalByHash = new Map(); // content_hash -> { id, url, site_id }
    const domainHtmlIds = new Map(); // domain -> [page_id]
    const domainDupIds = new Map(); // domain -> [page_id]
    const domainOtherIds = new Map(); // domain -> { frontier: [], error: [], binary: [] }

    // 1) HTML pages (canonical)
    for (const domain of domains) {
      const site_id = siteIds.get(domain);
      const pages = [];
      for (let i = 0; i < perDomainHtml; i++) {
        const path = i === 0 ? "/" : `/p/${i}`;
        const url = `https://${domain}${path}`;
        const content_hash = sha256hex(`HTML|${domain}|${Math.floor(i / 3)}`); // some repeated-ish content
        pages.push({
          site_id,
          page_type_code: "HTML",
          url,
          html_content: `<html><head><title>${domain} ${i}</title></head><body>${domain} ${i}</body></html>`,
          http_status_code: 200,
          accessed_time: shortNow(),
          relevance_score: rng(),
          next_attempt_at: shortNow(),
          attempt_count: 1,
          content_hash
        });
      }

      const inserted = await insertPages(client, pages);
      domainHtmlIds.set(domain, inserted.map((r) => r.id));
      for (const row of inserted) {
        if (!canonicalByHash.has(row.content_hash)) {
          canonicalByHash.set(row.content_hash, {
            owner_page_id: row.id
          });
        }
        allPages.push(row);
      }
    }

    // 2) DUPLICATE pages (share content_hash with some HTML canonical)
    for (const domain of domains) {
      const site_id = siteIds.get(domain);
      const htmlIds = domainHtmlIds.get(domain) ?? [];
      const pages = [];
      for (let i = 0; i < perDomainDup; i++) {
        const dupUrl = `https://${domain}/dup/${i}`;
        const canonicalId = pick(rng, htmlIds);
        const canonicalPage = allPages.find((p) => p.id === canonicalId);
        const content_hash = canonicalPage?.content_hash ?? sha256hex(`FALLBACK|${domain}|${i}`);

        pages.push({
          site_id,
          page_type_code: "DUPLICATE",
          url: dupUrl,
          html_content: null,
          http_status_code: 200,
          accessed_time: shortNow(),
          relevance_score: rng(),
          next_attempt_at: shortNow(),
          attempt_count: 1,
          content_hash
        });
      }

      const inserted = await insertPages(client, pages);
      domainDupIds.set(domain, inserted.map((r) => r.id));
      for (const row of inserted) allPages.push(row);
    }

    // 2b) Other page states (FRONTIER / ERROR / BINARY)
    for (const domain of domains) {
      const site_id = siteIds.get(domain);
      const pages = [];

      for (let i = 0; i < perDomainFrontier; i++) {
        pages.push({
          site_id,
          page_type_code: "FRONTIER",
          url: `https://${domain}/frontier/${i}`,
          html_content: null,
          http_status_code: null,
          accessed_time: null,
          relevance_score: rng(),
          next_attempt_at: shortNow(),
          attempt_count: 0,
          content_hash: null
        });
      }

      for (let i = 0; i < perDomainError; i++) {
        pages.push({
          site_id,
          page_type_code: "ERROR",
          url: `https://${domain}/error/${i}`,
          html_content: null,
          http_status_code: 500,
          accessed_time: shortNow(),
          relevance_score: rng(),
          next_attempt_at: shortNow(),
          attempt_count: 3,
          content_hash: null
        });
      }

      for (let i = 0; i < perDomainBinary; i++) {
        pages.push({
          site_id,
          page_type_code: "BINARY",
          url: `https://${domain}/file/${i}.pdf`,
          html_content: null,
          http_status_code: 200,
          accessed_time: shortNow(),
          relevance_score: rng(),
          next_attempt_at: shortNow(),
          attempt_count: 1,
          content_hash: null
        });
      }

      const inserted = await insertPages(client, pages);
      const frontier = [];
      const error = [];
      const binary = [];
      for (const row of inserted) {
        allPages.push(row);
        if (row.page_type_code === "FRONTIER") frontier.push(row.id);
        if (row.page_type_code === "ERROR") error.push(row.id);
        if (row.page_type_code === "BINARY") binary.push(row.id);
      }
      domainOtherIds.set(domain, { frontier, error, binary });
    }

    // 3) content_owner rows (one per hash)
    const owners = [];
    for (const [content_hash, o] of canonicalByHash.entries()) {
      owners.push({ content_hash, owner_page_id: o.owner_page_id, created_at: shortNow() });
    }
    await insertContentOwners(client, owners);

    // 4) links
    const links = [];

    // intra-domain links (HTML pages)
    for (const domain of domains) {
      const htmlIds = domainHtmlIds.get(domain) ?? [];
      for (const fromId of htmlIds) {
        for (let k = 0; k < intraDomainLinksPerPage; k++) {
          const toId = pick(rng, htmlIds);
          if (toId && toId !== fromId) links.push({ from_page: fromId, to_page: toId });
        }
      }
    }

    // duplicates link to canonical owner (makes DUPLICATE -> HTML edges visible)
    for (const domain of domains) {
      const dupIds = domainDupIds.get(domain) ?? [];
      for (const dupId of dupIds) {
        const dupPage = allPages.find((p) => p.id === dupId);
        const owner = dupPage?.content_hash ? canonicalByHash.get(dupPage.content_hash) : null;
        if (owner?.owner_page_id && owner.owner_page_id !== dupId) {
          links.push({ from_page: dupId, to_page: owner.owner_page_id });
        }
      }
    }

    // cross-domain links between homepages-ish
    const allHtml = domains.flatMap((d) => domainHtmlIds.get(d) ?? []);
    for (let i = 0; i < crossDomainLinks; i++) {
      const fromId = pick(rng, allHtml);
      const toId = pick(rng, allHtml);
      if (fromId && toId && fromId !== toId) links.push({ from_page: fromId, to_page: toId });
    }

    // connect other states to HTML so they appear in the graph
    for (const domain of domains) {
      const htmlIds = domainHtmlIds.get(domain) ?? [];
      const other = domainOtherIds.get(domain);
      if (!other || htmlIds.length === 0) continue;

      for (const fromId of other.frontier) {
        const toId = pick(rng, htmlIds);
        if (toId) links.push({ from_page: fromId, to_page: toId });
      }
      for (const fromId of other.error) {
        const toId = pick(rng, htmlIds);
        if (toId) links.push({ from_page: fromId, to_page: toId });
      }
      for (const binId of other.binary) {
        const fromId = pick(rng, htmlIds);
        if (fromId) links.push({ from_page: fromId, to_page: binId });
      }
    }

    await insertLinks(client, links);

    await client.query("COMMIT");

    const stats = await client.query(`
      SELECT
        (SELECT COUNT(*) FROM crawldb.site) AS sites,
        (SELECT COUNT(*) FROM crawldb.page) AS pages,
        (SELECT COUNT(*) FROM crawldb.link) AS links,
        (SELECT COUNT(*) FROM crawldb.page WHERE page_type_code='HTML') AS html_pages,
        (SELECT COUNT(*) FROM crawldb.page WHERE page_type_code='DUPLICATE') AS duplicate_pages,
        (SELECT COUNT(*) FROM crawldb.page WHERE page_type_code='FRONTIER') AS frontier_pages,
        (SELECT COUNT(*) FROM crawldb.page WHERE page_type_code='ERROR') AS error_pages,
        (SELECT COUNT(*) FROM crawldb.page WHERE page_type_code='BINARY') AS binary_pages
    `);
    // eslint-disable-next-line no-console
    console.log("Seed done:", stats.rows[0]);
  } catch (e) {
    await client.query("ROLLBACK");
    // eslint-disable-next-line no-console
    console.error("Seed failed:", e);
    process.exitCode = 1;
  } finally {
    client.release();
    await pool.end();
  }
}

await main();

