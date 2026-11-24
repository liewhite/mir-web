package mir.social

import mir.protocol.*

/**
 * 私聊（#1221，照原版 M2Server ObjPlay.pas TPlayObject.Whisper）：聊天框打「/名字 内容」。
 * 对方在线（不管在哪张图、死没死）就送到，同时回给发的人一份「已送到」；不在线回「XX 没有在线」。
 * 原版的拒绝私聊开关、黑名单这次不做。
 */
object Whispers:
  /** 一句私聊最多多少字（和聊天框、普通说话一样） */
  val MaxLength = 100

  def send(me: String, to: String, text: String, online: String => Option[Seen]): Vector[Letter] =
    val who = to.trim
    val t   = text.trim.take(MaxLength)
    if who.isEmpty || t.isEmpty then Social.refuse(me, "私聊的格式：/名字 内容（名字后面空一格）。")
    else
      online(who) match
        case Some(s) =>
          Vector(Letter(s.name, ServerMsg.Whisper(me, t, WhisperWay.Received)), Letter(me, ServerMsg.Whisper(s.name, t, WhisperWay.Sent)))
        case None => Social.refuse(me, s"$who 没有在线。")
