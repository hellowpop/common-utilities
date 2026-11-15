const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm'),fs=require('node:fs');
const model=require('../../main/resources/caller-ui/path-model.js');
test('copy button moves unsaved form contents into a new draft; cancel and Add leave source unchanged',()=>{
 const elements=new Map();
 function element(tag='div'){return {tag,children:[],events:{},value:'',textContent:'',open:false,append(...v){this.children.push(...v);},replaceChildren(){this.children=[];},setAttribute(){},focus(){},reset(){},showModal(){this.open=true;},close(){this.open=false;},addEventListener(name,fn){this.events[name]=fn;},querySelectorAll(selector){const all=[];const visit=e=>{for(const c of e.children||[]){if(c.tag==='input'&&(selector==='input'||c.checked))all.push(c);visit(c);}};visit(this);return all;}};}
 const el=id=>{if(!elements.has(id))elements.set(id,element());return elements.get(id);};
 const original=model.newPath('original');original.name='source';let scenario={endpointBaseUrl:'http://localhost',paths:[original]},errors=[];
 const context={CallerPathModel:model,document:{getElementById:el,createElement:element},crypto:{randomUUID:()=> 'new-id'}};
 vm.createContext(context);vm.runInContext(fs.readFileSync(require.resolve('../../main/resources/caller-ui/path-ui.js'),'utf8'),context);
 const ui=context.CallerPathUI.create({getScenario:()=>scenario,setScenario:s=>{scenario=s;},onError:e=>errors.push(e)});
 ui.render(scenario);el('path-rows').children[0].children[7].children[0].events.click({stopPropagation(){}});
 el('path-name').value='unsaved name';el('path-request-path').value='/copied';
 assert.equal(typeof el('path-copy').events.click,'function');el('path-copy').events.click();
 assert.equal(el('path-apply').textContent,'추가');assert.equal(el('path-name').value,'unsaved name (복사)');assert.equal(scenario.paths.length,1);
 el('path-cancel').events.click();assert.equal(scenario.paths.length,1);assert.equal(scenario.paths[0].requestPath,'/');
 ui.render(scenario);el('path-rows').children[0].children[7].children[0].events.click({stopPropagation(){}});el('path-request-path').value='/copied';el('path-copy').events.click();
 el('path-form').events.submit({preventDefault(){}});
 assert.deepEqual(errors,[]);assert.equal(scenario.paths.length,2);assert.equal(scenario.paths[0].id,'original');assert.equal(scenario.paths[0].requestPath,'/');assert.equal(scenario.paths[1].id,'new-id');assert.equal(scenario.paths[1].requestPath,'/copied');
 scenario={...scenario,paths:Array.from({length:100},(_,i)=>model.newPath('path-'+i))};ui.render(scenario);
 el('path-rows').children[0].children[7].children[0].events.click({stopPropagation(){}});el('path-copy').events.click();
 assert.equal(el('path-apply').textContent,'적용');el('path-request-path').value='/still-editable';el('path-form').events.submit({preventDefault(){}});
 assert.equal(scenario.paths.length,100);assert.equal(scenario.paths[0].requestPath,'/still-editable');
});
