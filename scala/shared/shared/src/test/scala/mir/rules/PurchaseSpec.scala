package mir.rules

import mir.core.*
import mir.data.*
import mir.model.*
import mir.testkit.TestFiles
import zio.test.*

/** 商店批量买的上限（#1304）：金币、格子、一次上限。 */
object PurchaseSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private def item(n: String) = catalog.item(ItemName(n)).get
  private def inst(n: String) = Some(ItemInstance.fresh(ItemUid(1), item(n)))

  private val emptyBelt = Vector.fill(Capacity.belt)(Option.empty[ItemInstance])
  private val emptyBag  = Vector.fill(Capacity.bag)(Option.empty[ItemInstance])
  private val fullBag   = Vector.fill(Capacity.bag)(inst("木剑"))
  private val fullBelt  = Vector.fill(Capacity.belt)(inst("木剑"))

  def spec = suite("商店批量买（#1304）")(
    test("可叠的药：有空位或已有一叠就不限格子，上限看金币和一次 999 个") {
      val potion = item("金创药(小量)")
      val price  = Pricing.buy(potion)
      assertTrue(
        Purchase.most(potion, Gold(price * 50), emptyBelt, emptyBag) == 50,
        Purchase.most(potion, Gold(price * 5000), emptyBelt, emptyBag) == Purchase.MaxPerBuy,
        // 包裹、快捷栏全满，但身上已有一叠：并进去，照样能买
        Purchase.most(potion, Gold(price * 7), fullBelt.updated(0, inst("金创药(小量)")), fullBag) == 7,
        Purchase.most(potion, Gold(price * 7), fullBelt, fullBag) == 0,
        Purchase.cost(potion, 7) == price.toLong * 7
      )
    },
    test("不可叠的装备一件一格，不能放快捷栏") {
      val sword = item("木剑")
      val price = Pricing.buy(sword)
      val bag3  = fullBag.updated(0, None).updated(5, None).updated(9, None)
      assertTrue(
        Purchase.most(sword, Gold(price * 100), emptyBelt, bag3) == 3,
        Purchase.refusal(sword, 4, Gold(price * 100), emptyBelt, bag3).contains("包裹只放得下 3 件"),
        Purchase.refusal(sword, 3, Gold(price * 100), emptyBelt, bag3).isEmpty,
        Purchase.refusal(sword, 1, Gold(price * 100), emptyBelt, fullBag).contains("包裹满了")
      )
    },
    test("数量非法、金币不足都拒绝并说明；贵东西乘大数量不溢出") {
      val potion = item("金创药(小量)")
      val price  = Pricing.buy(potion)
      val dear   = catalog.data.items.maxBy(_.price)
      assertTrue(
        Purchase.refusal(potion, 0, Gold(1_000_000), emptyBelt, emptyBag).contains("一次只能买 1–999 个"),
        Purchase.refusal(potion, -5, Gold(1_000_000), emptyBelt, emptyBag).isDefined,
        Purchase.refusal(potion, 1000, Gold(Int.MaxValue), emptyBelt, emptyBag).isDefined,
        Purchase.refusal(potion, 10, Gold(price * 3), emptyBelt, emptyBag).contains("金币不够，只够买 3 个"),
        Purchase.refusal(potion, 1, Gold(price - 1), emptyBelt, emptyBag).contains("金币不够"),
        Purchase.cost(dear, 999) == dear.price.toLong * 999
      )
    }
  )
