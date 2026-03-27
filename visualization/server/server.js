import express from "express";
import cors from "cors";
import { createProfilePools } from "./db.js";
import path from "node:path";
import { fileURLToPath } from "node:url";

function parseCsv(raw) {
  if (!raw) return [];
  return raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

const app = express();
app.use(cors());

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const webDir = path.resolve(__dirname, "../web");

// Serve the UI at `/` so visiting http://localhost:3001 works.
app.use(express.static(webDir));

const pools = createProfilePools();

function resolveProfile(req) {
  const profileRaw = String(req.query.profile ?? "mock").toLowerCase();
  return profileRaw === "real" ? "real" : "mock";
}

app.get("/health", async (req, res) => {
  try {
    const profile = resolveProfile(req);
    const pool = pools[profile];
    const r = await pool.query("SELECT 1 AS ok");
    res.json({ ok: r.rows[0].ok === 1, profile });
  } catch (e) {
    res.status(500).json({ ok: false, error: e?.message ?? String(e) });
  }
});

app.get("/graph", async (req, res) => {
  try {
    const profile = resolveProfile(req);
    const pool = pools[profile];
    const maxNodesRaw = Number.parseInt(req.query.maxNodes ?? "500", 10);
    const maxNodes = Number.isFinite(maxNodesRaw)
      ? Math.max(1, Math.min(maxNodesRaw, 5000))
      : 500;

    const groupByDomain =
      req.query.groupByDomain === "true" || req.query.groupByDomain === "1";

    const onlyTypes = parseCsv(req.query.onlyTypes);
    const domains = Array.isArray(req.query.domain)
      ? req.query.domain.map(String)
      : req.query.domain
        ? [String(req.query.domain)]
        : [];

    if (groupByDomain) {
      const params = [];
      let i = 1;
      const where = [];
      if (domains.length > 0) {
        where.push(`(s1.domain = ANY($${i}) OR s2.domain = ANY($${i}))`);
        params.push(domains);
        i++;
      }
      if (onlyTypes.length > 0) {
        where.push(
          `(p1.page_type_code = ANY($${i}) AND p2.page_type_code = ANY($${i}))`
        );
        params.push(onlyTypes);
        i++;
      }

      const q = `
        SELECT
          s1.domain AS from_domain,
          s2.domain AS to_domain,
          COUNT(*)::int AS weight
        FROM crawldb.link l
        JOIN crawldb.page p1 ON p1.id = l.from_page
        JOIN crawldb.site s1 ON s1.id = p1.site_id
        JOIN crawldb.page p2 ON p2.id = l.to_page
        JOIN crawldb.site s2 ON s2.id = p2.site_id
        ${where.length ? `WHERE ${where.join(" AND ")}` : ""}
        GROUP BY s1.domain, s2.domain
        ORDER BY weight DESC
        LIMIT 5000
      `;
      const r = await pool.query(q, params);

      const nodeSet = new Set();
      for (const row of r.rows) {
        nodeSet.add(row.from_domain);
        nodeSet.add(row.to_domain);
      }
      const nodes = [...nodeSet].map((d) => ({ id: d, label: d, group: "DOMAIN" }));
      const edges = r.rows.map((row) => ({
        from: row.from_domain,
        to: row.to_domain,
        value: row.weight,
        title: `links: ${row.weight}`
      }));
      return res.json({ mode: "domain", profile, nodes, edges });
    }

    const params = [];
    let i = 1;
    const where = [];
    if (domains.length > 0) {
      where.push(`s.domain = ANY($${i})`);
      params.push(domains);
      i++;
    }
    if (onlyTypes.length > 0) {
      where.push(`p.page_type_code = ANY($${i})`);
      params.push(onlyTypes);
      i++;
    }

    params.push(maxNodes);

    const q = `
      WITH selected_pages AS (
        SELECT
          p.id,
          p.url,
          p.page_type_code,
          s.domain
        FROM crawldb.page p
        JOIN crawldb.site s ON s.id = p.site_id
        ${where.length ? `WHERE ${where.join(" AND ")}` : ""}
        ORDER BY p.id ASC
        LIMIT $${i}
      ),
      selected_edges AS (
        SELECT l.from_page, l.to_page
        FROM crawldb.link l
        JOIN selected_pages a ON a.id = l.from_page
        JOIN selected_pages b ON b.id = l.to_page
        LIMIT 20000
      )
      SELECT
        (SELECT json_agg(sp) FROM selected_pages sp) AS nodes,
        (SELECT json_agg(se) FROM selected_edges se) AS edges
    `;

    const r = await pool.query(q, params);
    const nodesRaw = r.rows[0].nodes ?? [];
    const edgesRaw = r.rows[0].edges ?? [];

    const nodes = nodesRaw.map((n) => ({
      id: n.id,
      label: n.url.length > 60 ? `${n.url.slice(0, 57)}...` : n.url,
      title: `${n.url}\n${n.domain}\n${n.page_type_code}`,
      group: n.page_type_code
    }));
    const edges = edgesRaw.map((e) => ({ from: e.from_page, to: e.to_page }));

    res.json({ mode: "page", profile, nodes, edges });
  } catch (e) {
    res.status(500).json({ error: e?.message ?? String(e) });
  }
});

const port = Number.parseInt(process.env.VIZ_PORT ?? "3001", 10) || 3001;
app.listen(port, () => {
  // eslint-disable-next-line no-console
  console.log(`viz api listening on http://localhost:${port}`);
});

