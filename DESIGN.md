# 传奇 1.76 单机网页版（issue #1137）设计与进度

## 来源
- 素材：`raw/m176/`（suneveryday/Mir176Client v0.1.5 Resources，SHA256 0da278c1…，WZL 格式，国服）；备用 `raw/th2001/`（2001 欧服光盘，原始 WIL）。不运行任何 exe。
- 数据：`ref/Mir176/content/catalog.json`（monsters/items/drops/skills/spawns/merchants/routes/maps/guards/minimaps）、`level-experience.json`。
- 客户端行为权威参考：`ref/mir2delphi/client`（原版 Delphi 客户端源码，GBK 编码，读前 iconv）：
  - `Actor/Actor.pas`：人物动作表 HA、怪物动作表 MA9..MA47、GetRaceByPM(RaceImg→动作表)、怪物图库 aGetMonImg(appr div 10 → Mon{n+1})、GetOffset(appr)、GetNpcOffset。
  - `MapUnit.pas`、`DrawScrn.pas`、`Scene/`：地图绘制；`MagicEff.pas`：技能特效。
- 服务端公式参考：`ref/Mir176/server/src`（OpenMir2 C#）。

## 部署（用户 11-23 定）
- 不放 muliao 页面（sandbox 无存储），做成独立 B/S：小服务端（静态+素材长缓存、登录、存档读写）。
- 部署到 kaojiang 服务器（(服务器地址)，k3s + Traefik + cert-manager letsencrypt，admin@kaojiang，ssh 证书要用户批），域名 mir.leeliewhite.com（DNS 由 muliao 加）。
- 公网域名 → 必须登录（只给用户一个账号），素材和存档都要登录后才能取。
- kaojiang 服务器同时是 muliao 公网入口：不动 Traefik 本身，只加 Ingress；改网络前先问用户。

## 架构（领域划分）
- `tools/`（Python，离线）：`wemade.py` 解析 WIL/WZL/MAP；`build_assets.py` 产出 `site/mir176/assets/`：
  - `img/<lib>/<chunk>.png|json`：按帧号分块的图集，json 每帧 `[ax,ay,w,h,ox,oy]` 或 0。
  - `ground/<map>/<cx>_<cy>.jpg`：地面层（Tiles+SmTiles）预渲染大块。
  - `map/<map>.bin`：格子数据（可走、前景图号、图库、动画、门、光）。
  - `data/*.json`：从 catalog 编译的游戏数据。
- `game/src`（TypeScript，esbuild 打包成 `site/mir176/app.js`）：
  - `assets/`：图集按需加载、帧查询。
  - `world/`：纯逻辑模拟（实体、移动、战斗公式、AI、刷怪、掉落、技能、升级），不碰渲染，可单测。
  - `render/`：地图/角色/特效/飘字 绘制（Canvas2D）。
  - `ui/`：HUD、背包、商店、属性面板、设置。
  - `input/`：鼠标/触屏/键盘 → 指令。
  - `save/`：SaveStore 接口。

## Scala 3 多人版（#1148，进行中；替换前线上仍是上面的 TS 单机版）
- `scala/`（sbt；JDK 用 `JAVA_HOME=/opt/homebrew/opt/openjdk@21`；`sbt test` 同时跑 JVM 和 JS）：
  - `shared/`（JVM+JS 交叉编译，纯逻辑，不碰网络/文件/DOM）：
    - `mir.core`：编号与数值的不透明类型（ActorId、ItemUid、ItemName、MonsterName、MapId、SkillId、CharId、Hp、Mp、Gold、Exp、Millis）、格子 Cell、八方向 Dir（ordinal = 原版编号）。
    - `mir.model`：职业、性别、装备位、动作、攻击种类、藏身、中毒等枚举；物品实例、身上位置、学会的技能、角色便利设置。
    - `mir.data`：game.json 解码（GameData，格式不变）与索引 Catalog（查不到返回 None）。
    - `mir.rules`：ItemKind/ItemEffects/Requirement（items.ts）、Rules（rules.ts，随机数经 Rng 传入）、Rates（rates.ts）、Leveling。
    - `mir.protocol`：`ClientCmd = SessionCmd | GameCmd`（登录选人对时归服务端连接；游戏指令归世界）、`ServerMsg`（事件）+ 视野类型，zio-json 编码（外层键是分支名）。走路按原版：客户端带起点格和方向，服务端回 MoveOk / MoveDenied(拉回格)。
    - `mir.world`（#1153）：一张图一个 `World`（单线程用，内部可变，对外「`handle(玩家, GameCmd)` / `tick(时刻)` 进，`flush(): Outbox` 出」；随机数 Rng、物品编号 UidSource、时钟都从外面给；没玩家的图 tick 不做事）。
      - 进出：`join(CharacterData, 宝宝)` 返回本图对象编号；`leave` 取存档；换图（传送点、传送菜单、回城、复活）在 `Outbox.departures` 里交给服务端送去目的地图。
      - `Inventory`（#1159）：身上和仓库物品进出，同名可叠的并叠、按格判满、重量按个数、拆分；进图时 normalize（旧存档补数量、合并）。`GameCmd.Sell/Deposit/Withdraw` 带 `count`（空 = 整叠）。
      - `Behavior`（Race → 出招/藏身/附加本领）+ `MonsterAi`；`Combat`（普攻剑术、技能）；`PlayerOps`（指令：移动校验、物品、NPC 买卖存取，非法的回 Refused）；`AutoPlay`（自动喝药、按住攻击、还手、烈火、捡物，按角色设置在服务端跑）。
      - 视野：flush 时按切比雪夫 14 格算每个玩家的出现/消失，对象的动作/血量/外观按「和上次发出去的比，变了才发」，特效声音飘字只发给附近的人，自己的属性背包等变了才发。
      - 守卫按 Race 认（11/12/20/112），不再按名字（TS 版把沃玛卫士、祖玛卫士当成了守卫）。
    - `mir.view.ViewState`：客户端的视野副本，`apply(ServerMsg)` 纯函数折叠；测试里每一步都和世界对照。
  - `server/`（JVM，#1154；包 `mir.server`）：
    - `http/HttpServer`（zio-http）：`POST api/login {account,password}` 设 cookie `mir_sess`（HMAC 签名、90 天，换 MIR_SESSION_SECRET 全部失效；同 IP 10 分钟失败 10 次 429）、`GET api/me`、`POST api/logout`、`GET ws`（cookie 有效才升级，一帧一条 JSON：客户端 ClientCmd、服务端 ServerMsg；连上先发 LoggedIn）；`assets/…` 登录后才给（缓存一周，version.json 除外），其余路径是 WEB_DIR 的客户端页面；ETag/304、文本类 gzip。
    - `game/GameServer`：一个游戏线程每 50ms 一步（网络线程只投事件）：会话指令（列/建/删角色、进游戏、对时）自己办，游戏指令交给角色所在图的 World；推进有人的图 → flush → 按对象编号发给连接 → 换图的人交给目的地图（`game/Maps` 按需开图，开过的留着）。同一角色再登录踢旧连接（Kicked）、接着用在线状态；断线、换图、升级、每 30 秒、停服时存档；进不了存档里的图就回城、再不行回新手村。
    - `store/`：SQLite（DATA_DIR/mir.db，WAL）：accounts（scrypt，与旧 Node 版同参数）、characters（存档是 CharacterData JSON；每号最多 2 个，名字全服唯一、GBK 不超过 14 字节，删除只做标记）、counters（全服物品编号按 1000 一块领，服务端和命令行同时发也不撞）；每天备份 backup/mir-日期.db 留 30 份。
    - 命令行（`Main`）：`account add|passwd <账号>`（密码从标准输入读）、`account list`、`migrate <账号> <save.json> [角色名]`（旧单机存档导成角色，物品重新编号）。
    - 环境变量：PORT、HOST、ASSETS_DIR（public/assets）、WEB_DIR（scala/client/web）、DATA_DIR、MIR_SESSION_SECRET。本机：`cd scala && ASSETS_DIR=../public/assets WEB_DIR=client/web DATA_DIR=/tmp/mir-data MIR_SESSION_SECRET=<16位以上> sbt "server/run account add 名字"` 再 `sbt server/run`。
    - 测试 `ServerSpec`：真起服务，两个机器人账号 HTTP 登录 + WebSocket：开号命令、素材要登录、建角色规则、互相看见、走和打、断线重连、踢旧连接、换图（含图上只剩一人时传走）、迁移命令。
    - 部署 `deploy/jvm/deploy.sh`（sbt-assembly 打成 mir176.jar；JRE 21 放 /opt/mir176/jre；systemd 单元仍叫 mir176、端口和 Ingress 不变；服务器上命令行 `/opt/mir176/bin/mir176ctl`）。从 Node 版切过来在 #1148 做。
  - `client/`（Scala.js，#1155；照 TS 版 assets/render/audio/input/joystick/viewport 移植，画面与 TS 版逐像素对得上）：
    - 构建：`sbt client/fastLinkJS`（开发）/ `client/fullLinkJS`（发布，约 5MB）产物在 `client/web/js/main.js`（不进 git）；`client/web/` 是服务端在 `/` 提供的静态目录（index.html、style.css、图标）。
    - 选人画面用 ChrSel 图库（build_assets.py 整库导出的 `chrsel`）。
    - 本机看：`client/dev-serve.sh [端口]`（默认 18190，只听 127.0.0.1）后开 `http://127.0.0.1:18190/?local`：`net.LocalServer` 在浏览器里跑共用的世界当服务端（同一协议，指令消息都过 JSON），不用起服务端。`window.__mir` 给 browser-cli 测试：`state()`、`stick(方向,跑)`、`release()`、`tap(对象)`、`attack()`、`teleport(下标)`。
    - 和服务端的约定（#1154 评论）：HTTP `api/login`/`api/me` 登录拿 cookie（素材要登录），WebSocket 在相对路径 `ws`，一帧一条 JSON；连上服务端先发 LoggedIn；断线自动重连后直接 `EnterGame(上次的角色)`。
    - `net`：`Transport`（WsTransport / LocalServer）、`ServerClock`（进图粗定、Ping/Pong 按往返一半校正，只采信快的）。
    - `game`：`GameClient`（视野副本 + 一次性画面 + 发指令，不碰 DOM，测试里直接接 World 跑）、`SelfMotion`（本地先走：发 Walk/Run 同时本地走，MoveOk 才发下一步，MoveDenied 丢掉本地的回到服务端位置）、`Controller`（意图：摇杆走跑、点 NPC 走过去对话、点物品去捡、锁定攻击；自动喝药捡物还手烈火在服务端 AutoPlay）、`UiEvents`（给界面的一次性事件）。
    - `assets`（素材版本、图集块、地图格子 MapData、重试下载）、`render`（Frames 动作表、Renderer、Scene、Transients 特效飘字气泡）、`audio`、`input`（Joystick、Input：界面 Overlay 先接触摸和按键）。
    - `ui`（#1156，照 TS 版 ui/classic 移植，外观操作一致）：`ClassicUi`（实现 UiEvents + Overlay；画面 Lobby/Select/Game、窗口开关、点击登记 `Hits`、键盘）、`Board`（原版底栏 + 叠在上方的摇杆/攻击键/F1–F8 技能环/锁定怪信息 + 传送键）、`Panels`（小地图、状态窗 4 页、包裹、设置、技能快捷键、悬停说明、手机物品菜单）、`MerchantPanel`（买卖存取）、`TeleportPanel`、`CharSelect`（原版选人/建角色画面，ChrSel 图库）、`ChatInput`（回车或点聊天框弹网页输入框，「!」开头喊话）、`Describe`/`SkillInfo`（物品/属性/技能说明，纯函数；#1165 数值全部调 shared 规则现算：区间用 `Rng.lowest/highest`，概率用 `Odds` 枚举随机分支，规则常量在 Rules/Recovery/PotionRules/Durability/Pricing/SpellRules/SwordRules，不在说明里抄公式；技能环长按看说明）、`Hand`（拖物品：只是界面状态，放下时发 MoveItem/UseItem/DropItem，结果以服务端为准）、`SettingRow`（设置分两类：玩法类发 UpdateSettings 存角色上，画面声音类存 localStorage）、`Lobby`（加载页、登录网页表单）。所有改状态的操作都发 GameCmd，画的永远是视野副本。触屏设备（pointer: coarse）点物品弹说明+操作菜单（按触屏倍数放大），电脑点一下拿起、再点放下、双击使用。
    - `window.__mir` 另有 `screen()`、`chat()`、`bag()`、`worn()`、`gold()`、`sent()`（最近发出的游戏指令）、`goTo(x,y)`。
- 测试：怪物行为（TS 版 7 项）、多人（互相看见、各打各的、掉落、换图消失、60 秒随机操作每步副本与世界一致、比奇省满图性能）、非法指令；game.json 完整解码与引用检查；规则与 TS 版逐条对照（`scala/testdata/ts-golden.json`，TS 规则改了就跑 `scala/testdata/gen-golden.sh` 重算）；协议每种消息往返（新加消息必须补样例，测试会查）。

## 进度
- [x] 找素材、找数据、核实平台存储限制
- [x] WIL/WZL/MAP 解析，比奇地图渲染验证
- [x] 资源管线（tools/build_assets.py、build_data.py；改素材后 bump_version）
- [x] 引擎骨架、战斗/怪物/掉落/升级（战士验证过打鸡）
- [x] 界面：用户要求「尽量接近原版 UI」→ ui/classic 照 FState.pas 移植（底栏、状态窗4页、包裹、商人、菜单、出售/保管、快捷键、设置）
- [x] 服务端 server/server.mjs（登录、存档、ETag 缓存）；本机 dev：`set -a; . server/dev.env` 后 node server.mjs，密码 test
- [x] 技能特效校准（tools/sheet.py 出帧号对照图）、骷髅洞、触屏技能按钮
- [x] 部署 kaojiang（Node 单机版，已停用）：systemd mir176（/opt/mir176，监听 cni0 10.42.0.1:18176），k3s ns mir176 Ingress；存档 /var/lib/mir176；密码 vault MIR176_PASSWORD
- [x] 11-23 线上换成 Scala 多人版（#1148）：部署只用 `deploy/jvm/deploy.sh`（旧 deploy/deploy.sh 已停用）；用户账号 liewhite（密码 vault MIR176_PASSWORD），旧单机存档已导成角色；给朋友开号在服务器上 `/opt/mir176/bin/mir176ctl account add <账号>`。Node 版最后的存档留在 /var/lib/mir176/backup/save-node-final-pre-jvm.json。TS 版 game/ 不再上线，改玩法只改 scala/（TS 版只留作规则对照，scala/testdata/gen-golden.sh 用它算对照值）。
- [x] 小地图、音效（tools/build_sounds.py → assets/sound/*.m4a）、远程怪、首屏预加载、物件 10 帧分块
- [x] 手机布局（#1140，用户定）：`ui/viewport.ts` 按屏幕选布局——电脑/平板用原版底栏（`ui/classic/layout.ts` BoardLayout），触屏且短边 < 600 用手机布局（`ui/classic/touch.ts`）：界面与地图分开缩放、左半屏虚拟摇杆（`game/joystick.ts`）、点怪锁定并显示怪物信息、攻击键+F1–F8 技能环、物品点一下弹说明和操作菜单；安全区、添加到主屏幕全屏
- [x] 第二阶段地图（#1139）：从比奇沿传送点走得到的 188 张地图全上（毒蛇山谷、盟重、沃玛森林/寺庙、石墓、祖玛、矿区、地牢等）；内容相同的地图共用素材（MapDef.asset），素材约 2.4G。
- [x] 第三阶段地图（#1144，用户定「都加进去」）：数据表里有地图文件的 265 张全上（素材 3.6G），没路进去的白日门、封魔谷、苍月岛作为城市、未知暗殿和不归路作为独立地区进传送菜单（build_data.py TOWNS / ENTRIES），赤月、封魔殿、尸魔洞、骨魔洞、牛魔寺庙等按城后的路分组。
- [x] 怪物行为（#1139）：game/src/world/monster_ai.ts 按 Race 描述出招/藏身/附加本领（食人花钻地、石像、僵尸电光与复活、洞蛆麻痹、蜘蛛喷毒、角蝇巢/幻影蜘蛛放小怪、爆裂蜘蛛自爆、沃玛教主狂暴瞬移、祖玛教主召唤、赤月恶魔/触龙神范围攻击）；测试 `cd game && npm test`
- [x] 传送菜单（#1139，用户要求）：左上「传送」键或 T 键，分组列城中心和各打怪地区入口（tools/build_data.py teleports；ui/classic/teleport.ts）
- [x] 物品叠放（#1159，用户定，原版不叠）：药、卷轴、技能书、捆装药同名叠一格不设上限（`ItemKind.stacks` / items.ts `stacks`），装备、符毒、矿石不叠；`world/inventory.ts` 管并叠、拆分、按格判满、重量按个数；卖/存/取一叠弹数量框（`ui/classic/quantity.ts`，默认全部）；旧存档读档补数量 1 并合并同名。
- [x] 法师、道士技能实际效果（#1163，Scala 版）：公式在 `mir.rules.SpellRules`（MagicManager.cs，随机数经 Rng，技能说明可求上下限）；`Combat.spell` 按技能做；地上持续法术（火墙、困魔咒光圈）是世界里的 `Field`，按视野发 `FieldAppear/FieldDisappear`，客户端照 ClEvent.pas 画；怪物被定住/发狂是 `Monster.seizedUntil/crazyUntil`，外观 `Look.Monster.mood` 让名字变色；神圣战甲术/幽灵盾是 `Actor.acUp/macUp`。本机 `?local&hero` 新建 40 级满技能角色看效果。
- [x] 战士野蛮冲撞（#1168）：`world.Rush` 照 DoMotaebo/CharPushed，公式 `SpellRules.rush*`；被推用 `World.pushBack`（抗拒火环共用），动作 `ActKind.Rush`（from 为空＝撞不动原地冲）/`Backstep`（脸朝推的人倒退），客户端按原版 ActRushLeft/Right、倒放走路帧画。
- [ ] 门（doorIndex）；按地图回城点（现在一律回比奇）
