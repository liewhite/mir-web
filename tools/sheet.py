import sys
from pathlib import Path
from PIL import Image, ImageDraw
from wemade import open_library
D=Path('../raw/m176/Resource/Data')
lib=open_library(D,sys.argv[1]); out=sys.argv[2]
idxs=[]
for a in sys.argv[3:]:
    if '-' in a: s,e=map(int,a.split('-')); idxs+=range(s,e+1)
    else: idxs.append(int(a))
cell=110; cols=10
rows=(len(idxs)+cols-1)//cols
c=Image.new('RGBA',(cols*cell,rows*(cell+12)),(40,40,40,255)); d=ImageDraw.Draw(c)
for k,i in enumerate(idxs):
    f=lib.frame(i); x=(k%cols)*cell; y=(k//cols)*(cell+12)
    d.text((x+2,y),f'{i} {f.w}x{f.h} {f.x},{f.y}',fill=(255,255,0))
    if not f.empty:
        im=Image.fromarray(f.rgba,'RGBA'); im.thumbnail((cell-4,cell-4)); c.paste(im,(x+2,y+12),im)
c.save(out)
