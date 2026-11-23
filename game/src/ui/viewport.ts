/**
 * 按屏幕定「地图怎么缩放、界面怎么缩放、用哪套布局」。
 * - board：原版布局（电脑、平板）。地图和界面一起按 800x600 等比缩放。
 * - touch：手机布局（触屏且短边 < 600）。地图按短边看到约 560 逻辑像素；界面按手指大小画，
 *   原版窗口约 1 倍大小（字 12 像素、包裹格 36 像素），不再跟着地图缩小。
 */
export type LayoutKind = 'board' | 'touch';

export interface Viewport {
  kind: LayoutKind;
  /** 地图：1 逻辑像素 = 多少 CSS 像素 */
  worldZoom: number;
  /** 界面：1 界面像素 = 多少 CSS 像素 */
  uiScale: number;
}

const clamp = (v: number, lo: number, hi: number): number => Math.max(lo, Math.min(hi, v));

/**
 * 用户 11-23（#1140）：暂时只保留手机布局（摇杆），电脑、平板也用它。
 * 原版布局代码留着，以后要恢复电脑端时把它打开。
 */
const MOBILE_ONLY = true;

export function chooseViewport(cssW: number, cssH: number, coarsePointer: boolean): Viewport {
  const fit800 = Math.min(cssW / 800, cssH / 600);
  const short = Math.min(cssW, cssH);
  if (MOBILE_ONLY || (coarsePointer && short < 600)) {
    return {
      kind: 'touch',
      worldZoom: Math.max(fit800, short / 560),
      // 横屏按高 390 排得下商店+商品列表，竖屏按宽 420 排得下商人对话框
      uiScale: clamp(Math.min(cssW / 420, cssH / 390), 0.9, 1.3),
    };
  }
  return { kind: 'board', worldZoom: fit800, uiScale: fit800 };
}

/** 设备是不是以触屏为主（手指，没有悬停） */
export function coarsePointer(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;
}
