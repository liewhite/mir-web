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

## 进度
- [x] 找素材、找数据、核实平台存储限制
- [x] WIL/WZL/MAP 解析，比奇地图渲染验证
- [x] 资源管线（tools/build_assets.py、build_data.py；改素材后 bump_version）
- [x] 引擎骨架、战斗/怪物/掉落/升级（战士验证过打鸡）
- [x] 界面：用户要求「尽量接近原版 UI」→ ui/classic 照 FState.pas 移植（底栏、状态窗4页、包裹、商人、菜单、出售/保管、快捷键、设置）
- [x] 服务端 server/server.mjs（登录、存档、ETag 缓存）；本机 dev：`set -a; . server/dev.env` 后 node server.mjs，密码 test
- [x] 技能特效校准（tools/sheet.py 出帧号对照图）、骷髅洞、触屏技能按钮
- [x] 部署 kaojiang：`mir/deploy/deploy.sh`（--init 首次）；systemd mir176（/opt/mir176，监听 cni0 10.42.0.1:18176），k3s ns mir176 Ingress；存档 /var/lib/mir176；密码 vault MIR176_PASSWORD
- [x] 小地图、音效（tools/build_sounds.py → assets/sound/*.m4a）、远程怪、首屏预加载、物件 10 帧分块
- [x] 手机布局（#1140，用户定）：`ui/viewport.ts` 按屏幕选布局——电脑/平板用原版底栏（`ui/classic/layout.ts` BoardLayout），触屏且短边 < 600 用手机布局（`ui/classic/touch.ts`）：界面与地图分开缩放、左半屏虚拟摇杆（`game/joystick.ts`）、点怪锁定并显示怪物信息、攻击键+F1–F8 技能环、物品点一下弹说明和操作菜单；安全区、添加到主屏幕全屏
- [x] 第二阶段地图（#1139）：从比奇沿传送点走得到的 188 张地图全上（毒蛇山谷、盟重、沃玛森林/寺庙、石墓、祖玛、矿区、地牢等）；内容相同的地图共用素材（MapDef.asset），素材约 2.4G。
- [x] 第三阶段地图（#1144，用户定「都加进去」）：数据表里有地图文件的 265 张全上（素材 3.6G），没路进去的白日门、封魔谷、苍月岛作为城市、未知暗殿和不归路作为独立地区进传送菜单（build_data.py TOWNS / ENTRIES），赤月、封魔殿、尸魔洞、骨魔洞、牛魔寺庙等按城后的路分组。
- [x] 怪物行为（#1139）：game/src/world/monster_ai.ts 按 Race 描述出招/藏身/附加本领（食人花钻地、石像、僵尸电光与复活、洞蛆麻痹、蜘蛛喷毒、角蝇巢/幻影蜘蛛放小怪、爆裂蜘蛛自爆、沃玛教主狂暴瞬移、祖玛教主召唤、赤月恶魔/触龙神范围攻击）；测试 `cd game && npm test`
- [x] 传送菜单（#1139，用户要求）：左上「传送」键或 T 键，分组列城中心和各打怪地区入口（tools/build_data.py teleports；ui/classic/teleport.ts）
- [ ] 门（doorIndex）；按地图回城点（现在一律回比奇）
