package mir.client.ui

import mir.data.Catalog
import mir.model.{EquipSlot, ItemInstance}
import mir.rules.{Durability, Mining, WeaponRefine}
import mir.view.ViewState

/**
 * 武器店升级武器的确认说明（#1286，用户 11-25 要求：确认之前列出会收走什么、+1/+2/+3 和碎掉各多少概率）。
 * 纯函数，数值全部从 shared 的 WeaponRefine 现算（和服务端同一套）。
 */
object RefineInfo:
  /** 要给服务端核对的：确认时看到的武器和材料；不能升级时为 None。 */
  final case class Offer(weapon: ItemInstance, materials: Set[mir.core.ItemUid])

  def pct(x: Double): String =
    val v = BigDecimal(x * 100).setScale(if x < 0.01 then 2 else 1, BigDecimal.RoundingMode.HALF_UP)
    s"${v.bigDecimal.stripTrailingZeros.toPlainString}%"

  private def points(n: Int): String = Durability.shown(n.abs).toString

  /** 说明各行和（能升级时）要发的确认内容。 */
  def apply(catalog: Catalog, view: ViewState, gold: Int): (List[Line], Option[Offer]) =
    val weapon = view.worn.get(EquipSlot.Weapon)
    val m      = WeaponRefine.materials(view.bag.flatten, catalog.item)
    val head   = Line(s"升级手上的武器：每次 ${WeaponRefine.Price} 金币，当场出结果", Tint.Title)
    val what = List(
      Line(s"武器：${weapon.fold("（手上没拿武器）")(_.name.value)}", if weapon.isDefined then Tint.Plain else Tint.Bad),
      Line(
        if m.ores.isEmpty then "黑铁矿石：包裹里没有"
        else s"收走黑铁矿石 ×${m.ores.size}：纯度 ${m.ores.map(o => Mining.shownPurity(o.dura)).sorted.reverse.mkString("、")}",
        if m.ores.isEmpty then Tint.Bad else Tint.Plain
      ),
      Line(
        if m.jewels.isEmpty then "收走首饰：无（不带首饰也能升，但成功率最低）"
        else s"收走首饰：${m.jewels.map(_._1.name.value).mkString("、")}"
      ),
      Line("包裹里的黑铁矿石和首饰（含头盔）会全部收走，不想用的先存仓库", Tint.Dim)
    )
    val body = WeaponRefine.refusal(weapon, gold, m) match
      case Some(why) => (List(Line(why, Tint.Bad)), None)
      case None =>
        val lines = for
          w <- weapon
          r <- WeaponRefine.recipe(m)
        yield
          val f = WeaponRefine.forecast(w, r)
          val a = f.attempt
          List(
            Line(s"首饰点数：攻击 ${r.dc}　魔法 ${r.mc}　道术 ${r.sc}（最高的一项定往哪升）", Tint.Dim),
            Line(s"这次往「${a.aspect.label}」升，成功率 ${a.chance}%", Tint.Plain)
          ) ++
            (if f.capped then List(Line(s"这把武器的攻击/魔法/道术附加已有 ${WeaponRefine.points(w)} 点，到 ${WeaponRefine.MaxPoints} 点再升必碎", Tint.Bad))
             else f.raised.toList.sorted.map((by, p) => Line(s"　${a.aspect.label} +$by：${pct(p)}", Tint.Good))) ++
            List(Line(s"　失败，武器碎掉：${pct(f.broken)}", Tint.Bad)) ++
            Option.when(!f.capped)(Line(s"成功时持久上限：${duraText(f.duraMax)}（纯度分 ${r.purity}）", Tint.Dim))
        (lines.getOrElse(Nil), weapon.map(w => Offer(w, m.uids)))
    (head :: what ++ body._1, body._2)

  /** 持久上限变化的说明：「−3」「33% 机会 −1，其余不变」「50% +1、20% +2 …」 */
  private def duraText(d: Map[Int, Double]): String =
    d.toList.sortBy(_._1) match
      case List((delta, _)) => if delta == 0 then "不变" else s"${if delta > 0 then "+" else "−"}${points(delta)}"
      case many =>
        many.map { (delta, p) =>
          val t = if delta == 0 then "不变" else s"${if delta > 0 then "+" else "−"}${points(delta)}"
          s"${pct(p)} $t"
        }.mkString("、")
