import React, { useState } from "react";
import { Order } from "../types/trading";
import { Clock, CheckCircle2, XCircle, ArrowRight, ChevronDown, ChevronRight, Copy, Check, AlertTriangle } from "lucide-react";

interface RecentOrdersProps {
  orders: Order[];
  onViewAll?: () => void;
}

export const RecentOrders: React.FC<RecentOrdersProps> = ({ orders, onViewAll }) => {
  const recentOrders = orders.slice(0, 5);
  const [expandedOrderId, setExpandedOrderId] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const toggleExpand = (orderId: string) => {
    setExpandedOrderId((prev) => (prev === orderId ? null : orderId));
  };

  const handleCopy = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    navigator.clipboard.writeText(id);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const getStatusBadge = (status: Order["status"]) => {
    switch (status) {
      case "COMPLETED":
      case "FILLED":
        return (
          <span className="inline-flex items-center gap-1 text-[11px] font-mono text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 px-2 py-0.5 rounded">
            <CheckCircle2 className="w-3 h-3" /> {status}
          </span>
        );
      case "REJECTED":
      case "FAILED":
        return (
          <span className="inline-flex items-center gap-1 text-[11px] font-mono text-red-400 bg-red-500/10 border border-red-500/20 px-2 py-0.5 rounded">
            <XCircle className="w-3 h-3" /> {status}
          </span>
        );
      case "PENDING":
      case "WORKING":
        return (
          <span className="inline-flex items-center gap-1 text-[11px] font-mono text-amber-400 bg-amber-500/10 border border-amber-500/20 px-2 py-0.5 rounded">
            <Clock className="w-3 h-3" /> {status}
          </span>
        );
      default:
        return (
          <span className="inline-flex items-center text-[11px] font-mono text-gray-400 bg-gray-800 px-2 py-0.5 rounded">
            {status}
          </span>
        );
    }
  };

  return (
    <div className="bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-3.5">
      {/* Header (without "Latest 5" tag) */}
      <div className="flex items-center justify-between gap-3 pb-3 border-b border-[#232936]">
        <div className="flex items-center space-x-2.5">
          <Clock className="w-4 h-4 text-blue-400" />
          <h3 className="text-sm font-semibold text-white tracking-wide">Recent Orders</h3>
        </div>

        {onViewAll && (
          <button
            onClick={onViewAll}
            className="flex items-center space-x-1.5 text-xs font-medium text-blue-400 hover:text-blue-300 transition group"
          >
            <span>View full audit trail</span>
            <ArrowRight className="w-3.5 h-3.5 group-hover:translate-x-0.5 transition-transform" />
          </button>
        )}
      </div>

      {/* Orders Table */}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead>
            <tr className="border-b border-[#232936] text-gray-400 font-mono text-[11px]">
              <th className="w-8 py-2 px-2 text-center"></th>
              <th className="py-2 px-3">Time</th>
              <th className="py-2 px-3">Symbol</th>
              <th className="py-2 px-3">Side</th>
              <th className="py-2 px-3 text-right">Qty</th>
              <th className="py-2 px-3 text-right">Price</th>
              <th className="py-2 px-3">Status</th>
              <th className="py-2 px-3">Strategy</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#232936]/50 font-mono text-xs">
            {recentOrders.length === 0 ? (
              <tr>
                <td colSpan={8} className="py-6 text-center text-gray-500 text-xs">
                  No orders recorded yet.
                </td>
              </tr>
            ) : (
              recentOrders.map((order) => {
                const isExpanded = expandedOrderId === order.orderId;
                return (
                  <React.Fragment key={order.orderId}>
                    <tr
                      onClick={() => toggleExpand(order.orderId)}
                      className={`cursor-pointer transition ${
                        isExpanded ? "bg-[#1A202C]/70" : "hover:bg-[#1E2430]/40"
                      }`}
                    >
                      <td className="py-2.5 px-2 text-center text-gray-400">
                        {isExpanded ? (
                          <ChevronDown className="w-3.5 h-3.5 text-blue-400 inline-block" />
                        ) : (
                          <ChevronRight className="w-3.5 h-3.5 text-gray-500 hover:text-gray-300 inline-block" />
                        )}
                      </td>
                      <td className="py-2.5 px-3 text-gray-400 whitespace-nowrap">
                        {new Date(order.timestamp).toLocaleTimeString([], {
                          hour: "2-digit",
                          minute: "2-digit",
                          second: "2-digit",
                        })}
                      </td>
                      <td className="py-2.5 px-3 font-bold text-white">{order.symbol}</td>
                      <td className="py-2.5 px-3">
                        <span
                          className={`px-2 py-0.5 rounded text-[11px] font-bold ${
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
                      <td className="py-2.5 px-3 text-gray-400 text-[11px] truncate max-w-[180px]">
                        {order.strategy || "Direct Execution"}
                      </td>
                    </tr>

                    {/* Expandable Order ID & Details Drawer */}
                    {isExpanded && (
                      <tr className="bg-[#0B0E14]/90 border-b border-[#232936]">
                        <td colSpan={8} className="p-0">
                          <div className="p-4 space-y-3 border-l-2 border-blue-500 bg-[#0E121A]/60 ml-2 my-1 mr-2 rounded-r-lg">
                            <div className="flex flex-wrap items-center justify-between gap-3 pb-2.5 border-b border-[#232936]/60">
                              <div className="flex items-center flex-wrap gap-2">
                                <span className="text-gray-400 font-semibold uppercase text-[10px] tracking-wider">
                                  Full Order ID:
                                </span>
                                <span className="text-emerald-400 font-mono font-bold select-all bg-[#151922] px-2.5 py-1 rounded border border-[#232936] text-xs">
                                  {order.orderId}
                                </span>
                                <button
                                  onClick={(e) => handleCopy(order.orderId, e)}
                                  className="flex items-center gap-1 text-[11px] text-blue-400 hover:text-blue-300 bg-blue-500/10 border border-blue-500/20 px-2 py-1 rounded transition"
                                  title="Copy Order ID"
                                >
                                  {copiedId === order.orderId ? (
                                    <>
                                      <Check className="w-3 h-3 text-emerald-400" />
                                      <span className="text-emerald-400 font-medium">Copied</span>
                                    </>
                                  ) : (
                                    <>
                                      <Copy className="w-3 h-3" />
                                      <span>Copy</span>
                                    </>
                                  )}
                                </button>
                              </div>
                              <span className="text-[11px] text-gray-500 font-mono">
                                UTC: {new Date(order.timestamp).toISOString()}
                              </span>
                            </div>

                            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-[11px]">
                              <div className="bg-[#151922] p-2.5 rounded border border-[#232936]/60">
                                <span className="text-gray-500 block text-[10px]">STRATEGY</span>
                                <span className="text-gray-200 font-semibold">
                                  {order.strategy || "Direct Execution"}
                                </span>
                              </div>
                              <div className="bg-[#151922] p-2.5 rounded border border-[#232936]/60">
                                <span className="text-gray-500 block text-[10px]">PROVIDER</span>
                                <span className="text-gray-200 font-semibold uppercase">
                                  {order.provider || "Alpaca"}
                                </span>
                              </div>
                              <div className="bg-[#151922] p-2.5 rounded border border-[#232936]/60">
                                <span className="text-gray-500 block text-[10px]">FILLED / TOTAL QTY</span>
                                <span className="text-gray-200 font-semibold">
                                  {order.filledQty ?? 0} / {order.qty}
                                </span>
                              </div>
                              <div className="bg-[#151922] p-2.5 rounded border border-[#232936]/60">
                                <span className="text-gray-500 block text-[10px]">FILLED AVG PRICE</span>
                                <span className="text-gray-200 font-semibold">
                                  {order.filledAvgPrice && order.filledAvgPrice > 0
                                    ? `$${order.filledAvgPrice.toFixed(2)}`
                                    : `$${order.price.toFixed(2)}`}
                                </span>
                              </div>
                            </div>

                            {order.rejectReason && (
                              <div className="bg-red-500/10 border border-red-500/20 p-2.5 rounded flex items-center gap-2 text-red-400 text-xs">
                                <AlertTriangle className="w-4 h-4 flex-shrink-0" />
                                <span>{order.rejectReason}</span>
                              </div>
                            )}
                          </div>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};
