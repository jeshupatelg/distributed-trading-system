import React, { useState, useEffect } from "react";
import { Shield, AlertCircle, Settings, CheckCircle2 } from "lucide-react";
import { RiskStatus, RiskConfig } from "../types/trading";
import { apiUrl } from "../utils/api";

interface RiskCenterProps {
  riskStatus: RiskStatus | null;
  provider: string;
  onRefresh: () => void;
}

export const RiskCenter: React.FC<RiskCenterProps> = ({ riskStatus, provider, onRefresh }) => {
  const [config, setConfig] = useState<RiskConfig>({
    max_daily_loss: 2000,
    price_collar_pct: 1.5,
    max_order_size: 500,
  });
  const [isUpdating, setIsUpdating] = useState(false);
  const [updateSuccess, setUpdateSuccess] = useState(false);

  useEffect(() => {
    // Fetch active dynamic config
    fetch(apiUrl(`/api/v1/risk/config?provider=${provider}`))
      .then((res) => res.json())
      .then((data) => {
        if (data) {
          setConfig({
            max_daily_loss: data.max_daily_loss ?? data["risk:config:max_daily_loss"] ?? 2000,
            price_collar_pct: data.price_collar_pct ?? data["risk:config:price_collar_pct"] ?? 1.5,
            max_order_size: data.max_order_size ?? 500,
          });
        }
      })
      .catch((err) => console.error("Error fetching risk config:", err));
  }, [provider]);

  const handleSaveConfig = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsUpdating(true);
    setUpdateSuccess(false);
    try {
      const res = await fetch(apiUrl(`/api/v1/risk/config?provider=${provider}`), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(config),
      });
      if (res.ok) {
        setUpdateSuccess(true);
        setTimeout(() => setUpdateSuccess(false), 3000);
        onRefresh();
      }
    } catch (err) {
      console.error("Failed to update risk config:", err);
    } finally {
      setIsUpdating(false);
    }
  };

  const maxLoss = riskStatus?.max_daily_loss || 2000;
  const currentDrawdown = riskStatus?.current_drawdown || 0;
  const drawdownPct = Math.min(100, Math.max(0, (currentDrawdown / maxLoss) * 100));

  return (
    <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
      {/* 1. Drawdown & Daily Loss Gauge */}
      <div className="bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Shield className="w-4 h-4 text-blue-400" />
            <h3 className="text-sm font-semibold text-white">Daily Loss Threshold</h3>
          </div>
          <span className="text-xs font-mono text-gray-400">Gate Level 1</span>
        </div>

        <div className="space-y-2">
          <div className="flex justify-between text-xs font-mono">
            <span className="text-gray-400">Current Drawdown:</span>
            <span className={currentDrawdown > 0 ? "text-amber-400 font-bold" : "text-gray-200"}>
              ${currentDrawdown.toFixed(2)}
            </span>
          </div>

          <div className="w-full bg-[#0B0E14] rounded-full h-3 border border-[#232936] overflow-hidden">
            <div
              className={`h-full transition-all duration-500 ${
                drawdownPct > 80 ? "bg-red-500" : drawdownPct > 50 ? "bg-amber-500" : "bg-emerald-500"
              }`}
              style={{ width: `${drawdownPct}%` }}
            />
          </div>

          <div className="flex justify-between text-xs text-gray-400 font-mono">
            <span>0%</span>
            <span>Max Limit: ${maxLoss.toLocaleString()}</span>
          </div>
        </div>

        <div className="pt-2 border-t border-[#232936] flex items-center justify-between text-xs">
          <span className="text-gray-400">Circuit Breaker:</span>
          <span
            className={`px-2 py-0.5 rounded font-mono font-semibold ${
              riskStatus?.circuit_breaker_tripped
                ? "bg-red-500/20 text-red-400 border border-red-500/30"
                : "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
            }`}
          >
            {riskStatus?.circuit_breaker_tripped ? "TRIPPED" : "ARMED / NORMAL"}
          </span>
        </div>
      </div>

      {/* 2. Price Collar Band Gate */}
      <div className="bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <AlertCircle className="w-4 h-4 text-emerald-400" />
            <h3 className="text-sm font-semibold text-white">Price Collar Firewall</h3>
          </div>
          <span className="text-xs font-mono text-gray-400">Gate Level 2</span>
        </div>

        <div className="space-y-3">
          <div className="bg-[#0B0E14] border border-[#232936] rounded-lg p-3 text-center space-y-1">
            <div className="text-2xl font-bold font-mono text-emerald-400">
              ±{riskStatus?.price_collar_pct ?? 1.5}%
            </div>
            <div className="text-xs text-gray-400">Max allowable tick deviation</div>
          </div>
          <p className="text-xs text-gray-400 leading-relaxed">
            Trades exceeding this price slippage band relative to the last cached price in Redis are automatically
            rejected by OPS before submission.
          </p>
        </div>
      </div>

      {/* 3. Dynamic Configuration Tuning */}
      <div className="bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Settings className="w-4 h-4 text-gray-400" />
            <h3 className="text-sm font-semibold text-white">Dynamic Risk Tuning</h3>
          </div>
          {updateSuccess && (
            <span className="text-xs text-emerald-400 flex items-center gap-1 font-mono">
              <CheckCircle2 className="w-3.5 h-3.5" /> Updated
            </span>
          )}
        </div>

        <form onSubmit={handleSaveConfig} className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs text-gray-400 block mb-1">Max Daily Loss ($)</label>
              <input
                type="number"
                value={config.max_daily_loss ?? 2000}
                onChange={(e) => setConfig({ ...config, max_daily_loss: Number(e.target.value) })}
                className="w-full bg-[#0B0E14] border border-[#232936] rounded px-3 py-1.5 text-xs text-white font-mono focus:border-blue-500 focus:outline-none"
              />
            </div>

            <div>
              <label className="text-xs text-gray-400 block mb-1">Price Collar (%)</label>
              <input
                type="number"
                step="0.1"
                value={config.price_collar_pct ?? 1.5}
                onChange={(e) => setConfig({ ...config, price_collar_pct: Number(e.target.value) })}
                className="w-full bg-[#0B0E14] border border-[#232936] rounded px-3 py-1.5 text-xs text-white font-mono focus:border-blue-500 focus:outline-none"
              />
            </div>
          </div>

          <button
            type="submit"
            disabled={isUpdating}
            className="w-full bg-blue-600 hover:bg-blue-500 text-white text-xs font-semibold py-2 rounded-lg transition"
          >
            {isUpdating ? "Saving to OPS..." : "Apply Risk Parameters"}
          </button>
        </form>
      </div>
    </div>
  );
};
