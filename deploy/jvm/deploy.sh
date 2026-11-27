#!/bin/bash
# Scala 多人版部署到 kaojiang（#1154；从 Node 单机版切过来在 #1148 做；试启动、留版本、回滚在 #1288 加）。
#
#   deploy/jvm/deploy.sh             打包 → 上传成新版本 → 在旁边试启动（线上库的副本、另一个端口）→ 通过才停旧服换新版
#   deploy/jvm/deploy.sh --dry-run   只在本机打包，并在本机用临时库试启动一次、跑冒烟检查，不连服务器
#   deploy/jvm/deploy.sh --rollback  切回上一版并重启（不打包；数据库不回退，玩家进度保留）
#   deploy/jvm/deploy.sh --trial     只上传并在服务器上试启动一次，不换下旧服（试完删掉这个版本目录）；不限 master 分支
#   MIR_TRIAL_AT=local deploy/jvm/deploy.sh
#                                    服务器内存紧、在旁边试启动会挤线上时用（#1378）：同样上传、备份线上库，
#                                    但试启动改在本机跑（线上库的备份拉到本机临时目录，试完删掉）；不生成类缓存，新版开服慢几秒
#
# 只动 mir176 自己的东西：/opt/mir176、/etc/mir176.env、/etc/systemd/system/mir176.service、/var/lib/mir176，
# 试启动时临时用 systemd 单元 mir176-trial（及 -check、-smoke）、端口 MIR_TRIAL_PORT（默认 18177，只听 127.0.0.1）、/run/mir176-trial.env。
# Ingress 不变（k8s.yaml 指向 10.42.0.1:18176，WebSocket 走同一个 Ingress）。
# 服务器上：
#   /opt/mir176/jre                       JRE 21（第一次从本机上传）
#   /opt/mir176/releases/<时间>-<提交>/    每次部署一个版本：mir176.jar、web/（客户端页面）、mir176.jsa（类缓存，试启动时生成，#1290）
#   /opt/mir176/current -> releases/…     在用的版本（systemd 单元和 mir176ctl 都经它找 jar）；留最近 3 版
#   /opt/mir176/public/assets/            素材（各版本共用）
#   /var/lib/mir176/mir.db                账号和角色；backup/ 每天一份留 30 天，另外每次部署、回滚前一份 predeploy-*.db 留 10 份
#   /opt/mir176/bin/mir176ctl             命令行：mir176ctl account add <账号> 等
#
# 部署到别的服务器：用环境变量改目标（默认值就是作者自己的 kaojiang），第一次部署前的准备见 README.md「部署到服务器」。
#   MIR_DEPLOY_HOST    ssh 目标（ssh 配置里的别名或 user@host），登录账号要能免密 sudo
#   MIR_HEALTH_URL     在服务器上检查服务起来没有的地址（和 /etc/mir176.env 的 HOST、PORT 一致）
#   MIR_TRIAL_PORT     试启动用的本地端口（默认 18177，要空着）
#   MIR_JAVA_HOME      本机 JDK 21 的目录
set -euo pipefail
cd "$(dirname "$0")/../.."
H=${MIR_DEPLOY_HOST:-kaojiang}
HEALTH=${MIR_HEALTH_URL:-http://10.42.0.1:18176/healthz}
TRIAL_PORT=${MIR_TRIAL_PORT:-18177}
export JAVA_HOME=${MIR_JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
FLOCK=$(command -v flock || echo /opt/homebrew/bin/flock)
JRE=deploy/jvm/jre.tar.gz
JAR=scala/server/target/scala-3.10.1/mir176.jar
MODE=${1:-deploy}
case $MODE in deploy|--dry-run|--trial|--rollback) ;; *) echo "用法：$0 [--dry-run | --trial | --rollback]"; exit 2 ;; esac
TRIAL_AT=${MIR_TRIAL_AT:-server}   # 试启动在哪跑：server（服务器上、线上库副本）| local（本机、拉下来的线上库副本，#1378）
case $TRIAL_AT in server|local) ;; *) echo "MIR_TRIAL_AT 只能是 server 或 local"; exit 2 ;; esac

# 服务器上的公共函数：切版本、等健康检查、留备份。部署和回滚都用。
REMOTE_LIB=$(cat <<'LIB'
set -euo pipefail
OPT=/opt/mir176
DATA=/var/lib/mir176
health() { for i in $(seq 1 "$2"); do curl -sf -m 2 "$1" >/dev/null && return 0; sleep 1; done; return 1; }
# 数据库在线备份（服务在写也一致），留最近 10 份
backup_db() {
  local f=$DATA/backup/predeploy-$(date +%Y%m%d-%H%M%S).db
  sudo -u mir176 mkdir -p $DATA/backup
  sudo -u mir176 sqlite3 -cmd '.timeout 10000' $DATA/mir.db ".backup '$f'"
  sudo -u mir176 sqlite3 "$f" 'PRAGMA quick_check' | grep -qx ok || { echo "备份 $f 校验不过"; return 1; }
  sudo bash -c "ls -1t $DATA/backup/predeploy-*.db | tail -n +11 | xargs -r rm -f"
  echo "$f"
}
# 停服 → 软链换到 $1 → 开服，等健康检查
switch_to() {
  sudo systemctl stop mir176
  ln -sfn "releases/$1" $OPT/current.tmp && mv -T $OPT/current.tmp $OPT/current
  sudo systemctl start mir176
  health "$HEALTH" 60
}
current_release() { local c; c=$(readlink $OPT/current 2>/dev/null) || return 0; basename "$c"; }
LIB
)
# 在服务器上跑：标准输入给的脚本前面加上 REMOTE_LIB；参数是传过去的环境变量（如 REL=…）
remote() { local body; body=$(cat); ssh $H env "$@" bash -s <<<"$REMOTE_LIB"$'\n'"$body"; }

# ---------------- 回滚 ----------------
if [[ $MODE == --rollback ]]; then
  exec 9>/tmp/mir176-deploy.lock
  "$FLOCK" -w 1800 9 || { echo "等别的部署超过 30 分钟，先看 pgrep -fl deploy/jvm/deploy.sh"; exit 1; }
  remote HEALTH="$HEALTH" <<'R'
cur=$(current_release)
prev=$(ls -1 $OPT/releases | sort | awk -v c="$cur" '$0 < c' | tail -1)
[[ -n $cur && -n $prev ]] || { echo "没有可回滚的上一版（现在：${cur:-无}；有：$(ls $OPT/releases | tr '\n' ' ')）"; exit 1; }
B=$(backup_db)
echo "== 备份数据库：$B"
echo "== 回滚 $cur → $prev"
if switch_to "$prev"; then
  echo "ok 已回滚到 ${prev}（数据库没回退；旧版读不懂新版写的停服快照时只跳过那部分：野怪重刷、地上物品不保留，角色存档不受影响）"
else
  echo "回滚后健康检查不过，看 journalctl -u mir176 -n 50"; exit 1
fi
R
  exit 0
fi

# 只从 master 部署：在 worktree 分支上跑会把没合并的代码推上线，盖掉别人已合进 master 的改动。
BR=$(git rev-parse --abbrev-ref HEAD)
[[ $BR == master || $MODE != deploy ]] || { echo "当前分支是 ${BR}：先合进 master，再在主仓库 mir/ 里部署"; exit 1; }
# 几个会话可能同时部署：排队，一个打包上传完下一个再从最新 master 打包（不排队的话后打包的可能缺先合并的提交）。
exec 9>/tmp/mir176-deploy.lock
"$FLOCK" -w 1800 9 || { echo "等别的部署超过 30 分钟，先看 pgrep -fl deploy/jvm/deploy.sh"; exit 1; }
COMMIT=$(git rev-parse --short HEAD)
echo "== 部署 $(git log --oneline -1)"

echo "== 打包"
(cd scala && sbt -batch server/assembly client/fullLinkJS)
test -f "$JAR"
test -f scala/client/web/index.html && test -f scala/client/web/js/main.js || { echo "缺客户端页面 scala/client/web/"; exit 1; }
if [[ ! -f $JRE ]]; then
  echo "== 下载 JRE 21（linux x64）"
  curl -fL --retry 5 -A mir176-deploy -o $JRE.tmp 'https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse'
  mv $JRE.tmp $JRE
fi

if [[ $MODE == --dry-run ]]; then
  echo "== 本机试启动（临时库）"
  T=$(mktemp -d)
  PORT=$(/usr/bin/python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
  env HOST=127.0.0.1 PORT=$PORT DATA_DIR=$T ASSETS_DIR=public/assets WEB_DIR=scala/client/web MIR_SESSION_SECRET=dry-run-$RANDOM-0123456789 \
    "$JAVA_HOME/bin/java" -Xmx512m -jar "$JAR" serve > "$T/serve.log" 2>&1 &
  PID=$!
  trap 'kill $PID 2>/dev/null; wait $PID 2>/dev/null; rm -rf "$T"' EXIT
  for i in $(seq 1 60); do curl -sf -m 2 "http://127.0.0.1:$PORT/healthz" >/dev/null && break; kill -0 $PID 2>/dev/null || break; sleep 1; done
  curl -sf -m 2 "http://127.0.0.1:$PORT/healthz" >/dev/null || { echo "本机试启动起不来："; tail -30 "$T/serve.log"; exit 1; }
  "$JAVA_HOME/bin/java" -jar "$JAR" smoke "http://127.0.0.1:$PORT"
  ls -la "$JAR" $JRE
  exit 0
fi

REL=$(date +%Y%m%d-%H%M%S)-$COMMIT
# 通讯格式指纹（#1289、#1303）：变了旧页面重连时马上刷新；没变就先接着玩，等换图、回城、复活时再刷新
WIRE_PY='import sys,zipfile,re; m=re.findall(rb"[0-9a-f]{16}", zipfile.ZipFile(sys.argv[1]).read("mir/protocol/Release$.class")); print(m[0].decode() if m else "?")'
NEW_WIRE=$(/usr/bin/python3 -c "$WIRE_PY" "$JAR")
OLD_WIRE=$(ssh $H python3 - /opt/mir176/current/mir176.jar <<<"$WIRE_PY" 2>/dev/null || echo "?")
if [[ $NEW_WIRE == "$OLD_WIRE" ]]; then echo "== 通讯格式没变（${NEW_WIRE}）：旧页面先接着玩，换图、回城、复活时刷新"
else echo "== 通讯格式变了（$OLD_WIRE → ${NEW_WIRE}）：旧页面重连时马上刷新"; fi
echo "== 上传新版本 ${REL}（旧服照常在跑）"
ssh $H 'sudo mkdir -p /opt/mir176/releases /opt/mir176/bin /opt/mir176/public && sudo chown "$(id -un)": /opt/mir176 /opt/mir176/releases /opt/mir176/bin /opt/mir176/public'
if ! ssh $H 'test -x /opt/mir176/jre/bin/java'; then
  scp $JRE $H:/tmp/mir176-jre.tar.gz
  ssh $H 'sudo mkdir -p /opt/mir176/jre && sudo tar -xzf /tmp/mir176-jre.tar.gz -C /opt/mir176/jre --strip-components=1 && rm -f /tmp/mir176-jre.tar.gz'
fi
# 新版本目录先用上一版硬链接铺底，rsync 只传差异（rsync 写临时文件再改名，不会改到上一版的文件）
ssh $H REL="$REL" bash -s <<'R'
set -e
d=/opt/mir176/releases/$REL
mkdir -p "$d/web"
if [[ -L /opt/mir176/current ]]; then cp -al /opt/mir176/current/mir176.jar /opt/mir176/current/web "$d/" 2>/dev/null || true
elif [[ -f /opt/mir176/app/mir176.jar ]]; then cp -al /opt/mir176/app/mir176.jar "$d/" && cp -al /opt/mir176/web/. "$d/web/"; fi
rm -f "$d/mir176.jsa"
R
rsync -az "$JAR" $H:/opt/mir176/releases/$REL/mir176.jar
rsync -az --delete scala/client/web/ $H:/opt/mir176/releases/$REL/web/
rsync -az --delete public/assets/ $H:/opt/mir176/public/assets/
scp -q deploy/jvm/mir176.service $H:/tmp/mir176.service
scp -q deploy/jvm/mir176ctl $H:/tmp/mir176ctl

# 本机试启动（#1378）：在服务器上备份线上库，拉到本机临时目录，用刚打的 jar 跑 存档检查 → 开服 → 冒烟，试完删掉库副本。
# 不通过就删掉刚上传的版本目录退出，旧服不受影响。
local_trial() {
  local B T PORT PID
  B=$(remote <<<'backup_db' | tail -1) || { echo "试启动不通过：数据库备份失败"; ssh $H "rm -rf /opt/mir176/releases/$REL"; exit 1; }
  echo "数据库备份：$B"
  T=$(mktemp -d)
  PID=
  trap '[[ -n $PID ]] && kill $PID 2>/dev/null; wait $PID 2>/dev/null; rm -rf "$T"' EXIT
  local_fail() {
    echo "试启动不通过：$1"; [[ -f $T/serve.log ]] && tail -30 "$T/serve.log"
    echo "旧服没停，线上不受影响。新版本目录已删。"; ssh $H "rm -rf /opt/mir176/releases/$REL"; exit 1
  }
  ssh $H "sudo cat '$B'" > "$T/mir.db" || local_fail "拉不下数据库备份"
  [[ $(sqlite3 "$T/mir.db" 'PRAGMA quick_check') == ok ]] || local_fail "拉下来的数据库校验不过"
  PORT=$(/usr/bin/python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
  local ENV=(env HOST=127.0.0.1 PORT=$PORT DATA_DIR=$T ASSETS_DIR=public/assets WEB_DIR=scala/client/web MIR_SESSION_SECRET=trial-$RANDOM-$RANDOM-0123456789)
  echo "-- 角色存档检查"
  "${ENV[@]}" "$JAVA_HOME/bin/java" -Xmx512m -jar "$JAR" check || local_fail "新版读不了现有的角色存档"
  echo "-- 开服"
  "${ENV[@]}" "$JAVA_HOME/bin/java" -Xmx1024m -jar "$JAR" serve > "$T/serve.log" 2>&1 &
  PID=$!
  local t0; t0=$(date +%s)
  for _ in $(seq 1 150); do curl -sf -m 2 "http://127.0.0.1:$PORT/healthz" >/dev/null && break; kill -0 $PID 2>/dev/null || break; sleep 1; done
  curl -sf -m 2 "http://127.0.0.1:$PORT/healthz" >/dev/null || local_fail "150 秒内健康检查不过"
  echo "开服 $(( $(date +%s) - t0 )) 秒"
  echo "-- 冒烟（注册一次性账号、建角色、进图；在副本库上）"
  "$JAVA_HOME/bin/java" -jar "$JAR" smoke "http://127.0.0.1:$PORT" || local_fail "冒烟检查不过"
  kill $PID; wait $PID 2>/dev/null || true; PID=
  rm -rf "$T"; trap - EXIT
  echo "（本机试启动不生成类缓存，新版开服慢几秒，不影响）"
  echo "试启动通过"
}

if [[ $TRIAL_AT == local ]]; then
echo "== 本机试启动新版（线上库的副本）"
local_trial
[[ $MODE == --trial ]] && { ssh $H "rm -rf /opt/mir176/releases/$REL"; echo "（--trial：版本目录已删，线上没动）"; }
else
echo "== 试启动新版（线上库的副本、127.0.0.1:${TRIAL_PORT}）"
remote REL="$REL" TRIAL_PORT="$TRIAL_PORT" MODE="$MODE" <<'R'
d=$OPT/releases/$REL
T=$DATA/trial
# 试启动别挤线上（#1359）：kaojiang 2 核 3.5G 没有 swap，线上服和 k3s（muliao 的公网入口、Traefik）已占大半内存。
# 11-26 晚高峰试启动把整机挤到内存吃紧，k3s 卡住、Traefik 被重启，线上 34 人连接全断。所以：
#  - 试启动的几个 JVM 都用最省的配置（小堆、Serial GC、只用 C1 编译），CPU/IO 优先级最低，各自限内存，超了只杀它自己；
#  - 开始前看整机可用内存，不够就不试（也就不换版）；
#  - 试的过程中盯着整机内存压力（/proc/pressure/memory），一冒头就立即杀掉试启动、不换版。
# 类缓存（#1290）用这套配置生成，线上 G1 大堆照样能用（只记类，不记 GC 和堆大小）。
LEAN="-Xms32m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -XX:ReservedCodeCacheSize=48m -Xss512k -Dfile.encoding=UTF-8"
LOW=(-p Nice=19 -p CPUWeight=1 -p IOWeight=1 -p User=mir176 -p EnvironmentFile=/run/mir176-trial.env)
UNITS="mir176-trial mir176-trial-check mir176-trial-smoke"
NEED_MB=850        # 开始前整机可用内存至少要这么多：开服那个 JVM 限 450M，再给线上和系统缓存留 400M
PSI_LIMIT=5        # 整机「有进程在等内存」的时间占比（最近 10 秒，%）到这个数就停；平时是 0
FLOOR_MB=300       # 试的过程中整机可用内存低于这个也停
R_FILE=; S_FILE=
mem_avail() { awk '/^MemAvailable/{print int($2/1024)}' /proc/meminfo; }
mem_stall() { awk '/^some/{split($2,a,"="); print int(a[2])}' /proc/pressure/memory; }
# 盯内存的后台循环：线上开始被挤就杀掉试启动的进程，原因写进 R_FILE；这期间最差的一刻写进 S_FILE
watch_pressure() {
  local lo=999999 hi=0
  while sleep 2; do
    local s a; s=$(mem_stall); a=$(mem_avail)
    (( a < lo )) && lo=$a; (( s > hi )) && hi=$s
    echo "试启动期间整机可用内存最低 ${lo}MB、等内存的时间最高占 ${hi}%" > "$S_FILE"
    if (( s >= PSI_LIMIT || a < FLOOR_MB )); then
      echo "整机内存吃紧（等内存的时间占 ${s}%、可用 ${a}MB），为了不挤垮线上服，已立即停掉试启动" > "$R_FILE"
      sudo systemctl kill -s KILL $UNITS 2>/dev/null || true
      return
    fi
  done
}
WPID=
fail() {
  local why=$1; [[ -s $R_FILE ]] && why=$(cat "$R_FILE")
  echo "试启动不通过：$why"; echo "旧服没停，线上不受影响。新版本目录已删。"; sudo rm -rf "$d"; exit 1
}
cleanup() {
  [[ -n $WPID ]] && kill "$WPID" 2>/dev/null || true
  for u in $UNITS; do sudo systemctl stop "$u" 2>/dev/null || true; sudo systemctl reset-failed "$u" 2>/dev/null || true; done
  sudo rm -rf "$T" /run/mir176-trial.env
  rm -f "$R_FILE" "$S_FILE"
}
trap cleanup EXIT
cleanup
R_FILE=$(mktemp); S_FILE=$(mktemp)
! ss -ltn "sport = :$TRIAL_PORT" | grep -q LISTEN || fail "端口 $TRIAL_PORT 被占用"
avail=$(mem_avail)
(( avail >= NEED_MB )) || fail "服务器现在可用内存只有 ${avail}MB（试启动要 ${NEED_MB}MB 才不挤线上服），等在线人少些再部署"
echo "整机可用内存 ${avail}MB、等内存的时间占 $(mem_stall)%"
B=$(backup_db) || fail "数据库备份失败"
echo "数据库备份：$B"
sudo -u mir176 mkdir -p "$T" && sudo -u mir176 cp "$B" "$T/mir.db"
# 环境：线上的那份，换掉端口、地址、数据目录、页面目录
{ sudo grep -vE '^(PORT|HOST|DATA_DIR|WEB_DIR)=' /etc/mir176.env; printf 'PORT=%s\nHOST=127.0.0.1\nDATA_DIR=%s\nWEB_DIR=%s\n' "$TRIAL_PORT" "$T" "$d/web"; } \
  | sudo tee /run/mir176-trial.env >/dev/null
sudo chmod 600 /run/mir176-trial.env
J=$OPT/jre/bin/java
watch_pressure & WPID=$!
echo "-- 角色存档检查"
sudo systemd-run --quiet --wait --pipe --collect --unit=mir176-trial-check "${LOW[@]}" -p MemoryMax=400M \
  $J $LEAN -Xmx256m -jar "$d/mir176.jar" check || fail "新版读不了现有的角色存档"
echo "-- 开服"
# 退出时写类缓存（#1290）
sudo systemd-run --quiet --unit=mir176-trial "${LOW[@]}" \
  -p MemoryMax=450M -p RuntimeMaxSec=600 -p ProtectSystem=strict -p ProtectHome=true -p PrivateTmp=true -p ReadWritePaths=$T \
  -p TimeoutStopSec=60 -p SuccessExitStatus=143 \
  $J $LEAN -Xmx256m -XX:ArchiveClassesAtExit=$T/mir176.jsa -jar "$d/mir176.jar" serve
t0=$(date +%s)
trial_up() { for _ in $(seq 1 150); do curl -sf -m 2 "http://127.0.0.1:$TRIAL_PORT/healthz" >/dev/null && return 0; [[ -s $R_FILE ]] && return 1; sleep 1; done; return 1; }
trial_up || { sudo journalctl -u mir176-trial -n 30 --no-pager; fail "150 秒内健康检查不过"; }
echo "开服 $(( $(date +%s) - t0 )) 秒"
echo "-- 冒烟（注册一次性账号、建角色、进图；在副本库上）"
sudo systemd-run --quiet --wait --pipe --collect --unit=mir176-trial-smoke "${LOW[@]}" -p MemoryMax=250M \
  $J $LEAN -Xmx128m -jar "$d/mir176.jar" smoke "http://127.0.0.1:$TRIAL_PORT" \
  || { sudo journalctl -u mir176-trial -n 30 --no-pager; fail "冒烟检查不过"; }
sudo systemctl stop mir176-trial
kill "$WPID" 2>/dev/null || true; WPID=
[[ -s $R_FILE ]] && fail "$(cat "$R_FILE")"
cat "$S_FILE"
if sudo test -s "$T/mir176.jsa"; then sudo cp "$T/mir176.jsa" "$d/mir176.jsa" && sudo chown "$(id -un)": "$d/mir176.jsa"; else echo "（没生成类缓存，不影响）"; fi
echo "试启动通过"
if [[ $MODE == --trial ]]; then rm -rf "$d"; echo "（--trial：版本目录已删，线上没动）"; fi
R
fi

[[ $MODE == --trial ]] && exit 0

echo "== 换上新版并重启"
remote REL="$REL" HEALTH="$HEALTH" <<'R'
prev=$(current_release)
sudo install -m 644 /tmp/mir176.service /etc/systemd/system/mir176.service && rm -f /tmp/mir176.service
install -m 755 /tmp/mir176ctl $OPT/bin/mir176ctl && rm -f /tmp/mir176ctl
# 环境：沿用原有的 PORT/HOST/MIR_SESSION_SECRET；页面跟着版本走（current/web），其余补上缺的
setenv() { if sudo grep -q "^$1=" /etc/mir176.env; then sudo sed -i "s|^$1=.*|$1=$2|" /etc/mir176.env; else echo "$1=$2" | sudo tee -a /etc/mir176.env >/dev/null; fi; }
setenv WEB_DIR $OPT/current/web
sudo grep -q '^ASSETS_DIR=' /etc/mir176.env || setenv ASSETS_DIR $OPT/public/assets
sudo grep -q '^DATA_DIR=' /etc/mir176.env || setenv DATA_DIR $DATA
sudo systemctl daemon-reload
sudo systemctl enable mir176 >/dev/null 2>&1
if switch_to "$REL"; then
  echo "$(systemctl is-active mir176) $REL"
else
  sudo journalctl -u mir176 -n 30 --no-pager
  if [[ -n $prev ]]; then
    echo "新版开服后健康检查不过，自动切回 $prev"
    switch_to "$prev" && echo "已切回 ${prev}，线上恢复" || echo "切回后也起不来，马上看 journalctl -u mir176"
  else
    echo "新版开服后健康检查不过（没有上一版可切回），马上看 journalctl -u mir176"
  fi
  exit 1
fi
# 第一次切到版本目录后，旧的单份目录没用了
[[ -d $OPT/app && ! -L $OPT/app ]] && rm -rf $OPT/app
[[ -d $OPT/web && ! -L $OPT/web ]] && rm -rf $OPT/web
# 留最近 3 版（在用的那版总留着）
cur=$(current_release)
ls -1 $OPT/releases | sort | head -n -3 | while read -r r; do [[ $r == "$cur" ]] || rm -rf "$OPT/releases/$r"; done
echo "现有版本：$(ls -1 $OPT/releases | tr '\n' ' ')"
R

echo "== 核对线上 jar 与本机一致"
LOCAL=$(shasum -a 256 "$JAR" | cut -d' ' -f1)
REMOTE=$(ssh $H 'sha256sum /opt/mir176/current/mir176.jar' | cut -d' ' -f1)
[[ $LOCAL == "$REMOTE" ]] || { echo "线上 jar 和本机不一致：本机 $LOCAL 线上 $REMOTE"; exit 1; }
echo "ok $(git log --oneline -1)"
