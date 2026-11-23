package mir.core

import zio.json.*

/** 枚举的 JSON 编码：协议里按名字（可读、改顺序不坏），数据表里按原版数字编号。 */
object EnumCodec:
  def byName[E](values: Array[E], name: E => String): JsonCodec[E] =
    val m = values.map(e => name(e) -> e).toMap
    JsonCodec.string.transformOrFail(s => m.get(s).toRight(s"未知取值：$s"), name)

  def byCode[E](values: Array[E], code: E => Int): JsonCodec[E] =
    val m = values.map(e => code(e) -> e).toMap
    JsonCodec.int.transformOrFail(n => m.get(n).toRight(s"未知编号：$n"), code)

  def fieldByName[E](values: Array[E], name: E => String): (JsonFieldEncoder[E], JsonFieldDecoder[E]) =
    val m = values.map(e => name(e) -> e).toMap
    (
      JsonFieldEncoder.string.contramap(name),
      JsonFieldDecoder.string.mapOrFail(s => m.get(s).toRight(s"未知取值：$s"))
    )
