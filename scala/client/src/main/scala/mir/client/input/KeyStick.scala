package mir.client.input

import mir.core.Dir

/** 键盘上当摇杆用的一个键（按 KeyboardEvent.code 认，不受大小写、Shift、输入法影响）。 */
enum MoveKey(val code: String, val dx: Int, val dy: Int):
  case W extends MoveKey("KeyW", 0, -1)
  case A extends MoveKey("KeyA", -1, 0)
  case S extends MoveKey("KeyS", 0, 1)
  case D extends MoveKey("KeyD", 1, 0)

object MoveKey:
  def of(code: String): Option[MoveKey] = values.find(_.code == code)

/** 键盘方向变化后该怎么做。 */
enum KeyMove:
  /** 朝这个方向走（跑） */
  case Go(stick: Stick)
  /** 停下 */
  case Stop
  /** 斜走时松开了一个键：等 KeyStick.SettleMs 再看（两个键几乎同时松开就直接停，不拐成直走一步） */
  case Settle
  /** 方向没变，不用管 */
  case Same

/**
 * 电脑键盘摇杆（用户 #1189）：WASD 单键 4 方向，两键组合斜走，共 8 方向；默认跑，按住 Shift 走。
 * 相对的两个键（W+S、A+D）互相抵消。只记按键状态、算方向，不碰 DOM，事件接线在 Input。
 */
final class KeyStick:
  private var held = Set.empty[MoveKey]
  private var walk = false

  /** 现在的方向：没按或相互抵消为 None。 */
  def current: Option[Stick] =
    val dx = held.iterator.map(_.dx).sum
    val dy = held.iterator.map(_.dy).sum
    Option.when(dx != 0 || dy != 0)(Stick(Dir.fromVector(dx, dy), run = !walk))

  def active: Boolean = current.isDefined

  def press(k: MoveKey): KeyMove = change(held + k)

  def release(k: MoveKey): KeyMove =
    val before = current
    val move   = change(held - k)
    (before, move) match
      case (Some(b), KeyMove.Go(_)) if isDiagonal(b.dir) => KeyMove.Settle
      case _                                              => move

  /** Shift 按下或松开 */
  def shift(down: Boolean): KeyMove =
    val before = current
    walk = down
    diff(before)

  /** 窗口失焦：全部松开 */
  def clear(): KeyMove =
    val before = current
    held = Set.empty
    walk = false
    diff(before)

  private def change(next: Set[MoveKey]): KeyMove =
    val before = current
    held = next
    diff(before)

  private def diff(before: Option[Stick]): KeyMove = (before, current) match
    case (b, c) if b == c => KeyMove.Same
    case (_, Some(c))     => KeyMove.Go(c)
    case (_, None)        => KeyMove.Stop

  private def isDiagonal(d: Dir): Boolean = d.dx != 0 && d.dy != 0

object KeyStick:
  /** 斜走松开一个键后，等这么久（毫秒）再拐成直走：人松两个键前后一般差几十毫秒 */
  val SettleMs = 80.0
