import struct,sys
from pathlib import Path
from wemade import WilLibrary
D=Path('../raw/th2001/data')
for name in ['Hum','Tiles','SmTiles','Objects','Mon3','Weapon','Hair','Items','Magic','Prguse']:
    L=WilLibrary(D/f'{name}.wil')
    ok4=ok1=bad=0
    for i in range(min(len(L)-1,3000)):
        a,b=L.offsets[i],L.offsets[i+1]
        if a<=0 or b<=0 or b<=a: continue
        w,h,x,y=struct.unpack_from('<hhhh',L.data,a)
        d=b-a-L.header_size
        if d==((w+3)&~3)*h: ok4+=1
        elif d==w*h: ok1+=1
        else: bad+=1
    print(name,len(L),'ver',L.version,'pad4',ok4,'nopad',ok1,'bad',bad)
