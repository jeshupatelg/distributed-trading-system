from datetime import datetime, timezone

def _format_time(epoch_ms: int = None) -> str:
    if epoch_ms:
        dt = datetime.fromtimestamp(epoch_ms / 1000.0, tz=timezone.utc)
    else:
        dt = datetime.now(timezone.utc)
    return dt.strftime("%Y-%m-%d %H:%M:%S UTC")


GATE_EXPLANATIONS = {
    "KILL_SWITCH": "Global Emergency Lockdown is active. All automated strategy executions are suspended.",
    "DAILY_LOSS_GATE": "Cumulative daily portfolio drawdown reached the maximum allowed loss threshold. Execution stopped to protect capital.",
    "PRICE_COLLAR": "The proposed price deviated too far from the latest market tick (fat-finger collar). Blocked to prevent trading at an unfavorable price.",
    "VELOCITY_THROTTLER": "Order rate threshold exceeded (too many orders in short succession). Throttled to prevent runaway loops and rate-limit violations.",
    "MAX_ORDER_QTY": "The requested quantity exceeds the maximum permissible single-order size limit.",
    "MAX_ORDER_VALUE": "The total currency value of this order exceeds the single-order maximum financial ceiling.",
    "PORTFOLIO_CONCENTRATION": "Executing this order would cause portfolio exposure in this single ticker to exceed the allowable concentration percentage.",
    "INSUFFICIENT_MARGIN": "Available free cash margin is insufficient to reserve funds required for this order.",
    "BROKER_ERROR": "The broker gateway encountered an unexpected communication or execution error while transmitting the order."
}

def _get_gate_explanation(gate: str) -> str:
    return GATE_EXPLANATIONS.get(gate.upper(), "Order blocked by Pre-Trade Risk Engine to safeguard portfolio rules.")


# ==========================================
# 1. RISK REJECTION (FAILED ORDERS) FORMATTERS
# ==========================================
def format_reject_telegram(data: dict) -> str:
    sym = data.get("symbol", "N/A")
    side = data.get("side", "N/A")
    qty = data.get("qty", 0)
    px = data.get("price", 0.0)
    cost = data.get("estimatedCost", px * qty)
    gate = data.get("riskGateLevel", "UNKNOWN_GATE")
    reason = data.get("rejectReason", "No reason provided")
    explanation = _get_gate_explanation(gate)
    strategy = data.get("strategy", "N/A")
    provider = data.get("provider", "N/A")
    order_id = data.get("orderId", "N/A")
    t_str = _format_time(data.get("timestamp"))

    return (
        f"🚨 <b>TRADE REJECTED BY RISK FIREWALL</b>\n"
        f"━━━━━━━━━━━━━━━━━━━━━━\n"
        f"• <b>Symbol:</b> <code>{sym}</code>\n"
        f"• <b>Action:</b> <b>{side}</b>\n"
        f"• <b>Quantity:</b> {qty:,} shares\n"
        f"• <b>Signal Price:</b> ${px:,.2f}\n"
        f"• <b>Estimated Value:</b> ${cost:,.2f}\n"
        f"• <b>Risk Gate:</b> <code>{gate}</code>\n"
        f"• <b>Rejection Reason:</b> {reason}\n"
        f"  <i>💡 Explanation: {explanation}</i>\n"
        f"• <b>Strategy:</b> {strategy} | <b>Provider:</b> {provider}\n"
        f"• <b>Order ID:</b> <code>{order_id[:18]}...</code>\n"
        f"• <b>Timestamp:</b> {t_str}"
    )


def format_reject_ntfy(data: dict) -> tuple[str, str]:
    sym = data.get("symbol", "N/A")
    side = data.get("side", "N/A")
    qty = data.get("qty", 0)
    px = data.get("price", 0.0)
    cost = data.get("estimatedCost", px * qty)
    gate = data.get("riskGateLevel", "UNKNOWN_GATE")
    reason = data.get("rejectReason", "No reason provided")
    explanation = _get_gate_explanation(gate)
    strategy = data.get("strategy", "N/A")
    provider = data.get("provider", "N/A")
    t_str = _format_time(data.get("timestamp"))

    title = f"🚨 Trade Rejected: {sym} ({gate})"
    body = (
        f"**Order Dropped by Pre-Trade Risk Firewall**\n\n"
        f"- **Action:** {side} {qty:,} shares @ ${px:,.2f} (${cost:,.2f})\n"
        f"- **Risk Gate Level:** `{gate}`\n"
        f"- **Reason:** {reason}\n"
        f"  *💡 Explanation: {explanation}*\n"
        f"- **Strategy:** {strategy} ({provider})\n"
        f"- **Time:** {t_str}"
    )
    return title, body


def format_reject_whatsapp(data: dict) -> str:
    sym = data.get("symbol", "N/A")
    side = data.get("side", "N/A")
    qty = data.get("qty", 0)
    px = data.get("price", 0.0)
    cost = data.get("estimatedCost", px * qty)
    gate = data.get("riskGateLevel", "UNKNOWN_GATE")
    reason = data.get("rejectReason", "No reason provided")
    explanation = _get_gate_explanation(gate)
    strategy = data.get("strategy", "N/A")
    provider = data.get("provider", "N/A")
    order_id = data.get("orderId", "N/A")
    t_str = _format_time(data.get("timestamp"))

    return (
        f"*🚨 TRADE REJECTED BY RISK FIREWALL*\n"
        f"━━━━━━━━━━━━━━━━━━━━━━\n"
        f"• *Symbol:* {sym}\n"
        f"• *Action:* {side}\n"
        f"• *Quantity:* {qty:,} shares\n"
        f"• *Signal Price:* ${px:,.2f}\n"
        f"• *Order Value:* ${cost:,.2f}\n"
        f"• *Risk Gate:* `{gate}`\n"
        f"• *Reason:* {reason}\n"
        f"  _💡 Explanation: {explanation}_\n"
        f"• *Strategy:* {strategy} | *Provider:* {provider}\n"
        f"• *Order ID:* {order_id[:18]}...\n"
        f"• *Time:* {t_str}"
    )


# ==========================================
# 2. ORDER CREATED (DISPATCHED) FORMATTERS
# ==========================================
def format_create_telegram(data: dict) -> str:
    sym = data.get("symbol", "N/A")
    side = data.get("side", "N/A")
    qty = data.get("qty", 0)
    px = data.get("limitPrice", 0.0)
    otype = data.get("orderType", "market")
    strategy = data.get("strategy", "N/A")
    provider = data.get("provider", "N/A")
    order_id = data.get("orderId", "N/A")

    return (
        f"🚀 <b>ORDER DISPATCHED TO BROKER</b>\n"
        f"━━━━━━━━━━━━━━━━━━━━━━\n"
        f"• <b>Symbol:</b> <code>{sym}</code>\n"
        f"• <b>Action:</b> <b>{side}</b>\n"
        f"• <b>Quantity:</b> {qty:,} shares\n"
        f"• <b>Type:</b> {otype.upper()}\n"
        f"• <b>Ref Price:</b> ${px:,.2f}\n"
        f"• <b>Strategy:</b> {strategy} | <b>Provider:</b> {provider}\n"
        f"• <b>Broker Order ID:</b> <code>{order_id[:18]}...</code>"
    )


def format_create_ntfy(data: dict) -> tuple[str, str]:
    sym = data.get("symbol", "N/A")
    side = data.get("side", "N/A")
    qty = data.get("qty", 0)
    px = data.get("limitPrice", 0.0)
    title = f"🚀 Order Placed: {side} {qty} {sym}"
    body = f"Dispatched {side} {qty:,} {sym} @ ${px:,.2f} to {data.get("provider", "broker")}."
    return title, body


def format_create_whatsapp(data: dict) -> str:
    sym = data.get("symbol", "N/A")
    side = data.get("side", "N/A")
    qty = data.get("qty", 0)
    px = data.get("limitPrice", 0.0)
    strategy = data.get("strategy", "N/A")
    provider = data.get("provider", "N/A")
    order_id = data.get("orderId", "N/A")

    return (
        f"*🚀 ORDER DISPATCHED TO BROKER*\n"
        f"━━━━━━━━━━━━━━━━━━━━━━\n"
        f"• *Symbol:* {sym}\n"
        f"• *Action:* {side}\n"
        f"• *Quantity:* {qty:,} shares\n"
        f"• *Price:* ${px:,.2f}\n"
        f"• *Strategy:* {strategy} | *Provider:* {provider}\n"
        f"• *Order ID:* {order_id[:18]}..."
    )


# ==========================================
# 3. ORDER COMPLETED / FILLED FORMATTERS
# ==========================================
def format_complete_telegram(data: dict) -> str:
    sym = data.get("symbol", "N/A")
    side = data.get("side", "N/A")
    status = data.get("status", "N/A")
    req_qty = data.get("qty", 0)
    filled_qty = data.get("filledQty", 0)
    avg_px = data.get("filledAvgPrice", 0.0)
    total_val = avg_px * filled_qty
    strategy = data.get("strategy", "N/A")
    provider = data.get("provider", "N/A")
    order_id = data.get("orderId", "N/A")

    icon = "✅" if status == "COMPLETED" else "⚠️"
    return (
        f"{icon} <b>ORDER {status} &amp; SETTLED</b>\n"
        f"━━━━━━━━━━━━━━━━━━━━━━\n"
        f"• <b>Symbol:</b> <code>{sym}</code>\n"
        f"• <b>Action:</b> <b>{side}</b>\n"
        f"• <b>Filled:</b> {filled_qty:,} / {req_qty:,} shares\n"
        f"• <b>Filled Avg Price:</b> ${avg_px:,.2f}\n"
        f"• <b>Settled Value:</b> ${total_val:,.2f}\n"
        f"• <b>Status:</b> <b>{status}</b>\n"
        f"• <b>Strategy:</b> {strategy} | <b>Provider:</b> {provider}\n"
        f"• <b>Broker Order ID:</b> <code>{order_id[:18]}...</code>"
    )


def format_complete_ntfy(data: dict) -> tuple[str, str]:
    sym = data.get("symbol", "N/A")
    side = data.get("side", "N/A")
    status = data.get("status", "N/A")
    filled_qty = data.get("filledQty", 0)
    avg_px = data.get("filledAvgPrice", 0.0)

    title = f"✅ Order Settled: {side} {filled_qty} {sym} ({status})"
    body = f"Order {status}. Filled {filled_qty:,} {sym} @ ${avg_px:,.2f} (${avg_px * filled_qty:,.2f})."
    return title, body


def format_complete_whatsapp(data: dict) -> str:
    sym = data.get("symbol", "N/A")
    side = data.get("side", "N/A")
    status = data.get("status", "N/A")
    req_qty = data.get("qty", 0)
    filled_qty = data.get("filledQty", 0)
    avg_px = data.get("filledAvgPrice", 0.0)
    total_val = avg_px * filled_qty
    strategy = data.get("strategy", "N/A")
    provider = data.get("provider", "N/A")
    order_id = data.get("orderId", "N/A")

    icon = "✅" if status == "COMPLETED" else "⚠️"
    return (
        f"*{icon} ORDER {status} & SETTLED*\n"
        f"━━━━━━━━━━━━━━━━━━━━━━\n"
        f"• *Symbol:* {sym}\n"
        f"• *Action:* {side}\n"
        f"• *Filled:* {filled_qty:,} / {req_qty:,} shares\n"
        f"• *Filled Avg Price:* ${avg_px:,.2f}\n"
        f"• *Settled Value:* ${total_val:,.2f}\n"
        f"• *Status:* {status}\n"
        f"• *Strategy:* {strategy} | *Provider:* {provider}\n"
        f"• *Broker Order ID:* {order_id[:18]}..."
    )
