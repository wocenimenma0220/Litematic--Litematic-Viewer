"""Check readable palette icons and faithful side/top state projections."""
import json
from pathlib import Path
from PIL import Image

root=Path(__file__).resolve().parents[2]/'webapp/renderer'
catalog=json.loads((root/'board-catalog.js').read_text(encoding='utf8').split('window.BoardCatalog = ',1)[1].strip().rstrip(';'))
blocks={b['id']:b for b in catalog}
atlas=Image.open(root/'board-atlas.png').convert('RGBA')
def sprite(index):
    x,y=index%64*32,index//64*32
    return atlas.crop((x,y,x+32,y+32))
def dimensions(image):
    box=image.getbbox()
    assert box, 'visible geometry must not disappear when viewed edge-on'
    return box[2]-box[0],box[3]-box[1]
def variant(block,**states):
    return next(v for v in blocks[block]['visuals'] if all(v['when'].get(k)==value for k,value in states.items()))
banners=[]
oak=sprite(blocks['oak_planks']['visuals'][0]['icon']).tobytes()
for color in ['white','orange','magenta','light_blue','yellow','lime','pink','gray','light_gray','cyan','purple','blue','brown','green','red','black']:
    flag=sprite(variant(color+'_banner',rotation='0')['icon']);width,height=dimensions(flag)
    assert flag.tobytes()!=oak, f'{color} banner uses oak-plank particle texture'
    assert 10<=width<=18 and height>=28, f'{color}: palette banner must show cloth and stand'
    assert flag.getpixel((0,0))[3]==0, f'{color}: banner background must stay transparent'
    banners.append(flag.tobytes())
assert len(set(banners))==16, 'all banner palette dye colors must be distinct'
for color,channel in [('red',0),('blue',2),('green',1)]:
    pixel=sprite(variant(color+'_banner',rotation='0')['icon']).getpixel((15,15))
    assert pixel[channel]>max(pixel[i] for i in range(3) if i!=channel), f'wrong {color} banner dye'
for color in ['white','red','blue']:
    banner=blocks[color+'_banner']
    assert {v['when'].get('rotation') for v in banner['visuals']}=={str(i) for i in range(16)}, f'{color}: every banner rotation needs its own projection'
    front=variant(color+'_banner',rotation='0');edge=variant(color+'_banner',rotation='4')
    assert dimensions(sprite(front['sprite']))[0]>=10, f'{color}: rotation 0 must show broad cloth from the side'
    width,height=dimensions(sprite(edge['sprite']))
    assert width<=4 and height>=28, f'{color}: rotation 4 must show edge-on cloth and post from the side'
    width,height=dimensions(sprite(front['top']))
    assert width>=10 and height<=4, f'{color}: banner must be a narrow horizontal profile from above'
    width,height=dimensions(sprite(edge['top']))
    assert width<=4 and height>=10, f'{color}: banner top profile must rotate with the block'
for view in ['sprite','top','icon']:
    stone=sprite(blocks['stone']['visuals'][0][view])
    assert sum(stone.getchannel('A').histogram()[128:])>=1000, f'{view}: cube face must be a flat square'
    for rail_variant in blocks['rail']['visuals']:
        width,height=dimensions(sprite(rail_variant[view]))
        if view!='sprite':
            assert width>=24 and height>=24, f'{view}: rail icon/top face must stay readable {rail_variant["when"]}'
    chest=sprite(next(v for v in blocks['copper_chest']['visuals'] if v['when']=={'facing':'north','type':'single'})[view])
    box=chest.getbbox()
    assert box and box[2]-box[0]>=24 and box[3]-box[1]>=24, f'{view}: copper chest must be readable'
flat=variant('rail',shape='north_south')
width,height=dimensions(sprite(flat['sprite']))
assert width>=24 and height<=3, 'side-view flat rail must stay a thin horizontal profile'
crossing=sprite(variant('rail',shape='east_west')['sprite'])
assert dimensions(crossing)[1]<=3, 'east/west rail must keep its thin side profile'
columns=sum(any(crossing.getpixel((x,y))[3]>=128 for y in range(32)) for x in range(32))
assert columns>=30, 'edge-on rail must be a continuous line without alternating missing pixels'
ascending=variant('rail',shape='ascending_east')
slope=sprite(ascending['sprite']);width,height=dimensions(slope)
assert width>=24 and height>=24, 'side-view ascending east rail must retain its diagonal rise'
assert sum(slope.getchannel('A').histogram()[128:])<=width*height*.25, 'side-view ascending rail must be a thin slope'
for shape,sign in [('ascending_east',-1),('ascending_west',1)]:
    image=sprite(variant('rail',shape=shape)['sprite'])
    points=[(x,y) for y in range(32) for x in range(32) if image.getpixel((x,y))[3]>=128]
    mean_x=sum(x for x,y in points)/len(points);mean_y=sum(y for x,y in points)/len(points)
    covariance=sum((x-mean_x)*(y-mean_y) for x,y in points)
    assert covariance*sign>0, f'{shape}: slope must rise toward its named direction'
assert sprite(flat['icon']).tobytes()!=sprite(flat['sprite']).tobytes(), 'palette and actual rail projections must differ'
print('PASS: readable flat palette icons; edge-on/diagonal rail states and 16 real banner rotations in side/top views')
