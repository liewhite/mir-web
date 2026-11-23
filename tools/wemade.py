"""读取 Wemade 传奇2 客户端原始数据：WIL/WIX 图片库、MAP 地图。

只做解析，不做任何业务决定。格式依据 Crystal Mir2 LibraryEditor/WeMadeLibrary.cs、
ReadMap.cs（v0 版 12 字节格子）以及实测。
"""
from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass
from functools import cached_property
from pathlib import Path

import numpy as np


@dataclass(frozen=True)
class Frame:
    """一帧图：RGBA，行自上而下；x,y 是绘制偏移（图左上角相对锚点）。"""
    w: int
    h: int
    x: int
    y: int
    rgba: np.ndarray  # uint8 [h, w, 4]
    index: np.ndarray | None = None  # 8 位帧的调色板下标 [h, w]（标准调色板），16 位帧为 None

    @property
    def empty(self) -> bool:
        return self.w == 0 or self.h == 0


EMPTY = Frame(0, 0, 0, 0, np.zeros((0, 0, 4), np.uint8))


def _find_case_insensitive(path: Path) -> Path:
    if path.exists():
        return path
    for p in path.parent.iterdir():
        if p.name.lower() == path.name.lower():
            return p
    raise FileNotFoundError(path)


class WilLibrary:
    """经典 8 位调色板 WIL（#ILIB v1.0）。"""

    def __init__(self, wil: Path):
        self.wil_path = _find_case_insensitive(wil)
        self.wix_path = _find_case_insensitive(wil.with_suffix(".wix"))
        self.data = self.wil_path.read_bytes()
        hdr = self.data[:60]
        if not hdr.startswith(b"#ILIB") and not hdr.startswith(b"ILIB"):
            raise ValueError(f"{self.wil_path.name}: 不是经典 WIL（头 {hdr[:8]!r}）")
        self.image_count, = struct.unpack_from("<i", self.data, 44)
        self.color_count, = struct.unpack_from("<i", self.data, 48)
        self.version, = struct.unpack_from("<i", self.data, 56)
        if self.color_count != 256:
            raise ValueError(f"{self.wil_path.name}: 只支持 256 色（{self.color_count}）")
        pal_off = 56 if self.version == 0 else 60
        self.palette_off = pal_off
        self.header_size = 8 if self.version == 0 else 12
        wix = self.wix_path.read_bytes()
        idx_off = 48 if self.version == 0 else 52
        n, = struct.unpack_from("<i", wix, 44)
        self.offsets = list(struct.unpack_from(f"<{n}i", wix, idx_off))

    @cached_property
    def palette(self) -> np.ndarray:
        """[256, 4] RGBA，0 号透明。"""
        bgra = np.frombuffer(self.data, np.uint8, 1024, self.palette_off).reshape(256, 4)
        rgba = np.empty_like(bgra)
        rgba[:, 0], rgba[:, 1], rgba[:, 2] = bgra[:, 2], bgra[:, 1], bgra[:, 0]
        rgba[:, 3] = 255
        rgba[0, 3] = 0
        return rgba

    def __len__(self) -> int:
        return len(self.offsets)

    def frame(self, i: int) -> Frame:
        if i < 0 or i >= len(self.offsets):
            return EMPTY
        off = self.offsets[i]
        if off <= 0 or off + 8 > len(self.data):
            return EMPTY
        w, h, x, y = struct.unpack_from("<hhhh", self.data, off)
        if w <= 0 or h <= 0:
            return EMPTY
        start = off + self.header_size
        stride = (w + 3) & ~3  # 行按 4 字节对齐（实测见 verify）
        need = stride * h
        if start + need > len(self.data):
            stride = w
            need = w * h
            if start + need > len(self.data):
                return EMPTY
        raw = np.frombuffer(self.data, np.uint8, need, start).reshape(h, stride)[:, :w]
        idx = raw[::-1]  # 文件里自下而上
        return Frame(w, h, x, y, self.palette[idx], idx)


# Wemade 标准 256 色调色板（ARGB，有符号 int），WZL 不带调色板时用它。来源 Crystal WeMadeLibrary.cs。
_DEFAULT_ARGB = [-16777216, -8388608, -16744448, -8355840, -16777088, -8388480, -16744320, -4144960, -11173737, -6440504, -8686733, -13817559, -10857902, -10266022, -12437191, -14870504, -15200240, -14084072, -15726584, -886415, -2005153, -42406, -52943, -2729390, -7073792, -7067368, -13039616, -9236480, -4909056, -4365486, -12445680, -21863, -10874880, -9225943, -5944783, -7046285, -4369871, -11394800, -8703720, -13821936, -7583183, -7067392, -4378368, -3771566, -9752296, -3773630, -3257856, -5938375, -10866408, -14020608, -15398912, -12969984, -16252928, -14090240, -11927552, -6488064, -2359296, -2228224, -327680, -6524078, -7050422, -9221591, -11390696, -7583208, -7846895, -11919104, -14608368, -2714534, -3773663, -1086720, -35072, -5925756, -12439263, -15200248, -14084088, -14610432, -13031144, -7576775, -12441328, -9747944, -8697320, -7058944, -7568261, -9739430, -11910599, -14081768, -12175063, -4872812, -8688806, -3231340, -5927821, -7572646, -4877197, -2710157, -1071798, -1063284, -8690878, -9742791, -4352934, -10274560, -2701651, -11386327, -7052520, -1059155, -5927837, -10266038, -4348549, -10862056, -4355023, -13291223, -7043997, -8688822, -5927846, -10859991, -6522055, -12439280, -1069791, -15200256, -14081792, -6526208, -7044006, -11386344, -9741783, -8690911, -6522079, -2185984, -10857927, -13555440, -3228293, -10266055, -7044022, -3758807, -15688680, -12415926, -13530046, -15690711, -16246768, -16246760, -16242416, -15187415, -5917267, -9735309, -15193815, -15187382, -13548982, -10238242, -12263937, -7547153, -9213127, -532935, -528500, -530688, -9737382, -10842971, -12995089, -11887410, -13531979, -13544853, -2171178, -4342347, -7566204, -526370, -16775144, -16246727, -16248791, -16246784, -16242432, -16756059, -16745506, -15718070, -15713941, -15707508, -14591323, -15716006, -15711612, -13544828, -15195855, -11904389, -11375707, -14075549, -15709474, -14079711, -11908551, -14079720, -11908567, -8684734, -6513590, -10855895, -12434924, -13027072, -10921728, -3525332, -9735391, -14077696, -13551344, -13551336, -12432896, -11377896, -10849495, -13546984, -15195904, -15191808, -15189744, -10255286, -9716406, -10242742, -10240694, -10838966, -11891655, -10238390, -10234294, -11369398, -13536471, -10238374, -11354806, -15663360, -15193832, -11892662, -11868342, -16754176, -16742400, -16739328, -16720384, -16716288, -16712960, -11904364, -10259531, -8680234, -9733162, -8943361, -3750194, -7039844, -6515514, -13553351, -14083964, -15204220, -11910574, -11386245, -10265997, -3230217, -7570532, -8969524, -2249985, -1002454, -2162529, -1894477, -1040, -6250332, -8355712, -65536, -16711936, -256, -16776961, -65281, -16711681, -1]


def default_palette() -> np.ndarray:
    """[256, 4] RGBA，0 号透明。"""
    v = np.array(_DEFAULT_ARGB, np.int64) & 0xFFFFFFFF
    rgba = np.stack([(v >> 16) & 255, (v >> 8) & 255, v & 255, np.full_like(v, 255)], 1).astype(np.uint8)
    rgba[0, 3] = 0
    return rgba


class WzlLibrary:
    """盛大 WZL/WZX：每帧 zlib 压缩；格式字节 3 = 8 位调色板，5 = 16 位 RGB565（0 = 透明）。其他格式返回空帧并计数。"""

    def __init__(self, wzl: Path):
        self.wil_path = _find_case_insensitive(wzl)
        self.wix_path = _find_case_insensitive(wzl.with_suffix(".wzx"))
        self.data = self.wil_path.read_bytes()
        wix = self.wix_path.read_bytes()
        n, = struct.unpack_from("<i", wix, 44)
        n = min(n, (len(wix) - 48) // 4)
        self.offsets = list(struct.unpack_from(f"<{n}i", wix, 48))
        self.skipped_16bit = 0

    @cached_property
    def palette(self) -> np.ndarray:
        return default_palette()

    def __len__(self) -> int:
        return len(self.offsets)

    def frame(self, i: int) -> Frame:
        if i < 0 or i >= len(self.offsets):
            return EMPTY
        off = self.offsets[i]
        if off <= 0 or off + 16 > len(self.data):
            return EMPTY
        fmt = self.data[off]
        w, h, x, y, size = struct.unpack_from("<hhhhi", self.data, off + 4)
        if w <= 0 or h <= 0:
            return EMPTY
        bpp = {3: 1, 5: 2}.get(fmt)
        if bpp is None:
            self.skipped_16bit += 1
            return EMPTY
        stride = ((w * bpp) + 3) & ~3
        body = self.data[off + 16: off + 16 + size] if size > 0 else None
        raw = zlib.decompress(body) if body else self.data[off + 16: off + 16 + stride * h]
        if len(raw) < stride * h:
            return EMPTY
        rows = np.frombuffer(raw, np.uint8, stride * h).reshape(h, stride)[::-1]
        if bpp == 1:
            return Frame(w, h, x, y, self.palette[rows[:, :w]], rows[:, :w])
        c = rows[:, :w * 2].copy().view("<u2").astype(np.uint32)
        rgba = np.empty((h, w, 4), np.uint8)
        rgba[..., 0] = (c >> 8) & 0xF8
        rgba[..., 1] = (c >> 3) & 0xFC
        rgba[..., 2] = (c << 3) & 0xF8
        rgba[..., 3] = np.where(c == 0, 0, 255)
        return Frame(w, h, x, y, rgba)


def open_library(data_dir: Path, name: str):
    """按名字找图片库：先 .wil，再 .wzl（不分大小写）。找不到抛 FileNotFoundError。"""
    names = {p.name.lower(): p for p in data_dir.iterdir()}
    if f"{name.lower()}.wil" in names:
        return WilLibrary(names[f"{name.lower()}.wil"])
    if f"{name.lower()}.wzl" in names:
        return WzlLibrary(names[f"{name.lower()}.wzl"])
    raise FileNotFoundError(f"{data_dir}/{name}")


@dataclass(frozen=True)
class MapFile:
    """经典 .map：52 字节头 + w*h 个 12 字节格子，按列存（x 外层）。"""
    width: int
    height: int
    back: np.ndarray    # uint16 [w,h]，最高位 = 不可走
    middle: np.ndarray  # uint16
    front: np.ndarray   # uint16，最高位 = 不可飞/不可走
    door_index: np.ndarray
    door_offset: np.ndarray
    anim_frame: np.ndarray
    anim_tick: np.ndarray
    area: np.ndarray    # Objects{area+1}.wil
    light: np.ndarray

    @staticmethod
    def load(path: Path) -> "MapFile":
        data = _find_case_insensitive(path).read_bytes()
        w, h = struct.unpack_from("<HH", data, 0)
        cell = 12
        body = len(data) - 52
        if body < w * h * cell:
            raise ValueError(f"{path.name}: 长度不符 {len(data)} vs {w}x{h}")
        if body >= w * h * 14 and body % (w * h) == 0 and body // (w * h) == 14:
            cell = 14
        rec = np.frombuffer(data, np.uint8, w * h * cell, 52).reshape(w, h, cell)
        u16 = lambda o: rec[:, :, o].astype(np.uint16) | (rec[:, :, o + 1].astype(np.uint16) << 8)
        return MapFile(
            width=w, height=h,
            back=u16(0), middle=u16(2), front=u16(4),
            door_index=rec[:, :, 6].copy(), door_offset=rec[:, :, 7].copy(),
            anim_frame=rec[:, :, 8].copy(), anim_tick=rec[:, :, 9].copy(),
            area=rec[:, :, 10].copy(), light=rec[:, :, 11].copy(),
        )

    @property
    def walkable(self) -> np.ndarray:
        return ((self.back & 0x8000) == 0) & ((self.front & 0x8000) == 0)
