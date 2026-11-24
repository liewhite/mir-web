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
    - `mir.rules`：ItemKind/ItemEffects/Requirement（items.ts）、Rules（rules.ts，随机数经 Rng 传入）、Rates（rates.ts）、Leveling；`Pace`（走跑挥刀施法冲撞的时长、NPC 对话距离）和 `Side`（阵营、谁和谁敌对），服务端判定和客户端预测共用（#1173）。
    - `mir.terrain`：地形 `Terrain`（能不能走、挡不挡魔法）和八方向 A* `Path`，服务端和客户端共用。
    - `mir.protocol`：`ClientCmd = SessionCmd | GameCmd`（登录选人对时归服务端连接；游戏指令归世界）、`ServerMsg`（事件）+ 视野类型，zio-json 编码（外层键是分支名）。敌我按外观判：`Look.hostile(a, b, catalog.isGuard)`（服务端结算和客户端选目标同一条，守卫按 `MonsterDef.isGuard`）。走路按原版：客户端带起点格和方向，服务端回 MoveOk / MoveDenied(拉回格)。
    - `mir.world`（#1153）：一张图一个 `World`（单线程用，内部可变，对外「`handle(玩家, GameCmd)` / `tick(时刻)` 进，`flush(): Outbox` 出」；随机数 Rng、物品编号 UidSource、时钟都从外面给；没玩家的图 tick 不做事）。
      - 结构（#1173）：`World` 只管对象表、时钟、定时器、每次推进的顺序和地上物品；各管一摊的部分是它的 `private[world] val`，world 包里的规则点名调用（如 `w.vitals.strike`、`w.out.notice`、`w.grid.move`）：`Mailbox`（out，攒要发的消息）、`Placement`（grid，占位与移动：格子、朝向、动作只由它改）、`Vitals`（血蓝与生死：挨打结算、死亡经验掉落、复活、回血、属性重算；血量蓝量血上限只由它改，别处调 heal/spendMp/damage/setMaxHp）、`Conditions`（身上状态、地上法术）、`Hostility`（敌我、找最近的敌人）、`Roster`（进出图、存档快照、传送换图）、`Spawner`（开图放 NPC 守卫、刷怪）、`Visibility`（flush 时按视野分消息、SelfView）。约定由 JVM 测试 `OwnershipSpec` 按源码查（含「客户端除 LocalServer 外不 import mir.world」）。
      - 进出：`join(CharacterData, Carried)` 返回本图对象编号；`leave` 取存档；换图（传送点、传送菜单、回城、复活）在 `Outbox.departures` 里交给服务端送去目的地图，`Carried`（宝宝、身上的限时状态、没回完的药，不进存档）跟着带过去（#1170）。
      - 战斗结算（#1170）：所有攻击（剑术、魔法、怪物各种出招、火墙、野蛮冲撞）都造一个 `Blow`（扣防/扣魔防/不扣/自爆各一半、威力、要不要判命中）交给 `vitals.strike`：敌我与在不在图上 → 命中 → 扣防 → `vitals.damage`（红毒 ×1.2、魔法盾、扣血、被打动作、死亡经验），返回 `StrikeResult`（毒液毒气的附带效果按是否打到身上判）。身上的限时状态是 `Actor.statuses`（`Status`：中毒、魔法盾、隐身、麻痹、神圣战甲术、幽灵盾、定住、发狂），每次 tick 开头统一到期，怪的名字颜色（mood）和自己的 SelfStatus 都从它算。延时回调只有 `afterOn(对象, 毫秒)`：到点时对象已不在本图或已死就不跑。普通药分跳回血是 `Player.potions`。
      - `Inventory`（#1159）：身上和仓库物品进出，同名可叠的并叠、按格判满、重量按个数、拆分；进图时 normalize（旧存档补数量、合并）。`GameCmd.Sell/Deposit/Withdraw` 带 `count`（空 = 整叠）。
      - `Behavior`（Race → 出招/藏身/附加本领；每种本领的状态是 `ExtraState`，存在 `Monster.extras`，加 Boss 本领只加 Extra/ExtraState 分支和 MonsterAi 里的处理，不往 Monster 加字段）+ `MonsterAi`；按外观 RaceImg 的画法和特效（钻地类、固定朝向、攻击帧、攻击特效、飞行物）只在 `mir.data.MonsterLook` 一张表，服务端 AI 和客户端 `Frames` 共用；`Combat`（普攻剑术；放技能的共通流程：扣蓝、冷却、施法动作、熟练度）；技能（#1171）：`mir.rules.Skill` 枚举（名字代替编号，带用法 被动/开关/蓄力/冲撞/魔法，耗蓝和冷却随用法），施放效果在 `mir.world.skills`（Wizard/Taoist/Warrior，每个技能一个 `SkillHandler`，含起手特效），`SkillHandlers.of` 按 Skill 穷举注册——加技能：Skill 加一项、写处理器、注册一行，说明在客户端 `SkillInfo` 按 Skill 穷举（漏了编译不过）；`PlayerOps`（指令：移动校验、物品、NPC 买卖存取，非法的回 Refused）；`AutoPlay`（自动喝药、按住攻击、还手、烈火、捡物，按角色设置在服务端跑）。
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
    - `ui`（#1156，照 TS 版 ui/classic 移植，外观操作一致）：`ClassicUi`（实现 UiEvents + Overlay；画面 Lobby/Select/Game、点击登记 `Hits`、悬停说明）、窗口统一管理（#1174）：每个窗口实现 `Window`（isOpen/draw/close，可选 itemAt/hover 给悬停说明），`ClassicUi.windows` 按从下到上排好，绘制、点击叠放、悬停、closeAll 都遍历它——新加窗口只写一个实现、在列表里排位置；底栏按钮和键盘共用 `UiAction`（键位表 `UiAction.Keys` 是数据）。窗口：`MerchantPanel`（买卖存取）、`BagWindow`、`StateWindow`（4 页 `StatePage`）、`SettingsWindow`、`TeleportPanel`、`KeyDialog`（技能快捷键）、`QuantityPanel`、`ItemMenuWindow`（触屏说明+操作弹框，最上层）；常驻 HUD：`Board`（原版底栏 + 叠在上方的摇杆/攻击键/F1–F8 技能环/锁定怪信息 + 传送键）、`MiniMap`、`CharSelect`（原版选人/建角色画面，ChrSel 图库）、`ChatInput`（回车或点聊天框弹网页输入框，「!」开头喊话）、`Describe`/`SkillInfo`（物品/属性/技能说明，纯函数；#1165 数值全部调 shared 规则现算：区间用 `Rng.lowest/highest`，概率用 `Odds` 枚举随机分支，规则常量在 Rules/Recovery/PotionRules/Durability/Pricing/SpellRules/SwordRules，不在说明里抄公式；技能环长按看说明）、`Hand`（拖物品：只是界面状态，放下时发 MoveItem/UseItem/DropItem，结果以服务端为准）、`SettingRow`（设置分两类：玩法类发 UpdateSettings 存角色上，画面声音类存 localStorage）、`Lobby`（加载页、登录网页表单）。所有改状态的操作都发 GameCmd，画的永远是视野副本。触屏设备（pointer: coarse）点物品弹说明+操作菜单（按触屏倍数放大），电脑点一下拿起、再点放下、双击使用。
    - `window.__mir` 另有 `screen()`、`chat()`、`bag()`、`worn()`、`gold()`、`sent()`（最近发出的游戏指令）、`goTo(x,y)`。
- 测试：怪物行为（TS 版 7 项）、多人（互相看见、各打各的、掉落、换图消失、60 秒随机操作每步副本与世界一致、比奇省满图性能）、非法指令；game.json 完整解码与引用检查；规则与 TS 版逐条对照（`scala/testdata/ts-golden.json`，TS 规则改了就跑 `scala/testdata/gen-golden.sh` 重算）；协议每种消息往返（新加消息必须补样例，测试会查）。

## 怎么加技能/怪物/状态/窗口（#1175，按「加代码 + 加表」）
路径省略前缀 `scala/shared/shared/src/main/scala/` 与 `scala/client/src/main/scala/`。
- **新地图、新怪（只用已有的出招方式）、刷怪、爆率、商店**：只改数据（`tools/build_data.py` 产出的 game.json / ref 数据表），不改代码；新怪的 Race 已在 `Behavior.of` 里有对应就自动有行为，外观特征按 RaceImg 在 `mir.data.MonsterLook`。
- **加技能**：`mir.rules.Skill` 加一项（编号、用法）→ `mir.world.skills` 下对应职业写一个 `SkillHandler`（效果、起手特效）→ `SkillHandlers.of` 注册一行 → 客户端 `SkillInfo` 写说明（按 Skill 穷举，漏了编译不过）。公式放 `SpellRules`/`SwordRules`，说明从同一个函数现算。耗蓝、冷却、施法动作、熟练度由 `Combat.cast` 统一处理，不用写。
- **加怪物出招方式**：`Strike` 加分支 → `MonsterAi` 里处理（伤害一律造 `Blow` 交给 `vitals.strike`）→ `Behavior.of` 把 Race 映射过去。藏身方式同理加 `Hiding` 分支。
- **加 Boss 本领**：`Extra` 加分支和 `initial` → `ExtraState` 加它的状态 → `MonsterAi` 本领处理加一个分支；不往 `Monster` 加字段。
- **加身上状态**：`StatusKind` 加一种、`Status` 加带参数的分支 → 上状态的地方 `statuses.put/extend`，生效的地方 `has/find`，到期由世界统一去掉；要让人看见时在 `Entities.mood`（怪名字颜色）或 `Visibility.selfStatus`（自己的状态栏）加一行；换图自动随 `Carried` 带走。
- **加界面窗口**：`mir.client.ui` 写一个 `Window` 实现（isOpen/draw/close，可选 itemAt/hover）→ 在 `ClassicUi.windows` 按层次排位置；要快捷键或底栏按钮就加 `UiAction` 一项和 `UiAction.Keys` 一行。
- **加协议消息**：`ServerMsg`/`GameCmd` 加分支，协议往返测试里补样例（测试会查漏）。
- 结构约定由 `OwnershipSpec` 按源码检查（谁能改血量、位置；客户端不 import `mir.world`），违反了 `sbt test` 报错。

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
- [x] 法师、道士技能实际效果（#1163，Scala 版）：公式在 `mir.rules.SpellRules`（MagicManager.cs，随机数经 Rng，技能说明可求上下限）；施放效果按技能注册在 `mir.world.skills`（#1171）；地上持续法术（火墙、困魔咒光圈）是世界里的 `Field`，按视野发 `FieldAppear/FieldDisappear`，客户端照 ClEvent.pas 画；怪物被定住/发狂、神圣战甲术/幽灵盾都是 `Actor.statuses` 里的状态（#1170），外观 `Look.Monster.mood` 让名字变色。本机 `?local&hero` 新建 40 级满技能角色看效果。
- [x] 战士野蛮冲撞（#1168）：`world.Rush` 照 DoMotaebo/CharPushed，公式 `SpellRules.rush*`；被推用 `grid.pushBack`（抗拒火环共用），动作 `ActKind.Rush`（from 为空＝撞不动原地冲）/`Backstep`（脸朝推的人倒退），客户端按原版 ActRushLeft/Right、倒放走路帧画。
- [x] 瞬息移动改传当前地图随机位置（#1177，用户定，原版 MagSaceMove 传回城点那张图）：取格用 `Placement.randomSpot`（随机传送卷共用）；带 NORANDOMMOVE 的图两者都不能用（照原版 EatUseItems 2：卷轴不消耗、技能不扣蓝）。
- [x] 随时出售 + 自动捡物分类（#1178，用户定）：`GameCmd.QuickSell(uid, count)` 不用找 NPC，包裹/快捷栏里的都收（穿着的不行），价钱同商店收购 `Pricing.sell`（表价一半），和 NPC 卖出共用 `PlayerOps.sellOff`；客户端一律先弹数量框确认（手机：物品菜单「出售」；电脑：拿起物品点包裹里的「出售」键）。自动捡物 = 总开关 `autoPickup` + 分类 `CharSettings.pickupKinds`（`PickupKind`：金币、药品、装备、书、肉矿石、符毒、卷轴、其他；物品归类 `ItemKind.pickupKind`，肉 StdMode 40 算肉矿石），存档没有这个字段时全捡；范围不变（6 格内走过去捡）。
- [x] 极品（#1191，用户定照原版、不加倍率）：`ItemInstance.bonus: Option[ItemBonus]`（`Weapon` 攻/魔/道上限、准确、攻速可负、强度；`Gear` 加在 AC/MAC/DC/MC/SC 上限，含义仍由 `ItemEffects.of(d, bonus)` 按 StdMode 解读），旧存档没有字段为 None。只在怪物爆物时生成：`RandomUpgrade.monsterDrop`，1/10 进随机升级，各部位概率照 GameItemSystem.cs UpgradeRandom*（另加持久，只加持久不算极品）。强度按原版磨损比例降低本服武器磨损（`Durability.weaponWears`）。地上 `GroundThing.Item.special` 名字亮青色（`Renderer.SpecialItemColor`），物品说明标题标「极品」并单列多了什么。原版爆物持久随机 20%–99% 本服没做。
- [ ] 门（doorIndex）；按地图回城点（现在一律回比奇）
