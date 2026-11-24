package mir.rules

/**
 * 玩家交易（#1222，照原版 M2Server ObjPlay.pas ClientDealTry / ClientAddDealItem / ClientChangeDealGold / ClientDealEnd，
 * 客户端 FState.pas DDealDlg / DDealRemoteDlg）的常量。
 */
object TradeRules:
  /** 每边交易栏几格（原版窗口 5×2） */
  val MaxItems = 10
  /** 发起交易要站多近（切比雪夫距离；原版要面对面相邻） */
  val OpenRange = 1
  /** 交易中两人离得超过这么多格就取消（留一步的余地，摇杆碰一下不至于取消） */
  val KeepRange = 2
  /** 交易请求多久内对方点「交易」算同意，毫秒 */
  val AskMs: Long = 30_000
  /** 同一个人重复请求时，多久内不再提醒对方，毫秒（原版 TryDealTime 3 秒） */
  val RepeatAskMs: Long = 3_000
  /** 对方改了交易栏后多久内不能确认，毫秒（原版 DealOKTime 1 秒，防最后一刻换东西） */
  val SettleMs: Long = 1_000
  /** 身上金币上限（金币是 Int） */
  val GoldMax: Long = Int.MaxValue.toLong
