package com.inzent.apim.utilities.http.caller.web;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HtmlReportTest {
    @Test void rendersCategoryLineChartsAndReadableTablesWithEscapedLabels() {
        Map<String,Object> run=new LinkedHashMap<>();
        run.put("title","<script>alert(1)</script>");
        run.put("statusCounts",Map.of("200",12,"500",2));
        run.put("errorCounts",Map.of("<timeout>",2));
        run.put("latency",Map.of("samples",14,"minMillis",1.25,"meanMillis",2.5,"maxMillis",8.0,"p50Millis",2.0,"p95Millis",8.0,"p99Millis",8.0));
        run.put("snapshot",Map.of("paths",List.of(Map.of("id","p1","name","<orders>","method","POST","requestPath","/orders"))));
        run.put("pathStatistics",List.of(Map.of("pathId","p1","completed",14,"succeeded",12,"failed",2)));
        run.put("workers",List.of(Map.of("index",1,"status","COMPLETED","completed",14,"startedAt","2026-11-14T10:00:00Z","endedAt","2026-11-14T10:00:02Z")));
        String html=HtmlReport.render(run,List.of());
        assertEquals(2,html.split("<svg",-1).length-1);
        assertTrue(html.contains("<polyline"));assertTrue(html.contains("<circle"));
        assertTrue(html.contains("범주별 건수"));assertTrue(html.contains("&lt;timeout&gt;"));
        for(String id:List.of("latency-table","path-table","worker-table"))assertTrue(html.contains("id=\""+id+"\""),id);
        assertTrue(html.contains("&lt;orders&gt;"));assertTrue(html.contains("/orders"));assertTrue(html.contains("2000"));
        assertFalse(html.contains("<script>"));assertFalse(html.contains("<pre>"));
    }
    @Test void emptyStatisticsHaveExplicitEmptyStatesAndNoInvalidSvgNumbers() {
        String html=HtmlReport.render(Map.of(),List.of());
        assertTrue(html.contains("집계 데이터 없음"));
        assertFalse(html.contains("NaN"));assertFalse(html.contains("Infinity"));
        assertTrue(html.contains("id=\"worker-table\""));
    }
}
