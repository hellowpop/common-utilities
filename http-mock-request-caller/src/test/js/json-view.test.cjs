const {test}=require('node:test');
const assert=require('node:assert/strict');
const {tokenize}=require('../../main/resources/static/json-view.js');

test('distinguishes braces, keys, string and scalar values without changing source',()=>{
  const source='{ "name": "caller", "count": -1.5e2, "enabled": true, "items": [null, false] }';
  const tokens=tokenize(source);
  assert.equal(tokens.map(t=>t.text).join(''),source);
  assert.deepEqual(tokens.filter(t=>t.kind==='brace').map(t=>t.text),['{','[',']','}']);
  assert.deepEqual(tokens.filter(t=>t.kind==='key').map(t=>t.text),['"name"','"count"','"enabled"','"items"']);
  assert.deepEqual(tokens.filter(t=>t.kind==='number').map(t=>t.text),['-1.5e2']);
  assert.equal(tokens.find(t=>t.kind==='string').text,'"caller"');
  assert.deepEqual(tokens.filter(t=>t.kind==='literal').map(t=>t.text),['true','null','false']);
});

test('keeps escaped quotes and brace-like or HTML-like content inside string values',()=>{
  const source='{"a\\\"key":"{[\\\"value\\\"]}<img src=x onerror=alert(1)>"}';
  const tokens=tokenize(source);
  assert.equal(tokens.map(t=>t.text).join(''),source);
  assert.deepEqual(tokens.filter(t=>t.kind==='brace').map(t=>t.text),['{','}']);
  assert.equal(tokens.filter(t=>t.kind==='string').length,1);
  assert.ok(tokens.find(t=>t.kind==='string').text.includes('<img'));
});

test('preserves incomplete input and trailing newlines while typing',()=>{
  for(const source of ['', '{"name":', '{\n  "a": "unfinished {', '{"a":1}\n']){
    assert.equal(tokenize(source).map(t=>t.text).join(''),source);
  }
});
