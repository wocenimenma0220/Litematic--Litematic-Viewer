"""Build the offline 2D palette from the bundled vanilla models and client sources.

Run with Python + Pillow. Generated files are shipped with the Electron UI.
"""
from functools import lru_cache
import json
import math
import re
import sys
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT.parents[1] / '26.3-client-src'
ASSETS = CLIENT / 'assets/minecraft'
JAVA = CLIENT / 'net/minecraft/world/level/block'
OUT = ROOT / 'webapp/renderer'
XK = ROOT.parents[1] / 'XK redstone display 26.3.0/assets/minecraft'
RESOURCE_ROOTS = [XK] + [ROOT / ('assets/builtin/'+pack+'/assets/minecraft') for pack in ['static-block-entities','block-entity-textures','fast-chest']] + [ASSETS]
DYE_COLORS = dict(zip(
    ['white','orange','magenta','light_blue','yellow','lime','pink','gray','light_gray','cyan','purple','blue','brown','green','red','black'],
    [16383998,16351261,13061821,3847130,16701501,8439583,15961002,4673362,10329495,1481884,8991416,3949738,8606770,6192150,11546150,1908001]))
@lru_cache(None)
def resource(relative):
    return next((root / relative for root in RESOURCE_ROOTS if (root / relative).is_file()), ASSETS / relative)
lang = json.loads((ASSETS / 'lang/en_us.json').read_text(encoding='utf8'))
lang.update(json.loads((ROOT / 'assets/builtin/block-entity-textures/assets/minecraft/lang/zh_cn.json').read_text(encoding='utf8')))
tabs = (CLIENT / 'net/minecraft/world/item/CreativeModeTabs.java').read_text(encoding='utf8')
blocks_java = (JAVA / 'Blocks.java').read_text(encoding='utf8')
classes = {p.stem: p.read_text(encoding='utf8') for p in JAVA.glob('*.java')}
declarations = dict(re.findall(r'public static final Block (\w+)\s*=(.*?)(?=public static final|\Z)', blocks_java, re.S))
categories = ['building_blocks', 'colored_blocks', 'natural_blocks', 'functional_blocks', 'redstone_blocks', 'op_blocks']
membership = {}
for cat in categories:
    match = re.search(r'\n\s+' + cat.upper() + r',\s*CreativeModeTab\.builder', tabs)
    if not match:
        continue
    end = tabs.find('Registry.register(', match.end())
    body = tabs[match.end():end if end >= 0 else len(tabs)]
    ids = re.findall(r'(?:Items|Blocks)\.([A-Z][A-Z_0-9]+)', body)
    for name in ids:
        membership.setdefault(name.lower(), []).append(cat)
    for family in re.findall(r'registerColoredItems\([^;]*Items\.([A-Z_]+)', body):
        suffix = {'DYED_TERRACOTTA':'terracotta', 'DYED_SHULKER_BOX':'shulker_box', 'DYED_CANDLE':'candle'}.get(family, family.lower())
        for color in ['white','orange','magenta','light_blue','yellow','lime','pink','gray','light_gray','cyan','purple','blue','brown','green','red','black']:
            membership.setdefault(color + '_' + suffix, []).append(cat)

def ancestry(name, seen=None):
    seen = set() if seen is None else seen
    if name in seen or name not in classes:
        return ''
    seen.add(name)
    text = classes[name]
    parent = re.search(r'class \w+ extends (\w+)', text)
    return text + (ancestry(parent[1], seen) if parent else '')

def source_for(name):
    text = declarations.get(name.upper(), '')
    types = re.findall(r'(\w+Block)::new|new (\w+Block)\(', text)
    result = ''.join(ancestry(a or b) for a, b in types)
    if 'registerLegacyStair' in text or name.endswith('_stairs'):
        result += ancestry('StairBlock')
    if not result:
        normalized=re.sub(r'^(?:waxed_)?(?:(?:exposed|weathered|oxidized)_)?','',name)
        exact=''.join(part.title() for part in normalized.split('_'))+'Block'
        result += ancestry(exact)
        if name.endswith('_banner'):
            result += ancestry('WallBannerBlock' if name.endswith('_wall_banner') else 'BannerBlock')
        for suffix, cls in [('_stairs','StairBlock'),('_slab','SlabBlock'),('_fence_gate','FenceGateBlock'),('_fence','FenceBlock'),('_wall','WallBlock'),('_trapdoor','TrapDoorBlock'),('_door','DoorBlock'),('_pressure_plate','PressurePlateBlock'),('_button','ButtonBlock'),('_hanging_sign','CeilingHangingSignBlock'),('_sign','StandingSignBlock'),('_leaves','LeavesBlock'),('_copper_bulb','CopperBulbBlock'),('_copper_grate','WaterloggedTransparentBlock'),('copper_golem_statue','CopperGolemStatueBlock')]:
            if name.endswith(suffix):
                result += ancestry(cls)
                break
    return result

# Recover properties omitted by model JSON (waterlogging, trigger flags, power, etc.)
# from the actual StateDefinition and its inherited property declarations.
PROPERTY_SOURCE = (JAVA / 'state/properties/BlockStateProperties.java').read_text(encoding='utf8')
FIELD = re.compile(r'(?:BooleanProperty|IntegerProperty|EnumProperty<[^>]+>)\s+(\w+)\s*=\s*(.*?);', re.S)
SHARED_FIELDS = dict(FIELD.findall(PROPERTY_SOURCE))
@lru_cache(None)
def enum_values(name):
    if name == 'Direction': return ['north','east','south','west','up','down']
    if name == 'Direction.Axis': return ['x','y','z']
    if '.' in name and name.split('.')[0] in classes:
        text=classes[name.split('.')[0]]
        match=re.search(r'\benum '+name.split('.')[-1]+r'\b[^\{]*\{',text)
        if not match: return []
        body=text[match.end():].split(';',1)[0]
        return re.findall(r'\b[A-Z][A-Z_0-9]*\s*\(\s*"([a-z_0-9]+)"',body)
    paths = list((CLIENT / 'net/minecraft').rglob(name.split('.')[-1]+'.java'))
    if not paths: return []
    text = paths[0].read_text(encoding='utf8')
    body = text.split('enum ',1)[-1].split('{',1)[-1].split(';',1)[0]
    values = re.findall(r'\b[A-Z][A-Z_0-9]*\s*\(\s*"([a-z_0-9]+)"', body)
    return values or [v.lower() for v in re.findall(r'(?:^|,)\s*([A-Z][A-Z_0-9]*)\s*(?:,|$)',body)]

@lru_cache(None)
def declared_properties(source):
    fields = {}
    for key,expression in FIELD.findall(source): fields.setdefault(key,expression)
    def resolve_field(key,seen=None):
        seen = set() if seen is None else seen
        if key in seen: return None
        seen.add(key)
        if '.' in key:
            owner,field=key.split('.',1)
            expression=dict(FIELD.findall(classes.get(owner,''))).get(field,'') if owner!='BlockStateProperties' else SHARED_FIELDS.get(field,'')
        else: expression = fields.get(key,SHARED_FIELDS.get(key,''))
        alias = re.fullmatch(r'(?:(\w+)\.)?(\w+)',expression.strip())
        if alias:
            return resolve_field((alias[1]+'.' if alias[1] else '')+alias[2],seen)
        m=re.search(r'(BooleanProperty|IntegerProperty|EnumProperty)\.create\(\s*"([a-z_0-9]+)"(.*?)\)\s*$',expression,re.S)
        if not m: return None
        kind,prop,args=m.groups()
        if kind=='BooleanProperty': values=['false','true']
        elif kind=='IntegerProperty':
            nums=re.findall(r'(?:^|,)\s*(\d+)',args)
            values=[str(i) for i in range(int(nums[0]),int(nums[1])+1)] if len(nums)>=2 else ([str(i) for i in range(16)] if prop=='rotation' else [])
        else:
            cls=re.search(r'([\w.]+)\.class',args)
            values=enum_values(cls[1]) if cls else []
            if cls and cls[1]=='Direction':
                if 'Plane.HORIZONTAL' in args: values=['north','east','south','west']
                elif '->' in args:
                    excluded=re.findall(r'!=\s*Direction\.([A-Z]+)',args);values=[v for v in values if v.upper() not in excluded]
                else:
                    explicit=re.findall(r'Direction\.([A-Z]+)\b',args)
                    if explicit: values=[v.lower() for v in explicit]
            if cls and cls[1]=='Direction.Axis':
                explicit=re.findall(r'Direction.Axis\.([XYZ])',args)
                if explicit: values=[v.lower() for v in explicit]
        return (prop,values) if values else None
    result={}
    for body in re.findall(r'void createBlockStateDefinition\([^)]*\)\s*\{(.*?)\n\s*\}',source,re.S):
        for args in re.findall(r'\.add\(([^()]*)\)',body):
            for key in args.split(','):
                value=resolve_field(key.strip().split('.')[-1])
                if value: result.setdefault(value[0],value[1])
    return result

model_cache = {}
def model(name):
    name = name.split(':')[-1]
    if name in model_cache:
        return model_cache[name]
    banner=re.fullmatch(r'block/board_(.+)_banner',name)
    if banner and banner[1] in DYE_COLORS:
        def box(start,end,texture,uvs):
            return {'from':start,'to':end,'faces':{side:{'texture':'#'+texture,'uv':[v/4 for v in uv]} for side,uv in uvs.items()}}
        result={'textures':{'cloth':'board/banner/'+banner[1],'stand':'minecraft:entity/banner/banner_base'},'elements':[
            box([7.5,.5,7.5],[8.5,15,8.5],'stand',{'north':[46,2,48,44],'south':[50,2,52,44],'west':[44,2,46,44],'east':[48,2,50,44],'up':[46,0,48,2],'down':[48,0,50,2]}),
            box([4.5,14.5,7.5],[11.5,15.5,8.5],'stand',{'north':[2,44,22,46],'south':[24,44,44,46],'west':[0,44,2,46],'east':[22,44,24,46],'up':[2,42,22,44],'down':[22,42,42,44]}),
            box([4.5,1,8.5],[11.5,14.5,9],'cloth',{'north':[22,1,42,41],'south':[1,1,21,41],'west':[0,1,1,41],'east':[21,1,22,41],'up':[1,0,21,1],'down':[21,0,41,1]})]}
        model_cache[name]=result
        return result
    copper=re.fullmatch(r'block/board_(copper(?:_exposed|_weathered|_oxidized)?)_chest_(single|left|right)',name)
    if copper:
        texture,kind=copper.groups();template='center' if kind=='single' else kind
        parent=model('block/litematicrender_ebe_template_chest_'+template)
        result={**parent,'textures':{**parent.get('textures',{}),'chest':'minecraft:entity/chest/'+texture+('' if kind=='single' else '_'+kind)}}
        model_cache[name]=result
        return result
    path = resource('models/' + name + '.json')
    if not path.exists():
        return {}
    data = json.loads(path.read_text())
    parent = model(data['parent']) if 'parent' in data else {}
    result = {**parent, **data, 'textures': {**parent.get('textures', {}), **data.get('textures', {})}}
    model_cache[name] = result
    return result

@lru_cache(None)
def load_texture(path):
    image = Image.open(path).convert('RGBA')
    return image.crop((0, 0, image.width, min(image.width, image.height)))

def resolve(tex, textures):
    for _ in range(20):
        if isinstance(tex, dict):
            tex = tex.get('sprite', tex.get('texture', ''))
        if not tex.startswith('#'):
            break
        tex = textures.get(tex[1:], '')
    if tex.startswith('board/banner/'):
        return banner_texture(tex.rsplit('/',1)[-1]).copy()
    path = resource('textures/' + tex.split(':')[-1] + '.png')
    if not tex or not path.is_file():
        return None
    return load_texture(path).copy()

sprite_images = []
sprite_cache = {}
@lru_cache(None)
def banner_texture(color):
    # Vanilla's block/banner model only declares oak-plank particles. Its visible
    # cloth is an entity texture with a dye layer, not that fallback block face.
    base = resolve('entity/banner/banner_base', {})
    overlay = resolve('entity/banner/base', {})
    if base is None or overlay is None:
        raise ValueError('Missing banner entity textures')
    overlay = overlay.resize(base.size, Image.Resampling.NEAREST)
    dye = DYE_COLORS[color]
    channels = overlay.split()
    overlay = Image.merge('RGBA', tuple(channels[i].point(
        lambda n, t=(dye >> (16-i*8)) & 255: n*t//255) for i in range(3)) + (channels[3],))
    return Image.alpha_composite(base, overlay)

def render_banner(color):
    base = resolve('entity/banner/banner_base', {})
    cloth = banner_texture(color)
    def crop(texture, rect, size):
        return texture.crop(tuple(n*texture.width/64 for n in rect)).resize(size, Image.Resampling.NEAREST)
    image = Image.new('RGBA', (32,32))
    image.alpha_composite(crop(base, (46,2,48,44), (2,28)), (15,3))
    image.alpha_composite(crop(base, (2,44,22,46), (14,2)), (9,1))
    image.alpha_composite(crop(cloth, (1,1,21,41), (14,27)), (9,3))
    return image

def rotate_element(p,rotation):
    p=list(p);origin=rotation.get('origin',[8,8,8]);axis=rotation['axis'];angle=math.radians(rotation['angle'])
    i,j={'x':(1,2),'y':(2,0),'z':(0,1)}[axis];a,b=p[i]-origin[i],p[j]-origin[j]
    scale=1/math.cos(angle) if rotation.get('rescale') else 1
    p[i]=origin[i]+(a*math.cos(angle)-b*math.sin(angle))*scale
    p[j]=origin[j]+(a*math.sin(angle)+b*math.cos(angle))*scale
    return p
def render(variant, view='side', offset=(0,0,0)):
    if isinstance(variant, list):
        variant = variant[0]
    key = view + json.dumps(variant, sort_keys=True) + str(offset)
    if key in sprite_cache:
        return sprite_cache[key]
    banner = re.fullmatch(r'block/board_(.+)_banner', variant.get('model', ''))
    if banner and banner[1] in DYE_COLORS and view=='icon':
        index = len(sprite_images)
        # The palette always shows a broad face; placed/state views use geometry.
        sprite_images.append(render_banner(banner[1]))
        sprite_cache[key] = index
        return index
    data = model(variant.get('model', ''))
    image = Image.new('RGBA', (32, 32))
    faces = []
    rx, ry = [math.radians(-variant.get(a, 0)) for a in ['x', 'y']]
    projection_axis='up' if view=='top' or view=='icon' and 'rail' in variant.get('model','') else 'north'
    def project(p):
        x,y,z=p
        if projection_axis=='up': return x*2,z*2,-y
        if projection_axis=='east': return (16-z)*2,(16-y)*2,-x
        return x*2,(16-y)*2,z
    def transform(p, rotation=None):
        if rotation and 'axis' not in rotation:
            for axis in ['x','y','z']:
                if rotation.get(axis):
                    # Recent models may store multiple element rotation axes.
                    p=rotate_element(p,{**rotation,'axis':axis,'angle':rotation[axis]})
            rotation=None
        if rotation:
            p=rotate_element(p,rotation)
        x,y,z = [v - 8 for v in p]
        y,z = y*math.cos(rx)-z*math.sin(rx), y*math.sin(rx)+z*math.cos(rx)
        x,z = x*math.cos(ry)+z*math.sin(ry), -x*math.sin(ry)+z*math.cos(ry)
        return x+8+offset[0],y+8+offset[1],z+8+offset[2]
    quads=[]
    areas={'north':0,'up':0,'east':0}
    element_areas={}
    for element_index,element in enumerate(data.get('elements', [])):
        element_areas[element_index]={'north':0,'up':0,'east':0}
        a,b,c = element['from']; d,e,f = element['to']
        vertices = {'north':[(a,e,c),(d,e,c),(d,b,c),(a,b,c)], 'south':[(d,e,f),(a,e,f),(a,b,f),(d,b,f)], 'west':[(a,e,f),(a,e,c),(a,b,c),(a,b,f)], 'east':[(d,e,c),(d,e,f),(d,b,f),(d,b,c)], 'up':[(a,e,c),(d,e,c),(d,e,f),(a,e,f)], 'down':[(a,b,f),(d,b,f),(d,b,c),(a,b,c)]}
        for direction,face in element.get('faces', {}).items():
            points = [transform(p,element.get('rotation')) for p in vertices[direction]]
            quads.append((points,face,element_index))
            u=[points[1][i]-points[0][i] for i in range(3)];v=[points[3][i]-points[0][i] for i in range(3)]
            normal=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]]
            for axis,index in [('north',2),('up',1),('east',0)]:
                areas[axis]+=abs(normal[index])
                element_areas[element_index][axis]+=abs(normal[index])
    # Only palette icons choose the most readable face. State/placed previews
    # keep the board's actual viewing axis, including thin edge-on surfaces.
    best=max(areas,key=areas.get)
    if view=='icon' and areas[projection_axis]<areas[best]*.9: projection_axis=best
    for points,face,element_index in quads:
            projected_points=[project(p) for p in points]
            xs,ys = [p[0] for p in projected_points],[p[1] for p in projected_points]
            width,height = round(max(xs)-min(xs)), round(max(ys)-min(ys))
            if width < 1 and height < 1: continue
            tex = resolve(face['texture'], data.get('textures', {}))
            if tex is None:
                continue
            uv = face.get('uv', [0,0,16,16]); u0,v0,u1,v1 = uv
            tile = tex.crop((min(u0,u1)*tex.width/16,min(v0,v1)*tex.height/16,max(u0,u1)*tex.width/16,max(v0,v1)*tex.height/16))
            if not tile.width or not tile.height:
                continue
            if u1 < u0: tile = tile.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            if v1 < v0: tile = tile.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
            if face.get('rotation'): tile = tile.rotate(-face['rotation'], expand=True)
            if 'tintindex' in face:
                tint = (175,25,15) if 'redstone' in variant.get('model','') else (110,168,65) if 'water' not in variant.get('model','') else (63,118,228)
                channels = tile.split()
                tile = Image.merge('RGBA', tuple(channels[i].point(lambda n, t=tint[i]: n*t//255) for i in range(3))+(channels[3],))
            ux,uy=xs[1]-xs[0],ys[1]-ys[0];vx,vy=xs[3]-xs[0],ys[3]-ys[0];det=ux*vy-uy*vx
            if abs(det)<.01:
                if view!='icon' and element_areas[element_index][projection_axis]<.01:
                    # A zero-thickness rail/pane is still visible as a pixel-wide
                    # edge. Do not replace its valid geometry with a full texture.
                    points2=list(zip(xs,ys))
                    start,end=max(((a,b) for i,a in enumerate(points2) for b in points2[i+1:]),key=lambda p:math.dist(*p))
                    length=math.dist(start,end)
                    if length>=.5:
                        steps=max(2,math.ceil(length)+1)
                        horizontal=ux*ux+uy*uy>=vx*vx+vy*vy
                        strip=tile.resize((steps,tile.height) if horizontal else (tile.width,steps),Image.Resampling.NEAREST)
                        pixels=strip.load();profile=[]
                        for i in range(steps):
                            pixel=(0,0,0,0)
                            for j in range(strip.height if horizontal else strip.width):
                                sample=pixels[i,j] if horizontal else pixels[j,i]
                                if sample[3]>pixel[3]:pixel=sample
                                if pixel[3]==255:break
                            profile.append(pixel)
                        direction=(ux,uy) if horizontal else (vx,vy)
                        reverse=direction[0]*(end[0]-start[0])+direction[1]*(end[1]-start[1])<0
                        projected=Image.new('RGBA',(32,32));draw=ImageDraw.Draw(projected)
                        for step in range(steps):
                            t=step/(steps-1);sample=steps-1-step if reverse else step
                            pixel=profile[sample]
                            point=tuple(a+(b-a)*t for a,b in zip(start,end))
                            if pixel[3] and all(-.01<=n<=32.01 for n in point):
                                draw.point(tuple(max(0,min(31,math.floor(n))) for n in point),fill=pixel)
                        faces.append((sum(p[2] for p in projected_points)/4,projected))
                continue
            a1,b1=tile.width*vy/det,-tile.width*vx/det
            d1,e1=-tile.height*uy/det,tile.height*ux/det
            projected=tile.transform((32,32),Image.Transform.AFFINE,(a1,b1,-a1*xs[0]-b1*ys[0],d1,e1,-d1*xs[0]-e1*ys[0]),Image.Resampling.NEAREST)
            mask=Image.new('L',(32,32));ImageDraw.Draw(mask).polygon(list(zip(xs,ys)),fill=255)
            projected.putalpha(Image.composite(projected.getchannel('A'),Image.new('L',(32,32)),mask))
            faces.append((sum(p[2] for p in projected_points)/4,projected))
    for _, tile in sorted(faces, key=lambda q: -q[0]):
        image.alpha_composite(tile)
    if image.getbbox() is None and not quads:
        textures = data.get('textures', {})
        for name in [data.get('textures',{}).get(k,'') for k in ['front','side','all','particle']] + list(textures.values()):
            tex = resolve(name, textures)
            if tex:
                image = tex.resize((32,32), Image.Resampling.NEAREST)
                break
    index = len(sprite_images)
    sprite_images.append(image)
    sprite_cache[key] = index
    return index

def generate_piston_parts():
    # Keep non-inventory parts out of the palette. Render the rod's overlap into
    # the base as well: a vanilla long head extends four pixels into that cell.
    sprite_cache.clear()
    sprite_images.clear()
    data=json.loads(resource('blockstates/piston_head.json').read_text())
    directions={'north':(0,0,-16),'south':(0,0,16),'east':(16,0,0),'west':(-16,0,0),'up':(0,16,0),'down':(0,-16,0)}
    visuals=[]
    for state,variant in data['variants'].items():
        states=dict(pair.split('=') for pair in state.split(','))
        if states['short']!='false':continue
        visuals.append({'when':states,'sprite':render(variant),'top':render(variant,'top'),
                        'overlap':{'sprite':render(variant,offset=directions[states['facing']]),'top':render(variant,'top',directions[states['facing']])}})
    atlas=Image.new('RGBA',(64*32,math.ceil(len(sprite_images)/64)*32))
    for index,image in enumerate(sprite_images):atlas.paste(image,(index%64*32,index//64*32))
    atlas.save(OUT/'board-piston-parts.png')
    (OUT/'board-piston-parts.js').write_text('/* Generated by tools/generate-board-catalog.py --piston-parts. */\nwindow.BoardPistonParts = '+json.dumps({'visuals':visuals},separators=(',',':'))+';\n',encoding='utf8')
    print(f'{len(visuals)} piston head states, {len(sprite_images)} sprites')

if '--piston-parts' in sys.argv:
    generate_piston_parts()
    sys.exit()

catalog = []
for path in sorted((ASSETS / 'blockstates').glob('*.json')):
    name = path.stem
    item_alias = {'redstone_wire':'redstone','tripwire':'string'}.get(name,name)
    if not (ASSETS / 'items' / (item_alias + '.json')).exists() and name not in ['water','lava','nether_portal']:
        continue
    if name in ['air','cave_air','void_air']:
        continue
    data = json.loads(resource('blockstates/' + path.name).read_text())
    if name.removesuffix('_banner') in DYE_COLORS and name.endswith('_banner'):
        data = {'variants': {f'rotation={rotation}': {'model': 'block/board_' + name,'y':rotation*22.5} for rotation in range(16)}}
    if name.endswith('copper_chest'):
        texture='copper'+next(('_'+stage for stage in ['exposed','weathered','oxidized'] if stage in name),'')
        data={'variants':{f'facing={facing},type={kind}':{'model':f'block/board_{texture}_chest_{kind}','y':angle} for facing,angle in [('north',0),('east',90),('south',180),('west',270)] for kind in ['single','left','right']}}
    variants = data.get('variants', {})
    props = {}
    for state in variants:
        for pair in filter(None, state.split(',')):
            k,v = pair.split('='); props.setdefault(k,set()).add(v)
    for part in data.get('multipart', []):
        def walk(node):
            if isinstance(node,dict):
                for k,v in node.items():
                    if isinstance(v,str): props.setdefault(k,set()).update(v.split('|'))
                    else: walk(v)
            elif isinstance(node,list):
                for item in node: walk(item)
        walk(part.get('when',{}))
    source = source_for(name)
    for prop,values in declared_properties(source).items():
        if values==['false','true']: props.setdefault(prop,set()).update(values)
        else: props.setdefault(prop,set(values))
    wet = 'waterlogged' in props
    activation = None
    if name != 'observer':
        for prop in ['powered','triggered','extended','enabled','lit','open']:
            if prop in props or re.search(r'\b'+prop.upper()+r'\b', source) and prop in ['powered','triggered','extended','enabled']:
                activation = prop; break
    if name == 'redstone_wire': activation = 'power'
    if 'open' in props: activation = 'open'
    if name.endswith('copper_bulb'): activation = 'lit'
    if name in ['sculk_sensor','calibrated_sculk_sensor']: activation = 'sculk_sensor_phase'
    facing = sorted(props.get('facing', []))
    if not facing and 'axis' in props:
        facing = sorted(props['axis'])
    visuals = []
    for state, variant in variants.items():
        values = dict(pair.split('=') for pair in state.split(',') if pair)
        visuals.append({'when': values, 'sprite':render(variant), 'top':render(variant,'top'),'icon':render(variant,'icon')})
    multipart=[]
    for part in data.get('multipart',[]):
        variant=part['apply']
        multipart.append({'when':part.get('when',{}),'sprite':render(variant),'top':render(variant,'top'),'icon':render(variant,'icon')})
    if not visuals:
        candidates = list(variants.values()) or [p['apply'] for p in data.get('multipart',[])]
        if candidates: visuals = [{'when':{},'sprite':render(candidates[0]),'top':render(candidates[0],'top'),'icon':render(candidates[0],'icon')}]
    if not visuals or not sprite_images[visuals[0]['icon']].getbbox():
        # Flat item sprites cover signs/plants; custom entity-only models use a visible fallback.
        fallback = resolve('item/'+item_alias,{}) or resolve('block/'+name,{})
        if name == 'redstone_wire': fallback = resolve('block/redstone_dust_dot',{})
        if fallback is None:
            fallback = Image.new('RGBA',(32,32),(45,55,72,255))
            draw = ImageDraw.Draw(fallback)
            draw.rectangle((3,3,28,28),outline=(100,145,200,255),width=2)
            draw.text((10,8),'?',fill='white')
        index=len(sprite_images);sprite_images.append(fallback.resize((32,32),Image.Resampling.NEAREST))
        visuals=[{'when':{},'sprite':index,'top':index,'icon':index}]
    cats = membership.get(item_alias, [])
    if not cats:
        cats = ['natural_blocks' if re.search(r'ore|leaves|sapling|flower|grass|mushroom|coral|vine|roots|bush', name) else 'building_blocks']
    if name in ['water','lava','nether_portal']: cats = ['special']
    # Water can replace these non-solid vanilla blocks; waterlogged blocks keep their geometry.
    replaceable = bool(re.search(r'flower|sapling|torch|redstone_wire|rail|carpet|button|pressure_plate|short_grass|tall_grass|fern|dead_bush|tripwire|repeater|comparator|lever',name))
    properties={k:sorted(v,key=lambda x:(not x.isdigit(),int(x) if x.isdigit() else x)) for k,v in props.items()}
    preferred=['false','north','y','bottom','lower','straight','single','head','wall','none','inactive','0','1']
    defaults={k:next((v for v in preferred if v in values),values[0]) for k,values in properties.items()}
    if 'shape' in properties and 'north_south' in properties['shape']: defaults['shape']='north_south'
    if 'enabled' in properties: defaults['enabled']='true'
    catalog.append({'id':name,'name':lang.get('block.minecraft.'+name,name), 'categories':list(dict.fromkeys(cats)), 'properties':properties,'defaults':defaults,'facing':facing,'axis':'axis' in props and 'facing' not in props,'waterlogged':wet,'activation':activation,'replaceable':replaceable,'visuals':visuals,'multipart':multipart})

# Fluid / portal sprites have no model geometry.
for block in catalog:
    if block['id'] in ['water','lava','nether_portal']:
        texname = {'water':'water_still','lava':'lava_still','nether_portal':'nether_portal'}[block['id']]
        tex = resolve('block/'+texname,{})
        if tex:
            if block['id']=='water':
                overlay=Image.new('RGBA',tex.size,(63,118,228,255)); overlay.putalpha(tex.getchannel('A')); tex=Image.blend(tex,overlay,.65)
            index=len(sprite_images);sprite_images.append(tex.resize((32,32),Image.Resampling.NEAREST));block['visuals']=[{'when':{},'sprite':index,'top':index,'icon':index}];block['multipart']=[]
columns = 64
atlas = Image.new('RGBA',(columns*32,math.ceil(len(sprite_images)/columns)*32))
for index,image in enumerate(sprite_images): atlas.paste(image,(index%columns*32,index//columns*32))
atlas.save(OUT / 'board-atlas.png')
(OUT / 'board-catalog.js').write_text('/* Generated by tools/generate-board-catalog.py. */\nwindow.BoardCatalogInfo = {pack:"xkredstone_display",views:["side","top"]};\nwindow.BoardCatalog = '+json.dumps(catalog,ensure_ascii=False,separators=(',',':'))+';\n',encoding='utf8')
print(f'{len(catalog)} blocks, {len(sprite_images)} sprites')
generate_piston_parts()
