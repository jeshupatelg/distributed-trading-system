import React from "react";
import { Shield, AlertCircle, Wallet, Activity } from "lucide-react";
import { RiskStatus } from "../types/trading";

interface InformativeRiskCardsProps {
  riskStatus: RiskStatus | null;
  provider: string;
  isHorizontal?: boolean;
}

export const InformativeRiskCards: React.FC<InformativeRiskCardsProps> = ({
  riskStatus,
  provider,
  isHorizontal = false,
}) => {
  const maxLoss = riskStatus?.max_daily_loss || 2000;
  const currentDrawdown = riskStatus?.current_drawdown || 0;
  const drawdownPct = Math.min(100, Math.max(0, (currentDrawdown / maxLoss) * 100));
  const cashBalance = riskStatus?.cash_balance ?? 170533.88;
  const blockedMargin = riskStatus?.blocked_margin ?? 0;
  const isTripped = riskStatus?.circuit_breaker_tripped ?? false;

  return (
    <div className={isHorizontal ? "grid grid-cols-1 md:grid-cols-3 gap-4" : "space-y-4"}>
      {/* 1. Daily Loss Threshold & Circuit Breaker */}
      <div className="bg-[#151922] border border-[#232936] rounded-xl p-4 space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Shield className="w-4 h-4 text-blue-400" />
            <h3 className="text-xs font-bold text-white tracking-wide uppercase">Daily Loss Threshold</h3>
          </div>
          <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-[#0B0E14] text-blue-400 border border-[#232936]">
            Gate 1
          </span>
        </div>

        <div className="space-y-1.5">
          <div className="flex justify-between text-xs font-mono">
            <span className="text-gray-400">Current Drawdown:</span>
            <span className={currentDrawdown > 0 ? "text-amber-400 font-bold" : "text-gray-200"}>
              ${currentDrawdown.toFixed(2)}
            </span>
          </div>

          <div className="w-full bg-[#0B0E14] rounded-full h-2.5 border border-[#232936] overflow-hidden">
            <div
              className={`h-full transition-all duration-500 ${
                drawdownPct > 80 ? "bg-red-500" : drawdownPct > 50 ? "bg-amber-500" : "bg-emerald-500"
              }`}
              style={{ width: `${Math.max(drawdownPct, 2)}%` }}
            />
          </div>

          <div className="flex justify-between text-[11px] text-gray-500 font-mono">
            <span>{drawdownPct.toFixed(1)}% Used</span>
            <span>Max: ${maxLoss.toLocaleString()}</span>
          </div>
        </div>

        <div className="pt-2 border-t border-[#232936] flex items-center justify-between text-xs">
          <span className="text-gray-400">Circuit Breaker:</span>
          <span
            className={`px-2 py-0.5 rounded text-[11px] font-mono font-semibold flex items-center gap-1.5 ${
              isTripped
                ? "bg-red-500/20 text-red-400 border border-red-500/30"
                : "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
            }`}
          >
            <span className={`w-1.5 h-1.5 rounded-full ${isTripped ? "bg-red-400" : "bg-emerald-400 animate-pulse"}`} />
            {isTripped ? "TRIPPED" : "ARMED / NORMAL"}
          </span>
        </div>
      </div>

      {/* 2. Price Collar Firewall */}
      <div className="bg-[#151922] border border-[#232936] rounded-xl p-4 space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <AlertCircle className="w-4 h-4 text-emerald-400" />
            <h3 className="text-xs font-bold text-white tracking-wide uppercase">Price Collar Firewall</h3>
          </div>
          <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-[#0B0E14] text-emerald-400 border border-[#232936]">
            Gate 2
          </span>
        </div>

        <div className="bg-[#0B0E14] border border-[#232936] rounded-lg p-2.5 flex items-center justify-between">
          <div>
            <div className="text-lg font-bold font-mono text-emerald-400">
              ±{riskStatus?.price_collar_pct ?? 1.5}%
            </div>
            <div className="text-[10px] text-gray-400">Max allowable tick deviation</div>
          </div>
          <span className="text-[11px] font-mono text-gray-400 bg-[#151922] px-2 py-1 rounded border border-[#232936]">
            Active
          </span>
        </div>

        <p className="text-[11px] text-gray-400 leading-relaxed">
          Orders deviating beyond slippage limits from Redis prices are instantly blocked by OPS before transmission.
        </p>
      </div>

      {/* 3. Account Capital & Margin Posture */}
      <div className="bg-[#151922] border border-[#232936] rounded-xl p-4 space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Wallet className="w-4 h-4 text-amber-400" />
            <h3 className="text-xs font-bold text-white tracking-wide uppercase">Capital & Margin</h3>
          </div>
          <span className="text-[10px] font-mono text-gray-400 capitalize">
            {provider}
          </span>
        </div>

        <div className="grid grid-cols-2 gap-2 text-xs font-mono">
          <div className="bg-[#0B0E14] border border-[#232936] rounded p-2">
            <div className="text-[10px] text-gray-500 uppercase">Cash Balance</div>
            <div className="text-white font-bold text-sm mt-0.5">${cashBalance.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</div>
          </div>
          <div className="bg-[#0B0E14] border border-[#232936] rounded p-2">
            <div className="text-[10px] text-gray-500 uppercase">Blocked Margin</div>
            <div className="text-gray-300 font-bold text-sm mt-0.5">${blockedMargin.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</div>
          </div>
        </div>

        <div className="pt-2 border-t border-[#232936] flex items-center justify-between text-[11px] text-gray-400 font-mono">
          <span className="flex items-center gap-1.5">
            <Activity className="w-3.5 h-3.5 text-blue-400" />
            Pre-Trade Gate Engine
          </span>
          <span className="text-emerald-400 font-semibold">Active</span>
        </div>
      </div>
    </div>
  );
};
