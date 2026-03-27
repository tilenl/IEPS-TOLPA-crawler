import pg from "pg";

const { Pool } = pg;

function envInt(name, fallback) {
  const raw = process.env[name];
  if (!raw) return fallback;
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) ? n : fallback;
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
    database: process.env[`${p}VIZ_DB_NAME`] ?? process.env.VIZ_DB_NAME ?? "crawldb"
  };
}

export function createPool(config = readConfig("")) {
  return new Pool({
    host: config.host,
    port: config.port,
    user: config.user,
    password: config.password,
    database: config.database
  });
}

export function createProfilePools() {
  return {
    mock: createPool(readConfig("MOCK")),
    real: createPool(readConfig("REAL"))
  };
}

