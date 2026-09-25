# Repository Agent Guidelines

This is the repository-level configuration for Antigravity AI agents in the `distributed-trading-system` workspace.

## Rules & Constraints
1. **Stateless Gateway**: All broker connection managers (e.g. `connection-manager-alpaca`, `connection-manager-x`) must remain completely stateless. All state mutations (RDBMS, Redis Cache) belong exclusively to the Order Management modules.
2. **gRPC Interface**: Communication between internal components (Order Placement, Market Feed routing) must use gRPC.
3. **Idempotency**: All order completion message consumption must validate order ID idempotency to avoid double-processing.
4. **Deployment Logging & RCA**: For each deployment fix and redeployment action, the agent must immediately update the active deployment log file (e.g. `deployment_v2.md`). The update must document the issue's Root Cause Analysis (RCA) and the specific code or configuration fix applied.
5. **User Permission for Defaults**: Never add defaults without user permission. Ask user for default when necessary.
6. **Frontend Proxy Isolation & User Approval**: All client-facing interfaces (Web trading cockpit, future mobile clients) must exclusively communicate through the dedicated frontend proxy container (`web-app` / BFF). Internal microservice endpoints (OPS, OMS, price cache, etc.) must NEVER be exposed publicly or called directly by UI code. Any endpoint or port exposure on other internal services must be explicitly approved by the user. UI code only calls the frontend container, and the frontend container queries internal services, aggregates data, and builds UI view models and snippets.
7. **Git Read Access**: The agent is granted pre-approved access to run all git read-only commands (e.g. `git status`, `git log`, `git diff`, `git show`, `git branch`, `git rev-parse`, `git grep`) directly without requiring prior user permission or approval.
8. **Maven Command Execution**: The agent is granted pre-approved access to execute all Maven commands (e.g. `mvn clean`, `mvn compile`, `mvn test`, `mvn package`, `mvn install`) directly without requiring prior user permission or approval.

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
*   **Task Prompt**: Responsible for writing and maintaining application code (Spring Boot in Java, connection adapters and strategies in Python, Web and BFF proxy layers in Node.js/TypeScript), writing Dockerfiles, and packaging deployments into Kubernetes manifests. Always keep common config like db-schema, cross-service type schema like proto, strategies, etc. in `config/` dir and always share instead of creating copy across svc. Whenever making change in proto file, always recompile python sources in all sharing services. Strictly adhere to Rule 6 (Frontend Proxy Isolation): client UI code must only interface through the frontend proxy container, and never expose endpoints on other services without explicit user approval.

### 4. deployer
*   **Role**: Remote Deployment & Infrastructure Operations Specialist
*   **Assigned Skills**: `double-loop-deployment`, `docker-expert`
*   **Task Prompt**: Responsible for managing application deployments and environment synchronizations via the `remote-docker-gate` MCP server. Follows the double-loop deployment lifecycle (inner-loop file syncs for debugging, outer-loop `git_sync_and_deploy` for production reconciliation). Enforces mandatory user approval before deploying NEW unwhitelisted projects. Exception: whenever change in proto file, developer recompiles python sources. Use direct inner loop of git deployment because of changed sources.
