package mir.protocol

import mir.core.Build

/**
 * 一个构建对外的样子（#1242、#1289）：页面和服务端各有一份，连上时服务端在 LoggedIn 里发来，页面拿来比。
 *
 * - commit：构建时的 git 提交，不同就是部署过新版。
 * - wire：通讯格式（双方收发的消息长什么样），不同就是旧页面会看错消息，必须马上刷新。
 * - data：游戏数据（game.json）的摘要。页面里 NPC 商店、传送点等按数据表的下标说话，数据表变了旧页面会点错，也要马上刷新。
 *
 * wire、data 都一样时旧页面可以先接着玩，找个本来就会切画面的时机再刷新（见客户端 Freshness）。
 */
final case class Release(commit: String, wire: String, data: String):
  /** 页面（this）能不能先接着和 server 玩：通讯格式和游戏数据都一样 */
  def compatible(server: Release): Boolean = wire == server.wire && data == server.data

object Release:
  /**
   * 通讯格式的手动版本号：结构摘要看不出的改动（手写编解码器的取值映射、不透明类型背后的类型、同一条消息的含义变了）
   * 改了就加一。
   */
  val WireRevision = 1

  /** 本构建的通讯格式：手动版本号 + 编译期算出的消息结构摘要（WireShape） */
  val wire: String = s"$WireRevision-${WireShape.digest[ClientCmd | ServerMsg]}"

  /** 本构建配上这份游戏数据（game.json 原文） */
  def of(gameJson: String): Release = Release(Build.commit, wire, dataPrint(gameJson))

  /** 游戏数据的摘要：64 位 FNV-1a，按 UTF-16 码元算，服务端（JVM）和页面（JS）对同一段文字算出一样的值 */
  def dataPrint(text: String): String =
    var h = 0xcbf29ce484222325L
    var i = 0
    while i < text.length do
      h = (h ^ text.charAt(i).toLong) * 0x100000001b3L
      i += 1
    java.lang.Long.toHexString(h)
