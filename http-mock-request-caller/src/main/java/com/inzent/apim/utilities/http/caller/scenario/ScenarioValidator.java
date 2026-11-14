package com.inzent.apim.utilities.http.caller.scenario;

import java.net.URI;
import java.util.*;
import com.inzent.apim.utilities.http.payload.ContentType;

public final class ScenarioValidator {
    public long validate(Scenario s){
        require(s!=null,"scenario required");
        require(s.title!=null&&!s.title.isBlank()&&s.title.length()<=200,"title required, max 200 characters");
        require(s.description!=null&&s.description.length()<=10000,"description exceeds limit");
        require(s.maxWorkers>=1&&s.maxWorkers<=32,"maxWorkers must be 1..32");
        require(s.loopCount>=1&&s.loopCount<=100000,"loopCount must be 1..100000");
        require(s.runTimeoutMillis>=1&&s.runTimeoutMillis<=86400000,"run timeout must be 1ms..24h");
        require(s.requestTimeoutMillis>=1&&s.requestTimeoutMillis<=s.runTimeoutMillis,"invalid request timeout");
        require(Double.isFinite(s.rampUpSeconds)&&s.rampUpSeconds>=0&&s.rampUpSeconds*1000<s.runTimeoutMillis,"invalid ramp-up");
        require(Set.of("SHUFFLED_ALL","ORDERED_ALL").contains(s.selectionMode),"invalid selectionMode");
        require(Set.of("NONE","FAILURES","ALL").contains(s.bodyRetention),"invalid bodyRetention");
        require(s.logging!=null&&s.logging.maxFileSize>=1024&&s.logging.maxFileSize<=1024L*1024*1024&&s.logging.maxFiles>=1&&s.logging.maxFiles<=100,"invalid logging limits");
        require(!s.logging.enabled||Math.multiplyExact(Math.multiplyExact(s.logging.maxFileSize,s.logging.maxFiles),s.maxWorkers+1)<=1024L*1024*1024,"logging exceeds run budget 1 GiB");
        hooks(s.scenarioHooks);hooks(s.workerHooks);base(s.endpointBaseUrl);
        require(s.paths!=null&&!s.paths.isEmpty()&&s.paths.size()<=100,"paths must contain 1..100 entries");
        Set<String> ids=new HashSet<>(),origins=new HashSet<>();long total=0;
        for(var p:s.paths){
            require(p!=null&&p.id!=null&&ids.add(p.id),"path ids must be unique");
            require(p.name!=null&&p.name.length()<=200,"invalid path name");
            URI uri=resolve(p.requestUrl==null||p.requestUrl.isBlank()?s.endpointBaseUrl:p.requestUrl,p.requestPath);
            origins.add(uri.getScheme()+"://"+uri.getRawAuthority());
            require(Set.of("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS").contains(p.method),"unsupported method");
            require(p.successStatusMin>=100&&p.successStatusMax<=599&&p.successStatusMin<=p.successStatusMax,"invalid success range");
            validateHeaders(p.headers);hooks(p.pathHooks);hooks(p.requestHooks);
            PayloadMatrix matrix=new PayloadMatrix(p.payload);
            for(long i=0;i<matrix.size();i++){
                var v=matrix.at(i);require(v.contentLength()>=0&&v.contentLength()<=64L*1024*1024,"payload max 64 MiB");
                require(v.blockSize()>0&&v.blockSize()<=64L*1024*1024,"invalid blockSize");
                long min=switch(v.contentType()){
                    case APPLICATION_JSON->2;case APPLICATION_XML->13;case TEXT_HTML->26;case MULTIPART_FORM_DATA->233;default->1;};
                require(v.contentLength()==0||v.contentLength()>=min,"payload shorter than minimum for "+v.contentType());
                if(p.method.equals("GET")||p.method.equals("HEAD"))require(v.contentLength()==0&&!v.chunked()&&v.contentEncoding().name().equals("IDENTITY"),"GET/HEAD require empty identity nonchunked payload");
            }
            total=Math.addExact(total,matrix.size());
        }
        require(origins.size()<=16,"max 16 origins");
        total=Math.multiplyExact(Math.multiplyExact(total,s.maxWorkers),s.loopCount);
        require(total<=100000,"planned requests exceed 100000");return total;
    }
    public static URI resolve(String base,String path){
        URI b=base(base);require(path!=null&&(path.isEmpty()||path.startsWith("/"))&&!path.startsWith("//"),"path must be a relative /path");
        require(!path.contains("\r")&&!path.contains("\n")&&!path.contains("\\"),"invalid path");
        URI rel=URI.create(path);require(!rel.isAbsolute()&&rel.getRawAuthority()==null&&rel.getRawFragment()==null,"invalid path authority/fragment");
        String decoded=rel.getPath();for(String part:decoded.split("/"))require(!part.equals(".")&&!part.equals(".."),"dot path segment forbidden");
        String value=b.toASCIIString().replaceAll("/+$","")+(path.isEmpty()?"/":path);
        return URI.create(value);
    }
    public static URI base(String value){
        require(value!=null,"base URL required");URI u=URI.create(value);
        require(("http".equals(u.getScheme())||"https".equals(u.getScheme()))&&u.getHost()!=null&&u.getRawUserInfo()==null&&u.getRawQuery()==null&&u.getRawFragment()==null,"base must be http/https URL without query, fragment or user-info");
        require(u.getPort()>=-1&&u.getPort()!=0&&u.getPort()<=65535,"invalid port");return u;
    }
    public static void validateHeaders(Map<String,List<String>> headers){
        require(headers!=null&&headers.size()<=64,"max 64 headers");long bytes=0;
        Set<String> managed=Set.of("host","content-length","transfer-encoding","content-type","content-encoding","connection","upgrade","expect");
        for(var e:headers.entrySet()){
            require(e.getKey()!=null&&e.getKey().matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")&&!managed.contains(e.getKey().toLowerCase(Locale.ROOT)),"invalid or managed header: "+e.getKey());
            require(e.getValue()!=null&&!e.getValue().isEmpty(),"header values required");
            for(String v:e.getValue()){require(v!=null&&!v.contains("\r")&&!v.contains("\n")&&v.indexOf(0)<0,"invalid header value");bytes+=e.getKey().length()+v.length();}
        }require(bytes<=32768,"header limit 32 KiB");
    }
    private static void hooks(Scenario.Hooks h){require(h!=null&&h.pre!=null&&h.post!=null&&h.pre.length()<=32768&&h.post.length()<=32768,"invalid hook length");}
    public static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
