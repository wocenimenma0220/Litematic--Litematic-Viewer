"""Check that flat icons fill their face, and rails are not edge-on lines."""
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
for view in ['sprite','top','icon']:
    banners=[]
    oak=sprite(blocks['oak_planks']['visuals'][0][view]).tobytes()
    for color in ['white','orange','magenta','light_blue','yellow','lime','pink','gray','light_gray','cyan','purple','blue','brown','green','red','black']:
        flag=sprite(blocks[color+'_banner']['visuals'][0][view]);box=flag.getbbox()
        assert flag.tobytes()!=oak, f'{view}: {color} banner uses oak-plank particle texture'
        assert box and 10<=box[2]-box[0]<=18 and box[3]-box[1]>=28, f'{view}: banner must show cloth and stand'
        assert flag.getpixel((0,0))[3]==0, f'{view}: banner background must stay transparent'
        banners.append(flag.tobytes())
    assert len(set(banners))==16, f'{view}: all banner dye colors must be distinct'
    for color,channel in [('red',0),('blue',2),('green',1)]:
        pixel=sprite(blocks[color+'_banner']['visuals'][0][view]).getpixel((15,15))
        assert pixel[channel]>max(pixel[i] for i in range(3) if i!=channel), f'{view}: wrong {color} banner dye'
    stone=sprite(blocks['stone']['visuals'][0][view])
    assert sum(stone.getchannel('A').histogram()[128:])>=1000, f'{view}: cube face must be a flat square'
    for variant in blocks['rail']['visuals']:
        rail=sprite(variant[view]);box=rail.getbbox()
        assert box and box[2]-box[0]>=24 and box[3]-box[1]>=24, f'{view}: edge-on rail {variant["when"]}'
    chest=sprite(next(v for v in blocks['copper_chest']['visuals'] if v['when']=={'facing':'north','type':'single'})[view])
    box=chest.getbbox()
    assert box and box[2]-box[0]>=24 and box[3]-box[1]>=24, f'{view}: copper chest must be readable'
print('PASS: 16 distinct dyed banners without oak-plank fallback in all views; flat blocks, rails and copper chest')
