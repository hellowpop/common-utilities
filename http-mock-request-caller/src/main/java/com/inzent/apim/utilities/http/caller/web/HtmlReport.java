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
        for(String key:List.of("id","status","outcome","startedAt","endedAt","seed","executionMode","durationSeconds","executionElapsedMillis","planned","completed","succeeded","failed","skipped","sentBytes","receivedBytes","persistenceState"))row(b,key,key.equals("planned")&&run.get(key)==null&&"DURATION".equals(run.get("executionMode"))?"시간 기준 (요청 수 미정)":key.endsWith("At")?time(run.get(key)):run.get(key));
        b.append("</tbody></table>");
        chart(b,"상태 코드",map(run.get("statusCounts")));chart(b,"오류 분포",map(run.get("errorCounts")));
        b.append("<h2>지연 시간 (ms, 근삿값 percentile)</h2>");
        tableStart(b,"latency-table",List.of("표본 수","최소 (ms)","평균 (ms)","최대 (ms)","p50 (ms)","p95 (ms)","p99 (ms)"));
        Map<?,?> latency=map(run.get("latency"));
        if(latency.isEmpty())emptyRow(b,7);else cells(b,List.of("samples","minMillis","meanMillis","maxMillis","p50Millis","p95Millis","p99Millis").stream().map(latency::get).toArray());
        tableEnd(b);b.append("<h2>Path별 결과</h2>");
        tableStart(b,"path-table",List.of("Path 이름 / ID","메서드","요청 Path","완료","성공","실패"));
        Map<String,Map<?,?>> paths=new LinkedHashMap<>();
        for(Map<?,?> p:maps(map(run.get("snapshot")).get("paths")))paths.put(String.valueOf(p.get("id")),p);
        List<Map<?,?>> statistics=maps(run.get("pathStatistics"));
        for(Map<?,?> p:statistics){Map<?,?> definition=paths.getOrDefault(String.valueOf(p.get("pathId")),Map.of());
            cells(b,(definition.get("name")==null?"":definition.get("name"))+" / "+p.get("pathId"),definition.get("method"),definition.get("requestPath"),p.get("completed"),p.get("succeeded"),p.get("failed"));}
        if(statistics.isEmpty())emptyRow(b,6);tableEnd(b);b.append("<h2>Worker timeline</h2>");
        tableStart(b,"worker-table",List.of("Worker","상태","시작 (Asia/Seoul)","종료 (Asia/Seoul)","수행 시간 (ms)","완료 요청"));
        List<Map<?,?>> workers=maps(run.get("workers"));
        for(Map<?,?> w:workers)cells(b,w.get("index"),w.get("status"),time(w.get("startedAt")),time(w.get("endedAt")),elapsed(w.get("startedAt"),w.get("endedAt")),w.get("completed"));
        if(workers.isEmpty())emptyRow(b,6);tableEnd(b);b.append("<h2>실행 설정</h2>");
        Object snapshot=run.get("snapshot");
        if(snapshot instanceof Map<?,?> s){b.append("<table>");for(String key:List.of("maxWorkers","executionMode","loopCount","durationSeconds","rampUpSeconds","requestTimeoutMillis","runTimeoutMillis","selectionMode","bodyRetention"))row(b,key,s.get(key));b.append("</table>");}
        b.append("<h2>실패 요청 (최대 100건)</h2><table><tr><th>ID / path</th><th>상태</th><th>분류</th></tr>");
        for(var r:failed)b.append("<tr><td>").append(escape(r.get("id"))).append("<br>").append(escape(r.get("pathName"))).append("</td><td>").append(escape(r.get("status"))).append("</td><td>").append(escape(r.get("errorType"))).append("</td></tr>");
        long omitted=Math.max(0,number(run.get("failed"))-failed.size());b.append("</table><p>생략된 실패 요청: ").append(omitted).append("</p><p class=\"note\">본문·스크립트·요청 및 응답 헤더는 보고서에 포함하지 않습니다. 전체 상세는 실행 이력 API에서 조회하세요.</p></html>");return b.toString();
    }
    private static void chart(StringBuilder b,String title,Map<?,?> values){
        b.append("<h2>").append(escape(title)).append("</h2><div style=\"overflow-x:auto;border:1px solid #ccd6de;padding:16px\"><p class=\"note\">범주별 건수 · 가로축: ").append(escape(title)).append(" · 세로축: 요청 수</p>");
        List<Map.Entry<?,?>> entries=new ArrayList<>(values.entrySet());entries.sort(Comparator.comparing(e->String.valueOf(e.getKey())));
        if(entries.isEmpty()){b.append("<p>집계 데이터 없음</p></div>");return;}
        double max=Math.max(1,entries.stream().mapToLong(e->number(e.getValue())).max().orElse(1));
        int width=Math.max(800,entries.size()*130+140);double plotWidth=width-140;
        b.append("<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" aria-label=\"").append(escape(title)).append(" 범주별 건수\" style=\"width:100%;min-width:540px\" viewBox=\"0 0 ").append(width).append(" 350\"><title>").append(escape(title)).append("</title>");
        for(int i=0;i<=4;i++){double y=260-i*55;b.append("<line x1=\"70\" y1=\"").append(y).append("\" x2=\"").append(width-70).append("\" y2=\"").append(y).append("\" stroke=\"#e1e8ef\"/><text x=\"60\" y=\"").append(y+4).append("\" text-anchor=\"end\" font-size=\"12\">").append(String.format(Locale.ROOT,"%.0f",max*i/4)).append("</text>");}
        StringBuilder points=new StringBuilder();
        for(int i=0;i<entries.size();i++){double x=entries.size()==1?70+plotWidth/2:70+i*plotWidth/(entries.size()-1),y=260-220*number(entries.get(i).getValue())/max;points.append(x).append(',').append(y).append(' ');}
        b.append("<polyline fill=\"none\" stroke=\"#15736c\" stroke-width=\"3\" points=\"").append(points).append("\"/>");
        for(int i=0;i<entries.size();i++){var e=entries.get(i);double x=entries.size()==1?70+plotWidth/2:70+i*plotWidth/(entries.size()-1),y=260-220*number(e.getValue())/max;
            b.append("<circle cx=\"").append(x).append("\" cy=\"").append(y).append("\" r=\"5\" fill=\"#15736c\"><title>").append(escape(e.getKey())).append(": ").append(escape(e.getValue())).append("</title></circle><text text-anchor=\"middle\" x=\"").append(x).append("\" y=\"").append(y-12).append("\" font-size=\"13\">").append(escape(e.getValue())).append("</text><text text-anchor=\"middle\" x=\"").append(x).append("\" y=\"290\" font-size=\"12\">").append(escape(e.getKey())).append("</text>");}
        b.append("</svg></div>");
    }
    private static Map<?,?> map(Object o){return o instanceof Map<?,?> m?m:Map.of();}
    private static List<Map<?,?>> maps(Object o){List<Map<?,?>> result=new ArrayList<>();if(o instanceof Iterable<?> rows)for(Object row:rows)if(row instanceof Map<?,?> m)result.add(m);return result;}
    private static void tableStart(StringBuilder b,String id,List<String> headings){b.append("<div style=\"overflow-x:auto\"><table id=\"").append(id).append("\"><thead><tr>");for(String h:headings)b.append("<th scope=\"col\">").append(escape(h)).append("</th>");b.append("</tr></thead><tbody>");}
    private static void tableEnd(StringBuilder b){b.append("</tbody></table></div>");}
    private static void cells(StringBuilder b,Object... values){b.append("<tr>");for(Object v:values)b.append("<td>").append(escape(v)).append("</td>");b.append("</tr>");}
    private static void emptyRow(StringBuilder b,int columns){b.append("<tr><td colspan=\"").append(columns).append("\">집계 데이터 없음</td></tr>");}
    private static Object elapsed(Object from,Object to){try{return Math.max(0,Duration.between(Instant.parse(from.toString()),Instant.parse(to.toString())).toMillis());}catch(Exception e){return "—";}}
    private static void row(StringBuilder b,String key,Object value){b.append("<tr><th>").append(escape(key)).append("</th><td>").append(escape(value)).append("</td></tr>");}
    static String escape(Object value){return String.valueOf(value==null?"":value).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static long number(Object v){return v instanceof Number n?n.longValue():0;}
    private static Object time(Object v){try{return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z").format(Instant.parse(v.toString()).atZone(ZoneId.of("Asia/Seoul")));}catch(Exception e){return v;}}
}
