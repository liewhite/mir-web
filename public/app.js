// src/assets/sprites.ts
var assetVersion = "0";
async function loadAssetVersion() {
  try {
    const r2 = await fetch("assets/version.json", { cache: "no-store" });
    if (r2.ok) assetVersion = String((await r2.json()).v);
  } catch {
  }
}
function assetUrl(path) {
  return `${path}?v=${assetVersion}`;
}
function blockSize(lib) {
  const base = lib.replace(/\d+$/, "");
  if (base === "hum" || base === "weapon" || base === "hair") return 600;
  if (base === "objects" || base === "stateitem" || base === "mmap" || base === "npc") return 10;
  return 200;
}
function loadImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.decoding = "async";
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error(`\u56FE\u7247\u52A0\u8F7D\u5931\u8D25 ${src}`));
    img.src = src;
  });
}
async function fetchJsonRetry(url, tries = 4) {
  let last;
  for (let i = 0; i < tries; i++) {
    try {
      const r2 = await fetch(url);
      if (r2.status === 404) throw Object.assign(new Error("404"), { notFound: true });
      if (!r2.ok) throw new Error(`${url} ${r2.status}`);
      return await r2.json();
    } catch (e) {
      if (e.notFound) throw e;
      last = e;
      await new Promise((res) => setTimeout(res, 800 * (i + 1)));
    }
  }
  throw last;
}
var SpriteStore = class {
  constructor(base = "assets/img") {
    this.base = base;
    this.blocks = /* @__PURE__ */ new Map();
    /** 有新块加载完成时调用（渲染器据此知道该重画）。 */
    this.onLoaded = () => {
    };
  }
  /** 取一帧；没加载就开始加载并返回 undefined；帧不存在返回 null。 */
  get(lib, index) {
    if (index < 0) return null;
    const size = blockSize(lib);
    const b = Math.floor(index / size);
    const block = this.block(lib, b);
    if (block.state === "ready") return block.sprites[index - b * size] ?? null;
    if (block.state === "missing") return null;
    return void 0;
  }
  /** 预加载一组帧所在的块。 */
  async preload(lib, indices) {
    const size = blockSize(lib);
    const blocks = /* @__PURE__ */ new Set();
    for (const i of indices) blocks.add(Math.floor(i / size));
    await Promise.all([...blocks].map((b) => this.whenReady(lib, b)));
  }
  async whenReady(lib, b) {
    const block = this.block(lib, b);
    if (block.state === "loading") await block.promise;
  }
  block(lib, b) {
    const key = `${lib}/${b}`;
    const cur = this.blocks.get(key);
    if (cur) return cur;
    const promise = this.loadBlock(lib, b).then(
      (sprites) => {
        this.blocks.set(key, { state: "ready", sprites });
        this.onLoaded();
      },
      (e) => {
        console.warn("\u56FE\u96C6\u5757\u52A0\u8F7D\u5931\u8D25", key, e);
        this.blocks.set(key, { state: "missing" });
      }
    );
    const loading = { state: "loading", promise };
    this.blocks.set(key, loading);
    return loading;
  }
  async loadBlock(lib, b) {
    const meta = await fetchJsonRetry(assetUrl(`${this.base}/${lib}/${b}.json`));
    const pages = [];
    for (let p = 0; p < meta.pages; p++) {
      pages.push(await loadImage(assetUrl(`${this.base}/${lib}/${b}_${p}.png`)));
    }
    return meta.frames.map((f) => {
      if (f === 0) return null;
      const [page, sx, sy, w, h, ox, oy] = f;
      const img = pages[page];
      return img ? { img, sx, sy, w, h, ox, oy } : null;
    });
  }
};

// src/data/defs.ts
var Catalog = class {
  constructor(data) {
    this.data = data;
    this.itemsByName = /* @__PURE__ */ new Map();
    this.itemsById = /* @__PURE__ */ new Map();
    this.monstersByName = /* @__PURE__ */ new Map();
    this.skillsById = /* @__PURE__ */ new Map();
    this.skillsByName = /* @__PURE__ */ new Map();
    this.mapsById = /* @__PURE__ */ new Map();
    for (const it of data.items) {
      this.itemsByName.set(it.name, it);
      this.itemsById.set(it.id, it);
    }
    for (const m of data.monsters) this.monstersByName.set(m.name, m);
    for (const s of data.skills) {
      this.skillsById.set(s.id, s);
      this.skillsByName.set(s.name, s);
    }
    for (const m of data.maps) this.mapsById.set(m.id, m);
  }
  item(name) {
    const it = this.itemsByName.get(name);
    if (!it) throw new Error(`\u6CA1\u6709\u7269\u54C1 ${name}`);
    return it;
  }
  map(id) {
    const m = this.mapsById.get(id);
    if (!m) throw new Error(`\u6CA1\u6709\u5730\u56FE ${id}`);
    return m;
  }
  /** 升到下一级要的经验；满级返回 Infinity。 */
  expToNext(level) {
    return this.data.exp[String(level)] ?? Infinity;
  }
  displayName(monster) {
    return this.data.displayNames[monster] ?? monster;
  }
};
async function loadCatalog() {
  const r2 = await fetch(assetUrl("assets/data/game.json"));
  if (!r2.ok) throw new Error(`game.json ${r2.status}`);
  return new Catalog(await r2.json());
}

// src/assets/mapdata.ts
var UNITX = 48;
var UNITY = 32;
var GROUND_BLOCK = 16;
var MapData = class _MapData {
  constructor(id, asset, width, height, flags, front, area, ani, aniTick, doorIndex, doorOffset, light) {
    this.id = id;
    this.asset = asset;
    this.width = width;
    this.height = height;
    this.flags = flags;
    this.front = front;
    this.area = area;
    this.ani = ani;
    this.aniTick = aniTick;
    this.doorIndex = doorIndex;
    this.doorOffset = doorOffset;
    this.light = light;
  }
  inside(x, y) {
    return x >= 0 && y >= 0 && x < this.width && y < this.height;
  }
  /** 地形可走（不看人物占位）。 */
  walkable(x, y) {
    return this.inside(x, y) && (this.flags[y * this.width + x] & 1) === 0;
  }
  /** 魔法、弓箭能否穿过。 */
  flyable(x, y) {
    return this.inside(x, y) && (this.flags[y * this.width + x] & 2) === 0;
  }
  static parse(def, buf) {
    const id = def.id;
    const dv = new DataView(buf);
    const magic = String.fromCharCode(dv.getUint8(0), dv.getUint8(1), dv.getUint8(2), dv.getUint8(3));
    if (magic !== "M176") throw new Error(`\u5730\u56FE ${id} \u683C\u5F0F\u4E0D\u5BF9`);
    const w = dv.getUint16(4, true);
    const h = dv.getUint16(6, true);
    const n = w * h;
    let off = 8;
    const u8 = () => {
      const a = new Uint8Array(buf, off, n);
      off += n;
      return a;
    };
    const flags = u8();
    const front = new Uint16Array(n);
    for (let i = 0; i < n; i++) front[i] = dv.getUint16(off + i * 2, true);
    off += n * 2;
    return new _MapData(id, def.asset, w, h, flags, front, u8(), u8(), u8(), u8(), u8(), u8());
  }
};
async function loadMap(def) {
  for (let i = 0; ; i++) {
    try {
      const r2 = await fetch(assetUrl(`assets/map/${def.asset}.bin`));
      if (!r2.ok) throw new Error(`\u5730\u56FE ${def.title}: ${r2.status}`);
      return MapData.parse(def, await r2.arrayBuffer());
    } catch (e) {
      if (i >= 4) throw e;
      await new Promise((res) => setTimeout(res, 1e3 * (i + 1)));
    }
  }
}

// src/world/entities.ts
var ActorBase = class {
  constructor(id, map, x, y, now) {
    this.id = id;
    this.dir = 4;
    this.hp = 1;
    this.mp = 0;
    /** 下一次能接受新动作的时间 */
    this.busyUntil = 0;
    this.dead = false;
    this.deadAt = 0;
    this.poison = null;
    /** 魔法盾 / 隐身 / 神圣战甲 / 幽灵盾 等状态的结束时间 */
    this.shieldUntil = 0;
    this.shieldLevel = 0;
    this.invisibleUntil = 0;
    /** 麻痹（原版 STONE 状态）到什么时候：期间不能动 */
    this.paralyzedUntil = 0;
    /** 最近打过我的角色，用于反击和经验归属 */
    this.lastHitBy = 0;
    this.map = map;
    this.x = x;
    this.y = y;
    this.action = { kind: "stand", startedAt: now, duration: 0 };
  }
  get pos() {
    return { x: this.x, y: this.y };
  }
};
var BAG_SIZE = 40;
var Player = class extends ActorBase {
  constructor(id, name, job, sex, map, x, y, now) {
    super(id, map, x, y, now);
    this.kind = "player";
    this.level = 1;
    this.exp = 0;
    this.gold = 0;
    /** 包裹 40 格（原版 8x5；另 6 格是快捷栏 belt） */
    this.bag = new Array(BAG_SIZE).fill(null);
    this.belt = new Array(6).fill(null);
    this.storage = [];
    this.equip = {
      weapon: null,
      dress: null,
      helmet: null,
      necklace: null,
      braceletL: null,
      braceletR: null,
      ringL: null,
      ringR: null
    };
    this.skills = [];
    /** 战士：攻杀剑术计数、烈火是否已蓄好、刺杀/半月开关 */
    this.powerHitReady = false;
    this.powerHitCount = 0;
    this.powerHitPoint = 0;
    this.fireReady = false;
    this.fireCooldownUntil = 0;
    this.longHitOn = true;
    this.wideHitOn = false;
    /** 技能冷却：技能 id → 可再用时间 */
    this.cooldown = /* @__PURE__ */ new Map();
    /** 宝宝：召唤物 id */
    this.pets = [];
    this.lastRegen = 0;
    this.name = name;
    this.job = job;
    this.sex = sex;
    this.hair = 1;
    this.home = { map, x, y };
  }
};
var Monster = class extends ActorBase {
  constructor(id, def, spawn, map, x, y, now) {
    super(id, map, x, y, now);
    this.kind = "monster";
    this.target = 0;
    this.nextThink = 0;
    this.lastAttack = 0;
    /** 召唤物或被诱惑的怪：主人的 id */
    this.master = 0;
    /** 宝宝等级（0-3，影响属性） */
    this.petLevel = 0;
    /** 尸体消失时间 */
    this.removeAt = 0;
    /** 被打到时短暂停手 */
    this.stunnedUntil = 0;
    /** 藏身状态：open 在外面；buried 埋在地下（看不见、打不到）；stone 石像（打不到）。见 monster_ai.ts */
    this.cover = "open";
    /** 最近一次钻出/钻回/醒来的时间 */
    this.coverAt = 0;
    this.bornAt = 0;
    /** 召唤出来的小怪 */
    this.minions = [];
    /** 还能复活几次、几时复活（0 = 不复活） */
    this.revivesLeft = 0;
    this.reviveAt = 0;
    /** 打死给的经验倍数（复活一次减半） */
    this.expScale = 1;
    /** 祖玛教主召唤档位、沃玛教主狂暴计数 */
    this.danger = 5;
    this.rage = "calm";
    this.rageAt = 0;
    this.extraTick = 0;
    this.def = def;
    this.spawn = spawn;
    this.maxHp = def.hp;
    this.hp = def.hp;
    this.ac = def.ac;
    this.mac = def.mac;
    this.dc = def.dc;
    this.hit = def.hit;
    this.agility = def.speed;
    this.attackInterval = def.attackSpeed;
    this.walkInterval = def.walkSpeed;
    this.dir = Math.floor(Math.random() * 8);
  }
};
var Npc = class extends ActorBase {
  constructor(id, def, now) {
    super(id, def.map, def.x, def.y, now);
    this.def = def;
    this.kind = "npc";
    this.hp = 1;
  }
};
function isGold(it) {
  return "gold" in it;
}

// src/world/items.ts
var EQUIP_SLOTS = [
  "weapon",
  "dress",
  "helmet",
  "necklace",
  "braceletL",
  "braceletR",
  "ringL",
  "ringR"
];
function itemKind(d) {
  switch (d.mode) {
    case 0:
      return { kind: "potion", hp: d.ac[0], mp: d.mac[0], instant: d.shape === 1 };
    case 3:
      return {
        kind: "scroll",
        effect: d.shape === 1 ? "escape" : d.shape === 2 ? "random" : d.shape === 3 ? "town" : "other"
      };
    case 4:
      return { kind: "book", job: d.shape, level: d.dura };
    case 31:
      return { kind: "bundle", of: d.shape };
    case 5:
    case 6:
      return { kind: "equip", slots: ["weapon"] };
    case 10:
    case 11:
      return { kind: "equip", slots: ["dress"] };
    case 15:
      return { kind: "equip", slots: ["helmet"] };
    case 19:
    case 20:
    case 21:
      return { kind: "equip", slots: ["necklace"] };
    case 22:
    case 23:
      return { kind: "equip", slots: ["ringL", "ringR"] };
    case 24:
    case 26:
      return { kind: "equip", slots: ["braceletL", "braceletR"] };
    case 25:
      return { kind: "amulet", poison: d.shape === 1 ? 1 : d.shape === 2 ? 2 : 0 };
    case 42:
    case 43:
      return { kind: "material" };
    default:
      return { kind: "other" };
  }
}
var ZERO = [0, 0];
function emptyEffects() {
  return {
    ac: ZERO,
    mac: ZERO,
    dc: ZERO,
    mc: ZERO,
    sc: ZERO,
    hit: 0,
    agility: 0,
    attackSpeed: 0,
    luck: 0,
    curse: 0,
    magicAvoid: 0,
    poisonAvoid: 0,
    hpRegen: 0,
    mpRegen: 0,
    poisonRegen: 0
  };
}
function weaponSpeed(v) {
  return v <= 10 ? -v : v - 10;
}
function itemEffects(d) {
  const e = emptyEffects();
  e.dc = d.dc;
  e.mc = d.mc;
  e.sc = d.sc;
  const [acLo, acHi] = d.ac;
  const [macLo, macHi] = d.mac;
  switch (d.mode) {
    case 5:
    case 6:
      e.hit = acHi;
      e.attackSpeed = weaponSpeed(macHi);
      e.luck = acLo;
      e.curse = macLo;
      break;
    case 19:
      e.magicAvoid = acHi;
      e.curse = macLo;
      e.luck = macHi;
      break;
    case 20:
    case 24:
      e.hit = acHi;
      e.agility = macHi;
      break;
    case 21:
      e.hpRegen = acHi;
      e.mpRegen = macHi;
      e.attackSpeed = acLo - macLo;
      break;
    case 23:
      e.poisonAvoid = acHi;
      e.poisonRegen = macHi;
      e.attackSpeed = acLo - macLo;
      break;
    case 0:
    case 3:
    case 4:
    case 25:
    case 31:
      break;
    // 消耗品：AC/MAC 是药效，不是属性
    default:
      e.ac = d.ac;
      e.mac = d.mac;
  }
  return e;
}
function requirement(d) {
  const stat = ["level", "dc", "mc", "sc"][d.need] ?? "level";
  return { stat, value: d.needLevel };
}
function dressSex(d) {
  return d.mode === 10 ? 0 : d.mode === 11 ? 1 : null;
}
var nextUid = 1;
function setNextUid(n) {
  nextUid = Math.max(nextUid, n);
}
function newItem(d) {
  return { uid: nextUid++, name: d.name, dura: d.dura, duraMax: d.dura };
}

// src/core/geom.ts
var DX = [0, 1, 1, 1, 0, -1, -1, -1];
var DY = [-1, -1, 0, 1, 1, 1, 0, -1];
function step(p, dir, n = 1) {
  return { x: p.x + DX[dir] * n, y: p.y + DY[dir] * n };
}
function dirTo(from, to) {
  const dx = Math.sign(to.x - from.x);
  const dy = Math.sign(to.y - from.y);
  for (let d = 0; d < 8; d++) if (DX[d] === dx && DY[d] === dy) return d;
  return 4;
}
function dir16(from, to) {
  const a = Math.atan2((to.x - from.x) * 48, -(to.y - from.y) * 32);
  return (Math.round(a / (Math.PI / 8)) % 16 + 16) % 16;
}
function chebyshev(a, b) {
  return Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
}
function samePos(a, b) {
  return a.x === b.x && a.y === b.y;
}
var ALL_DIRS = [0, 1, 2, 3, 4, 5, 6, 7];
function opposite(d) {
  return (d + 4) % 8;
}

// src/world/rules.ts
var JOB_NAME = ["\u6218\u58EB", "\u6CD5\u5E08", "\u9053\u58EB"];
function random(n) {
  return n > 0 ? Math.floor(Math.random() * n) : 0;
}
function roll(r2) {
  const [lo, hi] = r2;
  return lo + random(Math.abs(hi - lo) + 1);
}
var round = (x) => Math.round(x);
function levelAbility(job, lv) {
  const n7 = Math.floor(lv / 7);
  switch (job) {
    case 2: {
      const n6 = round(lv / 6);
      return {
        maxHp: 14 + round((lv / 6 + 2.5) * lv),
        maxMp: 13 + round(lv / 8 * 2.2 * lv),
        maxBagWeight: 50 + round(lv / 4 * lv),
        maxWearWeight: Math.min(255, 15 + round(lv / 50 * lv)),
        maxHandWeight: Math.min(255, 12 + round(lv / 42 * lv)),
        dc: [Math.max(n7 - 1, 0), Math.max(1, n7)],
        mc: [0, 0],
        sc: [Math.max(n7 - 1, 0), Math.max(1, n7)],
        ac: [0, 0],
        mac: [Math.floor(n6 / 2), n6 + 1]
      };
    }
    case 1:
      return {
        maxHp: 14 + round((lv / 15 + 1.8) * lv),
        maxMp: 13 + round((lv / 5 + 2) * 2.2 * lv),
        maxBagWeight: 50 + round(lv / 5 * lv),
        maxWearWeight: Math.min(255, 15 + round(lv / 100 * lv)),
        maxHandWeight: Math.min(255, 12 + round(lv / 90 * lv)),
        dc: [Math.max(n7 - 1, 0), Math.max(1, n7)],
        mc: [Math.max(n7 - 1, 0), Math.max(1, n7)],
        sc: [0, 0],
        ac: [0, 0],
        mac: [0, 0]
      };
    case 0:
      return {
        maxHp: 14 + round((lv / 4 + 4.5 + lv / 20) * lv),
        maxMp: 11 + round(lv * 3.5),
        maxBagWeight: 50 + round(lv / 3 * lv),
        maxWearWeight: Math.min(255, 15 + round(lv / 20 * lv)),
        maxHandWeight: Math.min(255, 12 + round(lv / 13 * lv)),
        dc: [Math.max(Math.floor(lv / 5) - 1, 1), Math.max(1, Math.floor(lv / 5))],
        mc: [0, 0],
        sc: [0, 0],
        ac: [0, n7],
        mac: [0, 0]
      };
  }
}
var addR = (a, b) => [Math.min(255, a[0] + b[0]), Math.min(255, a[1] + b[1])];
function computeStats(job, level, equips, passive) {
  const base = levelAbility(job, level);
  let s = {
    maxHp: base.maxHp,
    maxMp: base.maxMp,
    ac: base.ac,
    mac: base.mac,
    dc: base.dc,
    mc: base.mc,
    sc: base.sc,
    hit: 5,
    agility: job === 2 ? 18 : 15,
    hitInterval: 900,
    luck: 0,
    magicAvoid: 0,
    poisonAvoid: 0,
    hpRegen: 0,
    mpRegen: 0,
    maxBagWeight: base.maxBagWeight,
    maxWearWeight: base.maxWearWeight,
    maxHandWeight: base.maxHandWeight
  };
  let speed = 0;
  for (const e of equips) {
    s = {
      ...s,
      ac: addR(s.ac, e.ac),
      mac: addR(s.mac, e.mac),
      dc: addR(s.dc, e.dc),
      mc: addR(s.mc, e.mc),
      sc: addR(s.sc, e.sc),
      hit: s.hit + e.hit,
      agility: s.agility + e.agility,
      luck: s.luck + e.luck - e.curse,
      magicAvoid: s.magicAvoid + e.magicAvoid,
      poisonAvoid: s.poisonAvoid + e.poisonAvoid,
      hpRegen: s.hpRegen + e.hpRegen,
      mpRegen: s.mpRegen + e.mpRegen
    };
    speed += e.attackSpeed;
  }
  if (passive.oneSword > 0) s.hit += round(9 / 3 * passive.oneSword);
  if (passive.spiritSword > 0) s.hit += round(8 / 3 * passive.spiritSword);
  if (passive.powerHit > 0) s.hit += passive.powerHit;
  const hitSpeed = Math.min(15, speed > 0 ? Math.floor(speed / 2) : Math.trunc((speed - 1) / 2));
  s.hitInterval = Math.max(300, 900 - hitSpeed * 60);
  return s;
}
function hits(attackerHit, defenderAgility) {
  return !(attackerHit < random(defenderAgility));
}
function afterArmor(power2, ac) {
  return Math.max(0, power2 - roll(ac));
}
function afterMagicArmor(power2, mac) {
  return Math.max(0, power2 - roll(mac));
}
function hitChance(h, a) {
  return a <= 0 ? 1 : Math.min(1, (h + 1) / a);
}
function skillPower(base, level, defPower) {
  return round(base / 4 * (level + 1)) + defPower[0] + random(defPower[1] - defPower[0]);
}
function skillPower13(n, level, defPower) {
  const d10 = n / 3;
  const d18 = n - d10;
  return round(d18 / 4 * (level + 1) + d10 + (defPower[0] + random(defPower[1] - defPower[0])));
}
function magicBase(power2) {
  return power2[0] + random(power2[1] - power2[0]);
}
function rpow(r2) {
  return r2[1] > r2[0] ? random(r2[1] - r2[0] + 1) + r2[0] : r2[0];
}
function attackPower(base, spread) {
  return base + random(Math.max(0, spread) + 1);
}

// src/world/path.ts
function findPath(map, from, to, maxNodes = 4e3, stopAdjacent = false) {
  const key = (x, y) => y << 16 | x & 65535;
  const h = (x, y) => Math.max(Math.abs(x - to.x), Math.abs(y - to.y));
  const open = [{ x: from.x, y: from.y, g: 0, f: h(from.x, from.y), parent: null }];
  const best = /* @__PURE__ */ new Map([[key(from.x, from.y), 0]]);
  let visited = 0;
  let closest = open[0];
  while (open.length && visited < maxNodes) {
    let bi = 0;
    for (let i = 1; i < open.length; i++) if (open[i].f < open[bi].f) bi = i;
    const cur = open.splice(bi, 1)[0];
    visited++;
    const d = h(cur.x, cur.y);
    if (d === 0 || stopAdjacent && d === 1) return unwind(cur);
    if (d < h(closest.x, closest.y)) closest = cur;
    for (const dir of ALL_DIRS) {
      const n = step(cur, dir);
      const isGoal = n.x === to.x && n.y === to.y;
      if (!isGoal && !map.canEnter(n.x, n.y)) continue;
      if (isGoal && !stopAdjacent && !map.canEnter(n.x, n.y)) continue;
      const g = cur.g + 1;
      const k = key(n.x, n.y);
      if ((best.get(k) ?? Infinity) <= g) continue;
      best.set(k, g);
      open.push({ x: n.x, y: n.y, g, f: g + h(n.x, n.y), parent: cur });
    }
  }
  return closest.parent ? unwind(closest) : null;
}
function unwind(n) {
  const dirs = [];
  let cur = n;
  while (cur && cur.parent) {
    dirs.push(dirTo(cur.parent, cur));
    cur = cur.parent;
  }
  return dirs.reverse();
}

// src/world/rates.ts
var RATES = {
  /** 打怪经验倍数 */
  exp: 10,
  /** 非装备物品（金币、药、书、材料）的掉落概率倍数，最高 100% */
  drop: 20,
  /** 高级装备（要求 20 级以上，或要求攻击/魔法/道术）的掉落概率下限 */
  highEquipMin: 0.2,
  highEquipLevel: 20
};
function dropChance(d, def) {
  const base = d.numerator / d.denominator;
  const scaled = Math.min(1, base * RATES.drop);
  return def && isHighEquip(def) ? Math.max(scaled, RATES.highEquipMin) : scaled;
}
function isHighEquip(def) {
  return itemKind(def).kind === "equip" && (def.need !== 0 || def.needLevel >= RATES.highEquipLevel);
}

// src/world/sounds.ts
var S = {
  walkGround: [1, 2],
  runGround: [3, 4],
  walkCave: [21, 22],
  runCave: [23, 24],
  hitFist: 57,
  money: 106,
  eatDrug: 107,
  spaceOut: 109,
  spaceIn: 110,
  itemClick: 118,
  glassButton: 105,
  yedoMan: 130,
  yedoWoman: 131,
  longHit: 132,
  wideHit: 133,
  fireHitReady: 136,
  fireHit: 137,
  manStruck: 138,
  womStruck: 139,
  manDie: 144,
  womDie: 145
};
function weaponSwing(shape) {
  if (shape === null) return S.hitFist;
  if ([6, 20].includes(shape)) return 50;
  if (shape === 1) return 51;
  if ([2, 13, 9, 5, 14, 22].includes(shape)) return 52;
  if ([4, 17, 10, 15, 16, 23].includes(shape)) return 53;
  if ([3, 7, 11].includes(shape)) return 54;
  if (shape === 24) return 55;
  if ([8, 12, 18, 21].includes(shape)) return 56;
  return S.hitFist;
}
function struckBody(shape) {
  if (shape === null) return 73;
  if ([6, 1, 2, 4, 5, 9, 10, 13, 14, 15, 16, 17].includes(shape)) return 70;
  if ([3, 11].includes(shape)) return 71;
  if ([8, 12, 18].includes(shape)) return 72;
  return 73;
}
function monsterSound(appr, k) {
  return 200 + appr * 10 + k;
}
function magicSound(id, k) {
  return 1e4 + id * 10 + k;
}

// src/world/combat.ts
function skillOf(p, id) {
  return p.skills.find((s) => s.id === id);
}
function train(w, p, s, amount) {
  const def = w.catalog.skillsById.get(s.id);
  if (!def || s.level >= 3) return;
  if (p.level < def.needLevel[s.level]) return;
  s.train += amount;
  if (s.train >= def.train[s.level]) {
    s.train = 0;
    s.level++;
    w.recalc(p);
    w.events.push({ type: "msg", text: `${def.name} \u5347\u5230 ${s.level} \u7EA7`, color: "good" });
  }
}
function physicalHit(w, p, t, power2, ignoreArmor) {
  if (t.kind === "npc" || !w.isEnemy(p, t)) return false;
  const agility = t.kind === "monster" ? t.agility : t.stats.agility;
  if (!hits(p.stats.hit, agility)) {
    w.events.push({ type: "miss", target: t.id });
    return false;
  }
  const ac = t.kind === "monster" ? t.ac : t.stats.ac;
  const dmg = ignoreArmor ? power2 : afterArmor(power2, ac);
  if (dmg <= 0) {
    w.events.push({ type: "miss", target: t.id });
    return false;
  }
  w.applyDamage(t, dmg, p.id);
  return true;
}
function resolvePlayerHit(w, p, dir, style) {
  let power2 = attackPower(p.stats.dc[0], p.stats.dc[1] - p.stats.dc[0]);
  const luck = p.stats.luck;
  if (luck > 0 && random(10 - Math.min(9, luck)) === 0) power2 = p.stats.dc[1];
  else if (luck < 0 && random(10 - Math.min(9, -luck)) === 0) power2 = p.stats.dc[0];
  const front = step(p, dir);
  const target = w.occupant(front.x, front.y);
  switch (style) {
    case "power": {
      const s = skillOf(p, 7);
      power2 += 5 + s.level;
      p.powerHitReady = false;
      break;
    }
    case "fire": {
      const s = skillOf(p, 26);
      power2 += Math.round(power2 / 100 * (4 + s.level * 4) * 10);
      p.fireReady = false;
      if (target) train(w, p, s, 1);
      break;
    }
    case "wide":
      p.mp -= 3;
      break;
    default:
      break;
  }
  if (target) physicalHit(w, p, target, power2, false);
  if (style === "long") {
    const s = skillOf(p, 12);
    const far = step(p, dir, 2);
    const t2 = w.occupant(far.x, far.y);
    if (t2 && w.isEnemy(p, t2)) {
      physicalHit(w, p, t2, power2, true);
      train(w, p, s, 1);
    }
  }
  if (style === "wide") {
    const s = skillOf(p, 25);
    const pw = Math.round(power2 * (s.level + 1) / 4) + 1;
    for (const k of [1, 2, 6, 7, 3, 5, 4]) {
      const c = step(p, (dir + k) % 8);
      const t = w.occupant(c.x, c.y);
      if (t && w.isEnemy(p, t)) physicalHit(w, p, t, pw, false);
    }
    train(w, p, s, 1);
  }
  if (target && w.isEnemy(p, target)) {
    for (const id of [3, 4]) {
      const s = skillOf(p, id);
      if (s) train(w, p, s, random(3) + 1);
    }
  }
  const ph = skillOf(p, 7);
  if (ph && p.equip.weapon) {
    if (style === "power") train(w, p, ph, 1);
    if (p.powerHitCount <= 0) {
      p.powerHitCount = 7 - ph.level;
      p.powerHitPoint = random(p.powerHitCount);
    }
    p.powerHitCount--;
    if (p.powerHitCount === p.powerHitPoint) p.powerHitReady = true;
  }
  if (p.equip.weapon) {
    p.equip.weapon.dura = Math.max(0, p.equip.weapon.dura - (target ? 1 : 0) * (random(3) === 0 ? 1 : 0));
  }
}
function manaCost(def, level) {
  return Math.round(def.spell / 4 * (level + 1) + def.defSpell / 4 * (level + 1)) || def.spell;
}
function fx(w, e) {
  w.effects.push({
    blend: true,
    ...e,
    startedAt: w.now,
    duration: e.duration ?? e.frames * e.frameTime
  });
}
function castFx(w, p, def) {
  const base = CAST_BASE[def.effect];
  if (base === void 0) return;
  fx(w, { lib: base.lib, start: base.idx, frames: base.frames ?? 10, frameTime: 60, at: { actor: p.id } });
}
var CAST_BASE = {
  1: { lib: "magic", idx: 0 },
  2: { lib: "magic", idx: 200 },
  3: { lib: "magic", idx: 400 },
  4: { lib: "magic", idx: 600 },
  6: { lib: "magic", idx: 900 },
  7: { lib: "magic", idx: 920 },
  9: { lib: "magic2", idx: 20, frames: 3 },
  15: { lib: "magic", idx: 1500 },
  16: { lib: "magic", idx: 1520 },
  18: { lib: "magic", idx: 1560 },
  19: { lib: "magic", idx: 1590 },
  29: { lib: "magic", idx: 3880 },
  31: { lib: "magic", idx: 3840 },
  27: { lib: "magic", idx: 1790 }
};
function hitMagic(w, p, t, power2) {
  if (!w.isEnemy(p, t)) return;
  const mac = t.kind === "monster" ? t.mac : t.kind === "player" ? t.stats.mac : [0, 0];
  const dmg = afterMagicArmor(power2, mac);
  if (dmg > 0) w.applyDamage(t, dmg, p.id);
  else w.events.push({ type: "miss", target: t.id });
}
function enemiesAround(w, p, c, r2) {
  const out = [];
  for (const a of w.actors.values()) if (w.isEnemy(p, a) && chebyshev(a, c) <= r2) out.push(a);
  return out;
}
function consumeAmulet(p, w, poison) {
  const it = p.equip.braceletL;
  if (!it) return false;
  const k = itemKind(w.catalog.item(it.name));
  if (k.kind !== "amulet") return false;
  if (poison !== "any" && k.poison !== poison) return false;
  it.dura -= 100;
  if (it.dura <= 0) {
    p.equip.braceletL = null;
    w.events.push({ type: "msg", text: `${it.name} \u7528\u5B8C\u4E86`, color: "bad" });
  }
  return true;
}
function amuletPoison(p, w) {
  const it = p.equip.braceletL;
  if (!it) return null;
  const k = itemKind(w.catalog.item(it.name));
  return k.kind === "amulet" ? k.poison : null;
}
function castSpell(w, p, skillId, tgt) {
  const s = skillOf(p, skillId);
  const def = w.catalog.skillsById.get(skillId);
  if (!s || !def) return { ok: false, why: "\u8FD8\u6CA1\u5B66\u4F1A" };
  if (p.dead) return { ok: false, why: "\u4F60\u5DF2\u7ECF\u6B7B\u4E86" };
  if (w.now < p.busyUntil) return { ok: false, why: "" };
  if ((p.cooldown.get(skillId) ?? 0) > w.now) return { ok: false, why: "" };
  const cost = manaCost(def, s.level);
  if (p.mp < cost) return { ok: false, why: "\u9B54\u6CD5\u503C\u4E0D\u591F" };
  const MC = p.stats.mc;
  const SC = p.stats.sc;
  const mcPower = () => attackPower(skillPower(magicBase(def.power), s.level, def.defPower) + MC[0], MC[1] - MC[0] + 1);
  const t = tgt.actor && !tgt.actor.dead ? tgt.actor : void 0;
  if (t) p.dir = dirTo(p, t);
  else if (tgt.pos.x !== p.x || tgt.pos.y !== p.y) p.dir = dirTo(p, tgt.pos);
  const need = (cond, why) => cond ? null : { ok: false, why };
  let r2 = null;
  switch (skillId) {
    case 1:
    // 火球术
    case 5:
    // 大火球
    case 11: {
      r2 = need(!!t && w.isEnemy(p, t), "\u9700\u8981\u9009\u4E2D\u4E00\u4E2A\u76EE\u6807");
      if (r2) return r2;
      if (!w.map.flyable(t.x, t.y)) return { ok: false, why: "\u88AB\u6321\u4F4F\u4E86" };
      let power2 = mcPower();
      if (skillId === 11 && t.kind === "monster" && t.def.undead) power2 = Math.round(power2 * 1.5);
      const delay = skillId === 11 ? 100 : 80 * chebyshev(p, t);
      w.sound(magicSound(skillId, 1));
      w.after(delay, () => {
        w.sound(magicSound(skillId, 2), t.pos);
        hitMagic(w, p, t, power2);
      });
      if (skillId === 11) {
        fx(w, { lib: "magic2", start: 10, frames: 5, frameTime: 80, at: { actor: t.id } });
      } else {
        const base = skillId === 1 ? 0 : 400;
        fx(w, { lib: "magic", start: base + 10 + dir16(p, t) * 10, frames: 6, frameTime: 50, at: { actor: t.id }, from: p.pos, duration: delay });
        w.after(delay, () => fx(w, { lib: "magic", start: base + 170, frames: 10, frameTime: 50, at: { actor: t.id } }));
      }
      break;
    }
    case 33: {
      const c = t ? t.pos : tgt.pos;
      const power2 = mcPower();
      for (const e of enemiesAround(w, p, c, 1)) hitMagic(w, p, e, power2);
      fx(w, { lib: "magic", start: 3850, frames: 20, frameTime: 60, at: c });
      break;
    }
    case 8: {
      for (const e of enemiesAround(w, p, p, 1)) {
        if (e.kind !== "monster" || e.def.level > p.level) continue;
        const d = dirTo(p, e);
        w.move(e, d, false);
      }
      fx(w, { lib: "magic", start: 900, frames: 6, frameTime: 80, at: { actor: p.id } });
      break;
    }
    case 9: {
      const power2 = mcPower();
      const d = t ? dirTo(p, t) : dirTo(p, tgt.pos);
      for (let k = 1; k <= 5; k++) {
        const c = step(p, d, k);
        const a = w.occupant(c.x, c.y);
        if (a) hitMagic(w, p, a, power2);
        fx(w, { lib: "magic", start: 930, frames: 6, frameTime: 70, at: c });
      }
      break;
    }
    case 31: {
      p.shieldUntil = w.now + skillPower(15 + rpow(MC), s.level, def.defPower) * 1e3;
      p.shieldLevel = s.level;
      w.events.push({ type: "msg", text: "\u9B54\u6CD5\u76FE\u5F00\u542F", color: "system" });
      break;
    }
    case 20: {
      r2 = need(!!t && t.kind === "monster" && !t.master, "\u9700\u8981\u9009\u4E2D\u4E00\u53EA\u602A");
      if (r2) return r2;
      const m = t;
      const ok = m.def.level < p.level - 1 + s.level && random(4 - s.level + 1) === 0 && p.pets.length < 5 && m.def.race !== 0;
      fx(w, { lib: "magic", start: 1560 + 10, frames: 10, frameTime: 60, at: { actor: m.id } });
      if (ok) {
        m.master = p.id;
        m.target = 0;
        p.pets.push(m.id);
        w.events.push({ type: "msg", text: `${m.def.name} \u6210\u4E86\u4F60\u7684\u5B9D\u5B9D`, color: "good" });
      }
      break;
    }
    case 2: {
      const who = t && !w.isEnemy(p, t) && t.kind !== "npc" ? t : p;
      const heal = attackPower(skillPower(magicBase(def.power), s.level, def.defPower) + SC[0] * 2, (SC[1] - SC[0]) * 2 + 1);
      const maxHp = who.kind === "player" ? who.stats.maxHp : who.kind === "monster" ? who.maxHp : 1;
      const v = Math.min(heal, maxHp - who.hp);
      who.hp += Math.max(0, v);
      w.events.push({ type: "heal", target: who.id, amount: v });
      fx(w, { lib: "magic", start: 200, frames: 10, frameTime: 60, at: { actor: who.id } });
      break;
    }
    case 6: {
      r2 = need(!!t && w.isEnemy(p, t), "\u9700\u8981\u9009\u4E2D\u4E00\u4E2A\u76EE\u6807");
      if (r2) return r2;
      const kind = amuletPoison(p, w);
      if (kind !== 1 && kind !== 2) return { ok: false, why: "\u5DE6\u624B\u956F\u4F4D\u8981\u88C5\u5907\u7070\u8272\u6216\u9EC4\u8272\u836F\u7C89" };
      consumeAmulet(p, w, kind);
      const power2 = skillPower13(kind === 1 ? 40 : 30, s.level, def.defPower) + rpow(SC) * 2;
      t.poison = { kind: kind === 1 ? "green" : "red", until: w.now + power2 * 1e3, power: Math.max(1, Math.round(rpow(SC) / 2) + 1), nextTick: w.now + 2e3, by: p.id };
      if (t.kind === "monster" && !t.target) t.target = p.id;
      fx(w, { lib: "magic", start: 600 + 10, frames: 10, frameTime: 60, at: { actor: t.id } });
      break;
    }
    case 13: {
      r2 = need(!!t && w.isEnemy(p, t), "\u9700\u8981\u9009\u4E2D\u4E00\u4E2A\u76EE\u6807");
      if (r2) return r2;
      if (amuletPoison(p, w) !== 0) return { ok: false, why: "\u5DE6\u624B\u956F\u4F4D\u8981\u88C5\u5907\u62A4\u8EAB\u7B26" };
      consumeAmulet(p, w, 0);
      const power2 = attackPower(skillPower(magicBase(def.power), s.level, def.defPower) + SC[0], SC[1] - SC[0] + 1);
      const delay = 80 * chebyshev(p, t);
      fx(w, { lib: "magic", start: 1160 + dir16(p, t) * 10, frames: 3, frameTime: 60, at: { actor: t.id }, from: p.pos, duration: delay });
      w.sound(magicSound(13, 1));
      w.after(delay, () => {
        w.sound(magicSound(13, 2), t.pos);
        hitMagic(w, p, t, power2);
        fx(w, { lib: "magic", start: 1320, frames: 10, frameTime: 50, at: { actor: t.id } });
      });
      break;
    }
    case 17:
    // 召唤骷髅
    case 30: {
      if (amuletPoison(p, w) !== 0) return { ok: false, why: "\u5DE6\u624B\u956F\u4F4D\u8981\u88C5\u5907\u62A4\u8EAB\u7B26" };
      const name = skillId === 17 ? "\u53D8\u5F02\u9AB7\u9AC5" : "\u795E\u517D";
      const existing = p.pets.map((id) => w.actors.get(id)).find((a) => a?.kind === "monster" && a.def.name === name);
      if (existing) return { ok: false, why: `\u5DF2\u7ECF\u6709${name}\u4E86` };
      const mdef = w.catalog.monstersByName.get(name);
      if (!mdef) return { ok: false, why: "\u53EC\u5524\u5931\u8D25" };
      consumeAmulet(p, w, 0);
      const at = step(p, p.dir);
      const m = w.spawnMonster(mdef, -1, at, 1);
      if (m) {
        m.master = p.id;
        m.petLevel = s.level;
        const boost = 1 + s.level * 0.35;
        m.maxHp = Math.round(mdef.hp * boost);
        m.hp = m.maxHp;
        m.dc = [Math.round(mdef.dc[0] * boost), Math.round(mdef.dc[1] * boost)];
        p.pets.push(m.id);
        fx(w, { lib: "magic", start: 1500, frames: 10, frameTime: 60, at: { actor: m.id } });
      }
      break;
    }
    case 18: {
      if (amuletPoison(p, w) !== 0) return { ok: false, why: "\u5DE6\u624B\u956F\u4F4D\u8981\u88C5\u5907\u62A4\u8EAB\u7B26" };
      consumeAmulet(p, w, 0);
      const sec = skillPower13(30, s.level, def.defPower) + rpow(SC) * 3;
      p.invisibleUntil = w.now + sec * 1e3;
      for (const a of w.actors.values()) if (a.kind === "monster" && a.target === p.id && !a.def.coolEye) a.target = 0;
      w.events.push({ type: "msg", text: `\u9690\u8EAB ${sec} \u79D2\uFF08\u6253\u602A\u6216\u88AB\u6253\u4F1A\u73B0\u8EAB\uFF09`, color: "system" });
      break;
    }
    case 26: {
      if (p.fireReady) return { ok: false, why: "\u70C8\u706B\u5DF2\u7ECF\u51C6\u5907\u597D\u4E86" };
      p.fireReady = true;
      p.cooldown.set(26, w.now + 1e4);
      w.sound(S.fireHitReady);
      w.events.push({ type: "msg", text: "\u70C8\u706B\u5251\u6CD5\u51C6\u5907\u597D\u4E86", color: "system" });
      p.mp -= cost;
      return { ok: true };
    }
    case 25:
      p.wideHitOn = !p.wideHitOn;
      w.events.push({ type: "msg", text: p.wideHitOn ? "\u5F00\u542F\u534A\u6708\u5F2F\u5200" : "\u5173\u95ED\u534A\u6708\u5F2F\u5200", color: "system" });
      return { ok: true };
    case 12:
      p.longHitOn = !p.longHitOn;
      w.events.push({ type: "msg", text: p.longHitOn ? "\u5F00\u542F\u523A\u6740\u5251\u672F" : "\u5173\u95ED\u523A\u6740\u5251\u672F", color: "system" });
      return { ok: true };
    default:
      return { ok: false, why: "\u8FD9\u4E2A\u6280\u80FD\u8FD8\u6CA1\u505A" };
  }
  p.mp -= cost;
  w.sound(magicSound(skillId, 0));
  castFx(w, p, def);
  p.action = { kind: "spell", startedAt: w.now, duration: SPELL_ANIM_MS, spellEffect: def.effect };
  p.busyUntil = w.now + SPELL_INTERVAL;
  if (def.delay) p.cooldown.set(skillId, w.now + Math.max(SPELL_INTERVAL, def.delay));
  train(w, p, s, 1 + random(3));
  if (p.invisibleUntil > w.now && skillId !== 18) p.invisibleUntil = 0;
  return { ok: true };
}

// src/world/monster_ai.ts
var AXE = { lib: "mon3", base: 447, stride: 10, frames: 3 };
var THORN = { lib: "mon3", base: 2967, stride: 10, frames: 3 };
var ARROW = { lib: "effect", base: 272, stride: 1, frames: 1 };
var ZUMA = ["\u7956\u739B\u536B\u58EB", "\u7956\u739B\u96D5\u50CF", "\u7956\u739B\u5F13\u7BAD\u624B", "\u6954\u86FE"];
function behave(strike, more = {}) {
  return { strike, cover: { kind: "none" }, rooted: false, passive: false, extras: [], ...more };
}
var MELEE = { kind: "melee" };
function behaviorOf(def) {
  switch (def.race) {
    case 50:
    case 51:
    case 52:
      return behave(MELEE, { passive: true });
    case 82:
    case 119:
      return behave({ kind: "spit" });
    case 85:
      return behave(MELEE, { cover: { kind: "burrow", comeOut: 4, reach: 4 }, rooted: true });
    case 87:
      return behave({ kind: "missile", range: 7, fly: def.raceImg === 15 ? AXE : def.raceImg === 22 ? THORN : null });
    case 104:
    case 112:
      return behave({ kind: "missile", range: 7, fly: def.raceImg === 45 ? ARROW : null });
    case 90:
    case 105:
    case 106:
      return behave({ kind: "gas" });
    case 91:
      return behave({ kind: "magic" });
    case 92:
      return behave({ kind: "split", physical: 0.5 }, { extras: [{ kind: "cowKing" }] });
    case 94:
      return behave({ kind: "lightning" });
    case 95:
      return behave(MELEE, { cover: { kind: "digOut" } });
    case 96:
      return behave(MELEE, { extras: [{ kind: "revive" }] });
    case 101:
      return behave(MELEE, { cover: { kind: "statue", wakeAll: true } });
    case 102:
      return behave({ kind: "split", physical: 0 }, { cover: { kind: "statue", wakeAll: false }, extras: [{ kind: "summonZuma" }] });
    case 103:
      return behave({ kind: "spawn", child: "\u8759\u8760", below: false }, { rooted: true });
    case 107:
      return behave({ kind: "area", range: 6, poison: true }, { cover: { kind: "centipede" }, rooted: true });
    case 115:
      return behave({ kind: "area", range: 16, poison: false }, { rooted: true });
    case 116:
      return behave({ kind: "spawn", child: "\u7206\u88C2\u8718\u86DB", below: true }, { rooted: true });
    case 117:
      return behave({ kind: "explode" });
    default:
      return behave(MELEE);
  }
}
var cache = /* @__PURE__ */ new WeakMap();
function behavior(m) {
  let b = cache.get(m.def);
  if (!b) {
    b = behaviorOf(m.def);
    cache.set(m.def, b);
  }
  return b;
}
function isGuard(d) {
  return d.name.includes("\u536B\u58EB") || d.name.includes("\u62A4\u536B") || d.name.includes("\u5B88\u536B");
}
function fixedDir(m) {
  return [33, 34, 35, 43].includes(m.def.raceImg);
}
function initMonster(w, m) {
  const b = behavior(m);
  if (fixedDir(m)) m.dir = 0;
  switch (b.cover.kind) {
    case "burrow":
    case "digOut":
    case "centipede":
      m.cover = "buried";
      break;
    case "statue":
      m.cover = "stone";
      if (m.def.raceImg === 49) m.dir = 5;
      break;
    case "none":
      m.cover = "open";
  }
  if (b.extras.some((e) => e.kind === "revive") && random(3) === 0) m.revivesLeft = random(3) + 1;
  m.bornAt = w.now;
}
function thinkMonster(w, m) {
  if (w.now < m.nextThink || w.now < m.busyUntil) return;
  if (m.dead) {
    tryRevive(w, m);
    return;
  }
  m.nextThink = w.now + 100 + random(100);
  if (m.action.kind !== "stand" && w.now >= m.action.startedAt + m.action.duration) {
    w.setAction(m, "stand", 0);
  }
  const b = behavior(m);
  if (!updateCover(w, m, b)) return;
  for (const e of b.extras) extraTick(w, m, e);
  if (b.strike.kind === "explode" && w.now - m.bornAt > 6e4) {
    explode(w, m);
    return;
  }
  if (b.strike.kind === "spawn") {
    spawnTick(w, m, b.strike);
    return;
  }
  const guard = isGuard(m.def);
  const master = m.master ? w.actors.get(m.master) : void 0;
  let target = m.target ? w.actors.get(m.target) : void 0;
  if (target && (target.dead || !w.isEnemy(m, target) || chebyshev(m, target) > 14 || target.invisibleUntil > w.now)) {
    target = void 0;
    m.target = 0;
  }
  if (!target && (!b.passive || master)) {
    const range = guard ? 6 : master ? 8 : b.strike.kind === "area" ? b.strike.range : 7;
    target = w.nearestEnemy(m, range);
    if (target) m.target = target.id;
  }
  if (target) {
    if (!attack(w, m, b, target) && !guard && !b.rooted) stepToward(w, m, target);
    return;
  }
  if (master && master.map === m.map) {
    if (chebyshev(m, master) > 2) stepToward(w, m, master);
    return;
  }
  if (master || guard || b.rooted) return;
  if (random(100) < 4 && w.move(m, random(8), false)) {
    m.busyUntil = w.now + m.def.walkSpeed + random(1e3);
  }
}
function updateCover(w, m, b) {
  const c = b.cover;
  const near = (r2, strict = false) => {
    const e = w.nearestEnemy(m, r2);
    return e && (!strict || chebyshev(m, e) < r2) ? e : void 0;
  };
  if (m.cover === "stone" && c.kind === "statue") {
    if (!near(2)) return false;
    wake(w, m);
    if (c.wakeAll) {
      for (const a of w.actors.values()) {
        if (a.kind === "monster" && a.cover === "stone" && chebyshev(a, m) <= 7 && behavior(a).cover.kind === "statue") wake(w, a);
      }
    }
    return false;
  }
  if (m.cover === "buried") {
    if (c.kind === "centipede" && w.now - m.coverAt < 1e4) return false;
    const e = c.kind === "burrow" ? near(c.comeOut, true) : c.kind === "centipede" ? near(4, true) : near(3);
    if (!e) return false;
    surface(w, m);
    if (c.kind === "centipede") m.hp = m.maxHp;
    return false;
  }
  if (c.kind === "burrow") {
    const t = m.target ? w.actors.get(m.target) : void 0;
    if (!t || t.dead || chebyshev(m, t) > c.reach) {
      const e = w.nearestEnemy(m, c.reach);
      if (!e) {
        burrow(w, m);
        return false;
      }
      m.target = e.id;
    }
  }
  if (c.kind === "centipede") {
    if (w.now - m.coverAt < 3e3) return false;
    if (!w.nearestEnemy(m, 5) && w.now - m.coverAt > 1e4) {
      burrow(w, m);
      return false;
    }
  }
  return true;
}
function digAnim(w, m, kind) {
  const t = w.actTable(m);
  const info = t ? kind === "digdown" || !herbLike(m) ? t.ActDeath : t.ActWalk : null;
  const dur = info ? Math.max(300, info[1] * info[3]) : 600;
  w.setAction(m, kind, dur);
  m.busyUntil = w.now + dur;
  return dur;
}
function herbLike(m) {
  return [13, 33, 34, 35].includes(m.def.raceImg);
}
function surface(w, m) {
  m.cover = "open";
  m.coverAt = w.now;
  digAnim(w, m, "digup");
}
function burrow(w, m) {
  m.target = 0;
  m.coverAt = w.now;
  digAnim(w, m, "digdown");
  w.after(m.busyUntil - w.now, () => {
    if (!m.dead) m.cover = "buried";
  });
}
function wake(w, m) {
  m.cover = "open";
  m.coverAt = w.now;
  digAnim(w, m, "digup");
}
function power(m) {
  return m.dc[0] + random(Math.max(0, m.dc[1] - m.dc[0]) + 1);
}
function defenseOf(t) {
  if (t.kind === "player") return { ac: t.stats.ac, mac: t.stats.mac, agility: t.stats.agility };
  if (t.kind === "monster") return { ac: t.ac, mac: t.mac, agility: t.agility };
  return null;
}
function damage(w, m, t, amount) {
  if (amount > 0) w.applyDamage(t, amount, m.id);
  else w.events.push({ type: "miss", target: t.id });
}
function physical(w, m, t, pw, checkHit = true) {
  const d = defenseOf(t);
  if (!d) return;
  if (checkHit && !hits(m.hit, d.agility)) {
    w.events.push({ type: "miss", target: t.id });
    return;
  }
  damage(w, m, t, afterArmor(pw, d.ac));
}
function magical(w, m, t, pw) {
  const d = defenseOf(t);
  if (d) damage(w, m, t, afterMagicArmor(pw, d.mac));
}
function swing(w, m, t) {
  if (t && !fixedDir(m)) m.dir = dirTo(m, t);
  const tab = w.actTable(m);
  const info = tab ? m.def.raceImg === 33 ? tab.ActCritical : tab.ActAttack : null;
  const anim = info ? info[1] * info[3] : 600;
  w.setAction(m, "attack", anim);
  m.busyUntil = w.now + anim;
  m.lastAttack = w.now;
  w.sound(monsterSound(m.def.appr, 2), m.pos);
  attackFx(w, m, t, info ? info[1] : 6, info ? info[3] : 100);
  return anim;
}
var ATTACK_FX = {
  16: { lib: "mon3", base: 1444 },
  // 洞蛆毒气（晚一帧开始）
  20: { lib: "mon4", base: 1800, extra: 1 },
  // 火焰沃玛
  21: { lib: "mon4", base: 1900 },
  // 沃玛教主
  40: { lib: "mon5", base: 350, dir16: true },
  // 僵尸电光
  49: { lib: "mon7", base: 1680, frames: 9 },
  // 祖玛教主
  52: { lib: "mon4", base: 3590 },
  // 楔蛾
  53: { lib: "mon3", base: 3590 }
  // 粪虫
};
function attackFx(w, m, t, frames, ftime) {
  const f = ATTACK_FX[m.def.raceImg];
  if (!f) return;
  const dir = f.dir16 && t ? dir16(m, t) : f.dir16 ? m.dir * 2 : m.dir;
  const n = f.frames ?? frames + (f.extra ?? 0);
  w.effects.push({
    lib: f.lib,
    start: f.base + dir * 10,
    frames: n,
    frameTime: ftime,
    at: { actor: m.id },
    blend: true,
    startedAt: w.now,
    duration: n * ftime
  });
}
function ready(w, m) {
  return w.now - m.lastAttack >= m.attackInterval;
}
function attack(w, m, b, t) {
  const d = chebyshev(m, t);
  const s = b.strike;
  switch (s.kind) {
    case "melee":
    case "magic":
    case "split":
    case "gas":
    case "explode": {
      if (d > 1) return false;
      if (!fixedDir(m)) m.dir = dirTo(m, t);
      if (!ready(w, m)) return true;
      if (s.kind === "explode") {
        explode(w, m);
        return true;
      }
      swing(w, m, t);
      const pw = power(m);
      if (s.kind === "melee") physical(w, m, t, pw);
      else if (s.kind === "magic") magical(w, m, t, pw);
      else if (s.kind === "split") {
        const ph = Math.round(pw * s.physical);
        if (ph > 0) physical(w, m, t, ph, false);
        magical(w, m, t, pw - ph);
      } else gas(w, m, t, pw);
      return true;
    }
    case "spit": {
      const dir = dirTo(m, t);
      const inLine2 = d <= 2 && (step(m, dir, d).x === t.x && step(m, dir, d).y === t.y);
      if (!inLine2) return false;
      m.dir = dir;
      if (!ready(w, m)) return true;
      swing(w, m, t);
      const pw = power(m);
      for (const k of [1, 2]) {
        const c = step(m, dir, k);
        const a = w.occupant(c.x, c.y);
        const def = a && defenseOf(a);
        if (!a || !def || !w.isEnemy(m, a) || !hits(m.hit, def.agility)) continue;
        magical(w, m, a, pw);
        if (!a.dead && random(20) === 0) poisonGreen(w, a, m, 3e4, 1);
      }
      return true;
    }
    case "missile": {
      if (d < 1 || d > s.range) return false;
      if (!ready(w, m)) return true;
      swing(w, m, t);
      const delay = d * 50 + 300;
      if (s.fly) {
        w.effects.push({
          lib: s.fly.lib,
          start: s.fly.base + dir16(m, t) * s.fly.stride,
          frames: s.fly.frames,
          frameTime: 60,
          at: { actor: t.id },
          from: m.pos,
          blend: false,
          startedAt: w.now,
          duration: delay
        });
      }
      const pw = power(m);
      w.after(delay, () => {
        if (!t.dead && !m.dead) physical(w, m, t, pw, false);
      });
      return true;
    }
    case "lightning": {
      if (d <= 2 && random(3) === 0 && stepAway(w, m, t)) return true;
      if (d >= 6) return false;
      if (!ready(w, m)) return true;
      swing(w, m, t);
      const dir = dirTo(m, t);
      const pw = power(m);
      const hitIds = /* @__PURE__ */ new Set();
      for (let k = 1; k <= 8; k++) {
        const c = step(m, dir, k);
        const a = w.occupant(c.x, c.y);
        if (a && !hitIds.has(a.id) && w.isEnemy(m, a)) {
          hitIds.add(a.id);
          magical(w, m, a, pw);
        }
      }
      return true;
    }
    case "area": {
      if (d >= s.range) return true;
      if (!ready(w, m)) return true;
      swing(w, m, t);
      const pw = power(m);
      const delay = s.poison ? 600 : 200;
      for (const a of [...w.actors.values()]) {
        if (!w.isEnemy(m, a) || chebyshev(m, a) >= s.range) continue;
        w.after(delay, () => {
          if (a.dead || m.dead) return;
          magical(w, m, a, pw);
          if (s.poison && !a.dead && random(4) === 0) {
            if (random(3) !== 0) poisonGreen(w, a, m, 6e4, 3);
            else paralyze(w, a, 5e3);
          }
        });
      }
      return true;
    }
    case "spawn":
      return true;
  }
}
function gas(w, m, t, pw) {
  const d = defenseOf(t);
  if (!d) return;
  if (!hits(m.hit, d.agility)) {
    w.events.push({ type: "miss", target: t.id });
    return;
  }
  magical(w, m, t, pw);
  if (!t.dead && random(20) === 0) paralyze(w, t, 5e3);
}
function poisonGreen(w, t, m, ms, pw) {
  t.poison = { kind: "green", until: w.now + ms, power: pw, nextTick: w.now + 2e3, by: m.id };
  if (t.kind === "player") w.events.push({ type: "msg", text: "\u4F60\u4E2D\u6BD2\u4E86", color: "bad" });
}
function paralyze(w, t, ms) {
  t.paralyzedUntil = Math.max(t.paralyzedUntil, w.now + ms);
  t.busyUntil = Math.max(t.busyUntil, t.paralyzedUntil);
  if (t.kind === "player") w.events.push({ type: "msg", text: `\u4F60\u88AB\u9EBB\u75F9\u4E86\uFF08${ms / 1e3} \u79D2\uFF09`, color: "bad" });
}
function explode(w, m) {
  const pw = power(m);
  for (const a of [...w.actors.values()]) {
    if (!w.isEnemy(m, a) || chebyshev(m, a) > 1) continue;
    const d = defenseOf(a);
    if (!d) continue;
    const dmg = afterArmor(Math.floor(pw / 2), d.ac) + afterMagicArmor(Math.floor(pw / 2), d.mac);
    damage(w, m, a, dmg);
  }
  w.killWithoutReward(m);
}
function spawnTick(w, m, s) {
  m.minions = m.minions.filter((id) => {
    const a = w.actors.get(id);
    return a !== void 0 && !a.dead;
  });
  if (!ready(w, m)) return;
  m.lastAttack = w.now;
  const t = w.nearestEnemy(m, 9);
  if (!t || m.minions.length >= 15) return;
  swing(w, m, null);
  const def = w.catalog.monstersByName.get(s.child);
  if (!def) return;
  w.after(500, () => {
    if (m.dead) return;
    const at = s.below ? { x: m.x, y: m.y + 1 } : m.pos;
    const c = w.spawnMonster(def, -1, at, 1);
    if (c) {
      c.target = t.id;
      m.minions.push(c.id);
    }
  });
}
function extraTick(w, m, e) {
  switch (e.kind) {
    case "revive":
      return;
    case "summonZuma": {
      if (w.now - m.extraTick < 1e3) return;
      m.extraTick = w.now;
      const level = Math.floor(m.hp * 5 / m.maxHp);
      if (m.hp >= m.maxHp) m.danger = 5;
      if (m.danger > level && m.danger > 0 && m.target) {
        m.danger--;
        m.minions = m.minions.filter((id) => !(w.actors.get(id)?.dead ?? true));
        const at = step(m, m.dir);
        const n = random(6) + 6;
        for (let i = 0; i < n && m.minions.length < 30; i++) {
          const def = w.catalog.monstersByName.get(ZUMA[random(ZUMA.length)]);
          const c = def && w.spawnMonster(def, -1, at, 2);
          if (c) {
            c.cover = "open";
            c.target = m.target;
            m.minions.push(c.id);
          }
        }
      }
      return;
    }
    case "cowKing":
      cowKing(w, m);
  }
}
function cowKing(w, m) {
  if (w.now - m.extraTick < 3e4) return;
  m.extraTick = w.now;
  const t = m.target ? w.actors.get(m.target) : void 0;
  if (t && !t.dead) {
    let blocked = 0;
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      if ((dx || dy) && !w.canEnter(m.x + dx, m.y + dy)) blocked++;
    }
    if (blocked >= 5) {
      const back = step(t, opposite(t.dir));
      const to = w.canEnter(back.x, back.y) ? back : w.freeCellNear(t, 3);
      if (to) {
        w.teleportActor(m, to);
        return;
      }
    }
  }
  const before = m.danger;
  m.danger = 7 - Math.floor(m.hp / Math.max(1, Math.floor(m.maxHp / 7)));
  if (m.danger >= 2 && m.danger !== before) {
    m.rage = "ready";
    m.rageAt = w.now;
  }
  if (m.rage === "ready") {
    if (w.now - m.rageAt < 8e3) m.attackInterval = 1e4;
    else {
      m.rage = "crazy";
      m.rageAt = w.now;
    }
  }
  if (m.rage === "crazy") {
    if (w.now - m.rageAt < 8e3) {
      m.attackInterval = 500;
      m.walkInterval = 400;
    } else {
      m.rage = "calm";
      m.attackInterval = m.def.attackSpeed;
      m.walkInterval = m.def.walkSpeed;
    }
  }
}
function tryRevive(w, m) {
  if (m.revivesLeft <= 0 || m.reviveAt === 0 || w.now < m.reviveAt) return;
  if (!w.canEnter(m.x, m.y)) {
    m.reviveAt = w.now + 1e3;
    return;
  }
  m.revivesLeft--;
  m.reviveAt = 0;
  m.maxHp = Math.max(1, m.maxHp >> 1);
  m.hp = m.maxHp;
  m.expScale /= 2;
  w.reviveMonster(m);
  digAnim(w, m, "digup");
}
function onMonsterDeath(w, m) {
  if (m.revivesLeft > 0) {
    m.reviveAt = w.now + (random(20) + 4) * 1e3;
    m.removeAt = Math.max(m.removeAt, m.reviveAt + 1e3);
  }
}
function stepToward(w, m, t) {
  if (w.move(m, dirTo(m, t), false)) {
    m.busyUntil = w.now + m.walkInterval;
    return;
  }
  const path = w.findPathFor(m, t);
  if (path?.[0] !== void 0 && w.move(m, path[0], false)) m.busyUntil = w.now + m.walkInterval;
  else m.busyUntil = w.now + 500;
}
function stepAway(w, m, t) {
  const away = opposite(dirTo(m, t));
  for (const d of [away, (away + 1) % 8, (away + 7) % 8]) {
    if (w.move(m, d, false)) {
      m.busyUntil = w.now + m.walkInterval;
      return true;
    }
  }
  return false;
}

// src/world/world.ts
var WALK_MS = 600;
var RUN_MS = 600;
var HIT_ANIM_MS = 510;
var SPELL_ANIM_MS = 600;
var SPELL_INTERVAL = 800;
var CORPSE_MS = 1e4;
var ITEM_KEEP_MS = 10 * 6e4;
var World = class {
  constructor(catalog) {
    this.catalog = catalog;
    this.now = 0;
    this.actors = /* @__PURE__ */ new Map();
    this.items = [];
    this.events = [];
    this.effects = [];
    /** 刷怪点：下一次补怪时间 */
    this.spawnTimers = [];
    this.occupancy = new Int32Array(0);
    this.nextId = 100;
    // ---------------- 时间推进 ----------------
    this.timers = [];
    this.stepFoot = 0;
    /** 由游戏控制器在加载完新地图后执行。 */
    this.pendingTeleport = null;
  }
  // ---------------- 地图与占位 ----------------
  /** 进入地图（调用前已加载好 MapData）。 */
  enterMap(map, pos) {
    const p = this.player;
    for (const a of [...this.actors.values()]) {
      if (a !== p && !(a.kind === "monster" && p.pets.includes(a.id))) this.actors.delete(a.id);
    }
    this.items.length = 0;
    this.effects = [];
    this.map = map;
    this.occupancy = new Int32Array(map.width * map.height);
    const start = this.freeCellNear(pos) ?? pos;
    p.map = map.id;
    p.x = start.x;
    p.y = start.y;
    p.action = { kind: "stand", startedAt: this.now, duration: 0 };
    this.occupy(p);
    for (const id of p.pets) {
      const pet = this.actors.get(id);
      if (!pet) continue;
      const c = this.freeCellNear(start) ?? start;
      pet.map = map.id;
      pet.x = c.x;
      pet.y = c.y;
      this.occupy(pet);
    }
    p.pets = p.pets.filter((id) => this.actors.has(id));
    for (const m of this.catalog.data.merchants.filter((m2) => m2.map === map.id)) {
      const npc = new Npc(this.nextId++, m, this.now);
      this.actors.set(npc.id, npc);
      this.occupy(npc);
    }
    for (const g of this.catalog.data.guards.filter((g2) => g2.map === map.id)) {
      const def = this.catalog.monstersByName.get(g.monster);
      if (def) {
        const m = this.spawnMonster(def, -1, g);
        if (m) m.dir = g.direction % 8;
      }
    }
    const spawns = this.catalog.data.spawns;
    this.spawnTimers = spawns.map(() => Infinity);
    spawns.forEach((s, i) => {
      if (s.map !== map.id) return;
      this.spawnTimers[i] = this.now;
    });
    this.events.push({ type: "mapChange", map: map.id, x: start.x, y: start.y });
    this.sound(S.spaceIn);
  }
  idx(x, y) {
    return y * this.map.width + x;
  }
  occupant(x, y) {
    if (!this.map.inside(x, y)) return void 0;
    const id = this.occupancy[this.idx(x, y)];
    return id ? this.actors.get(id) : void 0;
  }
  occupy(a) {
    this.occupancy[this.idx(a.x, a.y)] = a.id;
  }
  vacate(a) {
    const i = this.idx(a.x, a.y);
    if (this.occupancy[i] === a.id) this.occupancy[i] = 0;
  }
  /** 地形可走且没人站。 */
  canEnter(x, y) {
    return this.map.walkable(x, y) && !this.occupant(x, y);
  }
  freeCellNear(p, radius = 6) {
    for (let r2 = 0; r2 <= radius; r2++) {
      for (let dy = -r2; dy <= r2; dy++) {
        for (let dx = -r2; dx <= r2; dx++) {
          if (Math.max(Math.abs(dx), Math.abs(dy)) !== r2) continue;
          if (this.canEnter(p.x + dx, p.y + dy)) return { x: p.x + dx, y: p.y + dy };
        }
      }
    }
    return null;
  }
  // ---------------- 生成 ----------------
  spawnMonster(def, spawn, at, radius = 0) {
    let pos = null;
    for (let t = 0; t < 20 && !pos; t++) {
      const c = { x: at.x + random(radius * 2 + 1) - radius, y: at.y + random(radius * 2 + 1) - radius };
      if (this.canEnter(c.x, c.y)) pos = c;
    }
    pos ?? (pos = this.freeCellNear(at, Math.max(3, radius)));
    if (!pos) return null;
    const m = new Monster(this.nextId++, def, spawn, this.map.id, pos.x, pos.y, this.now);
    m.action = { kind: "stand", startedAt: this.now, duration: 0 };
    m.nextThink = this.now + random(2e3);
    this.actors.set(m.id, m);
    this.occupy(m);
    initMonster(this, m);
    return m;
  }
  runSpawns() {
    const spawns = this.catalog.data.spawns;
    spawns.forEach((s, i) => {
      if ((this.spawnTimers[i] ?? Infinity) > this.now) return;
      const def = this.catalog.monstersByName.get(s.monster);
      if (!def) {
        this.spawnTimers[i] = Infinity;
        return;
      }
      let alive = 0;
      for (const a of this.actors.values()) if (a.kind === "monster" && a.spawn === i && !a.dead) alive++;
      for (let k = alive; k < s.count && k < alive + 40; k++) this.spawnMonster(def, i, s, s.radius);
      this.spawnTimers[i] = this.now + Math.max(1, s.minutes) * 6e4;
    });
  }
  sound(id, at) {
    this.events.push({ type: "sound", id, at });
  }
  /** 玩家武器外观（用于挥刀、打中的声音） */
  weaponShape(p) {
    return p.equip.weapon ? this.catalog.item(p.equip.weapon.name).shape : null;
  }
  /** 按游戏时间延后执行（飞行技能命中、药效分跳等）。 */
  after(ms, fn) {
    this.timers.push({ at: this.now + ms, fn });
  }
  tick(dt) {
    this.now += dt;
    if (this.timers.length) {
      const due = this.timers.filter((t) => t.at <= this.now);
      this.timers = this.timers.filter((t) => t.at > this.now);
      for (const t of due) t.fn();
    }
    this.runSpawns();
    for (const a of [...this.actors.values()]) {
      if (a.kind === "monster") thinkMonster(this, a);
      this.tickStatus(a);
    }
    this.regen();
    this.effects = this.effects.filter((e) => this.now - e.startedAt < e.duration);
    for (const a of [...this.actors.values()]) {
      if (a.kind === "monster" && a.dead && this.now >= a.removeAt) this.actors.delete(a.id);
    }
    for (let i = this.items.length - 1; i >= 0; i--) {
      if (this.now - this.items[i].droppedAt > ITEM_KEEP_MS) this.items.splice(i, 1);
    }
  }
  tickStatus(a) {
    const p = a.poison;
    if (!p || a.dead) return;
    if (this.now >= p.until) {
      a.poison = null;
      return;
    }
    if (p.kind === "green" && this.now >= p.nextTick) {
      p.nextTick = this.now + 2e3;
      this.applyDamage(a, Math.max(1, p.power), p.by, false);
    }
  }
  regen() {
    const p = this.player;
    if (p.dead || this.now - p.lastRegen < 2e3) return;
    p.lastRegen = this.now;
    const s = p.stats;
    const hp = Math.min(s.maxHp - p.hp, Math.max(1, Math.floor(s.maxHp / 75)) + s.hpRegen);
    const mp = Math.min(s.maxMp - p.mp, Math.max(1, Math.floor(s.maxMp / 75)) + s.mpRegen);
    if (hp > 0) p.hp += hp;
    if (mp > 0) p.mp += mp;
  }
  // ---------------- 动作 ----------------
  setAction(a, kind, duration, extra = {}) {
    a.action = { kind, startedAt: this.now, duration, ...extra };
  }
  /** 走/跑一步。成功返回 true。 */
  move(a, dir, run) {
    if (a.dead) return false;
    const steps = run ? 2 : 1;
    let to = a.pos;
    for (let i = 0; i < steps; i++) {
      const n = step(to, dir);
      if (!this.canEnter(n.x, n.y)) return false;
      to = n;
    }
    const from = a.pos;
    this.vacate(a);
    a.x = to.x;
    a.y = to.y;
    a.dir = dir;
    this.occupy(a);
    const dur = a.kind === "player" ? run ? RUN_MS : WALK_MS : this.monsterMoveMs(a);
    this.setAction(a, run ? "run" : "walk", dur, { from });
    a.busyUntil = this.now + dur;
    if (a.kind === "player") {
      const cave = this.map.id.startsWith("D");
      const pair = run ? cave ? S.runCave : S.runGround : cave ? S.walkCave : S.walkGround;
      this.sound(pair[this.stepFoot++ % 2]);
      this.afterPlayerMove(a);
    }
    return true;
  }
  turn(a, dir) {
    a.dir = dir;
  }
  monsterMoveMs(m) {
    const t = this.actTable(m);
    const anim = t ? t.ActWalk[1] * t.ActWalk[3] : 600;
    return Math.max(300, Math.min(anim, m.walkInterval));
  }
  actTable(m) {
    return m.def.act ? this.catalog.data.monsterActions[m.def.act] : void 0;
  }
  afterPlayerMove(p) {
    const r2 = this.catalog.data.routes.find((r3) => r3.from === this.map.id && r3.x === p.x && r3.y === p.y);
    if (r2) this.pendingTeleport = { map: r2.to, x: r2.tx, y: r2.ty };
  }
  // ---------------- 战斗 ----------------
  isEnemy(a, b) {
    if (a.dead || b.dead || a === b || b.kind === "npc" || a.kind === "npc") return false;
    if (b.kind === "monster" && b.cover !== "open") return false;
    const side = (x) => x.kind === "player" ? "p" : x.master ? "p" : isGuard(x.def) ? "g" : "m";
    const sa = side(a);
    const sb = side(b);
    if (sa === sb) return false;
    if (sa === "g" && sb === "p" || sa === "p" && sb === "g") return false;
    return true;
  }
  /** 伤害结算：魔法盾减伤、死亡处理、经验。 */
  applyDamage(target, amount, sourceId, interrupt = true) {
    if (target.dead || target.kind === "npc") return;
    let dmg = amount;
    if (target.shieldUntil > this.now && target.kind === "player") {
      const absorb = [0.3, 0.4, 0.5, 0.6][target.shieldLevel] ?? 0.3;
      dmg = Math.max(1, Math.round(dmg * (1 - absorb)));
    }
    target.hp -= dmg;
    target.lastHitBy = sourceId;
    const src = this.actors.get(sourceId);
    if (target.kind === "monster") {
      this.sound(struckBody(src?.kind === "player" ? this.weaponShape(src) : null), target.pos);
      if (target.hp > 0) this.sound(monsterSound(target.def.appr, 4), target.pos);
    } else if (target.kind === "player" && target.hp > 0) {
      this.sound(target.sex === 1 ? S.womStruck : S.manStruck);
    }
    this.events.push({ type: "damage", target: target.id, amount: dmg, source: sourceId });
    if (target.hp <= 0) {
      target.hp = 0;
      this.kill(target, sourceId);
      return;
    }
    if (interrupt && target.kind === "monster") {
      const t = this.actTable(target);
      const dur = t ? Math.max(200, t.ActStruck[1] * t.ActStruck[3]) : 200;
      if (target.action.kind === "stand" || target.action.kind === "struck") {
        this.setAction(target, "struck", dur);
        target.busyUntil = Math.max(target.busyUntil, this.now + dur);
      }
      if (!target.target) target.target = sourceId;
    } else if (target.kind === "player" && target.action.kind === "stand") {
      this.setAction(target, "struck", 210);
    }
  }
  kill(target, killerId) {
    target.dead = true;
    target.deadAt = this.now;
    target.poison = null;
    this.setAction(target, "die", 1200);
    if (target.kind === "monster") this.sound(monsterSound(target.def.appr, 5), target.pos);
    else if (target.kind === "player") this.sound(target.sex === 1 ? S.womDie : S.manDie);
    if (target.kind === "monster") {
      this.vacate(target);
      target.removeAt = this.now + CORPSE_MS;
      const killer = this.actors.get(killerId);
      const owner = killer?.kind === "player" ? killer : killer?.kind === "monster" && killer.master ? this.actors.get(killer.master) : void 0;
      if (owner?.kind === "player" && !target.master) {
        this.gainExp(owner, Math.floor(target.def.exp * target.expScale) * RATES.exp);
        this.dropLoot(target);
      }
      if (target.master) {
        const m = this.actors.get(target.master);
        if (m?.kind === "player") m.pets = m.pets.filter((id) => id !== target.id);
      }
      onMonsterDeath(this, target);
    } else if (target.kind === "player") {
      this.events.push({ type: "died" });
      this.events.push({ type: "msg", text: "\u4F60\u6B7B\u4E86\u3002\u70B9\u300C\u56DE\u57CE\u590D\u6D3B\u300D\u56DE\u5230\u57CE\u91CC\u3002", color: "bad" });
    }
  }
  /** 不给经验、不掉东西的死（自爆）。 */
  killWithoutReward(a) {
    a.hp = 0;
    this.kill(a, 0);
  }
  /** 怪物原地复活（僵尸）。 */
  reviveMonster(m) {
    m.dead = false;
    m.target = 0;
    m.removeAt = 0;
    this.occupy(m);
  }
  /** 瞬移到某格（沃玛教主）。 */
  teleportActor(a, to) {
    this.vacate(a);
    a.x = to.x;
    a.y = to.y;
    this.occupy(a);
    this.setAction(a, "stand", 0);
  }
  revive() {
    const p = this.player;
    if (!p.dead) return;
    p.dead = false;
    p.hp = Math.max(1, Math.floor(p.stats.maxHp / 3));
    p.mp = Math.floor(p.stats.maxMp / 3);
    this.vacate(p);
    this.pendingTeleport = { ...p.home };
  }
  gainExp(p, exp) {
    if (exp <= 0) return;
    p.exp += exp;
    this.events.push({ type: "msg", text: `\u83B7\u5F97\u7ECF\u9A8C ${exp}`, color: "good" });
    let need = this.catalog.expToNext(p.level);
    while (p.exp >= need) {
      p.exp -= need;
      p.level++;
      this.recalc(p);
      p.hp = p.stats.maxHp;
      p.mp = p.stats.maxMp;
      this.events.push({ type: "levelUp", level: p.level });
      this.events.push({ type: "msg", text: `\u5347\u7EA7\u4E86\uFF01\u73B0\u5728 ${p.level} \u7EA7`, color: "good" });
      need = this.catalog.expToNext(p.level);
    }
  }
  /** 按爆率表掉落（概率按 rates.ts 换算；金币数量 amount/2 + Random(amount)）。 */
  dropLoot(m) {
    const table = this.catalog.data.drops[m.def.name] ?? [];
    for (const d of table) {
      const def0 = d.item === "\u91D1\u5E01" ? null : this.catalog.itemsByName.get(d.item) ?? null;
      if (Math.random() >= dropChance(d, def0)) continue;
      if (d.item === "\u91D1\u5E01") {
        const gold = Math.floor(d.amount / 2) + random(d.amount);
        if (gold > 0) this.dropAt(m.pos, { gold });
        continue;
      }
      const def = this.catalog.itemsByName.get(d.item);
      if (!def) continue;
      for (let k = 0; k < Math.max(1, d.amount); k++) this.dropAt(m.pos, newItem(def));
    }
  }
  dropAt(p, item) {
    for (let r2 = 0; r2 < 4; r2++) {
      for (let dy = -r2; dy <= r2; dy++) {
        for (let dx = -r2; dx <= r2; dx++) {
          const x = p.x + dx;
          const y = p.y + dy;
          if (!this.map.walkable(x, y) || this.items.some((i) => i.x === x && i.y === y)) continue;
          this.items.push({ id: this.nextId++, map: this.map.id, x, y, item, droppedAt: this.now });
          return;
        }
      }
    }
    this.items.push({ id: this.nextId++, map: this.map.id, x: p.x, y: p.y, item, droppedAt: this.now });
  }
  // ---------------- 玩家：属性 ----------------
  recalc(p) {
    const effects = EQUIP_SLOTS.flatMap((s) => {
      const it = p.equip[s];
      if (!it || it.dura <= 0 && itemKind(this.catalog.item(it.name)).kind !== "amulet") return [];
      const def = this.catalog.item(it.name);
      return itemKind(def).kind === "amulet" ? [] : [itemEffects(def)];
    });
    const lv = (id) => p.skills.find((s) => s.id === id)?.level ?? -1;
    p.stats = computeStats(p.job, p.level, effects, { oneSword: lv(3), spiritSword: lv(4), powerHit: lv(7) });
    p.hp = Math.min(p.hp, p.stats.maxHp);
    p.mp = Math.min(p.mp, p.stats.maxMp);
  }
  get stats() {
    return this.player.stats;
  }
  // ---------------- 物品 ----------------
  bagWeight(p) {
    return [...p.bag, ...p.belt].reduce((s, it) => s + (it ? this.catalog.item(it.name).weight : 0), 0);
  }
  wearWeight(p) {
    return ["dress", "helmet", "necklace", "braceletL", "braceletR", "ringL", "ringR"].reduce((s, k) => s + (p.equip[k] ? this.catalog.item(p.equip[k].name).weight : 0), 0);
  }
  /** 放进包裹；满了返回 false。药品优先放快捷栏空位。 */
  addToBag(p, it) {
    const kind = itemKind(this.catalog.item(it.name)).kind;
    if (kind === "potion" || kind === "scroll") {
      const b = p.belt.indexOf(null);
      if (b >= 0) {
        p.belt[b] = it;
        return true;
      }
    }
    const i = p.bag.indexOf(null);
    if (i < 0) return false;
    p.bag[i] = it;
    return true;
  }
  pickUp(p) {
    const here = this.items.filter((i) => i.x === p.x && i.y === p.y);
    let any = false;
    for (const g of here) {
      this.sound(isGold(g.item) ? S.money : S.itemClick);
      if (isGold(g.item)) {
        p.gold += g.item.gold;
        this.events.push({ type: "msg", text: `\u6361\u5230\u91D1\u5E01 ${g.item.gold}`, color: "item" });
      } else {
        const def = this.catalog.item(g.item.name);
        if (this.bagWeight(p) + def.weight > p.stats.maxBagWeight) {
          this.events.push({ type: "msg", text: "\u5305\u88F9\u592A\u91CD\u4E86\uFF0C\u62FF\u4E0D\u52A8", color: "bad" });
          continue;
        }
        if (!this.addToBag(p, g.item)) {
          this.events.push({ type: "msg", text: "\u5305\u88F9\u6EE1\u4E86", color: "bad" });
          continue;
        }
        this.events.push({ type: "msg", text: `\u6361\u5230 ${g.item.name}`, color: "item" });
      }
      this.items.splice(this.items.indexOf(g), 1);
      any = true;
    }
    return any;
  }
  findOwned(p, uid) {
    let i = p.bag.findIndex((x) => x?.uid === uid);
    if (i >= 0) return { where: "bag", index: i, item: p.bag[i] };
    i = p.belt.findIndex((x) => x?.uid === uid);
    if (i >= 0) return { where: "belt", index: i, item: p.belt[i] };
    return null;
  }
  removeOwned(p, uid) {
    const f = this.findOwned(p, uid);
    if (!f) return null;
    (f.where === "bag" ? p.bag : p.belt)[f.index] = null;
    return f.item;
  }
  /** 双击包裹里的物品：喝药、读书、用卷轴、穿装备、拆药包。返回提示文字（失败时）。 */
  useItem(p, uid) {
    if (p.dead) return "\u4F60\u5DF2\u7ECF\u6B7B\u4E86";
    const f = this.findOwned(p, uid);
    if (!f) return null;
    const def = this.catalog.item(f.item.name);
    const k = itemKind(def);
    switch (k.kind) {
      case "potion": {
        this.removeOwned(p, uid);
        this.sound(S.eatDrug);
        if (k.instant) {
          const hp = Math.min(k.hp, p.stats.maxHp - p.hp);
          const mp = Math.min(k.mp, p.stats.maxMp - p.mp);
          p.hp += hp;
          p.mp += mp;
          if (hp) this.events.push({ type: "heal", target: p.id, amount: hp });
        } else {
          this.queueRecover(p, k.hp, k.mp);
        }
        return null;
      }
      case "bundle": {
        const one = this.catalog.data.items.find((i) => i.mode !== 31 && i.ani === k.of);
        if (!one) return "\u8FD9\u4E2A\u5305\u62C6\u4E0D\u5F00";
        const free = p.bag.filter((x) => x === null).length - 1 + p.belt.filter((x) => x === null).length;
        if (free < 6) return "\u5305\u88F9\u7A7A\u4F4D\u4E0D\u591F\uFF0C\u62C6\u5305\u9700\u8981 6 \u4E2A\u7A7A\u4F4D";
        this.removeOwned(p, uid);
        for (let i = 0; i < 6; i++) this.addToBag(p, newItem(one));
        return null;
      }
      case "scroll":
        if (k.effect === "town" || k.effect === "escape") {
          this.removeOwned(p, uid);
          this.pendingTeleport = { ...p.home };
          return null;
        }
        if (k.effect === "random") {
          this.removeOwned(p, uid);
          for (let t = 0; t < 50; t++) {
            const x = random(this.map.width);
            const y = random(this.map.height);
            if (this.canEnter(x, y)) {
              this.pendingTeleport = { map: this.map.id, x, y };
              break;
            }
          }
          return null;
        }
        return "\u8FD9\u4E2A\u6682\u65F6\u7528\u4E0D\u4E86";
      case "book": {
        const skill = this.catalog.skillsByName.get(def.name);
        if (!skill) return "\u6CA1\u6709\u8FD9\u4E2A\u6280\u80FD";
        if (skill.job !== p.job) return "\u4F60\u7684\u804C\u4E1A\u5B66\u4E0D\u4E86\u8FD9\u4E2A\u6280\u80FD";
        if (p.level < k.level) return `\u9700\u8981 ${k.level} \u7EA7\u624D\u80FD\u5B66`;
        if (p.skills.some((s) => s.id === skill.id)) return "\u5DF2\u7ECF\u5B66\u4F1A\u4E86";
        this.removeOwned(p, uid);
        const key = [1, 2, 3, 4, 5, 6, 7, 8].find((n) => !p.skills.some((s) => s.key === n)) ?? 0;
        p.skills.push({ id: skill.id, level: 0, train: 0, key: isPassive(skill.id) ? 0 : key });
        this.recalc(p);
        this.events.push({ type: "msg", text: `\u5B66\u4F1A\u4E86 ${skill.name}`, color: "good" });
        return null;
      }
      case "equip":
      case "amulet":
        return this.equip(p, uid);
      default:
        return "\u8FD9\u4E2A\u4E1C\u897F\u4E0D\u80FD\u76F4\u63A5\u4F7F\u7528";
    }
  }
  /** 普通药：分几次慢慢回（1.76 是约 1 秒一跳）。 */
  queueRecover(p, hp, mp) {
    const n = 5;
    for (let i = 0; i < n; i++) {
      this.after(i * 400, () => {
        if (p.dead) return;
        const h = Math.min(Math.ceil(hp / n), p.stats.maxHp - p.hp);
        const m = Math.min(Math.ceil(mp / n), p.stats.maxMp - p.mp);
        p.hp += Math.max(0, h);
        p.mp += Math.max(0, m);
      });
    }
  }
  canWear(p, def) {
    const r2 = requirement(def);
    const have = r2.stat === "level" ? p.level : r2.stat === "dc" ? p.stats.dc[1] : r2.stat === "mc" ? p.stats.mc[1] : p.stats.sc[1];
    const label = { level: "\u7B49\u7EA7", dc: "\u653B\u51FB\u4E0A\u9650", mc: "\u9B54\u6CD5\u4E0A\u9650", sc: "\u9053\u672F\u4E0A\u9650" }[r2.stat];
    if (have < r2.value) return `\u9700\u8981${label} ${r2.value}\uFF08\u4F60\u73B0\u5728 ${have}\uFF09`;
    const sex = dressSex(def);
    if (sex !== null && sex !== p.sex) return "\u6027\u522B\u4E0D\u7B26";
    return null;
  }
  /** 穿戴；prefer 指定左右手镯/戒指位。 */
  equip(p, uid, prefer) {
    const f = this.findOwned(p, uid);
    if (!f) return null;
    const def = this.catalog.item(f.item.name);
    const k = itemKind(def);
    const slots = k.kind === "equip" ? k.slots : k.kind === "amulet" ? ["braceletL"] : [];
    if (!slots.length) return "\u8FD9\u4E2A\u4E0D\u80FD\u7A7F\u6234";
    const why = this.canWear(p, def);
    if (why) return why;
    if (slots[0] === "weapon") {
      if (def.weight > p.stats.maxHandWeight) return `\u6B66\u5668\u592A\u91CD\uFF08${def.weight}\uFF09\uFF0C\u4F60\u7684\u8155\u529B ${p.stats.maxHandWeight}`;
    } else {
      const cur = slots.map((s) => p.equip[s]);
      const freeWeight = this.wearWeight(p) - (cur[0] ? this.catalog.item(cur[0].name).weight : 0);
      if (freeWeight + def.weight > p.stats.maxWearWeight) return `\u7A7F\u4E0D\u52A8\u4E86\uFF08\u8D1F\u91CD\u4E0A\u9650 ${p.stats.maxWearWeight}\uFF09`;
    }
    if (prefer && !slots.includes(prefer)) return "\u4E0D\u80FD\u653E\u5728\u8FD9\u4E2A\u4F4D\u7F6E";
    const slot = prefer ?? slots.find((s) => !p.equip[s]) ?? slots[0];
    this.removeOwned(p, uid);
    const old = p.equip[slot];
    p.equip[slot] = f.item;
    if (old) {
      if (f.where === "bag") p.bag[f.index] = old;
      else this.addToBag(p, old);
    }
    this.recalc(p);
    return null;
  }
  unequip(p, slot) {
    const it = p.equip[slot];
    if (!it) return null;
    const i = p.bag.indexOf(null);
    if (i < 0) return "\u5305\u88F9\u6EE1\u4E86";
    p.bag[i] = it;
    p.equip[slot] = null;
    this.recalc(p);
    return null;
  }
  // ---------------- 玩家：攻击 ----------------
  /** 朝某方向普通攻击（含战士剑术）。 */
  playerAttack(p, dir) {
    if (p.dead || this.now < p.busyUntil) return;
    p.dir = dir;
    const front = step(p, dir);
    const target = this.occupant(front.x, front.y);
    let style = "normal";
    const has2 = (id) => p.skills.some((s) => s.id === id);
    if (p.fireReady && target && this.isEnemy(p, target)) style = "fire";
    else if (p.powerHitReady && has2(7)) style = "power";
    else if (p.wideHitOn && has2(25) && p.mp >= 3) style = "wide";
    else if (p.longHitOn && has2(12)) style = "long";
    this.setAction(p, style === "normal" ? "attack" : "heavy", HIT_ANIM_MS, { hit: style });
    p.busyUntil = this.now + Math.max(HIT_ANIM_MS, p.stats.hitInterval);
    this.sound(weaponSwing(this.weaponShape(p)));
    const styleSound = { normal: 0, power: p.sex === 1 ? S.yedoWoman : S.yedoMan, long: 0, wide: S.wideHit, fire: S.fireHit }[style];
    if (styleSound) this.sound(styleSound);
    if (style === "long" && target) this.sound(S.longHit);
    resolvePlayerHit(this, p, dir, style);
  }
  // ---------------- 怪物 AI（行为在 monster_ai.ts） ----------------
  /** range 格以内最近的敌人（怪看不见隐身的，除非它能看穿隐身）。 */
  nearestEnemy(m, range) {
    let best;
    let bd = Infinity;
    const seesInvisible = m.kind !== "monster" || m.def.coolEye;
    for (const a of this.actors.values()) {
      if (!this.isEnemy(m, a) || a.invisibleUntil > this.now && !seesInvisible) continue;
      const d = chebyshev(m, a);
      if (d <= range && d < bd) {
        best = a;
        bd = d;
      }
    }
    return best;
  }
  // ---------------- 寻路 ----------------
  pathTo(from, to, adjacent = false) {
    if (samePos(from, to)) return [];
    return findPath(this, from, to, 3e3, adjacent);
  }
  /** 怪物绕路：走到目标旁边的短路径。 */
  findPathFor(m, to) {
    return findPath(this, m, to, 200, true);
  }
};
function isPassive(skillId) {
  return skillId === 3 || skillId === 4 || skillId === 7 || skillId === 12;
}

// src/render/frames.ts
var HA = {
  stand: [0, 4, 4, 200, 0],
  walk: [64, 6, 2, 90, 2],
  run: [128, 6, 2, 120, 3],
  warMode: [192, 1, 0, 200, 0],
  hit: [200, 6, 2, 85, 0],
  heavyHit: [264, 6, 2, 90, 0],
  bigHit: [328, 8, 0, 70, 0],
  spell: [392, 6, 2, 60, 0],
  struck: [472, 3, 5, 70, 0],
  die: [536, 4, 4, 120, 0]
};
var HUMAN_FRAMES = 600;
function frameOf(info, a, now, loop) {
  const [, frames, , ftime] = info;
  if (frames <= 1) return 0;
  const elapsed = now - a.startedAt;
  if (loop) return Math.floor(elapsed / ftime) % frames;
  const per = a.duration > 0 ? a.duration / frames : ftime;
  return Math.min(frames - 1, Math.max(0, Math.floor(elapsed / per)));
}
function pick(info, dir, f) {
  const [start, frames, skip] = info;
  return start + dir * (frames + skip) + f;
}
function finished(a, now) {
  return now - a.startedAt >= a.duration;
}
function humanFrame(a, dir, now, dead) {
  if (dead) return pick(HA.die, dir, HA.die[1] - 1);
  if (finished(a, now) && a.kind !== "die") return pick(HA.stand, dir, frameOf(HA.stand, a, now, true));
  switch (a.kind) {
    case "walk":
      return pick(HA.walk, dir, frameOf(HA.walk, a, now, false));
    case "run":
      return pick(HA.run, dir, frameOf(HA.run, a, now, false));
    case "attack":
      return pick(HA.hit, dir, frameOf(HA.hit, a, now, false));
    case "heavy":
      return a.hit === "wide" || a.hit === "fire" ? pick(HA.bigHit, dir, frameOf(HA.bigHit, a, now, false)) : pick(HA.heavyHit, dir, frameOf(HA.heavyHit, a, now, false));
    case "spell":
      return pick(HA.spell, dir, frameOf(HA.spell, a, now, false));
    case "struck":
      return pick(HA.struck, dir, frameOf(HA.struck, a, now, false));
    case "die":
    case "dead":
      return pick(HA.die, dir, frameOf(HA.die, a, now, false));
    default:
      return pick(HA.stand, dir, frameOf(HA.stand, a, now, true));
  }
}
function monsterLook(raceImg) {
  return {
    herb: [13, 33, 34, 35].includes(raceImg),
    deathNoDir: [23, 48, 49].includes(raceImg),
    criticalAttack: raceImg === 33
  };
}
function monsterFrame(t, look, a, dir, now, dead, stone) {
  const deathDir = look.deathNoDir ? 0 : dir;
  if (dead) {
    if (a.kind === "die" && !finished(a, now)) return pick(t.ActDie, dir, frameOf(t.ActDie, a, now, false));
    return pick(t.ActDie, dir, t.ActDie[1] - 1);
  }
  if (stone) return pick(t.ActDeath, deathDir, 0);
  const stand = () => pick(t.ActStand, look.herb ? 0 : dir, frameOf(t.ActStand, a, now, true));
  if (finished(a, now) || a.kind === "stand") return stand();
  switch (a.kind) {
    case "walk":
    case "run":
      return t.ActWalk[1] ? pick(t.ActWalk, dir, frameOf(t.ActWalk, a, now, false)) : pick(t.ActStand, dir, 0);
    case "attack":
    case "heavy": {
      const info = look.criticalAttack ? t.ActCritical : t.ActAttack;
      return pick(info, dir, frameOf(info, a, now, false));
    }
    case "struck":
      return t.ActStruck[1] ? pick(t.ActStruck, dir, frameOf(t.ActStruck, a, now, false)) : stand();
    case "digup":
      return look.herb ? pick(t.ActWalk, 0, frameOf(t.ActWalk, a, now, false)) : pick(t.ActDeath, deathDir, frameOf(t.ActDeath, a, now, false));
    case "digdown":
      return pick(t.ActDeath, look.herb ? 0 : deathDir, frameOf(t.ActDeath, a, now, false));
    default:
      return stand();
  }
}
var WORDER_SRC = [
  "000000001111111111111111111111110000000000001111000011110000111100000000111111111111111111111111000000000000000100000001000000010000000011111111111111111111111100111111001110010000000100000001011100001110001111100000111000001111111111111111111000000000000011110011011000110110001111100000111001111111111101111111000111000111101111010000110000001111100011001000111000010110000000001110111110000000001100000011000000111000011111111111011111110011001100010011001011111100010000011111111111111111111111111111000111110001111100011111000111110011111101111111111111111111111100011111000111110001111100011111",
  "000000001111111111111111111111110000000000001111000011110000111100000000111111111111111111111111000000000000000100000001000000010000000011111111111111111111111100111111001110010000000100000001111100001110001111100000111000001111111111111111111000000000000011110011011000110110001111100000111001111111111101111111000111000111101111010000110000001111100011001000111000010110000000001110111110000000001100000011000000111000011111111111011111110011001100010011001011111100010000011111111111111111111111111111000111110001111100011111000111110011111101111111111111111111111100011111000111110001111100011111"
];
function weaponInFront(sex, frame) {
  return WORDER_SRC[sex][frame] === "1";
}

// src/render/renderer.ts
var LONG_ROWS = 22;
var WIDE_COLS = 10;
var Renderer = class {
  constructor(canvas, sprites, world, catalog) {
    this.canvas = canvas;
    this.sprites = sprites;
    this.world = world;
    this.catalog = catalog;
    this.ground = /* @__PURE__ */ new Map();
    this.floaters = [];
    this.hits = [];
    this.camX = 0;
    this.camY = 0;
    /** 逻辑像素尺寸（不含缩放） */
    this.width = 800;
    this.height = 600;
    this.zoom = 1;
    this.hoverActor = 0;
    /** 锁定的目标（手机）：脚下画一个圈 */
    this.targetActor = 0;
    this.hoverItem = null;
    this.view = { showHpBars: true, showItemNames: true, showMonsterNames: true };
    this.dpr = 1;
    /** 人物在画面纵向的位置：原版底栏高，人物略偏上 */
    this.centerY = 0.47;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("\u6D4F\u89C8\u5668\u4E0D\u652F\u6301 Canvas");
    this.ctx = ctx;
  }
  get context() {
    return this.ctx;
  }
  /** 画布铺满窗口；zoom 是地图的缩放（CSS 像素 / 逻辑像素），由 ui/viewport 按设备定。 */
  resize(cssW, cssH, zoom) {
    this.dpr = Math.min(2, window.devicePixelRatio || 1);
    this.zoom = zoom;
    this.width = Math.round(cssW / this.zoom);
    this.height = Math.round(cssH / this.zoom);
    this.canvas.width = Math.round(cssW * this.dpr);
    this.canvas.height = Math.round(cssH * this.dpr);
    this.canvas.style.width = `${cssW}px`;
    this.canvas.style.height = `${cssH}px`;
    this.scaleTo(this.zoom);
  }
  /** 把画布坐标切到「1 单位 = scale 个 CSS 像素」：地图用 zoom，界面用自己的缩放。 */
  scaleTo(scale) {
    this.ctx.setTransform(this.dpr * scale, 0, 0, this.dpr * scale, 0, 0);
    this.ctx.imageSmoothingEnabled = false;
  }
  /** 屏幕（CSS 像素）→ 逻辑像素。 */
  toLogical(cssX, cssY) {
    return { x: cssX / this.zoom, y: cssY / this.zoom };
  }
  /** 逻辑像素 → 地图格子。 */
  cellAt(lx, ly) {
    return { x: Math.floor((lx + this.camX) / UNITX), y: Math.floor((ly + this.camY) / UNITY) };
  }
  /**
   * 逻辑像素下的角色（取最靠前的一个）。slop > 0 时（触屏）正中没点到任何角色，
   * 就取点击框外 slop 以内离得最近的一个；exclude 是不参与放宽的角色（自己）。
   */
  actorAt(lx, ly, slop = 0, exclude = 0) {
    let best;
    for (const h of this.hits) {
      if (lx >= h.x && lx < h.x + h.w && ly >= h.y && ly < h.y + h.h && (!best || h.row >= best.row)) best = h;
    }
    if (best || slop <= 0) return best?.actor;
    let bd = slop;
    for (const h of this.hits) {
      if (h.actor.id === exclude) continue;
      const dx = Math.max(h.x - lx, 0, lx - (h.x + h.w));
      const dy = Math.max(h.y - ly, 0, ly - (h.y + h.h));
      const d = Math.hypot(dx, dy);
      if (d <= bd) {
        best = h;
        bd = d;
      }
    }
    return best?.actor;
  }
  /** 角色在世界中的像素位置（含走路插值）。 */
  actorPixel(a, now) {
    const act = a.action;
    if ((act.kind === "walk" || act.kind === "run") && act.from) {
      const t = Math.min(1, (now - act.startedAt) / act.duration);
      return {
        x: (act.from.x + (a.x - act.from.x) * t) * UNITX,
        y: (act.from.y + (a.y - act.from.y) * t) * UNITY
      };
    }
    return { x: a.x * UNITX, y: a.y * UNITY };
  }
  /** 进游戏前把玩家周围一屏的地面和物件先下载好，避免一进来画面缺块。 */
  async preloadAround(center, onProgress) {
    const map = this.world.map;
    const rx = Math.ceil(this.width / UNITX / 2) + 2;
    const ry = Math.ceil(this.height / UNITY / 2) + 2;
    const objects = /* @__PURE__ */ new Map();
    for (let y = center.y - ry; y <= center.y + ry + LONG_ROWS; y++) {
      for (let x = center.x - rx - WIDE_COLS; x <= center.x + rx; x++) {
        if (!map.inside(x, y)) continue;
        const i = y * map.width + x;
        const f = map.front[i];
        if (!f) continue;
        const area = map.area[i];
        const lib = area === 0 ? "objects" : `objects${area + 1}`;
        const set = objects.get(lib) ?? /* @__PURE__ */ new Set();
        const ani = map.ani[i] & 127;
        for (let k = 0; k < Math.max(1, ani); k++) set.add(f - 1 + k);
        objects.set(lib, set);
      }
    }
    const jobs = [];
    for (const [lib, set] of objects) jobs.push(this.sprites.preload(lib, set));
    const bw = GROUND_BLOCK * UNITX;
    const bh = GROUND_BLOCK * UNITY;
    const cx = center.x * UNITX;
    const cy = center.y * UNITY;
    for (let by = Math.floor((cy - this.height) / bh); by <= Math.floor((cy + this.height) / bh); by++) {
      for (let bx = Math.floor((cx - this.width) / bw); bx <= Math.floor((cx + this.width) / bw); bx++) {
        if (bx < 0 || by < 0) continue;
        jobs.push(new Promise((res) => {
          const tick = () => {
            const v = this.ground.get(`${map.asset}/${bx}_${by}`);
            if (v === void 0) this.groundImage(map.asset, bx, by);
            if (v === "loading" || v === void 0) setTimeout(tick, 50);
            else res();
          };
          tick();
        }));
      }
    }
    let done = 0;
    onProgress(0, jobs.length);
    await Promise.all(jobs.map((j) => j.then(() => onProgress(++done, jobs.length))));
  }
  float(actor, text, color) {
    this.floaters.push({ actor, text, color, born: performance.now() });
  }
  // ---------------- 主绘制 ----------------
  draw() {
    const w = this.world;
    const map = w.map;
    if (!map) return;
    const now = w.now;
    const ctx = this.ctx;
    const me = w.player;
    this.scaleTo(this.zoom);
    const mp = this.actorPixel(me, now);
    this.camX = Math.round(mp.x + UNITX / 2 - this.width / 2);
    this.camY = Math.round(mp.y + UNITY / 2 - this.height * this.centerY);
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, this.width, this.height);
    this.drawGround(map);
    const left = Math.floor(this.camX / UNITX);
    const top = Math.floor(this.camY / UNITY);
    const right = Math.ceil((this.camX + this.width) / UNITX);
    const bottom = Math.ceil((this.camY + this.height) / UNITY);
    const aniCount = Math.floor(performance.now() / 100);
    const actorsByRow = /* @__PURE__ */ new Map();
    for (const a of w.actors.values()) {
      if (a.map !== map.id) continue;
      const px = this.actorPixel(a, now);
      const row = Math.round(px.y / UNITY);
      if (row < top - 2 || row > bottom + 4) continue;
      const list = actorsByRow.get(row) ?? [];
      list.push(a);
      actorsByRow.set(row, list);
    }
    const itemsByRow = /* @__PURE__ */ new Map();
    for (const it of w.items) {
      const list = itemsByRow.get(it.y) ?? [];
      list.push(it);
      itemsByRow.set(it.y, list);
    }
    for (let y = top - 1; y <= bottom + 1; y++) {
      for (let x = left - 1; x <= right + 1; x++) {
        const s = this.objectSprite(map, x, y, aniCount);
        if (s && !s.blend && s.sprite.w === UNITX && s.sprite.h === UNITY) {
          this.blit(s.sprite, x * UNITX - this.camX, (y + 1) * UNITY - s.sprite.h - this.camY);
        }
      }
    }
    this.hits = [];
    for (let y = top - 1; y <= bottom + LONG_ROWS; y++) {
      for (let x = left - WIDE_COLS; x <= right + 2; x++) {
        const s = this.objectSprite(map, x, y, aniCount);
        if (!s) continue;
        const sx = x * UNITX - this.camX;
        if (s.blend) {
          ctx.globalCompositeOperation = "lighter";
          this.blit(s.sprite, sx + s.sprite.ox - 2, y * UNITY - this.camY + s.sprite.oy - 68);
          ctx.globalCompositeOperation = "source-over";
        } else if (s.sprite.w !== UNITX || s.sprite.h !== UNITY) {
          this.blit(s.sprite, sx, (y + 1) * UNITY - s.sprite.h - this.camY);
        }
      }
      if (y <= bottom + 2) {
        for (const it of itemsByRow.get(y) ?? []) this.drawGroundItem(it);
        const row = actorsByRow.get(y);
        if (row) {
          row.sort((a, b) => Number(b.dead) - Number(a.dead));
          for (const a of row) this.drawActor(a, now, y);
        }
      }
    }
    this.drawEffects(now);
    this.drawOverlays(now);
  }
  blit(s, x, y) {
    if (x > this.width || y > this.height || x + s.w < 0 || y + s.h < 0) return;
    this.ctx.drawImage(s.img, s.sx, s.sy, s.w, s.h, x, y, s.w, s.h);
  }
  drawGround(map) {
    const bw = GROUND_BLOCK * UNITX;
    const bh = GROUND_BLOCK * UNITY;
    const bx0 = Math.floor(this.camX / bw);
    const by0 = Math.floor(this.camY / bh);
    const bx1 = Math.floor((this.camX + this.width) / bw);
    const by1 = Math.floor((this.camY + this.height) / bh);
    for (let by = by0 - 1; by <= by1 + 1; by++) {
      for (let bx = bx0 - 1; bx <= bx1 + 1; bx++) {
        if (bx < 0 || by < 0 || bx * GROUND_BLOCK >= map.width || by * GROUND_BLOCK >= map.height) continue;
        const img = this.groundImage(map.asset, bx, by);
        const visible = bx >= bx0 && bx <= bx1 && by >= by0 && by <= by1;
        if (img && visible) this.ctx.drawImage(img, bx * bw - this.camX, by * bh - this.camY, bw + 1, bh + 1);
      }
    }
  }
  groundImage(asset, bx, by) {
    const key = `${asset}/${bx}_${by}`;
    const cur = this.ground.get(key);
    if (cur instanceof HTMLImageElement) return cur;
    if (cur) return null;
    this.ground.set(key, "loading");
    const img = new Image();
    img.onload = () => this.ground.set(key, img);
    img.onerror = () => this.ground.set(key, "none");
    img.src = assetUrl(`assets/ground/${asset}/${bx}_${by}.webp`);
    return null;
  }
  /** 前景层某格要画的图（含动画、开门）。 */
  objectSprite(map, x, y, aniCount) {
    if (!map.inside(x, y)) return null;
    const i = y * map.width + x;
    let idx = map.front[i];
    if (!idx) return null;
    let ani = map.ani[i];
    const blend = (ani & 128) !== 0;
    ani &= 127;
    if (ani > 0) {
      const tick = map.aniTick[i];
      idx += Math.floor(aniCount % (ani + ani * tick) / (1 + tick));
    }
    const area = map.area[i];
    const lib = area === 0 ? "objects" : `objects${area + 1}`;
    const s = this.sprites.get(lib, idx - 1);
    return s ? { sprite: s, blend } : null;
  }
  drawGroundItem(it) {
    const look = isGold(it.item) ? goldLook(it.item.gold) : this.catalog.item(it.item.name).look;
    const s = this.sprites.get("dnitems", look);
    const cx = it.x * UNITX - this.camX + UNITX / 2;
    const cy = it.y * UNITY - this.camY + UNITY / 2;
    if (s) this.blit(s, Math.round(cx - s.w / 2), Math.round(cy - s.h / 2));
  }
  // ---------------- 角色 ----------------
  drawActor(a, now, row) {
    const px = this.actorPixel(a, now);
    const dx = Math.round(px.x - this.camX);
    const dy = Math.round(px.y - this.camY);
    const ctx = this.ctx;
    const invisible = a.invisibleUntil > now;
    if (invisible) ctx.globalAlpha = a.kind === "player" && a === this.world.player ? 0.45 : 0;
    let body;
    if (a.kind === "player") body = this.drawHuman(a, now, dx, dy);
    else if (a.kind === "monster") body = this.drawMonster(a, now, dx, dy);
    else {
      const off = npcOffset(a.def.appearance);
      const f = Math.floor(now / 200) % 4;
      body = this.sprites.get("npc", off + f);
      if (body) this.blit(body, dx + body.ox, dy + body.oy);
    }
    ctx.globalAlpha = 1;
    if (body && !a.dead) {
      this.hits.push({ actor: a, x: dx + body.ox, y: dy + body.oy, w: body.w, h: body.h, row });
    }
    if (a.shieldUntil > now) {
      const s = this.sprites.get("magic", 3890 + Math.floor(now / 100) % 3);
      if (s) {
        ctx.globalCompositeOperation = "lighter";
        this.blit(s, dx + s.ox, dy + s.oy);
        ctx.globalCompositeOperation = "source-over";
      }
    }
  }
  drawHuman(p, now, dx, dy) {
    const frame = humanFrame(p.action, p.dir, now, p.dead);
    const dressItem = p.equip.dress ? this.catalog.item(p.equip.dress.name) : null;
    const dress = (dressItem ? dressItem.shape : 0) * 2 + p.sex;
    const body = this.sprites.get("hum", dress * HUMAN_FRAMES + frame) ?? this.sprites.get("hum", p.sex * HUMAN_FRAMES + frame);
    const wItem = p.equip.weapon ? this.catalog.item(p.equip.weapon.name) : null;
    const weapon = wItem ? this.sprites.get("weapon", (wItem.shape * 2 + p.sex) * HUMAN_FRAMES + frame) : null;
    const hair = p.hair > 0 ? this.sprites.get("hair", (p.hair * 2 + p.sex) * HUMAN_FRAMES + frame) : null;
    const front = weaponInFront(p.sex, frame);
    if (weapon && !front) this.blit(weapon, dx + weapon.ox, dy + weapon.oy);
    if (body) this.blit(body, dx + body.ox, dy + body.oy);
    if (hair) this.blit(hair, dx + hair.ox, dy + hair.oy);
    if (weapon && front) this.blit(weapon, dx + weapon.ox, dy + weapon.oy);
    const a = p.action;
    if (a.kind === "heavy" && now - a.startedAt < a.duration) {
      const base = a.hit === "power" ? 800 : a.hit === "long" ? 1410 : a.hit === "wide" ? 1700 : a.hit === "fire" ? 3390 : -1;
      if (base >= 0) {
        const f = Math.min(5, Math.floor((now - a.startedAt) / a.duration * 6));
        const s = this.sprites.get("magic", base + p.dir * 10 + f);
        if (s) {
          this.ctx.globalCompositeOperation = "lighter";
          this.blit(s, dx + s.ox, dy + s.oy);
          this.ctx.globalCompositeOperation = "source-over";
        }
      }
    }
    return body;
  }
  drawMonster(m, now, dx, dy) {
    const t = this.world.actTable(m);
    if (!t) return null;
    if (m.cover === "buried" && m.action.kind !== "digdown") return null;
    const f = monsterFrame(t, monsterLook(m.def.raceImg), m.action, m.dir, now, m.dead, m.cover === "stone");
    const s = this.sprites.get(m.def.lib, m.def.offset + f);
    if (s) this.blit(s, dx + s.ox, dy + s.oy);
    return s;
  }
  // ---------------- 特效 ----------------
  drawEffects(now) {
    const ctx = this.ctx;
    for (const e of this.world.effects) {
      const p = this.effectPos(e, now);
      if (!p) continue;
      const f = Math.min(e.frames - 1, Math.floor((now - e.startedAt) / e.frameTime) % e.frames);
      const s = this.sprites.get(e.lib, e.start + f);
      if (!s) continue;
      if (e.blend) ctx.globalCompositeOperation = "lighter";
      this.blit(s, Math.round(p.x - this.camX + s.ox), Math.round(p.y - this.camY + s.oy));
      ctx.globalCompositeOperation = "source-over";
    }
  }
  effectPos(e, now) {
    let target;
    if ("actor" in e.at) {
      const a = this.world.actors.get(e.at.actor);
      if (!a) return null;
      target = this.actorPixel(a, now);
    } else {
      target = { x: e.at.x * UNITX, y: e.at.y * UNITY };
    }
    if (e.from) {
      const t = Math.min(1, (now - e.startedAt) / Math.max(1, e.duration));
      return { x: e.from.x * UNITX + (target.x - e.from.x * UNITX) * t, y: e.from.y * UNITY + (target.y - e.from.y * UNITY) * t };
    }
    return target;
  }
  // ---------------- 名字、血条、飘字 ----------------
  drawOverlays(now) {
    const ctx = this.ctx;
    const w = this.world;
    ctx.font = '12px "PingFang SC","Microsoft YaHei",sans-serif';
    ctx.textAlign = "center";
    ctx.textBaseline = "bottom";
    for (const a of w.actors.values()) {
      if (a.dead || a.map !== w.map.id || a.invisibleUntil > now && a !== w.player) continue;
      const px = this.actorPixel(a, now);
      const x = Math.round(px.x - this.camX + UNITX / 2);
      const y = Math.round(px.y - this.camY);
      if (x < -50 || x > this.width + 50 || y < -50 || y > this.height + 120) continue;
      if (a.id === this.targetActor) {
        ctx.strokeStyle = "#ff5a3a";
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.ellipse(x, y + UNITY / 2, 22, 10, 0, 0, Math.PI * 2);
        ctx.stroke();
      }
      const hovered = a.id === this.hoverActor || a.id === this.targetActor;
      if (a.kind === "npc" || hovered || a.kind === "monster" && this.view.showMonsterNames || a.kind === "player") {
        const name = a.kind === "player" ? a.name : a.kind === "npc" ? a.def.name : w.catalog.displayName(a.def.name);
        const color = a.kind === "npc" ? "#7fdcff" : a.kind === "monster" ? a.master ? "#9cff8a" : "#fff" : "#fff";
        this.text(name, x, y - 66, color);
      }
      if (this.view.showHpBars && a.kind !== "npc") {
        const max = a.kind === "player" ? a.stats.maxHp : a.maxHp;
        const ratio = Math.max(0, Math.min(1, a.hp / max));
        ctx.fillStyle = "rgba(0,0,0,.6)";
        ctx.fillRect(x - 16, y - 62, 32, 4);
        ctx.fillStyle = a.kind === "player" ? "#2ecc71" : a.master ? "#2ecc71" : "#e74c3c";
        ctx.fillRect(x - 16, y - 62, Math.round(32 * ratio), 4);
      }
    }
    if (this.view.showItemNames) {
      for (const it of w.items) {
        const x = it.x * UNITX - this.camX + UNITX / 2;
        const y = it.y * UNITY - this.camY + 4;
        if (x < -40 || x > this.width + 40 || y < -20 || y > this.height + 20) continue;
        const name = isGold(it.item) ? `\u91D1\u5E01 ${it.item.gold}` : it.item.name;
        const kind = isGold(it.item) ? "gold" : itemKind(this.catalog.item(it.item.name)).kind;
        this.text(name, x, y, kind === "equip" ? "#ffd76b" : kind === "gold" ? "#ffe08a" : "#ddd");
      }
    }
    const t = performance.now();
    this.floaters = this.floaters.filter((f) => t - f.born < 1100);
    ctx.font = "bold 14px sans-serif";
    for (const f of this.floaters) {
      const a = w.actors.get(f.actor);
      if (!a) continue;
      const px = this.actorPixel(a, now);
      const k = (t - f.born) / 1100;
      this.text(f.text, px.x - this.camX + UNITX / 2, px.y - this.camY - 72 - k * 30, f.color);
    }
  }
  text(s, x, y, color) {
    const ctx = this.ctx;
    ctx.fillStyle = "#000";
    ctx.fillText(s, x + 1, y + 1);
    ctx.fillStyle = color;
    ctx.fillText(s, x, y);
  }
};
function goldLook(n) {
  if (n >= 1e3) return 116;
  if (n >= 500) return 115;
  if (n >= 300) return 114;
  if (n >= 100) return 113;
  return 112;
}
function npcOffset(appr) {
  if (appr <= 22) return appr * 60;
  if (appr === 23) return 1380;
  if (appr === 24 || appr === 25) return (appr - 24) * 60 + 1470;
  if (appr === 27 || appr === 32) return (appr - 26) * 60 + 1620 - 30;
  if (appr === 26 || appr >= 28 && appr <= 41) return (appr - 26) * 60 + 1620;
  if (appr === 42 || appr === 43) return 2580;
  if (appr >= 44 && appr <= 47) return 2640;
  if (appr >= 48 && appr <= 50) return (appr - 48) * 60 + 2700;
  if (appr === 51) return 2880;
  if (appr === 52) return 2960;
  return 0;
}

// src/game/controller.ts
var Controller = class {
  constructor(world, settings) {
    this.world = world;
    this.settings = settings;
    this.intent = { kind: "none" };
    /** 最近选中的目标（点过的怪），技能默认打它 */
    this.focus = 0;
    this.lastPotion = 0;
    this.loadingMap = false;
    /** 打开 NPC 对话的回调（UI 实现） */
    this.onTalk = () => {
    };
    this.onMessage = () => {
    };
    /** 设置改了（存档、刷新显示） */
    this.onSettingsChanged = () => {
    };
    this.repaths = 0;
  }
  get me() {
    return this.world.player;
  }
  // ---------------- 输入入口 ----------------
  steer(target, run) {
    this.intent = { kind: "steer", target, run };
  }
  goTo(target, run = true) {
    this.repaths = 0;
    const path = this.world.pathTo(this.me, target) ?? [];
    this.intent = { kind: "goto", target, path, run };
  }
  attack(target) {
    if (target.kind === "npc") {
      this.intent = { kind: "talk", npc: target.id };
      return;
    }
    if (!this.world.isEnemy(this.me, target)) return;
    this.focus = target.id;
    this.intent = { kind: "attack", target: target.id, once: !this.settings.stickyAttack };
  }
  pickup(item) {
    this.intent = { kind: "pickup", item: item.id };
  }
  stop() {
    if (this.intent.kind === "steer" || this.intent.kind === "stick") this.intent = { kind: "none" };
  }
  /** 摇杆推向某个方向（dx、dy 各是 -1/0/1）。 */
  stick(dx, dy, run) {
    this.intent = { kind: "stick", dx, dy, run };
  }
  /**
   * 手机点角色：点 NPC 走过去对话；点怪只锁定为目标（攻击键、技能打它），
   * 正在打别的怪时改打它。
   */
  lock(target) {
    if (target.kind === "npc") {
      this.intent = { kind: "talk", npc: target.id };
      return;
    }
    if (target.kind !== "monster") return;
    this.focus = target.id;
    if (this.intent.kind === "attack" && this.world.isEnemy(this.me, target)) this.attack(target);
  }
  /** 取消锁定（也不再打它）。 */
  unlock() {
    if (this.intent.kind === "attack" && this.intent.target === this.focus) this.intent = { kind: "none" };
    this.focus = 0;
  }
  /** 锁定的目标（还活着、在这张图上）。 */
  focused() {
    const a = this.world.actors.get(this.focus);
    return a && !a.dead && a.map === this.me.map ? a : void 0;
  }
  /** 放技能：目标优先用鼠标下的怪，其次当前攻击目标，否则鼠标所在格。 */
  cast(skillId, hover, cell) {
    const w = this.world;
    const cur = w.actors.get(this.intent.kind === "attack" ? this.intent.target : this.focus);
    const target = hover && !hover.dead ? hover : cur && !cur.dead ? cur : void 0;
    if (target && w.isEnemy(this.me, target)) this.focus = target.id;
    const r2 = castSpell(w, this.me, skillId, { pos: target ? target.pos : cell, actor: target });
    if (!r2.ok && r2.why) this.onMessage(r2.why, "bad");
    if (r2.ok && target && w.isEnemy(this.me, target) && this.me.job !== 0) {
      this.intent = { kind: "none" };
    }
  }
  castByKey(key, hover, cell) {
    const s = this.me.skills.find((x) => x.key === key);
    if (s) this.cast(s.id, hover, cell);
  }
  /** 触屏放技能：没有鼠标指着的怪，就打当前目标，再没有就打最近的怪。 */
  castByKeyAuto(key) {
    const s = this.me.skills.find((x) => x.key === key);
    if (s) this.castSkillAuto(s.id);
  }
  castSkillAuto(skillId) {
    const t = this.autoTarget();
    this.cast(skillId, t, t ? t.pos : this.me.pos);
  }
  /** 触屏攻击键：打当前目标，没有就打身边最近的怪。 */
  attackNearest() {
    const t = this.autoTarget();
    if (t) this.attack(t);
    else this.onMessage("\u9644\u8FD1\u6CA1\u6709\u602A", "bad");
  }
  /** 当前目标（还活着、不太远），否则 10 格内最近的敌人。 */
  autoTarget() {
    const w = this.world;
    const cur = w.actors.get(this.intent.kind === "attack" ? this.intent.target : this.focus);
    if (cur && w.isEnemy(this.me, cur) && chebyshev(this.me, cur) <= 12) return cur;
    return w.nearestEnemy(this.me, 10);
  }
  // ---------------- 每帧 ----------------
  update() {
    const w = this.world;
    const p = this.me;
    if (w.pendingTeleport && !this.loadingMap) void this.teleport();
    if (p.dead || this.loadingMap) return;
    this.autoPotion();
    if (w.now < p.busyUntil) return;
    switch (this.intent.kind) {
      case "steer":
        this.stepToward(this.intent.target, this.intent.run);
        return;
      case "stick": {
        const it = this.intent;
        this.stepToward({ x: p.x + it.dx * 4, y: p.y + it.dy * 4 }, it.run);
        return;
      }
      case "goto":
        this.followPath();
        return;
      case "attack":
        this.doAttack();
        return;
      case "talk": {
        const npc = w.actors.get(this.intent.npc);
        if (!npc) {
          this.intent = { kind: "none" };
          return;
        }
        if (chebyshev(p, npc) <= 3) {
          this.intent = { kind: "none" };
          this.onTalk(npc.id);
        } else this.approach(npc.pos);
        return;
      }
      case "pickup": {
        const id = this.intent.item;
        const it = w.items.find((i) => i.id === id);
        if (!it) {
          this.intent = { kind: "none" };
          return;
        }
        if (samePos(p, it)) {
          w.pickUp(p);
          this.intent = { kind: "none" };
        } else this.approach(it, false);
        return;
      }
      case "none":
        this.idle();
    }
  }
  async teleport() {
    const w = this.world;
    const t = w.pendingTeleport;
    this.loadingMap = true;
    try {
      const map = t.map === w.map.id ? w.map : await loadMap(w.catalog.map(t.map));
      w.pendingTeleport = null;
      w.enterMap(map, t);
      if (this.isTown(t.map)) this.me.home = { map: t.map, x: this.me.x, y: this.me.y };
      this.intent = { kind: "none" };
    } catch (e) {
      this.onMessage(`\u5730\u56FE\u52A0\u8F7D\u5931\u8D25\uFF1A${e.message}`, "bad");
      w.pendingTeleport = null;
    } finally {
      this.loadingMap = false;
    }
  }
  isTown(_map) {
    return false;
  }
  stepToward(target, run) {
    const p = this.me;
    if (samePos(p, target)) return;
    const dir = dirTo(p, target);
    const far = chebyshev(p, target) >= 2;
    if (run && far && this.world.move(p, dir, true)) return;
    if (this.world.move(p, dir, false)) return;
    for (const d of [1, 7, 2, 6]) {
      if (this.world.move(p, (dir + d) % 8, false)) return;
    }
    this.world.turn(p, dir);
  }
  followPath() {
    const it = this.intent;
    if (it.kind !== "goto") return;
    const p = this.me;
    const dir = it.path[0];
    if (samePos(p, it.target)) {
      this.intent = { kind: "none" };
      return;
    }
    if (dir === void 0) {
      it.path = this.world.pathTo(p, it.target) ?? [];
      if (!it.path.length || ++this.repaths > 40) {
        this.intent = { kind: "none" };
        this.repaths = 0;
      }
      return;
    }
    const next2 = it.path[1];
    if (it.run && next2 === dir && this.world.move(p, dir, true)) {
      it.path.splice(0, 2);
      return;
    }
    if (this.world.move(p, dir, false)) {
      it.path.shift();
      return;
    }
    it.path = this.world.pathTo(p, it.target) ?? [];
    if (!it.path.length) this.intent = { kind: "none" };
  }
  /** 走到目标旁边（或站上去）。 */
  approach(target, adjacent = true) {
    const path = this.world.pathTo(this.me, target, adjacent);
    const d = path?.[0];
    if (d === void 0 || !this.world.move(this.me, d, false)) {
      this.world.turn(this.me, dirTo(this.me, target));
      this.me.busyUntil = this.world.now + 200;
    }
  }
  doAttack() {
    const it = this.intent;
    if (it.kind !== "attack") return;
    const w = this.world;
    const p = this.me;
    const t = w.actors.get(it.target);
    if (!t || t.dead || !w.isEnemy(p, t)) {
      this.intent = { kind: "none" };
      return;
    }
    const d = chebyshev(p, t);
    const longOk = p.longHitOn && !!skillOf(p, 12) && d === 2 && inLine(p, t) && this.clearBetween(p, t);
    if (d === 1 || longOk) {
      this.maybeFire();
      w.playerAttack(p, dirTo(p, t));
      if (it.once) this.intent = { kind: "none" };
      return;
    }
    this.approach(t.pos);
  }
  clearBetween(a, b) {
    const mid = step(a, dirTo(a, b));
    return !this.world.occupant(mid.x, mid.y);
  }
  maybeFire() {
    const p = this.me;
    if (!this.settings.autoFireSword || p.fireReady || !skillOf(p, 26)) return;
    if ((p.cooldown.get(26) ?? 0) > this.world.now) return;
    const r2 = castSpell(this.world, p, 26, { pos: p.pos });
  }
  idle() {
    const w = this.world;
    const p = this.me;
    if (this.settings.autoFightBack && p.lastHitBy) {
      const a = w.actors.get(p.lastHitBy);
      p.lastHitBy = 0;
      if (a && !a.dead && w.isEnemy(p, a) && chebyshev(p, a) <= 3) {
        this.intent = { kind: "attack", target: a.id, once: false };
        return;
      }
    }
    if (this.settings.autoPickup) {
      const it = this.nearestLoot(6);
      if (it) this.intent = { kind: "pickup", item: it.id };
    }
  }
  nearestLoot(radius) {
    const w = this.world;
    const p = this.me;
    let best;
    let bd = Infinity;
    for (const it of w.items) {
      const d = chebyshev(p, it);
      if (d > radius || d >= bd) continue;
      if (!isGold(it.item)) {
        const def = w.catalog.item(it.item.name);
        if (this.settings.pickupValuableOnly) {
          const k = itemKind(def).kind;
          if (k !== "equip" && k !== "book" && k !== "amulet") continue;
        }
        if (w.bagWeight(p) + def.weight > p.stats.maxBagWeight) continue;
        if (!p.bag.includes(null)) continue;
      }
      const occ = w.occupant(it.x, it.y);
      if (occ && occ !== p) continue;
      best = it;
      bd = d;
    }
    return best;
  }
  autoPotion() {
    const w = this.world;
    const p = this.me;
    if (w.now - this.lastPotion < 1500) return;
    const s = this.settings;
    const want = (cur, max, pct) => pct > 0 && cur < max * pct / 100;
    const pick2 = (field) => {
      const all = [...p.belt, ...p.bag];
      let best;
      for (const it of all) {
        if (!it) continue;
        const k = itemKind(w.catalog.item(it.name));
        if (k.kind !== "potion") continue;
        const v = field === "hp" ? k.hp : k.mp;
        if (v > 0 && (!best || (k.instant ? v * 0.5 : v) > best.v)) best = { uid: it.uid, v: k.instant ? v * 0.5 : v };
      }
      return best?.uid;
    };
    let used = false;
    if (want(p.hp, p.stats.maxHp, s.autoHpPercent)) {
      const uid = pick2("hp");
      if (uid !== void 0) {
        w.useItem(p, uid);
        used = true;
      }
    }
    if (want(p.mp, p.stats.maxMp, s.autoMpPercent)) {
      const uid = pick2("mp");
      if (uid !== void 0) {
        w.useItem(p, uid);
        used = true;
      }
    }
    if (used) this.lastPotion = w.now;
  }
  /** 技能栏：主动技能按快捷键排序。 */
  activeSkills() {
    return this.me.skills.filter((s) => !isPassive(s.id)).map((s) => ({ id: s.id, key: s.key }));
  }
};
function inLine(a, b) {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  return dx === 0 || dy === 0 || Math.abs(dx) === Math.abs(dy);
}

// src/game/input.ts
function bindInput(canvas, r2, ctl, ui, uiScale) {
  let held = null;
  let uiPointer = null;
  let lastMouse = { x: 0, y: 0 };
  const stick = ui.joystick;
  const css = (e) => {
    const rect = canvas.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  };
  const logical = (e) => {
    const c = css(e);
    return r2.toLogical(c.x, c.y);
  };
  const uiPos = (e) => {
    const c = css(e);
    const s = uiScale();
    return { x: c.x / s, y: c.y / s };
  };
  const actorUnder = (p, touch) => {
    const a = r2.actorAt(p.x, p.y, touch ? 14 / r2.zoom : 0, ctl.me.id);
    return a && a.id !== ctl.me.id ? a : void 0;
  };
  const groundItem = (p) => {
    const cell = r2.cellAt(p.x, p.y);
    return ctl.world.items.find((i) => i.x === cell.x && i.y === cell.y);
  };
  canvas.addEventListener("contextmenu", (e) => e.preventDefault());
  canvas.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    const p = logical(e);
    lastMouse = p;
    const u = uiPos(e);
    if (ui.pointerDown(u.x, u.y, e.button)) {
      uiPointer = e.pointerId;
      return;
    }
    const touch = e.pointerType !== "mouse";
    try {
      canvas.setPointerCapture(e.pointerId);
    } catch {
    }
    if (ui.layout.joystick) {
      const target2 = actorUnder(p, true);
      if (target2) return ctl.lock(target2);
      const item2 = groundItem(p);
      if (item2) return ctl.pickup(item2);
      if (!stick.active && u.x < ui.W / 2) stick.start(e.pointerId, u);
      return;
    }
    const target = actorUnder(p, touch);
    if (target) return ctl.attack(target);
    const item = groundItem(p);
    if (item && e.button === 0) return ctl.pickup(item);
    const c = css(e);
    const cell = r2.cellAt(p.x, p.y);
    held = { id: e.pointerId, run: e.button === 2, start: performance.now(), moved: false, touch, x0: c.x, y0: c.y };
    if (touch) ctl.goTo(cell);
    else ctl.steer(cell, held.run);
  });
  canvas.addEventListener("pointermove", (e) => {
    if (stick.pointer === e.pointerId) {
      const d = stick.move(uiPos(e));
      if (d) ctl.stick(d.dx, d.dy, d.run);
      else ctl.stop();
      return;
    }
    const p = logical(e);
    lastMouse = p;
    const u = uiPos(e);
    ui.pointerMove(u.x, u.y);
    if (e.pointerType === "mouse") r2.hoverActor = ui.covers(u.x, u.y) ? 0 : actorUnder(p, false)?.id ?? 0;
    if (!held || held.id !== e.pointerId) return;
    const c = css(e);
    if (held.touch && !held.moved && Math.hypot(c.x - held.x0, c.y - held.y0) < 12) return;
    held.moved = true;
    const cell = r2.cellAt(p.x, p.y);
    ctl.steer(cell, held.run || held.touch && performance.now() - held.start > 250);
  });
  const release = (e) => {
    if (stick.pointer === e.pointerId) {
      stick.end();
      ctl.stop();
      return;
    }
    if (uiPointer === e.pointerId || e.pointerType === "mouse") {
      const u = uiPos(e);
      ui.pointerUp(u.x, u.y);
      uiPointer = null;
    }
    if (!held || held.id !== e.pointerId) return;
    const wasTouchTap = held.touch && !held.moved;
    held = null;
    if (!wasTouchTap) ctl.stop();
  };
  canvas.addEventListener("pointerup", release);
  canvas.addEventListener("pointercancel", release);
  window.addEventListener("keydown", (e) => {
    const hover = r2.actorAt(lastMouse.x, lastMouse.y);
    const cell = r2.cellAt(lastMouse.x, lastMouse.y);
    const f = /^F([1-8])$/.exec(e.key);
    if (f) {
      e.preventDefault();
      ctl.castByKey(Number(f[1]), hover, cell);
      return;
    }
    if (/^[1-6]$/.test(e.key)) {
      const it = ctl.me.belt[Number(e.key) - 1];
      if (it) {
        const why = ctl.world.useItem(ctl.me, it.uid);
        if (why) ui.message(why, "bad");
      }
      return;
    }
    switch (e.key.toLowerCase()) {
      case "f9":
      case "b":
      case "i":
        e.preventDefault();
        ui.toggleBag();
        break;
      case "f10":
      case "c":
        e.preventDefault();
        ui.toggleState(0);
        break;
      case "f11":
      case "k":
        e.preventDefault();
        ui.toggleState(3);
        break;
      case " ":
        e.preventDefault();
        ctl.world.pickUp(ctl.me);
        break;
      case "tab":
      case "m":
        e.preventDefault();
        ui.toggleMiniMap();
        break;
      case "escape":
        ui.closeAll();
        break;
    }
  });
}

// src/game/settings.ts
var DEFAULT_SETTINGS = {
  autoPickup: true,
  pickupValuableOnly: false,
  autoHpPercent: 40,
  autoMpPercent: 20,
  stickyAttack: true,
  autoFightBack: true,
  autoFireSword: true,
  autoLongHitPosition: false,
  showHpBars: true,
  showItemNames: true,
  showMonsterNames: true,
  soundOn: true,
  musicOn: false
};
function loadSettings(raw) {
  const s = { ...DEFAULT_SETTINGS };
  if (!raw) return s;
  for (const k of Object.keys(s)) {
    const v = raw[k];
    if (typeof v === typeof s[k]) s[k] = v;
  }
  return s;
}
var SETTING_LABELS = [
  { key: "autoPickup", label: "\u81EA\u52A8\u6361\u9644\u8FD1\u7684\u7269\u54C1", kind: "bool" },
  { key: "pickupValuableOnly", label: "\u53EA\u6361\u88C5\u5907\u3001\u91D1\u5E01\u548C\u4E66", kind: "bool" },
  { key: "autoHpPercent", label: "\u8840\u91CF\u4F4E\u4E8E\u591A\u5C11\u81EA\u52A8\u559D\u7EA2\u836F\uFF08%\uFF0C0 \u5173\u95ED\uFF09", kind: "percent" },
  { key: "autoMpPercent", label: "\u84DD\u91CF\u4F4E\u4E8E\u591A\u5C11\u81EA\u52A8\u559D\u84DD\u836F\uFF08%\uFF0C0 \u5173\u95ED\uFF09", kind: "percent" },
  { key: "stickyAttack", label: "\u70B9\u4E00\u4E0B\u602A\u5C31\u4E00\u76F4\u6253\u5230\u6B7B", kind: "bool" },
  { key: "autoFightBack", label: "\u7AD9\u7740\u88AB\u6253\u65F6\u81EA\u52A8\u8FD8\u624B", kind: "bool" },
  { key: "autoFireSword", label: "\u6218\u58EB\uFF1A\u70C8\u706B\u51B7\u5374\u597D\u4E86\u81EA\u52A8\u5F00", kind: "bool" },
  { key: "showHpBars", label: "\u5934\u9876\u663E\u793A\u8840\u6761", kind: "bool" },
  { key: "showItemNames", label: "\u5730\u4E0A\u7269\u54C1\u663E\u793A\u540D\u5B57", kind: "bool" },
  { key: "showMonsterNames", label: "\u602A\u7269\u663E\u793A\u540D\u5B57", kind: "bool" },
  { key: "soundOn", label: "\u97F3\u6548", kind: "bool" },
  { key: "musicOn", label: "\u80CC\u666F\u97F3\u4E50", kind: "bool" }
];

// src/save/save.ts
var SAVE_VERSION = 1;
function toSave(p, settings) {
  return {
    version: SAVE_VERSION,
    savedAt: Date.now(),
    player: {
      name: p.name,
      job: p.job,
      sex: p.sex,
      hair: p.hair,
      level: p.level,
      exp: p.exp,
      gold: p.gold,
      hp: p.hp,
      mp: p.mp,
      map: p.map,
      x: p.x,
      y: p.y,
      home: p.home,
      bag: p.bag,
      belt: p.belt,
      storage: p.storage,
      equip: p.equip,
      skills: p.skills.map((s) => ({ ...s })),
      longHitOn: p.longHitOn,
      wideHitOn: p.wideHitOn
    },
    settings
  };
}
function fromSave(d, catalog, id, now) {
  const s = d.player;
  const p = new Player(id, s.name, s.job, s.sex, s.map, s.x, s.y, now);
  const valid = (it) => it && catalog.itemsByName.has(it.name) ? it : null;
  p.hair = s.hair;
  p.level = s.level;
  p.exp = s.exp;
  p.gold = s.gold;
  p.hp = s.hp;
  p.mp = s.mp;
  p.home = s.home;
  p.bag = Array.from({ length: BAG_SIZE }, (_, i) => valid(s.bag[i]));
  p.belt = Array.from({ length: 6 }, (_, i) => valid(s.belt[i]));
  p.storage = s.storage.filter((x) => valid(x));
  for (const k of EQUIP_SLOTS) p.equip[k] = valid(s.equip[k]);
  p.skills = s.skills.filter((x) => catalog.skillsById.has(x.id)).map((x) => ({ ...x }));
  p.longHitOn = s.longHitOn;
  p.wideHitOn = s.wideHitOn;
  const all = [...p.bag, ...p.belt, ...p.storage, ...Object.values(p.equip)];
  setNextUid(1 + Math.max(0, ...all.map((it) => it?.uid ?? 0)));
  return p;
}
var HttpSaveStore = class {
  async load() {
    const r2 = await fetch("api/save", { credentials: "same-origin", cache: "no-store" });
    if (r2.status === 404) return null;
    if (r2.status === 401) throw new Error("\u672A\u767B\u5F55");
    if (!r2.ok) throw new Error(`\u8BFB\u6863\u5931\u8D25 ${r2.status}`);
    return await r2.json();
  }
  async save(d) {
    const r2 = await fetch("api/save", {
      method: "PUT",
      credentials: "same-origin",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(d)
    });
    if (!r2.ok) throw new Error(`\u5B58\u6863\u5931\u8D25 ${r2.status}`);
  }
};

// src/ui/describe.ts
var r = (v) => v[0] === v[1] ? `${v[0]}` : `${v[0]}-${v[1]}`;
var has = (v) => v[0] > 0 || v[1] > 0;
function effectLines(e) {
  const out = [];
  if (has(e.dc)) out.push({ text: `\u653B\u51FB +${r(e.dc)}\u3000\uFF08\u666E\u901A\u653B\u51FB\u548C\u6218\u58EB\u5251\u672F\u7684\u4F24\u5BB3\uFF09` });
  if (has(e.mc)) out.push({ text: `\u9B54\u6CD5 +${r(e.mc)}\u3000\uFF08\u6CD5\u5E08\u6280\u80FD\u7684\u4F24\u5BB3\uFF09` });
  if (has(e.sc)) out.push({ text: `\u9053\u672F +${r(e.sc)}\u3000\uFF08\u9053\u58EB\u6280\u80FD\u7684\u4F24\u5BB3\u3001\u6CBB\u7597\u91CF\u548C\u53EC\u5524\u7269\u5F3A\u5EA6\uFF09` });
  if (has(e.ac)) out.push({ text: `\u9632\u5FA1 +${r(e.ac)}\u3000\uFF08\u6BCF\u6B21\u6328\u6253\u51CF\u53BB\u8FD9\u4E2A\u8303\u56F4\u91CC\u7684\u968F\u673A\u503C\uFF09` });
  if (has(e.mac)) out.push({ text: `\u9B54\u9632 +${r(e.mac)}\u3000\uFF08\u9B54\u6CD5\u4F24\u5BB3\u51CF\u53BB\u8FD9\u4E2A\u8303\u56F4\u91CC\u7684\u968F\u673A\u503C\uFF09` });
  if (e.hit) out.push({ text: `\u51C6\u786E +${e.hit}\u3000\uFF08\u66F4\u4E0D\u5BB9\u6613\u6253\u7A7A\uFF09` });
  if (e.agility) out.push({ text: `\u654F\u6377 +${e.agility}\u3000\uFF08\u66F4\u5BB9\u6613\u8EB2\u5F00\u602A\u7269\u7684\u653B\u51FB\uFF09` });
  if (e.attackSpeed > 0) out.push({ text: `\u653B\u51FB\u901F\u5EA6 +${e.attackSpeed}\u3000\uFF08\u6BCF 2 \u70B9\u8BA9\u4E24\u5200\u4E4B\u95F4\u5FEB 60 \u6BEB\u79D2\uFF09`, tone: "good" });
  if (e.attackSpeed < 0) out.push({ text: `\u653B\u51FB\u901F\u5EA6 ${e.attackSpeed}\u3000\uFF08\u51FA\u5200\u53D8\u6162\uFF09`, tone: "bad" });
  if (e.luck) out.push({ text: `\u5E78\u8FD0 +${e.luck}\u3000\uFF08\u6253\u51FA\u6700\u5927\u653B\u51FB\u7684\u673A\u4F1A\u66F4\u9AD8\uFF09`, tone: "good" });
  if (e.curse) out.push({ text: `\u8BC5\u5492 +${e.curse}\u3000\uFF08\u6253\u51FA\u6700\u5C0F\u653B\u51FB\u7684\u673A\u4F1A\u66F4\u9AD8\uFF09`, tone: "bad" });
  if (e.magicAvoid) out.push({ text: `\u9B54\u6CD5\u8EB2\u907F +${e.magicAvoid}\u3000\uFF08\u6709\u673A\u4F1A\u5B8C\u5168\u8EB2\u5F00\u9B54\u6CD5\uFF09` });
  if (e.poisonAvoid) out.push({ text: `\u6BD2\u7269\u8EB2\u907F +${e.poisonAvoid}\u3000\uFF08\u6709\u673A\u4F1A\u4E0D\u4E2D\u6BD2\uFF09` });
  if (e.hpRegen) out.push({ text: `\u4F53\u529B\u6062\u590D +${e.hpRegen}\u3000\uFF08\u6BCF 2 \u79D2\u591A\u56DE ${e.hpRegen} \u70B9\u8840\uFF09` });
  if (e.mpRegen) out.push({ text: `\u9B54\u6CD5\u6062\u590D +${e.mpRegen}\u3000\uFF08\u6BCF 2 \u79D2\u591A\u56DE ${e.mpRegen} \u70B9\u84DD\uFF09` });
  if (e.poisonRegen) out.push({ text: `\u4E2D\u6BD2\u6062\u590D +${e.poisonRegen}\u3000\uFF08\u4E2D\u6BD2\u65F6\u95F4\u7F29\u77ED\uFF09` });
  return out;
}
function itemLines(def, inst, p, compareWith) {
  const out = [{ text: def.name, tone: "title" }];
  const k = itemKind(def);
  switch (k.kind) {
    case "potion":
      if (k.hp) out.push({ text: `\u56DE\u590D\u4F53\u529B ${k.hp}${k.instant ? "\uFF08\u7ACB\u5373\uFF09" : "\uFF08\u51E0\u79D2\u5185\u6162\u6162\u56DE\u6EE1\uFF09"}` });
      if (k.mp) out.push({ text: `\u56DE\u590D\u9B54\u6CD5 ${k.mp}${k.instant ? "\uFF08\u7ACB\u5373\uFF09" : "\uFF08\u51E0\u79D2\u5185\u6162\u6162\u56DE\u6EE1\uFF09"}` });
      break;
    case "book":
      out.push({ text: `${JOB_NAME[k.job] ?? ""}\u6280\u80FD\u4E66\uFF0C${k.level} \u7EA7\u53EF\u5B66`, tone: p.job === k.job && p.level >= k.level ? void 0 : "bad" });
      break;
    case "scroll":
      out.push({ text: { town: "\u56DE\u5230\u57CE\u91CC", escape: "\u79BB\u5F00\u5730\u4E0B\u57CE\u56DE\u5230\u57CE\u91CC", random: "\u968F\u673A\u4F20\u9001\u5230\u672C\u5730\u56FE\u67D0\u5904", other: "\u7279\u6B8A\u5377\u8F74" }[k.effect] });
      break;
    case "bundle":
      out.push({ text: "\u53CC\u51FB\u62C6\u6210 6 \u74F6\uFF08\u9700\u8981 6 \u4E2A\u7A7A\u4F4D\uFF09" });
      break;
    case "amulet":
      out.push({ text: k.poison === 0 ? "\u9053\u58EB\u65BD\u6CD5\u6D88\u8017\uFF08\u7075\u9B42\u706B\u7B26\u3001\u53EC\u5524\u3001\u9690\u8EAB\uFF09" : k.poison === 1 ? "\u65BD\u6BD2\u672F\u7528\uFF1A\u7EFF\u6BD2\uFF0C\u6301\u7EED\u6389\u8840" : "\u65BD\u6BD2\u672F\u7528\uFF1A\u7EA2\u6BD2\uFF0C\u964D\u4F4E\u9632\u5FA1" });
      out.push({ text: "\u88C5\u5907\u5728\u5DE6\u624B\u956F\u4F4D\u7F6E\u4F7F\u7528", tone: "dim" });
      if (inst) out.push({ text: `\u5269\u4F59 ${Math.ceil(inst.dura / 100)} \u6B21`, tone: "dim" });
      break;
    case "equip": {
      const e = itemEffects(def);
      out.push(...effectLines(e));
      if (compareWith && compareWith.name !== def.name) out.push(...compareLines(e, itemEffects(compareWith), compareWith.name));
      break;
    }
    default:
      break;
  }
  const req = requirement(def);
  if (k.kind === "equip" && req.value > 0) {
    const label = { level: "\u7B49\u7EA7", dc: "\u653B\u51FB", mc: "\u9B54\u6CD5", sc: "\u9053\u672F" }[req.stat];
    const have = req.stat === "level" ? p.level : req.stat === "dc" ? p.stats.dc[1] : req.stat === "mc" ? p.stats.mc[1] : p.stats.sc[1];
    out.push({ text: `\u9700\u8981${label} ${req.value}`, tone: have >= req.value ? "dim" : "bad" });
  }
  out.push({ text: `\u91CD\u91CF ${def.weight}${inst && k.kind === "equip" ? `\u3000\u6301\u4E45 ${Math.ceil(inst.dura / 1e3)}/${Math.ceil(inst.duraMax / 1e3)}` : ""}`, tone: "dim" });
  out.push({ text: `\u5356\u7ED9\u5546\u5E97\u7EA6 ${Math.floor(def.price / 2)} \u91D1\u5E01`, tone: "gold" });
  return out;
}
function compareLines(a, b, oldName) {
  const diffs = [];
  const range = (name, x, y) => {
    const d0 = x[0] - y[0];
    const d1 = x[1] - y[1];
    if (d0 || d1) {
      const s = (n) => n > 0 ? `+${n}` : `${n}`;
      diffs.push({ text: `${name} ${s(d0)}/${s(d1)}`, tone: d0 + d1 >= 0 ? "good" : "bad" });
    }
  };
  const num = (name, x, y, goodWhenHigher = true) => {
    const d = x - y;
    if (d) diffs.push({ text: `${name} ${d > 0 ? "+" : ""}${d}`, tone: d > 0 === goodWhenHigher ? "good" : "bad" });
  };
  range("\u653B\u51FB", a.dc, b.dc);
  range("\u9B54\u6CD5", a.mc, b.mc);
  range("\u9053\u672F", a.sc, b.sc);
  range("\u9632\u5FA1", a.ac, b.ac);
  range("\u9B54\u9632", a.mac, b.mac);
  num("\u51C6\u786E", a.hit, b.hit);
  num("\u654F\u6377", a.agility, b.agility);
  num("\u653B\u901F", a.attackSpeed, b.attackSpeed);
  num("\u5E78\u8FD0", a.luck - a.curse, b.luck - b.curse);
  if (!diffs.length) return [];
  return [{ text: `\u6362\u4E0B ${oldName} \u540E\uFF1A`, tone: "dim" }, ...diffs];
}
function statLines(s, p, typicalMonsterAgility, typicalMonsterHit) {
  const hitPct = Math.round(hitChance(s.hit, typicalMonsterAgility) * 100);
  const dodgePct = Math.round((1 - hitChance(typicalMonsterHit, s.agility)) * 100);
  const lines = [
    { text: `${p.name}\u3000${JOB_NAME[p.job]}\u3000${p.level} \u7EA7`, tone: "title" },
    { text: `\u4F53\u529B ${p.hp}/${s.maxHp}\u3000\u9B54\u6CD5 ${p.mp}/${s.maxMp}` },
    { text: `\u653B\u51FB ${r(s.dc)}\uFF1A\u666E\u901A\u653B\u51FB\u6BCF\u5200\u4F24\u5BB3\u5728\u8FD9\u4E4B\u95F4\u968F\u673A\uFF0C\u518D\u51CF\u53BB\u602A\u7269\u9632\u5FA1` }
  ];
  if (has(s.mc)) lines.push({ text: `\u9B54\u6CD5 ${r(s.mc)}\uFF1A\u6CD5\u5E08\u6280\u80FD\u5A01\u529B = \u6280\u80FD\u57FA\u7840\u503C + \u8FD9\u4E2A\u8303\u56F4\u91CC\u7684\u968F\u673A\u503C` });
  if (has(s.sc)) lines.push({ text: `\u9053\u672F ${r(s.sc)}\uFF1A\u6CBB\u6108\u3001\u706B\u7B26\u5A01\u529B\u548C\u53EC\u5524\u7269\u5F3A\u5EA6\u90FD\u6309\u5B83\u7B97` });
  lines.push(
    { text: `\u9632\u5FA1 ${r(s.ac)}\uFF1A\u6BCF\u6B21\u6328\u6253\u5148\u51CF\u6389\u8FD9\u4E2A\u8303\u56F4\u91CC\u7684\u968F\u673A\u503C` },
    { text: `\u9B54\u9632 ${r(s.mac)}\uFF1A\u9B54\u6CD5\u4F24\u5BB3\u5148\u51CF\u6389\u8FD9\u4E2A\u8303\u56F4\u91CC\u7684\u968F\u673A\u503C` },
    { text: `\u51C6\u786E ${s.hit}\uFF1A\u6253\u9644\u8FD1\u602A\u7269\uFF08\u654F\u6377\u7EA6 ${typicalMonsterAgility}\uFF09\u547D\u4E2D\u7387\u7EA6 ${hitPct}%` },
    { text: `\u654F\u6377 ${s.agility}\uFF1A\u9644\u8FD1\u602A\u7269\uFF08\u51C6\u786E\u7EA6 ${typicalMonsterHit}\uFF09\u6253\u4F60\u7EA6 ${dodgePct}% \u6253\u7A7A` },
    { text: `\u653B\u51FB\u95F4\u9694 ${s.hitInterval} \u6BEB\u79D2\uFF08\u88C5\u5907\u653B\u901F\u6BCF 2 \u70B9\u5FEB 60 \u6BEB\u79D2\uFF09` }
  );
  if (s.luck) lines.push({ text: `\u5E78\u8FD0 ${s.luck}` });
  lines.push({ text: `\u8D1F\u91CD\uFF1A\u5305\u88F9 ${s.maxBagWeight}\uFF0C\u7A7F\u6234 ${s.maxWearWeight}\uFF0C\u8155\u529B ${s.maxHandWeight}\uFF08\u80FD\u62FF\u7684\u6B66\u5668\u91CD\u91CF\uFF09`, tone: "dim" });
  return lines;
}

// src/ui/viewport.ts
var clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
var USE_COMPACT = false;
var BOARD_JOYSTICK = true;
function chooseViewport(cssW, cssH, coarsePointer2) {
  const fit800 = Math.min(cssW / 800, cssH / 600);
  const short = Math.min(cssW, cssH);
  if (USE_COMPACT && coarsePointer2 && short < 600) {
    return {
      kind: "touch",
      worldZoom: Math.max(fit800, short / 560),
      // 横屏按高 390 排得下商店+商品列表，竖屏按宽 420 排得下商人对话框
      uiScale: clamp(Math.min(cssW / 420, cssH / 390), 0.9, 1.3),
      ctlScale: 1
    };
  }
  return { kind: "board", worldZoom: fit800, uiScale: fit800, ctlScale: clamp(0.95 / fit800, 1, 2) };
}
function coarsePointer() {
  return typeof matchMedia === "function" && matchMedia("(pointer: coarse)").matches;
}

// src/ui/classic/gfx.ts
var FONT = '12px "SimSun","\u5B8B\u4F53","Songti SC","STSong","PingFang SC",serif';
var MAIN = "prguse";
var Gfx = class {
  constructor(ctx, sprites) {
    this.ctx = ctx;
    this.sprites = sprites;
  }
  sprite(lib, idx) {
    return this.sprites.get(lib, idx);
  }
  size(lib, idx) {
    const s = this.sprites.get(lib, idx);
    return s ? { w: s.w, h: s.h } : null;
  }
  /** 按左上角画（界面图）。 */
  img(lib, idx, x, y) {
    const s = this.sprites.get(lib, idx);
    if (s) this.ctx.drawImage(s.img, s.sx, s.sy, s.w, s.h, Math.round(x), Math.round(y), s.w, s.h);
    return s;
  }
  /** 画图的一部分（血球、经验条按比例裁剪）。 */
  imgPart(lib, idx, x, y, rx, ry, rw, rh) {
    const s = this.sprites.get(lib, idx);
    if (!s || rw <= 0 || rh <= 0) return;
    this.ctx.drawImage(s.img, s.sx + rx, s.sy + ry, rw, rh, Math.round(x), Math.round(y), rw, rh);
  }
  /** 居中画在一个矩形里（物品图标）。 */
  imgCenter(lib, idx, x, y, w, h) {
    const s = this.sprites.get(lib, idx);
    if (s) this.ctx.drawImage(s.img, s.sx, s.sy, s.w, s.h, Math.round(x + (w - s.w) / 2), Math.round(y + (h - s.h) / 2), s.w, s.h);
  }
  /** 以 (cx, cy) 为中心按倍数画（手机上把原版小图标放大）。 */
  imgScaled(lib, idx, cx, cy, scale) {
    const s = this.sprites.get(lib, idx);
    if (!s) return;
    const w = s.w * scale;
    const h = s.h * scale;
    this.ctx.drawImage(s.img, s.sx, s.sy, s.w, s.h, Math.round(cx - w / 2), Math.round(cy - h / 2), Math.round(w), Math.round(h));
  }
  circle(cx, cy, r2, fill, stroke) {
    const ctx = this.ctx;
    ctx.beginPath();
    ctx.arc(cx, cy, r2, 0, Math.PI * 2);
    ctx.fillStyle = fill;
    ctx.fill();
    if (stroke) {
      ctx.strokeStyle = stroke;
      ctx.lineWidth = 1.5;
      ctx.stroke();
    }
  }
  /** 描边矩形（手机上的按钮、面板边框）。 */
  frame(x, y, w, h, fill, stroke) {
    const ctx = this.ctx;
    ctx.fillStyle = fill;
    ctx.fillRect(x, y, w, h);
    ctx.strokeStyle = stroke;
    ctx.lineWidth = 1;
    ctx.strokeRect(Math.round(x) + 0.5, Math.round(y) + 0.5, Math.round(w) - 1, Math.round(h) - 1);
  }
  text(s, x, y, color = "#fff", bold = false) {
    const ctx = this.ctx;
    ctx.font = FONT;
    ctx.textAlign = "left";
    ctx.textBaseline = "top";
    if (bold) {
      ctx.fillStyle = "#000";
      for (const [dx, dy] of [[-1, 0], [1, 0], [0, -1], [0, 1]]) ctx.fillText(s, x + dx, y + dy);
    }
    ctx.fillStyle = color;
    ctx.fillText(s, x, y);
    return ctx.measureText(s).width;
  }
  width(s) {
    this.ctx.font = FONT;
    return this.ctx.measureText(s).width;
  }
  rect(x, y, w, h, color) {
    this.ctx.fillStyle = color;
    this.ctx.fillRect(x, y, w, h);
  }
};
function goldStr(n) {
  return n.toLocaleString("en-US");
}
function inside(r2, x, y) {
  return x >= r2.x && y >= r2.y && x < r2.x + r2.w && y < r2.y + r2.h;
}
function distTo(r2, x, y) {
  return Math.hypot(Math.max(r2.x - x, 0, x - (r2.x + r2.w)), Math.max(r2.y - y, 0, y - (r2.y + r2.h)));
}

// src/game/joystick.ts
var STICK_R = 56;
var DEAD = 12;
var RUN_AT = 0.6;
var Joystick = class {
  constructor() {
    /** 按着摇杆的手指；没有是 null */
    this.pointer = null;
    this.base = { x: 0, y: 0 };
    this.knob = { x: 0, y: 0 };
    /** 摇杆半径（界面像素），布局按放大倍数设置 */
    this.radius = STICK_R;
  }
  get active() {
    return this.pointer !== null;
  }
  start(pointer, at) {
    this.pointer = pointer;
    this.base = at;
    this.knob = at;
  }
  /** 手指移到 at：返回方向，在死区里返回 null */
  move(at) {
    const vx = at.x - this.base.x;
    const vy = at.y - this.base.y;
    const d = Math.hypot(vx, vy);
    const R = this.radius;
    const k = d > R ? R / d : 1;
    this.knob = { x: this.base.x + vx * k, y: this.base.y + vy * k };
    if (d < DEAD) return null;
    const sector = Math.round(Math.atan2(vy, vx) / (Math.PI / 4));
    const a = sector * Math.PI / 4;
    return { dx: Math.round(Math.cos(a)), dy: Math.round(Math.sin(a)), run: d >= R * RUN_AT };
  }
  end() {
    this.pointer = null;
  }
};

// src/ui/classic/controls.ts
var ATTACK_R = 38;
var SKILL_R = 22;
var RINGS = [92, 152];
var ANGLES = [180, 210, 240, 270];
var TARGET_W = 210;
var TouchControls = class {
  constructor(h) {
    this.h = h;
  }
  get s() {
    return this.h.ctlScale;
  }
  get stickR() {
    return STICK_R * this.s;
  }
  get attackR() {
    return ATTACK_R * this.s;
  }
  draw(a) {
    this.drawTarget(a.targetTop);
    this.drawStick(a.stick);
    this.drawAttack(a.attack);
    this.drawSkills(a.attack);
  }
  /** F1-F8 按键位摆；没设快捷键的主动技能顺次补进空位（只是显示，不改存档里的快捷键）。 */
  skillButtons(attack2) {
    const all = this.h.ctl.activeSkills();
    const byKey = new Map(all.filter((s) => s.key > 0 && s.key <= 8).map((s) => [s.key, s]));
    const loose = all.filter((s) => !(s.key > 0 && s.key <= 8));
    const out = [];
    const sr = SKILL_R * this.s;
    for (let k = 1; k <= 8; k++) {
      const s = byKey.get(k) ?? loose.shift();
      if (!s) continue;
      const ring = RINGS[k <= 4 ? 0 : 1] * this.s;
      const ang = ANGLES[(k - 1) % 4] * Math.PI / 180;
      const cx = attack2.x + ring * Math.cos(ang);
      const cy = attack2.y + ring * Math.sin(ang);
      out.push({ id: s.id, key: k, cx, cy, r: { x: cx - sr, y: cy - sr, w: sr * 2, h: sr * 2 } });
    }
    return out;
  }
  /** 锁定的怪：名字、等级、血条、攻防、经验（倍率后）。右上角 × 取消锁定。 */
  drawTarget(top) {
    const { g, ctl, catalog } = this.h;
    const t = ctl.focused();
    if (!t || t.kind !== "monster") return;
    const x = Math.round((this.h.W - TARGET_W) / 2);
    const y = top;
    const hh = 70;
    this.h.area({ x, y, w: TARGET_W, h: hh });
    g.frame(x, y, TARGET_W, hh, "rgba(10,7,3,.8)", t.master ? "#5a8a5a" : "#8a3a2a");
    const tag = t.master ? "\uFF08\u5B9D\u5B9D\uFF09" : t.def.undead ? "\uFF08\u4E0D\u6B7B\u7CFB\uFF09" : "";
    g.text(`${catalog.displayName(t.def.name)}  ${t.def.level} \u7EA7${tag}`, x + 6, y + 5, t.master ? "#9cff8a" : "#ffe08a", true);
    const ratio = Math.max(0, Math.min(1, t.hp / t.maxHp));
    g.rect(x + 6, y + 22, TARGET_W - 12, 10, "#300");
    g.rect(x + 6, y + 22, Math.round((TARGET_W - 12) * ratio), 10, t.master ? "#2ecc71" : "#d33");
    const hp = `${t.hp}/${t.maxHp}`;
    g.text(hp, x + (TARGET_W - g.width(hp)) / 2, y + 21, "#fff", true);
    g.text(`\u653B\u51FB ${t.dc[0]}-${t.dc[1]}  \u9632\u5FA1 ${t.ac[0]}-${t.ac[1]}  \u9B54\u9632 ${t.mac[0]}-${t.mac[1]}`, x + 6, y + 37, "#ddd");
    g.text(`\u7ECF\u9A8C ${t.def.exp * RATES.exp}`, x + 6, y + 52, "#c0c0c0");
    const close = { x: x + TARGET_W - 30, y, w: 30, h: 22 };
    g.text("\xD7", close.x + 11, close.y + 4, "#f88", true);
    this.h.button({ ...close, img: -1, onClick: () => ctl.unlock() });
  }
  drawStick(home) {
    const { g } = this.h;
    const js = this.h.joystick;
    js.radius = this.stickR;
    const base = js.active ? js.base : home;
    const knob = js.active ? js.knob : base;
    g.circle(base.x, base.y, this.stickR, js.active ? "rgba(0,0,0,.4)" : "rgba(0,0,0,.35)", "rgba(216,162,74,.85)");
    g.circle(knob.x, knob.y, 22 * this.s, js.active ? "rgba(216,162,74,.8)" : "rgba(216,162,74,.55)", "#d8a24a");
    if (!js.active) {
      const t = "\u6447\u6746";
      g.text(t, base.x - g.width(t) / 2, base.y - 6, "#fff", true);
    }
  }
  drawAttack(c) {
    const { g } = this.h;
    const ar = this.attackR;
    const r2 = { x: c.x - ar, y: c.y - ar, w: ar * 2, h: ar * 2 };
    g.circle(c.x, c.y, ar, this.h.isPressed(r2) ? "rgba(140,40,20,.85)" : "rgba(60,20,10,.7)", "#d8a24a");
    const t = "\u653B\u51FB";
    g.text(t, c.x - g.width(t) / 2, c.y - 6, "#ffe08a", true);
    this.h.area(r2);
    this.h.button({ ...r2, img: -1, onClick: () => this.h.ctl.attackNearest() });
  }
  drawSkills(attack2) {
    const { g, p, catalog, world } = this.h;
    const sr = SKILL_R * this.s;
    for (const { id, key, cx, cy, r: r2 } of this.skillButtons(attack2)) {
      const def = catalog.skillsById.get(id);
      const ls = p.skills.find((s) => s.id === id);
      if (!def || !ls) continue;
      g.circle(cx, cy, sr, this.h.isPressed(r2) ? "rgba(90,70,30,.9)" : "rgba(0,0,0,.6)", "#8a6a3a");
      g.imgCenter("magicon", def.effect * 2, r2.x, r2.y, r2.w, r2.h);
      const cd = (p.cooldown.get(id) ?? 0) - world.now;
      if (cd > 0) {
        g.circle(cx, cy, sr, "rgba(0,0,0,.6)");
        const t = (cd / 1e3).toFixed(cd < 1e4 ? 1 : 0);
        g.text(t, cx - g.width(t) / 2, cy - 6, "#fff", true);
      } else if (p.mp < manaCost(def, ls.level)) g.circle(cx, cy, sr, "rgba(0,0,80,.45)");
      g.text(`F${key}`, r2.x - 2, r2.y - 4, "#ff0", true);
      this.h.area(r2);
      this.h.button({ ...r2, img: -1, onClick: () => this.h.ctl.castSkillAuto(id) });
    }
  }
};

// src/ui/classic/layout.ts
var CHAT_COLOR = {
  system: ["#fff", "#000"],
  good: ["#9cff8a", "#000"],
  bad: ["#fff", "#c00"],
  item: ["#ff0", "#000"]
};
var BOARD_W = 800;
var BOARD_H = 251;
var CHAT_LINES = 9;
var BoardLayout = class {
  /**
   * touch：触屏设备；joystick：底栏上方叠摇杆、攻击键、技能环（用户 #1140 定：原版底栏不动）。
   * 不用摇杆的触屏设备在右侧画一列技能键。
   */
  constructor(h, touch, joystick) {
    this.h = h;
    this.touch = touch;
    this.joystick = joystick;
    this.kind = "board";
    this.settingsRowH = 19;
    this.itemMenu = touch;
    this.controls = joystick ? new TouchControls(h) : null;
  }
  boardX() {
    return Math.round((this.h.W - BOARD_W) / 2);
  }
  beltRects() {
    const bx = this.boardX();
    const by = this.h.H - BOARD_H;
    return Array.from({ length: 6 }, (_, i) => ({ x: bx + 285 + 43 * i, y: by + 59, w: 32, h: 29 }));
  }
  bagOrigin(merchantOpen) {
    return { x: (merchantOpen ? 475 : 0) + this.h.safe.left, y: 0 };
  }
  stateOrigin() {
    return { x: this.h.W - 232 - this.h.safe.right, y: 0 };
  }
  merchantOrigin() {
    return { x: this.h.safe.left, y: 0 };
  }
  miniMapRight() {
    return { x: this.h.W - this.h.safe.right, y: this.h.safe.top };
  }
  dialogOrigin(w, hh) {
    return { x: Math.round((this.h.W - w) / 2), y: Math.max(0, Math.round((this.h.H - BOARD_H - hh) / 2)) };
  }
  drawHud(now) {
    this.drawBottom();
    const c = this.controls;
    if (c) {
      const top = this.h.H - BOARD_H - 16;
      c.draw({
        stick: { x: this.h.safe.left + c.stickR + 30, y: top - c.stickR },
        attack: { x: this.h.W - this.h.safe.right - c.attackR - 30, y: top - c.attackR },
        targetTop: this.h.safe.top + 6
      });
    } else if (this.touch) this.drawTouchSkills();
  }
  drawBottom() {
    const { g, p, catalog, world } = this.h;
    const H = this.h.H;
    const bx = this.boardX();
    const by = H - BOARD_H;
    this.h.area({ x: bx, y: by + 40, w: BOARD_W, h: BOARD_H - 40 });
    this.h.area({ x: bx, y: by, w: 190, h: 40 });
    this.h.area({ x: bx + 610, y: by, w: 190, h: 40 });
    g.rect(bx + 208, by + 120, 387, 124, "#000");
    g.img(MAIN, 1, bx, by);
    g.img(MAIN, 12, bx + 748, by + 79);
    const s = p.stats;
    if (p.job === 0 && p.level < 28) {
      const full = g.size(MAIN, 5);
      if (full) {
        g.imgPart(MAIN, 5, bx + 38, by + 90, 0, 0, full.w - 2, full.h);
        const top = Math.round(full.h / s.maxHp * (s.maxHp - p.hp));
        g.imgPart(MAIN, 6, bx + 38, by + 90 + top, 0, top, full.w - 2, full.h - top);
      }
    } else drawOrb(g, p, bx + 40, by + 91);
    const hpText = `${p.hp}/${s.maxHp}`;
    const mpText = `${p.mp}/${s.maxMp}`;
    g.text(hpText, bx + 86 - g.width(hpText) / 2, by + 186, "#fff", true);
    g.text(mpText, bx + 86 - g.width(mpText) / 2, by + 200, "#9fc6ff", true);
    g.text(String(p.level), bx + 660, H - 104, "#fff", true);
    const bar = g.size(MAIN, 7);
    if (bar) {
      const need = catalog.expToNext(p.level);
      const er = need === Infinity ? 1 : p.exp / need;
      g.imgPart(MAIN, 7, bx + 666, H - 73, 0, 0, Math.round(bar.w * Math.min(1, er)), bar.h);
      const wr = world.bagWeight(p) / Math.max(1, s.maxBagWeight);
      g.imgPart(MAIN, 7, bx + 666, H - 40, 0, 0, Math.round(bar.w * Math.min(1, wr)), bar.h);
    }
    this.h.chat.slice(-CHAT_LINES).forEach((l, i) => {
      const [fg, bg] = CHAT_COLOR[l.color];
      g.rect(bx + 208, H - 130 + i * 12, g.width(l.text), 12, bg);
      g.text(l.text, bx + 208, H - 130 + i * 12, fg);
    });
    this.h.button({ img: 8, x: bx + 643, y: by + 61, w: 24, h: 24, onClick: () => this.h.toggleState(0) });
    this.h.button({ img: 9, x: bx + 682, y: by + 41, w: 24, h: 22, onClick: () => this.h.toggleBag() });
    this.h.button({ img: 10, x: bx + 722, y: by + 21, w: 24, h: 23, onClick: () => this.h.toggleState(3) });
    this.h.button({ img: 11, x: bx + 764, y: by + 11, w: 24, h: 23, onClick: () => this.h.toggleSettings() });
    this.beltRects().forEach((r2, i) => {
      const it = p.belt[i];
      if (it && !this.h.beltInHand(i)) g.imgCenter("items", catalog.item(it.name).look, r2.x, r2.y, r2.w, r2.h);
      g.text(String(i + 1), r2.x + 13, r2.y + 19, "#fff", true);
    });
    this.h.button({ img: 130, x: bx + 219, y: by + 104, w: 28, h: 13, always: true, onClick: () => this.h.toggleMiniMap() });
    for (const [img, dx] of [[132, 249], [134, 279], [128, 309]]) {
      this.h.button({ img, x: bx + dx, y: by + 104, w: 28, h: 13, always: true, onClick: () => this.h.message("\u5355\u673A\u7248\u6CA1\u6709\u8FD9\u4E2A\u529F\u80FD") });
    }
    this.h.button({ img: 136, x: bx + 530, y: by + 104, w: 28, h: 13, always: true, onClick: () => this.h.logout() });
    this.h.button({ img: 138, x: bx + 560, y: by + 104, w: 28, h: 13, always: true, onClick: () => this.h.logout() });
  }
  /** 平板（触屏但屏幕大，用原版底栏）：右侧一列技能按钮，原版没有，触屏上没有 F1-F8。 */
  drawTouchSkills() {
    const { g, p, catalog, world } = this.h;
    const keyed = p.skills.filter((s) => s.key > 0).sort((a, b) => a.key - b.key);
    const x = (this.h.stateOpen ? this.stateOrigin().x : this.h.W - this.h.safe.right) - 44;
    let y = this.h.H - BOARD_H - 44;
    for (const ls of keyed) {
      const def = catalog.skillsById.get(ls.id);
      const r2 = { x, y, w: 36, h: 36 };
      g.rect(r2.x - 2, r2.y - 2, r2.w + 4, r2.h + 4, "rgba(0,0,0,.55)");
      g.img("magicon", def.effect * 2, r2.x + 2, r2.y + 1);
      g.text(`F${ls.key}`, r2.x, r2.y + 24, "#ff0", true);
      const cd = (p.cooldown.get(ls.id) ?? 0) - world.now;
      if (cd > 0) g.rect(r2.x, r2.y, r2.w, Math.round(r2.h * Math.min(1, cd / Math.max(1, def.delay))), "rgba(0,0,0,.6)");
      this.h.area(r2);
      const key = ls.key;
      this.h.button({ ...r2, img: -1, onClick: () => this.h.ctl.castByKeyAuto(key) });
      y -= 42;
    }
  }
};
function drawOrb(g, p, x, y) {
  const d = g.size(MAIN, 4);
  if (!d) return;
  const s = p.stats;
  const half = Math.floor(d.w / 2);
  const top = Math.round(d.h / s.maxHp * (s.maxHp - p.hp));
  g.imgPart(MAIN, 4, x, y + top, 0, top, half - 1, d.h - top);
  const mtop = Math.round(d.h / Math.max(1, s.maxMp) * (s.maxMp - p.mp));
  g.imgPart(MAIN, 4, x + half + 1, y + mtop, half + 1, mtop, d.w - half - 2, d.h - mtop);
}

// src/ui/classic/touch.ts
var SLOT = 42;
var GAP = 4;
var CHAT_KEEP_MS = 12e3;
var CHAT_MAX = 5;
var STATUS_H = 96;
var TouchLayout = class {
  constructor(h) {
    this.h = h;
    this.kind = "touch";
    this.joystick = true;
    this.itemMenu = true;
    this.settingsRowH = 26;
    this.ctl = new TouchControls(h);
  }
  get L() {
    return this.h.safe.left;
  }
  get R() {
    return this.h.W - this.h.safe.right;
  }
  get T() {
    return this.h.safe.top;
  }
  get B() {
    return this.h.H - this.h.safe.bottom;
  }
  /** 横屏（宽大于高）：窗口左右并排；竖屏：窗口上下叠放 */
  get wide() {
    return this.h.W > this.h.H;
  }
  // ---------------- 窗口位置 ----------------
  bagOrigin(merchantOpen) {
    if (this.wide) return { x: merchantOpen ? this.R - 336 : this.L, y: this.T };
    return { x: Math.max(0, Math.round((this.h.W - 336) / 2)), y: this.T + (merchantOpen ? 381 : 0) };
  }
  stateOrigin(bagOpen) {
    if (this.wide) return { x: this.R - 232, y: this.T };
    return { x: Math.round((this.h.W - 232) / 2), y: this.T + (bagOpen ? 270 : 0) };
  }
  merchantOrigin() {
    return { x: this.wide ? this.L : Math.max(0, Math.round((this.h.W - 416) / 2)), y: this.T };
  }
  miniMapRight() {
    return { x: this.R, y: this.T };
  }
  dialogOrigin(w, hh) {
    return { x: Math.max(0, Math.round((this.h.W - w) / 2)), y: Math.max(this.T, Math.round((this.h.H - hh) / 2)) };
  }
  /** 摇杆没按着时的位置（左下） */
  stickHome() {
    const r2 = this.ctl.stickR;
    return { x: this.L + r2 + 30, y: this.B - r2 - 24 };
  }
  /**
   * 快捷栏 6 格：优先放在最底下摇杆和攻击键之间（和技能键不打架时）；
   * 放不下（竖屏窄、技能多）就贴在摇杆上方，技能键伸过来时再往上让开。
   */
  beltRects() {
    const total = 6 * SLOT + 5 * GAP;
    const row = (x02, y) => Array.from({ length: 6 }, (_, i) => ({ x: x02 + i * (SLOT + GAP), y, w: SLOT, h: SLOT }));
    const skills = this.skillButtons().map((b) => b.r);
    const hits2 = (rs) => rs.some((r2) => skills.some((k) => overlap(r2, k)));
    const left = this.stickHome().x + this.ctl.stickR + 12;
    const right = this.attackCenter().x - this.ctl.attackR - 12;
    if (right - left >= total) {
      const bottom = row(Math.round(left + (right - left - total) / 2), this.B - SLOT - 6);
      if (!hits2(bottom)) return bottom;
      const tight = row(left, this.B - SLOT - 6);
      if (!hits2(tight)) return tight;
    }
    const x0 = this.L + 8;
    const tops = [this.stickHome().y - this.ctl.stickR, ...skills.filter((k) => k.x < x0 + total).map((k) => k.y)];
    return row(x0, Math.min(...tops) - 10 - SLOT);
  }
  // ---------------- HUD ----------------
  drawHud(now) {
    this.drawStatus();
    this.drawChat();
    this.drawBelt();
    this.drawMenuButtons();
    this.ctl.draw({ stick: this.stickHome(), attack: this.attackCenter(), targetTop: this.wide ? this.T + 50 : this.T + 172 });
  }
  drawStatus() {
    const { g, p, catalog } = this.h;
    const x = this.L + 4;
    const y = this.T + 4;
    this.h.area({ x: this.L, y: this.T, w: 200, h: STATUS_H });
    g.circle(x + 47, y + 46, 49, "rgba(0,0,0,.55)");
    drawOrb(g, p, x + 1, y + 1);
    const s = p.stats;
    const need = catalog.expToNext(p.level);
    const exp = need === Infinity ? "\u6EE1\u7EA7" : `${(100 * p.exp / need).toFixed(1)}%`;
    const tx = x + 98;
    g.text(`\u8840 ${p.hp}/${s.maxHp}`, tx, y + 24, "#ff8a8a", true);
    g.text(`\u9B54 ${p.mp}/${s.maxMp}`, tx, y + 40, "#9fc6ff", true);
    g.text(`${p.level} \u7EA7  ${exp}`, tx, y + 56, "#fff", true);
  }
  drawChat() {
    const { g } = this.h;
    const t = performance.now();
    const lines = this.h.chat.slice(-CHAT_MAX).filter((l) => t - l.at < CHAT_KEEP_MS);
    const maxW = this.wide ? Math.min(this.h.W * 0.42, 360) : this.R - this.L - 236;
    lines.forEach((l, i) => {
      const [fg, bg] = CHAT_COLOR[l.color];
      let text = l.text;
      while (text.length > 4 && g.width(text) > maxW) text = text.slice(0, -2);
      if (text !== l.text) text += "\u2026";
      const y = this.T + STATUS_H + 4 + i * 14;
      g.rect(this.L + 4, y, g.width(text) + 4, 14, bg === "#000" ? "rgba(0,0,0,.5)" : bg);
      g.text(text, this.L + 6, y + 1, fg);
    });
  }
  drawBelt() {
    const { g, p, catalog } = this.h;
    this.beltRects().forEach((r2, i) => {
      g.frame(r2.x, r2.y, r2.w, r2.h, "rgba(0,0,0,.55)", "#8a6a3a");
      this.h.area(r2);
      const it = p.belt[i];
      if (it && !this.h.beltInHand(i)) g.imgCenter("items", catalog.item(it.name).look, r2.x, r2.y, r2.w, r2.h);
      g.text(String(i + 1), r2.x + 3, r2.y + 2, "#ffe08a", true);
    });
  }
  /** 包裹、状态、技能、设置、小地图。 */
  drawMenuButtons() {
    const { g } = this.h;
    const items = [
      { icon: 9, onClick: () => this.h.toggleBag() },
      { icon: 8, onClick: () => this.h.toggleState(0) },
      { icon: 10, onClick: () => this.h.toggleState(3) },
      { icon: 11, onClick: () => this.h.toggleSettings() },
      { icon: "\u5730\u56FE", onClick: () => this.h.toggleMiniMap() }
    ];
    const size = 40;
    const step2 = size + 4;
    const at = (i) => this.wide ? { x: this.R - 236 - (items.length - i) * step2, y: this.T + 4, w: size, h: size } : { x: this.R - size - 4, y: this.T + 126 + i * step2, w: size, h: size };
    items.forEach((it, i) => {
      const r2 = at(i);
      const pressed = this.h.isPressed(r2);
      g.frame(r2.x, r2.y, r2.w, r2.h, pressed ? "rgba(90,70,30,.85)" : "rgba(0,0,0,.55)", "#8a6a3a");
      if (typeof it.icon === "number") g.imgScaled(MAIN, it.icon, r2.x + size / 2, r2.y + size / 2, 1.4);
      else g.text(it.icon, r2.x + (size - g.width(it.icon)) / 2, r2.y + 14, "#ffe08a", true);
      this.h.area(r2);
      this.h.button({ ...r2, img: -1, onClick: it.onClick });
    });
  }
  attackCenter() {
    const ar = this.ctl.attackR;
    return { x: this.R - ar - 24, y: this.B - ar - 24 };
  }
  skillButtons() {
    return this.ctl.skillButtons(this.attackCenter());
  }
};
function overlap(a, b) {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
}

// src/ui/classic/classic.ts
var LINE_H = 14;
var MAX_MENU = 10;
var USE_LABEL = {
  potion: "\u4F7F\u7528",
  scroll: "\u4F7F\u7528",
  book: "\u5B66\u4E60",
  bundle: "\u62C6\u5F00",
  equip: "\u7A7F\u6234",
  amulet: "\u88C5\u5907",
  material: null,
  other: null
};
var EQUIP_RECTS = [
  ["necklace", { x: 168, y: 87, w: 34, h: 31 }],
  ["helmet", { x: 115, y: 93, w: 18, h: 18 }],
  ["braceletR", { x: 42, y: 176, w: 34, h: 31 }],
  ["braceletL", { x: 168, y: 176, w: 34, h: 31 }],
  ["ringR", { x: 42, y: 215, w: 34, h: 31 }],
  ["ringL", { x: 168, y: 215, w: 34, h: 31 }],
  ["weapon", { x: 47, y: 80, w: 47, h: 87 }],
  ["dress", { x: 96, y: 122, w: 53, h: 112 }]
];
var ClassicUi = class {
  constructor(g, world, ctl, catalog, touch) {
    this.g = g;
    this.world = world;
    this.ctl = ctl;
    this.catalog = catalog;
    this.touch = touch;
    this.chat = [];
    this.hand = null;
    this.mouse = { x: -1, y: -1 };
    this.pressed = null;
    this.lastClick = { t: 0, key: "" };
    /** 小地图：0 关，1 半透明，2 不透明（原版 g_nViewMinMapLv） */
    this.miniMap = 1;
    this.stateOpen = false;
    this.statePage = 0;
    this.magicPage = 0;
    this.bagOpen = false;
    this.settingsOpen = false;
    this.keyDlg = null;
    this.npc = null;
    this.mode = "talk";
    this.menuTop = 0;
    this.menuIndex = -1;
    this.sellSpot = null;
    this.links = [];
    /** 手机左侧虚拟摇杆（输入层改它，触屏布局画它） */
    this.joystick = new Joystick();
    /** 手机：点了物品弹出的操作菜单 */
    this.itemMenu = null;
    /** 每帧算出的界面占用区域（点在里面就不传给地图） */
    this.areas = [];
    this.buttons = [];
    /** 登记顺序（后登记的在上层）：被上层窗口盖住的按钮点不到 */
    this.z = 0;
    this.W = 800;
    this.H = 600;
    /** 安全区（刘海、圆角），界面像素；触屏按钮和贴边的小地图要避开 */
    this.safe = { left: 0, right: 0, top: 0, bottom: 0 };
    /** 触屏操作件放大倍数（main 按屏幕设） */
    this.ctlScale = 1;
    /** 退出按钮：存档后回到登录画面 */
    this.onLogout = () => {
    };
    /** 点按钮时（放按钮音效） */
    this.onButton = () => {
    };
    /** 物品菜单这一帧画在哪（点在里面不关菜单） */
    this.menuRect = null;
    this.layout = new BoardLayout(this, touch, BOARD_JOYSTICK);
  }
  /** 切换布局（屏幕大小变了时）。 */
  setLayout(kind) {
    if (kind === this.layout.kind) return;
    this.layout = kind === "touch" ? new TouchLayout(this) : new BoardLayout(this, this.touch, BOARD_JOYSTICK);
    this.itemMenu = null;
  }
  get p() {
    return this.world.player;
  }
  message(text, color = "system") {
    this.chat.push({ text, color, at: performance.now() });
    if (this.chat.length > 200) this.chat.splice(0, this.chat.length - 200);
  }
  openNpc(npc) {
    this.npc = npc;
    this.mode = "talk";
    this.bagOpen = true;
    this.menuIndex = -1;
    this.menuTop = 0;
  }
  closeAll() {
    this.returnSellSpot();
    this.npc = null;
    this.stateOpen = false;
    this.bagOpen = false;
    this.settingsOpen = false;
    this.keyDlg = null;
    this.itemMenu = null;
    this.cancelHand();
  }
  toggleBag() {
    this.bagOpen = !this.bagOpen;
    this.itemMenu = null;
  }
  toggleState(page) {
    this.itemMenu = null;
    if (this.stateOpen && this.statePage === page) this.stateOpen = false;
    else {
      this.stateOpen = true;
      this.statePage = page;
    }
  }
  toggleSettings() {
    this.settingsOpen = !this.settingsOpen;
  }
  logout() {
    this.onLogout();
  }
  cancelHand() {
    this.hand = null;
  }
  beltInHand(i) {
    return this.isHandFrom("belt", i);
  }
  area(r2) {
    this.areas.push({ ...r2, z: this.z++ });
  }
  /** 登记一个点击区（不画） */
  hit(b) {
    this.buttons.push({ ...b, z: this.z++ });
  }
  // ======================= 绘制 =======================
  draw(W, H, now) {
    this.W = W;
    this.H = H;
    this.areas = [];
    this.buttons = [];
    this.z = 0;
    this.menuRect = null;
    if (this.miniMap) this.drawMiniMap(now);
    this.layout.drawHud(now);
    if (this.npc) this.drawMerchant();
    if (this.bagOpen) this.drawBag();
    if (this.stateOpen) this.drawState();
    if (this.settingsOpen) this.drawSettings();
    if (this.keyDlg) this.drawKeyDlg();
    if (this.itemMenu) this.drawItemMenu(this.itemMenu);
    else this.drawHover();
    if (this.hand) {
      const def = this.catalog.item(this.hand.item.name);
      this.g.imgCenter("items", def.look, this.mouse.x - 20, this.mouse.y - 16, 40, 32);
    }
  }
  button(b) {
    this.hit(b);
    if (b.img < 0) return;
    if (b.always || this.isPressed(b)) {
      this.g.img(MAIN, b.always && this.isPressed(b) ? b.img + 1 : b.img, b.x, b.y);
    }
  }
  isPressed(r2) {
    const b = this.pressed;
    return !!b && b.x === r2.x && b.y === r2.y && b.w === r2.w && b.h === r2.h;
  }
  toggleMiniMap() {
    this.miniMap = (this.miniMap + 1) % 3;
  }
  // ---------------- 小地图（PlayScn.DrawMiniMap） ----------------
  miniRect() {
    const idx = this.catalog.data.minimaps[this.world.map.id];
    if (idx === void 0) return null;
    const s = this.g.sprite("mmap", idx - 1);
    if (!s) return null;
    const p = this.p;
    const mx = Math.floor(p.x * 48 / 32);
    const my = p.y;
    const left = Math.max(0, Math.min(s.w - 120, mx - 60));
    const top = Math.max(0, Math.min(s.h - 120, my - 60));
    const tr = this.layout.miniMapRight();
    const w = Math.min(120, s.w);
    return { idx: idx - 1, rc: { x: left, y: top, w, h: Math.min(120, s.h) }, x: tr.x - w, y: tr.y };
  }
  drawMiniMap(now) {
    const m = this.miniRect();
    if (!m) return;
    const g = this.g;
    const ctx = g.ctx;
    ctx.globalAlpha = this.miniMap === 1 ? 0.7 : 1;
    g.imgPart("mmap", m.idx, m.x, m.y, m.rc.x, m.rc.y, m.rc.w, m.rc.h);
    ctx.globalAlpha = 1;
    this.area({ x: m.x, y: m.y, w: m.rc.w, h: m.rc.h });
    this.hit({
      x: m.x,
      y: m.y,
      w: m.rc.w,
      h: m.rc.h,
      img: -1,
      onClick: () => {
        const tx = Math.round((this.mouse.x - m.x + m.rc.x) * (32 / 48));
        const ty = Math.round(this.mouse.y - m.y + m.rc.y);
        this.ctl.goTo({ x: tx, y: ty });
      }
    });
    const dot = (cx, cy, color, size = 2) => {
      const px = m.x + Math.floor(cx * 48 / 32) - m.rc.x;
      const py = m.y + cy - m.rc.y;
      if (px >= m.x && py >= m.y && px < m.x + m.rc.w && py < m.y + m.rc.h) g.rect(px, py, size, size, color);
    };
    for (const a of this.world.actors.values()) {
      if (a.dead || a === this.p || Math.abs(a.x - this.p.x) > 30 || Math.abs(a.y - this.p.y) > 30) continue;
      dot(a.x, a.y, a.kind === "npc" ? "#0f0" : a.kind === "monster" && a.master ? "#6cf" : "#f00");
    }
    if (Math.floor(now / 300) % 2 === 0) dot(this.p.x, this.p.y, "#fff", 3);
    const it = this.ctl.intent;
    if (it.kind === "goto") dot(it.target.x, it.target.y, "#ff0", 3);
  }
  // ---------------- 状态窗 ----------------
  stateOrigin() {
    return this.layout.stateOrigin(this.bagOpen);
  }
  drawState() {
    const g = this.g;
    const { x, y } = this.stateOrigin();
    const p = this.p;
    this.area({ x, y, w: 232, h: 325 });
    g.img(MAIN, 370, x, y);
    const title = `${p.name}`;
    g.text(title, x + 116 - g.width(title) / 2, y + 23, "#ffe08a", true);
    switch (this.statePage) {
      case 0:
        this.drawEquipPage(x, y);
        break;
      case 1: {
        const s = p.stats;
        const vals = [s.ac, s.mac, s.dc, s.mc, s.sc].map((r2) => `${r2[0]}-${r2[1]}`);
        vals.push(`${p.hp}/${s.maxHp}`, `${p.mp}/${s.maxMp}`);
        vals.forEach((v, i) => g.text(v, x + 112, y + 99 + i * 20, "#fff"));
        break;
      }
      case 2:
        this.drawAbilityPage(x, y);
        break;
      case 3:
        this.drawMagicPage(x, y);
        break;
    }
    this.button({ img: 371, x: x + 8, y: y + 39, w: 16, h: 23, onClick: () => this.stateOpen = false });
    this.button({ img: 373, x: x + 7, y: y + 128, w: 24, h: 23, onClick: () => this.statePage = (this.statePage + 3) % 4 });
    this.button({ img: 372, x: x + 7, y: y + 187, w: 24, h: 23, onClick: () => this.statePage = (this.statePage + 1) % 4 });
  }
  drawEquipPage(x, y) {
    const g = this.g;
    const p = this.p;
    g.img(MAIN, p.sex === 1 ? 377 : 376, x + 38, y + 52);
    const bbx = x + 31;
    const bby = y + 96;
    const hairIdx = (p.sex === 1 ? 480 : 440) + p.hair;
    const hair = g.sprite(MAIN, hairIdx);
    if (hair) g.img(MAIN, hairIdx, bbx + hair.ox, bby + hair.oy);
    for (const slot of ["dress", "weapon", "helmet"]) {
      const it = p.equip[slot];
      if (!it || this.isHandFrom("equip", slot)) continue;
      const look = this.catalog.item(it.name).look;
      const s = g.sprite("stateitem", look);
      if (s) g.img("stateitem", look, bbx + s.ox, bby + s.oy);
    }
    for (const [slot, r2] of EQUIP_RECTS) {
      if (slot === "dress" || slot === "weapon" || slot === "helmet") continue;
      const it = p.equip[slot];
      if (it && !this.isHandFrom("equip", slot)) g.imgCenter("items", this.catalog.item(it.name).look, x + r2.x, y + r2.y, r2.w, r2.h);
    }
  }
  drawAbilityPage(x, y) {
    const g = this.g;
    const p = this.p;
    const s = p.stats;
    g.img(MAIN, 382, x + 38, y + 52);
    const bx = x + 58;
    const by = y + 62;
    const mx = bx + 85;
    const need = this.catalog.expToNext(p.level);
    const rows = [
      ["\u7ECF\u9A8C", need === Infinity ? "\u6EE1\u7EA7" : `${(100 * p.exp / need).toFixed(2)}%`],
      ["\u80CC\u5305\u8D1F\u91CD", `${this.world.bagWeight(p)}/${s.maxBagWeight}`, this.world.bagWeight(p) > s.maxBagWeight],
      ["\u7A7F\u6234\u8D1F\u91CD", `${this.world.wearWeight(p)}/${s.maxWearWeight}`],
      ["\u8155\u529B", `${p.equip.weapon ? this.catalog.item(p.equip.weapon.name).weight : 0}/${s.maxHandWeight}`],
      ["\u51C6\u786E", String(s.hit)],
      ["\u654F\u6377", String(s.agility)],
      ["\u9B54\u6CD5\u8EB2\u907F", `+${s.magicAvoid * 10}%`],
      ["\u6BD2\u7269\u8EB2\u907F", `+${s.poisonAvoid * 10}%`],
      ["\u653B\u51FB\u95F4\u9694", `${s.hitInterval}ms`],
      ["\u4F53\u529B\u6062\u590D", `+${s.hpRegen * 10}%`],
      ["\u9B54\u6CD5\u6062\u590D", `+${s.mpRegen * 10}%`]
    ];
    rows.forEach(([k, v, bad], i) => {
      g.text(k, bx, by + 14 * i, "#c0c0c0");
      g.text(v, mx, by + 14 * i, bad ? "#f00" : "#c0c0c0");
    });
  }
  drawMagicPage(x, y) {
    const g = this.g;
    const p = this.p;
    g.img(MAIN, 383, x + 38, y + 52);
    const bbx = x + 38;
    const bby = y + 52;
    const list = p.skills;
    const top = this.magicPage * 5;
    for (let i = top; i < Math.min(top + 5, list.length); i++) {
      const ls = list[i];
      const def = this.catalog.skillsById.get(ls.id);
      const m = i - top;
      const iconR = { x: bbx + 8, y: bby + 8 + m * 37, w: 31, h: 33 };
      g.img("magicon", def.effect * 2, iconR.x, iconR.y);
      this.hit({ ...iconR, img: -1, onClick: () => this.keyDlg = { skill: ls.id } });
      g.text(def.name, bbx + 48, bby + 8 + m * 37, "#c0c0c0");
      g.img(MAIN, 112, bbx + 48, bby + 23 + m * 37);
      g.img(MAIN, 111, bbx + 74, bby + 23 + m * 37);
      g.text(String(ls.level), bbx + 64, bby + 23 + m * 37, "#c0c0c0");
      g.text(ls.level < 3 ? `${ls.train}/${def.train[ls.level]}` : "-", bbx + 94, bby + 23 + m * 37, "#c0c0c0");
      if (ls.key) g.text(`F${ls.key}`, bbx + 145, bby + 8 + m * 37, "#ff0", true);
    }
    this.button({ img: 398, x: x + 213, y: y + 113, w: 12, h: 21, always: true, onClick: () => this.magicPage = Math.max(0, this.magicPage - 1) });
    this.button({
      img: 396,
      x: x + 213,
      y: y + 143,
      w: 12,
      h: 21,
      always: true,
      onClick: () => this.magicPage = Math.min(Math.max(0, Math.ceil(list.length / 5) - 1), this.magicPage + 1)
    });
  }
  // ---------------- 包裹 ----------------
  bagOrigin() {
    return this.layout.bagOrigin(!!this.npc);
  }
  gridCell(i) {
    const { x, y } = this.bagOrigin();
    return { x: x + 20 + i % 8 * 36, y: y + 13 + Math.floor(i / 8) * 32, w: 36, h: 32 };
  }
  drawBag() {
    const g = this.g;
    const { x, y } = this.bagOrigin();
    const p = this.p;
    this.area({ x, y, w: 336, h: 270 });
    g.img(MAIN, 3, x, y);
    p.bag.forEach((it2, i) => {
      if (!it2 || this.isHandFrom("bag", i)) return;
      const r2 = this.gridCell(i);
      g.imgCenter("items", this.catalog.item(it2.name).look, r2.x - 1, r2.y + 1, r2.w, r2.h);
    });
    g.text(goldStr(p.gold), x + 64, y + 185, "#fff");
    const it = this.hoverItem();
    if (it) {
      const def = this.catalog.item(it.name);
      const lines = itemLines(def, it, p, null);
      const n = g.text(def.name, x + 70, y + 215, "#ff0");
      const why = itemKind(def).kind === "equip" ? this.world.canWear(p, def) : null;
      const rest = lines.slice(1, 4);
      g.text(` \u91CD\u91CF${def.weight}`, x + 70 + n, y + 215, "#fff");
      rest.forEach((l, i) => {
        if (i < 2) g.text(l.text.split("\u3000")[0], x + 70, y + 229 + i * 14, i === 1 && why ? "#f00" : "#fff");
      });
    }
    this.button({ img: 371, x: x + 309, y: y + 203, w: 14, h: 20, onClick: () => this.bagOpen = false });
    this.button({
      img: 26,
      x: x + 254,
      y: y + 183,
      w: 48,
      h: 22,
      onClick: () => {
        if (this.hand) this.useHand();
      }
    });
  }
  // ---------------- 商人 ----------------
  npcText() {
    const n = this.npc;
    if (n.def.service === "storage") {
      return `${n.def.name}\uFF1A\\\u6211\u66FF\u4F60\u4FDD\u7BA1\u7269\u54C1\uFF0C\u8981\u5B58\u8FD8\u662F\u8981\u53D6\uFF1F\\ \\<\u4FDD\u7BA1\u7269\u54C1/@store>   <\u53D6\u56DE\u7269\u54C1/@getback>\\ \\<\u79BB\u5F00/@exit>`;
    }
    return `${n.def.name}\uFF1A\\\u6B22\u8FCE\u5149\u4E34\uFF0C\u770B\u770B\u6709\u4EC0\u4E48\u9700\u8981\u7684\uFF1F\\ \\<\u8D2D\u4E70\u7269\u54C1/@buy>   <\u51FA\u552E\u7269\u54C1/@sell>\\ \\<\u79BB\u5F00/@exit>`;
  }
  drawMerchant() {
    const g = this.g;
    const o = this.layout.merchantOrigin();
    this.area({ x: o.x, y: o.y, w: 416, h: 176 });
    g.img(MAIN, 384, o.x, o.y);
    this.links = [];
    let ly = o.y + 20;
    for (const raw of this.npcText().split("\\")) {
      let sx = o.x + 30;
      const re = /<([^/>]+)\/(@\w+)>/g;
      let last = 0;
      let m;
      while (m = re.exec(raw)) {
        if (m.index > last) sx += g.text(raw.slice(last, m.index), sx, ly, "#fff", true);
        const label = m[1];
        const w = g.text(label, sx, ly, this.mode === m[2].slice(1) ? "#f00" : "#ff0", true);
        g.rect(sx, ly + 13, w, 1, "#ff0");
        this.links.push({ rect: { x: sx, y: ly, w, h: 14 }, cmd: m[2] });
        sx += w;
        last = m.index + m[0].length;
      }
      if (last < raw.length) g.text(raw.slice(last), sx, ly, "#fff", true);
      ly += 16;
    }
    this.button({ img: 64, x: o.x + 399, y: o.y + 1, w: 16, h: 23, onClick: () => this.closeNpc() });
    if (this.mode === "buy" || this.mode === "getback") this.drawMenu(o);
    if (this.mode === "sell" || this.mode === "store") this.drawSell(o);
  }
  /** 商品列表第 m 行（屏幕上第几行）的点击区 */
  menuRowRect(m) {
    const o = this.layout.merchantOrigin();
    return { x: o.x + 14, y: o.y + 176 + 32 + m * LINE_H, w: 265, h: LINE_H };
  }
  menuRows() {
    const n = this.npc;
    if (this.mode === "getback") {
      return this.p.storage.map((it, i) => ({
        name: it.name,
        price: "",
        dura: `${Math.ceil(it.dura / 1e3)}/${Math.ceil(it.duraMax / 1e3)}`,
        item: it,
        defName: it.name,
        onBuy: () => {
          if (!this.world.addToBag(this.p, it)) return this.message("\u5305\u88F9\u6EE1\u4E86", "bad");
          this.p.storage.splice(i, 1);
          this.menuIndex = -1;
        }
      }));
    }
    return n.def.goods.flatMap((gd) => {
      const def = this.catalog.itemsByName.get(gd.name);
      if (!def) return [];
      return [{
        name: def.name,
        price: `${def.price} \u91D1\u5E01`,
        dura: def.dura > 100 ? String(Math.ceil(def.dura / 1e3)) : "-",
        item: null,
        defName: def.name,
        onBuy: () => this.buy(def.name)
      }];
    });
  }
  drawMenu(o) {
    const g = this.g;
    const x = o.x;
    const y = o.y + 176;
    this.area({ x, y, w: 308, h: 205 });
    g.img(MAIN, 385, x, y);
    const store = this.mode === "getback";
    g.text(store ? "\u4FDD\u7BA1\u7269\u54C1" : "\u7269\u54C1\u5217\u8868", x + 19, y + 11, "#fff");
    g.text(store ? "\u6301\u4E45" : "\u4EF7\u683C", x + 156, y + 11, "#fff");
    if (!store) g.text("\u6301\u4E45", x + 245, y + 11, "#fff");
    const rows = this.menuRows();
    for (let i = this.menuTop; i < Math.min(rows.length, this.menuTop + MAX_MENU); i++) {
      const r2 = rows[i];
      const m = i - this.menuTop;
      const color = i === this.menuIndex ? "#f00" : "#fff";
      if (i === this.menuIndex) g.text("\u2022", x + 10, y + 32 + m * LINE_H, color);
      g.text(r2.name, x + 19, y + 32 + m * LINE_H, color);
      g.text(store ? r2.dura : r2.price, x + 156, y + 32 + m * LINE_H, color);
      if (!store) g.text(r2.dura, x + 245, y + 32 + m * LINE_H, color);
      const rr = this.menuRowRect(m);
      this.hit({
        ...rr,
        img: -1,
        onClick: () => {
          if (this.layout.itemMenu) {
            this.menuIndex = i;
            this.openShopMenu(r2, rr);
            return;
          }
          if (this.menuIndex === i && this.isDouble(`menu${i}`)) r2.onBuy();
          this.menuIndex = i;
        }
      });
    }
    this.button({ img: 388, x: x + 43, y: y + 175, w: 24, h: 24, always: true, onClick: () => this.menuTop = Math.max(0, this.menuTop - MAX_MENU) });
    this.button({
      img: 387,
      x: x + 90,
      y: y + 175,
      w: 24,
      h: 24,
      always: true,
      onClick: () => {
        if (this.menuTop + MAX_MENU < rows.length) this.menuTop += MAX_MENU;
      }
    });
    this.button({
      img: 386,
      x: x + 215,
      y: y + 171,
      w: 44,
      h: 16,
      always: true,
      onClick: () => {
        const r2 = rows[this.menuIndex];
        if (r2) r2.onBuy();
      }
    });
    this.button({ img: 64, x: x + 291, y, w: 16, h: 23, onClick: () => this.mode = "talk" });
  }
  drawSell(o) {
    const g = this.g;
    const x = o.x + 260;
    const y = o.y + 176;
    this.area({ x, y, w: 140, h: 181 });
    g.img(MAIN, 392, x, y);
    const store = this.mode === "store";
    g.text(store ? "\u653E\u5165\u8981\u4FDD\u7BA1\u7684\u7269\u54C1" : "\u653E\u5165\u8981\u51FA\u552E\u7684\u7269\u54C1", x + 12, y + 30, "#fff");
    const spot = { x: x + 27, y: y + 67, w: 61, h: 52 };
    this.hit({ ...spot, img: -1, onClick: () => this.clickSellSpot() });
    if (this.sellSpot) {
      const def = this.catalog.item(this.sellSpot.item.name);
      g.imgCenter("items", def.look, spot.x, spot.y, spot.w, spot.h);
      if (!store) g.text(`${Math.floor(def.price / 2)} \u91D1\u5E01`, x + 30, y + 128, "#ff0");
    }
    this.button({ img: 393, x: x + 85, y: y + 150, w: 24, h: 13, always: true, onClick: () => this.confirmSell() });
    this.button({ img: 64, x: x + 115, y, w: 16, h: 23, onClick: () => this.closeSell() });
  }
  // ---------------- 设置（便利功能） ----------------
  drawSettings() {
    const g = this.g;
    const sz = g.size(MAIN, 204) ?? { w: 608, h: 327 };
    const rowH = this.layout.settingsRowH;
    const classic = this.layout.kind === "board";
    const w = classic ? sz.w : Math.min(sz.w, this.W - this.safe.left - this.safe.right - 8);
    const h = classic ? sz.h : 40 + SETTING_LABELS.length * rowH + 12;
    const { x, y } = this.layout.dialogOrigin(w, h);
    this.area({ x, y, w, h });
    if (classic) {
      g.img(MAIN, 204, x, y);
      g.rect(x + 50, y + 6, 70, 20, "#2a2a2a");
    } else g.frame(x, y, w, h, "rgba(16,11,6,.96)", "#8a6a3a");
    g.text("\u4FBF\u5229\u8BBE\u7F6E", x + 56, y + 10, "#ffe08a", true);
    const s = this.ctl.settings;
    const mid = (rowH - 14) / 2;
    SETTING_LABELS.forEach((item, i) => {
      const ly = y + 40 + i * rowH;
      const v = s[item.key];
      if (item.kind === "bool") {
        const box = { x: x + 30, y: ly + mid, w: 14, h: 14 };
        g.rect(box.x, box.y, box.w, box.h, "#000");
        g.rect(box.x + 1, box.y + 1, box.w - 2, box.h - 2, "#4a3a20");
        if (v) g.text("\u2713", box.x + 2, box.y + 1, "#ff0", true);
        g.text(item.label, x + 52, ly + mid + 1, "#fff");
        this.hit({ x: box.x, y: ly, w: Math.min(400, w - 40), h: rowH - 1, img: -1, onClick: () => {
          s[item.key] = !v;
          this.ctl.onSettingsChanged();
        } });
      } else {
        g.text(item.label, x + 52, ly + mid + 1, "#fff");
        const vx = classic ? x + 360 : x + w - 100;
        g.text("\u25C0", vx, ly + mid + 1, "#ff0", true);
        g.text(`${v}%`, vx + 22, ly + mid + 1, "#fff", true);
        g.text("\u25B6", vx + 62, ly + mid + 1, "#ff0", true);
        const bw = classic ? 20 : 32;
        this.hit({ x: vx + 6 - bw / 2, y: ly, w: bw, h: rowH - 1, img: -1, onClick: () => {
          s[item.key] = Math.max(0, Number(v) - 5);
          this.ctl.onSettingsChanged();
        } });
        this.hit({ x: vx + 68 - bw / 2, y: ly, w: bw, h: rowH - 1, img: -1, onClick: () => {
          s[item.key] = Math.min(95, Number(v) + 5);
          this.ctl.onSettingsChanged();
        } });
      }
    });
    if (classic) {
      g.text("F1-F8 \u6280\u80FD  1-6 \u5FEB\u6377\u680F  \u7A7A\u683C \u6361\u53D6  Esc \u5173\u95ED  \u53F3\u952E\u8DD1\u6B65", x + 30, y + sz.h - 40, "#c0c0c0");
      this.button({ img: 361, x: x + 514, y: y + 287, w: 80, h: 34, onClick: () => this.settingsOpen = false });
      this.button({ img: 64, x: x + 584, y: y + 6, w: 16, h: 23, onClick: () => this.settingsOpen = false });
    } else {
      const close = { x: x + w - 44, y: y + 4, w: 40, h: 30 };
      g.frame(close.x, close.y, close.w, close.h, this.isPressed(close) ? "#7a5a2a" : "#3a2a16", "#c9a060");
      g.text("\u5173\u95ED", close.x + 8, close.y + 9, "#ffe08a", true);
      this.hit({ ...close, img: -1, onClick: () => this.settingsOpen = false });
      const quit = { x: close.x - 50, y: y + 4, w: 44, h: 30 };
      g.frame(quit.x, quit.y, quit.w, quit.h, this.isPressed(quit) ? "#7a5a2a" : "#3a2a16", "#c9a060");
      g.text("\u9000\u51FA", quit.x + 10, quit.y + 9, "#ff8a8a", true);
      this.hit({ ...quit, img: -1, onClick: () => this.onLogout() });
    }
  }
  // ---------------- 技能快捷键 ----------------
  drawKeyDlg() {
    const g = this.g;
    const sz = g.size(MAIN, 229) ?? { w: 376, h: 179 };
    const { x, y } = this.layout.dialogOrigin(sz.w, sz.h);
    this.area({ x, y, w: sz.w, h: sz.h });
    g.img(MAIN, 229, x, y);
    const id = this.keyDlg.skill;
    const def = this.catalog.skillsById.get(id);
    g.img("magicon", def.effect * 2, x + 52, y + 29);
    g.text(def.name, x + 100, y + 40, "#fff", true);
    const xs = [34, 66, 98, 130, 171, 203, 235, 267];
    const ls = this.p.skills.find((s) => s.id === id);
    xs.forEach((bx, i) => {
      const k = i + 1;
      const r2 = { x: x + bx, y: y + 83, w: 32, h: 30 };
      g.img(MAIN, 232 + i * 2 + (ls?.key === k ? 1 : 0), r2.x, r2.y);
      this.hit({ ...r2, img: -1, onClick: () => this.setKey(id, k) });
    });
    this.hit({ x: x + 299, y: y + 83, w: 40, h: 30, img: -1, onClick: () => this.setKey(id, 0) });
    g.img(MAIN, 230, x + 299, y + 83);
    this.button({ img: 62, x: x + 222, y: y + 131, w: 80, h: 34, always: false, onClick: () => this.keyDlg = null });
  }
  setKey(id, key) {
    for (const s of this.p.skills) if (key && s.key === key) s.key = 0;
    const ls = this.p.skills.find((s) => s.id === id);
    if (ls) ls.key = key;
    this.keyDlg = null;
  }
  // ---------------- 悬停说明（加强版：把属性讲清楚） ----------------
  hoverItem() {
    const { x, y } = this.mouse;
    const p = this.p;
    if (this.bagOpen) {
      for (let i = 0; i < p.bag.length; i++) if (p.bag[i] && inside(this.gridCell(i), x, y)) return p.bag[i];
    }
    if (this.stateOpen && this.statePage === 0) {
      for (const [slot, r2] of EQUIP_RECTS) {
        if (p.equip[slot] && inside(this.equipRect(r2), x, y)) return p.equip[slot];
      }
    }
    const belt = this.layout.beltRects();
    for (let i = 0; i < 6; i++) {
      if (p.belt[i] && inside(belt[i], x, y)) return p.belt[i];
    }
    return null;
  }
  /** 装备格在屏幕上的位置 */
  equipRect(r2) {
    const o = this.stateOrigin();
    return { ...r2, x: o.x + r2.x, y: o.y + r2.y };
  }
  drawHover() {
    if (this.hand) return;
    const it = this.hoverItem();
    let lines = null;
    if (it) {
      const def = this.catalog.item(it.name);
      const k = itemKind(def);
      const cur = k.kind === "equip" ? this.p.equip[k.slots[0]] : null;
      lines = itemLines(def, it, this.p, cur && cur !== it ? this.catalog.item(cur.name) : null);
    } else if (this.npc && (this.mode === "buy" || this.mode === "getback")) {
      const rows = this.menuRows();
      for (let i = this.menuTop; i < Math.min(rows.length, this.menuTop + MAX_MENU); i++) {
        if (inside(this.menuRowRect(i - this.menuTop), this.mouse.x, this.mouse.y)) {
          const r2 = rows[i];
          const def = this.catalog.item(r2.defName);
          lines = itemLines(def, r2.item, this.p, null);
        }
      }
    } else if (this.stateOpen && this.statePage === 1) {
      const nearby = [...this.world.actors.values()].filter((a) => a.kind === "monster" && !a.master && !a.dead);
      const avgA = nearby.length ? Math.round(nearby.reduce((s, m) => s + m.agility, 0) / nearby.length) : 15;
      const avgH = nearby.length ? Math.round(nearby.reduce((s, m) => s + m.hit, 0) / nearby.length) : 8;
      const so = this.stateOrigin();
      if (inside({ x: so.x + 40, y: so.y + 90, w: 180, h: 150 }, this.mouse.x, this.mouse.y)) {
        lines = statLines(this.p.stats, this.p, avgA, avgH);
      }
    } else if (this.stateOpen && this.statePage === 3) {
      const top = this.magicPage * 5;
      for (let i = top; i < Math.min(top + 5, this.p.skills.length); i++) {
        const m = i - top;
        const so = this.stateOrigin();
        if (inside({ x: so.x + 46, y: so.y + 60 + m * 37, w: 170, h: 33 }, this.mouse.x, this.mouse.y)) {
          const ls = this.p.skills[i];
          const def = this.catalog.skillsById.get(ls.id);
          lines = [
            { text: `${def.name}\u3000${ls.level} \u7EA7\u3000${JOB_NAME[def.job]}`, tone: "title" },
            { text: `\u8017\u9B54\u6CD5 ${manaCost(def, ls.level)}` },
            { text: ls.level < 3 ? `\u719F\u7EC3\u5EA6 ${ls.train}/${def.train[ls.level]}\uFF0C\u4EBA\u7269 ${def.needLevel[ls.level]} \u7EA7\u540E\u624D\u80FD\u7EE7\u7EED\u6DA8` : "\u5DF2\u7EC3\u6EE1", tone: "dim" },
            { text: "\u70B9\u56FE\u6807\u8BBE\u7F6E F1-F8 \u5FEB\u6377\u952E", tone: "dim" }
          ];
        }
      }
    }
    if (lines) this.hint(lines, this.mouse.x + 14, this.mouse.y + 14);
  }
  /** 原版 ShowHint 风格的黑底说明框。 */
  hint(lines, x, y) {
    const g = this.g;
    const w = Math.max(...lines.map((l) => g.width(l.text))) + 10;
    const h = lines.length * 14 + 6;
    const hx = Math.min(x, this.W - w - 2);
    const hy = Math.min(y, this.H - h - 2);
    g.rect(hx, hy, w, h, "rgba(0,0,0,.85)");
    const color = { title: "#ff0", good: "#7cff7c", bad: "#f55", dim: "#aaa", gold: "#ffd96b" };
    lines.forEach((l, i) => g.text(l.text, hx + 5, hy + 3 + i * 14, color[l.tone ?? ""] ?? "#fff"));
  }
  // ======================= 输入 =======================
  /** 点在界面上返回 true（不传给地图）。 */
  pointerDown(x, y, button) {
    this.mouse = { x, y };
    if (button === 2 && this.hand) {
      this.hand = null;
      return true;
    }
    if (this.itemMenu && this.menuRect) {
      const r2 = this.menuRect;
      if (inside(r2, x, y)) {
        const b2 = this.buttonAt(x, y);
        if (b2 && inside(r2, b2.x + 1, b2.y + 1)) this.pressed = b2;
        return true;
      }
      this.itemMenu = null;
    }
    const b = this.buttonAt(x, y);
    if (b) {
      this.pressed = b;
      return true;
    }
    for (const l of this.links) {
      if (inside(l.rect, x, y)) {
        this.runLink(l.cmd);
        return true;
      }
    }
    if (this.clickSlots(x, y)) return true;
    const onUi = this.areas.some((r2) => inside(r2, x, y));
    if (!onUi && this.hand) {
      const it = this.takeHand();
      if (it) this.world.dropAt(this.p.pos, it);
      return true;
    }
    return onUi;
  }
  /** 点到的按钮（后登记的在上面）。手机上原版小按钮（关闭×等）很小，没正好点中时允许偏 8 像素。 */
  buttonAt(x, y) {
    for (let i = this.buttons.length - 1; i >= 0; i--) {
      const b = this.buttons[i];
      if (inside(b, x, y)) return this.coveredAbove(b.z, x, y) ? void 0 : b;
    }
    const slop = this.touchSlop();
    if (!slop) return void 0;
    let best;
    let bd = slop;
    for (let i = this.buttons.length - 1; i >= 0; i--) {
      const b = this.buttons[i];
      if (b.w * b.h > 40 * 40) continue;
      const d = distTo(b, x, y);
      if (d < bd && !this.coveredAbove(b.z, x, y)) {
        best = b;
        bd = d;
      }
    }
    return best;
  }
  /** (x, y) 处有比 z 后登记的界面区域盖着（按钮在下层窗口里，被上层窗口挡住） */
  coveredAbove(z, x, y) {
    return this.areas.some((r2) => r2.z > z && inside(r2, x, y));
  }
  /** 手机上按钮点击允许偏出的距离（界面像素）；电脑 0 */
  touchSlop() {
    return this.layout.kind === "touch" ? 8 : 0;
  }
  pointerUp(x, y) {
    const b = this.pressed;
    this.pressed = null;
    if (b && distTo(b, x, y) <= this.touchSlop()) {
      this.onButton();
      b.onClick();
    }
  }
  pointerMove(x, y) {
    this.mouse = { x, y };
  }
  /** 鼠标在界面上（地图不处理悬停） */
  covers(x, y) {
    return this.areas.some((r2) => inside(r2, x, y));
  }
  isDouble(key) {
    const t = performance.now();
    const d = this.lastClick.key === key && t - this.lastClick.t < 400;
    this.lastClick = { t, key };
    return d;
  }
  isHandFrom(kind, at) {
    const s = this.hand?.src;
    if (!s || s.kind !== kind) return false;
    return s.kind === "equip" ? s.slot === at : s.index === at;
  }
  /**
   * 点物品格。电脑（原版）：点一下拿起、再点放下，双击使用。
   * 手机：没拿着东西时点一下弹出说明和操作菜单；选了「移动」拿在手上后，再点格子放下（同电脑）。
   */
  clickSlots(x, y) {
    const p = this.p;
    const menu = this.layout.itemMenu && !this.hand;
    if (this.bagOpen) {
      for (let i = 0; i < p.bag.length; i++) {
        const cell = this.gridCell(i);
        if (!inside(cell, x, y)) continue;
        const it = p.bag[i];
        if (menu) {
          if (it) this.openBagMenu(i, it, cell);
          return true;
        }
        if (it && this.isDouble(`bag${i}`) && (!this.hand || this.hand.item === it)) {
          this.hand = null;
          this.useOrEquip(it);
          return true;
        }
        this.placeIntoBag(i);
        return true;
      }
    }
    if (this.stateOpen && this.statePage === 0) {
      for (const [slot, r2] of EQUIP_RECTS) {
        const er = this.equipRect(r2);
        if (!inside(er, x, y)) continue;
        if (menu) {
          const it = p.equip[slot];
          if (it) this.openEquipMenu(slot, it, er);
          return true;
        }
        if (this.hand) {
          if (this.hand.src.kind === "equip") {
            this.hand = null;
            return true;
          }
          const why = this.world.equip(p, this.hand.item.uid, slot);
          if (why) this.message(why, "bad");
          this.hand = null;
        } else if (p.equip[slot]) {
          this.hand = { src: { kind: "equip", slot }, item: p.equip[slot] };
        }
        return true;
      }
    }
    const belt = this.layout.beltRects();
    for (let i = 0; i < 6; i++) {
      if (!inside(belt[i], x, y)) continue;
      const it = p.belt[i];
      if (this.hand) this.placeIntoBelt(i);
      else if (it && menu && this.bagOpen) this.openBeltMenu(i, it, belt[i]);
      else if (it) this.useOrEquip(it);
      return true;
    }
    return false;
  }
  // ---------------- 手机：物品菜单 ----------------
  /** 物品说明（装备和身上同部位的比较） */
  describe(it) {
    const def = this.catalog.item(it.name);
    const k = itemKind(def);
    const cur = k.kind === "equip" ? this.p.equip[k.slots[0]] : null;
    return itemLines(def, it, this.p, cur && cur !== it ? this.catalog.item(cur.name) : null);
  }
  openBagMenu(i, it, anchor) {
    const p = this.p;
    const def = this.catalog.item(it.name);
    const kind = itemKind(def).kind;
    const actions = [];
    const src = { kind: "bag", index: i };
    if (this.npc && this.mode === "sell") actions.push({ label: `\u5356\u51FA ${Math.floor(def.price / 2)} \u91D1\u5E01`, run: () => this.sellOrStore(src, it) });
    if (this.npc && this.mode === "store") actions.push({ label: "\u4FDD\u7BA1", run: () => this.sellOrStore(src, it) });
    const use = USE_LABEL[kind];
    if (use) actions.push({ label: use, run: () => this.useOrEquip(it) });
    if ((kind === "potion" || kind === "scroll") && p.belt.includes(null)) {
      actions.push({
        label: "\u653E\u5FEB\u6377\u680F",
        run: () => {
          const slot = p.belt.indexOf(null);
          if (slot < 0 || p.bag[i] !== it) return;
          p.bag[i] = null;
          p.belt[slot] = it;
        }
      });
    }
    actions.push({ label: "\u79FB\u52A8", run: () => this.hand = { src, item: it } });
    actions.push({ label: "\u4E22\u5F03", run: () => this.dropFrom(src, it) });
    this.itemMenu = { lines: this.describe(it), actions, anchor };
  }
  openEquipMenu(slot, it, anchor) {
    this.itemMenu = {
      lines: this.describe(it),
      anchor,
      actions: [{ label: "\u8131\u4E0B", run: () => this.unequip(slot, it) }]
    };
  }
  openBeltMenu(i, it, anchor) {
    const p = this.p;
    this.itemMenu = {
      lines: this.describe(it),
      anchor,
      actions: [
        { label: "\u4F7F\u7528", run: () => this.useOrEquip(it) },
        {
          label: "\u653E\u56DE\u5305\u88F9",
          run: () => {
            const slot = p.bag.indexOf(null);
            if (slot < 0) return this.message("\u5305\u88F9\u6EE1\u4E86", "bad");
            if (p.belt[i] !== it) return;
            p.belt[i] = null;
            p.bag[slot] = it;
          }
        }
      ]
    };
  }
  openShopMenu(row, anchor) {
    const def = this.catalog.item(row.defName);
    const getback = this.mode === "getback";
    this.itemMenu = {
      lines: itemLines(def, row.item, this.p, null),
      anchor,
      keepOpen: !getback,
      actions: [{ label: getback ? "\u53D6\u56DE" : `\u8D2D\u4E70 ${row.price}`, run: () => row.onBuy() }]
    };
  }
  unequip(slot, it) {
    const p = this.p;
    if (p.equip[slot] !== it) return;
    if (!p.bag.includes(null)) return this.message("\u5305\u88F9\u6EE1\u4E86", "bad");
    p.equip[slot] = null;
    this.world.recalc(p);
    p.bag[p.bag.indexOf(null)] = it;
  }
  dropFrom(src, it) {
    this.hand = { src, item: it };
    const taken = this.takeHand();
    if (taken) this.world.dropAt(this.p.pos, taken);
  }
  sellOrStore(src, it) {
    this.sellSpot = { src, item: it };
    this.confirmSell();
  }
  drawItemMenu(m) {
    const g = this.g;
    const LH = 15;
    const BTN_H = 32;
    const pad = 6;
    const btnW = m.actions.map((a2) => Math.max(56, g.width(a2.label) + 20));
    const textW = Math.max(...m.lines.map((l) => g.width(l.text)));
    const btnRowW = btnW.reduce((s, w2) => s + w2 + 6, -6);
    const w = Math.min(this.W - 8, Math.max(textW, btnRowW) + pad * 2);
    const rows = [[]];
    let rowW = 0;
    m.actions.forEach((a2, i) => {
      const bw = btnW[i];
      if (rowW > 0 && rowW + 6 + bw > w - pad * 2) {
        rows.push([]);
        rowW = 0;
      }
      rows[rows.length - 1].push({ a: a2, w: bw });
      rowW += (rowW > 0 ? 6 : 0) + bw;
    });
    const h = pad + m.lines.length * LH + 4 + rows.length * (BTN_H + 6) + pad - 6;
    const a = m.anchor;
    let x = a.x + a.w + 4;
    if (x + w > this.W - this.safe.right) x = a.x - w - 4;
    x = Math.max(this.safe.left + 2, Math.min(x, this.W - this.safe.right - w - 2));
    const y = Math.max(this.safe.top + 2, Math.min(a.y, this.H - this.safe.bottom - h - 2));
    this.menuRect = { x, y, w, h };
    this.area(this.menuRect);
    g.frame(x, y, w, h, "rgba(10,7,3,.94)", "#8a6a3a");
    const color = { title: "#ff0", good: "#7cff7c", bad: "#f55", dim: "#aaa", gold: "#ffd96b" };
    m.lines.forEach((l, i) => g.text(l.text, x + pad, y + pad + i * LH, color[l.tone ?? ""] ?? "#fff"));
    let by = y + pad + m.lines.length * LH + 4;
    for (const row of rows) {
      let bx = x + pad;
      for (const { a: act, w: bw } of row) {
        const r2 = { x: bx, y: by, w: bw, h: BTN_H };
        g.frame(r2.x, r2.y, r2.w, r2.h, this.isPressed(r2) ? "#7a5a2a" : "#3a2a16", "#c9a060");
        g.text(act.label, r2.x + (bw - g.width(act.label)) / 2, r2.y + 10, "#ffe08a", true);
        this.hit({
          ...r2,
          img: -1,
          onClick: () => {
            act.run();
            if (!m.keepOpen) this.itemMenu = null;
          }
        });
        bx += bw + 6;
      }
      by += BTN_H + 6;
    }
  }
  useOrEquip(it) {
    const why = this.world.useItem(this.p, it.uid);
    if (why) this.message(why, "bad");
  }
  useHand() {
    const h = this.hand;
    this.hand = null;
    if (h && h.src.kind !== "equip") this.useOrEquip(h.item);
  }
  /** 把手上的物品从原处拿走并返回。 */
  takeHand() {
    const h = this.hand;
    this.hand = null;
    if (!h) return null;
    const p = this.p;
    switch (h.src.kind) {
      case "bag":
        if (p.bag[h.src.index] !== h.item) return null;
        p.bag[h.src.index] = null;
        return h.item;
      case "belt":
        if (p.belt[h.src.index] !== h.item) return null;
        p.belt[h.src.index] = null;
        return h.item;
      case "equip":
        if (p.equip[h.src.slot] !== h.item) return null;
        p.equip[h.src.slot] = null;
        this.world.recalc(p);
        return h.item;
    }
  }
  placeIntoBag(i) {
    const p = this.p;
    if (!this.hand) {
      if (p.bag[i]) this.hand = { src: { kind: "bag", index: i }, item: p.bag[i] };
      return;
    }
    const target = p.bag[i];
    const src = this.hand.src;
    if (src.kind === "bag") {
      p.bag[src.index] = target ?? null;
      p.bag[i] = this.hand.item;
      this.hand = null;
      return;
    }
    if (target) {
      this.message("\u8BF7\u653E\u5230\u7A7A\u683C\u5B50\u91CC", "bad");
      return;
    }
    const it = this.takeHand();
    if (it) p.bag[i] = it;
  }
  placeIntoBelt(i) {
    const p = this.p;
    const h = this.hand;
    const k = itemKind(this.catalog.item(h.item.name)).kind;
    if (k !== "potion" && k !== "scroll") {
      this.message("\u5FEB\u6377\u680F\u53EA\u80FD\u653E\u836F\u54C1\u548C\u5377\u8F74", "bad");
      return;
    }
    const target = p.belt[i];
    if (h.src.kind === "bag") {
      p.bag[h.src.index] = target ?? null;
      p.belt[i] = h.item;
    } else if (h.src.kind === "belt") {
      p.belt[h.src.index] = target ?? null;
      p.belt[i] = h.item;
    }
    this.hand = null;
  }
  // ---------------- 商人动作 ----------------
  runLink(cmd) {
    this.returnSellSpot();
    this.menuIndex = -1;
    this.menuTop = 0;
    switch (cmd) {
      case "@buy":
        this.mode = "buy";
        break;
      case "@sell":
        this.mode = "sell";
        if (this.layout.itemMenu) this.message("\u70B9\u5305\u88F9\u91CC\u7684\u7269\u54C1\uFF0C\u9009\u300C\u5356\u51FA\u300D");
        break;
      case "@store":
        this.mode = "store";
        if (this.layout.itemMenu) this.message("\u70B9\u5305\u88F9\u91CC\u7684\u7269\u54C1\uFF0C\u9009\u300C\u4FDD\u7BA1\u300D");
        break;
      case "@getback":
        this.mode = "getback";
        break;
      case "@exit":
        this.closeNpc();
        break;
    }
  }
  closeNpc() {
    this.returnSellSpot();
    this.npc = null;
  }
  buy(name) {
    const def = this.catalog.item(name);
    const p = this.p;
    if (p.gold < def.price) return this.message("\u91D1\u5E01\u4E0D\u591F", "bad");
    if (this.world.bagWeight(p) + def.weight > p.stats.maxBagWeight) return this.message("\u5305\u88F9\u592A\u91CD\u4E86", "bad");
    if (!this.world.addToBag(p, newItem(def))) return this.message("\u5305\u88F9\u6EE1\u4E86", "bad");
    p.gold -= def.price;
    this.message(`\u4E70\u4E86 ${def.name}\uFF0C\u82B1\u8D39 ${def.price} \u91D1\u5E01`, "item");
  }
  clickSellSpot() {
    if (this.hand) {
      if (this.hand.src.kind === "equip") return this.message("\u5148\u628A\u88C5\u5907\u8131\u4E0B\u6765\u653E\u8FDB\u5305\u88F9", "bad");
      this.returnSellSpot();
      this.sellSpot = this.hand;
      this.hand = null;
    } else if (this.sellSpot) {
      this.hand = this.sellSpot;
      this.sellSpot = null;
    }
  }
  returnSellSpot() {
    this.sellSpot = null;
  }
  closeSell() {
    this.returnSellSpot();
    this.mode = "talk";
  }
  confirmSell() {
    const h = this.sellSpot;
    if (!h) return;
    this.sellSpot = null;
    this.hand = h;
    const it = this.takeHand();
    if (!it) return;
    const def = this.catalog.item(it.name);
    if (this.mode === "store") {
      this.p.storage.push(it);
      this.message(`\u4FDD\u7BA1\u4E86 ${def.name}`, "item");
    } else {
      const price = Math.floor(def.price / 2);
      this.p.gold += price;
      this.message(`\u5356\u51FA ${def.name}\uFF0C\u5F97\u5230 ${price} \u91D1\u5E01`, "item");
    }
  }
};

// src/audio/audio.ts
var Audio = class {
  constructor() {
    this.ctx = null;
    this.buffers = /* @__PURE__ */ new Map();
    this.available = /* @__PURE__ */ new Set();
    this.bgm = null;
    this.recent = /* @__PURE__ */ new Map();
    this.soundOn = true;
    this.musicOn = false;
  }
  async init() {
    try {
      const r2 = await fetch(assetUrl("assets/sound/index.json"));
      if (r2.ok) this.available = new Set((await r2.json()).ids);
    } catch {
    }
  }
  /** 浏览器要求用户操作后才能出声：第一次点击/按键时调用。 */
  unlock() {
    if (!this.ctx) {
      const AC = window.AudioContext ?? window.webkitAudioContext;
      if (!AC) return;
      this.ctx = new AC();
    }
    if (this.ctx.state === "suspended") void this.ctx.resume();
    if (this.musicOn) this.playMusic("field");
  }
  /** 播放一个音效；at 给出声源格子时按与玩家的距离降低音量。 */
  play(id, at, listener) {
    if (!this.soundOn || !this.ctx || !this.available.has(id)) return;
    const now = performance.now();
    if (now - (this.recent.get(id) ?? 0) < 60) return;
    this.recent.set(id, now);
    let vol = 0.8;
    if (at && listener) {
      const d = Math.max(Math.abs(at.x - listener.x), Math.abs(at.y - listener.y));
      if (d > 12) return;
      vol *= 1 - d / 14;
    }
    const buf = this.buffers.get(id);
    if (buf instanceof AudioBuffer) {
      this.start(buf, vol);
      return;
    }
    if (buf) return;
    this.buffers.set(id, "loading");
    void this.load(id).then((b) => {
      this.buffers.set(id, b ?? "none");
    });
  }
  start(buf, vol) {
    const ctx = this.ctx;
    const src = ctx.createBufferSource();
    src.buffer = buf;
    const g = ctx.createGain();
    g.gain.value = vol;
    src.connect(g).connect(ctx.destination);
    src.start();
  }
  async load(id) {
    try {
      const r2 = await fetch(assetUrl(`assets/sound/${id}.m4a`));
      if (!r2.ok) return null;
      const data = await r2.arrayBuffer();
      return await new Promise((res, rej) => this.ctx.decodeAudioData(data, res, rej));
    } catch {
      return null;
    }
  }
  playMusic(name) {
    if (!this.musicOn) return;
    if (!this.bgm) {
      this.bgm = new window.Audio(assetUrl(`assets/sound/bgm_${name}.m4a`));
      this.bgm.loop = true;
      this.bgm.volume = 0.35;
    }
    void this.bgm.play().catch(() => {
    });
  }
  stopMusic() {
    this.bgm?.pause();
  }
};

// src/main.ts
var START = { map: "0", x: 333, y: 272 };
var $ = (sel) => document.querySelector(sel);
function status(text) {
  $("#status").textContent = text;
}
async function ensureLogin() {
  const me = await fetch("api/me", { credentials: "same-origin", cache: "no-store" });
  if (me.ok) return;
  const form = $("#login");
  form.classList.remove("hidden");
  status("");
  await new Promise((resolve) => {
    form.onsubmit = async (e) => {
      e.preventDefault();
      const pw = $("#password").value;
      const r2 = await fetch("api/login", {
        method: "POST",
        credentials: "same-origin",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ password: pw })
      });
      if (r2.ok) {
        form.classList.add("hidden");
        resolve();
      } else $("#login-error").textContent = r2.status === 429 ? "\u8BD5\u592A\u591A\u6B21\u4E86\uFF0C\u8FC7\u4E00\u4F1A\u518D\u8BD5" : "\u5BC6\u7801\u4E0D\u5BF9";
    };
  });
}
async function createCharacter() {
  const form = $("#create");
  form.classList.remove("hidden");
  status("");
  return new Promise((resolve) => {
    form.onsubmit = (e) => {
      e.preventDefault();
      const fd = new FormData(form);
      const name = String(fd.get("name") || "").trim() || "\u65E0\u540D";
      form.classList.add("hidden");
      resolve({ name, job: Number(fd.get("job")), sex: Number(fd.get("sex")) });
    };
  });
}
function newPlayer(catalog, c) {
  const p = new Player(1, c.name, c.job, c.sex, START.map, START.x, START.y, 0);
  p.gold = 300;
  const give = (name, n = 1) => {
    for (let i = 0; i < n; i++) {
      const d = catalog.itemsByName.get(name);
      if (d) p.bag[p.bag.indexOf(null)] = newItem(d);
    }
  };
  p.equip.weapon = newItem(catalog.item("\u6728\u5251"));
  p.equip.dress = newItem(catalog.item(c.sex === 0 ? "\u5E03\u8863(\u7537)" : "\u5E03\u8863(\u5973)"));
  give("\u91D1\u521B\u836F(\u5C0F\u91CF)", 4);
  give("\u9B54\u6CD5\u836F(\u5C0F\u91CF)", 2);
  return p;
}
async function main() {
  status("\u8FDE\u63A5\u670D\u52A1\u5668\u2026");
  await ensureLogin();
  status("\u8BFB\u53D6\u6E38\u620F\u6570\u636E\u2026");
  await loadAssetVersion();
  const catalog = await loadCatalog();
  const store = new HttpSaveStore();
  let save = null;
  try {
    save = await store.load();
  } catch (e) {
    status(`\u8BFB\u6863\u5931\u8D25\uFF1A${e.message}\uFF0C\u5237\u65B0\u91CD\u8BD5`);
    return;
  }
  const world = new World(catalog);
  const player = save ? fromSave(save, catalog, 1, 0) : newPlayer(catalog, await createCharacter());
  world.player = player;
  world.actors.set(player.id, player);
  world.recalc(player);
  if (!save) {
    player.hp = player.stats.maxHp;
    player.mp = player.stats.maxMp;
    for (let i = 0; i < player.bag.length; i++) {
      const it = player.bag[i];
      const slot = player.belt.indexOf(null);
      if (it && slot >= 0 && catalog.item(it.name).mode === 0) {
        player.belt[slot] = it;
        player.bag[i] = null;
      }
    }
  }
  status("\u52A0\u8F7D\u5730\u56FE\u2026");
  const map = await Promise.resolve().then(() => loadMap(catalog.map(player.map))).catch(() => loadMap(catalog.map(START.map)));
  world.enterMap(map, player.map === map.id ? player.pos : START);
  const settings = loadSettings(save?.settings);
  const sprites = new SpriteStore();
  const canvas = $("#game");
  const renderer = new Renderer(canvas, sprites, world, catalog);
  const ctl = new Controller(world, settings);
  const touch = coarsePointer();
  const ui = new ClassicUi(new Gfx(renderer.context, sprites), world, ctl, catalog, touch);
  ctl.onMessage = (t, c) => ui.message(t, c);
  ctl.onTalk = (id) => {
    const npc = world.actors.get(id);
    if (npc?.kind === "npc") ui.openNpc(npc);
  };
  const audio = new Audio();
  await audio.init();
  const applyView = () => {
    renderer.view = { showHpBars: settings.showHpBars, showItemNames: settings.showItemNames, showMonsterNames: settings.showMonsterNames };
    audio.soundOn = settings.soundOn;
    const musicWas = audio.musicOn;
    audio.musicOn = settings.musicOn;
    if (settings.musicOn && !musicWas) audio.playMusic("field");
    if (!settings.musicOn) audio.stopMusic();
  };
  const unlock = () => audio.unlock();
  window.addEventListener("pointerdown", unlock);
  window.addEventListener("keydown", unlock);
  ui.onButton = () => audio.play(105);
  ui.onLogout = () => {
    void persist().finally(() => location.reload());
  };
  applyView();
  ctl.onSettingsChanged = () => {
    applyView();
    void persist();
  };
  let view = chooseViewport(window.innerWidth, window.innerHeight, touch);
  const fit = () => {
    const w = window.innerWidth;
    const h = window.innerHeight;
    view = chooseViewport(w, h, touch);
    renderer.resize(w, h, view.worldZoom);
    renderer.centerY = view.kind === "touch" ? 0.5 : 0.47;
    ui.setLayout(view.kind);
    ui.ctlScale = view.ctlScale;
    const css = getComputedStyle(document.documentElement);
    const inset = (name) => (parseFloat(css.getPropertyValue(name)) || 0) / view.uiScale;
    ui.safe = { left: inset("--safe-l"), right: inset("--safe-r"), top: inset("--safe-t"), bottom: inset("--safe-b") };
  };
  fit();
  window.addEventListener("resize", fit);
  window.addEventListener("orientationchange", () => setTimeout(fit, 300));
  bindInput(canvas, renderer, ctl, ui, () => view.uiScale);
  await Promise.race([
    renderer.preloadAround(player.pos, (d, t) => status(`\u52A0\u8F7D\u753B\u9762\u2026 ${d}/${t}`)),
    new Promise((r2) => setTimeout(r2, 3e4))
  ]);
  await sprites.preload("prguse", [1, 3, 370]);
  window.__mir = { world, renderer, ctl, sprites, ui };
  ui.message(
    ui.layout.joystick ? "\u6B22\u8FCE\u6765\u5230\u739B\u6CD5\u5927\u9646\u3002\u5DE6\u8FB9\u6447\u6746\u8D70\u8DEF\uFF08\u63A8\u8FDC\u4E86\u8DD1\uFF09\uFF1B\u70B9\u602A\u9501\u5B9A\uFF0C\u53F3\u8FB9\u300C\u653B\u51FB\u300D\u6253\u5B83\uFF1B\u70B9\u7269\u54C1\u770B\u8BF4\u660E\u548C\u64CD\u4F5C\u3002" : "\u6B22\u8FCE\u6765\u5230\u739B\u6CD5\u5927\u9646\u3002F9 \u5305\u88F9\uFF0CF10 \u72B6\u6001\uFF0CF11 \u6280\u80FD\uFF0C\u53F3\u4E0B\u89D2\u6309\u94AE\u4E5F\u53EF\u4EE5\u6253\u5F00\u3002",
    "good"
  );
  status("");
  $("#loading").classList.add("hidden");
  let saving = false;
  const persist = async () => {
    if (saving) return;
    saving = true;
    try {
      await store.save(toSave(player, settings));
    } catch (e) {
      ui.message(`\u5B58\u6863\u5931\u8D25\uFF1A${e.message}`, "bad");
    } finally {
      saving = false;
    }
  };
  setInterval(() => void persist(), 3e4);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") void persist();
  });
  let last = performance.now();
  const frame = (t) => {
    const dt = Math.min(100, t - last) * (window.__timeScale ?? 1);
    last = t;
    world.tick(dt);
    ctl.update();
    for (const ev of world.events) {
      switch (ev.type) {
        case "damage":
          renderer.float(ev.target, `-${ev.amount}`, ev.target === player.id ? "#ff6b6b" : "#ffd166");
          break;
        case "miss":
          renderer.float(ev.target, "MISS", "#bbb");
          break;
        case "heal":
          if (ev.amount > 0) renderer.float(ev.target, `+${ev.amount}`, "#7bed9f");
          break;
        case "msg":
          ui.message(ev.text, ev.color);
          break;
        case "sound":
          audio.play(ev.id, ev.at, player.pos);
          break;
        case "levelUp":
          void persist();
          break;
        case "mapChange":
          ui.closeAll();
          void persist();
          break;
        default:
          break;
      }
    }
    world.events = [];
    renderer.targetActor = ui.layout.joystick ? ctl.focused()?.id ?? 0 : 0;
    renderer.draw();
    renderer.scaleTo(view.uiScale);
    ui.draw(window.innerWidth / view.uiScale, window.innerHeight / view.uiScale, world.now);
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}
main().catch((e) => {
  console.error(e);
  status(`\u51FA\u9519\u4E86\uFF1A${e.message}`);
});
//# sourceMappingURL=app.js.map
