# 热血传奇 1.76 网页版

照《热血传奇》1.76 复刻的网页游戏：浏览器里玩（电脑、手机都行），服务端权威的多人模式，用 Scala 3 写（前端 Scala.js、服务端 JVM，游戏规则两边共用一份代码）。界面照原版客户端移植，数值和爆率按 1.76 数据表，再乘上自定的倍率。

这是给自己和朋友玩的非商业项目。

> **仓库里没有游戏素材。** 地图、图片、音效、原版数据表都是原版游戏的东西，版权归原权利人，不放进这个仓库。想跑起来要自己准备，做法见下面「准备素材」。

设计、架构、各模块怎么划分、做到哪了，见 [DESIGN.md](DESIGN.md)。

## 目录

| 目录 | 是什么 | 在不在 git 里 |
| --- | --- | --- |
| `scala/` | 现在线上跑的版本：`shared/`（共用的游戏逻辑）、`server/`（服务端）、`client/`（浏览器客户端） | 在 |
| `tools/` | Python 素材管线：把原版的 WZL/WIL 图库、MAP 地图、WAV 音效、数据表转成网页用的格式 | 在 |
| `deploy/` | 部署脚本、systemd 单元、k3s Ingress 示例 | 在 |
| `game/`、`server/`、`public/app.js` | 早期的 TypeScript 单机版，已不上线，只用来对照规则（`scala/testdata/gen-golden.sh` 用它算对照值） | 在 |
| `raw/` | 原版客户端素材（WZL 图库、地图、音效） | **不在**，自己放 |
| `ref/` | 原版数据表、参考源码 | **不在**，自己放 |
| `public/assets/` | 素材管线生成的网页素材（约 3.6 GB） | **不在**，自己生成 |

## 准备素材

一共三步：下原版素材 → 下数据表 → 跑素材管线生成 `public/assets/`。下面的路径都相对仓库根目录。

### 1. 原版客户端素材 → `raw/m176/`

用的是 [suneveryday/Mir176Client](https://github.com/suneveryday/Mir176Client) 的 Release **v0.1.5** 里的 `Mir176-Resources-ca77a19cb580.zip`（约 1.6 GB，国服 1.76 的 WZL 格式图库）。

```sh
mkdir -p raw/m176
# 下载 Mir176-Resources-ca77a19cb580.zip 后：
unzip Mir176-Resources-ca77a19cb580.zip -d raw/m176
```

解压完应该是这样（管线只认这几个位置）：

```
raw/m176/
  Map/*.map                 地图（约 264 个）
  Resource/Data/*.wzl|wzx   图库（Hum、Hair、Weapon、Mon*、Objects*、Prguse、ChrSel ……）
  Wav/                      音效和 sound.lst
```

### 2. 原版数据表 → `ref/Mir176/`

怪物、物品、爆率、技能、刷怪点、商店、地图传送点、经验表都从 [suneveryday/Mir176](https://github.com/suneveryday/Mir176) 的 `content/` 里来：

```sh
git clone https://github.com/suneveryday/Mir176.git ref/Mir176
git -C ref/Mir176 checkout 7ffa93f   # 现在用的版本；新版本格式变了的话 tools/build_data.py 可能要跟着改
```

要用到的是 `ref/Mir176/content/catalog.json` 和 `ref/Mir176/content/level-experience.json`。

另外，开发时对照原版行为用的 Delphi 客户端源码（`ref/mir2delphi`，来自 [baiyqukq/mir2](https://github.com/baiyqukq/mir2)）只是参考资料，跑游戏不需要。

### 3. 生成网页素材 → `public/assets/`

要装：[uv](https://docs.astral.sh/uv/)（跑 Python 脚本，自动带上 numpy、pillow）、ffmpeg（转音效）。

```sh
cd tools
uv run --no-project --with numpy --with pillow python build_assets.py   # 图集、地图格子、地面图（最慢的一步）
uv run --no-project --with numpy --with pillow python build_data.py     # 数据表 → assets/data/game.json
uv run --no-project --with numpy --with pillow python build_sounds.py   # 音效 → assets/sound/*.m4a
```

生成后的目录：

```
public/assets/
  data/game.json      游戏数据（服务端、客户端、测试都读它）
  img/<图库>/         按帧号分块的图集（png + json）
  ground/<地图>/      预渲染的地面大块（jpg）
  map/<地图>.bin      地图格子（能不能走、前景、动画、门）
  sound/              音效
  version.json        素材版本号，浏览器据此判断要不要重新下载
```

只改了一部分时可以只重跑一部分：`build_assets.py --only maps|libs|ground`。

## 本机自己玩

要装：JDK 21、[sbt](https://www.scala-sbt.org/)。素材按上面准备好。

### 方式一：起服务端（推荐，会存档，局域网朋友也能连）

```sh
cd scala
sbt server/assembly client/fullLinkJS      # 打出 server/target/scala-3.10.1/mir176.jar 和 client/web/js/main.js

export ASSETS_DIR=$PWD/../public/assets     # 素材
export WEB_DIR=$PWD/client/web              # 客户端页面
export DATA_DIR=$HOME/mir176-data           # 账号和存档（SQLite），自己挑个目录
export MIR_SESSION_SECRET=$(openssl rand -hex 24)   # 登录 cookie 的签名密钥，16 位以上；换了它所有人要重新登录，所以存下来固定用
export HOST=127.0.0.1 PORT=18176            # 局域网里别人也要连就把 HOST 改成 0.0.0.0

JAR=server/target/scala-3.10.1/mir176.jar
java -jar $JAR account add 我的账号          # 开账号，回车后输密码（登录页上也能自助注册）
java -jar $JAR serve                         # 起服务
```

浏览器打开 http://127.0.0.1:18176 ，登录、建角色就能玩。账号和角色存在 `$DATA_DIR/mir.db`，每天自动备份到 `$DATA_DIR/backup/`。

开发时不打 jar 也行：`sbt client/fastLinkJS` 后 `sbt "server/run serve"`（环境变量同上；不设时默认读 `../public/assets`、`client/web`，数据放 `server/data`）。

命令行（服务在跑的时候也能用）：

| 命令 | 作用 |
| --- | --- |
| `java -jar mir176.jar account add <账号>` | 开账号，密码从标准输入读 |
| `java -jar mir176.jar account passwd <账号>` | 改密码 |
| `java -jar mir176.jar account list` | 列账号 |
| `java -jar mir176.jar announce "内容"` | 给在线玩家发全服公告 |
| `java -jar mir176.jar bosses` | 各 boss 现在活着还是几点刷 |

`announce`、`bosses` 经 `$DATA_DIR/admin.sock` 跟正在跑的服务说话，要和服务用同一个 `DATA_DIR`、同一个系统用户。

### 方式二：纯浏览器单机（不起服务端，不存档）

```sh
cd scala
sbt client/fastLinkJS
client/dev-serve.sh          # 只听 127.0.0.1:18190
```

打开 http://127.0.0.1:18190/?local ：游戏世界直接在浏览器里跑，刷新就重来，适合看界面、调画面。`?local&hero` 直接给一个 40 级满技能的角色。

### 改倍率

经验、爆率、boss 刷新、技能熟练度等倍率在 `scala/shared/shared/src/main/scala/mir/rules/Rates.scala`。改完重新打包、重启服务。

### 跑测试

```sh
cd scala && sbt test      # JVM 和 JS 都跑；测试会读 public/assets/data/game.json，素材没准备好会失败
```

## 部署到服务器

服务端就是一个 jar + 一个静态页面目录 + 素材目录，前面挂一个支持 WebSocket 的 HTTPS 反向代理。下面以 Linux + systemd 为例。

### 服务器上第一次准备（只做一次）

```sh
sudo useradd --system --home /var/lib/mir176 --shell /usr/sbin/nologin mir176
sudo mkdir -p /var/lib/mir176 /opt/mir176/{releases,bin,public/assets,jre}
sudo chown mir176: /var/lib/mir176
# JRE 21：解压到 /opt/mir176/jre（deploy.sh 发现没有会自动从本机上传 Adoptium 的 linux x64 JRE）

sudo tee /etc/mir176.env >/dev/null <<EOF
HOST=127.0.0.1
PORT=18176
ASSETS_DIR=/opt/mir176/public/assets
WEB_DIR=/opt/mir176/current/web
DATA_DIR=/var/lib/mir176
MIR_SESSION_SECRET=$(openssl rand -hex 24)
EOF
sudo chmod 600 /etc/mir176.env
```

### 发布

本机（macOS 或 Linux，要有 JDK 21、sbt、rsync、flock）在仓库根目录、`master` 分支上：

```sh
MIR_DEPLOY_HOST=user@你的服务器 \
MIR_HEALTH_URL=http://127.0.0.1:18176/healthz \
MIR_JAVA_HOME=/path/to/jdk21 \
deploy/jvm/deploy.sh
```

[`deploy/jvm/deploy.sh`](deploy/jvm/deploy.sh) 做的事：

1. 本机打 jar 和客户端，传到服务器上一个新的版本目录 `/opt/mir176/releases/<时间>-<提交>/`（`mir176.jar`、`web/`），素材 `public/assets/` 到 `/opt/mir176/public/assets/`（各版本共用）；这时旧服照常在跑。
2. 在线备份数据库到 `DATA_DIR/backup/predeploy-<时间>.db`（留 10 份，服务器要装 `sqlite3`）。
3. **试启动**：用这份备份的副本、另一个端口（`MIR_TRIAL_PORT`，默认 18177，只听 127.0.0.1）、限内存的临时 systemd 单元把新版跑起来：先 `check`（全部角色存档解得开），再开服等 `/healthz`，再 `smoke`（注册一次性账号、建角色、进图）。任何一步不过就中止，旧服没停过。试启动退出时顺便生成类缓存 `mir176.jsa`（加快之后的开服）。
4. 装 systemd 单元 [`deploy/jvm/mir176.service`](deploy/jvm/mir176.service)，停旧服 → 软链 `/opt/mir176/current` 指向新版本 → 开服；60 秒内健康检查不过就自动切回上一版。版本目录留最近 3 个。
5. 核对服务器上的 jar 和本机一致。

ssh 登录的账号要能免密 sudo。几个人同时跑会自动排队。其他用法：

- `deploy/jvm/deploy.sh --dry-run`：只在本机打包，并用临时库在本机试启动、跑冒烟检查。
- `deploy/jvm/deploy.sh --trial`：只上传并在服务器上试启动，不换下旧服。
- `deploy/jvm/deploy.sh --rollback`：切回上一版并重启（数据库不回退；旧版读不懂新版写的停服快照时只跳过那部分，角色存档不受影响）。

不用脚本手工部署也一样：把 jar 和页面放进一个版本目录，`ln -sfn releases/<版本> /opt/mir176/current`，`sudo systemctl enable --now mir176`。

注意：

- **重启会把在线玩家踢下线**（存档不丢，页面会自动重连，版本变了会自动刷新一次）。部署前可以先发公告：`MIR_DEPLOY_HOST=… deploy/jvm/announce.sh "服务器 5 分钟后更新"`。
- 素材几个 GB，第一次 rsync 慢，之后只传变了的。服务器留够磁盘。
- 素材和存档只有登录后才能取（登录画面本身用的几张图和开门声除外），但登录页开放自助注册，拿到网址的人都能注册。

### 服务器上的日常命令

```sh
/opt/mir176/bin/mir176ctl account add <账号>      # 开号（密码从标准输入读）
/opt/mir176/bin/mir176ctl account passwd <账号>
/opt/mir176/bin/mir176ctl account list
/opt/mir176/bin/mir176ctl announce "内容"
/opt/mir176/bin/mir176ctl bosses
sudo journalctl -u mir176 -f                       # 日志：上线/下线（带在线人数）、boss 刷出死亡、客户端报错
```

### HTTPS 反向代理

服务只听 `HOST:PORT`，HTTPS 交给前面的代理，WebSocket 在路径 `/ws` 上，代理要放行 Upgrade。

nginx：

```nginx
server {
    listen 443 ssl;
    server_name mir.example.com;
    # ssl_certificate ...;
    location / {
        proxy_pass http://127.0.0.1:18176;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 1h;
    }
}
```

k3s + Traefik + cert-manager：参考 [`deploy/k8s.yaml`](deploy/k8s.yaml)（Service + EndpointSlice 指到宿主机上的服务，Ingress 配域名和证书；把域名、宿主机地址换成自己的，服务的 `HOST` 要设成集群能访问到的宿主机地址，例如 cni0 网卡的 `10.42.0.1`）。
