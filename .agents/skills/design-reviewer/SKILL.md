---
name: "design-reviewer"
description: "Reviews architecture designs (HLD/LLD) for structural consistency, scalability, rate-limiting rules, database indices, and potential race conditions."
---
# Design Reviewer Skill

## Instructions
1. Inspect design files and diagrams for architectural gaps (e.g., duplicate connections, stale cache states, race conditions).
2. Review compliance with rules such as stateless gateways, transaction boundaries, and idempotency.
3. Output clear, constructive feedback logs detailing pros/cons and mitigation workarounds.
