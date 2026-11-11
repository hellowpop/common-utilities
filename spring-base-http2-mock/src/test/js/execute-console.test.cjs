const test = require('node:test');
const assert = require('node:assert/strict');
const {SseJsonDecoder} = require('../../main/resources/static/execute-console.js');

test('decodes events split across network chunks with CRLF and multiline JSON', () => {
  const received=[]; const parser=new SseJsonDecoder((event,data)=>received.push({event,data}));
  parser.push(': keepalive\r\n\r\nevent: res');
  parser.push('ponse\r\ndata: {"body":\r');
  parser.push('\ndata: "한글 응답"}\r\n\r\nevent: summary\ndata: {"totalCount":2}\n\n');
  parser.finish();
  assert.deepEqual(received,[{event:'response',data:{body:'한글 응답'}},{event:'summary',data:{totalCount:2}}]);
});
test('ignores heartbeat comments and preserves colons inside JSON strings', () => {
  const received=[]; const parser=new SseJsonDecoder((event,data)=>received.push(data));
  parser.push(':ping\n\nid:1\nevent:response\ndata: {"url":"http://localhost:8080/"}\n\n'); parser.finish();
  assert.deepEqual(received,[{url:'http://localhost:8080/'}]);
});
test('reports malformed or incomplete event payloads', () => {
  assert.throws(()=>new SseJsonDecoder(()=>{}).push('data: invalid-json\n\n'),SyntaxError);
  const parser=new SseJsonDecoder(()=>{}); parser.push('event: summary\ndata: {}');
  assert.throws(()=>parser.finish(),/완료되지 않은/);
});
