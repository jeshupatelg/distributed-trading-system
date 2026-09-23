import React, { useState, useEffect } from "react";
import { Server, CheckCircle2, AlertTriangle, XCircle, RefreshCw } from "lucide-react";
import { ServiceHealth } from "../types/trading";

export const TelemetryMatrix: React.FC = () => {
  const [services, setServices] = useState<ServiceHealth[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  const fetchHealth = () => {
    setIsLoading(true);
    fetch("/api/v1/system/health")
      .then((res) => res.json())
      .then((data) => {
        if (Array.isArray(data)) {
          setServices(data);
        }
      })
      .catch((err) => console.error("Telemetry fetch error:", err))
      .finally(() => setIsLoading(false));
  };

  useEffect(() => {
    fetchHealth();
    const interval = setInterval(fetchHealth, 10000);
    return () => clearInterval(interval);
  }, []);

  const getStatusIcon = (status: ServiceHealth["status"]) => {
    switch (status) {
      case "healthy":
        return <CheckCircle2 className="w-4 h-4 text-emerald-400" />;
      case "degraded":
        return <AlertTriangle className="w-4 h-4 text-amber-400" />;
      case "unreachable":
        return <XCircle className="w-4 h-4 text-red-400" />;
    }
  };

  return (
    <div className="bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-4">
      <div className="flex items-center justify-between pb-3 border-b border-[#232936]">
        <div className="flex items-center space-x-2">
          <Server className="w-4 h-4 text-blue-400" />
          <h3 className="text-sm font-semibold text-white">Microservices Telemetry & Gateway Mesh</h3>
        </div>
        <button
          onClick={fetchHealth}
          disabled={isLoading}
          className="text-xs text-gray-400 hover:text-white flex items-center gap-1 font-mono transition"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? "animate-spin" : ""}`} /> Refresh
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
        {services.map((svc) => (
          <div
            key={svc.name}
            className="bg-[#0B0E14] border border-[#232936] rounded-lg p-3.5 flex items-center justify-between"
          >
            <div className="space-y-1">
              <div className="text-xs font-bold text-white flex items-center gap-1.5">
                {getStatusIcon(svc.status)}
                <span>{svc.name}</span>
              </div>
              <div className="text-[11px] font-mono text-gray-500">{svc.endpoint}</div>
            </div>

            <div className="text-right">
              <span
                className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded uppercase ${
                  svc.status === "healthy"
                    ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
                    : svc.status === "degraded"
                    ? "bg-amber-500/10 text-amber-400 border border-amber-500/20"
                    : "bg-red-500/10 text-red-400 border border-red-500/20"
                }`}
              >
                {svc.status}
              </span>
              {svc.latencyMs !== undefined && (
                <div className="text-[10px] text-gray-500 font-mono mt-1">{svc.latencyMs}ms</div>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
