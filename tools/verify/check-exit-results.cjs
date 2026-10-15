const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
for(const mode of ['on','off']){
 const out=path.join(__dirname,'large-nbt-output/ui-'+mode),runtime=path.join(out,'runtime'),file=path.join(out,'result.json'),result=JSON.parse(fs.readFileSync(file));assert.equal(result.pass,true);
 const scenes=fs.existsSync(path.join(runtime,'cache/scenes')),previews=fs.existsSync(path.join(runtime,'cache/previews'));
 assert.equal(scenes,mode==='off');assert.equal(previews,mode==='off');assert.equal(fs.readFileSync(path.join(runtime,'skins/local/keep'),'utf8'),'local skin');
 const settings=JSON.parse(fs.readFileSync(path.join(runtime,'settings/ui.json')));assert.equal(settings.clearCacheOnExit,mode==='on');
 result.exitVerified=true;result.cacheAfterExit={scenes,previews};fs.writeFileSync(file,JSON.stringify(result,null,2));
 console.log('PASS: '+mode+' exit setting, cache contents and preserved local skin/configuration');
}
