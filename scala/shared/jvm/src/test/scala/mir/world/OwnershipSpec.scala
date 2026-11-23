package mir.world

import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * 代码结构的约定（#1173），按源码查，免得以后又散开：
 * - 对象的血量、蓝量、血上限只在 Vitals 里改；格子、朝向、动作只在 Placement 里改（Entities 里是初值）。
 * - 客户端除了本机试玩用的 LocalServer，不依赖服务端的模拟代码（mir.world），常量和规则从 mir.rules / mir.protocol 拿。
 */
object OwnershipSpec extends ZIOSpecDefault:
  private val root = Path.of(sys.props.getOrElse("mir.scalaRoot", sys.error("缺系统属性 mir.scalaRoot（用 sbt test 跑）")))

  private def sources(dir: String): List[Path] =
    val d = root.resolve(dir)
    Files.walk(d).iterator.asScala.filter(_.toString.endsWith(".scala")).toList

  private def read(p: Path): String = Files.readString(p, StandardCharsets.UTF_8)

  /** 写这些字段的地方（文件名:行号） */
  private def writers(fields: String, allowed: Set[String]): List[String] =
    val write = raw"\.(?:$fields)\s*(?:=|\+=|-=)(?![=>])".r
    sources("shared/shared/src/main/scala/mir/world")
      .filterNot(p => allowed.contains(p.getFileName.toString))
      .flatMap(p => read(p).linesIterator.zipWithIndex.collect { case (l, i) if write.findFirstIn(l).isDefined => s"${p.getFileName}:${i + 1}" })

  def spec = suite("代码结构约定")(
    test("血量、蓝量、血上限只在 Vitals 里改") {
      assertTrue(writers("hp|mp|maxHpNow", Set("Vitals.scala", "Entities.scala")) == Nil)
    },
    test("格子、朝向、动作只在 Placement 里改") {
      assertTrue(writers("cell|dir|action", Set("Placement.scala", "Entities.scala")) == Nil)
    },
    test("客户端（LocalServer 除外）不依赖 mir.world") {
      val uses = sources("client/src/main/scala")
        .filterNot(_.getFileName.toString == "LocalServer.scala")
        .filter(p => raw"\bmir\.world\b".r.findFirstIn(read(p).linesIterator.filterNot(_.trim.startsWith("*")).mkString("\n")).isDefined)
        .map(_.getFileName.toString)
      assertTrue(uses == Nil)
    }
  )
