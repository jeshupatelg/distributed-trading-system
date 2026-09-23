export interface RiskStatus {
  status: string;
  kill_switch_active: boolean;
  max_daily_loss: number;
  current_drawdown: number;
  price_collar_pct: number;
  provider: string;
  circuit_breaker_tripped: boolean;
  blocked_margin: number;
  cash_balance: number;
}

export interface RiskConfig {
  max_daily_loss?: number;
  price_collar_pct?: number;
  max_order_size?: number;
  provider?: string;
  [key: string]: any;
}

export interface Order {
  orderId: string;
  symbol: string;
  side: "BUY" | "SELL";
  qty: number;
  price: number;
  status: "PENDING" | "FILLED" | "COMPLETED" | "CANCELLED" | "REJECTED" | "FAILED" | "WORKING" | string;
  filledQty?: number;
  filledAvgPrice?: number;
  rejectReason?: string;
  timestamp: string;
  provider: string;
  strategy: string;
}

export interface MarketTick {
  symbol: string;
  price: number;
  time: number;
  high?: number;
  low?: number;
  open?: number;
}

export interface ServiceHealth {
  name: string;
  status: "healthy" | "degraded" | "unreachable";
  endpoint: string;
  latencyMs?: number;
  lastChecked: string;
}
