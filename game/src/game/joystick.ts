/**
 * 手机左侧虚拟摇杆（用户 #1140 要求）：手指在屏幕左半边按下的位置就是摇杆中心，
 * 拖动方向按 8 方向取整（原版人物只走 8 个方向），推得远就跑、近就走。坐标用界面像素。
 */
type Pt = { x: number; y: number };

export interface StickDir {
  dx: -1 | 0 | 1;
  dy: -1 | 0 | 1;
  run: boolean;
}

/** 摇杆半径（界面像素） */
export const STICK_R = 56;
/** 小于这个距离不动（手指抖动） */
const DEAD = 12;
/** 超过半径的这个比例就跑 */
const RUN_AT = 0.6;

export class Joystick {
  /** 按着摇杆的手指；没有是 null */
  pointer: number | null = null;
  base: Pt = { x: 0, y: 0 };
  knob: Pt = { x: 0, y: 0 };

  get active(): boolean {
    return this.pointer !== null;
  }

  start(pointer: number, at: Pt): void {
    this.pointer = pointer;
    this.base = at;
    this.knob = at;
  }

  /** 手指移到 at：返回方向，在死区里返回 null */
  move(at: Pt): StickDir | null {
    const vx = at.x - this.base.x;
    const vy = at.y - this.base.y;
    const d = Math.hypot(vx, vy);
    const k = d > STICK_R ? STICK_R / d : 1;
    this.knob = { x: this.base.x + vx * k, y: this.base.y + vy * k };
    if (d < DEAD) return null;
    // 8 方向：每 45° 一格
    const sector = Math.round(Math.atan2(vy, vx) / (Math.PI / 4));
    const a = (sector * Math.PI) / 4;
    return { dx: Math.round(Math.cos(a)) as StickDir['dx'], dy: Math.round(Math.sin(a)) as StickDir['dy'], run: d >= STICK_R * RUN_AT };
  }

  end(): void {
    this.pointer = null;
  }
}
