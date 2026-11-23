package mir.client.render

import mir.client.assets.MapData
import mir.core.*
import mir.protocol.{ActorView, GroundView}

/** 画面上的显示开关（只在本机，存 localStorage；界面在 #1156 的设置窗口里改）。 */
final case class ViewOptions(
    showHpBars: Boolean = true,
    showItemNames: Boolean = true,
    showMonsterNames: Boolean = true,
    soundOn: Boolean = true,
    musicOn: Boolean = false
)

object ViewOptions:
  given zio.json.JsonCodec[ViewOptions] = zio.json.DeriveJsonCodec.gen

/**
 * 这一帧要画的全部东西：由客户端状态拼出（自己已换成本地先走的样子），渲染器只读它。
 * server 是估计的服务器此刻（动作、特效按它算），local 是本地时钟（飘字、气泡、物件动画）。
 */
final case class Scene(
    map: MapData,
    me: ActorId,
    actors: Iterable[ActorView],
    ground: Iterable[GroundView],
    fx: Transients,
    /** 锁定的目标：脚下画红圈 */
    target: Option[ActorId],
    /** 自己开着魔法盾 */
    myShield: Boolean,
    /** 自己隐身（画成半透明） */
    meInvisible: Boolean,
    server: Millis,
    local: Double,
    options: ViewOptions
)
