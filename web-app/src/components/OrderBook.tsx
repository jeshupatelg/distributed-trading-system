import React, { useState } from "react";
import { Order } from "../types/trading";
import { Layers, CheckCircle2, XCircle, Clock, AlertTriangle } from "lucide-react";

interface OrderBookProps {
  orders: Order[];
}

export const OrderBook: React.FC<OrderBookProps> = ({ orders }) => {
  const [filter, setFilter] = useState<string>("ALL");

  const filteredOrders = orders.filter((o) => {
    if (filter === "ALL") return true;
    return o.status === filter;
  });

  const getStatusBadge = (status: Order["status"]) => {
    switch (status) {
      case "COMPLETED":
      case "FILLED":
        return (
          <span className="flex items-center gap-1 text-xs font-mono text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 px-2 py-0.5 rounded">
            <CheckCircle2 className="w-3 h-3" /> {status}
          </span>
        );
      case "REJECTED":
      case "FAILED":
        return (
          <span className="flex items-center gap-1 text-xs font-mono text-red-400 bg-red-500/10 border border-red-500/20 px-2 py-0.5 rounded">
            <XCircle className="w-3 h-3" /> {status}
          </span>
        );
      case "PENDING":
      case "WORKING":
        return (
          <span className="flex items-center gap-1 text-xs font-mono text-amber-400 bg-amber-500/10 border border-amber-500/20 px-2 py-0.5 rounded">
            <Clock className="w-3 h-3" /> {status}
          </span>
        );
      default:
        return (
          <span className="text-xs font-mono text-gray-400 bg-gray-800 px-2 py-0.5 rounded">
            {status}
          </span>
        );
    }
  };

  return (
    <div className="bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 pb-3 border-b border-[#232936]">
        <div className="flex items-center space-x-2">
          <Layers className="w-4 h-4 text-blue-400" />
          <h3 className="text-sm font-semibold text-white">Execution Audit Trail & Order Feed</h3>
          <span className="text-xs bg-[#0B0E14] text-gray-400 font-mono px-2 py-0.5 rounded border border-[#232936]">
            {filteredOrders.length} events
          </span>
        </div>

        {/* Filter Chips */}
        <div className="flex items-center space-x-1.5 bg-[#0B0E14] p-1 rounded-lg border border-[#232936]">
          {["ALL", "COMPLETED", "FILLED", "REJECTED", "PENDING"].map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`px-2.5 py-1 text-xs font-mono rounded transition ${
                filter === f ? "bg-blue-600 text-white font-semibold shadow-sm" : "text-gray-400 hover:text-white"
              }`}
            >
              {f}
            </button>
          ))}
        </div>
      </div>

      {/* PostgreSQL Direct Read Pool Status Ribbon */}
      <div className="bg-[#0B0E14] border border-[#232936] rounded-lg p-2.5 flex flex-wrap items-center justify-between gap-2 text-xs">
        <div className="flex items-center space-x-2 text-gray-300">
          <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
          <span className="font-semibold text-white">PostgreSQL Direct Read Pool</span>
          <span className="text-gray-500 font-mono text-[11px] hidden sm:inline">— Live read-only pool on tracked_orders table (zero OMS write-path load)</span>
        </div>
        <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
          SQL LIVE FEED
        </span>
      </div>

      {/* Orders Table */}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead>
            <tr className="border-b border-[#232936] text-gray-400 font-mono">
              <th className="py-2.5 px-3">Timestamp</th>
              <th className="py-2.5 px-3">Order ID</th>
              <th className="py-2.5 px-3">Symbol</th>
              <th className="py-2.5 px-3">Side</th>
              <th className="py-2.5 px-3 text-right">Qty</th>
              <th className="py-2.5 px-3 text-right">Price</th>
              <th className="py-2.5 px-3">Status</th>
              <th className="py-2.5 px-3">Reason / Audit Diagnostic</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#232936]/60 font-mono">
            {filteredOrders.length === 0 ? (
              <tr>
                <td colSpan={8} className="py-8 text-center text-gray-500">
                  No orders recorded for current filter criteria.
                </td>
              </tr>
            ) : (
              filteredOrders.map((order) => (
                <tr key={order.orderId} className="hover:bg-[#1E2430]/40 transition">
                  <td className="py-2.5 px-3 text-gray-400 whitespace-nowrap">
                    {new Date(order.timestamp).toLocaleTimeString()}
                  </td>
                  <td className="py-2.5 px-3 text-gray-300 font-semibold truncate max-w-[120px]">
                    {order.orderId}
                  </td>
                  <td className="py-2.5 px-3 font-bold text-white">{order.symbol}</td>
                  <td className="py-2.5 px-3">
                    <span
                      className={`px-2 py-0.5 rounded font-bold ${
                        order.side === "BUY"
                          ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
                          : "bg-red-500/10 text-red-400 border border-red-500/20"
                      }`}
                    >
                      {order.side}
                    </span>
                  </td>
                  <td className="py-2.5 px-3 text-right text-gray-200">{order.qty}</td>
                  <td className="py-2.5 px-3 text-right text-white font-semibold">
                    ${order.price.toFixed(2)}
                  </td>
                  <td className="py-2.5 px-3">{getStatusBadge(order.status)}</td>
                  <td className="py-2.5 px-3 text-gray-400 truncate max-w-[240px]">
                    {order.rejectReason ? (
                      <span className="text-red-400 flex items-center gap-1">
                        <AlertTriangle className="w-3 h-3 flex-shrink-0" />
                        <span className="truncate">{order.rejectReason}</span>
                      </span>
                    ) : (
                      <span className="text-gray-400 font-mono text-[11px]">
                        {order.strategy ? `Strategy: ${order.strategy}` : "Direct Execution"}
                        {order.filledAvgPrice && order.filledAvgPrice > 0 ? ` @ $${order.filledAvgPrice.toFixed(2)}` : ""}
                      </span>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="pt-2 border-t border-[#232936] text-[11px] text-gray-500 flex items-center justify-between">
        <span>Order feed aggregated via Node.js Fastify BFF.</span>
        <span>Direct read-only pool on PostgreSQL (tracked_orders). Zero write-path load on OMS.</span>
      </div>
    </div>
  );
};
