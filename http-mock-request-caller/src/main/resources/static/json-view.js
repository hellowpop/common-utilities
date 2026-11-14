(function(root){
  'use strict';
  // Preserve every source character; token text is rendered with textContent, never HTML.
  function tokenize(source){
    const tokens=[],pattern=/"(?:\\[\s\S]|[^"\\])*"?|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|\b(?:true|false|null)\b|[{}\[\]]|[,:]/g;
    let cursor=0,match;
    while((match=pattern.exec(source))!==null){
      if(match.index>cursor)tokens.push({kind:'plain',text:source.slice(cursor,match.index)});
      const text=match[0];let kind;
      if(text[0]==='"'){
        let next=pattern.lastIndex;while(next<source.length&&/\s/.test(source[next]))next++;
        kind=source[next]===':'?'key':'string';
      }else if(/[{}\[\]]/.test(text[0]))kind='brace';
      else if(text===','||text===':')kind='punctuation';
      else if(text==='true'||text==='false'||text==='null')kind='literal';
      else kind='number';
      tokens.push({kind,text});cursor=pattern.lastIndex;
    }
    if(cursor<source.length)tokens.push({kind:'plain',text:source.slice(cursor)});
    return tokens;
  }
  if(typeof module==='object'&&module.exports)module.exports={tokenize};
  else root.CallerJson={tokenize};
})(typeof globalThis==='object'?globalThis:this);
