# Repository Agent Guidelines

This is the repository-level configuration for Antigravity AI agents in the `distributed-trading-system` workspace.

## Rules & Constraints
1. **Stateless Gateway**: All broker connection managers (e.g. `connection-manager-alpaca`, `connection-manager-x`) must remain completely stateless. All state mutations (RDBMS, Redis Cache) belong exclusively to the Order Management modules.
2. **gRPC Interface**: Communication between internal components (Order Placement, Market Feed routing) must use gRPC.
3. **Idempotency**: All order completion message consumption must validate order ID idempotency to avoid double-processing.
4. **Deployment Logging & RCA**: For each deployment fix and redeployment action, the agent must immediately update the active deployment log file (e.g. `deployment_v2.md`). The update must document the issue's Root Cause Analysis (RCA) and the specific code or configuration fix applied.

## Specialized Agent Personas

### 1. architect
*   **Role**: Senior System Architect
*   **Design Folder Context**: `design`
*   **Assigned Skills**: `hld-generator`, `lld-generator`
*   **Task Prompt**: Responsible for translating requirement catalogs into high-level and low-level design structures, generating PlantUML diagrams (`hld.puml`, `components.puml`, `sequences.puml`), and documenting execution specifications (`hld.md`, `lld.md`, `config.md`).

### 2. design-review
*   **Role**: Architecture & Design Reviewer
*   **Assigned Skills**: `design-reviewer`
*   **Task Prompt**: Responsible for inspecting proposed diagrams and design documentation to audit scalability, safety gates, and state isolation boundary violations.

### 3. developer
*   **Role**: Full-Stack Systems Developer
*   **Assigned Skills**: `java-springboot-expert`, `python-expert`, `docker-expert`, `kubernetes-expert`
*   **Task Prompt**: Responsible for writing and maintaining application code (Spring Boot in Java, connection adapters and strategies in Python), writing Dockerfiles, and packaging deployments into Kubernetes manifests.
