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
  val scalajsDom = "2.8.1"
  val macrotask  = "1.1.1"
  val zioHttp = "3.11.6"
  val sqlite  = "3.54.1.0"
  val bc      = "1.84" // 与 zio-http 带进来的 bcpkix、bcutil 同版本
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
    Test / javaOptions ++= Seq("-Dfile.encoding=UTF-8", s"-Dmir.gameJson=${gameJson.value}", s"-Dmir.tsGolden=${tsGolden.value}", s"-Dmir.scalaRoot=${(ThisBuild / baseDirectory).value}")
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
    libraryDependencies ++= Seq(
      "dev.zio"          %% "zio"           % V.zio,
      "dev.zio"          %% "zio-http"      % V.zioHttp,
      "org.xerial"        % "sqlite-jdbc"   % V.sqlite,
      "org.bouncycastle"  % "bcprov-jdk18on" % V.bc // scrypt（与旧 Node 服务端同参数）
    ),
    // zio-http 3.11.6 默认拉 zio-schema 1.8.7；统一到 1.9.1（与本机其他项目一致，免得重复下载）
    dependencyOverrides ++= Seq("zio-schema", "zio-schema-json", "zio-schema-derivation").map("dev.zio" %% _ % "1.9.1"),
    Compile / mainClass := Some("mir.server.Main"),
    Test / fork := true,
    Test / javaOptions ++= Seq("-Dfile.encoding=UTF-8", s"-Dmir.assets=${(ThisBuild / baseDirectory).value.getParentFile / "public" / "assets"}"),
    // 部署包：单个 jar（deploy/jvm/deploy.sh 调 sbt server/assembly）
    assembly / assemblyJarName := "mir176.jar",
    assembly / packageOptions += Package.ManifestAttributes("Enable-Native-Access" -> "ALL-UNNAMED"),
    assembly / assemblyMergeStrategy := {
      case PathList("module-info.class")                            => MergeStrategy.discard
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.contains("MANIFEST.MF") && xs.contains("OSGI-INF") => MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties")     => MergeStrategy.first
      case PathList("scala", "annotation", f) if f.startsWith("unroll.") => MergeStrategy.first
      case "deriving.conf"                                          => MergeStrategy.first
      case x =>
        val old = (assembly / assemblyMergeStrategy).value
        old(x)
    }
  )

// ---- 客户端（Scala.js）：#1155 ----
lazy val client = project
  .in(file("client"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(shared.js)
  .settings(zioTest)
  .settings(
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom"                % V.scalajsDom,
      "org.scala-js" %%% "scala-js-macrotask-executor" % V.macrotask
    ),
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    // 产物放进 web/js/（web/ 是服务端在 / 提供的静态目录：index.html、style.css、js/main.js）
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "web" / "js",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "web" / "js",
    // 测试在 Node 里跑纯逻辑（帧号、摇杆、走路预测、地图解析），用 CommonJS
    Test / scalaJSUseMainModuleInitializer := false,
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    Test / jsEnv := new NodeJSEnv(NodeJSEnv.Config().withEnv(Map("MIR_GAME_JSON" -> gameJson.value.toString)))
  )

lazy val root = project
  .in(file("."))
  .aggregate(shared.jvm, shared.js, server, client)
  .settings(publish / skip := true)
