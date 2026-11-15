const {test}=require('node:test');
const assert=require('node:assert/strict');
const model=require('../../main/resources/static/path-model.js');
test('copy is an independent draft with a new id and preserves nested payload, headers, hooks and delays',()=>{
 const original=model.newPath('source');original.name='orders';original.headers={'X-Tag':['one','two']};original.payload.contentLength=[4096,4096];original.delay={mode:'RANDOM',fixedMillis:7,minMillis:10,maxMillis:20};original.requestHooks.pre='worker.vars.put("x",1)';
 const before=JSON.stringify(original),copy=model.copyPath(original,'copy');
 assert.equal(copy.id,'copy');assert.equal(copy.name,'orders (복사)');
 assert.deepEqual({...copy,id:original.id,name:original.name},original);
 copy.headers['X-Tag'].push('three');copy.payload.contentLength.push(8192);copy.delay.minMillis=15;copy.requestHooks.pre='changed';
 assert.equal(JSON.stringify(original),before);
 const scenario={paths:[original]},added=model.upsertPath(scenario,null,model.copyPath(original,'new'));
 assert.equal(scenario.paths.length,1);assert.equal(added.paths.length,2);assert.equal(added.paths[1].id,'new');
});
