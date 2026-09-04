# Double-Loop Deployment & Approval Strategy (`remote-docker-gate`)

This rule governs all interactions when communicating with or managing remote projects via the `remote-docker-gate` MCP server.

## 1. Double-Loop Deployment Strategy

When developing, debugging, or deploying applications using `remote-docker-gate`, you MUST follow the double-loop lifecycle:

### Phase 1: Inner Loop (Fast Development & Debugging)
* **Goal:** Sub-second iteration and debugging without cluttering Git history.
* **Workflow:**
  1. Make necessary code changes locally in the IDE workspace.
  2. Use `sync_project_files(project_name=..., files=[...])` to send modified files directly to the remote project directory.
  3. Use `deploy_compose_stack(project_name=...)` or diagnostic tools (`get_container_logs`, `exec_command_in_container`, `list_running_containers`) to verify the fix.
  4. Repeat as needed during the active debugging session.
  5. DO NOT create premature Git commits during Phase 1.

### Phase 2: Outer Loop (Finalization & Git Synchronization)
* **Goal:** 100% alignment between local Git, remote Git repository, and running Docker containers.
* **Workflow:**
  1. Once the feature/bugfix is verified and working, commit and push local changes to the remote Git origin:
     - `git add .`
     - `git commit -m "<clean, descriptive commit message>"`
     - `git push origin <branch>`
  2. Call the atomic tool `git_sync_and_deploy(project_name=..., branch=<branch>)` on the remote MCP server. This force-checkouts/resets the remote filesystem to `origin/<branch>`, cleans untracked debug files, and redeploys the compose stack strictly from the clean on-disk `docker-compose.yml` file.
  3. Monitor container status and logs (`list_running_containers`, `get_container_logs`) to verify final production alignment.

---

## 2. Deployment Approval Governance

### Mandatory Explicit Approval for NEW Deployments
* **Rule:** Before creating a **NEW project deployment** or deploying a project/container that is NOT returned by `list_whitelisted_containers()`, you MUST explicitly prompt the user and obtain their direct permission before executing the deployment command.
* **Requirement:** State the project name, image/compose configuration, target remote host, and purpose before requesting approval.

### Exemption for Whitelisted In-Prompt Test Steps
* **Exemption:** For existing projects and containers already registered and returned by `list_whitelisted_containers()`, you DO NOT need to prompt for permission for routine `sync_project_files` or `deploy_compose_stack` test steps during an active inner-loop debugging session.
