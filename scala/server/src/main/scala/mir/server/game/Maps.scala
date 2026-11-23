package mir.server.game

import mir.core.*
import mir.data.Catalog
import mir.rules.Rng
import mir.world.{Terrain, UidSource, World}

import java.nio.file.{Files, Path}
import scala.collection.mutable

/**
 * 按需开图：第一次有人去某张图时读数据表里的地图和格子文件（ASSETS_DIR/map/<素材名>.bin）建一个世界。
 * 开过的图一直留着（怪物、地上的东西都在，没人时世界自己不动）。只在游戏线程里用。
 */
final class Maps(catalog: Catalog, assetsDir: Path, uids: UidSource, clock: () => Millis):
  private val worlds = mutable.LinkedHashMap.empty[MapId, World]

  def loaded: Iterable[World] = worlds.values

  def get(id: MapId): Either[String, World] =
    worlds.get(id).toRight(()).left.flatMap { _ =>
      for
        defn    <- catalog.map(id).toRight(s"数据表里没有地图 ${id.value}")
        file     = assetsDir.resolve("map").resolve(s"${defn.asset}.bin")
        bytes   <- Either.cond(Files.isRegularFile(file), Files.readAllBytes(file), s"缺少地图文件 $file")
        terrain <- Terrain.parseBin(bytes).left.map(e => s"地图文件 $file 解析失败：$e")
      yield
        val w = World(catalog, defn, terrain, Rng.fromScala(new scala.util.Random()), uids, clock())
        worlds(id) = w
        w
    }
