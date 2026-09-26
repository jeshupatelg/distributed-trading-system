import fastify, { FastifyInstance } from "fastify";
import cors from "@fastify/cors";
import fastifyStatic from "@fastify/static";
import fastifyWs from "@fastify/websocket";
import path from "path";
import fs from "fs";
import { fileURLToPath } from "url";
import dotenv from "dotenv";
import { Redis } from "ioredis";
import pg from "pg";
const { Pool } = pg;

dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = parseInt(process.env.PORT || "3030", 10);
const HOST = process.env.HOST || "0.0.0.0";

// Configurable context path:
// Rule: No defaults in application code. If process.env.CONTEXT_PATH is not provided or empty, contextPath is "".
// If provided (e.g. "/web-ui", "web-ui", "/web-ui/"), normalize to leading slash and no trailing slash ("/web-ui").
const rawContextPath = process.env.CONTEXT_PATH?.trim();
const contextPath = rawContextPath && rawContextPath !== "/"
  ? `/${rawContextPath.replace(/^\/+|\/+$/g, "")}`
  : "";

const OPS_URL = process.env.OPS_URL || "http://order-processing-service:8081";
const OMS_URL = process.env.OMS_URL || "http://order-management-service:8082";
const NOTIFICATION_URL = process.env.NOTIFICATION_URL || "http://notification-service:8085";
const PRICE_CACHE_URL = process.env.PRICE_CACHE_URL || "http://price-cache-service:8080";
const ALPACA_URL = process.env.ALPACA_URL || "http://connection-manager-alpaca:8000";

const REDIS_HOST = process.env.REDIS_HOST || "host.docker.internal";
const REDIS_PORT = parseInt(process.env.REDIS_PORT || "6379", 10);
const REDIS_PASSWORD = process.env.REDIS_PASSWORD || undefined;

const DB_HOST = process.env.DB_HOST || "host.docker.internal";
const DB_PORT = parseInt(process.env.DB_PORT || "5432", 10);
const DB_NAME = process.env.DB_NAME || "trading_agent";
const DB_USER = process.env.DB_USER || "admin";
const DB_PASSWORD = process.env.DB_PASSWORD || "admin";

// Read-only pool cap to prevent connection starvation on PostgreSQL
const dbPool = new Pool({
  host: DB_HOST,
  port: DB_PORT,
  database: DB_NAME,
  user: DB_USER,
  password: DB_PASSWORD,
  max: 5,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 3000,
});

dbPool.query("SELECT 1")
  .then(() => {
    console.log(`[BFF] Connected to PostgreSQL at ${DB_HOST}:${DB_PORT}/${DB_NAME}`);
  })
  .catch((err: any) => {
    console.warn(`[BFF] Initial PostgreSQL connection warning (${err.message}). Retrying lazily...`);
  });

// Connect to Redis for real-time market price ingestion (fed by price-cache-service)
let redis: Redis | null = null;
try {
  redis = new Redis({
    host: REDIS_HOST,
    port: REDIS_PORT,
    password: REDIS_PASSWORD,
    lazyConnect: true,
    maxRetriesPerRequest: 1,
    retryStrategy: (times: number) => Math.min(times * 200, 3000),
  });

  redis.connect().then(() => {
    console.log(`[BFF] Successfully connected to Redis price cache at ${REDIS_HOST}:${REDIS_PORT}`);
  }).catch((err: any) => {
    console.warn(`[BFF] Initial Redis connection warning (${err.message}). Retrying in background...`);
  });
} catch (err: any) {
  console.warn(`[BFF] Redis initialization failed: ${err.message}`);
}

async function getLivePricesFromRedis(symbols: string[]): Promise<Record<string, number>> {
  const result: Record<string, number> = {};
  if (!redis) return result;
  try {
    const keys = symbols.map((s) => `market:last_price:${s.toUpperCase()}`);
    const vals = await redis.mget(...keys);
    symbols.forEach((sym, i) => {
      const v = vals[i];
      if (v) {
        const p = parseFloat(v);
        if (!isNaN(p) && p > 0) {
          result[sym.toUpperCase()] = p;
        }
      }
    });
  } catch (err: any) {
    // Redis query transient error
  }
  return result;
}

const server = fastify({
  logger: {
    level: process.env.LOG_LEVEL || "info",
  },
});

const distPath = path.join(__dirname, "../dist");

function renderIndexHtml(): string {
  const indexPath = path.join(distPath, "index.html");
  if (!fs.existsSync(indexPath)) {
    return "<!doctype html><html><body>App bundle not found. Please build the frontend.</body></html>";
  }
  let html = fs.readFileSync(indexPath, "utf-8");
  const baseTag = `<base href="${contextPath ? `${contextPath}/` : "/"}" />`;
  const scriptTag = `<script>window.__CONTEXT_PATH__ = ${JSON.stringify(contextPath)};</script>`;

  if (html.includes("<head>")) {
    html = html.replace("<head>", `<head>\n    ${baseTag}\n    ${scriptTag}`);
  } else {
    html = `${baseTag}\n${scriptTag}\n${html}`;
  }
  return html;
}

// All BFF REST and WebSocket routes encapsulated in a Fastify plugin for prefix support
async function registerRoutes(app: FastifyInstance) {
  // 1. Risk Status Proxy
  app.get("/api/v1/risk/status", async (req, reply) => {
    const { provider = "alpaca" } = req.query as { provider?: string };
    try {
      const res = await fetch(`${OPS_URL}/api/v1/risk/status?provider=${provider}`);
      if (!res.ok) throw new Error(`OPS responded with status ${res.status}`);
      const data = await res.json();
      return reply.send(data);
    } catch (err: any) {
      server.log.warn(`OPS unavailable at ${OPS_URL}. Returning fallback telemetry: ${err.message}`);
      return reply.send({
        status: "FALLBACK_NORMAL",
        kill_switch_active: false,
        max_daily_loss: 2000,
        current_drawdown: 0.0,
        price_collar_pct: 1.5,
        provider,
        circuit_breaker_tripped: false,
        blocked_margin: 0.0,
        cash_balance: 100000.0,
      });
    }
  });

  // 2. Risk Config Proxy
  app.get("/api/v1/risk/config", async (req, reply) => {
    const { provider = "alpaca" } = req.query as { provider?: string };
    try {
      const res = await fetch(`${OPS_URL}/api/v1/risk/config?provider=${provider}`);
      if (!res.ok) throw new Error(`OPS status ${res.status}`);
      return reply.send(await res.json());
    } catch (err: any) {
      return reply.send({
        max_daily_loss: 2000,
        price_collar_pct: 1.5,
        max_order_size: 500,
        provider,
      });
    }
  });

  app.post("/api/v1/risk/config", async (req, reply) => {
    const { provider = "alpaca" } = req.query as { provider?: string };
    try {
      const res = await fetch(`${OPS_URL}/api/v1/risk/config?provider=${provider}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(req.body),
      });
      return reply.status(res.status).send(await res.json());
    } catch (err: any) {
      server.log.error(`Failed to update config at ${OPS_URL}: ${err.message}`);
      return reply.status(502).send({ error: "Failed to forward risk config to OPS", details: err.message });
    }
  });

  // 3. Emergency Kill Switch Triggers
  app.post("/api/v1/risk/kill-switch/trigger", async (req, reply) => {
    const { provider = "alpaca", liquidate = "true" } = req.query as { provider?: string; liquidate?: string };
    try {
      const res = await fetch(`${OPS_URL}/api/v1/risk/kill-switch/trigger?liquidate=${liquidate}&provider=${provider}`, {
        method: "POST",
      });
      return reply.status(res.status).send(await res.json());
    } catch (err: any) {
      return reply.status(502).send({ error: "OPS kill-switch trigger failed", details: err.message });
    }
  });

  app.post("/api/v1/risk/kill-switch/reset", async (req, reply) => {
    const { provider = "alpaca" } = req.query as { provider?: string };
    try {
      const res = await fetch(`${OPS_URL}/api/v1/risk/kill-switch/reset?provider=${provider}`, {
        method: "POST",
      });
      return reply.status(res.status).send(await res.json());
    } catch (err: any) {
      return reply.status(502).send({ error: "OPS kill-switch reset failed", details: err.message });
    }
  });

  // 4. Order Feed Proxy (Direct Read-Only DB Query from PostgreSQL with Filtering & Pagination)
  app.get("/api/v1/orders", async (req, reply) => {
    const {
      symbol,
      side,
      status,
      strategy,
      provider,
      dateRange,
      startDate,
      endDate,
      page = "1",
      limit = "25",
    } = req.query as {
      symbol?: string;
      side?: string;
      status?: string;
      strategy?: string;
      provider?: string;
      dateRange?: string;
      startDate?: string;
      endDate?: string;
      page?: string;
      limit?: string;
    };

    const pageNum = Math.max(parseInt(page, 10) || 1, 1);
    const pageLimit = Math.min(Math.max(parseInt(limit, 10) || 25, 1), 100);
    const offset = (pageNum - 1) * pageLimit;

    try {
      const conditions: string[] = [];
      const params: any[] = [];

      if (symbol && symbol !== "ALL") {
        params.push(symbol.toUpperCase());
        conditions.push(`symbol = $${params.length}`);
      }
      if (side && side !== "ALL") {
        params.push(side.toUpperCase());
        conditions.push(`side = $${params.length}`);
      }
      if (status && status !== "ALL") {
        params.push(status.toUpperCase());
        conditions.push(`status = $${params.length}`);
      }
      if (strategy && strategy !== "ALL") {
        params.push(strategy);
        conditions.push(`strategy = $${params.length}`);
      }
      if (provider && provider !== "ALL") {
        params.push(provider.toLowerCase());
        conditions.push(`provider = $${params.length}`);
      }

      // Date Filtering
      if (dateRange && dateRange !== "all") {
        if (dateRange === "today") {
          conditions.push(`created_at >= CURRENT_DATE`);
        } else if (dateRange === "24h") {
          conditions.push(`created_at >= NOW() - INTERVAL '24 hours'`);
        } else if (dateRange === "7d") {
          conditions.push(`created_at >= NOW() - INTERVAL '7 days'`);
        } else if (dateRange === "30d") {
          conditions.push(`created_at >= NOW() - INTERVAL '30 days'`);
        }
      } else {
        if (startDate) {
          params.push(new Date(startDate).toISOString());
          conditions.push(`created_at >= $${params.length}`);
        }
        if (endDate) {
          params.push(new Date(endDate).toISOString());
          conditions.push(`created_at <= $${params.length}`);
        }
      }

      const whereClause = conditions.length > 0 ? ` WHERE ` + conditions.join(" AND ") : "";

      // 1. Total Count Query for pagination
      const countQuery = `SELECT COUNT(*) as total FROM tracked_orders${whereClause}`;
      const countRes = await dbPool.query(countQuery, params);
      const total = parseInt(countRes.rows[0]?.total || "0", 10);
      const totalPages = Math.ceil(total / pageLimit) || 1;

      // 2. Paginated Data Query
      const dataParams = [...params];
      dataParams.push(pageLimit);
      const limitIndex = dataParams.length;
      dataParams.push(offset);
      const offsetIndex = dataParams.length;

      const dataQuery = `
        SELECT 
          order_id,
          symbol,
          side,
          qty,
          limit_price,
          status,
          provider,
          strategy,
          filled_qty,
          filled_avg_price,
          created_at
        FROM tracked_orders
        ${whereClause}
        ORDER BY created_at DESC 
        LIMIT $${limitIndex} OFFSET $${offsetIndex}
      `;

      const { rows } = await dbPool.query(dataQuery, dataParams);

      const orders = (rows || []).map((r: any) => ({
        orderId: r.order_id,
        symbol: r.symbol,
        side: r.side,
        qty: r.qty,
        price: r.limit_price ? parseFloat(r.limit_price) : 0,
        status: r.status,
        provider: r.provider || "alpaca",
        strategy: r.strategy || "Manual",
        filledQty: r.filled_qty ? parseInt(r.filled_qty, 10) : 0,
        filledAvgPrice: r.filled_avg_price ? parseFloat(r.filled_avg_price) : 0,
        timestamp: r.created_at ? new Date(r.created_at).toISOString() : new Date().toISOString(),
      }));

      reply.header("X-Data-Source", "PostgreSQL-Read-Direct");
      reply.header("X-Total-Count", total.toString());
      reply.header("X-Page", pageNum.toString());
      reply.header("X-Total-Pages", totalPages.toString());

      return reply.send({
        orders,
        pagination: {
          page: pageNum,
          limit: pageLimit,
          total,
          totalPages,
        },
      });
    } catch (err: any) {
      server.log.warn(`[BFF] Failed querying PostgreSQL tracked_orders: ${err.message}`);
    }

    reply.header("X-Data-Source", "Empty-Buffer");
    return reply.send({
      orders: [],
      pagination: {
        page: pageNum,
        limit: pageLimit,
        total: 0,
        totalPages: 1,
      },
    });
  });

  // 5. System Health & Telemetry Aggregation
  app.get("/api/v1/system/health", async (_req, reply) => {
    const checks = [
      { name: "Order Processing Service (OPS)", endpoint: `${OPS_URL}/actuator/health` },
      { name: "Order Management Service (OMS)", endpoint: `${OMS_URL}/actuator/health` },
      { name: "Price Cache Service", endpoint: `${PRICE_CACHE_URL}/health` },
      { name: "Notification Service", endpoint: `${NOTIFICATION_URL}/health` },
      { name: "Alpaca Connection Gateway", endpoint: `${ALPACA_URL}/health` },
      { name: "PostgreSQL Database (Read Pool)", endpoint: `${DB_HOST}:${DB_PORT}/${DB_NAME}`, isDb: true },
    ];

    const results = await Promise.allSettled(
      checks.map(async (c) => {
        const start = Date.now();
        if (c.isDb) {
          await dbPool.query("SELECT 1");
          return {
            name: c.name,
            status: "healthy" as const,
            endpoint: c.endpoint,
            latencyMs: Date.now() - start,
            lastChecked: new Date().toISOString(),
          };
        }
        const res = await fetch(c.endpoint, { signal: AbortSignal.timeout(2000) });
        const latency = Date.now() - start;
        return {
          name: c.name,
          status: res.ok ? ("healthy" as const) : ("degraded" as const),
          endpoint: c.endpoint,
          latencyMs: latency,
          lastChecked: new Date().toISOString(),
        };
      })
    );

    const statuses = results.map((r, i) => {
      if (r.status === "fulfilled") return r.value;
      return {
        name: checks[i].name,
        status: "unreachable" as const,
        endpoint: checks[i].endpoint,
        latencyMs: 2000,
        lastChecked: new Date().toISOString(),
      };
    });

    return reply.send(statuses);
  });

  // 6. Real-Time Price Cache Query (Redis Direct)
  app.get("/api/v1/market/prices", async (req, reply) => {
    const { symbols = "AAPL,MSFT" } = req.query as { symbols?: string };
    const symList = symbols.split(",").map((s) => s.trim().toUpperCase()).filter(Boolean);
    const prices = await getLivePricesFromRedis(symList);
    return reply.send({
      timestamp: Date.now(),
      prices,
      source: "redis",
    });
  });

  // 7. Notification Service Management Proxy
  app.get("/api/v1/notify/status", async (_req, reply) => {
    try {
      const res = await fetch(`${NOTIFICATION_URL}/api/v1/notify/status`, {
        signal: AbortSignal.timeout(3000),
      });
      if (res.ok) {
        const data = await res.json();
        return reply.send(data);
      }
      return reply.status(res.status).send({ error: "Failed to fetch notification status from microservice" });
    } catch (err: any) {
      server.log.warn(`[BFF] Notification status proxy error: ${err.message}`);
      return reply.status(503).send({ error: "Notification service unreachable", details: err.message });
    }
  });

  app.post("/api/v1/notify/test", async (req, reply) => {
    try {
      const res = await fetch(`${NOTIFICATION_URL}/api/v1/notify/test`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(req.body || {}),
        signal: AbortSignal.timeout(10000),
      });
      const data = await res.json();
      return reply.status(res.status).send(data);
    } catch (err: any) {
      server.log.warn(`[BFF] Notification test proxy error: ${err.message}`);
      return reply.status(503).send({ error: "Notification service unreachable", details: err.message });
    }
  });

  app.post("/api/v1/notify/config", async (req, reply) => {
    if (!redis) {
      return reply.status(503).send({ error: "Redis not connected for config persistence" });
    }
    try {
      const body = req.body as any;
      if (body.telegram) {
        if (body.telegram.token !== undefined) await redis.set("notify:config:telegram:token", body.telegram.token);
        if (body.telegram.chat_id !== undefined) await redis.set("notify:config:telegram:chat_id", body.telegram.chat_id);
        if (body.telegram.topic_id !== undefined) await redis.set("notify:config:telegram:topic_id", body.telegram.topic_id);
        if (body.telegram.enabled !== undefined) await redis.set("notify:config:telegram:enabled", String(body.telegram.enabled));
      }
      if (body.ntfy) {
        if (body.ntfy.url !== undefined) await redis.set("notify:config:ntfy:url", body.ntfy.url);
        if (body.ntfy.topic !== undefined) await redis.set("notify:config:ntfy:topic", body.ntfy.topic);
        if (body.ntfy.token !== undefined) await redis.set("notify:config:ntfy:token", body.ntfy.token);
        if (body.ntfy.enabled !== undefined) await redis.set("notify:config:ntfy:enabled", String(body.ntfy.enabled));
      }
      if (body.evolution) {
        if (body.evolution.url !== undefined) await redis.set("notify:config:evolution:url", body.evolution.url);
        if (body.evolution.apikey !== undefined) await redis.set("notify:config:evolution:apikey", body.evolution.apikey);
        if (body.evolution.instance !== undefined) await redis.set("notify:config:evolution:instance", body.evolution.instance);
        if (body.evolution.recipient !== undefined) await redis.set("notify:config:evolution:recipient", body.evolution.recipient);
        if (body.evolution.enabled !== undefined) await redis.set("notify:config:evolution:enabled", String(body.evolution.enabled));
      }
      if (body.filters) {
        if (body.filters.notify_on_reject !== undefined) await redis.set("notify:config:filter:reject", String(body.filters.notify_on_reject));
        if (body.filters.notify_on_order_create !== undefined) await redis.set("notify:config:filter:order_create", String(body.filters.notify_on_order_create));
        if (body.filters.notify_on_order_fill !== undefined) await redis.set("notify:config:filter:order_fill", String(body.filters.notify_on_order_fill));
        if (body.filters.notify_on_kill_switch !== undefined) await redis.set("notify:config:filter:kill_switch", String(body.filters.notify_on_kill_switch));
      }
      return reply.send({ success: true, message: "Configuration persisted to Redis" });
    } catch (err: any) {
      server.log.warn(`[BFF] Notification config save error: ${err.message}`);
      return reply.status(500).send({ error: "Failed to persist configuration to Redis", details: err.message });
    }
  });

  // 8. WebSocket Streaming Route
  app.get("/ws", { websocket: true }, (connection: any) => {
    server.log.info("Client connected to trading stream WebSocket");
    const ws = connection?.socket ?? connection;
    if (!ws) {
      server.log.error("Could not obtain WebSocket reference from connection");
      return;
    }

    // Stream real live prices from Redis for AAPL and MSFT
    const trackedSymbols = ["AAPL", "MSFT"];
    let lastKnownPrices: Record<string, number> = {
      AAPL: 336.87,
      MSFT: 499.06,
    };

    const interval = setInterval(async () => {
      if (ws.readyState !== 1) return; // 1 = OPEN

      try {
        const redisPrices = await getLivePricesFromRedis(trackedSymbols);

        for (const symbol of trackedSymbols) {
          let price = redisPrices[symbol];
          let isLive = true;
          if (price) {
            lastKnownPrices[symbol] = price;
          } else {
            price = lastKnownPrices[symbol] || (symbol === "AAPL" ? 336.87 : 499.06);
            isLive = false;
          }

          const tick = {
            type: "tick",
            payload: {
              symbol,
              price: parseFloat(price.toFixed(2)),
              time: Date.now(),
              source: isLive ? "redis" : "cached_fallback",
            },
          };

          ws.send(JSON.stringify(tick));
        }
      } catch (err: any) {
        // Socket may have closed between check and send
      }
    }, 1000);

    ws.on("close", () => {
      clearInterval(interval);
      server.log.info("Trading stream WebSocket closed");
    });

    ws.on("error", (err: any) => {
      clearInterval(interval);
      server.log.warn(`WebSocket error: ${err.message}`);
    });
  });
}

async function main() {
  await server.register(cors, {
    origin: "*",
  });

  await server.register(fastifyWs);

  // Serve compiled React bundle in production with configurable context path support
  if (contextPath) {
    // 1. Static asset prefix matching configured context path (e.g. /web-ui/assets/...)
    await server.register(fastifyStatic, {
      root: distPath,
      prefix: `${contextPath}/`,
      wildcard: false,
      index: false,
    });
    // 2. Also register static files at root / for direct asset access
    await server.register(fastifyStatic, {
      root: distPath,
      prefix: "/",
      wildcard: false,
      index: false,
      decorateReply: false,
    });
  } else {
    await server.register(fastifyStatic, {
      root: distPath,
      prefix: "/",
      wildcard: false,
      index: false,
    });
  }

  // Register REST and WebSocket routes
  if (contextPath) {
    // Mount routes under contextPath prefix (e.g. /web-ui/api/v1/... and /web-ui/ws)
    await server.register(registerRoutes, { prefix: contextPath });
    // Mount routes at root as alias so calls without context path also succeed
    await server.register(registerRoutes);

    // Redirect context path without trailing slash (e.g. /web-ui -> /web-ui/)
    server.get(contextPath, async (_req, reply) => {
      return reply.redirect(`${contextPath}/`, 302);
    });

    // Explicit route for contextPath/ root SPA document
    server.get(`${contextPath}/`, async (_req, reply) => {
      return reply.type("text/html; charset=utf-8").send(renderIndexHtml());
    });

    // Also redirect root / to contextPath/ when context path is active
    server.get("/", async (_req, reply) => {
      return reply.redirect(`${contextPath}/`, 302);
    });
  } else {
    // No context path configured: register routes directly at root
    await server.register(registerRoutes);

    server.get("/", async (_req, reply) => {
      return reply.type("text/html; charset=utf-8").send(renderIndexHtml());
    });
  }

  // SPA Fallback for client routing
  server.setNotFoundHandler(async (req, reply) => {
    // If it's an API request, return 404 JSON instead of HTML
    if (req.url.includes("/api/")) {
      return reply.status(404).send({ error: "Endpoint not found", path: req.url });
    }

    // If contextPath is configured and path doesn't start with contextPath, redirect to contextPath/
    if (contextPath && !req.url.startsWith(contextPath)) {
      return reply.redirect(`${contextPath}/`, 302);
    }

    return reply.type("text/html; charset=utf-8").send(renderIndexHtml());
  });

  try {
    await server.listen({ port: PORT, host: HOST });
    const fullUrl = `http://${HOST}:${PORT}${contextPath}`;
    server.log.info(`Trading Web Cockpit & BFF Server listening on ${fullUrl} (context path: '${contextPath || "/"}')`);
  } catch (err) {
    server.log.error(err);
    process.exit(1);
  }
}

main();
