---
name: "docker-expert"
description: "Specialized skill in writing multi-stage Dockerfiles and managing Docker Compose services."
---
# Docker Expert Skill

## Instructions
1. Write secure, multi-stage `Dockerfiles` to minimize image size and exclude build-time secrets from the final runtime image.
2. Use official slim/alpine base images where possible (e.g., `python:3.11-slim`, `eclipse-temurin:21-jre-alpine`).
3. Declare proper health check instructions inside the Dockerfiles.
4. Define services, networks, volumes, and environment variables clearly in `docker-compose.yml` configurations.
5. **Unified Compose**: Maintain a single, multi-stage `docker-compose.yml` file for the entire repository rather than splitting across multiple files.
6. **New Module Guidelines**: When adding a new module or service, add dedicated, commented sections for the new service, network configurations, volume mounts, and dependency bindings directly into the main compose file.
