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
    stone=sprite(blocks['stone']['visuals'][0][view])
    assert sum(stone.getchannel('A').histogram()[128:])>=1000, f'{view}: cube face must be a flat square'
    for variant in blocks['rail']['visuals']:
        rail=sprite(variant[view]);box=rail.getbbox()
        assert box and box[2]-box[0]>=24 and box[3]-box[1]>=24, f'{view}: edge-on rail {variant["when"]}'
    chest=sprite(next(v for v in blocks['copper_chest']['visuals'] if v['when']=={'facing':'north','type':'single'})[view])
    box=chest.getbbox()
    assert box and box[2]-box[0]>=24 and box[3]-box[1]>=24, f'{view}: copper chest must be readable'
print('PASS: flat square block faces, full rail faces in all states/views, readable copper chest')
