---
name: "lld-generator"
description: "Generates class diagrams, internal component interfaces, data structures, and sequence flows for specific microservices."
---
# LLD Generator Skill

## Instructions
1. Review the target high-level design (`hld.md` / `hld.puml`).
2. Write detailed component diagrams showing internal classes and interfaces.
3. Generate sequence diagrams mapping out all major execution/data paths.
4. Design config validation schemas and document environment properties.
5. Document any low-level design decisions as an Architecture Decision Record (ADR) under the folder `.agents/adr/` using standard ADR markdown format.
6. **HLD Synchronization**: If downstream development or low-level design details diverge from the High-Level Design (HLD) (e.g., changes to component state, cache boundaries, or interface flows), you must trigger the `hld-generator` skill to update the HLD. An ADR justifying and recording this design shift must be created under `.agents/adr/` prior to triggering the HLD update.

