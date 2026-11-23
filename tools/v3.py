from pathlib import Path
from PIL import Image
from wemade import open_library
D=Path('../raw/m176/Resource/Data')
for n in ['hum','tiles','smtiles','objects','mon3','weapon','hair','items','magic','prguse','npc','dnitems','mmap']:
    L=open_library(D,n); c=0
    for i in range(0,len(L),max(1,len(L)//400)): L.frame(i)
    print(n,len(L),'16bit-skipped',getattr(L,'skipped_16bit',0))
L=open_library(D,'hum')
for i in [0,600,1200]:
    f=L.frame(i); print(i,f.w,f.h,f.x,f.y)
    Image.fromarray(L.rgba(i),'RGBA').save(f'/tmp/hum{i}.png')
