const {test}=require('node:test');
const assert=require('node:assert/strict');
const model=require('../../main/resources/static/path-model.js');
const scenario=()=>({paths:[model.newPath('p')],loopCount:3,maxWorkers:2,runTimeoutMillis:120000,rampUpSeconds:0});
test('normalizes old scenarios and paths without mutating original JSON',()=>{
 const raw=scenario();delete raw.paths[0].delay;const s=model.normalizeScenario(raw);
 assert.equal(s.executionMode,'LOOPS');assert.equal(s.durationSeconds,60);assert.equal(raw.executionMode,undefined);
 assert.deepEqual(s.paths[0].delay,{mode:'NONE',fixedMillis:0,minMillis:0,maxMillis:0});
});
test('selected execution limit alone controls validation and estimate',()=>{
 const s=scenario();assert.equal(model.executionEstimate(s),'예상 6 요청');
 s.executionMode='DURATION';s.durationSeconds=60;s.loopCount=0;model.validateExecution(s);
 assert.match(model.executionEstimate(s),/시간 기준.*60초/);
 for(const duration of [0,86401,1.2])assert.throws(()=>model.validateExecution({...s,durationSeconds:duration}));
 assert.throws(()=>model.validateExecution({...s,runTimeoutMillis:60000}));
 assert.throws(()=>model.validateExecution({...s,rampUpSeconds:60}));
 model.validateExecution({...s,executionMode:'LOOPS',loopCount:3,durationSeconds:-1});
 assert.throws(()=>model.validateExecution({...s,executionMode:'UNKNOWN'}));
});
test('delay validates only active fields and survives isolated path JSON roundtrip',()=>{
 const p=model.newPath('p');assert.deepEqual(p.delay,{mode:'NONE',fixedMillis:0,minMillis:0,maxMillis:0});
 p.delay={mode:'FIXED',fixedMillis:600000,minMillis:-1,maxMillis:-2};model.validatePath(p);
 p.delay.fixedMillis=600001;assert.throws(()=>model.validatePath(p));p.delay.fixedMillis=1.5;assert.throws(()=>model.validatePath(p));
 p.delay={mode:'RANDOM',fixedMillis:-1,minMillis:0,maxMillis:600000};model.validatePath(p);
 p.delay.minMillis=600001;assert.throws(()=>model.validatePath(p));p.delay.minMillis=20;p.delay.maxMillis=10;assert.throws(()=>model.validatePath(p));
 p.delay={mode:'RANDOM',fixedMillis:15,minMillis:10,maxMillis:20};const next=model.upsertPath({paths:[]},null,p);
 assert.deepEqual(JSON.parse(JSON.stringify(next)).paths[0].delay,p.delay);assert.match(model.delaySummary(p),/10.*20.*ms/);
 p.delay.mode='INVALID';assert.throws(()=>model.validatePath(p));
});
test('duration count formatting never turns unknown planned requests into zero',()=>{
 assert.equal(model.runCountSummary({executionMode:'DURATION',completed:9,planned:null}),'9 / 시간 기준');
 assert.equal(model.runCountSummary({completed:3,planned:6}),'3 / 6');
});
test('duration validation honors backend defaults when optional deadlines are omitted',()=>{
 const s=scenario();s.executionMode='DURATION';delete s.runTimeoutMillis;delete s.rampUpSeconds;model.validateExecution(s);
});
