package mir.core

import zio.json.JsonCodec

/**
 * 各种编号：底层都是数或字符串，用不透明类型分开，编译期就不能混用（比如把物品编号当角色编号传）。
 * JSON 编码就是底层的数或字符串。
 */

/** 世界里的对象（玩家、怪物、NPC）编号，由世界分配。 */
opaque type ActorId = Int
object ActorId:
  def apply(n: Int): ActorId                  = n
  extension (id: ActorId) def value: Int      = id
  given JsonCodec[ActorId]                    = JsonCodec.int
  given Ordering[ActorId]                     = Ordering.Int

/** 一件具体物品（实例）的编号：同名的两瓶药编号不同。 */
opaque type ItemUid = Long
object ItemUid:
  def apply(n: Long): ItemUid                 = n
  extension (id: ItemUid) def value: Long     = id
  given JsonCodec[ItemUid]                    = JsonCodec.long
  given Ordering[ItemUid]                     = Ordering.Long

/** 地上物品的编号（世界分配）。 */
opaque type GroundId = Int
object GroundId:
  def apply(n: Int): GroundId                 = n
  extension (id: GroundId) def value: Int     = id
  given JsonCodec[GroundId]                   = JsonCodec.int

/** 地上持续法术（火墙一格、困魔咒光圈一格）的编号（世界分配）。 */
opaque type FieldId = Int
object FieldId:
  def apply(n: Int): FieldId                  = n
  extension (id: FieldId) def value: Int      = id
  given JsonCodec[FieldId]                    = JsonCodec.int

/** 物品种类：数据表里的物品名（爆率、商品、存档都按名字引用）。 */
opaque type ItemName = String
object ItemName:
  def apply(s: String): ItemName              = s
  extension (n: ItemName) def value: String   = n
  given JsonCodec[ItemName]                   = JsonCodec.string
  given zio.json.JsonFieldDecoder[ItemName]   = zio.json.JsonFieldDecoder.string
  given zio.json.JsonFieldEncoder[ItemName]   = zio.json.JsonFieldEncoder.string

/** 怪物种类：数据表里的怪物名（刷怪、爆率按名字引用）。 */
opaque type MonsterName = String
object MonsterName:
  def apply(s: String): MonsterName            = s
  extension (n: MonsterName) def value: String = n
  given JsonCodec[MonsterName]                 = JsonCodec.string
  given zio.json.JsonFieldDecoder[MonsterName] = zio.json.JsonFieldDecoder.string
  given zio.json.JsonFieldEncoder[MonsterName] = zio.json.JsonFieldEncoder.string

/** 地图编号（如 "0" 比奇省、"D001"），也是素材目录名的来源。 */
opaque type MapId = String
object MapId:
  def apply(s: String): MapId                 = s
  extension (n: MapId) def value: String      = n
  given JsonCodec[MapId]                      = JsonCodec.string
  given zio.json.JsonFieldDecoder[MapId]      = zio.json.JsonFieldDecoder.string
  given zio.json.JsonFieldEncoder[MapId]      = zio.json.JsonFieldEncoder.string

/** 技能编号（数据表 Magic.id）。 */
opaque type SkillId = Int
object SkillId:
  def apply(n: Int): SkillId                  = n
  extension (id: SkillId) def value: Int      = id
  given JsonCodec[SkillId]                    = JsonCodec.int
  given zio.json.JsonFieldDecoder[SkillId]    = zio.json.JsonFieldDecoder.int
  given zio.json.JsonFieldEncoder[SkillId]    = zio.json.JsonFieldEncoder.int

/** 账号下的角色编号（服务端数据库分配）。 */
opaque type CharId = Long
object CharId:
  def apply(n: Long): CharId                  = n
  extension (id: CharId) def value: Long      = id
  given JsonCodec[CharId]                     = JsonCodec.long

/** 体力值。 */
opaque type Hp = Int
object Hp:
  def apply(n: Int): Hp = n
  val zero: Hp          = 0
  extension (h: Hp)
    def value: Int           = h
    def +(d: Int): Hp        = h + d
    def -(d: Int): Hp        = h - d
    def min(o: Hp): Hp       = math.min(h, o)
    def max(o: Hp): Hp       = math.max(h, o)
    def <=(o: Hp): Boolean   = h <= o
    def <(o: Hp): Boolean    = h < o
    def isDepleted: Boolean  = h <= 0
  given JsonCodec[Hp] = JsonCodec.int

/** 魔法值。 */
opaque type Mp = Int
object Mp:
  def apply(n: Int): Mp = n
  val zero: Mp          = 0
  extension (m: Mp)
    def value: Int         = m
    def +(d: Int): Mp      = m + d
    def -(d: Int): Mp      = m - d
    def min(o: Mp): Mp     = math.min(m, o)
    def max(o: Mp): Mp     = math.max(m, o)
    def <=(o: Mp): Boolean = m <= o
    def <(o: Mp): Boolean  = m < o
  given JsonCodec[Mp] = JsonCodec.int

/** 金币。 */
opaque type Gold = Int
object Gold:
  def apply(n: Int): Gold = n
  val zero: Gold          = 0
  extension (g: Gold)
    def value: Int           = g
    def +(o: Gold): Gold     = g + o
    def -(o: Gold): Gold     = g - o
    def >=(o: Gold): Boolean = g >= o
    def <(o: Gold): Boolean  = g < o
  given JsonCodec[Gold] = JsonCodec.int

/** 经验值（46 级起每级要 4.8 亿，经验倍率后会超过 Int）。 */
opaque type Exp = Long
object Exp:
  def apply(n: Long): Exp = n
  val zero: Exp           = 0L
  extension (e: Exp)
    def value: Long          = e
    def +(o: Exp): Exp       = e + o
    def -(o: Exp): Exp       = e - o
    def >=(o: Exp): Boolean  = e >= o
    def <(o: Exp): Boolean   = e < o
  given JsonCodec[Exp] = JsonCodec.long

/** 毫秒时刻：世界时钟（服务端开机后单调递增），只由服务端给出。 */
opaque type Millis = Long
object Millis:
  def apply(n: Long): Millis = n
  val zero: Millis           = 0L
  extension (t: Millis)
    def value: Long               = t
    def +(ms: Long): Millis       = t + ms
    def -(o: Millis): Long        = t - o
    def <(o: Millis): Boolean     = t < o
    def <=(o: Millis): Boolean    = t <= o
    def >=(o: Millis): Boolean    = t >= o
    def >(o: Millis): Boolean     = t > o
  given JsonCodec[Millis] = JsonCodec.long
  given Ordering[Millis]  = Ordering.Long

/** game.json merchants 里的下标：指一个商人（NPC）的定义。 */
opaque type MerchantRef = Int
object MerchantRef:
  def apply(n: Int): MerchantRef              = n
  extension (r: MerchantRef) def index: Int   = r
  given JsonCodec[MerchantRef]                = JsonCodec.int

/** game.json teleports 里的下标：传送菜单的一个目的地。 */
opaque type TeleportRef = Int
object TeleportRef:
  def apply(n: Int): TeleportRef              = n
  extension (r: TeleportRef) def index: Int   = r
  given JsonCodec[TeleportRef]                = JsonCodec.int
