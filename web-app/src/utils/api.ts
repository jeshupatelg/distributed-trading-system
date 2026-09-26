/**
 * Dynamic API and WebSocket URL builder supporting configurable context_path.
 * 
 * Context path is injected at runtime by the Fastify BFF server into window.__CONTEXT_PATH__.
 * In accordance with application requirements:
 * - NO default context path is embedded in the application code.
 * - If window.__CONTEXT_PATH__ is not provided or empty, context path is "" (root).
 */

export function getContextPath(): string {
  if (typeof window !== "undefined" && (window as any).__CONTEXT_PATH__ !== undefined) {
    const cp = (window as any).__CONTEXT_PATH__;
    if (typeof cp === "string" && cp.trim() && cp.trim() !== "/") {
      return `/${cp.trim().replace(/^\/+|\/+$/g, "")}`;
    }
    return "";
  }
  return "";
}

/**
 * Prepends the active context path (if configured) to a REST API endpoint.
 *
 * @param path Endpoint path, e.g. "/api/v1/orders"
 * @returns Fully qualified context-aware path, e.g. "/web-ui/api/v1/orders" or "/api/v1/orders"
 */
export function apiUrl(path: string): string {
  const cp = getContextPath();
  const cleanPath = path.startsWith("/") ? path : `/${path}`;
  return cp ? `${cp}${cleanPath}` : cleanPath;
}

/**
 * Builds WebSocket stream URL respecting current protocol, host, and context_path.
 *
 * @param path WebSocket path, defaults to "/ws"
 * @returns Fully qualified WebSocket URL, e.g. "ws://localhost:3030/web-ui/ws"
 */
export function wsUrl(path: string = "/ws"): string {
  const cp = getContextPath();
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const host = window.location.host;
  const cleanPath = path.startsWith("/") ? path : `/${path}`;
  const fullPath = cp ? `${cp}${cleanPath}` : cleanPath;
  return `${protocol}//${host}${fullPath}`;
}
