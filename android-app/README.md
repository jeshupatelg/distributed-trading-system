# Trading System - Android Application

This directory hosts the Android client for the Distributed Trading System.

## Primary Capabilities
1. **Risk Control & Emergency Center**:
   - One-tap global and per-provider Emergency Kill Switch (`/api/v1/risk/kill-switch/trigger`, `/api/v1/risk/kill-switch/reset`).
   - Live telemetry on drawdown, cash balance, and risk gate limits (`/api/v1/risk/status`, `/api/v1/risk/config`).
2. **Order Lifecycle & Execution Monitor**:
   - Real-time updates for order creations, fills, rejections, and cancellations.
3. **Instant Push Alerts**:
   - Push notifications for order rejections, risk violations, and fill alerts via Notification Service (`ntfy` / FCM).
4. **Market & Portfolio Watch**:
   - Real-time symbol pricing and active position exposure.

## Backend Endpoints Reference
- **Order Processing Service (OPS)**: `http://<host>:8081`
  - `GET  /api/v1/risk/status?provider={name}`
  - `GET  /api/v1/risk/config?provider={name}`
  - `POST /api/v1/risk/config?provider={name}`
  - `POST /api/v1/risk/kill-switch/trigger?liquidate={true|false}&provider={name}`
  - `POST /api/v1/risk/kill-switch/reset?provider={name}`
- **Notification Service**: `http://<host>:8085`
  - `GET  /api/v1/notify/status`
  - `POST /api/v1/notify/test`
- **Price Cache Service**: `http://<host>:8084`
  - `GET  /health`
- **Alpaca Connection Gateway**: `http://<host>:8000`
  - `GET  /health`
