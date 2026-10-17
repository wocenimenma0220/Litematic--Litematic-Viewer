"""Bundle original Minecraft placement samples using Blocks/SoundType/SoundEvents.

Reads the installed game's extracted assets or content-addressed asset cache;
copies only samples used by the board. No network downloads are necessary.
"""
import argparse
import json
import re
import shutil
from functools import lru_cache
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
CLIENT=ROOT.parents[1]/'26.3-client-src/net/minecraft'
OUT=ROOT/'webapp/renderer'
parser=argparse.ArgumentParser()
parser.add_argument('--assets',type=Path,default=Path('D:/minecraft-assets-26.1.2/minecraft-assets-26.1.2/assets/minecraft'))
parser.add_argument('--cache',type=Path,default=Path('D:/.minecraft/versions/1.21.8/.minecraft/assets'))
parser.add_argument('--custom',type=Path,default=ROOT.parent)
args=parser.parse_args()
indices=[]
for path in sorted((args.cache/'indexes').glob('*.json'),key=lambda p:p.stat().st_size,reverse=True):
    indices.append(json.loads(path.read_text())['objects'])

def asset(name):
    for index in indices:
        entry=index.get('minecraft/'+name)
        if entry:
            path=args.cache/'objects'/entry['hash'][:2]/entry['hash']
            if path.is_file(): return path
    path=args.assets/name
    if path.is_file(): return path
    return None

sound_json=asset('sounds.json')
if not sound_json: raise SystemExit('Original Minecraft sounds.json not found')
events=json.loads(sound_json.read_text(encoding='utf8'))
# The richest local index can contain newer events than the extracted asset folder.
for index in reversed(indices):
    entry=index.get('minecraft/sounds.json')
    if entry:
        path=args.cache/'objects'/entry['hash'][:2]/entry['hash']
        if path.is_file(): events.update(json.loads(path.read_text(encoding='utf8')))
events.update(json.loads(sound_json.read_text(encoding='utf8')))
event_java=(CLIENT/'sounds/SoundEvents.java').read_text(encoding='utf8')
event_names=dict(re.findall(r'\b([A-Z][A-Z_0-9]+)\s*=\s*register\w*\(\s*"([^"]+)"',event_java))
types={}
for name,body in re.findall(r'public static final SoundType (\w+) = new SoundType\((.*?)\);',(CLIENT/'world/level/block/SoundType.java').read_text(encoding='utf8'),re.S):
    refs=re.findall(r'SoundEvents\.(\w+)',body)
    numbers=re.match(r'\s*([\d.]+)F?,\s*([\d.]+)F?,',body)
    if len(refs)>=3 and numbers:
        types[name]={'event':event_names.get(refs[2],''),'volume':(float(numbers[1])+1)/2,'pitch':float(numbers[2])*.8}
blocks_java=(CLIENT/'world/level/block/Blocks.java').read_text(encoding='utf8')
declarations=dict(re.findall(r'public static final (?:Block|\w+Collection<[^>]+>) (\w+)\s*=(.*?)(?=public static final|\Z)',blocks_java,re.S))
sets=dict((name,re.search(r'SoundType\.(\w+)',body)[1]) for name,body in re.findall(r'public static final BlockSetType (\w+) = (.*?)(?=public static final|\Z)',(CLIENT/'world/level/block/state/properties/BlockSetType.java').read_text(encoding='utf8'),re.S) if re.search(r'SoundType\.(\w+)',body))
colors='white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black'.split()

@lru_cache(None)
def sound_type(name,trail=()):
    if name in trail: return 'STONE'
    trail=(*trail,name)
    body=declarations.get(name.upper(),'')
    direct=re.search(r'\.sound\(SoundType\.(\w+)\)',body)
    if direct: return direct[1]
    copied=re.search(r'of(?:Legacy|Full)Copy\(\s*(\w+)\s*\)',body)
    if copied: return sound_type(copied[1].lower(),trail)
    stair=re.search(r'register(?:LegacyStair|Stair|Slab|Wall)\([^,]+,\s*(\w+)',body)
    if stair: return sound_type(stair[1].lower(),trail)
    block_set=re.search(r'BlockSetType\.(\w+)',body)
    if block_set and block_set[1] in sets: return sets[block_set[1]]
    base=re.sub(r'^(?:waxed_)?(?:(?:exposed|weathered|oxidized)_)?','',name)
    if base!=name and base.upper() in declarations: return sound_type(base,trail)
    for color in sorted(colors,key=len,reverse=True):
        if name.startswith(color+'_'):
            family=name[len(color)+1:]
            if family.upper() in declarations: return sound_type(family,trail)
            if ('DYED_'+family.upper()) in declarations: return sound_type('dyed_'+family,trail)
    wood=next((w for w in ['dark_oak','pale_oak','mangrove','cherry','bamboo','crimson','warped','spruce','jungle','acacia','birch','poplar','oak'] if name.startswith(w+'_') or name.startswith('stripped_'+w+'_')),None)
    if wood:
        kind={'cherry':'CHERRY_WOOD','bamboo':'BAMBOO_WOOD','crimson':'NETHER_WOOD','warped':'NETHER_WOOD'}.get(wood,'WOOD')
        if name.endswith('hanging_sign'): return {'CHERRY_WOOD':'CHERRY_WOOD_HANGING_SIGN','BAMBOO_WOOD':'BAMBOO_WOOD_HANGING_SIGN','NETHER_WOOD':'NETHER_WOOD_HANGING_SIGN'}.get(kind,'HANGING_SIGN')
        if name.endswith(('planks','log','wood','stairs','slab','fence','fence_gate','door','trapdoor','button','pressure_plate','sign')): return kind
    if 'copper' in name:
        return 'COPPER_GRATE' if name.endswith('grate') else 'COPPER_BULB' if name.endswith('bulb') else 'COPPER_GOLEM_STATUE' if name.endswith('statue') else 'COPPER'
    if name.endswith('wool') or 'wool_' in name: return 'WOOL'
    if name.endswith('carpet'): return 'WOOL'
    if name.endswith('concrete_powder'): return 'SAND'
    if 'glass' in name or name.endswith('_ice') or name=='ice': return 'GLASS'
    if name.endswith('candle') or name.endswith('candle_cake'): return 'CANDLE'
    if name.endswith('leaves'): return 'GRASS'
    # Generic helpers used by the vanilla registry supply these sound types.
    if 'flowerPotProperties(' in body: return 'STONE'
    if 'candleProperties(' in body: return 'CANDLE'
    if 'logProperties(' in body: return re.search(r'SoundType\.(\w+)',body)[1] if re.search(r'SoundType\.(\w+)',body) else 'WOOD'
    return 'STONE'

copied={}
def samples(event,stack=()):
    event=event.removeprefix('minecraft:')
    if event in stack: return []
    result=[]
    for item in events.get(event,{}).get('sounds',[]):
        entry={'name':item} if isinstance(item,str) else item
        if entry.get('type')=='event':
            child=samples(entry['name'],(*stack,event))
            for value in child: result.append({**value,'volume':value['volume']*entry.get('volume',1),'pitch':value['pitch']*entry.get('pitch',1),'weight':value['weight']*entry.get('weight',1)})
            continue
        relative='sounds/'+entry['name'].removeprefix('minecraft:')+'.ogg';source=asset(relative)
        if not source: continue
        target=OUT/'board-audio'/relative
        if relative not in copied:
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target);copied[relative]=True
        result.append({'file':'board-audio/'+relative,'volume':entry.get('volume',1),'pitch':entry.get('pitch',1),'weight':entry.get('weight',1)})
    return result

text=(OUT/'board-catalog.js').read_text(encoding='utf8')
catalog=json.loads(text.split('window.BoardCatalog = ',1)[1].rstrip().rstrip(';'))
manifest={}
for block in catalog:
    kind=sound_type(block['id']);definition=types.get(kind,types['STONE']);event=definition['event']
    if block['id'] in ['water','lava']: event='item.bucket.empty'+('_lava' if block['id']=='lava' else '')
    if block['id']=='nether_portal': definition=types['STONE'];kind='STONE';event=definition['event']
    options=samples(event)
    if not options and event!='intentionally_empty': raise SystemExit(f'Missing original placement audio: {block["id"]} / {kind} / {event}')
    manifest[block['id']]={**definition,'event':event,'type':kind,'samples':options}
for index,name in enumerate(['ice','packed_ice','blue_ice'],1):
    source=next((p for p in args.custom.iterdir() if p.name.lower()==f'{index}.mp3'),None)
    if source is None: raise SystemExit(f'Missing custom audio: {args.custom}/{index}.mp3')
    target=OUT/'board-audio'/f'{index}.mp3';target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    manifest[name]={'event':'custom:'+str(index),'type':'custom','volume':1,'pitch':1,'samples':[{'file':f'board-audio/{index}.mp3','volume':1,'pitch':1,'weight':1}]}
(OUT/'board-sounds.js').write_text('/* Generated from original Minecraft assets. */\nwindow.BoardSounds = '+json.dumps(manifest,ensure_ascii=False,separators=(',',':'))+';\n',encoding='utf8')
print(f'{len(manifest)} blocks, {len(copied)} original audio samples, 3 custom MP3 files')
