---
name: double-loop-deployment
description: Guidelines and procedure for managing fast inner-loop file sync deployments and outer-loop Git reconciliation with monitored redeployments on remote Docker hosts.
---

# Double-Loop Deployment Skill

This skill provides step-by-step instructions for executing the **Double-Loop Deployment Strategy** when developing, debugging, and finalizing applications running on remote servers managed by `remote-docker-gate`.

## Overview of Strategy

```
+-----------------------------------------------------------------------+
| PHASE 1: INNER LOOP (Active Development & Fast Debugging)            |
| Local Code Edit ---> sync_project_files ---> deploy_compose_stack     |
|                      (Sub-second update, no Git commits)             |
+-----------------------------------------------------------------------+
                                  |
                                  | (Fix Verified & Successful)
                                  v
+-----------------------------------------------------------------------+
| PHASE 2: OUTER LOOP (Final Reconciliation & Production Sync)          |
| Local git commit + push ---> git_sync_and_deploy                      |
|                              (Git reset + clean + Monitored Redeploy) |
+-----------------------------------------------------------------------+
```

---

## Phase 1: Inner Loop (Fast Development & Debugging)

Use Phase 1 during active pair-programming and troubleshooting sessions.

### Step 1: Make Local Code Edits
Modify code files locally in your IDE workspace. Do NOT commit the changes to Git yet.

### Step 2: Sync Files to Remote Host
Call `sync_project_files` with the logical `project_name`:
```python
sync_project_files(
    project_name="my-app",
    files=[
        {"rel_path": "src/server.js", "content": "..."},
        {"rel_path": "config/settings.json", "content": "..."}
    ]
)
```

### Step 3: Fast Redeploy & Diagnostics
Trigger a fast redeployment and inspect logs:
```python
deploy_compose_stack(project_name="my-app")
get_container_logs(container_name="my-app-web", tail_lines=50)
```
Repeat Steps 1-3 as needed.

---

## Phase 2: Outer Loop (Finalization & Git Synchronization)

Execute Phase 2 only after the feature or bug fix has been tested and verified in Phase 1.

### Step 1: Commit and Push Local Changes
Commit and push the verified changes to the remote Git origin repository from your local environment:
```bash
git add .
git commit -m "fix(server): resolve connection timeout in web adapter"
git push origin main
```

### Step 2: Atomic Git Reconciliation & Clean Redeployment
Call `git_sync_and_deploy` on the remote MCP server. This tool performs the git fetch/force-checkout/reset-hard/clean sequence, and then automatically redeploys the compose stack strictly from the clean on-disk `docker-compose.yml` file (rejecting any custom/in-memory file overrides):
```python
git_sync_and_deploy(project_name="my-app", branch="main")
list_running_containers()
```

---

## Approval Governance Rules

1. **New Projects / Containers:** If deploying a **NEW project or container** that is NOT returned by `list_whitelisted_containers()`, you MUST request explicit user approval before calling deployment tools.
2. **Existing Whitelisted Projects:** For existing projects and containers already returned by `list_whitelisted_containers()`, inner-loop file syncs and test redeployments during active debugging are pre-authorized and exempt from extra approval prompts.
