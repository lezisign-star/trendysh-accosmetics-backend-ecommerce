#!/bin/bash
# Read-only checks before adding the NBH photo stack to the shared Contabo server. Changes nothing.
echo "== time"; date -u; uptime
echo; echo "== memory (MB)"; free -m | head -3
echo; echo "== disk"; df -h / | tail -1
echo; echo "== containers"; docker ps --format '{{.Names}}|{{.Status}}|{{.Image}}' | sort
echo; echo "== memory use per container"; docker stats --no-stream --format '{{.Name}}|{{.MemUsage}}|{{.CPUPerc}}' | sort
echo; echo "== docker networks"; docker network ls --format '{{.Name}}|{{.Driver}}' | sort
echo; echo "== /opt"; ls -la /opt
echo; echo "== compose"; docker compose version
echo; echo "== listening ports (host)"; ss -ltnH | awk '{print $4}' | sort -u | head -30
echo; echo "== proxy"; docker inspect coolify-proxy --format 'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restarts={{.RestartCount}} started={{.State.StartedAt}}' 2>&1
echo; echo "== NBH leftovers (expect none)"
docker ps -a --filter label=com.nbh.project --format '{{.Names}} {{.Status}}'
docker network ls --filter name=nbh --format '{{.Name}}'
ls -la /opt/nbh 2>&1 | head -5
echo; echo "== DNS of the photo hosts as the server sees them"
for h in img.trendyshaccosmetic.com s3.trendyshaccosmetic.com; do printf '%s -> ' "$h"; getent hosts "$h" | awk '{print $1}' | head -1; echo; done
