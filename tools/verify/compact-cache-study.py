"""Independent LMESH6 reader, lossless 40-byte unit-face prototype and 16/32 cache-group study.

This is an experiment, not a production mesh encoder or a 32-cell mesher benchmark.
"""
from pathlib import Path
from collections import defaultdict, OrderedDict
import struct, zlib, json, time, math, sys

root=Path(__file__).resolve().parents[2]
result=json.loads((root/'tools/verify/cache-large-output/result.json').read_text(encoding='utf-8'))
mesh=Path(result['cold']['mesh']); output=root/'tools/verify/cache-large-output/compact-study.json'

def parse(f):
    h=f.read(64)
    assert h[:8]==b'LMESH6\0\0'
    count,offset,length,lods,quads=struct.unpack_from('>iqiiq',h,12)
    assert lods==4 and offset+length==mesh.stat().st_size and length<=128*1024*1024
    f.seek(offset); c=memoryview(f.read(length)); assert struct.unpack_from('>i',c)[0]==count
    at=4; sections=[]
    for i in range(count):
        p=struct.unpack_from('>iii',c,at); box=struct.unpack_from('>ffffff',c,at+12); at+=554
        levels=[]
        for lod in range(4):
            n=struct.unpack_from('>i',c,at)[0]; at+=4; pages=[]
            for j in range(n):
                page=struct.unpack_from('>qiiiiiI',c,at); at+=32; pages.append(page)
            levels.append(pages)
        sections.append((p,box,levels))
    assert at==length
    return sections,quads,length

def read_page(f,p):
    offset,size,n,layer,rawsize,codec,crc=p
    f.seek(offset); data=f.read(size); raw=zlib.decompress(data) if codec else data
    assert len(raw)==rawsize and zlib.crc32(raw)==crc
    return raw

def compact(raw,at,position):
    vertices=[struct.unpack_from('<fff',raw,at+i*20) for i in range(4)]
    lo=[min(p[a] for p in vertices) for a in range(3)]; hi=[max(p[a] for p in vertices) for a in range(3)]
    constant=[a for a in range(3) if hi[a]==lo[a]]
    if len(constant)!=1 or any(not math.isfinite(v) or v!=int(v) for v in lo):return None
    axis=constant[0]; a,b=(axis+1)%3,(axis+2)%3
    if hi[a]-lo[a]!=1 or hi[b]-lo[b]!=1:return None
    local=[int(lo[i])-position[i]*16 for i in range(3)]
    if any(v< -32768 or v>32767 for v in local):return None
    permutation=0; seen=0
    canonical=[(0,0),(0,1),(1,1),(1,0)]
    for v,p in enumerate(vertices):
        try:code=canonical.index((p[a]-lo[a],p[b]-lo[b]))
        except ValueError:return None
        seen|=1<<code;permutation|=code<<(v*2)
    if seen!=15:return None
    encoded=struct.pack('<hhhBB',*local,axis,permutation)+b''.join(raw[at+v*20+12:at+v*20+20] for v in range(4))
    assert len(encoded)==40
    # Decode through the stored record; compare all 80 bytes, including UVs and corner RGBA.
    x,y,z,axis,permutation=struct.unpack_from('<hhhBB',encoded); base=[x+position[0]*16,y+position[1]*16,z+position[2]*16];a,b=(axis+1)%3,(axis+2)%3
    decoded=bytearray()
    for v in range(4):
        p=base.copy();u,w=canonical[(permutation>>(v*2))&3];p[a]+=u;p[b]+=w
        decoded+=struct.pack('<fff',*p)+encoded[8+v*8:16+v*8]
    if decoded!=raw[at:at+80]:return None
    return encoded

def simulate(sections,unit,budget):
    groups=defaultdict(list)
    for i,(p,box,levels) in enumerate(sections):groups[tuple(v//(unit//16) for v in p)].append(i)
    weights={g:sum(sum(p[1] for p in sections[i][2][0]) for i in ids) for g,ids in groups.items()}
    points=[sections[int((len(sections)-1)*f)][1] for f in [.2,.8,.2]]
    lru=OrderedDict(); used=0;loads=hits=evictions=bytes_read=extra=0; movements=[]
    for box in points:
        eye=[(box[a]+box[a+3])/2 for a in range(3)]
        requested={i for i,(_,b,_) in enumerate(sections) if max(max(b[a]-eye[a],eye[a]-b[a+3],0) for a in range(3))<=96}
        wanted={tuple(v//(unit//16) for v in sections[i][0]) for i in requested}
        touched=set(j for g in wanted for j in groups[g]);extra+=len(touched-requested)
        for g in sorted(wanted):
            if g in lru:hits+=1;lru.move_to_end(g);continue
            loads+=1;bytes_read+=weights[g]
            if weights[g]>budget:continue
            while used+weights[g]>budget:
                old,_=lru.popitem(last=False);used-=weights[old];evictions+=1
            lru[g]=None;used+=weights[g]
        movements.append({'wantedSections':len(requested),'touchedSections':len(touched),'groups':len(wanted),'residentStoredBytes':used})
    return {'unit':unit,'budgetBytes':budget,'groups':len(groups),'largestGroupStoredBytes':max(weights.values()),'loads':loads,'hits':hits,'evictions':evictions,'storedBytesRead':bytes_read,'extraSections':extra,'moves':movements}

with mesh.open('rb') as f:
    sections,quads,catalog_bytes=parse(f);faces=sum(p[2] for _,_,levels in sections for p in levels[0] if p[3] in (0,1));stride=max(1,faces//50000)
    counter=sampled=eligible=0; start=time.perf_counter(); pages_verified=0; compression_pages=[]; compression_raw=0
    for si,(position,box,levels) in enumerate(sections):
        for page in levels[0]:
            if page[3] not in (0,1):continue
            raw=read_page(f,page);pages_verified+=1
            for qi in range((-counter)%stride,page[2],stride):
                sampled+=1
                if compact(raw,qi*80,position) is not None:eligible+=1
            counter+=page[2]
            if len(compression_pages)<96 and si%max(1,len(sections)//96)==0 and len(raw)>1000:
                compression_pages.append(raw);compression_raw+=len(raw)
    prototype_ms=(time.perf_counter()-start)*1000
    codecs=[]
    for level in [1,3,6]:
        encoded=[];start=time.perf_counter()
        for raw in compression_pages:encoded.append(zlib.compress(raw,level))
        encode_ms=(time.perf_counter()-start)*1000;start=time.perf_counter()
        for raw,data in zip(compression_pages,encoded):assert zlib.decompress(data)==raw
        codecs.append({'deflateLevel':level,'rawBytes':compression_raw,'storedBytes':sum(map(len,encoded)),'compressMillis':encode_ms,'decodeMillis':(time.perf_counter()-start)*1000})
    report={'pass':True,'mesh':str(mesh),'meshBytes':mesh.stat().st_size,'sections':len(sections),'fullDetailQuads':quads,'catalogBytes':catalog_bytes,'pagesVerified':pages_verified,'regularLayersQuads':faces,
      'prototype':{'recordBytes':40,'sampleStride':stride,'sampledQuads':sampled,'losslessEligible':eligible,'eligibleFraction':eligible/sampled,'sampleBytesBefore':sampled*80,'sampleBytesWithFallback':eligible*40+(sampled-eligible)*80,'elapsedMillis':prototype_ms,'deployed':False},
      'compressionSample':{'pages':len(compression_pages),'codecs':codecs,'runtime':'Python zlib; separate from production JDK timing'},
      'cacheGroupingSimulation':[simulate(sections,unit,budget*1048576) for budget in [32,256] for unit in [16,32]],
      'limits':['40-byte prototype is lossless only for eligible unit axis faces; other geometry retains 80 bytes.','16/32 results regroup existing 16-cell meshes; they do not measure 32-cell meshing throughput or a real camera frustum.','Sample eligibility is not a full-scene ratio. Compact encoding is not deployed.']}
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False))
