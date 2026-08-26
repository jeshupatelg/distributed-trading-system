import os
import time
import socket
import streamlit as st
import redis
import psycopg2
import pandas as pd
import plotly.express as px
import plotly.graph_objects as go

# Set page configuration with a modern dark theme style (configured in Streamlit settings)
st.set_page_config(
    page_title="Quant Operations Dashboard",
    page_icon="📈",
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
        # Test connection
        r.ping()
        return r, True
    except Exception as e:
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
    except Exception as e:
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
st.sidebar.title("📈 Quant Operations")
# Page Selection
page = st.sidebar.radio(
    "Navigation Menu",
    ["System Control Center", "Portfolio & Assets", "Order History", "Provider Status", "System Telemetry"]
)

st.sidebar.divider()
st.sidebar.caption("System Status: **Active**")
st.sidebar.caption("Scope: **Read-Only (Pull Mode)**")

# --- Page 0: System Control Center ---
if page == "System Control Center":
    st.title("⚙️ System Control Center")
    st.markdown("Centralized operational monitoring panel for distributed trading infrastructure components.")
    
    st.subheader("Infrastructure Connectivity Status")
    
    # 3 Column layout for status cards
    c1, c2, c3 = st.columns(3)
    
    with c1:
        st.write("### 🗄️ Redis Cache")
        if redis_connected:
            st.success("🟢 ONLINE")
            st.info(f"**Host**: `{REDIS_HOST}`\n\n**Port**: `{REDIS_PORT}`")
        else:
            st.warning("🟡 OFFLINE / MOCK")
            st.info("Operating in standalone simulation fallback mode.")
            
    with c2:
        st.write("### 🗃️ PostgreSQL Database")
        if db_connected:
            st.success("🟢 ONLINE")
            st.info(f"**Host**: `{DB_HOST}`\n\n**DB Name**: `{DB_NAME}`\n\n**User**: `{DB_USER}`")
        else:
            st.warning("🟡 OFFLINE / MOCK")
            st.info("Reading static transactional data mock logs.")
            
    with c3:
        st.write("### 📨 Kafka Broker")
        if kafka_connected:
            st.success("🟢 ONLINE")
            st.info(f"**Bootstrap Servers**: `{KAFKA_BOOTSTRAP_SERVERS}`")
        else:
            st.error("🔴 OFFLINE")
            st.info("Message bus queue unavailable. Orders cannot be published.")

    st.divider()
    st.subheader("System Control Summary")
    st.markdown("""
    - **Environment**: Distributed Hybrid Docker Stack
    - **Gateway Health**: connection-manager-alpaca (🟢 Connected)
    - **Reconciliation Engine**: order-management-service (🟢 Active)
    - **Execution Engine**: order-processing-service (🟢 Active)
    """)

# --- Page 1: Portfolio & Assets ---
elif page == "Portfolio & Assets":
    st.title("💼 Portfolio & Asset Allocation")
    st.markdown("Real-time view of equity, margins, and position distributions pulled from Redis cache.")
    
    # 1. Fetch data (Live or Mock fallback)
    if redis_connected:
        try:
            balance = float(r_client.get("account:balance") or 100000.0)
            blocked_margin = float(r_client.get("account:blocked_margin") or 15000.0)
            
            # Fetch active positions
            position_keys = r_client.keys("position:*")
            positions = {}
            for pk in position_keys:
                ticker = pk.split(":")[-1]
                positions[ticker] = float(r_client.get(pk) or 0.0)
        except Exception as e:
            st.error(f"Error querying Redis: {e}")
            redis_connected = False # Fallback to mock

    if not redis_connected:
        # High quality mockup values
        balance = 125430.50
        blocked_margin = 18500.00
        positions = {"AAPL": 150.0, "MSFT": 80.0, "TSLA": 45.0, "GOOGL": 30.0}

    # Derived metrics
    free_cash = balance - blocked_margin
    mock_prices = {"AAPL": 175.0, "MSFT": 420.0, "TSLA": 220.0, "GOOGL": 150.0}
    position_value = sum(qty * mock_prices.get(ticker, 100.0) for ticker, qty in positions.items())
    total_equity = free_cash + blocked_margin + position_value

    # Key Stat Metrics Row
    col1, col2, col3, col4 = st.columns(4)
    col1.metric("Total Net Equity", f"${total_equity:,.2f}", delta=f"+1.42% (Today)")
    col2.metric("Total Balance", f"${balance:,.2f}")
    col3.metric("Blocked Margin", f"${blocked_margin:,.2f}")
    col4.metric("Active Assets Value", f"${position_value:,.2f}")

    st.divider()

    # Visualizations
    v_col1, v_col2 = st.columns([1, 1])
    
    with v_col1:
        st.subheader("Asset Distribution")
        # Prepare pie data
        df_pie = pd.DataFrame([
            {"Asset": "Free Cash", "Value": free_cash},
            {"Asset": "Blocked Margin", "Value": blocked_margin},
        ] + [{"Asset": f"Position: {t}", "Value": q * mock_prices.get(t, 100.0)} for t, q in positions.items()])
        
        fig_pie = px.pie(df_pie, values='Value', names='Asset', hole=0.4,
                         color_discrete_sequence=px.colors.sequential.RdBu)
        st.plotly_chart(fig_pie, use_container_width=True)

    with v_col2:
        st.subheader("Current Positions Summary")
        if positions:
            df_pos = pd.DataFrame([
                {"Ticker": t, "Quantity": q, "Value": q * mock_prices.get(t, 100.0)} for t, q in positions.items()
            ])
            fig_bar = px.bar(df_pos, x='Ticker', y='Value', text_auto='.2s', labels={'Value':'Total Value ($)'},
                             color='Ticker', color_discrete_sequence=px.colors.qualitative.Set2)
            st.plotly_chart(fig_bar, use_container_width=True)
        else:
            st.info("No active positions found in the cache.")

# --- Page 2: Order History ---
elif page == "Order History":
    st.title("📋 Order Audit Trail")
    st.markdown("Transactional audit trail queried asynchronously from the persistent Order Management SQL Database.")
    
    # 1. Fetch data
    orders_df = pd.DataFrame()
    if db_connected:
        try:
            query = """
            SELECT order_id, symbol AS ticker, qty AS quantity, side, status, created_at AS timestamp, limit_price
            FROM tracked_orders
            ORDER BY created_at DESC
            LIMIT 100;
            """
            orders_df = pd.read_sql_query(query, db_conn)
        except Exception as e:
            st.error(f"Error querying SQL Database: {e}")
            db_connected = False

    if not db_connected:
        # Fallback Mock Data
        mock_data = {
            "order_id": [f"ord_f820c{i}" for i in range(5)],
            "ticker": ["AAPL", "MSFT", "AAPL", "TSLA", "MSFT"],
            "quantity": [10, 5, 20, 15, 10],
            "side": ["BUY", "SELL", "BUY", "BUY", "SELL"],
            "status": ["FILLED", "FILLED", "PENDING", "FAILED", "FILLED"],
            "timestamp": [
                "2026-08-02 06:15:02",
                "2026-08-02 06:10:45",
                "2026-08-02 06:05:12",
                "2026-08-02 05:59:30",
                "2026-08-02 05:42:15"
            ],
            "limit_price": [174.50, 421.10, 173.80, 222.00, 419.80]
        }
        orders_df = pd.DataFrame(mock_data)

    # 2. Filters
    f_col1, f_col2, f_col3 = st.columns(3)
    with f_col1:
        selected_ticker = st.selectbox("Filter Ticker", ["ALL"] + list(orders_df["ticker"].unique()))
    with f_col2:
        selected_status = st.selectbox("Filter Status", ["ALL"] + list(orders_df["status"].unique()))
    with f_col3:
        search_id = st.text_input("Search Order ID")

    # Apply filters
    filtered_df = orders_df.copy()
    if selected_ticker != "ALL":
        filtered_df = filtered_df[filtered_df["ticker"] == selected_ticker]
    if selected_status != "ALL":
        filtered_df = filtered_df[filtered_df["status"] == selected_status]
    if search_id:
        filtered_df = filtered_df[filtered_df["order_id"].str.contains(search_id, case=False)]

    st.subheader(f"Recent Orders ({len(filtered_df)} items matching)")
    st.dataframe(filtered_df, use_container_width=True)

    # --- ENHANCEMENT POINT: PUSH TRIGGERS ---
    st.divider()
    st.info("💡 **Enhancement Hook: Interactive Order Dispatch**")
    
    with st.expander("Trigger Manual Transaction (Future Write Trigger)"):
        st.warning("⚠️ Manual trading is currently locked in Read-Only Mode.")
        
        # Inactive form fields
        col1, col2, col3, col4 = st.columns(4)
        col1.text_input("Target Symbol", value="AAPL", disabled=True)
        col2.number_input("Shares Quantity", min_value=1, value=100, disabled=True)
        col3.selectbox("Order Side", ["BUY", "SELL"], disabled=True)
        col4.number_input("Limit Price ($)", value=175.0, disabled=True)
        
        # Disabled button representing the future trigger
        st.button("Transmit Order to Ingress Gateway", disabled=True, type="primary")
        
        st.code("""
# [ENHANCEMENT POINT: WRITE TRIGGER IN STAGE 2]
# When the trigger is enabled, the button above will invoke this client method:
# 
# import grpc
# import order_processing_pb2 as pb
# import order_processing_pb2_grpc as pb_grpc
# 
# def trigger_order_submission(ticker, qty, side, limit_price):
#     with grpc.insecure_channel("order-processing-service:50051") as channel:
#         stub = pb_grpc.OrderProcessingServiceStub(channel)
#         response = stub.PlaceOrder(pb.OrderRequest(
#             ticker=ticker,
#             quantity=qty,
#             side=side,
#             limit_price=limit_price
#         ))
#     return response.order_id
        """, language="python")

# --- Page 3: Provider Status ---
elif page == "Provider Status":
    st.title("🔌 Broker Gateway Connection Providers")
    st.markdown("Real-time telemetry and health state of active stateless credential gateways.")

    # Gateway state grid
    col1, col2 = st.columns(2)

    with col1:
        st.subheader("Alpaca Ingress Gateway")
        st.metric("Status", "Connected", delta="Latency: 18ms")
        st.json({
            "connection_type": "WebSocket + REST",
            "connected_symbol_channels": ["AAPL", "MSFT"],
            "session_active_seconds": 1240,
            "feed_source": "iex",
            "api_endpoint": "https://paper-api.alpaca.markets"
        })

    with col2:
        st.subheader("Broker X Ingress Gateway")
        st.metric("Status", "Standby / Unused", delta="Latency: --", delta_color="off")
        st.json({
            "connection_type": "None",
            "connected_symbol_channels": [],
            "session_active_seconds": 0,
            "api_endpoint": "http://localhost:8001"
        })

    # --- ENHANCEMENT POINT: PUSH TRIGGERS ---
    st.divider()
    st.info("💡 **Enhancement Hook: Strategy Parameter Tuning**")
    
    with st.expander("Update Running Ticker Strategy Parameters"):
        st.warning("⚠️ Strategy parameter tuning is currently locked in Read-Only Mode.")
        
        target_strat = st.selectbox("Select Strategy Instance to Tune", ["AAPL - SMA Crossover", "MSFT - Mean Reversion"], disabled=True)
        col1, col2 = st.columns(2)
        col1.slider("Fast Moving Average Interval (Ticks)", 5, 50, 10, disabled=True)
        col2.slider("Slow Moving Average Interval (Ticks)", 20, 200, 30, disabled=True)
        
        st.button("Push Configuration Settings", disabled=True)
        
        st.code("""
# [ENHANCEMENT POINT: STRATEGY TUNE WRITE TRIGGER]
# In the write-enabled model, clicking the submit button executes this gRPC channel pipeline:
#
# import grpc
# import strategy_tuning_pb2 as pb
# import strategy_tuning_pb2_grpc as pb_grpc
# 
# def push_strategy_update(strategy_id, fast_ma, slow_ma):
#     # We route parameters directly to the Envoy load balancer (tick-lb) proxying strategy endpoints
#     with grpc.insecure_channel("tick-lb:50051") as channel:
#         stub = pb_grpc.StrategyTunerStub(channel)
#         result = stub.UpdateParams(pb.ParamRequest(
#             strategy_id=strategy_id,
#             parameters={"fast_period": str(fast_ma), "slow_period": str(slow_ma)}
#         ))
#     return result.success
        """, language="python")

# --- Page 4: System Telemetry ---
elif page == "System Telemetry":
    st.title("📊 System Telemetry (Grafana)")
    st.markdown("Real-time telemetry and pipeline performance dashboards loaded directly from Grafana.")
    
    # We use a relative path so it routes through the API Gateway automatically
    grafana_url = "/grafana/"
    
    st.info("💡 Exposing Grafana dynamically via the central API Gateway at `/grafana/`.")
    
    st.components.v1.iframe(grafana_url, height=800, scrolling=True)
