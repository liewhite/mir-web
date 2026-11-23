/**
 * 鼠标、触屏、键盘 → 先给界面，界面不要的再变成 Controller 意图。
 * - 原版布局（电脑、平板）：左键按住走、右键按住跑、点怪攻击、F1-F8 技能；触屏点哪走哪、按住拖动跑。
 * - 手机布局（用户 #1140 定）：左半屏是虚拟摇杆控制走跑；点怪只锁定目标（攻击键、技能打它），点 NPC 对话；
 *   多指同时操作（一指摇杆、一指按技能）。
 */
import type { Renderer } from '../render/renderer';
import type { ClassicUi } from '../ui/classic/classic';
import type { Controller } from './controller';

type Pt = { x: number; y: number };

/** 界面和地图各用各的坐标：uiScale 是「1 界面像素 = 多少 CSS 像素」（手机上和地图缩放不同）。 */
export function bindInput(canvas: HTMLCanvasElement, r: Renderer, ctl: Controller, ui: ClassicUi, uiScale: () => number): void {
  let held: { id: number; run: boolean; start: number; moved: boolean; touch: boolean; x0: number; y0: number } | null = null;
  /** 正按着界面按钮的手指（松开时只有它触发按钮） */
  let uiPointer: number | null = null;
  let lastMouse = { x: 0, y: 0 };
  const stick = ui.joystick;

  const css = (e: PointerEvent): Pt => {
    const rect = canvas.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  };
  const logical = (e: PointerEvent): Pt => {
    const c = css(e);
    return r.toLogical(c.x, c.y);
  };
  const uiPos = (e: PointerEvent): Pt => {
    const c = css(e);
    const s = uiScale();
    return { x: c.x / s, y: c.y / s };
  };
  /** 角色点击：手指比鼠标粗，触屏时点击框向外放宽约 14 个 CSS 像素 */
  const actorUnder = (p: Pt, touch: boolean) => {
    const a = r.actorAt(p.x, p.y, touch ? 14 / r.zoom : 0, ctl.me.id);
    return a && a.id !== ctl.me.id ? a : undefined;
  };
  const groundItem = (p: Pt) => {
    const cell = r.cellAt(p.x, p.y);
    return ctl.world.items.find((i) => i.x === cell.x && i.y === cell.y);
  };

  canvas.addEventListener('contextmenu', (e) => e.preventDefault());

  canvas.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    const p = logical(e);
    lastMouse = p;
    const u = uiPos(e);
    if (ui.pointerDown(u.x, u.y, e.button)) {
      uiPointer = e.pointerId;
      return;
    }
    const touch = e.pointerType !== 'mouse';
    try {
      canvas.setPointerCapture(e.pointerId); // 手指滑出画布也继续收到移动
    } catch {
      /* 合成事件没有真实指针，抓不了也不影响 */
    }
    if (ui.layout.joystick) {
      const target = actorUnder(p, true);
      if (target) return ctl.lock(target);
      const item = groundItem(p);
      if (item) return ctl.pickup(item);
      // 左半屏：摇杆（以按下的位置为中心）
      if (!stick.active && u.x < ui.W / 2) stick.start(e.pointerId, u);
      return;
    }
    const target = actorUnder(p, touch);
    if (target) return ctl.attack(target);
    const item = groundItem(p);
    if (item && e.button === 0) return ctl.pickup(item);
    const c = css(e);
    const cell = r.cellAt(p.x, p.y);
    held = { id: e.pointerId, run: e.button === 2, start: performance.now(), moved: false, touch, x0: c.x, y0: c.y };
    if (touch) ctl.goTo(cell); // 平板：点哪走到哪
    else ctl.steer(cell, held.run);
  });

  canvas.addEventListener('pointermove', (e) => {
    if (stick.pointer === e.pointerId) {
      const d = stick.move(uiPos(e));
      if (d) ctl.stick(d.dx, d.dy, d.run);
      else ctl.stop();
      return;
    }
    const p = logical(e);
    lastMouse = p;
    const u = uiPos(e);
    ui.pointerMove(u.x, u.y);
    if (e.pointerType === 'mouse') r.hoverActor = ui.covers(u.x, u.y) ? 0 : (actorUnder(p, false)?.id ?? 0);
    if (!held || held.id !== e.pointerId) return;
    // 手指点下去总会抖几个像素：移动超过 12 像素才算拖动，否则还是「点哪走哪」
    const c = css(e);
    if (held.touch && !held.moved && Math.hypot(c.x - held.x0, c.y - held.y0) < 12) return;
    held.moved = true;
    const cell = r.cellAt(p.x, p.y);
    // 平板按住拖动 = 朝手指方向跑
    ctl.steer(cell, held.run || (held.touch && performance.now() - held.start > 250));
  });

  const release = (e: PointerEvent): void => {
    if (stick.pointer === e.pointerId) {
      stick.end();
      ctl.stop();
      return;
    }
    if (uiPointer === e.pointerId || e.pointerType === 'mouse') {
      const u = uiPos(e);
      ui.pointerUp(u.x, u.y);
      uiPointer = null;
    }
    if (!held || held.id !== e.pointerId) return;
    const wasTouchTap = held.touch && !held.moved;
    held = null;
    if (!wasTouchTap) ctl.stop();
  };
  canvas.addEventListener('pointerup', release);
  canvas.addEventListener('pointercancel', release);

  window.addEventListener('keydown', (e) => {
    const hover = r.actorAt(lastMouse.x, lastMouse.y);
    const cell = r.cellAt(lastMouse.x, lastMouse.y);
    const f = /^F([1-8])$/.exec(e.key);
    if (f) {
      e.preventDefault();
      ctl.castByKey(Number(f[1]), hover, cell);
      return;
    }
    if (/^[1-6]$/.test(e.key)) {
      const it = ctl.me.belt[Number(e.key) - 1];
      if (it) {
        const why = ctl.world.useItem(ctl.me, it.uid);
        if (why) ui.message(why, 'bad');
      }
      return;
    }
    switch (e.key.toLowerCase()) {
      case 'f9':
      case 'b':
      case 'i':
        e.preventDefault();
        ui.toggleBag();
        break;
      case 'f10':
      case 'c':
        e.preventDefault();
        ui.toggleState(0);
        break;
      case 'f11':
      case 'k':
        e.preventDefault();
        ui.toggleState(3);
        break;
      case ' ':
        e.preventDefault();
        ctl.world.pickUp(ctl.me);
        break;
      case 'tab':
      case 'm':
        e.preventDefault();
        ui.toggleMiniMap();
        break;
      case 'escape':
        ui.closeAll();
        break;
    }
  });
}
