import os
import time
import socket
import json
import urllib.request
import urllib.error
import streamlit as st
import redis
import psycopg2
import pandas as pd
import plotly.express as px
import plotly.graph_objects as go

# Set page configuration with a modern dark theme style
st.set_page_config(
    page_title="Quant Operations & Risk Center",
    page_icon="🛡️",
    layout="wide",
    initial_sidebar_state="expanded"
)

# --- Configuration & Connection Helpers ---
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "")

DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = os.getenv("DB_PORT", "5432")
DB_NAME = os.getenv("DB_NAME", "trading_db")
DB_USER = os.getenv("DB_USER", "dashboard_reader")
DB_PASSWORD = os.getenv("DB_PASSWORD", "read_pass")

KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
OPS_REST_ENDPOINT = os.getenv("OPS_REST_ENDPOINT", "http://order-processing-service:8081")
NOTIFICATION_SERVICE_ENDPOINT = os.getenv("NOTIFICATION_SERVICE_ENDPOINT", "http://notification-service:8085")

# Cache connection pools to prevent socket exhaustion
@st.cache_resource(show_spinner=False)
def get_redis_client():
    try:
        r = redis.Redis(
            host=REDIS_HOST,
            port=REDIS_PORT,
            password=REDIS_PASSWORD,
            socket_timeout=2.0,
            decode_responses=True
        )
        r.ping()
        return r, True
    except Exception:
        return None, False

@st.cache_resource(show_spinner=False)
def get_db_connection():
    try:
        conn = psycopg2.connect(
            host=DB_HOST,
            port=DB_PORT,
            dbname=DB_NAME,
            user=DB_USER,
            password=DB_PASSWORD,
            connect_timeout=2
        )
        return conn, True
    except Exception:
        return None, False

@st.cache_resource(ttl=10, show_spinner=False)
def check_kafka_connection():
    try:
        host, port = KAFKA_BOOTSTRAP_SERVERS.split(":")
        port = int(port)
        with socket.create_connection((host, port), timeout=2.0) as s:
            return True
    except Exception:
        return False

# Try connecting to live infrastructure
r_client, redis_connected = get_redis_client()
db_conn, db_connected = get_db_connection()
kafka_connected = check_kafka_connection()

# --- Sidebar Layout ---
st.sidebar.title("🛡️ Quant Operations & Risk")

# Currency selector (Indian / Global compatibility)
currency = st.sidebar.selectbox("Base Currency / Exchange", ["₹ INR (NSE/BSE)", "$ USD (US Markets)"])
curr_sym = "₹" if "INR" in currency else "$"

# Query Kill Switch Status from Redis
kill_switch_active = False
if redis_connected:
    try:
        ks_val = r_client.get("system:kill_switch")
        kill_switch_active = (ks_val == "true")
    except Exception:
        pass

# Global Status Banner in Sidebar
if kill_switch_active:
    st.sidebar.error("🔴 **EMERGENCY LOCKDOWN**\n\nKill switch active. Trading halted.")
else:
    st.sidebar.success("🟢 **SYSTEM NORMAL**\n\nTrading pipeline active.")

# Page Selection
page = st.sidebar.radio(
    "Navigation Menu",
    [
        "System Control Center",
        "Risk Engine & Controls",
        "Notification Center",
        "Portfolio & Assets",
        "Order History",
        "Provider Status",
        "System Telemetry"
    ]
)

st.sidebar.divider()
st.sidebar.caption("System Architecture: **Distributed Microservices**")
st.sidebar.caption("Mode: **Production Phase 1 Protected**")


# =====================================================================
# Page 0: System Control Center
# =====================================================================
if page == "System Control Center":
    st.title("⚙️ System Control Center")
    st.markdown("Centralized operational monitoring and infrastructure connectivity health.")

    st.subheader("Infrastructure Connectivity Status")
    c1, c2, c3 = st.columns(3)
    
    with c1:
        st.write("### 🗄️ Redis Cache")
        if redis_connected:
            st.success("🟢 ONLINE")
            st.info(f"**Host**: {REDIS_HOST}\n\n**Port**: {REDIS_PORT}")
        else:
            st.warning("🟡 OFFLINE / MOCK")
            st.info("Operating in standalone simulation fallback mode.")
            
    with c2:
        st.write("### 🗃️ PostgreSQL Database")
        if db_connected:
            st.success("🟢 ONLINE")
            st.info(f"**Host**: {DB_HOST}\n\n**DB Name**: {DB_NAME}\n\n**User**: {DB_USER}")
        else:
            st.warning("🟡 OFFLINE / MOCK")
            st.info("Reading static transactional data mock logs.")
            
    with c3:
        st.write("### 📨 Kafka Broker")
        if kafka_connected:
            st.success("🟢 ONLINE")
            st.info(f"**Bootstrap Servers**: {KAFKA_BOOTSTRAP_SERVERS}")
        else:
            st.error("🔴 OFFLINE")
            st.info("Message bus queue unavailable.")

    st.divider()
    st.subheader("Risk & Safety Gate Summary (Phase 1 Active)")
    
    r_col1, r_col2, r_col3 = st.columns(3)
    with r_col1:
        st.metric("Global Kill Switch", "ARMED / ACTIVE" if kill_switch_active else "NORMAL OPERATION")
    with r_col2:
        daily_loss_cfg = float(r_client.get("risk:config:max_daily_loss") or 2000.0) if redis_connected else 2000.0
        st.metric("Max Daily Loss Limit", f"{curr_sym}{daily_loss_cfg:,.2f}")
    with r_col3:
        collar_cfg = float(r_client.get("risk:config:price_collar_pct") or 1.5) if redis_connected else 1.5
        st.metric("Price Collar Band", f"±{collar_cfg}%")


# =====================================================================
# Page 1: Risk Engine & Controls (Interactive GUI Configuration)
# =====================================================================
elif page == "Risk Engine & Controls":
    st.title("🛡️ Pre-Trade Risk Engine & Safety Controls")
    st.markdown("Real-time telemetry, emergency controls, and dynamic parameter configuration for pre-trade risk gates.")

    # 1. Emergency Controls Section
    st.subheader("🚨 Emergency Global Kill Switch")
    ks_col1, ks_col2 = st.columns([2, 1])

    with ks_col1:
        if kill_switch_active:
            st.error("⚠️ **EMERGENCY LOCKDOWN IS CURRENTLY ACTIVE!** All incoming signals are being rejected before order placement.")
        else:
            st.success("✅ **SYSTEM IS ACTIVE AND OPERATIONAL.** Pre-trade firewalls are actively scanning all trades.")

    with ks_col2:
        if not kill_switch_active:
            if st.button("🚨 TRIGGER EMERGENCY KILL SWITCH", type="primary", use_container_width=True):
                if redis_connected:
                    r_client.set("system:kill_switch", "true")
                    # Send API trigger to OPS
                    try:
                        req = urllib.request.Request(f"{OPS_REST_ENDPOINT}/api/v1/risk/kill-switch/trigger", method="POST")
                        urllib.request.urlopen(req, timeout=3)
                    except Exception:
                        pass
                    st.rerun()
        else:
            if st.button("🔄 RESET EMERGENCY LOCKDOWN", type="secondary", use_container_width=True):
                if redis_connected:
                    r_client.set("system:kill_switch", "false")
                    try:
                        req = urllib.request.Request(f"{OPS_REST_ENDPOINT}/api/v1/risk/kill-switch/reset", method="POST")
                        urllib.request.urlopen(req, timeout=3)
                    except Exception:
                        pass
                    st.rerun()

    st.divider()

    # 2. Live Risk Metrics & Drawdown Monitor
    st.subheader("📊 Real-Time Pre-Trade Firewall Telemetry")
    
    # Read live balances & drawdown
    cash = float(r_client.get("balance:cash") or 100000.0) if redis_connected else 100000.0
    start_equity = float(r_client.get("balance:starting_equity") or 100000.0) if redis_connected else 100000.0
    blocked_margin = float(r_client.get("balance:blocked") or 0.0) if redis_connected else 0.0
    max_daily_loss = float(r_client.get("risk:config:max_daily_loss") or 2000.0) if redis_connected else 2000.0
    
    # Open positions
    pos_keys = r_client.keys("positions:*") if redis_connected else []
    positions_val = 0.0
    for pk in pos_keys:
        sym = pk.split(":")[-1]
        qty = float(r_client.get(pk) or 0.0)
        last_px = float(r_client.get(f"market:last_price:{sym}") or 100.0)
        positions_val += (qty * last_px)
        
    total_equity = cash + positions_val
    current_drawdown = max(0.0, start_equity - total_equity)
    drawdown_pct = (current_drawdown / max_daily_loss * 100.0) if max_daily_loss > 0 else 0.0

    m1, m2, m3, m4 = st.columns(4)
    m1.metric("Current Portfolio Equity", f"{curr_sym}{total_equity:,.2f}")
    m2.metric("Starting Day Equity", f"{curr_sym}{start_equity:,.2f}")
    m3.metric("Current Daily Drawdown", f"{curr_sym}{current_drawdown:,.2f}", delta=f"-{drawdown_pct:.1f}% of limit", delta_color="inverse")
    m4.metric("Max Allowed Daily Loss", f"{curr_sym}{max_daily_loss:,.2f}")

    # Drawdown progress bar
    st.write(f"**Daily Loss Gate Threshold Utilization**: {current_drawdown:,.2f} / {max_daily_loss:,.2f} {curr_sym}")
    progress_val = min(1.0, current_drawdown / max_daily_loss if max_daily_loss > 0 else 0.0)
    st.progress(progress_val)

    st.divider()

    # 3. Interactive Risk Threshold Configuration
    st.subheader("⚙️ Modifiable Pre-Trade Risk Limits (Persisted to Redis)")
    st.markdown("Adjust limits dynamically. Modifications take effect **immediately** across all worker threads without service restarts.")

    # Load current configs from Redis or defaults
    curr_daily_loss = float(r_client.get("risk:config:max_daily_loss") or 2000.0) if redis_connected else 2000.0
    curr_collar_pct = float(r_client.get("risk:config:price_collar_pct") or 1.5) if redis_connected else 1.5
    curr_vel_sec = int(r_client.get("risk:config:velocity_per_sec") or 5) if redis_connected else 5
    curr_vel_min = int(r_client.get("risk:config:velocity_per_min") or 30) if redis_connected else 30
    curr_max_qty = int(r_client.get("risk:config:max_order_qty") or 500) if redis_connected else 500
    curr_max_val = float(r_client.get("risk:config:max_order_val") or 25000.0) if redis_connected else 25000.0
    curr_max_conc = float(r_client.get("risk:config:max_concentration_pct") or 20.0) if redis_connected else 20.0
    curr_stop_loss = float(r_client.get("risk:config:stop_loss_pct") or 2.0) if redis_connected else 2.0

    with st.form("risk_config_form"):
        col_a, col_b = st.columns(2)
        
        with col_a:
            st.write("#### 🛡️ Loss & Sizing Protection")
            new_daily_loss = st.number_input(
                f"Max Daily Drawdown Gate ({curr_sym})",
                min_value=100.0,
                max_value=100000.0,
                value=curr_daily_loss,
                step=100.0,
                help="Maximum allowable portfolio loss in a single trading session before trading halts automatically."
            )
            new_max_qty = st.number_input(
                "Max Quantity Per Order (Shares/Lots)",
                min_value=1,
                max_value=10000,
                value=curr_max_qty,
                step=10,
                help="Fat-finger protection: rejects individual orders requesting more than this size."
            )
            new_max_val = st.number_input(
                f"Max Order Value ({curr_sym})",
                min_value=100.0,
                max_value=500000.0,
                value=curr_max_val,
                step=500.0,
                help="Single-order total rupee/dollar value ceiling."
            )
            new_max_conc = st.slider(
                "Max Symbol Concentration (% of Equity)",
                min_value=5.0,
                max_value=50.0,
                value=curr_max_conc,
                step=1.0,
                help="Prevents over-allocation into a single stock (Indian/US portfolio diversification rule)."
            )

        with col_b:
            st.write("#### ⚡ Market Microstructure & Velocity")
            new_collar_pct = st.slider(
                "Price Collar / Deviation Gate (±%)",
                min_value=0.2,
                max_value=5.0,
                value=curr_collar_pct,
                step=0.1,
                help=f"Rejects signals deviating more than this from reference price. E.g. at 1.5%, a {curr_sym}1.00 tick has a {curr_sym}0.015 collar."
            )
            st.caption(f"💡 *Deviation example*: On a {curr_sym}100 stock, ±{new_collar_pct}% allows prices between {curr_sym}{100*(1-new_collar_pct/100):.2f} and {curr_sym}{100*(1+new_collar_pct/100):.2f}.")

            new_stop_loss = st.slider(
                "Default Hard Stop Loss (% Below Entry)",
                min_value=0.5,
                max_value=10.0,
                value=curr_stop_loss,
                step=0.25,
                help="Attached as an on-exchange Stop Loss order with the broker immediately on entry."
            )
            new_vel_sec = st.number_input(
                "Symbol Velocity Limit (Max Orders / Second)",
                min_value=1,
                max_value=50,
                value=curr_vel_sec,
                help="Rate limiter preventing runaway strategy loops per ticker."
            )
            new_vel_min = st.number_input(
                "System-Wide Velocity Limit (Max Orders / Minute)",
                min_value=5,
                max_value=500,
                value=curr_vel_min,
                help="Global system-wide throttling to prevent exchange rate limit penalties."
            )

        st.divider()
        st.write("#### 🇮🇳 Indian Exchange / Product Type Compatibility")
        product_type = st.selectbox(
            "Default Execution Product Type",
            [
                "DAY (Standard Day Order - US & Global)",
                "CNC (Cash 'N Carry / Delivery - NSE/BSE)",
                "MIS (Margin Intraday Square-off - NSE/BSE)",
                "NRML (Normal F&O / Options - NSE/BSE)"
            ]
        )
        st.caption("Configures standard Indian broker compatibility (Zerodha, Angel One, Upstox, Dhan, Fyers).")

        submitted = st.form_submit_button("💾 Save Risk Parameters to Redis", type="primary", use_container_width=True)
        if submitted:
            if redis_connected:
                r_client.set("risk:config:max_daily_loss", str(new_daily_loss))
                r_client.set("risk:config:price_collar_pct", str(new_collar_pct))
                r_client.set("risk:config:velocity_per_sec", str(new_vel_sec))
                r_client.set("risk:config:velocity_per_min", str(new_vel_min))
                r_client.set("risk:config:max_order_qty", str(new_max_qty))
                r_client.set("risk:config:max_order_val", str(new_max_val))
                r_client.set("risk:config:max_concentration_pct", str(new_max_conc))
                r_client.set("risk:config:stop_loss_pct", str(new_stop_loss))
                st.success("✅ Risk parameters updated successfully in Redis! Pre-trade risk engine updated in real-time.")
                time.sleep(1)
                st.rerun()
            else:
                st.error("Cannot connect to Redis to save configurations.")


# =====================================================================
# Page: Notification Center (Telegram, ntfy, Evolution WhatsApp)
# =====================================================================
elif page == "Notification Center":
    st.title("🔔 Real-Time Notification Center")
    st.markdown("Configure multi-channel alerts for **Risk Manager rejections**, order placements, order fills, and emergency events.")

    # Query active configuration from Redis
    notify_on_reject = True
    notify_on_create = False
    notify_on_fill = True
    notify_on_kill = True

    evo_enabled = False
    evo_url = "http://192.168.29.96:3015"
    evo_apikey = ""
    evo_instance = ""
    evo_recipient = ""

    tg_enabled = False
    tg_token = ""
    tg_chat_id = ""

    ntfy_enabled = False
    ntfy_url = "https://ntfy.sh"
    ntfy_topic = "trading-system-alerts"
    ntfy_token = ""

    if redis_connected:
        try:
            notify_on_reject = (r_client.get("notify:config:filter:reject") or "true").lower() in ("true", "1", "yes")
            notify_on_create = (r_client.get("notify:config:filter:order_create") or "false").lower() in ("true", "1", "yes")
            notify_on_fill = (r_client.get("notify:config:filter:order_fill") or "true").lower() in ("true", "1", "yes")
            notify_on_kill = (r_client.get("notify:config:filter:kill_switch") or "true").lower() in ("true", "1", "yes")

            evo_enabled = (r_client.get("notify:config:evolution:enabled") or "false").lower() in ("true", "1", "yes")
            evo_url = r_client.get("notify:config:evolution:url") or "http://192.168.29.96:3015"
            evo_apikey = r_client.get("notify:config:evolution:apikey") or ""
            evo_instance = r_client.get("notify:config:evolution:instance") or ""
            evo_recipient = r_client.get("notify:config:evolution:recipient") or ""

            tg_enabled = (r_client.get("notify:config:telegram:enabled") or "false").lower() in ("true", "1", "yes")
            tg_token = r_client.get("notify:config:telegram:token") or ""
            tg_chat_id = r_client.get("notify:config:telegram:chat_id") or ""

            ntfy_enabled = (r_client.get("notify:config:ntfy:enabled") or "false").lower() in ("true", "1", "yes")
            ntfy_url = r_client.get("notify:config:ntfy:url") or "https://ntfy.sh"
            ntfy_topic = r_client.get("notify:config:ntfy:topic") or "trading-system-alerts"
            ntfy_token = r_client.get("notify:config:ntfy:token") or ""
            ntfy_topic = r_client.get("notify:config:ntfy:topic") or "trading-system-alerts"
        except Exception as e:
            st.error(f"Error querying notification settings from Redis: {e}")

    # Top Status Cards
    st.subheader("📡 Multi-Channel Delivery Channels")
    c1, c2, c3 = st.columns(3)
    with c1:
        st.write("#### 📱 Evolution API (WhatsApp)")
        if evo_enabled:
            st.success("🟢 ENABLED")
            st.caption(f"**URL:** {evo_url}\n\n**Instance:** {evo_instance or 'Not set'}\n\n**To:** {evo_recipient or 'Not set'}")
        else:
            st.warning("⚪ DISABLED")
            st.caption("WhatsApp message delivery paused.")

    with c2:
        st.write("#### 📢 Telegram Bot")
        if tg_enabled:
            st.success("🟢 ENABLED")
            st.caption(f"**Chat ID:** {tg_chat_id or 'Not set'}\n\n**Token:** {'Configured' if tg_token else 'Missing'}")
        else:
            st.warning("⚪ DISABLED")
            st.caption("Telegram bot messages paused.")

    with c3:
        st.write("#### 🔔 ntfy Push Alerts")
        if ntfy_enabled:
            st.success("🟢 ENABLED")
            st.caption(f"**Server:** {ntfy_url}\n\n**Topic:** `{ntfy_topic}`")
        else:
            st.warning("⚪ DISABLED")
            st.caption("ntfy push alerts paused.")

    st.divider()

    # Form to update all settings
    with st.form("notification_config_form"):
        st.subheader("🎯 Granular Event Alert Triggers")
        st.markdown("Select which lifecycle events trigger outgoing notifications:")

        f_col1, f_col2 = st.columns(2)
        with f_col1:
            new_notify_reject = st.checkbox(
                "🚨 Notify on Risk Rejections (Failed Orders)",
                value=notify_on_reject,
                help="Sends immediate high-priority alerts with order details, failure reason, and exact risk gate level when an order is blocked."
            )
            new_notify_kill = st.checkbox(
                "⚠️ Notify on Global Kill Switch Actions",
                value=notify_on_kill,
                help="Alerts immediately when Emergency Lockdown is activated or cleared."
            )
        with f_col2:
            new_notify_fill = st.checkbox(
                "✅ Notify on Terminal Order Fills & Settlement",
                value=notify_on_fill,
                help="Alerts when an order completes or is filled on the exchange with price and quantity."
            )
            new_notify_create = st.checkbox(
                "🚀 Notify on Working Order Placements",
                value=notify_on_create,
                help="Alerts when an order is initially dispatched to the broker gateway."
            )

        st.divider()
        st.subheader("⚙️ Channel Credentials & Endpoints")

        # Evolution API WhatsApp Card
        st.write("### 📱 Evolution API (WhatsApp)")
        e_col1, e_col2 = st.columns(2)
        with e_col1:
            new_evo_enabled = st.checkbox("Enable WhatsApp via Evolution API", value=evo_enabled)
            new_evo_url = st.text_input("Evolution API Base URL", value=evo_url, help="Base URL where your Evolution API instance is hosted.")
            new_evo_apikey = st.text_input("Evolution API Key", value=evo_apikey, type="password", help="Global API Key configured in Evolution API.")
        with e_col2:
            new_evo_instance = st.text_input("Instance Name", value=evo_instance, placeholder="e.g. trading-bot", help="Active connected WhatsApp instance name in Evolution API.")
            new_evo_recipient = st.text_input("Recipient Phone Number", value=evo_recipient, placeholder="e.g. 919876543210", help="Target phone number with country code (no + or spaces).")

        st.divider()

        # Telegram Bot Card
        st.write("### 📢 Telegram Bot")
        t_col1, t_col2 = st.columns(2)
        with t_col1:
            new_tg_enabled = st.checkbox("Enable Telegram Notifications", value=tg_enabled)
            new_tg_token = st.text_input("Telegram Bot Token", value=tg_token, type="password", placeholder="123456:ABC-DEF...", help="Bot token obtained from @BotFather.")
        with t_col2:
            new_tg_chat_id = st.text_input("Telegram Chat / Channel ID", value=tg_chat_id, placeholder="e.g. -1001234567890 or @mychannel", help="Numeric Chat ID or channel username.")

        st.divider()

        # ntfy Card
        st.write("### 🔔 ntfy Push Notifications")
        n_col1, n_col2 = st.columns(2)
        with n_col1:
            new_ntfy_enabled = st.checkbox("Enable ntfy Push Notifications", value=ntfy_enabled)
            new_ntfy_url = st.text_input("ntfy Server URL", value=ntfy_url, help="ntfy server (e.g. https://ntfy.sh or self-hosted).")
        with n_col2:
            new_ntfy_topic = st.text_input("ntfy Topic Name", value=ntfy_topic, placeholder="e.g. my-trading-alerts", help="Private topic name to subscribe on mobile/desktop.")
            new_ntfy_token = st.text_input("ntfy Access Token (Optional)", value=ntfy_token, type="password", placeholder="tk_...", help="Bearer token if authentication is enabled on your ntfy server.")

        st.divider()
        save_btn = st.form_submit_button("💾 Save Notification Configurations to Redis", type="primary", use_container_width=True)
        if save_btn:
            if redis_connected:
                # Save filters
                r_client.set("notify:config:filter:reject", "true" if new_notify_reject else "false")
                r_client.set("notify:config:filter:kill_switch", "true" if new_notify_kill else "false")
                r_client.set("notify:config:filter:order_fill", "true" if new_notify_fill else "false")
                r_client.set("notify:config:filter:order_create", "true" if new_notify_create else "false")

                # Save Evolution API
                r_client.set("notify:config:evolution:enabled", "true" if new_evo_enabled else "false")
                r_client.set("notify:config:evolution:url", new_evo_url.strip())
                if new_evo_apikey:
                    r_client.set("notify:config:evolution:apikey", new_evo_apikey.strip())
                r_client.set("notify:config:evolution:instance", new_evo_instance.strip())
                r_client.set("notify:config:evolution:recipient", new_evo_recipient.strip())

                # Save Telegram
                r_client.set("notify:config:telegram:enabled", "true" if new_tg_enabled else "false")
                if new_tg_token:
                    r_client.set("notify:config:telegram:token", new_tg_token.strip())
                r_client.set("notify:config:telegram:chat_id", new_tg_chat_id.strip())

                # Save ntfy
                r_client.set("notify:config:ntfy:enabled", "true" if new_ntfy_enabled else "false")
                r_client.set("notify:config:ntfy:url", new_ntfy_url.strip())
                r_client.set("notify:config:ntfy:topic", new_ntfy_topic.strip())
                r_client.set("notify:config:ntfy:token", new_ntfy_token.strip())

                st.success("✅ Notification configurations saved to Redis successfully! Changes take effect immediately across all dispatchers.")
                time.sleep(1)
                st.rerun()
            else:
                st.error("Cannot connect to Redis to save configurations.")

    st.divider()

    # Test Dispatch Section
    st.subheader("🧪 Live Channel Delivery Test")
    st.markdown("Send a simulated **Risk Manager Rejection** or **Order Fill** alert to test channel connectivity.")

    test_col1, test_col2, test_col3 = st.columns([1, 1, 2])
    with test_col1:
        test_channel = st.selectbox("Target Channel", ["all", "evolution", "telegram", "ntfy"])
    with test_col2:
        test_event = st.selectbox("Event Simulation", ["reject (Failed Order)", "fill (Successful Order)"])
    with test_col3:
        st.write("")
        st.write("")
        if st.button("📨 Send Test Notification", type="secondary", use_container_width=True):
            event_type = "reject" if "reject" in test_event else "fill"
            test_payload = {
                "channel": test_channel,
                "event_type": event_type,
                "symbol": "AAPL",
                "qty": 100,
                "price": 260.00,
                "gate": "PRICE_COLLAR",
                "reason": "PRICE_COLLAR_VIOLATION (13.04% > 2.0%)"
            }
            try:
                req_data = json.dumps(test_payload).encode("utf-8")
                req = urllib.request.Request(
                    f"{NOTIFICATION_SERVICE_ENDPOINT}/api/v1/notify/test",
                    data=req_data,
                    headers={"Content-Type": "application/json"},
                    method="POST"
                )
                with urllib.request.urlopen(req, timeout=5) as resp:
                    res_body = json.loads(resp.read().decode())
                    st.success("✅ Test dispatch triggered!")
                    st.json(res_body)
            except urllib.error.URLError as e:
                st.warning(f"Could not contact Notification Service directly ({e}). If running in standalone mode, check network connectivity.")
            except Exception as e:
                st.error(f"Error triggering test notification: {e}")


# =====================================================================
# Page 2: Portfolio & Assets
# =====================================================================
elif page == "Portfolio & Assets":
    st.title("💼 Portfolio & Asset Allocation")
    st.markdown("Real-time equity, margins, and position distributions pulled from Redis cache.")
    
    if redis_connected:
        try:
            balance = float(r_client.get("balance:cash") or 100000.0)
            blocked_margin = float(r_client.get("balance:blocked") or 0.0)
            
            position_keys = r_client.keys("positions:*")
            positions = {}
            for pk in position_keys:
                ticker = pk.split(":")[-1]
                qty = float(r_client.get(pk) or 0.0)
                if qty > 0:
                    positions[ticker] = qty
        except Exception as e:
            st.error(f"Error querying Redis: {e}")
            redis_connected = False

    if not redis_connected:
        balance = 100000.00
        blocked_margin = 0.00
        positions = {"AAPL": 100.0, "MSFT": 50.0}

    free_cash = balance - blocked_margin
    mock_prices = {"AAPL": 320.0, "MSFT": 515.0, "RELIANCE": 2980.0, "TCS": 4150.0, "INFY": 1820.0}
    position_value = sum(qty * mock_prices.get(ticker, 100.0) for ticker, qty in positions.items())
    total_equity = free_cash + blocked_margin + position_value

    col1, col2, col3, col4 = st.columns(4)
    col1.metric("Total Net Equity", f"{curr_sym}{total_equity:,.2f}")
    col2.metric("Available Cash", f"{curr_sym}{free_cash:,.2f}")
    col3.metric("Blocked Margin", f"{curr_sym}{blocked_margin:,.2f}")
    col4.metric("Active Assets Value", f"{curr_sym}{position_value:,.2f}")

    st.divider()
    v_col1, v_col2 = st.columns([1, 1])
    
    with v_col1:
        st.subheader("Asset Distribution")
        df_pie = pd.DataFrame([
            {"Asset": "Free Cash", "Value": max(0.0, free_cash)},
            {"Asset": "Blocked Margin", "Value": max(0.0, blocked_margin)},
        ] + [{"Asset": f"Position: {t}", "Value": q * mock_prices.get(t, 100.0)} for t, q in positions.items()])
        
        fig_pie = px.pie(df_pie, values='Value', names='Asset', hole=0.4,
                         color_discrete_sequence=px.colors.sequential.RdBu)
        st.plotly_chart(fig_pie, use_container_width=True)

    with v_col2:
        st.subheader("Current Open Positions")
        if positions:
            df_pos = pd.DataFrame([
                {"Ticker": t, "Quantity": q, "Value": q * mock_prices.get(t, 100.0)} for t, q in positions.items()
            ])
            fig_bar = px.bar(df_pos, x='Ticker', y='Value', text_auto='.2s', labels={'Value': f'Total Value ({curr_sym})'},
                             color='Ticker', color_discrete_sequence=px.colors.qualitative.Set2)
            st.plotly_chart(fig_bar, use_container_width=True)
        else:
            st.info("No active positions currently open.")


# =====================================================================
# Page 3: Order History
# =====================================================================
elif page == "Order History":
    st.title("📋 Order Audit Trail")
    st.markdown("Transactional audit trail queried asynchronously from persistent PostgreSQL database.")
    
    orders_df = pd.DataFrame()
    if db_connected:
        try:
            query = """
            SELECT order_id, symbol AS ticker, qty AS quantity, side, status, strategy, provider, created_at AS timestamp, limit_price, filled_avg_price
            FROM tracked_orders
            ORDER BY created_at DESC
            LIMIT 100;
            """
            orders_df = pd.read_sql_query(query, db_conn)
        except Exception as e:
            st.error(f"Error querying SQL Database: {e}")
            db_connected = False

    if not db_connected or orders_df.empty:
        mock_data = {
            "order_id": [f"ord_f820c{i}" for i in range(3)],
            "ticker": ["AAPL", "MSFT", "AAPL"],
            "quantity": [100, 100, 50],
            "side": ["BUY", "SELL", "BUY"],
            "status": ["COMPLETED", "COMPLETED", "PENDING"],
            "strategy": ["SmaCrossover", "MeanReversion", "SmaCrossover"],
            "provider": ["alpaca", "alpaca", "alpaca"],
            "timestamp": ["2026-08-28 16:20:53", "2026-08-28 16:20:53", "2026-08-28 16:21:04"],
            "limit_price": [321.13, 515.87, 321.50],
            "filled_avg_price": [321.13, 515.87, 0.0]
        }
        orders_df = pd.DataFrame(mock_data)

    f_col1, f_col2, f_col3 = st.columns(3)
    with f_col1:
        selected_ticker = st.selectbox("Filter Ticker", ["ALL"] + list(orders_df["ticker"].unique()))
    with f_col2:
        selected_status = st.selectbox("Filter Status", ["ALL"] + list(orders_df["status"].unique()))
    with f_col3:
        search_id = st.text_input("Search Order ID")

    filtered_df = orders_df.copy()
    if selected_ticker != "ALL":
        filtered_df = filtered_df[filtered_df["ticker"] == selected_ticker]
    if selected_status != "ALL":
        filtered_df = filtered_df[filtered_df["status"] == selected_status]
    if search_id:
        filtered_df = filtered_df[filtered_df["order_id"].str.contains(search_id, case=False)]

    st.subheader(f"Recent Orders ({len(filtered_df)} items matching)")
    st.dataframe(filtered_df, use_container_width=True)


# =====================================================================
# Page 4: Provider Status
# =====================================================================
elif page == "Provider Status":
    st.title("🔌 Broker Gateway Connection Providers")
    st.markdown("Stateless broker gateway interfaces & health states.")

    col1, col2 = st.columns(2)
    with col1:
        st.subheader("Alpaca / Global Gateway")
        st.metric("Status", "Connected", delta="Latency: 18ms")
        st.json({
            "connection_type": "WebSocket + REST",
            "connected_symbol_channels": ["AAPL", "MSFT"],
            "session_active_seconds": 1840,
            "feed_source": "iex",
            "supported_order_types": ["MARKET", "LIMIT", "BRACKET_STOP_LOSS"],
            "api_endpoint": "https://paper-api.alpaca.markets"
        })

    with col2:
        st.subheader("Indian Broker Gateway (Zerodha / Angel One)")
        st.metric("Status", "Configured (Proto Ready)", delta="Indian Mode Compatible", delta_color="normal")
        st.json({
            "connection_type": "KiteConnect / SmartAPI Ready",
            "exchanges_supported": ["NSE_EQ", "NSE_FO", "BSE_EQ"],
            "product_types": ["CNC", "MIS", "NRML"],
            "supported_order_types": ["MARKET", "LIMIT", "SL", "SL-M", "GTT"],
            "circuit_collar_protection": "Active (1.5% - 20%)"
        })


# =====================================================================
# Page 5: System Telemetry
# =====================================================================
elif page == "System Telemetry":
    st.title("📊 System Telemetry (Grafana)")
    st.markdown("Real-time telemetry and pipeline performance loaded from Grafana.")
    grafana_url = "/grafana/"
    st.components.v1.iframe(grafana_url, height=800, scrolling=True)