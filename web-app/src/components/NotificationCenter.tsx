import React, { useState, useEffect } from "react";
import {
  Bell,
  Send,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  RefreshCw,
  Save,
  MessageSquare,
  Radio,
  Smartphone,
  Eye,
  EyeOff,
  Sliders,
  Check,
  ShieldAlert,
} from "lucide-react";

interface ChannelStatus {
  enabled: boolean;
  [key: string]: any;
}

interface NotificationStatusResponse {
  channels: {
    telegram: {
      enabled: boolean;
      chat_id: string;
      topic_id?: string;
      token_configured: boolean;
      token_preview: string;
    };
    ntfy: {
      enabled: boolean;
      url: string;
      topic: string;
    };
    evolution: {
      enabled: boolean;
      url: string;
      instance: string;
      recipient: string;
      apikey_configured: boolean;
      apikey_preview: string;
    };
  };
  filters: {
    notify_on_reject: boolean;
    notify_on_order_create: boolean;
    notify_on_order_fill: boolean;
    notify_on_kill_switch: boolean;
  };
}

export const NotificationCenter: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // Form states
  const [telegramEnabled, setTelegramEnabled] = useState(false);
  const [telegramToken, setTelegramToken] = useState("");
  const [telegramChatId, setTelegramChatId] = useState("");
  const [telegramTopicId, setTelegramTopicId] = useState("");
  const [showTelegramToken, setShowTelegramToken] = useState(false);

  const [ntfyEnabled, setNtfyEnabled] = useState(false);
  const [ntfyUrl, setNtfyUrl] = useState("https://ntfy.sh");
  const [ntfyTopic, setNtfyTopic] = useState("trading-system-alerts");
  const [ntfyToken, setNtfyToken] = useState("");

  const [evolutionEnabled, setEvolutionEnabled] = useState(false);
  const [evolutionUrl, setEvolutionUrl] = useState("http://192.168.29.96:3015");
  const [evolutionApiKey, setEvolutionApiKey] = useState("");
  const [evolutionInstance, setEvolutionInstance] = useState("");
  const [evolutionRecipient, setEvolutionRecipient] = useState("");
  const [showEvolutionKey, setShowEvolutionKey] = useState(false);

  const [filterReject, setFilterReject] = useState(true);
  const [filterOrderCreate, setFilterOrderCreate] = useState(false);
  const [filterOrderFill, setFilterOrderFill] = useState(true);
  const [filterKillSwitch, setFilterKillSwitch] = useState(true);

  // Test notification console state
  const [testChannel, setTestChannel] = useState<string>("all");
  const [testEventType, setTestEventType] = useState<string>("reject");
  const [testSymbol, setTestSymbol] = useState<string>("AAPL");
  const [testQty, setTestQty] = useState<number>(100);
  const [testPrice, setTestPrice] = useState<number>(260.0);
  const [testGate, setTestGate] = useState<string>("PRICE_COLLAR");
  const [testReason, setTestReason] = useState<string>("PRICE_COLLAR_VIOLATION (13.04% > 2.0%)");
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<any | null>(null);

  const fetchStatus = async () => {
    setLoading(true);
    setErrorMsg(null);
    try {
      const res = await fetch("/api/v1/notify/status");
      if (!res.ok) {
        throw new Error(`Failed to fetch status: HTTP ${res.status}`);
      }
      const data: NotificationStatusResponse = await res.json();

      // Telegram
      setTelegramEnabled(data.channels.telegram?.enabled ?? false);
      setTelegramChatId(data.channels.telegram?.chat_id ?? "");
      setTelegramTopicId(data.channels.telegram?.topic_id ?? "");

      // ntfy
      setNtfyEnabled(data.channels.ntfy?.enabled ?? false);
      setNtfyUrl(data.channels.ntfy?.url || "https://ntfy.sh");
      setNtfyTopic(data.channels.ntfy?.topic || "trading-system-alerts");

      // Evolution
      setEvolutionEnabled(data.channels.evolution?.enabled ?? false);
      setEvolutionUrl(data.channels.evolution?.url || "http://192.168.29.96:3015");
      setEvolutionInstance(data.channels.evolution?.instance ?? "");
      setEvolutionRecipient(data.channels.evolution?.recipient ?? "");

      // Filters
      setFilterReject(data.filters?.notify_on_reject ?? true);
      setFilterOrderCreate(data.filters?.notify_on_order_create ?? false);
      setFilterOrderFill(data.filters?.notify_on_order_fill ?? true);
      setFilterKillSwitch(data.filters?.notify_on_kill_switch ?? true);
    } catch (err: any) {
      setErrorMsg(err.message || "Could not connect to Notification Service BFF endpoint");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchStatus();
  }, []);

  const handleSaveConfig = async () => {
    setSaving(true);
    setErrorMsg(null);
    setSaveSuccess(false);

    try {
      const payload: any = {
        telegram: {
          enabled: telegramEnabled,
          chat_id: telegramChatId,
          topic_id: telegramTopicId,
        },
        ntfy: {
          enabled: ntfyEnabled,
          url: ntfyUrl,
          topic: ntfyTopic,
        },
        evolution: {
          enabled: evolutionEnabled,
          url: evolutionUrl,
          instance: evolutionInstance,
          recipient: evolutionRecipient,
        },
        filters: {
          notify_on_reject: filterReject,
          notify_on_order_create: filterOrderCreate,
          notify_on_order_fill: filterOrderFill,
          notify_on_kill_switch: filterKillSwitch,
        },
      };

      if (telegramToken.trim()) {
        payload.telegram.token = telegramToken.trim();
      }
      if (ntfyToken.trim()) {
        payload.ntfy.token = ntfyToken.trim();
      }
      if (evolutionApiKey.trim()) {
        payload.evolution.apikey = evolutionApiKey.trim();
      }

      const res = await fetch("/api/v1/notify/config", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (!res.ok) {
        throw new Error(`Failed to save config: HTTP ${res.status}`);
      }

      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 3000);
      fetchStatus();
    } catch (err: any) {
      setErrorMsg(err.message || "Failed to persist configuration");
    } finally {
      setSaving(false);
    }
  };

  const handleSendTest = async () => {
    setTesting(true);
    setTestResult(null);

    try {
      const res = await fetch("/api/v1/notify/test", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          channel: testChannel,
          event_type: testEventType,
          symbol: testSymbol,
          qty: testQty,
          price: testPrice,
          gate: testGate,
          reason: testReason,
        }),
      });

      const data = await res.json();
      setTestResult(data);
    } catch (err: any) {
      setTestResult({ error: err.message || "Test notification dispatch failed" });
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="bg-[#151922] border border-[#232936] rounded-xl p-5 flex flex-wrap items-center justify-between gap-4">
        <div>
          <div className="flex items-center space-x-3">
            <div className="p-2.5 bg-blue-500/10 border border-blue-500/20 rounded-xl text-blue-400">
              <Bell className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold text-white tracking-wide">Enterprise Notification Center</h2>
              <p className="text-xs text-gray-400 mt-0.5">
                Real-time alert dispatching across Telegram, ntfy.sh, and Evolution API (WhatsApp)
              </p>
            </div>
          </div>
        </div>

        <div className="flex items-center space-x-3">
          <button
            onClick={fetchStatus}
            disabled={loading}
            className="flex items-center space-x-2 px-3.5 py-1.5 bg-[#0B0E14] border border-[#232936] hover:border-gray-500 text-gray-300 hover:text-white rounded-lg text-xs font-mono transition"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? "animate-spin text-blue-400" : ""}`} />
            <span>Refresh</span>
          </button>
          <button
            onClick={handleSaveConfig}
            disabled={saving}
            className={`flex items-center space-x-2 px-4 py-1.5 rounded-lg text-xs font-semibold font-mono transition shadow-sm ${
              saveSuccess
                ? "bg-emerald-600 text-white"
                : "bg-blue-600 hover:bg-blue-500 text-white"
            }`}
          >
            {saving ? (
              <RefreshCw className="w-3.5 h-3.5 animate-spin" />
            ) : saveSuccess ? (
              <Check className="w-3.5 h-3.5" />
            ) : (
              <Save className="w-3.5 h-3.5" />
            )}
            <span>{saveSuccess ? "Saved to Redis!" : saving ? "Saving..." : "Save Settings"}</span>
          </button>
        </div>
      </div>

      {errorMsg && (
        <div className="bg-red-500/10 border border-red-500/30 rounded-xl p-4 flex items-center gap-3 text-red-400 text-xs">
          <AlertTriangle className="w-4 h-4 shrink-0" />
          <span>{errorMsg}</span>
        </div>
      )}

      {/* Grid: 3 Channels */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
        {/* Telegram Card */}
        <div className="bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-4 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between pb-3 border-b border-[#232936]">
              <div className="flex items-center space-x-2.5">
                <div className="p-2 bg-sky-500/10 rounded-lg text-sky-400">
                  <Send className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-white">Telegram</h3>
                  <span className="text-[11px] text-gray-400">Bot & Channel Alerts</span>
                </div>
              </div>
              <label className="relative inline-flex items-center cursor-pointer">
                <input
                  type="checkbox"
                  checked={telegramEnabled}
                  onChange={(e) => setTelegramEnabled(e.target.checked)}
                  className="sr-only peer"
                />
                <div className="w-9 h-5 bg-[#0B0E14] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-gray-400 after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-sky-600 peer-checked:after:bg-white border border-[#232936]"></div>
              </label>
            </div>

            <div className="space-y-3 pt-3">
              <div>
                <label className="text-[11px] font-mono text-gray-400 block mb-1">Bot Token</label>
                <div className="relative">
                  <input
                    type={showTelegramToken ? "text" : "password"}
                    placeholder="e.g. 123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"
                    value={telegramToken}
                    onChange={(e) => setTelegramToken(e.target.value)}
                    className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 font-mono focus:border-sky-500 focus:outline-none pr-8"
                  />
                  <button
                    type="button"
                    onClick={() => setShowTelegramToken(!showTelegramToken)}
                    className="absolute right-2.5 top-2 text-gray-500 hover:text-gray-300"
                  >
                    {showTelegramToken ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                  </button>
                </div>
                <span className="text-[10px] text-gray-500 font-mono mt-0.5 block">Leave empty to preserve existing token</span>
              </div>

              <div>
                <label className="text-[11px] font-mono text-gray-400 block mb-1">Chat ID</label>
                <input
                  type="text"
                  placeholder="e.g. -100123456789 or @channel"
                  value={telegramChatId}
                  onChange={(e) => setTelegramChatId(e.target.value)}
                  className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 font-mono focus:border-sky-500 focus:outline-none"
                />
              </div>

              <div>
                <label className="text-[11px] font-mono text-gray-400 block mb-1">Topic ID (Optional)</label>
                <input
                  type="text"
                  placeholder="e.g. 12 (Forum Supergroup Thread)"
                  value={telegramTopicId}
                  onChange={(e) => setTelegramTopicId(e.target.value)}
                  className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 font-mono focus:border-sky-500 focus:outline-none"
                />
              </div>
            </div>
          </div>

          <div className="pt-3 border-t border-[#232936] flex items-center justify-between text-[11px] font-mono">
            <span className="text-gray-500">Status</span>
            <span className={telegramEnabled ? "text-sky-400 font-semibold" : "text-gray-500"}>
              {telegramEnabled ? "ACTIVE" : "DISABLED"}
            </span>
          </div>
        </div>

        {/* ntfy Card */}
        <div className="bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-4 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between pb-3 border-b border-[#232936]">
              <div className="flex items-center space-x-2.5">
                <div className="p-2 bg-emerald-500/10 rounded-lg text-emerald-400">
                  <Radio className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-white">ntfy.sh</h3>
                  <span className="text-[11px] text-gray-400">Push Notifications & Mobile App</span>
                </div>
              </div>
              <label className="relative inline-flex items-center cursor-pointer">
                <input
                  type="checkbox"
                  checked={ntfyEnabled}
                  onChange={(e) => setNtfyEnabled(e.target.checked)}
                  className="sr-only peer"
                />
                <div className="w-9 h-5 bg-[#0B0E14] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-gray-400 after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-emerald-600 peer-checked:after:bg-white border border-[#232936]"></div>
              </label>
            </div>

            <div className="space-y-3 pt-3">
              <div>
                <label className="text-[11px] font-mono text-gray-400 block mb-1">Server URL</label>
                <input
                  type="text"
                  placeholder="https://ntfy.sh"
                  value={ntfyUrl}
                  onChange={(e) => setNtfyUrl(e.target.value)}
                  className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 font-mono focus:border-emerald-500 focus:outline-none"
                />
              </div>

              <div>
                <label className="text-[11px] font-mono text-gray-400 block mb-1">Topic Name</label>
                <input
                  type="text"
                  placeholder="e.g. trading-system-alerts"
                  value={ntfyTopic}
                  onChange={(e) => setNtfyTopic(e.target.value)}
                  className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 font-mono focus:border-emerald-500 focus:outline-none"
                />
              </div>

              <div>
                <label className="text-[11px] font-mono text-gray-400 block mb-1">Access Token (Optional)</label>
                <input
                  type="password"
                  placeholder="Bearer token if topic is protected"
                  value={ntfyToken}
                  onChange={(e) => setNtfyToken(e.target.value)}
                  className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 font-mono focus:border-emerald-500 focus:outline-none"
                />
              </div>
            </div>
          </div>

          <div className="pt-3 border-t border-[#232936] flex items-center justify-between text-[11px] font-mono">
            <span className="text-gray-500">Status</span>
            <span className={ntfyEnabled ? "text-emerald-400 font-semibold" : "text-gray-500"}>
              {ntfyEnabled ? "ACTIVE" : "DISABLED"}
            </span>
          </div>
        </div>

        {/* Evolution API (WhatsApp) Card */}
        <div className="bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-4 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between pb-3 border-b border-[#232936]">
              <div className="flex items-center space-x-2.5">
                <div className="p-2 bg-green-500/10 rounded-lg text-green-400">
                  <Smartphone className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-white">Evolution API</h3>
                  <span className="text-[11px] text-gray-400">WhatsApp Gateway</span>
                </div>
              </div>
              <label className="relative inline-flex items-center cursor-pointer">
                <input
                  type="checkbox"
                  checked={evolutionEnabled}
                  onChange={(e) => setEvolutionEnabled(e.target.checked)}
                  className="sr-only peer"
                />
                <div className="w-9 h-5 bg-[#0B0E14] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-gray-400 after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-green-600 peer-checked:after:bg-white border border-[#232936]"></div>
              </label>
            </div>

            <div className="space-y-3 pt-3">
              <div>
                <label className="text-[11px] font-mono text-gray-400 block mb-1">Server URL</label>
                <input
                  type="text"
                  placeholder="http://192.168.29.96:3015"
                  value={evolutionUrl}
                  onChange={(e) => setEvolutionUrl(e.target.value)}
                  className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 font-mono focus:border-green-500 focus:outline-none"
                />
              </div>

              <div>
                <label className="text-[11px] font-mono text-gray-400 block mb-1">API Key</label>
                <div className="relative">
                  <input
                    type={showEvolutionKey ? "text" : "password"}
                    placeholder="API Global/Instance Key"
                    value={evolutionApiKey}
                    onChange={(e) => setEvolutionApiKey(e.target.value)}
                    className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 font-mono focus:border-green-500 focus:outline-none pr-8"
                  />
                  <button
                    type="button"
                    onClick={() => setShowEvolutionKey(!showEvolutionKey)}
                    className="absolute right-2.5 top-2 text-gray-500 hover:text-gray-300"
                  >
                    {showEvolutionKey ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                  </button>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="text-[11px] font-mono text-gray-400 block mb-1">Instance</label>
                  <input
                    type="text"
                    placeholder="default"
                    value={evolutionInstance}
                    onChange={(e) => setEvolutionInstance(e.target.value)}
                    className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 font-mono focus:border-green-500 focus:outline-none"
                  />
                </div>
                <div>
                  <label className="text-[11px] font-mono text-gray-400 block mb-1">Recipient Number</label>
                  <input
                    type="text"
                    placeholder="919876543210"
                    value={evolutionRecipient}
                    onChange={(e) => setEvolutionRecipient(e.target.value)}
                    className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-600 font-mono focus:border-green-500 focus:outline-none"
                  />
                </div>
              </div>
            </div>
          </div>

          <div className="pt-3 border-t border-[#232936] flex items-center justify-between text-[11px] font-mono">
            <span className="text-gray-500">Status</span>
            <span className={evolutionEnabled ? "text-green-400 font-semibold" : "text-gray-500"}>
              {evolutionEnabled ? "ACTIVE" : "DISABLED"}
            </span>
          </div>
        </div>
      </div>

      {/* Row: Event Filtering Rules & Interactive Test Dispatcher */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
        {/* Event Filtering Rules */}
        <div className="lg:col-span-5 bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-4">
          <div className="flex items-center space-x-2.5 pb-3 border-b border-[#232936]">
            <div className="p-2 bg-purple-500/10 rounded-lg text-purple-400">
              <Sliders className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-white">Event Routing Rules</h3>
              <p className="text-[11px] text-gray-400">Configure which system occurrences trigger alerts</p>
            </div>
          </div>

          <div className="space-y-3 font-mono text-xs">
            <label className="flex items-center justify-between p-3 rounded-lg bg-[#0B0E14] border border-[#232936] cursor-pointer hover:border-gray-600 transition">
              <div className="flex items-center space-x-2.5">
                <ShieldAlert className="w-4 h-4 text-red-400" />
                <div>
                  <div className="text-gray-200 font-semibold">Risk Gate Rejections</div>
                  <div className="text-[10px] text-gray-500 font-sans">Price Collar, Drawdown, Margin violations</div>
                </div>
              </div>
              <input
                type="checkbox"
                checked={filterReject}
                onChange={(e) => setFilterReject(e.target.checked)}
                className="w-4 h-4 rounded border-[#232936] text-blue-600 focus:ring-0 cursor-pointer"
              />
            </label>

            <label className="flex items-center justify-between p-3 rounded-lg bg-[#0B0E14] border border-[#232936] cursor-pointer hover:border-gray-600 transition">
              <div className="flex items-center space-x-2.5">
                <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                <div>
                  <div className="text-gray-200 font-semibold">Order Fills & Executions</div>
                  <div className="text-[10px] text-gray-500 font-sans">Alpaca execution confirmations and partial fills</div>
                </div>
              </div>
              <input
                type="checkbox"
                checked={filterOrderFill}
                onChange={(e) => setFilterOrderFill(e.target.checked)}
                className="w-4 h-4 rounded border-[#232936] text-blue-600 focus:ring-0 cursor-pointer"
              />
            </label>

            <label className="flex items-center justify-between p-3 rounded-lg bg-[#0B0E14] border border-[#232936] cursor-pointer hover:border-gray-600 transition">
              <div className="flex items-center space-x-2.5">
                <AlertTriangle className="w-4 h-4 text-amber-400" />
                <div>
                  <div className="text-gray-200 font-semibold">Circuit Breaker & Kill Switch</div>
                  <div className="text-[10px] text-gray-500 font-sans">Emergency circuit tripping and system halt events</div>
                </div>
              </div>
              <input
                type="checkbox"
                checked={filterKillSwitch}
                onChange={(e) => setFilterKillSwitch(e.target.checked)}
                className="w-4 h-4 rounded border-[#232936] text-blue-600 focus:ring-0 cursor-pointer"
              />
            </label>

            <label className="flex items-center justify-between p-3 rounded-lg bg-[#0B0E14] border border-[#232936] cursor-pointer hover:border-gray-600 transition">
              <div className="flex items-center space-x-2.5">
                <MessageSquare className="w-4 h-4 text-blue-400" />
                <div>
                  <div className="text-gray-200 font-semibold">Order Placement Requests</div>
                  <div className="text-[10px] text-gray-500 font-sans">Incoming strategy order generation notices</div>
                </div>
              </div>
              <input
                type="checkbox"
                checked={filterOrderCreate}
                onChange={(e) => setFilterOrderCreate(e.target.checked)}
                className="w-4 h-4 rounded border-[#232936] text-blue-600 focus:ring-0 cursor-pointer"
              />
            </label>
          </div>
        </div>

        {/* Interactive Test Notification Dispatcher */}
        <div className="lg:col-span-7 bg-[#151922] border border-[#232936] rounded-xl p-5 space-y-4">
          <div className="flex items-center space-x-2.5 pb-3 border-b border-[#232936]">
            <div className="p-2 bg-emerald-500/10 rounded-lg text-emerald-400">
              <Send className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-white">Live Alert Dispatch Console</h3>
              <p className="text-[11px] text-gray-400">Trigger test payload directly through the notification service</p>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="text-[11px] font-mono text-gray-400 block mb-1">Target Channel</label>
              <select
                value={testChannel}
                onChange={(e) => setTestChannel(e.target.value)}
                className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white font-mono focus:border-blue-500 focus:outline-none"
              >
                <option value="all">All Active Channels</option>
                <option value="telegram">Telegram Only</option>
                <option value="ntfy">ntfy.sh Only</option>
                <option value="evolution">Evolution WhatsApp Only</option>
              </select>
            </div>

            <div>
              <label className="text-[11px] font-mono text-gray-400 block mb-1">Event Type</label>
              <select
                value={testEventType}
                onChange={(e) => setTestEventType(e.target.value)}
                className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white font-mono focus:border-blue-500 focus:outline-none"
              >
                <option value="reject">Risk Rejection Alert</option>
                <option value="fill">Order Filled Execution</option>
              </select>
            </div>

            <div>
              <label className="text-[11px] font-mono text-gray-400 block mb-1">Symbol & Qty</label>
              <div className="grid grid-cols-2 gap-2">
                <input
                  type="text"
                  value={testSymbol}
                  onChange={(e) => setTestSymbol(e.target.value.toUpperCase())}
                  className="bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white font-mono focus:border-blue-500 focus:outline-none"
                  placeholder="AAPL"
                />
                <input
                  type="number"
                  value={testQty}
                  onChange={(e) => setTestQty(parseInt(e.target.value, 10) || 0)}
                  className="bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white font-mono focus:border-blue-500 focus:outline-none"
                  placeholder="100"
                />
              </div>
            </div>

            <div>
              <label className="text-[11px] font-mono text-gray-400 block mb-1">Price ($)</label>
              <input
                type="number"
                step="0.01"
                value={testPrice}
                onChange={(e) => setTestPrice(parseFloat(e.target.value) || 0)}
                className="w-full bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white font-mono focus:border-blue-500 focus:outline-none"
                placeholder="260.00"
              />
            </div>

            {testEventType === "reject" && (
              <div className="md:col-span-2">
                <label className="text-[11px] font-mono text-gray-400 block mb-1">Gate & Rejection Reason</label>
                <div className="grid grid-cols-1 md:grid-cols-3 gap-2">
                  <input
                    type="text"
                    value={testGate}
                    onChange={(e) => setTestGate(e.target.value)}
                    className="bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white font-mono focus:border-blue-500 focus:outline-none"
                    placeholder="Gate Name"
                  />
                  <input
                    type="text"
                    value={testReason}
                    onChange={(e) => setTestReason(e.target.value)}
                    className="md:col-span-2 bg-[#0B0E14] border border-[#232936] rounded-lg px-3 py-1.5 text-xs text-white font-mono focus:border-blue-500 focus:outline-none"
                    placeholder="Reason details"
                  />
                </div>
              </div>
            )}
          </div>

          <div className="pt-2 flex items-center justify-between">
            <button
              onClick={handleSendTest}
              disabled={testing}
              className="flex items-center space-x-2 px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg text-xs font-mono font-semibold transition shadow-sm"
            >
              {testing ? (
                <RefreshCw className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Send className="w-3.5 h-3.5" />
              )}
              <span>{testing ? "Dispatching..." : "Dispatch Test Alert"}</span>
            </button>
            <span className="text-[11px] text-gray-500 font-mono">BFF: /api/v1/notify/test</span>
          </div>

          {testResult && (
            <div className="mt-3 p-3 bg-[#0B0E14] border border-[#232936] rounded-lg text-xs font-mono space-y-2">
              <div className="flex items-center justify-between text-gray-300">
                <span className="font-semibold text-white">Dispatch Result:</span>
                <span className="text-gray-400 text-[11px]">{new Date().toLocaleTimeString()}</span>
              </div>
              <pre className="text-[11px] text-emerald-400 overflow-x-auto p-2 bg-[#151922] rounded border border-[#232936]">
                {JSON.stringify(testResult, null, 2)}
              </pre>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
