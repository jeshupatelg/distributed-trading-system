import { useState, useEffect, useRef, useCallback } from "react";
import { MarketTick, Order, RiskStatus } from "../types/trading";
import { wsUrl } from "../utils/api";

export function useTradingStream() {
  const [isConnected, setIsConnected] = useState(false);
  const [ticks, setTicks] = useState<Record<string, MarketTick>>({});
  const [recentOrders, setRecentOrders] = useState<Order[]>([]);
  const [riskStatus, setRiskStatus] = useState<RiskStatus | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<any>(null);

  const connect = useCallback(() => {
    try {
      const targetWsUrl = wsUrl("/ws");

      const ws = new WebSocket(targetWsUrl);
      wsRef.current = ws;

      ws.onopen = () => {
        setIsConnected(true);
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          if (data.type === "tick" && data.payload) {
            setTicks((prev) => ({
              ...prev,
              [data.payload.symbol]: data.payload,
            }));
          } else if (data.type === "risk" && data.payload) {
            setRiskStatus(data.payload);
          } else if (data.type === "order" && data.payload) {
            setRecentOrders((prev) => [data.payload, ...prev.slice(0, 49)]);
          }
        } catch (e) {
          console.error("Failed to parse WS message", e);
        }
      };

      ws.onclose = () => {
        setIsConnected(false);
        reconnectTimeoutRef.current = setTimeout(connect, 3000);
      };

      ws.onerror = () => {
        ws.close();
      };
    } catch (e) {
      setIsConnected(false);
      reconnectTimeoutRef.current = setTimeout(connect, 3000);
    }
  }, []);

  useEffect(() => {
    connect();
    return () => {
      if (wsRef.current) wsRef.current.close();
      if (reconnectTimeoutRef.current) clearTimeout(reconnectTimeoutRef.current);
    };
  }, [connect]);

  return { isConnected, ticks, recentOrders, riskStatus, setRiskStatus };
}
