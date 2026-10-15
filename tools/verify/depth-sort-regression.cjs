const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const root=path.resolve(__dirname,'../..');
const context=vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(root,'webapp/renderer/gpu-view.js'),'utf8')+'\nglobalThis.sortForTest=sortDepthOrder;',context);
let randomState=42;
const random=()=>{randomState^=randomState<<13;randomState^=randomState>>>17;randomState^=randomState<<5;return (randomState>>>0)/4294967296;};
const count=50000,depths=new Float64Array(count),order=new Uint32Array(count);
for(let i=0;i<count;i++)depths[i]=i%7===0?0:i%9===0?-0:i%11===0?3.14159:(random()-.5)*2048;
depths[0]=Infinity;depths[1]=-Infinity;depths[2]=Number.MIN_VALUE;depths[3]=-Number.MIN_VALUE;
const layer={quads:count,depths,order};
for(let sample=0;sample<5;sample++){
  const expected=Array.from({length:count},(_,i)=>i).sort((a,b)=>depths[b]-depths[a]||a-b);
  context.sortForTest(layer);
  if(layer.order.some((id,i)=>id!==expected[i]))throw new Error('64-bit depth order differs from comparator');
  for(let i=0;i<count;i++)depths[i]=-depths[i];
}
// Both paths start from the same previous ordering, as during a real camera turn.
const centers=Float64Array.from({length:count*3},()=>Math.floor(random()*10000)/100);
let totalOld=0,totalNew=0;
const previous=Array.from({length:count},(_,i)=>i);
for(let frame=0;frame<36;frame++){
  const angle=frame*.006,dir=[Math.sin(angle),.12,Math.cos(angle)];
  for(let i=0;i<count;i++)depths[i]=centers[i*3]*dir[0]+centers[i*3+1]*dir[1]+centers[i*3+2]*dir[2];
  let start=performance.now();previous.sort((a,b)=>depths[b]-depths[a]||a-b);const before=performance.now()-start;
  start=performance.now();context.sortForTest(layer);const after=performance.now()-start;
  if(layer.order.some((id,i)=>id!==previous[i]))throw new Error('camera-turn ordering changed');
  if(frame>=6){totalOld+=before;totalNew+=after;}
}
const report={pass:true,checks:'50k faces, ties, signed zero, subnormal values, camera reversal, exact comparator equivalence',faces:count,sampledFrames:30,comparisonMillis:totalOld/30,radixMillis:totalNew/30};
const output=path.join(root,'tools/verify/fly-output/depth-sort.json');fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
