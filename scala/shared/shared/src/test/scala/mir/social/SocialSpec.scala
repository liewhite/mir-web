package mir.social

import mir.model.SocialPrefs
import mir.protocol.*
import zio.test.*

/** 私聊、好友（#1221）。 */
object SocialSpec extends ZIOSpecDefault:
  /** 一群在线的人；known 是全部角色（含不在线的） */
  private final class Town(online: Seq[String], others: Seq[String] = Nil):
    val s     = Social()
    var here  = online.toSet
    val known = online ++ others
    online.foreach(s.online(_, SocialPrefs()))
    val dir = Directory(
      n => here.find(_.equalsIgnoreCase(n)).map(Seen(_, true)),
      n => known.find(_.equalsIgnoreCase(n))
    )
    def run(me: String, c: SocialCmd): Vector[Letter] = s.handle(me, c, dir)
    def come(n: String): Vector[Letter] =
      here += n
      s.online(n, SocialPrefs())
    def go(n: String): Vector[Letter] =
      here -= n
      s.offline(n)

  private def refused(out: Vector[Letter], me: String): Option[String] =
    out.collectFirst { case Letter(`me`, ServerMsg.Refused(why)) => why }

  private def friendsOf(out: Vector[Letter], who: String): Option[List[FriendView]] =
    out.collectFirst { case Letter(`who`, ServerMsg.Friends(f)) => f }

  private def noticed(out: Vector[Letter], who: String): List[String] =
    out.collect { case Letter(`who`, ServerMsg.Notice(t, _)) => t }.toList

  def spec = suite("私聊、好友")(
    test("私聊：对方收到「谁说的」，自己收到「已送到」；名字不分大小写，按角色名回") {
      val t   = Town(Seq("Abc", "小红"))
      val out = t.run("小红", SocialCmd.Whisper("abc", "  你好  "))
      assertTrue(
        out == Vector(
          Letter("Abc", ServerMsg.Whisper("小红", "你好", WhisperWay.Received)),
          Letter("小红", ServerMsg.Whisper("Abc", "你好", WhisperWay.Sent))
        )
      )
    },
    test("私聊不成：对方不在线、没写名字或内容") {
      val t = Town(Seq("甲"), others = Seq("乙"))
      assertTrue(
        refused(t.run("甲", SocialCmd.Whisper("乙", "在吗")), "甲").exists(_.contains("乙 没有在线")),
        refused(t.run("甲", SocialCmd.Whisper("乙", "  ")), "甲").exists(_.contains("格式")),
        refused(t.run("甲", SocialCmd.Whisper("", "在吗")), "甲").isDefined
      )
    },
    test("私聊太长截到 100 字") {
      val t   = Town(Seq("甲", "乙"))
      val out = t.run("甲", SocialCmd.Whisper("乙", "啊" * 150))
      assertTrue(out.collectFirst { case Letter("乙", ServerMsg.Whisper(_, text, _)) => text.length }.contains(Whispers.MaxLength))
    },
    test("加好友：不在线的已有角色也能加，名单带在线与否、存进偏好；名字按角色名") {
      val t   = Town(Seq("甲", "丙"), others = Seq("Bob"))
      val a   = t.run("甲", SocialCmd.AddFriend("bob"))
      val b   = t.run("甲", SocialCmd.AddFriend("丙"))
      assertTrue(
        friendsOf(a, "甲").contains(List(FriendView("Bob", false))),
        friendsOf(b, "甲").contains(List(FriendView("Bob", false), FriendView("丙", true))),
        t.s.prefsOf("甲").map(_.friends).contains(List("Bob", "丙")),
        // 单向：丙的名单不变
        t.s.friendsView("丙").isEmpty
      )
    },
    test("加好友不成：查无此人、自己、已经是好友、满了") {
      val many = (1 to Friends.Max).map(i => s"人$i")
      val t    = Town(Seq("甲"), others = Seq("乙") ++ many)
      t.run("甲", SocialCmd.AddFriend("乙"))
      val nobody = t.run("甲", SocialCmd.AddFriend("无名"))
      val self   = t.run("甲", SocialCmd.AddFriend("甲"))
      val twice  = t.run("甲", SocialCmd.AddFriend("乙"))
      val empty  = t.run("甲", SocialCmd.AddFriend(" "))
      many.foreach(n => t.run("甲", SocialCmd.AddFriend(n)))
      val full = t.run("甲", SocialCmd.AddFriend("人1"))
      val t2   = Town(Seq("甲"), others = many :+ "多一个")
      many.foreach(n => t2.run("甲", SocialCmd.AddFriend(n)))
      val over = t2.run("甲", SocialCmd.AddFriend("多一个"))
      assertTrue(
        refused(nobody, "甲").exists(_.contains("没有叫 无名")), refused(self, "甲").isDefined,
        refused(twice, "甲").exists(_.contains("已经是")), refused(empty, "甲").isDefined,
        refused(full, "甲").isDefined, refused(over, "甲").exists(_.contains(s"最多 ${Friends.Max}")),
        t2.s.friendsView("甲").size == Friends.Max
      )
    },
    test("删好友：不分大小写；不在名单里的回提示") {
      val t = Town(Seq("甲"), others = Seq("Bob"))
      t.run("甲", SocialCmd.AddFriend("Bob"))
      val gone = t.run("甲", SocialCmd.RemoveFriend("BOB"))
      val miss = t.run("甲", SocialCmd.RemoveFriend("Bob"))
      assertTrue(friendsOf(gone, "甲").contains(Nil), refused(miss, "甲").isDefined, t.s.prefsOf("甲").exists(_.friends.isEmpty))
    },
    test("好友上线、下线：加了他的人收到提示和新名单；顶号重进不再提示；自己上线收到名单") {
      val t = Town(Seq("甲"), others = Seq("乙"))
      t.run("甲", SocialCmd.AddFriend("乙"))
      val up    = t.come("乙")
      val again = t.s.online("乙", SocialPrefs())
      val down  = t.go("乙")
      val mine  = t.come("丁")
      assertTrue(
        friendsOf(up, "甲").contains(List(FriendView("乙", true))), noticed(up, "甲") == List("好友 乙 上线了。"),
        noticed(again, "甲").isEmpty,
        friendsOf(down, "甲").contains(List(FriendView("乙", false))), noticed(down, "甲") == List("好友 乙 下线了。"),
        !down.exists(_.to == "乙"),
        friendsOf(mine, "丁").contains(Nil)
      )
    },
    test("存档里带的好友名单上线后照样用") {
      val s   = Social()
      s.online("乙", SocialPrefs())
      val out = s.online("甲", SocialPrefs(friends = List("乙", "丙")))
      assertTrue(friendsOf(out, "甲").contains(List(FriendView("乙", true), FriendView("丙", false))))
    }
  )
