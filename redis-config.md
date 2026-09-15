# Redis Configuration & Keyspace Registry

This document provides a comprehensive specification of all Redis keys, namespaces, data types, YAML hierarchy, and component-wise Read/Write access scopes across the trading platform.

---

## 1. Master Redis Keyspace YAML Hierarchy

```yaml
redis_keyspace:
  balance:
    cash:
      <provider>:
        description: "Live available cash balance per broker provider"
        type: "String (double)"
        example: "balance:cash:alpaca"
      legacy_fallback:
        description: "Unnamespaced legacy fallback cash key"
        type: "String (double)"
        example: "balance:cash"
    blocked:
      <provider>:
        description: "Margin locked/reserved for pending active orders"
        type: "String (double)"
        example: "balance:blocked:alpaca"
      legacy_fallback:
        description: "Unnamespaced legacy fallback blocked margin key"
        type: "String (double)"
        example: "balance:blocked"
    starting_equity:
      <provider>:
        description: "Day-start baseline equity used for daily drawdown calculations"
        type: "String (double)"
        example: "balance:starting_equity:alpaca"
      legacy_fallback:
        description: "Unnamespaced legacy fallback starting equity key"
        type: "String (double)"
        example: "balance:starting_equity"
    last_reset_date:
      <provider>:
        description: "ISO date string (YYYY-MM-DD) of last timezone-aware daily equity reset"
        type: "String"
        example: "balance:last_reset_date:alpaca"

  positions:
    <provider>:
      <symbol>:
        description: "Open position quantity for symbol per broker provider"
        type: "String (integer)"
        example: "positions:alpaca:AAPL"
    legacy_fallback:
      <symbol>:
        description: "Unnamespaced legacy position key"
        type: "String (integer)"
        example: "positions:AAPL"

  orders:
    pending:
      <provider>:
        description: "Set of active pending order IDs holding locked margin"
        type: "Set"
        example: "orders:pending:alpaca"
      legacy_fallback:
        description: "Unnamespaced legacy set of pending order IDs"
        type: "Set"
        example: "orders:pending"

  market:
    last_price:
      <provider>:
        <symbol>:
          description: "Latest market price per broker provider"
          type: "String (double)"
          example: "market:last_price:alpaca:AAPL"
      <symbol>:
        description: "Global fallback reference market price for symbol"
        type: "String (double)"
        example: "market:last_price:AAPL"

  system:
    kill_switch:
      global:
        description: "Global emergency lockdown flag ('true'/'false')"
        type: "String"
        example: "system:kill_switch"
      <provider>:
        description: "Provider-specific emergency lockdown flag ('true'/'false')"
        type: "String"
        example: "system:kill_switch:alpaca"

  provider:
    status:
      <provider>:
        description: "Broker connection manager health state ('ACTIVE'/'INACTIVE')"
        type: "String (enum)"
        example: "provider:status:alpaca"

  risk:
    config:
      max_daily_loss:
        <provider>: "Max daily drawdown cap before rejecting orders"
      price_collar_pct:
        <provider>: "Max price collar deviation percentage for symbol reference price"
      velocity_per_sec:
        <provider>: "Sliding window order submission rate cap per second"
      velocity_per_min:
        <provider>: "Sliding window order submission rate cap per minute"
      max_order_qty:
        <provider>: "Fat-finger protection max share/lot quantity ceiling per order"
      max_order_val:
        <provider>: "Max single order monetary value cap"
      max_concentration_pct:
        <provider>: "Max portfolio equity allocation percentage allowed for single symbol"
      stop_loss_pct:
        <provider>: "Default percentage threshold for auto Stop Loss attachment"
    velocity:
      sec:
        <provider>:
          <epochSecond>:
            description: "Sliding window order counter per second (TTL 2s)"
            type: "String (integer)"
            example: "risk:velocity:sec:alpaca:1789237740"
      min:
        <provider>:
          <epochMinute>:
            description: "Sliding window order counter per minute (TTL 120s)"
            type: "String (integer)"
            example: "risk:velocity:min:alpaca:29820629"

  notify:
    config:
      filter:
        reject: "Toggle notification alert for risk rejections ('true'/'false')"
        order_create: "Toggle notification alert for working order placements ('true'/'false')"
        order_fill: "Toggle notification alert for order fills/settlements ('true'/'false')"
        kill_switch: "Toggle notification alert for emergency lockdown ('true'/'false')"
      telegram:
        enabled: "Toggle Telegram channel ('true'/'false')"
        token: "Telegram Bot API token"
        chat_id: "Telegram destination chat/channel ID"
      ntfy:
        enabled: "Toggle ntfy.sh push channel ('true'/'false')"
        url: "ntfy server URL endpoint"
        topic: "ntfy topic channel name"
        token: "ntfy Bearer authentication token"
      evolution:
        enabled: "Toggle Evolution API WhatsApp channel ('true'/'false')"
        url: "Evolution API server URL"
        apikey: "Evolution API key"
        instance: "Evolution WhatsApp instance name"
        recipient: "Target WhatsApp recipient phone number"
```

---

## 2. Component-Wise Key Access Registry

### 1. `conn-mgr` (`connection-manager-alpaca`)
- **Read Keys**: *None*
- **Write Keys**: *None*
- **Scope**: **0 Redis Operations**
- **Description**: Follows the **Stateless Gateway Pattern** (Rule 1). All state mutation belongs exclusively to OMS/OPS.

---

### 2. `signal-gen` (Quantitative Strategy Modules)
- **Read Keys**: *None*
- **Write Keys**: *None*
- **Scope**: **0 Redis Operations**
- **Description**: Completely stateless strategy calculations. Moving averages and z-scores are maintained strictly in local Python RAM buffers (`self.prices`).

---

### 3. `ops` (`order-processing-service`)

| Key Pattern | Access Scope | Operation | Purpose & Description |
| :--- | :---: | :--- | :--- |
| `system:kill_switch`<br>`system:kill_switch:<provider>` | **Read / Write** | `GET`, `SET` | Read during Gate 1 pre-trade check; written via `RiskAdminController` REST endpoints. |
| `provider:status:<provider>` | **Write** | `SET` | Sets status (`"ACTIVE"` / `"INACTIVE"`) during startup health probe and gRPC transport exceptions. |
| `balance:cash:<provider>` | **Read** | `GET` | Evaluates available cash (`cash - blocked`) for margin locking at Gate 7. |
| `balance:blocked:<provider>` | **Read / Write** | `GET`, `INCRBY` | Reads current blocked margin; increments by estimated cost on risk approval. |
| `balance:starting_equity:<provider>` | **Read** | `GET` | Used to evaluate daily drawdown against `max_daily_loss` at Gate 2. |
| `orders:pending:<provider>` | **Write** | `SADD` | Tracks active pending order IDs holding locked margin. |
| `positions:<provider>:<symbol>` | **Read** | `KEYS`, `GET` | Scans open position share counts for mark-to-market total portfolio equity valuation. |
| `market:last_price:<provider>:<symbol>` | **Read** | `GET` | Reference market price for Price Collar validation (Gate 3) and position valuation. |
| `risk:velocity:sec:<provider>:<epochSec>` | **Read / Write** | `INCR`, `EXPIRE` | Increments order count in current 1-second window (Gate 4, TTL 2s). |
| `risk:velocity:min:<provider>:<epochMin>` | **Read / Write** | `INCR`, `EXPIRE` | Increments order count in current 1-minute window (Gate 4, TTL 120s). |
| `risk:config:*:<provider>` | **Read / Write** | `GET`, `SET` | Reads dynamic risk rule caps (Gates 2-6, 8); updated via `RiskAdminController` REST API. |

---

### 4. `oms` (`order-management-service`)

| Key Pattern | Access Scope | Operation | Purpose & Description |
| :--- | :---: | :--- | :--- |
| `balance:blocked:<provider>` | **Write** | `INCRBY` (Negative) | Decrements/releases blocked margin reserved during order placement upon order resolution. |
| `orders:pending:<provider>` | **Write** | `SREM` | Removes resolved order ID from active pending sets upon terminal order resolution. |
| `balance:cash:<provider>` | **Read / Write** | `GET`, `INCRBY` | **Write**: Settles cash on order fill (`-` for BUY, `+` for SELL).<br>**Read**: Read during daily equity refresh. |
| `positions:<provider>:<symbol>` | **Read / Write** | `GET`, `SET` | **Write**: Updates open position share count (`+` for BUY, `-` for SELL).<br>**Read**: Scanned during daily equity mark-to-market. |
| `provider:status:<provider>` | **Read / Write** | `GET`, `SET` | Probed by `ProviderHealthCheckJob` every 15s to attempt gRPC health recovery and set `"ACTIVE"`. |
| `balance:last_reset_date:<provider>` | **Read / Write** | `GET`, `SET` | Checked by `DailyEquityRefreshJob` every minute to evaluate local date rollover per timezone. |
| `balance:starting_equity:<provider>` | **Write** | `SET` | Resets starting equity baseline (`cash + open_positions_market_value`) at local midnight date rollover. |
| `market:last_price:<provider>:<symbol>` | **Read** | `GET` | Fetches current market price to value open positions during daily equity refresh. |

---

### 5. `notification svc` (`notification-service`)

| Key Pattern | Access Scope | Operation | Purpose & Description |
| :--- | :---: | :--- | :--- |
| `notify:config:filter:*` | **Read** | `GET` | Reads event filter preferences (`reject`, `order_create`, `order_fill`, `kill_switch`). |
| `notify:config:telegram:*` | **Read** | `GET` | Reads Telegram Bot API token, chat ID, and enabled status. |
| `notify:config:ntfy:*` | **Read** | `GET` | Reads ntfy server URL, topic name, Bearer token, and enabled status. |
| `notify:config:evolution:*` | **Read** | `GET` | Reads Evolution WhatsApp API URL, API key, instance, recipient phone, and enabled status. |

*Note*: Notification Service performs **0 Write operations** to Redis.

---

### 6. `price-cache-service`

| Key Pattern | Access Scope | Operation | Purpose & Description |
| :--- | :---: | :--- | :--- |
| `market:last_price:<symbol>` | **Write** | `MSET` (Pipelined) | Flushes deduplicated tick close price snapshots from gRPC stream every 0.5s via pipelined atomic `MSET`. |

*Note*: Price Cache Service performs **0 Read operations** from Redis.

---

### 7. `quant-dashboard` (`Streamlit Web Interface`)

| Key Pattern | Access Scope | Operation | Purpose & Description |
| :--- | :---: | :--- | :--- |
| `system:kill_switch` | **Read / Write** | `GET`, `SET` | Reads lockdown status for UI banner; writes `"true"`/`"false"` when user clicks lockdown buttons. |
| `balance:cash:<provider>`<br>`balance:starting_equity:<provider>`<br>`balance:blocked:<provider>` | **Read** | `GET` | Reads cash, day-start equity, and blocked margin metrics for live telemetry cards. |
| `positions:<provider>:*` | **Read** | `KEYS`, `GET` | Scans open position keys to display portfolio holdings table and compute total equity. |
| `market:last_price:<provider>:<symbol>` | **Read** | `GET` | Reads latest reference prices for mark-to-market portfolio equity calculation. |
| `risk:config:*:<provider>` | **Read / Write** | `GET`, `SET` | Reads dynamic risk rule caps for GUI inputs; writes updated threshold values on form submit. |
| `notify:config:*` | **Read / Write** | `GET`, `SET` | Reads notification credentials and channel toggles; writes updated alert configurations on submit. |
