---
name: "hld-generator"
description: "Generates high-level architecture designs, system component layouts, and data pipeline schemas."
---
# HLD Generator Skill

## Instructions
1. Read requirement documents or system context inputs.
2. Design high-level component diagrams using PlantUML (`hld.puml`).
3. Document component roles, responsibilities, and network protocols in `hld.md`.
4. Group logical boundaries and distinguish database, cache, message broker, and gateway interfaces.
5. Document any new architectural decisions as an Architecture Decision Record (ADR) under the folder `.agents/adr/` using standard ADR markdown format.
6. **Downstream Shift Synchronization**: When triggered by the `lld-generator` to sync a downstream development change, you must verify the existence of an approved Architecture Decision Record (ADR) in `.agents/adr/` that specifies and justifies the shift. Do not update the HLD specifications without a corresponding verified ADR.

