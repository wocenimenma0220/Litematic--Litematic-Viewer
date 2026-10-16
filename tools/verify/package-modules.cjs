'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(process.argv[2]||path.join(__dirname,'../../dist/投影预览'),'webapp'),seen=new Set();
function visit(file){
 if(seen.has(file)||!file.endsWith('.js'))return;seen.add(file);
 for(const match of fs.readFileSync(file,'utf8').matchAll(/require\(['"](\.[^'"]+)['"]\)/g)){
  const target=path.resolve(path.dirname(file),match[1]),resolved=[target,target+'.js',target+'.json',path.join(target,'index.js')].find(candidate=>fs.existsSync(candidate)&&fs.statSync(candidate).isFile());
  assert.ok(resolved,'Missing packaged dependency '+match[1]+' required by '+file);visit(resolved);
 }
}
visit(path.join(root,'main.js'));visit(path.join(root,'preload.js'));
for(const match of fs.readFileSync(path.join(root,'renderer/index.html'),'utf8').matchAll(/<script src="([^"]+)"/g))assert.ok(fs.existsSync(path.join(root,'renderer',match[1])),'Missing renderer script '+match[1]);
assert.equal(require(path.join(root,'package.json')).version,require('../../webapp/package.json').version);
console.log('PASS: portable package contains all '+seen.size+' local modules and every renderer script');
