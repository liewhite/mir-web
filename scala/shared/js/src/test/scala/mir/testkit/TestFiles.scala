package mir.testkit

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("node:fs", JSImport.Namespace)
private object NodeFs extends js.Object:
  def readFileSync(path: String, encoding: String): String = js.native

@js.native
@JSImport("node:process", JSImport.Namespace)
private object NodeProcess extends js.Object:
  val env: js.Dictionary[String] = js.native

/** 测试读的外部文件（路径由 build.sbt 以环境变量传入）。 */
object TestFiles:
  private def read(name: String): String =
    val p = NodeProcess.env.get(name).getOrElse(sys.error(s"缺环境变量 $name（用 sbt test 跑）"))
    NodeFs.readFileSync(p, "utf8")

  def gameJson: String = read("MIR_GAME_JSON")
  def tsGolden: String = read("MIR_TS_GOLDEN")
