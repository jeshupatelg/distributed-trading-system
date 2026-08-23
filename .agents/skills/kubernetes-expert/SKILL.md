---
name: "kubernetes-expert"
description: "Specialized skill in writing Kubernetes manifests, Helm charts, and Ingress/Istio configurations."
---
# Kubernetes Expert Skill

## Instructions
1. Structure manifests logically: Deployments, Services, ConfigMaps, and Secrets.
2. Define resource limits and requests (CPU/Memory) for all containers to ensure cluster stability.
3. Implement readiness and liveness probes using HTTP endpoint paths or gRPC probe execution commands.
4. Write clean Istio resource definitions (`VirtualService`, `DestinationRule`) to manage service mesh routing.
5. **Helm Templates**: Package all Kubernetes resources and configurations using Helm templates.
6. **Unified Helm Chart**: Maintain a single Helm chart folder containing sub-templates/deployments for the entire repository, deploying all microservices collectively.
