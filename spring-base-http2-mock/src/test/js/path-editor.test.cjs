const {test}=require('node:test');
const assert=require('node:assert/strict');
const {newPath,mergeSelections,parseNumbers,parseHeaders,formatHeaders,validatePath,upsertPath,combinationCount}=require('../../main/resources/caller-ui/path-model.js');

test('adds an isolated path and edits the same id without changing other scenario settings',()=>{
  const first=newPath('first'),scenario={id:'scenario',version:7,title:'test',workerHooks:{pre:'keep'},paths:[first]};
  const next=newPath('second');next.name='new path';
  const added=upsertPath(scenario,null,next);
  assert.equal(scenario.paths.length,1);assert.equal(added.paths.length,2);assert.equal(added.version,7);assert.equal(added.workerHooks.pre,'keep');
  const draft=structuredClone(added.paths[1]);draft.name='updated';draft.headers={'X-Test':['a']};
  const updated=upsertPath(added,1,draft);
  assert.equal(updated.paths.length,2);assert.equal(updated.paths[1].id,'second');assert.equal(updated.paths[1].name,'updated');assert.equal(added.paths[1].name,'new path');
  updated.paths[1].headers['X-Test'].push('b');assert.deepEqual(draft.headers['X-Test'],['a']);
});

test('selection edits preserve original order and duplicate combinations',()=>{
  assert.deepEqual(mergeSelections(['GZIP','IDENTITY','GZIP'],['IDENTITY','GZIP','BR']),['GZIP','IDENTITY','GZIP','BR']);
  const p=newPath('p');p.payload.contentLength=parseNumbers('64, 128, 64',true);p.payload.chunked=[false,true];
  assert.equal(combinationCount(p),6);assert.deepEqual(p.payload.contentLength,[64,128,64]);
  assert.throws(()=>parseNumbers('1.2, 4',true));assert.throws(()=>parseNumbers('',true));assert.throws(()=>parseNumbers('0',false));
  assert.throws(()=>parseNumbers('64,',true));
});

test('round-trips repeated headers and refuses invalid or engine-owned headers',()=>{
  const headers=parseHeaders('X-Test: first\nx-test: second\nX-Empty:');
  assert.deepEqual(headers,{'X-Test':['first','second'],'X-Empty':['']});
  assert.deepEqual(parseHeaders(formatHeaders(headers)),headers);
  assert.throws(()=>parseHeaders('Host: example.test'));assert.throws(()=>parseHeaders('bad name: value'));assert.throws(()=>parseHeaders('missing colon'));
  assert.equal(Object.getPrototypeOf(parseHeaders('__proto__: safe')),Object.prototype);
  assert.deepEqual(Object.keys(parseHeaders('__proto__: safe')),['__proto__']);
});

test('validates URI, payload combinations and GET restrictions before applying a row',()=>{
  const p=newPath('p');p.requestPath='/orders?q=a%20b';p.requestUrl='https://example.test/api';
  validatePath(p);
  p.requestPath='/../secret';assert.throws(()=>validatePath(p));p.requestPath='//evil.test';assert.throws(()=>validatePath(p));p.requestPath='/orders';
  p.payload.contentLength=[1];assert.throws(()=>validatePath(p));p.payload.contentLength=[64];p.method='GET';assert.throws(()=>validatePath(p));
  p.payload.contentLength=[0];validatePath(p);p.payload.contentEncoding=['UNKNOWN'];assert.throws(()=>validatePath(p));
});

test('rejects invalid edit indexes, duplicate ids and more than 100 paths',()=>{
  assert.throws(()=>upsertPath({paths:[newPath('p')]},2,newPath('p')));
  assert.throws(()=>upsertPath({paths:[newPath('p')]},null,newPath('p')));
  assert.throws(()=>upsertPath({paths:Array.from({length:100},(_,i)=>newPath(String(i)))},null,newPath('more')));
});

test('assigns an id when editing an unsaved path supplied through full JSON',()=>{
  const p=newPath('assigned');const raw=structuredClone(p);delete raw.id;
  const updated=upsertPath({paths:[raw]},0,p);
  assert.equal(updated.paths[0].id,'assigned');assert.equal(raw.id,undefined);
});
