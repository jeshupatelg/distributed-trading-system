import React, { useState } from "react";
import { Shield, ShieldAlert, ShieldCheck, Activity, Power, RefreshCw, AlertTriangle } from "lucide-react";
import { RiskStatus } from "../types/trading";

interface HeaderProps {
  riskStatus: RiskStatus | null;
  selectedProvider: string;
  onProviderChange: (p: string) => void;
  isWsConnected: boolean;
  onRefresh: () => void;
}

export const Header: React.FC<HeaderProps> = ({
  riskStatus,
  selectedProvider,
  onProviderChange,
  isWsConnected,
  onRefresh,
}) => {
  const [showConfirmModal, setShowConfirmModal] = useState(false);
  const [liquidate, setLiquidate] = useState(true);
  const [isProcessing, setIsProcessing] = useState(false);

  const isLockdown = riskStatus?.kill_switch_active ?? false;

  const handleTriggerKillSwitch = async () => {
    setIsProcessing(true);
    try {
      const res = await fetch(`/api/v1/risk/kill-switch/trigger?liquidate=${liquidate}&provider=${selectedProvider}`, {
        method: "POST",
      });
      if (res.ok) {
        setShowConfirmModal(false);
        onRefresh();
      }
    } catch (err) {
      console.error("Failed to trigger kill switch", err);
    } finally {
      setIsProcessing(false);
    }
  };

  const handleResetKillSwitch = async () => {
    setIsProcessing(true);
    try {
      const res = await fetch(`/api/v1/risk/kill-switch/reset?provider=${selectedProvider}`, {
        method: "POST",
      });
      if (res.ok) {
        onRefresh();
      }
    } catch (err) {
      console.error("Failed to reset kill switch", err);
    } finally {
      setIsProcessing(false);
    }
  };

  return (
    <>
      <header className="bg-[#151922] border-b border-[#232936] px-6 py-3 flex flex-wrap items-center justify-between gap-4 sticky top-0 z-40">
        <div className="flex items-center space-x-3">
          <div className="p-2 bg-blue-600/10 border border-blue-500/20 rounded-lg text-blue-500">
            <Activity className="w-5 h-5" />
          </div>
          <div>
            <h1 className="text-lg font-bold tracking-tight text-white flex items-center gap-2">
              Quant Trading Cockpit
              <span className="text-xs px-2 py-0.5 rounded font-mono bg-[#232936] text-gray-400">v2.0 BFF</span>
            </h1>
            <p className="text-xs text-gray-400">Distributed Algorithmic Execution & Pre-Trade Risk Gateway</p>
          </div>
        </div>

        {/* Global Controls & Status */}
        <div className="flex items-center space-x-4">
          {/* Provider Select */}
          <div className="flex items-center space-x-2 text-xs">
            <span className="text-gray-400">Provider:</span>
            <select
              value={selectedProvider}
              onChange={(e) => onProviderChange(e.target.value)}
              className="bg-[#0B0E14] border border-[#232936] rounded px-2.5 py-1 text-gray-200 focus:outline-none focus:border-blue-500 font-mono"
            >
              <option value="alpaca">Alpaca Paper (US)</option>
              <option value="megabull">Megabull (NSE)</option>
            </select>
          </div>

          {/* WebSocket Ping */}
          <div className="flex items-center space-x-1.5 text-xs bg-[#0B0E14] px-2.5 py-1 rounded border border-[#232936]">
            <span className={`w-2 h-2 rounded-full ${isWsConnected ? "bg-green-500 animate-pulse" : "bg-red-500"}`} />
            <span className="text-gray-300 font-mono">{isWsConnected ? "LIVE STREAM" : "OFFLINE"}</span>
          </div>

          {/* Refresh Action */}
          <button
            onClick={onRefresh}
            title="Refresh Status"
            className="p-1.5 text-gray-400 hover:text-white bg-[#0B0E14] border border-[#232936] rounded hover:border-gray-600 transition"
          >
            <RefreshCw className="w-4 h-4" />
          </button>

          {/* Kill Switch Toggle */}
          {isLockdown ? (
            <button
              onClick={handleResetKillSwitch}
              disabled={isProcessing}
              className="flex items-center space-x-2 bg-amber-500/10 hover:bg-amber-500/20 text-amber-400 border border-amber-500/30 px-3.5 py-1.5 rounded-lg text-xs font-semibold transition"
            >
              <ShieldCheck className="w-4 h-4" />
              <span>RESET LOCKDOWN</span>
            </button>
          ) : (
            <button
              onClick={() => setShowConfirmModal(true)}
              className="flex items-center space-x-2 bg-red-600/10 hover:bg-red-600 text-red-400 hover:text-white border border-red-500/30 hover:border-red-600 px-3.5 py-1.5 rounded-lg text-xs font-semibold transition shadow-sm"
            >
              <Power className="w-4 h-4" />
              <span>EMERGENCY KILL-SWITCH</span>
            </button>
          )}
        </div>
      </header>

      {/* Prominent Banner if lockdown is active */}
      {isLockdown && (
        <div className="bg-red-950/80 border-b border-red-600 text-red-200 px-6 py-2.5 flex items-center justify-between text-xs animate-pulse">
          <div className="flex items-center space-x-2 font-mono">
            <ShieldAlert className="w-4 h-4 text-red-400" />
            <span className="font-bold">EMERGENCY SYSTEM LOCKDOWN ACTIVE:</span>
            <span>All incoming trading signals are being dropped. Working orders cancelled.</span>
          </div>
          <button
            onClick={handleResetKillSwitch}
            disabled={isProcessing}
            className="bg-red-700 hover:bg-red-600 text-white font-bold px-3 py-1 rounded text-xs transition"
          >
            Clear Lockdown
          </button>
        </div>
      )}

      {/* Confirmation Modal */}
      {showConfirmModal && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-[#151922] border border-red-500/40 rounded-xl max-w-md w-full p-6 shadow-2xl space-y-4">
            <div className="flex items-center space-x-3 text-red-400">
              <div className="p-2 bg-red-500/10 rounded-lg">
                <AlertTriangle className="w-6 h-6" />
              </div>
              <h2 className="text-base font-bold text-white">Trigger Emergency Kill-Switch?</h2>
            </div>

            <p className="text-xs text-gray-300 leading-relaxed">
              This initiates an emergency lockdown across the <strong>{selectedProvider.toUpperCase()}</strong> gateway:
              all incoming signals will be rejected by OPS immediately and all working orders will be cancelled via gRPC.
            </p>

            <div className="bg-[#0B0E14] border border-[#232936] p-3 rounded-lg flex items-center space-x-2">
              <input
                type="checkbox"
                id="liquidate-checkbox"
                checked={liquidate}
                onChange={(e) => setLiquidate(e.target.checked)}
                className="w-4 h-4 rounded text-red-600 focus:ring-red-500 border-gray-700 bg-gray-900"
              />
              <label htmlFor="liquidate-checkbox" className="text-xs text-gray-300 font-medium cursor-pointer">
                Liquidate all open positions to cash immediately
              </label>
            </div>

            <div className="flex items-center justify-end space-x-3 pt-2">
              <button
                onClick={() => setShowConfirmModal(false)}
                className="px-4 py-2 text-xs text-gray-400 hover:text-white transition"
              >
                Cancel
              </button>
              <button
                onClick={handleTriggerKillSwitch}
                disabled={isProcessing}
                className="px-4 py-2 bg-red-600 hover:bg-red-500 text-white text-xs font-bold rounded-lg transition shadow-lg shadow-red-950"
              >
                {isProcessing ? "Triggering..." : "CONFIRM LOCKDOWN"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};
