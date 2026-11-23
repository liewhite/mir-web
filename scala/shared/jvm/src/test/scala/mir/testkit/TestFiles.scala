package mir.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** 测试读的外部文件（路径由 build.sbt 以系统属性传入）。 */
object TestFiles:
  private def read(prop: String): String =
    val p = sys.props.getOrElse(prop, sys.error(s"缺系统属性 $prop（用 sbt test 跑）"))
    Files.readString(Path.of(p), StandardCharsets.UTF_8)

  def gameJson: String = read("mir.gameJson")
  def tsGolden: String = read("mir.tsGolden")
