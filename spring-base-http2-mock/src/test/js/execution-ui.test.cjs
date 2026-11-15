const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const model=require('../../main/resources/caller-ui/path-model.js');
function app(){
 const elements=new Map();const element=()=>({value:'',textContent:'',hidden:false,disabled:false,style:{},classList:{toggle(){}},children:[],append(...items){this.children.push(...items);},replaceChildren(){this.children=[];},addEventListener(){},close(){this.open=false;},showModal(){this.open=true;}});
 const context={document:{getElementById(id){if(!elements.has(id))elements.set(id,element());return elements.get(id);},createElement:element},window:{addEventListener(){}},CallerPathModel:model,CallerPathUI:{create(){return {render(){}};}},URLSearchParams,console};
 vm.createContext(context);const source=fs.readFileSync(require.resolve('../../main/resources/caller-ui/app.js'),'utf8');
 vm.runInContext(source.slice(0,source.lastIndexOf("action(async()=>{csrf=")),context);
 return {run:code=>vm.runInContext(code,context),el:id=>context.document.getElementById(id),context};
}
test('mode control toggles enabled limits and retains inactive values through JSON synchronization',()=>{
 const a=app();a.context.sample={title:'test',endpointBaseUrl:'http://localhost',maxWorkers:1,rampUpSeconds:0,loopCount:7,durationSeconds:90,bodyRetention:'NONE',runTimeoutMillis:120000,paths:[]};
 a.run('populate(sample)');assert.equal(a.el('duration').disabled,true);assert.equal(a.el('loops').disabled,false);
 a.el('execution-mode').value='DURATION';a.run('fields()');assert.equal(a.el('duration').disabled,false);assert.equal(a.el('loops').disabled,true);
 assert.equal(JSON.parse(a.el('definition').value).loopCount,7);assert.equal(JSON.parse(a.el('definition').value).durationSeconds,90);
 a.el('duration').value='45';a.run('fields()');a.el('execution-mode').value='LOOPS';a.run('fields()');assert.equal(JSON.parse(a.el('definition').value).durationSeconds,45);assert.equal(a.el('loops').value,7);
});
test('YAML import sends plain text with CSRF and populates a new scenario only after validation succeeds',async()=>{
 const a=app();const calls=[];a.el('yaml-input').value='title: imported';
 a.context.fetch=async(url,options)=>{calls.push({url,options});return {ok:true,json:async()=>({title:'imported',paths:[]})};};
 await a.run('importYaml()');assert.equal(calls[0].url,'/api/scenarios/import');assert.equal(calls[0].options.body,'title: imported');assert.match(calls[0].options.headers['Content-Type'],/yaml/);assert.ok('X-CSRF-Token' in calls[0].options.headers);
 assert.equal(a.el('title').value,'imported');assert.equal(a.el('version').textContent,'저장 전');
 a.el('yaml-input').value='invalid';a.context.fetch=async()=>({ok:false,json:async()=>({detail:'Invalid YAML'})});
 await assert.rejects(a.run('importYaml()'),/Invalid YAML/);assert.equal(a.el('title').value,'imported');
});
test('duration live metrics show time and unknown totals while old runs retain request counts',()=>{
 const a=app();a.context.snapshot={id:'run',title:'test',status:'RUNNING',outcome:'NONE',executionMode:'DURATION',durationSeconds:60,executionElapsedMillis:15000,remainingDurationMillis:45000,resolvedProgress:.25,planned:null,completed:8,succeeded:8,failed:0,skipped:0,persistenceState:'OK'};
 a.run('showRun(snapshot)');assert.equal(a.el('progress-bar').style.width,'25%');assert.match(a.el('run-duration').textContent,/60초.*15초.*45초/);assert.equal(a.el('metrics').children[0].children[1].textContent,'시간 기준');
 a.run('delete snapshot.executionMode;snapshot.planned=10;showRun(snapshot)');assert.equal(a.el('run-duration').hidden,true);assert.equal(a.el('metrics').children[0].children[1].textContent,'10');
});

test('run summaries render table cells including status, errors, latency and named paths',()=>{
 const a=app();a.context.snapshot={id:'r',status:'COMPLETED',persistenceState:'OK',statusCounts:{200:3,500:1},errorCounts:{TIMEOUT:1},latency:{samples:4,meanMillis:1.25,p95Millis:2},throughput:3.5,sentBytes:10,receivedBytes:20,pathStatistics:[{pathId:'p',completed:4,succeeded:3,failed:1}],snapshot:{paths:[{id:'p',name:'echo',requestPath:'/echo'}]}};
 a.run('showRun(snapshot)');
 const cells=id=>a.el(id).children.flatMap(row=>row.children.map(cell=>cell.textContent));
 assert.ok(cells('run-stats').includes('200'));assert.ok(cells('run-stats').includes('TIMEOUT'));
 assert.ok(cells('latency').includes('3.50 req/s'));assert.ok(cells('latency').includes('1.25 ms'));
 assert.ok(cells('path-stats').includes('echo'));assert.ok(cells('path-stats').includes('/echo'));
 a.run('showRun({status:"QUEUED",persistenceState:"OK"})');assert.equal(a.el('path-stats').children.length,1);
});

test('opening a run defers unit requests until search and clears stale request details',async()=>{
 const a=app();const calls=[];a.el('request-detail').textContent='stale';
 a.context.fetch=async url=>{calls.push(url);return {ok:true,json:async()=>({id:'r',status:'COMPLETED',persistenceState:'OK'})};};
 await a.run('openRun("r")');assert.deepEqual(calls,['/api/runs/r']);assert.equal(a.el('request-results').hidden,true);assert.equal(a.el('request-detail').textContent,'');
});

test('request search sends combined filters and keeps applied filters across pages',async()=>{
 const a=app();const calls=[];a.run('currentRun="r"');
 a.el('request-status').value='500';a.el('request-worker').value='2';a.el('request-min-elapsed').value='1.5';a.el('request-max-elapsed').value='20';
 a.context.fetch=async url=>{calls.push(url);return {ok:true,json:async()=>[]};};
 await a.run('searchRequests()');const q=new URL(calls[0],'http://local').searchParams;
 assert.equal(q.get('status'),'500');assert.equal(q.get('workerIndex'),'2');assert.equal(q.get('minElapsedMillis'),'1.5');assert.equal(q.get('maxElapsedMillis'),'20');
 assert.equal(a.el('request-results').hidden,false);assert.equal(a.el('request-rows').children.length,1);
 a.el('request-status').value='200';await a.run('requestPage=1;results()');assert.equal(new URL(calls[1],'http://local').searchParams.get('status'),'500');
 a.el('request-min-elapsed').value='30';await assert.rejects(a.run('searchRequests()'),/소요시간/);assert.equal(calls.length,2);
});
