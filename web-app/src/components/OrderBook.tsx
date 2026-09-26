import React, { useState, useEffect, useCallback } from "react";
import { Order, PaginationInfo } from "../types/trading";
import { apiUrl } from "../utils/api";
import {
  Layers,
  CheckCircle2,
  XCircle,
  Clock,
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  Copy,
  Check,
  RotateCcw,
  ChevronLeft,
  Filter,
  Calendar,
  Activity,
  ArrowUpDown,
  Tag,
  Cpu,
  Loader2,
} from "lucide-react";

interface OrderBookProps {
  orders?: Order[];
  provider?: string;
}

export const OrderBook: React.FC<OrderBookProps> = ({ orders: initialOrders, provider = "alpaca" }) => {
  // Filter States
  const [dateRange, setDateRange] = useState<string>("24h");
  const [statusFilter, setStatusFilter] = useState<string>("ALL");
  const [symbolFilter, setSymbolFilter] = useState<string>("ALL");
  const [sideFilter, setSideFilter] = useState<string>("ALL");
  const [strategyFilter, setStrategyFilter] = useState<string>("ALL");

  // Pagination & Data States
  const [page, setPage] = useState<number>(1);
  const [orders, setOrders] = useState<Order[]>(initialOrders || []);
  const [pagination, setPagination] = useState<PaginationInfo>({
    page: 1,
    limit: 25,
    total: initialOrders?.length || 0,
    totalPages: Math.ceil((initialOrders?.length || 0) / 25) || 1,
  });
  const [isLoading, setIsLoading] = useState<boolean>(false);

  // Expandable row & Copy state
  const [expandedOrderId, setExpandedOrderId] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  // Fetch orders from BFF based on active filters and page
  const fetchOrders = useCallback(async () => {
    setIsLoading(true);
    try {
      const params = new URLSearchParams();
      if (provider && provider !== "ALL") params.append("provider", provider);
      if (symbolFilter && symbolFilter !== "ALL") params.append("symbol", symbolFilter);
      if (sideFilter && sideFilter !== "ALL") params.append("side", sideFilter);
      if (statusFilter && statusFilter !== "ALL") params.append("status", statusFilter);
      if (strategyFilter && strategyFilter !== "ALL") params.append("strategy", strategyFilter);
      if (dateRange && dateRange !== "all") params.append("dateRange", dateRange);
      params.append("page", page.toString());
      params.append("limit", "25");

      const res = await fetch(apiUrl(`/api/v1/orders?${params.toString()}`));
      if (!res.ok) throw new Error(`HTTP error ${res.status}`);
      const data = await res.json();

      if (data && Array.isArray(data.orders)) {
        setOrders(data.orders);
        if (data.pagination) {
          setPagination(data.pagination);
        }
      } else if (Array.isArray(data)) {
        setOrders(data);
        setPagination({
          page: 1,
          limit: 25,
          total: data.length,
          totalPages: Math.ceil(data.length / 25) || 1,
        });
      }
    } catch (err) {
      console.error("[OrderBook] Failed to fetch paginated orders:", err);
    } finally {
      setIsLoading(false);
    }
  }, [provider, symbolFilter, sideFilter, statusFilter, strategyFilter, dateRange, page]);

  // Trigger query on mount and whenever filters or page changes
  useEffect(() => {
    fetchOrders();
  }, [fetchOrders]);

  const handleFilterChange = (setter: React.Dispatch<React.SetStateAction<string>>, value: string) => {
    setter(value);
    setPage(1); // Reset to first page on any filter mutation
  };

  const resetFilters = () => {
    setDateRange("24h");
    setStatusFilter("ALL");
    setSymbolFilter("ALL");
    setSideFilter("ALL");
    setStrategyFilter("ALL");
    setPage(1);
  };

  const hasActiveFilters =
    dateRange !== "24h" ||
    statusFilter !== "ALL" ||
    symbolFilter !== "ALL" ||
    sideFilter !== "ALL" ||
    strategyFilter !== "ALL";

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
      {/* Header & Status Ribbon */}
      <div className="flex flex-wrap items-center justify-between gap-3 pb-3 border-b border-[#232936]">
        <div className="flex items-center space-x-2">
          <Layers className="w-4 h-4 text-blue-400" />
          <h3 className="text-sm font-semibold text-white">Execution Audit Trail & Order Feed</h3>
          <span className="text-xs bg-[#0B0E14] text-gray-400 font-mono px-2 py-0.5 rounded border border-[#232936]">
            {pagination.total} total records
          </span>
          {isLoading && <Loader2 className="w-3.5 h-3.5 text-blue-400 animate-spin" />}
        </div>

        {/* PostgreSQL Direct Read Pool Status Ribbon */}
        <div className="flex items-center space-x-2 text-xs">
          <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
          <span className="text-gray-400 font-mono text-[11px] hidden sm:inline">PostgreSQL Direct Read Pool</span>
          <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
            SQL LIVE FEED
          </span>
        </div>
      </div>

      {/* Interactive Filter Toolbar */}
      <div className="bg-[#0B0E14] border border-[#232936] rounded-lg p-3 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2.5 text-xs">
          <div className="flex items-center space-x-1.5 text-gray-400 font-medium mr-1">
            <Filter className="w-3.5 h-3.5 text-blue-400" />
            <span>Filters:</span>
          </div>

          {/* 1. Date Range Filter */}
          <div className="flex items-center space-x-1 bg-[#151922] border border-[#232936] rounded-md px-2.5 py-1">
            <Calendar className="w-3 h-3 text-gray-500" />
            <select
              value={dateRange}
              onChange={(e) => handleFilterChange(setDateRange, e.target.value)}
              className="bg-transparent text-gray-200 text-xs focus:outline-none cursor-pointer"
            >
              <option value="all" className="bg-[#151922] text-white">All Time</option>
              <option value="today" className="bg-[#151922] text-white">Today</option>
              <option value="24h" className="bg-[#151922] text-white">Last 24 Hours</option>
              <option value="7d" className="bg-[#151922] text-white">Last 7 Days</option>
              <option value="30d" className="bg-[#151922] text-white">Last 30 Days</option>
            </select>
          </div>

          {/* 2. Status Filter */}
          <div className="flex items-center space-x-1 bg-[#151922] border border-[#232936] rounded-md px-2.5 py-1">
            <Activity className="w-3 h-3 text-gray-500" />
            <select
              value={statusFilter}
              onChange={(e) => handleFilterChange(setStatusFilter, e.target.value)}
              className="bg-transparent text-gray-200 text-xs focus:outline-none cursor-pointer"
            >
              <option value="ALL" className="bg-[#151922] text-white">All Statuses</option>
              <option value="COMPLETED" className="bg-[#151922] text-white">Completed (Success)</option>
              <option value="FAILED" className="bg-[#151922] text-white">Failed / Rejected</option>
              <option value="PENDING" className="bg-[#151922] text-white">Pending</option>
            </select>
          </div>

          {/* 3. Symbol Filter */}
          <div className="flex items-center space-x-1 bg-[#151922] border border-[#232936] rounded-md px-2.5 py-1">
            <Tag className="w-3 h-3 text-gray-500" />
            <select
              value={symbolFilter}
              onChange={(e) => handleFilterChange(setSymbolFilter, e.target.value)}
              className="bg-transparent text-gray-200 text-xs focus:outline-none cursor-pointer"
            >
              <option value="ALL" className="bg-[#151922] text-white">All Symbols</option>
              <option value="AAPL" className="bg-[#151922] text-white">AAPL</option>
              <option value="MSFT" className="bg-[#151922] text-white">MSFT</option>
            </select>
          </div>

          {/* 4. Side Filter */}
          <div className="flex items-center space-x-1 bg-[#151922] border border-[#232936] rounded-md px-2.5 py-1">
            <ArrowUpDown className="w-3 h-3 text-gray-500" />
            <select
              value={sideFilter}
              onChange={(e) => handleFilterChange(setSideFilter, e.target.value)}
              className="bg-transparent text-gray-200 text-xs focus:outline-none cursor-pointer"
            >
              <option value="ALL" className="bg-[#151922] text-white">All Sides</option>
              <option value="BUY" className="bg-[#151922] text-white">BUY</option>
              <option value="SELL" className="bg-[#151922] text-white">SELL</option>
            </select>
          </div>

          {/* 5. Strategy Filter */}
          <div className="flex items-center space-x-1 bg-[#151922] border border-[#232936] rounded-md px-2.5 py-1">
            <Cpu className="w-3 h-3 text-gray-500" />
            <select
              value={strategyFilter}
              onChange={(e) => handleFilterChange(setStrategyFilter, e.target.value)}
              className="bg-transparent text-gray-200 text-xs focus:outline-none cursor-pointer"
            >
              <option value="ALL" className="bg-[#151922] text-white">All Strategies</option>
              <option value="SmaCrossover" className="bg-[#151922] text-white">SmaCrossover</option>
              <option value="MeanReversion" className="bg-[#151922] text-white">MeanReversion</option>
            </select>
          </div>
        </div>

        {/* Reset Filter Button */}
        {hasActiveFilters && (
          <button
            onClick={resetFilters}
            className="flex items-center space-x-1 text-xs text-amber-400 hover:text-amber-300 bg-amber-500/10 border border-amber-500/20 px-2.5 py-1 rounded transition"
          >
            <RotateCcw className="w-3 h-3" />
            <span>Reset Filters</span>
          </button>
        )}
      </div>

      {/* Orders Table */}
      <div className="overflow-x-auto relative">
        <table className="w-full text-left text-xs">
          <thead>
            <tr className="border-b border-[#232936] text-gray-400 font-mono">
              <th className="w-8 py-2.5 px-2 text-center"></th>
              <th className="py-2.5 px-3">Timestamp</th>
              <th className="py-2.5 px-3">Symbol</th>
              <th className="py-2.5 px-3">Side</th>
              <th className="py-2.5 px-3 text-right">Qty</th>
              <th className="py-2.5 px-3 text-right">Price</th>
              <th className="py-2.5 px-3">Status</th>
              <th className="py-2.5 px-3">Reason / Audit Diagnostic</th>
            </tr>
          </thead>
          <tbody className={`divide-y divide-[#232936]/60 font-mono ${isLoading ? "opacity-60" : ""}`}>
            {orders.length === 0 ? (
              <tr>
                <td colSpan={8} className="py-12 text-center text-gray-500">
                  {isLoading ? "Querying PostgreSQL database..." : "No orders found matching the selected filter criteria."}
                </td>
              </tr>
            ) : (
              orders.map((order) => {
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
                        {new Date(order.timestamp).toLocaleTimeString()}
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
                      <td className="py-2.5 px-3 text-gray-400 truncate max-w-[280px]">
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

                    {/* Expandable Order ID & Audit Drawer */}
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

      {/* Pagination Bar (25 orders per page) */}
      <div className="pt-3 border-t border-[#232936] flex flex-wrap items-center justify-between gap-3 text-xs">
        <div className="text-gray-400 font-mono">
          {pagination.total > 0 ? (
            <span>
              Showing <strong className="text-white">{(pagination.page - 1) * pagination.limit + 1}</strong> –{" "}
              <strong className="text-white">{Math.min(pagination.page * pagination.limit, pagination.total)}</strong> of{" "}
              <strong className="text-white">{pagination.total}</strong> orders
            </span>
          ) : (
            <span>0 orders found</span>
          )}
        </div>

        {/* Page Navigation Controls */}
        <div className="flex items-center space-x-2">
          <button
            onClick={() => setPage((p) => Math.max(p - 1, 1))}
            disabled={page <= 1 || isLoading}
            className="flex items-center space-x-1 px-3 py-1.5 rounded-lg bg-[#0B0E14] border border-[#232936] text-gray-300 hover:text-white disabled:opacity-40 disabled:cursor-not-allowed transition"
          >
            <ChevronLeft className="w-3.5 h-3.5" />
            <span>Previous</span>
          </button>

          <span className="px-3 py-1.5 rounded-lg bg-[#0B0E14] border border-[#232936] text-gray-300 font-mono">
            Page <strong className="text-blue-400">{pagination.page}</strong> of{" "}
            <strong>{pagination.totalPages}</strong>
          </span>

          <button
            onClick={() => setPage((p) => Math.min(p + 1, pagination.totalPages))}
            disabled={page >= pagination.totalPages || isLoading}
            className="flex items-center space-x-1 px-3 py-1.5 rounded-lg bg-[#0B0E14] border border-[#232936] text-gray-300 hover:text-white disabled:opacity-40 disabled:cursor-not-allowed transition"
          >
            <span>Next</span>
            <ChevronRight className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    </div>
  );
};
