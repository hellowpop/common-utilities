package com.inzent.apim.utilities.http.caller.web;

import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Self-contained, escaped report. Bodies, scripts and credential headers are intentionally excluded. */
final class HtmlReport {
    private HtmlReport(){}
    static String render(Map<String,Object> run,List<Map<String,Object>> failed){
        StringBuilder b=new StringBuilder("<!doctype html><html lang=\"ko\"><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>HTTP 실행 보고서</title><style>body{font:15px system-ui;max-width:1100px;margin:40px auto;padding:20px;color:#172b3a}h1{border-bottom:3px solid #15736c;padding-bottom:16px}table{border-collapse:collapse;width:100%;margin:20px 0}td,th{border:1px solid #ccd6de;padding:10px;text-align:left;word-break:break-word}th{background:#edf5f3}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f3f6f8;padding:16px}.note{color:#536576}</style><h1>HTTP 실행 보고서 · "+escape(run.get("title"))+"</h1>");
        b.append("<p class=\"note\">시간대 Asia/Seoul · 외부 리소스 없는 오프라인 보고서</p><table><tbody>");
        for(String key:List.of("id","status","outcome","startedAt","endedAt","seed","planned","completed","succeeded","failed","skipped","sentBytes","receivedBytes","persistenceState"))row(b,key,key.endsWith("At")?time(run.get(key)):run.get(key));
        b.append("</tbody></table><h2>상태 코드</h2><pre>").append(escape(run.get("statusCounts"))).append("</pre><h2>오류 분포</h2><pre>").append(escape(run.get("errorCounts"))).append("</pre><h2>지연 시간(ms, 근삿값 percentile)</h2><pre>").append(escape(run.get("latency"))).append("</pre><h2>Path별 결과</h2><pre>").append(escape(run.get("pathStatistics"))).append("</pre><h2>Worker 타임라인</h2><pre>").append(escape(run.get("workers"))).append("</pre><h2>실행 설정</h2>");
        Object snapshot=run.get("snapshot");
        if(snapshot instanceof Map<?,?> s){b.append("<table>");for(String key:List.of("maxWorkers","loopCount","rampUpSeconds","requestTimeoutMillis","runTimeoutMillis","selectionMode","bodyRetention"))row(b,key,s.get(key));b.append("</table>");}
        b.append("<h2>실패 요청 (최대 100건)</h2><table><tr><th>ID / path</th><th>상태</th><th>분류</th></tr>");
        for(var r:failed)b.append("<tr><td>").append(escape(r.get("id"))).append("<br>").append(escape(r.get("pathName"))).append("</td><td>").append(escape(r.get("status"))).append("</td><td>").append(escape(r.get("errorType"))).append("</td></tr>");
        long omitted=Math.max(0,number(run.get("failed"))-failed.size());b.append("</table><p>생략된 실패 요청: ").append(omitted).append("</p><p class=\"note\">본문·스크립트·요청 및 응답 헤더는 보고서에 포함하지 않습니다. 전체 상세는 실행 이력 API에서 조회하세요.</p></html>");return b.toString();
    }
    private static void row(StringBuilder b,String key,Object value){b.append("<tr><th>").append(escape(key)).append("</th><td>").append(escape(value)).append("</td></tr>");}
    static String escape(Object value){return String.valueOf(value==null?"":value).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static long number(Object v){return v instanceof Number n?n.longValue():0;}
    private static Object time(Object v){try{return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z").format(Instant.parse(v.toString()).atZone(ZoneId.of("Asia/Seoul")));}catch(Exception e){return v;}}
}
