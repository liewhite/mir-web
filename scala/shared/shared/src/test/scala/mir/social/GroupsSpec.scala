package mir.social

import mir.core.Exp
import mir.model.SocialPrefs
import mir.protocol.*
import mir.rules.GroupRules
import zio.test.*

/** 组队（#1220）：进退队、队长权限、下线、队伍聊天、分经验算法。 */
object GroupsSpec extends ZIOSpecDefault:
  private val allow = SocialPrefs(allowGroup = true)

  /** 一群在线的人：dead 里的算死了 */
  private final class Town(names: Seq[String], prefs: SocialPrefs = allow):
    val g    = Social()
    var dead = Set.empty[String]
    names.foreach(g.online(_, prefs))
    def find(n: String): Option[Seen] = Social.pick(names, n).map(x => Seen(x, !dead(x)))
    def run(me: String, c: SocialCmd): Vector[Letter] = g.handle(me, c, Directory.onlineOnly(find))
    def members(n: String): List[String] = g.groupView(n).members
    def refused(out: Vector[Letter], me: String): Boolean = out.exists { case Letter(`me`, ServerMsg.Refused(_)) => true; case _ => false }

  def spec = suite("组队")(
    test("建组：自己当队长，两人都收到成员名单；建组的人自动允许组队") {
      // 丙没开允许组队，也能建组
      val t2  = Social()
      t2.online("丙", SocialPrefs())
      t2.online("乙", allow)
      val out = t2.handle("丙", SocialCmd.CreateGroup("乙"), Directory.onlineOnly(n => Some(Seen(n, true))))
      assertTrue(
        out.contains(Letter("乙", ServerMsg.Group(GroupView(true, List("丙", "乙"))))),
        out.contains(Letter("丙", ServerMsg.Group(GroupView(true, List("丙", "乙"))))),
        t2.prefsOf("丙").contains(allow)
      )
    },
    test("建组不成：对方没开允许组队、不在线、死了、已在别组、是自己；自己已在组里") {
      val t = Town(Seq("甲", "乙", "丙", "丁"))
      t.g.handle("乙", SocialCmd.AllowGroup(false), Directory.onlineOnly(t.find))
      val noAllow = t.run("甲", SocialCmd.CreateGroup("乙"))
      val nobody  = t.run("甲", SocialCmd.CreateGroup("无名"))
      t.dead = Set("丙")
      val deadOne = t.run("甲", SocialCmd.CreateGroup("丙"))
      val self    = t.run("甲", SocialCmd.CreateGroup("甲"))
      t.dead = Set.empty
      t.run("丙", SocialCmd.CreateGroup("丁"))
      val taken   = t.run("甲", SocialCmd.CreateGroup("丁"))
      val twice   = t.run("丙", SocialCmd.CreateGroup("甲"))
      assertTrue(
        t.refused(noAllow, "甲"), t.refused(nobody, "甲"), t.refused(deadOne, "甲"), t.refused(self, "甲"),
        t.refused(taken, "甲"), t.refused(twice, "丙"),
        t.members("甲").isEmpty, t.members("丙") == List("丙", "丁")
      )
    },
    test("名字不分大小写，按角色名认") {
      val t = Town(Seq("Abc", "Xyz"))
      t.run("Abc", SocialCmd.CreateGroup("xyz"))
      assertTrue(t.members("Xyz") == List("Abc", "Xyz"))
    },
    test("踢人：组里有只差大小写的两个人（yoyo / Yoyo），踢大小写完全一样的那个（#1358）") {
      val t = Town(Seq("甲", "yoyo", "Yoyo"))
      t.run("甲", SocialCmd.CreateGroup("yoyo"))
      t.run("甲", SocialCmd.AddMember("Yoyo"))
      val before = t.members("甲")
      t.run("甲", SocialCmd.RemoveMember("Yoyo"))
      assertTrue(before == List("甲", "yoyo", "Yoyo"), t.members("甲") == List("甲", "yoyo"))
    },
    test("加人只有队长能加，最多 11 人") {
      val names = (1 to 13).map(i => s"人$i")
      val t     = Town(names)
      t.run("人1", SocialCmd.CreateGroup("人2"))
      val byMember = t.run("人2", SocialCmd.AddMember("人3"))
      (3 to 11).foreach(i => t.run("人1", SocialCmd.AddMember(s"人$i")))
      val full = t.run("人1", SocialCmd.AddMember("人12"))
      assertTrue(
        t.refused(byMember, "人2"),
        t.members("人1").size == GroupRules.MaxMembers, t.refused(full, "人1"), t.members("人12").isEmpty
      )
    },
    test("队长删人：被删的收到空名单，其余收到新名单；删到只剩一人就解散") {
      val t = Town(Seq("甲", "乙", "丙"))
      t.run("甲", SocialCmd.CreateGroup("乙"))
      t.run("甲", SocialCmd.AddMember("丙"))
      val byMember = t.run("乙", SocialCmd.RemoveMember("丙"))
      val out = t.run("甲", SocialCmd.RemoveMember("乙"))
      val left = t.members("甲")
      t.run("甲", SocialCmd.RemoveMember("丙"))
      assertTrue(
        t.refused(byMember, "乙"),
        out.contains(Letter("乙", ServerMsg.Group(GroupView(true, Nil)))),
        left == List("甲", "丙"),
        t.members("甲").isEmpty, t.members("丙").isEmpty
      )
    },
    test("队长删自己 = 解散全队") {
      val t = Town(Seq("甲", "乙", "丙"))
      t.run("甲", SocialCmd.CreateGroup("乙"))
      t.run("甲", SocialCmd.AddMember("丙"))
      t.run("甲", SocialCmd.RemoveMember("甲"))
      assertTrue(t.members("甲").isEmpty, t.members("乙").isEmpty, t.members("丙").isEmpty)
    },
    test("队员关掉允许组队 = 退出小组；队长关不掉") {
      val t = Town(Seq("甲", "乙", "丙"))
      t.run("甲", SocialCmd.CreateGroup("乙"))
      t.run("甲", SocialCmd.AddMember("丙"))
      val leader = t.run("甲", SocialCmd.AllowGroup(false))
      t.run("丙", SocialCmd.AllowGroup(false))
      assertTrue(
        t.refused(leader, "甲"), t.g.groupView("甲").allowGroup,
        t.members("甲") == List("甲", "乙"), t.members("丙").isEmpty, !t.g.groupView("丙").allowGroup
      )
    },
    test("下线：队员下线就退出；队长下线全队解散（原版不转让队长）") {
      val t = Town(Seq("甲", "乙", "丙", "丁"))
      t.run("甲", SocialCmd.CreateGroup("乙"))
      t.run("甲", SocialCmd.AddMember("丙"))
      t.run("甲", SocialCmd.AddMember("丁"))
      val out = t.g.offline("丁")
      val afterMember = t.members("甲")
      t.g.offline("甲")
      assertTrue(
        afterMember == List("甲", "乙", "丙"), !out.exists(_.to == "丁"),
        t.members("乙").isEmpty, t.members("丙").isEmpty, t.g.prefsOf("甲").isEmpty
      )
    },
    test("重新上线（顶号、重连）保留在线时的状态；下线后再上线用存档里的偏好") {
      val g = Social()
      g.online("甲", SocialPrefs())
      g.handle("甲", SocialCmd.AllowGroup(true), Directory.onlineOnly(_ => None))
      g.online("甲", SocialPrefs())
      val kept = g.prefsOf("甲")
      g.offline("甲")
      g.online("甲", SocialPrefs())
      assertTrue(kept.contains(allow), g.prefsOf("甲").contains(SocialPrefs()))
    },
    test("队伍聊天发给全队（含自己）；没组队的不能发") {
      val t = Town(Seq("甲", "乙", "丙"))
      val alone = t.run("甲", SocialCmd.GroupSay("有人吗"))
      t.run("甲", SocialCmd.CreateGroup("乙"))
      val out = t.run("乙", SocialCmd.GroupSay(" 集合 "))
      assertTrue(
        t.refused(alone, "甲"),
        out.toSet == Set(Letter("甲", ServerMsg.Chat("乙", "集合", ChatKind.Group)), Letter("乙", ServerMsg.Chat("乙", "集合", ChatKind.Group)))
      )
    },
    test("世界查队友：同队的全部人名（含自己），没组队为空") {
      val t = Town(Seq("甲", "乙", "丙"))
      t.run("甲", SocialCmd.CreateGroup("乙"))
      assertTrue(t.g.mates("乙") == Vector("甲", "乙"), t.g.mates("丙").isEmpty)
    },
    test("分经验：2 人以上总经验按人数加成（原版表按人数取：2 人 ×1.3 … 11 人 ×2.2）后按等级比例分；1 人不分") {
      assertTrue(
        GroupRules.share(Exp(1000), Vector(1)).isEmpty,
        GroupRules.share(Exp(1000), Vector(1, 3)).contains(Vector(Exp(325), Exp(975))),
        GroupRules.share(Exp(1000), Vector.fill(11)(10)).contains(Vector.fill(11)(Exp(200))),
        GroupRules.Bonus(2) == 1.3, GroupRules.Bonus(11) == 2.2
      )
    }
  )
