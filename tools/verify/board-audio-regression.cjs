const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto');
const {BoardAudio}=require('../../webapp/renderer/board-audio.js');
const dir=path.resolve(__dirname,'../../webapp/renderer'),context={window:{}};
for(const file of ['board-catalog.js','board-sounds.js'])vm.runInNewContext(fs.readFileSync(path.join(dir,file),'utf8'),context);
const sounds=context.window.BoardSounds;
for(const b of context.window.BoardCatalog){const sound=sounds[b.id];assert(sound,`missing ${b.id}`);assert(sound.samples.length||sound.event==='intentionally_empty');for(const s of sound.samples)assert(fs.statSync(path.join(dir,s.file)).size>0);}
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
for(const [id,index]of [['ice',1],['packed_ice',2],['blue_ice',3]]){const s=sounds[id];assert.equal(s.pitch,1);assert.equal(s.volume,1);assert.equal(s.samples[0].file,`board-audio/${index}.mp3`);assert.equal(hash(path.join(dir,s.samples[0].file)),hash(path.resolve(__dirname,`../../../${index}.MP3`)));}
assert.equal(sounds.stone.event,'block.stone.place');assert.equal(sounds.oak_planks.event,'block.wood.place');assert.equal(sounds.copper_chest.event,'block.copper.place');assert.equal(sounds.chiseled_cinnabar.event,'block.cinnabar.place');
assert.equal(sounds.nether_portal.event,'block.stone.place');assert.equal(sounds.nether_portal.pitch,sounds.stone.pitch);
const created=[];class FakeAudio{constructor(file){this.file=file;this.listeners={};created.push(this);}addEventListener(e,fn){this.listeners[e]=fn;}play(){this.played=true;return Promise.resolve();}pause(){this.paused=true;}}
const player=new BoardAudio(sounds,{AudioClass:FakeAudio,random:()=>0});player.play('rail');assert(created[0].played);assert.equal(created[0].playbackRate,sounds.rail.pitch*sounds.rail.samples[0].pitch);assert.equal(created[0].preservesPitch,false);
player.play('blue_ice');assert.equal(created.at(-1).file,'board-audio/3.mp3');assert.equal(created.at(-1).playbackRate,1);assert.equal(player.play('missing'),false);
for(let i=0;i<20;i++)player.play('stone');assert(player.playing.size<=16);assert(created[0].paused);
console.log('PASS: all block audio mappings, original sample files, custom MP3 hashes, vanilla pitch/volume, playback and voice limit');
