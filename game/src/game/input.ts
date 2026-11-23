/** 鼠标、触屏、键盘 → 先给界面，界面不要的再变成 Controller 意图。原版手感：左键按住走、右键按住跑、点怪攻击、F1-F8 技能。 */
import type { Renderer } from '../render/renderer';
import type { ClassicUi } from '../ui/classic/classic';
import type { Controller } from './controller';

export function bindInput(canvas: HTMLCanvasElement, r: Renderer, ctl: Controller, ui: ClassicUi): void {
  let held: { id: number; run: boolean; start: number; moved: boolean; touch: boolean } | null = null;
  let lastMouse = { x: 0, y: 0 };

  const logical = (e: PointerEvent): { x: number; y: number } => {
    const rect = canvas.getBoundingClientRect();
    return r.toLogical(e.clientX - rect.left, e.clientY - rect.top);
  };

  canvas.addEventListener('contextmenu', (e) => e.preventDefault());

  canvas.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    const p = logical(e);
    lastMouse = p;
    if (ui.pointerDown(p.x, p.y, e.button)) return;
    const target = r.actorAt(p.x, p.y);
    if (target && target.id !== ctl.me.id) {
      ctl.attack(target);
      return;
    }
    const cell = r.cellAt(p.x, p.y);
    const item = ctl.world.items.find((i) => i.x === cell.x && i.y === cell.y);
    if (item && e.button === 0) {
      ctl.pickup(item);
      return;
    }
    const touch = e.pointerType !== 'mouse';
    held = { id: e.pointerId, run: e.button === 2, start: performance.now(), moved: false, touch };
    canvas.setPointerCapture(e.pointerId);
    if (touch) ctl.goTo(cell); // 手机：点哪走到哪
    else ctl.steer(cell, held.run);
  });

  canvas.addEventListener('pointermove', (e) => {
    const p = logical(e);
    lastMouse = p;
    ui.pointerMove(p.x, p.y);
    if (e.pointerType === 'mouse') r.hoverActor = ui.covers(p.x, p.y) ? 0 : (r.actorAt(p.x, p.y)?.id ?? 0);
    if (!held || held.id !== e.pointerId) return;
    held.moved = true;
    const cell = r.cellAt(p.x, p.y);
    // 手机按住拖动 = 朝手指方向跑
    ctl.steer(cell, held.run || (held.touch && performance.now() - held.start > 250));
  });

  const release = (e: PointerEvent): void => {
    const p = logical(e);
    ui.pointerUp(p.x, p.y);
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
      case 'escape':
        ui.closeAll();
        break;
    }
  });
}
