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
- 公网域名 → 必须登录，素材和存档都要登录后才能取（登录画面本身用的几张图和开门声除外）；11-24 起完全开放自助注册（用户定，不加邀请码，#1197），拿到网址的人都能注册。
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
    - `mir.protocol`：`ClientCmd = SessionCmd | GameCmd | SocialCmd`（登录选人对时归服务端连接；游戏指令归世界；社交指令归服务端的 `mir.social`）、`ServerMsg`（事件）+ 视野类型，zio-json 编码（外层键是分支名）。敌我按外观判：`Look.hostile(a, b, catalog.isGuard)`（服务端结算和客户端选目标同一条，守卫按 `MonsterDef.isGuard`）。走路按原版：客户端带起点格和方向，服务端回 MoveOk / MoveDenied(拉回格)。
    - `mir.world`（#1153）：一张图一个 `World`（单线程用，内部可变，对外「`handle(玩家, GameCmd)` / `tick(时刻)` 进，`flush(): Outbox` 出」；随机数 Rng、物品编号 UidSource、时钟都从外面给；没玩家的图 tick 不做事）。
      - 结构（#1173）：`World` 只管对象表、时钟、定时器、每次推进的顺序和地上物品；各管一摊的部分是它的 `private[world] val`，world 包里的规则点名调用（如 `w.vitals.strike`、`w.out.notice`、`w.grid.move`）：`Mailbox`（out，攒要发的消息）、`Placement`（grid，占位与移动：格子、朝向、动作只由它改）、`Vitals`（血蓝与生死：挨打结算、死亡经验掉落、复活、回血、属性重算；血量蓝量血上限只由它改，别处调 heal/spendMp/damage/setMaxHp）、`Conditions`（身上状态、地上法术）、`Hostility`（敌我、找最近的敌人；`isEnemy` 主动当目标、`harms` 范围攻击能伤到谁（含 PK）、`canAttack` 亲手选中能打谁）、`Justice`（PK：灰名、PK 值涨落、红名死了掉东西，#1225）、`Roster`（进出图、存档快照、传送换图）、`Spawner`（开图放 NPC 守卫、刷怪）、`Visibility`（flush 时按视野分消息、SelfView）。约定由 JVM 测试 `OwnershipSpec` 按源码查（含「客户端除 LocalServer 外不 import mir.world」）。
      - 进出：`join(CharacterData, Carried)` 返回本图对象编号；`leave` 取存档；换图（传送点、传送菜单、回城、复活）在 `Outbox.departures` 里交给服务端送去目的地图，`Carried`（宝宝、身上的限时状态、没回完的药，不进存档）跟着带过去（#1170）。
      - 战斗结算（#1170）：所有攻击（剑术、魔法、怪物各种出招、火墙、野蛮冲撞）都造一个 `Blow`（扣防/扣魔防/不扣/自爆各一半、威力、要不要判命中）交给 `vitals.strike`：敌我与在不在图上 → 命中 → 扣防 → `vitals.damage`（红毒按强度加深、魔法盾、扣血、被打动作、死亡经验），返回 `StrikeResult`（毒液毒气的附带效果按是否打到身上判）。身上的限时状态是 `Actor.statuses`（`Status`：中毒、魔法盾、隐身、麻痹、神圣战甲术、幽灵盾、定住、发狂），每次 tick 开头统一到期，怪的名字颜色（mood）、角色身上看得见的异常（`Actor.afflictions` → `ActorView.afflictions` / `ServerMsg.AfflictionsChanged`，锁定信息卡列出中了绿毒/红毒/麻痹，#1212；客户端用 `Affliction.tint` 推出染色：绿毒染绿、红毒染红、麻痹染灰，同时有时麻痹 > 红毒 > 绿毒，照原版 GetDrawEffectValue；客户端 `render.Tinter` 按亮度重上色并缓存，人物只染身子和头发不染武器，#1211）和自己的 SelfStatus 都从它算。延时回调只有 `afterOn(对象, 毫秒)`：到点时对象已不在本图或已死就不跑。普通药分跳回血是 `Player.potions`。自然回血（`Vitals.regen`，规则 `rules.Recovery`，#1214）：玩家每 2 秒、怪物和宝宝照原版每 6 秒回 上限/75+1；中绿毒期间不回，毒消失后从头计时。
      - `Inventory`（#1159）：身上和仓库物品进出，同名可叠的并叠、按格判满、重量按个数、拆分；进图时 normalize（旧存档补数量、合并）。`GameCmd.Sell/Deposit/Withdraw` 带 `count`（空 = 整叠）。
      - `Behavior`（Race → 出招/藏身/附加本领；每种本领的状态是 `ExtraState`，存在 `Monster.extras`，加 Boss 本领只加 Extra/ExtraState 分支和 MonsterAi 里的处理，不往 Monster 加字段）+ `MonsterAi`；按外观 RaceImg 的画法和特效（钻地类、固定朝向、攻击帧、攻击特效、飞行物）只在 `mir.data.MonsterLook` 一张表，服务端 AI 和客户端 `Frames` 共用；`Combat`（普攻剑术；放技能的共通流程：扣蓝、冷却、施法动作、熟练度）；技能（#1171）：`mir.rules.Skill` 枚举（名字代替编号，带用法 被动/开关/蓄力/冲撞/魔法，耗蓝和冷却随用法），施放效果在 `mir.world.skills`（Wizard/Taoist/Warrior，每个技能一个 `SkillHandler`，含起手特效），`SkillHandlers.of` 按 Skill 穷举注册——加技能：Skill 加一项、写处理器、注册一行，说明在客户端 `SkillInfo` 按 Skill 穷举（漏了编译不过）；`PlayerOps`（指令：移动校验、物品、NPC 买卖存取，非法的回 Refused）；`AutoPlay`（自动喝药、按住攻击、还手、烈火、捡物，按角色设置在服务端跑）。
      - 视野：flush 时按切比雪夫 14 格算每个玩家的出现/消失，对象的动作/血量/外观按「和上次发出去的比，变了才发」，特效声音飘字只发给附近的人，自己的属性背包等变了才发。
      - 守卫按 Race 认（11/12/20/112），不再按名字（TS 版把沃玛卫士、祖玛卫士当成了守卫）。
      - 守卫可被玩家选中攻击（#1205）：`Look.attackable` = 敌对或「玩家打守卫」，只用于亲手选中的单个目标（`World.canAttack`，`Vitals.strike` 总闸）；范围攻击、自动找怪、宝宝仍只认 `isEnemy`。守卫被出手（躲开/防住也算）就记仇 30 秒（`Monster.grudge`），期间 `isEnemy(守卫, 他)` 为真；Race 11/12 出招 `Strike.Warp`：不走动、目标多远都一刀砍到。
      - 守卫打谁看种类 `GuardKind`（#1216，`MonsterDef.guard` 按 Race 认）：`Blade`（11 卫士、带刀护卫，原版 SuperGuard AttackPet=True）主动打 Race ≥ 80 的野怪和宝宝（诱惑来的鸡鹿、野狼不打）；`PetSparing`（12，数据里没有）宝宝只打正把它当目标的；`Archer`（20/112 弓箭守卫）不打宝宝、野怪除鸡鹿羊都打。宝宝阵营上仍跟玩家一边，守卫与宝宝之间的敌我在 `Hostility.guardVsPet` 补：守卫正打着某人或他的任何一只宝宝时，他的宝宝都把这个守卫当敌人（原版 IsAttackTarget），挨砍的宝宝还手、同主人的围上去。玩家体验：骷髅、神兽等宝宝走进卫士、带刀护卫附近会被砍死。
    - `mir.social`（#1220 起，跨地图的社交，服务端单线程用，每个操作返回要发的 `Letter(收信人名字, ServerMsg)`）：门面 `Social`（实现 `Company`，`handle(me, SocialCmd, Directory)` 按指令分给下面几块；`Directory` 是查人的两种方式：在线角色、全部角色（加好友，服务端查库 `Characters.canonicalName`））；`Book` 存在线角色的社交偏好（有记录即在线），组队、好友共用。`Groups` 组队，照原版 ObjPlay.pas——「允许组队」开关（`SocialPrefs.allowGroup`，默认关，存档；建组的人自动打开；队员关掉 = 退队，队长关不掉）；建组（对方在线、活着、不在组、允许组队）、队长加人（最多 `GroupRules.MaxMembers` 11 人）、队长删人（删自己 = 解散，剩 1 人也散）；队员下线退队、队长下线全队解散（原版不转让）；队伍聊天 `SocialCmd.GroupSay`（聊天框「!!」开头，`Speech.command` 分流）发全队，`ChatKind.Group`，客户端照原版前缀〖组队〗、只进聊天框。人按角色名认（不分大小写）。社交偏好在 `CharacterData.social`：世界只原样带着，在线时的值归 `Social` 的 `Book`，`GameServer.save` 存档时填进去（所以下线先存档再 `offline`）。世界经 `Company.mates(名字)` 查队友（`World` 构造参数，服务端传同一个 `Social`）：打死怪时同图、活着、横竖 12 格内的队友不止一人，经验 ×`GroupRules.Bonus(人数)`（2 人 1.3 … 11 人 2.2，原版表按人数取）后按等级比例分（`GroupRules.share`；原版只判了横坐标，这里横竖都判）。客户端：`ui.GroupWindow`（prguse 120 原版组队窗口，底栏组队键或 P；新建/添加/删除弹聊天框位置的输入框，预填锁定的玩家或点选的成员）、锁定别的玩家时信息卡下有组队键（没组队「组队」，队长「加入小组」/「踢出小组」）；`ViewState.group`（换图不清）。原版没有队员血条、小地图标记，没做。
    - 私聊（#1221，`Whispers`，照原版 TPlayObject.Whisper）：聊天框「/名字 内容」（`Speech.command` 分出 `SocialCmd.Whisper`），对方在线就发 `ServerMsg.Whisper(对方, 内容, Received)`，同时回发的人 `Sent`（服务端确认才显示，不在线回「XX 没有在线。」）；最多 100 字。客户端照原版颜色：收到「名字=> 内容」蓝字白底（调色板 $FC/$FF），发出「/名字 内容」浅蓝（180 号）白底；只进聊天框。点私聊那行 = 预填「/对方 」回复（原版点聊天行填名字，这里只对私聊行，免得点聊天框说话总被填名字）；电脑按「/」预填最近私聊的人（原版 WhisperName）。原版的拒绝私聊开关、黑名单没做。
    - 好友（#1221，`Friends`）：1.76 原版没有（素材里没有好友窗图），做个简单的：名单存 `SocialPrefs.friends`（旧档没有为空），单向、不用对方同意，名字须是已有角色（不用在线），最多 `Friends.Max` 50；`ServerMsg.Friends(List[FriendView(名字, 在线)])` 上线、增删、名单上的人上下线时发整份（另发「好友 X 上线了/下线了」提示；顶号重进不提示）。客户端 `ui.FriendWindow`（传送窗口同款面板，底栏组队键右边自画的「友」键或 F 键；在线排前、8 行一页；添加/删除/私聊）；锁定别的玩家时信息卡下一排键：组队键（同上）、「私聊」、「加好友」/「删好友」。`ViewState.friends`。
    - 交易（#1222，`mir.world.Trades`，照原版 ObjPlay.pas ClientDealTry/AddDealItem/ChangeDealGold/DealEnd）：交易双方必在同一张图上，所以归世界（不走 `mir.social`），指令是 `GameCmd.DealTry/DealPut/DealTake/DealGold/DealConfirm/DealCancel`，状态 `ServerMsg.Deal(Option[DealView])`（整份发，关掉为空；`ViewState.deal`，换图清掉）。开始：相邻（`TradeRules.OpenRange` 1 格）一方点「交易」是请求，对方 30 秒内也对他点才打开（原版要两人面对面、一方按键；手机转向不便，改成双方各点一次）。交易栏是「指着包裹里的东西」（物品编号+个数、金币数），东西一直在包裹里，存档、掉线都不会丢；`World.handle` 和 `tick` 之后 `trades.settle()` 核对：走开（>`KeepRange` 2 格）、死了、不在图上（下线、换图）就取消，交易栏里的东西被用掉/扔掉/穿上/花掉就按包裹改过并取消双方确认。任一边改了交易栏双方确认都取消，对方 1 秒内改过不能确认（原版 DealOKTime）。成交原子：先核对，再记下两人包裹金币（`Pocket`），拿出、互放，任何一边放不下（格子、金币超 Int）就整体恢复、交易栏留着；成交后 `out.checkpoint` 双方，服务端在同一步里存两人的档（`Outbox.checkpoints`，升级存档也走它）。客户端 `ui.DealWindow`（prguse 390 对方、389 自己，右上角上下叠放、让出攻击键；每边 5×2 格；391 确认、名字变绿打勾是已确认；点左下金币格输金币；打开时包裹一起开，已放进交易栏的包裹/快捷栏格子画暗；Esc 关窗即取消）：电脑拿起包裹物品点自己的格子放入（一叠问个数）、点格子里的拿回；手机物品菜单「放入交易」/「从交易栏拿回」。发起：锁定玩家信息卡的「交易」键，或底栏交易键（prguse 132；锁定的玩家，没有就相邻最近的）。本机 `?local&buddy` 身边放个会自动同意、确认的「木头人」看交易窗口；`__mir.trade()`、`deal()`、`cmd(指令JSON)`。
    - `mir.view.ViewState`：客户端的视野副本，`apply(ServerMsg)` 纯函数折叠；测试里每一步都和世界对照。
  - `server/`（JVM，#1154；包 `mir.server`）：
    - `http/HttpServer`（zio-http）：`POST api/login {account,password}` 设 cookie `mir_sess`（HMAC 签名、90 天，换 MIR_SESSION_SECRET 全部失效；同 IP 10 分钟失败 10 次 429）、`POST api/register {account,password}`（#1197：自助注册，账号/密码格式同开号命令，重名 409、格式错 400，`{error}` 是给玩家看的中文；同 IP 1 小时试 10 次 429；成功即设 cookie）、`GET api/me`、`POST api/logout`、`GET ws`（cookie 有效才升级，一帧一条 JSON：客户端 ClientCmd、服务端 ServerMsg；连上先发 LoggedIn；解不开的指令回 Refused 并记日志「看不懂的指令」，每账号每分钟最多 5 条）、`POST api/client-error {message,stack?,char?,version?}`（#1228：登录才收，浏览器里的报错打成日志 `客户端报错：消息 ｜ 账号 角色 版本 ｜ UA` 加 `    at …` 堆栈行；每账号 30 条/小时、每 IP 60 条/小时，超了 429，大于 16KB 413；字段去换行、截短）；`assets/…` 登录后才给（`LoginScreenAssets` 白名单除外：version.json、chrsel 和 prguse 图集第 0 块、开门声 100；缓存一周，version.json 除外），其余路径是 WEB_DIR 的客户端页面；ETag/304、文本类 gzip。
    - `game/GameServer`：一个游戏线程每 50ms 一步（网络线程只投事件）：会话指令（列/建/删角色、进游戏、对时）自己办，游戏指令交给角色所在图的 World；推进有人的图 → flush → 按对象编号发给连接 → 换图的人交给目的地图（`game/Maps` 按需开图，开过的留着）。同一角色再登录踢旧连接（Kicked）、接着用在线状态；断线、换图、升级、每 30 秒、停服时存档；进不了存档里的图就回城、再不行回新手村。掉线超时（#1218）：一个连接 30 秒没收到任何消息（客户端每 10 秒 Ping）就关连接、存档下线，管手机锁屏/断网不发关闭帧的情况；日志里每次上线、下线都打「在线 N 人」（部署前看有没有人在玩）。一步超过 500ms 打「游戏一步耗时 N 毫秒」（`game/SlowSteps`，每分钟最多一行，#1228）。客户端 `ErrorReport` 把 window 报错、未处理的 Promise 失败、脚本/样式加载失败、主循环和启动里接住的异常、看不懂的服务端消息报给 `api/client-error`（同条本页只报一次、每页最多 20 条，版本取 sbt 生成的 `BuildInfo.commit`）；主循环一帧出错不再定住画面。巡检 watch/mir-health/check.py 按这几个行首关键字发现。
    - `store/`：SQLite（DATA_DIR/mir.db，WAL）：accounts（scrypt，与旧 Node 版同参数）、characters（存档是 CharacterData JSON；每号最多 2 个，名字全服唯一、GBK 不超过 14 字节，删除只做标记）、counters（全服物品编号按 1000 一块领，服务端和命令行同时发也不撞）；每天备份 backup/mir-日期.db 留 30 份。
    - 命令行（`Main`）：`account add|passwd <账号>`（密码从标准输入读）、`account list`、`migrate <账号> <save.json> [角色名]`（旧单机存档导成角色，物品重新编号）。
    - 环境变量：PORT、HOST、ASSETS_DIR（public/assets）、WEB_DIR（scala/client/web）、DATA_DIR、MIR_SESSION_SECRET。本机：`cd scala && ASSETS_DIR=../public/assets WEB_DIR=client/web DATA_DIR=/tmp/mir-data MIR_SESSION_SECRET=<16位以上> sbt "server/run account add 名字"` 再 `sbt server/run`。
    - 测试 `GameServerSpec`：不起网络，假连接 + 假时钟测主循环（掉线超时、交易成交双方立刻存档）。
    - 测试 `ServerSpec`：真起服务，两个机器人账号 HTTP 登录 + WebSocket：开号命令、素材要登录、自助注册（成功即登录、重名、格式错、限频、登录画面素材免登录）、建角色规则、互相看见、走和打、断线重连、踢旧连接、换图（含图上只剩一人时传走）、迁移命令。
    - 部署 `deploy/jvm/deploy.sh`（sbt-assembly 打成 mir176.jar；JRE 21 放 /opt/mir176/jre；systemd 单元仍叫 mir176、端口和 Ingress 不变；服务器上命令行 `/opt/mir176/bin/mir176ctl`）。从 Node 版切过来在 #1148 做。
    - 全服公告（#1243）：服务端在数据目录开管理入口 `admin.sock`（unix socket，权限 600，只有服务账号 mir176 和 root 连得上，不监听网络端口、不经 Ingress；`server/admin/AdminSocket`，指令 `AdminCmd`）。`mir176ctl announce "内容"`（或从标准输入读）经它让游戏线程给全部连接发 `ServerMsg.Announce`，聊天框照原版 SendBroadCastMsg(t_Notice) 显示「〖公告〗内容」白字红底；本机用 `deploy/jvm/announce.sh "内容"` 经 ssh 发。回话列出游戏里在线的角色。
    - 定时人数公告（#1244）：游戏线程每 5 分钟（`server/game/Headcount`）用同一种公告发一行「本服已注册 N 人，当前在线 M 人」（注册数查 accounts 表，在线数是游戏里的角色）；那一轮游戏里没人就不发。
  - `client/`（Scala.js，#1155；照 TS 版 assets/render/audio/input/joystick/viewport 移植，画面与 TS 版逐像素对得上）：
    - 构建：`sbt client/fastLinkJS`（开发）/ `client/fullLinkJS`（发布，约 5MB）产物在 `client/web/js/main.js`（不进 git）；`client/web/` 是服务端在 `/` 提供的静态目录（index.html、style.css、图标）。
    - 选人画面用 ChrSel 图库（build_assets.py 整库导出的 `chrsel`）。
    - 本机看：`client/dev-serve.sh [端口]`（默认 18190，只听 127.0.0.1）后开 `http://127.0.0.1:18190/?local`：`net.LocalServer` 在浏览器里跑共用的世界当服务端（同一协议，指令消息都过 JSON），不用起服务端。`window.__mir` 给 browser-cli 测试：`state()`、`stick(方向,跑)`、`release()`、`tap(对象)`、`attack()`、`teleport(下标)`。
    - 和服务端的约定（#1154 评论）：HTTP `api/login`/`api/me` 登录拿 cookie（素材要登录），WebSocket 在相对路径 `ws`，一帧一条 JSON；连上服务端先发 LoggedIn；断线自动重连后直接 `EnterGame(上次的角色)`。
    - `net`：`Transport`（WsTransport / LocalServer）、`ServerClock`（进图粗定、Ping/Pong 按往返一半校正，只采信快的）。
    - `game`：`GameClient`（视野副本 + 一次性画面 + 发指令，不碰 DOM，测试里直接接 World 跑）、`SelfMotion`（本地先走：发 Walk/Run 同时本地走，MoveOk 才发下一步，MoveDenied 丢掉本地的回到服务端位置）、`Controller`（意图：摇杆走跑、点 NPC 走过去对话、点物品去捡、锁定攻击；自动喝药捡物还手烈火在服务端 AutoPlay）、`UiEvents`（给界面的一次性事件）。
    - `assets`（素材版本、图集块、地图格子 MapData、重试下载）、`render`（Frames 动作表、Renderer、Scene、Transients 特效飘字气泡）、`audio`、`input`（Joystick、Input：界面 Overlay 先接触摸和按键）。
    - `ui`（#1156，照 TS 版 ui/classic 移植，外观操作一致）：`ClassicUi`（实现 UiEvents + Overlay；画面 Lobby/Select/Game、点击登记 `Hits`、悬停说明）、窗口统一管理（#1174）：每个窗口实现 `Window`（isOpen/draw/close，可选 itemAt/hover 给悬停说明），`ClassicUi.windows` 按从下到上排好，绘制、点击叠放、悬停、closeAll 都遍历它——新加窗口只写一个实现、在列表里排位置；底栏按钮和键盘共用 `UiAction`（键位表 `UiAction.Keys` 是数据）。窗口：`MerchantPanel`（买卖存取）、`BagWindow`、`StateWindow`（4 页 `StatePage`）、`SettingsWindow`、`TeleportPanel`、`KeyDialog`（技能快捷键）、`QuantityPanel`、`ItemMenuWindow`（触屏说明+操作弹框，最上层）；常驻 HUD：`Board`（原版底栏 + 叠在上方的摇杆/攻击键/F1–F8 技能环/锁定怪信息 + 传送键）、`MiniMap`、`CharSelect`（原版选人/建角色画面，ChrSel 图库）、`ChatInput`（回车或点聊天框弹网页输入框，「!」开头喊话）、`Describe`/`SkillInfo`（物品/属性/技能说明，纯函数；#1165 数值全部调 shared 规则现算：区间用 `Rng.lowest/highest`，概率用 `Odds` 枚举随机分支，规则常量在 Rules/Recovery/PotionRules/Durability/Pricing/SpellRules/SwordRules，不在说明里抄公式；技能环长按看说明）、`Hand`（拖物品：只是界面状态，放下时发 MoveItem/UseItem/DropItem，结果以服务端为准）、`SettingRow`（设置分两类：玩法类发 UpdateSettings 存角色上，画面声音类存 localStorage）、`Lobby`（加载页；没登录先进 `LoginScene`）、`LoginScene`（#1197，原版登录画面：石门 ChrSel 22 + 登录框 prguse 60，账号密码是盖在框上的网页表单；「提交」登录、「新用户」用框里的账号密码注册，成功后石门打开 ChrSel 23-32 再淡出进选人；不经 ClassicUi，自己管画布和监听，调 `net.AccountApi`）。所有改状态的操作都发 GameCmd，画的永远是视野副本。触屏设备（pointer: coarse）点物品弹说明+操作菜单（按触屏倍数放大），电脑点一下拿起、再点放下、双击使用。
    - `window.__mir` 另有 `screen()`、`chat()`、`bag()`、`worn()`、`gold()`、`sent()`（最近发出的游戏、社交指令）、`goTo(x,y)`、`group()`（开关组队窗口）、`friends()`（开关好友窗口）。
- 测试：怪物行为（TS 版 7 项）、多人（互相看见、各打各的、掉落、换图消失、60 秒随机操作每步副本与世界一致、比奇省满图性能）、非法指令；game.json 完整解码与引用检查；规则与 TS 版逐条对照（`scala/testdata/ts-golden.json`，TS 规则改了就跑 `scala/testdata/gen-golden.sh` 重算）；协议每种消息往返（新加消息必须补样例，测试会查）。

## 怎么加技能/怪物/状态/窗口（#1175，按「加代码 + 加表」）
路径省略前缀 `scala/shared/shared/src/main/scala/` 与 `scala/client/src/main/scala/`。
- **新地图、新怪（只用已有的出招方式）、刷怪、爆率、商店**：只改数据（`tools/build_data.py` 产出的 game.json / ref 数据表），不改代码；新怪的 Race 已在 `Behavior.of` 里有对应就自动有行为，外观特征按 RaceImg 在 `mir.data.MonsterLook`。
- **加技能**：`mir.rules.Skill` 加一项（编号、用法）→ `mir.world.skills` 下对应职业写一个 `SkillHandler`（效果、起手特效）→ `SkillHandlers.of` 注册一行 → 客户端 `SkillInfo` 写说明（按 Skill 穷举，漏了编译不过）。公式放 `SpellRules`/`SwordRules`，说明从同一个函数现算。耗蓝、冷却、施法动作、熟练度由 `Combat.cast` 统一处理，不用写。
- **加怪物出招方式**：`Strike` 加分支 → `MonsterAi` 里处理（伤害一律造 `Blow` 交给 `vitals.strike`）→ `Behavior.of` 把 Race 映射过去。藏身方式同理加 `Hiding` 分支。
- **怪物找目标（#1207，照原版）**：没目标时每回合找 7 格内（宝宝 8 格）最近的敌人（远近按曼哈顿距离比）；有目标时按 `Behavior.retarget` 重找、找到更近的就换（大多数怪每 8 秒，食人花/触龙神每次能出手时，僵尸和守卫不换）；挨打时没目标、当前目标贴身或 1/6 机会就改打打它的人（`MonsterAi.struck`，宝宝能把贴身打玩家的怪拉走；守卫另按 #1205）。
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
- [x] 全景地图（#1195）：点小地图或按 M 打开，整张小地图缩放铺满屏幕，标商人、出口、视野里的怪和人；点哪走哪（client/game/Travel：先按地形算整条路再分段就近寻路，墙里的点挪到最近能走的格）
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
- [x] 极品（#1191，用户定照原版、不加倍率）：`ItemInstance.bonus: Option[ItemBonus]`（`Weapon` 攻/魔/道上限、准确、攻速可负、强度；`Gear` 加在 AC/MAC/DC/MC/SC 上限，含义仍由 `ItemEffects.of(d, bonus)` 按 StdMode 解读），旧存档没有字段为 None。只在怪物爆物时生成：`RandomUpgrade.monsterDrop`，1/10 进随机升级，各部位概率照 GameItemSystem.cs UpgradeRandom*（另加持久，只加持久不算极品）。强度按原版磨损比例降低本服武器磨损（`Durability.weaponWears`）。地上 `GroundThing.Item.bonus`（有即极品）名字亮青色（`Renderer.SpecialItemColor`），物品说明标题标「极品」并单列多了什么。原版爆物持久随机 20%–99% 本服没做。
- [x] 爆率（`Rates.dropChance`，#1241 用户 11-24 定总方向）：所有怪、所有物品原版 × `Rates.drop`（20）封顶 100%，普通怪没有保底（祖玛卫士黑铁头盔 0.22%）；boss（`Rates.bosses` 手写名单 → `MonsterRank.Boss`：沃玛/祖玛/黄泉/虹魔教主、千年树妖、石墓尸王、牛魔王、赤月恶魔、双头金刚、双头血魔及其「暗之」版本；守卫、城墙不算）的高级装备（20 级以上或要求攻/魔/道）至少 `bossHighEquipMin` 10%（祖玛教主力量戒指 1/50×20=40%，裁决之杖 1/6000×20 → 10%）。每只怪一次最多掉 `Rates.maxDropsPerKill`（10）件（#1241）：先全掷完，多了按原版爆率从低到高留（`DropRules.cap`，同爆率按表顺序），可叠物一叠算一件，金币不占名额。TS 版只同步了掉率，没做 10 件上限。
- [x] 高级物品提醒（#1200，用户定）：`rules.Upgrade.gain` 算一件装备换下身上同部位那件后本职业主属性（战士攻击/法师魔法/道士道术）下限+上限之和净多多少；不看现在穿不穿得上等级/属性要求（#1215 用户定：只要有净收益就提示，要求在说明里「需要道术上限 25（你现在 16）」标红），只排除性别不符的衣服；戒指手镯和较弱那只比、空位算 0、护身符占的手镯位不算、持久 0 的按 0。地上物品带 bonus 下发，客户端 `GameClient.groundUpgrades` 算出名字换粉紫 `Renderer.UpgradeItemColor`（极品又是提升时用它），设置里本机开关 `ViewOptions.upgradeHint`（默认开）；包裹物品说明标题换色、第二行写「比身上的 X 攻击高 N」。
- [x] 护身符、药粉放包裹里就能用（#1202，用户定，原版要戴左手镯位）：`SpellCtx.charm` / `poisonPowder` 先看左手镯位，再按包裹格子顺序找第一份合要求的；施毒术两种都带着时按 `Player.lastPoison` 换上次没下的那种（红绿交替，用户 11-24 定），绿毒红毒是两个状态 `StatusKind.GreenPoison/RedPoison` 可同时在身上，返回 `AmuletAt(位置)`，`consume` 只扣那一份的次数，用完拿掉。
- [x] 护身符、药粉按次叠成一格（#1213，用户定）：`rules.Charges`——这类物品的数量就是剩余次数（新拿到的一份 = 物品表持久÷100 次，护身符 300、药粉 100–500），持久固定 100；同名并叠、拆分、数量框都走 #1159 那套，`consume` 数量减 1、到 0 拿掉；左手镯位整叠戴上，`Unequip` 不指定格子时并回包裹那一叠；卖价按次折算（`Pricing.sell(d, n)` = 一份卖价×n÷每份次数）；旧存档（一份一格、次数在持久里）进图时 `Inventory.normalize` 经 `Charges.settle` 换算后合并，settle 可重复做不变。同名才叠（灰色药粉少量/中量/大量各一格）。
- [x] 施毒术数值（#1212，用户 11-24 定）：`SpellRules.poisonDose` 只掷一次道术随机值 s，按技能等级 L 确定地算：绿毒持续 GetPower13(40)+2s 秒、每 2 秒掉 s/2+L+1 血；红毒持续 GetPower13(30)+2s 秒、挨打伤害加深 10+5L+s/2 %；已中同种毒再下，强度取较大的、时长从现在重算、下一跳不推迟；必中（不掷毒物躲避）。
- [x] 道术加成宝宝（#1209，用户定，原版没有）：召唤骷髅/神兽那一刻按主人道术上限每点 +2%、封顶 +60%（`SpellRules.summonScBoost`），乘血、攻、防、魔防；技能等级加成（`summonBoost`）仍只乘血和攻，两者相乘，算法在 `SpellRules.summonedBody`（技能说明共用）。宝宝身板 `PetBody`（血上限、攻、防、魔防，`Monster.baseAc/baseMac` + `Vitals.shape`）召唤时定下、换装不变，换图经 `PetData.body` 带走；诱惑之光收服的不加。
- [x] 组队（#1220，照原版）：见上面 `mir.social`。
- [x] 交易（#1222）：见上面「交易」；双方在同一张图，放在 `mir.world.Trades` / `GameCmd`，不走 `mir.social`。
- [x] 私聊、好友（#1221）：见上面 `mir.social`。
- [x] 查看别人装备（#1263，原版 CM_QUERYUSERSTATE / SM_SENDUSERSTATE、FState.pas DUserState1）：锁定别的玩家后信息卡下一排键里的「装备」发 `GameCmd.Inspect(对象)`，世界（`PlayerOps.inspect`）核对同图、视野内（`World.ViewRange`）、活着的别的玩家，回 `ServerMsg.Inspected(InspectView(名字, 性别, 发型, PK 身份, 穿戴中的装备))`，不含包裹；否则 Refused。客户端 `ui.InspectWindow`：状态窗同底图（Main 370），摆在自己状态窗左边（原版 SCREENWIDTH − 2×窗宽），人形和装备格与自己状态窗共用 `StateWindow.drawFigure`；名字按 PK 身份上色；电脑悬停、手机点格子看物品说明（和自己同部位比较照常）。是点那一刻的快照，再点一次刷新。没有行会，不显示行会名。
- [x] PK（#1225，用户 11-24 定：安全区外玩家可互相攻击、不分攻击模式、不做监狱；其余照原版 M2Server 默认配置）：规则 `mir.rules.Pk`（PK 值、名字身份 `PkStanding` 清白/灰/黄/红、红名死亡掉落）和 `SafeZone`（StartPoint.txt 出生点横竖 10 格，1.76 数据没有 SAFE 图）；谁能打谁 `Look.duel`（玩家打别的玩家、别人的宝宝，双方都在安全区外；服务端 `Justice.duel` 与客户端 `GameClient.canAttack` 共用；服务端另加进图/传送 3 秒保护）。玩家之间不算 `isEnemy`：自动还手、自动找怪、攻击键没锁定时都不会打人；锁定玩家后攻击键、攻击技能打他；范围攻击按 `harms` 筛，队友也误伤，自己的宝宝不伤。`Justice`：打清白的人（或他的宝宝）变灰 60 秒（`Status.Brawler`，换图带着）；杀不是红名也不是灰名的 PK 值 +100（宝宝杀的算主人），杀灰名正当防卫、杀红名都不加；在线每 2 分钟降 1（下次降的时刻随 `Carried` 换图带走，重新登录重计）；红名（≥200）死了包裹含快捷栏全掉、身上每件 1/30（≥300 为 1/15），不管谁杀的；不是红名死了不掉。守卫（卫士、弓箭手）见红名就砍（`Look.hostile`）。宝宝护主（`Justice.petDefends`，原版 IsAttackTarget）：主人 30 秒内挨过谁打、主人正打着谁、谁正打着主人或这只宝宝，就打谁。竞技地图（`MapFlag.Fight`）打人杀人不算、不掉东西；1.76 数据里带它的 78 张是各城的店铺、客栈、仓库、房屋等室内图，#1230 用户定「店里算安全」：除竞技场 0137（`SafeZone.Arenas`）外整张算安全区（`SafeZone.wholeMap`），客户端按自己数据表里的 `MapDef` 同一条判定。存档 `CharacterData.pkPoints`（旧档 0），`SelfStats.pkPoints` 显示在状态窗属性页，`Look.Human.pk` 决定名字颜色（灰 #a8a8a8、黄、红）。没做：红名复活回红名村、杀人扣幸运（没有人物幸运值）。
- [x] 神兽照原版（#1255，ObjMon.pas TElfMonster / TElfWarriorMonster，客户端 AxeMon.pas TWarriorElfMonster）：召出来是小神兽（Race 113，`Strike.Hold` 不出手、跟着主人），它有目标或主人按住攻击打着谁、30 秒内挨过别的玩家打，就原地变成「神兽1」（Race 114）；神兽1 连续 `SpellRules.ShinsuCalmMs` 60 秒没仗打就钻下去（客户端倒放 ActCritical，`MonsterLook.digDownReversed`）变回小神兽。变身是 `Extra.Shapeshift(变成谁, ShiftWhen)` 本领，`Monster.morph` 原地换数据表、行为、画法（同一个对象编号，客户端按 LookChanged 换图），身板按 `Monster.summoning`（召唤时的技能等级、道术上限，随 `PetData` 换图带走）和新形态底数重算，血按比例；两种形态出现时都钻出来，走路/出手间隔 `SpellRules.shinsuWalkMs/shinsuHitMs`（500−等级×50、1500−等级×100）。神兽1 出招 `Strike.Spit(poison = false)`：朝目标方向身前 2 格判命中的魔法伤害，攻击第 5 帧放 Mon18 821+10×方向 起 5 帧喷火（`AttackFx.atFrame/frameTime`）。同种 3 只的名额按 `SpellRules.summonedAs` 算（神兽1 算神兽）。
- [ ] 门（doorIndex）；按地图回城点（现在一律回比奇）
