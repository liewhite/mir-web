/** 音效与背景音乐：Web Audio 播放 assets/sound/<编号>.m4a（tools/build_sounds.py 生成），按距离衰减。 */
import type { Pos } from '../core/geom';
import { assetUrl } from '../assets/sprites';

export class Audio {
  private ctx: AudioContext | null = null;
  private readonly buffers = new Map<number, AudioBuffer | 'loading' | 'none'>();
  private available = new Set<number>();
  private bgm: HTMLAudioElement | null = null;
  private recent = new Map<number, number>();
  soundOn = true;
  musicOn = false;

  async init(): Promise<void> {
    try {
      const r = await fetch(assetUrl('assets/sound/index.json'));
      if (r.ok) this.available = new Set(((await r.json()) as { ids: number[] }).ids);
    } catch {
      // 没有音效也能玩
    }
  }

  /** 浏览器要求用户操作后才能出声：第一次点击/按键时调用。 */
  unlock(): void {
    if (!this.ctx) {
      const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (!AC) return;
      this.ctx = new AC();
    }
    if (this.ctx.state === 'suspended') void this.ctx.resume();
    if (this.musicOn) this.playMusic('field');
  }

  /** 播放一个音效；at 给出声源格子时按与玩家的距离降低音量。 */
  play(id: number, at?: Pos, listener?: Pos): void {
    if (!this.soundOn || !this.ctx || !this.available.has(id)) return;
    const now = performance.now();
    if (now - (this.recent.get(id) ?? 0) < 60) return; // 同一声音太密就跳过
    this.recent.set(id, now);
    let vol = 0.8;
    if (at && listener) {
      const d = Math.max(Math.abs(at.x - listener.x), Math.abs(at.y - listener.y));
      if (d > 12) return;
      vol *= 1 - d / 14;
    }
    const buf = this.buffers.get(id);
    if (buf instanceof AudioBuffer) {
      this.start(buf, vol);
      return;
    }
    if (buf) return;
    this.buffers.set(id, 'loading');
    void this.load(id).then((b) => {
      this.buffers.set(id, b ?? 'none');
    });
  }

  private start(buf: AudioBuffer, vol: number): void {
    const ctx = this.ctx!;
    const src = ctx.createBufferSource();
    src.buffer = buf;
    const g = ctx.createGain();
    g.gain.value = vol;
    src.connect(g).connect(ctx.destination);
    src.start();
  }

  private async load(id: number): Promise<AudioBuffer | null> {
    try {
      const r = await fetch(assetUrl(`assets/sound/${id}.m4a`));
      if (!r.ok) return null;
      const data = await r.arrayBuffer();
      return await new Promise<AudioBuffer>((res, rej) => this.ctx!.decodeAudioData(data, res, rej));
    } catch {
      return null;
    }
  }

  playMusic(name: string): void {
    if (!this.musicOn) return;
    if (!this.bgm) {
      this.bgm = new window.Audio(assetUrl(`assets/sound/bgm_${name}.m4a`));
      this.bgm.loop = true;
      this.bgm.volume = 0.35;
    }
    void this.bgm.play().catch(() => {});
  }

  stopMusic(): void {
    this.bgm?.pause();
  }
}
