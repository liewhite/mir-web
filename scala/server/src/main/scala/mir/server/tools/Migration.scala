package mir.server.tools

import mir.core.*
import mir.model.*
import mir.world.{CharacterData, Place, UidSource}
import zio.json.*

/**
 * 旧单机版存档（game/src/save/save.ts 的 SaveData，version 1）→ 多人版角色存档。
 * 物品重新编号（全服唯一）；便利设置取多人版有的那几项（只影响画面声音的开关留在客户端）。
 */
object Migration:
  private final case class TsItem(uid: Long, name: String, dura: Int, duraMax: Int)
  private final case class TsPlace(map: String, x: Int, y: Int)
  private final case class TsSkill(id: Int, level: Int, train: Int, key: Int)
  private final case class TsPlayer(
      name: String, job: Int, sex: Int, hair: Int, level: Int, exp: Long, gold: Int, hp: Int, mp: Int,
      map: String, x: Int, y: Int, home: TsPlace,
      bag: Vector[Option[TsItem]], belt: Vector[Option[TsItem]], storage: List[TsItem],
      equip: Map[String, Option[TsItem]], skills: List[TsSkill], longHitOn: Boolean, wideHitOn: Boolean
  )
  private final case class TsSave(version: Int, player: TsPlayer, settings: Option[CharSettings])

  private given JsonDecoder[TsItem]   = DeriveJsonDecoder.gen
  private given JsonDecoder[TsPlace]  = DeriveJsonDecoder.gen
  private given JsonDecoder[TsSkill]  = DeriveJsonDecoder.gen
  private given JsonDecoder[TsPlayer] = DeriveJsonDecoder.gen
  private given JsonDecoder[TsSave]   = DeriveJsonDecoder.gen

  /** name 给了就用它当角色名（旧名字被占用时）。 */
  def fromTsSave(json: String, uids: UidSource, name: Option[String] = None): Either[String, CharacterData] =
    for
      save <- json.fromJson[TsSave].left.map(e => s"不是旧版存档：$e")
      _    <- Either.cond(save.version == 1, (), s"不认识的存档版本 ${save.version}")
      p     = save.player
      job  <- Job.values.find(_.code == p.job).toRight(s"职业编号不对：${p.job}")
      sex  <- Sex.values.find(_.code == p.sex).toRight(s"性别编号不对：${p.sex}")
      worn <- p.equip.toList.collect { case (k, Some(it)) => (k, it) }.foldLeft[Either[String, Map[EquipSlot, TsItem]]](Right(Map.empty)) {
                case (acc, (k, it)) => acc.flatMap(m => EquipSlot.values.find(_.key == k).toRight(s"装备位不对：$k").map(s => m + (s -> it)))
              }
    yield
      def item(t: TsItem) = ItemInstance(uids.next(), ItemName(t.name), t.dura, t.duraMax)
      CharacterData(
        name = name.getOrElse(p.name), job = job, sex = sex, hair = p.hair, level = p.level, exp = Exp(p.exp), gold = Gold(p.gold),
        hp = Hp(p.hp), mp = Mp(p.mp), place = Place(MapId(p.map), Cell(p.x, p.y)), dir = Dir.Down,
        home = Place(MapId(p.home.map), Cell(p.home.x, p.home.y)),
        bag = p.bag.map(_.map(item)).padTo(Capacity.bag, None).take(Capacity.bag),
        belt = p.belt.map(_.map(item)).padTo(Capacity.belt, None).take(Capacity.belt),
        worn = worn.view.mapValues(item).toMap,
        storage = p.storage.map(item),
        skills = p.skills.map(s => LearnedSkill(SkillId(s.id), s.level, s.train, Option.when(s.key > 0)(s.key))),
        settings = save.settings.getOrElse(CharSettings()),
        longHitOn = p.longHitOn, wideHitOn = p.wideHitOn
      )
