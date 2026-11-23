import struct
from pathlib import Path
from wemade import WilLibrary
L=WilLibrary(Path('../raw/th2001/data/SmTiles.wil'))
c=0
for i in range(len(L)-1):
    a,b=L.offsets[i],L.offsets[i+1]
    w,h,x,y=struct.unpack_from('<hhhh',L.data,a)
    d=b-a-8
    if d!=((w+3)&~3)*h:
        print(i,w,h,x,y,d,w*h); c+=1
        if c>8: break
