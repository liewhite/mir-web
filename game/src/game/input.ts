/** 鼠标、触屏、键盘 → Controller 意图。保留原版手感：左键按住走、右键按住跑、点怪攻击、F1-F8 技能。 */
import type { Renderer } from '../render/renderer';
import type { Ui } from '../ui/ui';
import type { Controller } from './controller';

export function bindInput(canvas: HTMLCanvasElement, r: Renderer, ctl: Controller, ui: Ui): void {
  let held: { id: number; run: boolean; start: number; moved: boolean; touch: boolean } | null = null;
  let lastMouse = { x: 0, y: 0 };

  const logical = (e: PointerEvent | MouseEvent): { x: number; y: number } => {
    const rect = canvas.getBoundingClientRect();
    return r.toLogical(e.clientX - rect.left, e.clientY - rect.top);
  };

  canvas.addEventListener('contextmenu', (e) => e.preventDefault());

  canvas.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    ui.hideTip();
    const p = logical(e);
    lastMouse = p;
    const target = r.actorAt(p.x, p.y);
    if (target && target.id !== ctl.me.id) {
      ctl.attack(target);
      return;
    }
    const cell = r.cellAt(p.x, p.y);
    const item = ctl.world.items.find((i) => i.x === cell.x && i.y === cell.y);
    if (item) {
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
    if (e.pointerType === 'mouse') r.hoverActor = r.actorAt(p.x, p.y)?.id ?? 0;
    if (!held || held.id !== e.pointerId) return;
    held.moved = true;
    const cell = r.cellAt(p.x, p.y);
    // 手机按住拖动 = 朝手指方向跑
    ctl.steer(cell, held.run || (held.touch && performance.now() - held.start > 250));
  });

  const release = (e: PointerEvent): void => {
    if (!held || held.id !== e.pointerId) return;
    const wasTouchTap = held.touch && !held.moved;
    held = null;
    if (!wasTouchTap) ctl.stop();
  };
  canvas.addEventListener('pointerup', release);
  canvas.addEventListener('pointercancel', release);

  window.addEventListener('keydown', (e) => {
    if ((e.target as HTMLElement).tagName === 'INPUT' || (e.target as HTMLElement).tagName === 'SELECT') return;
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
        ui.invalidate();
      }
      return;
    }
    switch (e.key.toLowerCase()) {
      case 'b':
      case 'i':
        ui.toggle('bag');
        break;
      case 'k':
        ui.toggle('skills');
        break;
      case ' ':
        e.preventDefault();
        ctl.world.pickUp(ctl.me);
        ui.invalidate();
        break;
      case 'escape':
        ui.closePanels();
        break;
    }
  });
}
