import sys
from pathlib import Path
import numpy as np
from PIL import Image
from wemade import open_library, MapFile
D=Path(sys.argv[1]); mp=sys.argv[2]; cx,cy,R=int(sys.argv[3]),int(sys.argv[4]),int(sys.argv[5])
M=MapFile.load(Path(sys.argv[7])/f'{mp}.map')
print('map',M.width,M.height,'areas',np.unique(M.area))
libs={}
def lib(n):
    if n not in libs:
        try: libs[n]=open_library(D,n)
        except Exception as e: print('miss',n,e); libs[n]=None
    return libs[n]
x0,y0=cx-R,cy-R; W=(2*R)*48; H=(2*R)*32
img=Image.new('RGBA',(W,H),(0,0,0,255))
def paste(L,i,px,py):
    if L is None: return
    f=L.frame(i)
    if f.empty: return
    im=Image.fromarray(f.rgba,'RGBA')
    img.alpha_composite(im,(px,py)) if 0<=px and 0<=py and px+f.w<=W and py+f.h<=H else img.paste(im,(px,py),im)
for x in range(x0,x0+2*R):
    for y in range(y0,y0+2*R):
        if x%2==0 and y%2==0:
            b=int(M.back[x,y])&0x7fff
            if b: paste(lib('Tiles'),b-1,(x-x0)*48,(y-y0)*32)
for x in range(x0,x0+2*R):
    for y in range(y0,y0+2*R):
        m=int(M.middle[x,y])
        if m: paste(lib('SmTiles'),m-1,(x-x0)*48,(y-y0)*32)
for y in range(y0,y0+2*R+10):
    for x in range(x0,x0+2*R):
        if y>=M.height: continue
        f=int(M.front[x,y])&0x7fff
        if not f: continue
        a=int(M.area[x,y]); n='Objects' if a==0 else f'Objects{a+1}'
        L=lib(n)
        if L is None: continue
        fr=L.frame(f-1)
        if fr.empty: continue
        paste(L,f-1,(x-x0)*48,(y-y0+1)*32-fr.h)
img.save(sys.argv[6]); print('ok')
