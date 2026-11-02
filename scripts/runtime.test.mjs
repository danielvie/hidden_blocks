import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {immutableFile,immutableWeb} from './run-app.mjs';

test('new builds never replace an already staged runtime artifact',()=>{
 const dir=mkdtempSync(join(tmpdir(),'sysml-runtime-'));
 try{
  const file=join(dir,'app.jar'),cache=join(dir,'cache');
  writeFileSync(file,'first');const first=immutableFile(file,'jars',cache);
  assert.equal(immutableFile(file,'jars',cache),first);
  writeFileSync(file,'second');const second=immutableFile(file,'jars',cache);
  assert.notEqual(first,second);assert.equal(readFileSync(first,'utf8'),'first');assert.equal(readFileSync(second,'utf8'),'second');
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test('corrupt immutable artifacts fail explicitly rather than being overwritten',()=>{
 const dir=mkdtempSync(join(tmpdir(),'sysml-runtime-'));
 try{
  const file=join(dir,'app.jar'),cache=join(dir,'cache');writeFileSync(file,'source');
  const stored=immutableFile(file,'jars',cache);writeFileSync(stored,'corrupt');
  assert.throws(()=>immutableFile(file,'jars',cache),/Corrupt runtime cache/);
  assert.equal(readFileSync(stored,'utf8'),'corrupt');assert.equal(readFileSync(file,'utf8'),'source');
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test('web snapshots isolate a running version from later frontend builds',()=>{
 const dir=mkdtempSync(join(tmpdir(),'sysml-runtime-'));
 try{
  const web=join(dir,'dist'),cache=join(dir,'cache');mkdirSync(join(web,'assets'),{recursive:true});
  writeFileSync(join(web,'index.html'),'first');writeFileSync(join(web,'assets','app.js'),'code1');
  const first=immutableWeb(web,cache);writeFileSync(join(web,'index.html'),'second');
  const second=immutableWeb(web,cache);
  assert.notEqual(first,second);assert.equal(readFileSync(join(first,'index.html'),'utf8'),'first');
  assert.equal(readFileSync(join(second,'assets','app.js'),'utf8'),'code1');
 }finally{rmSync(dir,{recursive:true,force:true});}
});
