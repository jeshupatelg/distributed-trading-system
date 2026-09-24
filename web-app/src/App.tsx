import React, { useState, useEffect, useCallback } from "react";
import { Header } from "./components/Header";
import { RiskCenter } from "./components/RiskCenter";
import { InformativeRiskCards } from "./components/InformativeRiskCards";
import { ChartSection } from "./components/ChartSection";
import { OrderBook } from "./components/OrderBook";
import { RecentOrders } from "./components/RecentOrders";
import { TelemetryMatrix } from "./components/TelemetryMatrix";
import { NotificationCenter } from "./components/NotificationCenter";
import { useTradingStream } from "./hooks/useTradingStream";
import { RiskStatus, Order } from "./types/trading";
import { LayoutDashboard, Shield, Layers, Server, Bell } from "lucide-react";

export default function App() {
  const [selectedProvider, setSelectedProvider] = useState<string>("alpaca");
  const [activeTab, setActiveTab] = useState<string>("cockpit");
  const [isChartExpanded, setIsChartExpanded] = useState<boolean>(false);
  const { isConnected, ticks, recentOrders, riskStatus, setRiskStatus } = useTradingStream();
  const [orders, setOrders] = useState<Order[]>([]);

  // Fetch initial risk status from BFF
  const fetchRiskStatus = useCallback(() => {
    fetch(`/api/v1/risk/status?provider=${selectedProvider}`)
      .then((res) => res.json())
      .then((data: RiskStatus) => {
        if (data) setRiskStatus(data);
      })
      .catch((err) => console.error("Error fetching risk status:", err));
  }, [selectedProvider, setRiskStatus]);

  // Fetch initial orders from BFF
  const fetchOrders = useCallback(() => {
    fetch(`/api/v1/orders?provider=${selectedProvider}&limit=25`)
      .then((res) => res.json())
      .then((data: any) => {
        const list = Array.isArray(data) ? data : data?.orders;
        if (Array.isArray(list)) setOrders(list);
      })
      .catch((err) => console.error("Error fetching orders:", err));
  }, [selectedProvider]);

  useEffect(() => {
    fetchRiskStatus();
    fetchOrders();
    const interval = setInterval(fetchRiskStatus, 5000);
    return () => clearInterval(interval);
  }, [fetchRiskStatus, fetchOrders]);

  // Merge websocket-streamed orders with REST fetched orders
  const allOrders = [...recentOrders, ...orders.filter((o) => !recentOrders.some((ro) => ro.orderId === o.orderId))];

  return (
    <div className="min-h-screen flex flex-col bg-[#0B0E14] text-gray-100">
      {/* 1. Cockpit Header with Emergency Kill Switch */}
      <Header
        riskStatus={riskStatus}
        selectedProvider={selectedProvider}
        onProviderChange={setSelectedProvider}
        isWsConnected={isConnected}
        onRefresh={() => {
          fetchRiskStatus();
          fetchOrders();
        }}
      />

      {/* 2. Cockpit Navigation Tabs */}
      <nav className="bg-[#151922] border-b border-[#232936] px-6 flex items-center space-x-6 text-xs font-semibold">
        <button
          onClick={() => setActiveTab("cockpit")}
          className={`py-3 flex items-center gap-2 border-b-2 transition ${
            activeTab === "cockpit"
              ? "border-blue-500 text-blue-400"
              : "border-transparent text-gray-400 hover:text-gray-200"
          }`}
        >
          <LayoutDashboard className="w-4 h-4" />
          <span>Trading Cockpit</span>
        </button>

        <button
          onClick={() => setActiveTab("risk")}
          className={`py-3 flex items-center gap-2 border-b-2 transition ${
            activeTab === "risk"
              ? "border-blue-500 text-blue-400"
              : "border-transparent text-gray-400 hover:text-gray-200"
          }`}
        >
          <Shield className="w-4 h-4" />
          <span>Risk & Safety Gates</span>
        </button>

        <button
          onClick={() => setActiveTab("orders")}
          className={`py-3 flex items-center gap-2 border-b-2 transition ${
            activeTab === "orders"
              ? "border-blue-500 text-blue-400"
              : "border-transparent text-gray-400 hover:text-gray-200"
          }`}
        >
          <Layers className="w-4 h-4" />
          <span>Order Audit Trail</span>
        </button>

        <button
          onClick={() => setActiveTab("notifications")}
          className={`py-3 flex items-center gap-2 border-b-2 transition ${
            activeTab === "notifications"
              ? "border-blue-500 text-blue-400"
              : "border-transparent text-gray-400 hover:text-gray-200"
          }`}
        >
          <Bell className="w-4 h-4" />
          <span>Notification Center</span>
        </button>

        <button
          onClick={() => setActiveTab("telemetry")}
          className={`py-3 flex items-center gap-2 border-b-2 transition ${
            activeTab === "telemetry"
              ? "border-blue-500 text-blue-400"
              : "border-transparent text-gray-400 hover:text-gray-200"
          }`}
        >
          <Server className="w-4 h-4" />
          <span>System Telemetry</span>
        </button>
      </nav>

      {/* 3. Main Dashboard Body */}
      <main className="flex-1 p-4 lg:p-6 max-w-[1720px] w-full mx-auto space-y-6">
        {activeTab === "cockpit" && (
          <div className="space-y-6">
            {isChartExpanded ? (
              <div className="space-y-6">
                <ChartSection
                  ticks={ticks}
                  isExpanded={true}
                  onToggleExpand={() => setIsChartExpanded(false)}
                />
                <InformativeRiskCards
                  riskStatus={riskStatus}
                  provider={selectedProvider}
                  isHorizontal={true}
                />
              </div>
            ) : (
              <div className="grid grid-cols-1 xl:grid-cols-12 gap-6 items-start">
                <div className="xl:col-span-8">
                  <ChartSection
                    ticks={ticks}
                    isExpanded={false}
                    onToggleExpand={() => setIsChartExpanded(true)}
                  />
                </div>
                <div className="xl:col-span-4">
                  <InformativeRiskCards
                    riskStatus={riskStatus}
                    provider={selectedProvider}
                    isHorizontal={false}
                  />
                </div>
              </div>
            )}

            {/* Bottom Dock: Recent Orders (Latest 5) */}
            <RecentOrders orders={allOrders} onViewAll={() => setActiveTab("orders")} />
          </div>
        )}

        {activeTab === "risk" && (
          <div className="space-y-6">
            <RiskCenter riskStatus={riskStatus} provider={selectedProvider} onRefresh={fetchRiskStatus} />
          </div>
        )}

        {activeTab === "orders" && (
          <div className="space-y-6">
            <OrderBook provider={selectedProvider} />
          </div>
        )}

        {activeTab === "notifications" && (
          <div className="space-y-6">
            <NotificationCenter />
          </div>
        )}

        {activeTab === "telemetry" && (
          <div className="space-y-6">
            <TelemetryMatrix />
          </div>
        )}
      </main>

      {/* 4. Footer */}
      <footer className="bg-[#151922] border-t border-[#232936] px-6 py-3 text-xs text-gray-500 flex flex-wrap items-center justify-between gap-4 font-mono">
        <div>Distributed Trading System — Frontend Gateway BFF Architecture (ADR 0003)</div>
        <div className="flex items-center space-x-4">
          <span>Port 3030 Isolated</span>
          <span>BFF: Fastify + Node.js</span>
          <span>UI: React 18 + Vite</span>
        </div>
      </footer>
    </div>
  );
}
