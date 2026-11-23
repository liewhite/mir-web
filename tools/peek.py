import sys
from pathlib import Path
from PIL import Image
from wemade import open_library
D=Path('../raw/m176/Resource/Data')
lib=open_library(D,sys.argv[1])
for i in map(int,sys.argv[2:]):
    f=lib.frame(i)
    print(i,f.w,f.h,f.x,f.y)
    if not f.empty: Image.fromarray(f.rgba,'RGBA').save(f'/tmp/peek_{sys.argv[1]}_{i}.png')
