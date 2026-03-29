import pg from "pg";

const { Pool } = pg;

function envInt(name, fallback) {
  const raw = process.env[name];
  if (!raw) return fallback;
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) ? n : fallback;
}

/** @returns {boolean | null} null = not set */
function parseSslToggle(raw) {
  if (raw === undefined || raw === "") return null;
  const v = String(raw).toLowerCase();
  if (["1", "true", "yes", "require", "on"].includes(v)) return true;
  if (["0", "false", "no", "off", "disable"].includes(v)) return false;
  return null;
}

/**
 * Resolves TLS for node-pg. Honors per-profile env, then global VIZ_DB_SSL, then PGSSLMODE.
 * Self-signed / dev: set VIZ_DB_SSL_REJECT_UNAUTHORIZED=false
 */
function readSsl(prefix) {
  const p = prefix ? `${prefix}_` : "";
  const explicit =
    parseSslToggle(process.env[`${p}VIZ_DB_SSL`]) ??
    parseSslToggle(process.env.VIZ_DB_SSL);
  if (explicit === false) return undefined;
  if (explicit === true) return buildSslOptions();

  const mode = (process.env.PGSSLMODE || "").toLowerCase();
  if (["require", "verify-ca", "verify-full"].includes(mode)) {
    return buildSslOptions();
  }
  return undefined;
}

function buildSslOptions() {
  const raw = process.env.VIZ_DB_SSL_REJECT_UNAUTHORIZED;
  const rejectUnauthorized =
    raw === undefined || raw === ""
      ? true
      : !["0", "false", "no", "off"].includes(String(raw).toLowerCase());
  return rejectUnauthorized ? { rejectUnauthorized: true } : { rejectUnauthorized: false };
}

function readConfig(prefix) {
  const p = prefix ? `${prefix}_` : "";
  return {
    host: process.env[`${p}VIZ_DB_HOST`] ?? process.env.VIZ_DB_HOST ?? "127.0.0.1",
    port: envInt(`${p}VIZ_DB_PORT`, envInt("VIZ_DB_PORT", 5432)),
    user: process.env[`${p}VIZ_DB_USER`] ?? process.env.VIZ_DB_USER ?? "user",
    password:
      process.env[`${p}VIZ_DB_PASSWORD`] ??
      process.env.VIZ_DB_PASSWORD ??
      "SecretPassword",
    database: process.env[`${p}VIZ_DB_NAME`] ?? process.env.VIZ_DB_NAME ?? "crawldb",
    ssl: readSsl(prefix || undefined)
  };
}

export function createPool(config = readConfig("")) {
  const poolConfig = {
    host: config.host,
    port: config.port,
    user: config.user,
    password: config.password,
    database: config.database
  };
  if (config.ssl) {
    poolConfig.ssl = config.ssl;
  }
  return new Pool(poolConfig);
}

export function createProfilePools() {
  return {
    mock: createPool(readConfig("MOCK")),
    real: createPool(readConfig("REAL"))
  };
}
