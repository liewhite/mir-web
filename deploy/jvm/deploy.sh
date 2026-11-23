#!/bin/bash
# Scala 多人版部署到 kaojiang（#1154；从 Node 单机版切过来在 #1148 做）。
#
#   deploy/jvm/deploy.sh            打包 jar、同步页面和素材、换上 JVM 版的 systemd 单元并重启
#   deploy/jvm/deploy.sh --dry-run  只在本机打包，不连服务器
#
# 只动 mir176 自己的东西：/opt/mir176、/etc/mir176.env、/etc/systemd/system/mir176.service、/var/lib/mir176。
# Ingress 不变（k8s.yaml 指向 10.42.0.1:18176，WebSocket 走同一个 Ingress）。
# 服务器上：
#   /opt/mir176/jre                 JRE 21（第一次从本机上传）
#   /opt/mir176/app/mir176.jar      服务端
#   /opt/mir176/web/                客户端页面（scala/client/web）
#   /opt/mir176/public/assets/      素材（和 Node 版共用）
#   /var/lib/mir176/mir.db          账号和角色；backup/ 每天一份留 30 天
#   /opt/mir176/bin/mir176ctl       命令行：mir176ctl account add <账号> 等
set -euo pipefail
cd "$(dirname "$0")/../.."
H=kaojiang
JRE=deploy/jvm/jre.tar.gz

# 只从 master 部署：在 worktree 分支上跑会把没合并的代码推上线，盖掉别人已合进 master 的改动。
BR=$(git rev-parse --abbrev-ref HEAD)
[[ $BR == master || ${1:-} == --dry-run ]] || { echo "当前分支是 $BR：先合进 master，再在主仓库 mir/ 里部署"; exit 1; }
# 几个会话可能同时部署：排队，一个打包上传完下一个再从最新 master 打包（不排队的话后打包的可能缺先合并的提交）。
LOCK=/tmp/mir176-deploy.lock
exec 9>"$LOCK"
/opt/homebrew/bin/flock -w 1800 9 || { echo "等别的部署超过 30 分钟，先看 pgrep -fl deploy/jvm/deploy.sh"; exit 1; }
echo "== 部署 $(git log --oneline -1)"

echo "== 打包"
(cd scala && JAVA_HOME=/opt/homebrew/opt/openjdk@21 sbt -batch server/assembly client/fullLinkJS)
JAR=scala/server/target/scala-3.10.1/mir176.jar
test -f "$JAR"
test -f scala/client/web/index.html && test -f scala/client/web/js/main.js || { echo "缺客户端页面 scala/client/web/"; exit 1; }
if [[ ! -f $JRE ]]; then
  echo "== 下载 JRE 21（linux x64）"
  curl -fL --retry 5 -A mir176-deploy -o $JRE.tmp 'https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse'
  mv $JRE.tmp $JRE
fi
[[ "${1:-}" == "--dry-run" ]] && { ls -la "$JAR" $JRE; exit 0; }

echo "== 上传"
ssh $H 'sudo mkdir -p /opt/mir176/app /opt/mir176/web /opt/mir176/bin /opt/mir176/public && sudo chown -R admin:admin /opt/mir176/app /opt/mir176/web /opt/mir176/bin /opt/mir176/public'
if ! ssh $H 'test -x /opt/mir176/jre/bin/java'; then
  scp $JRE $H:/tmp/mir176-jre.tar.gz
  ssh $H 'sudo mkdir -p /opt/mir176/jre && sudo tar -xzf /tmp/mir176-jre.tar.gz -C /opt/mir176/jre --strip-components=1 && rm -f /tmp/mir176-jre.tar.gz'
fi
rsync -az "$JAR" $H:/opt/mir176/app/mir176.jar
rsync -az --delete scala/client/web/ $H:/opt/mir176/web/
rsync -az --delete public/assets/ $H:/opt/mir176/public/assets/
rsync -az deploy/jvm/mir176ctl $H:/opt/mir176/bin/mir176ctl
scp deploy/jvm/mir176.service $H:/tmp/mir176.service

echo "== 重启"
ssh $H bash -s <<'R'
set -e
sudo install -m 644 /tmp/mir176.service /etc/systemd/system/mir176.service && rm -f /tmp/mir176.service
# 环境：沿用原有的 PORT/HOST/MIR_SESSION_SECRET，补上 JVM 版要的目录
for kv in ASSETS_DIR=/opt/mir176/public/assets WEB_DIR=/opt/mir176/web DATA_DIR=/var/lib/mir176; do
  sudo grep -q "^${kv%%=*}=" /etc/mir176.env || echo "$kv" | sudo tee -a /etc/mir176.env >/dev/null
done
sudo systemctl daemon-reload
sudo systemctl enable mir176 >/dev/null 2>&1
sudo systemctl restart mir176
for i in $(seq 1 30); do curl -sf -m 2 http://10.42.0.1:18176/healthz && break; sleep 1; done
echo
systemctl is-active mir176
R

echo "== 核对线上 jar 与本机一致"
LOCAL=$(shasum -a 256 "$JAR" | cut -d' ' -f1)
REMOTE=$(ssh $H 'sha256sum /opt/mir176/app/mir176.jar' | cut -d' ' -f1)
[[ $LOCAL == "$REMOTE" ]] || { echo "线上 jar 和本机不一致：本机 $LOCAL 线上 $REMOTE"; exit 1; }
echo "ok $(git log --oneline -1)"
