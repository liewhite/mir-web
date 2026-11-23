// 传奇 1.76 多人版（Scala 3，#1148）。
// 模块与依赖方向：shared（JVM+JS 共用的纯逻辑：领域类型、游戏数据、规则、通信协议）← server（JVM）、client（Scala.js）。
// server 与 client 互不依赖，只通过 shared 里的协议说话。

import org.scalajs.jsenv.nodejs.NodeJSEnv

ThisBuild / scalaVersion := "3.10.1"
ThisBuild / organization := "mir176"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:imports",
  "-Wvalue-discard",
  "-Werror"
)

val V = new {
  val zio     = "2.1.27"
  val zioJson = "1.1.2"
}

// 测试读真实的游戏数据（tools/build_data.py 产出，不进 git；worktree 里 public/assets 软链到主仓库）
lazy val gameJson = settingKey[File]("public/assets/data/game.json 的绝对路径")
ThisBuild / gameJson := (ThisBuild / baseDirectory).value.getParentFile / "public" / "assets" / "data" / "game.json"
// TS 版规则算出的对照值（testdata/gen-golden.sh 生成，进 git）
lazy val tsGolden = settingKey[File]("testdata/ts-golden.json 的绝对路径")
ThisBuild / tsGolden := (ThisBuild / baseDirectory).value / "testdata" / "ts-golden.json"

lazy val zioTest = Def.settings(
  libraryDependencies ++= Seq(
    "dev.zio" %%% "zio-test"     % V.zio % Test,
    "dev.zio" %%% "zio-test-sbt" % V.zio % Test
  )
)

// ---- 共用：领域类型、游戏数据、规则、协议（不碰网络、文件、DOM） ----
lazy val shared = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("shared"))
  .settings(zioTest)
  .settings(libraryDependencies += "dev.zio" %%% "zio-json" % V.zioJson)
  .jvmSettings(
    Test / fork := true,
    Test / javaOptions ++= Seq("-Dfile.encoding=UTF-8", s"-Dmir.gameJson=${gameJson.value}", s"-Dmir.tsGolden=${tsGolden.value}")
  )
  .jsSettings(
    // 测试在 Node 里跑，用 CommonJS 才能 import node:fs 读 game.json
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    Test / jsEnv := new NodeJSEnv(
      NodeJSEnv.Config().withEnv(Map("MIR_GAME_JSON" -> gameJson.value.toString, "MIR_TS_GOLDEN" -> tsGolden.value.toString))
    )
  )

// ---- 服务端（JVM）：#1154 ----
lazy val server = project
  .in(file("server"))
  .dependsOn(shared.jvm)
  .settings(zioTest)
  .settings(
    libraryDependencies += "dev.zio" %% "zio" % V.zio,
    Test / fork := true
  )

// ---- 客户端（Scala.js）：#1155 ----
lazy val client = project
  .in(file("client"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(shared.js)
  .settings(zioTest)
  .settings(
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule))
  )

lazy val root = project
  .in(file("."))
  .aggregate(shared.jvm, shared.js, server, client)
  .settings(publish / skip := true)
