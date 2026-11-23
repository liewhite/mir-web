#!/bin/bash
# 部署/更新到 kaojiang：同步页面和素材、服务端代码，重启服务。首次部署加 --init。
set -euo pipefail
cd "$(dirname "$0")/.."
H=kaojiang
if [[ "${1:-}" == "--init" ]]; then
  scp deploy/node.tar.xz deploy/mir176.env deploy/mir176.service deploy/k8s.yaml $H:/tmp/
  ssh $H bash -s <<'R'
set -e
sudo id mir176 >/dev/null 2>&1 || sudo useradd --system --no-create-home --shell /usr/sbin/nologin mir176
sudo mkdir -p /opt/mir176/node /opt/mir176/server /opt/mir176/public /var/lib/mir176
sudo tar -xJf /tmp/node.tar.xz -C /opt/mir176/node --strip-components=1
sudo install -m 600 -o root -g root /tmp/mir176.env /etc/mir176.env
sudo install -m 644 /tmp/mir176.service /etc/systemd/system/mir176.service
sudo chown -R admin:admin /opt/mir176/public /opt/mir176/server
sudo chown mir176:mir176 /var/lib/mir176 && sudo chmod 700 /var/lib/mir176
rm -f /tmp/node.tar.xz /tmp/mir176.env /tmp/mir176.service
R
fi
rsync -az --delete --exclude 'app.js.map' public/ $H:/opt/mir176/public/
rsync -az server/server.mjs $H:/opt/mir176/server/
ssh $H bash -s <<'R'
set -e
sudo systemctl daemon-reload
sudo systemctl enable mir176 >/dev/null 2>&1
sudo systemctl restart mir176
sleep 1
systemctl is-active mir176
curl -s -m 5 http://10.42.0.1:18176/healthz
R
if [[ "${1:-}" == "--init" ]]; then
  ssh $H 'sudo k3s kubectl apply -f /tmp/k8s.yaml && rm -f /tmp/k8s.yaml'
fi
