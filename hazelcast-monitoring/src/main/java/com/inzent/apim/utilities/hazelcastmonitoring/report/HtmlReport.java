package com.inzent.apim.utilities.hazelcastmonitoring.report;

import com.inzent.apim.utilities.hazelcastmonitoring.cli.Options;
import com.inzent.apim.utilities.hazelcastmonitoring.export.AtomicFiles;
import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import com.inzent.apim.utilities.hazelcastmonitoring.time.DateTimes;
import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Fully self-contained HTML. No JSON/script interpolation of cluster-provided strings. */
public final class HtmlReport {
    private record Key(String service,String name) {}
    private record Point(int index,Long count,String status) {}
    private static final int MAX_SERIES=1000;
    public Path write(List<Snapshot> rows,Options o) throws IOException {
        Path target=o.reportFile()!=null?o.reportFile():Path.of("output/reports/report-"+
            DateTimes.fileName(Instant.now(),o.reportZone())+"-"+UUID.randomUUID()+".html").toAbsolutePath();
        Map<Key,List<Point>> series=new LinkedHashMap<>();
        for(int i=0;i<rows.size();i++)for(var object:rows.get(i).objects()) {
            Key key=new Key(object.serviceName(),object.objectName());
            series.computeIfAbsent(key,k -> new ArrayList<>()).add(new Point(i,object.objectCount(),object.countStatus().name()));
            if(series.size()>MAX_SERIES)throw new IllegalArgumentException("Report exceeds 1000 object series; narrow time range");
        }
        AtomicFiles.write(target,w -> render(w,rows,series,o)); return target;
    }
    private void render(Writer w,List<Snapshot> rows,Map<Key,List<Point>> series,Options o) throws IOException {
        w.write("""
            <!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Hazelcast monitoring report</title><style>
            :root{font-family:system-ui,sans-serif;color:#17243d;background:#f3f6fb}body{max-width:1200px;margin:auto;padding:32px}
            header{border-top:6px solid #4f46e5;padding:24px 0}h1{font-size:32px;margin:8px 0}h2{font-size:20px}h3{overflow-wrap:anywhere}
            .eyebrow{color:#4f46e5;font-weight:700;letter-spacing:.12em}.muted,small{color:#56647a}.card{background:white;border:1px solid #dce2ec;border-radius:12px;padding:20px;margin:20px 0}
            .stats{display:flex;gap:24px;flex-wrap:wrap}.stats strong{font-size:28px;display:block}svg{width:100%;height:auto}svg text{font-size:12px;fill:#56647a}
            .line{fill:none;stroke:#4f46e5;stroke-width:2.5}.dot{fill:#4f46e5}.gap{fill:#c2410c}circle:focus{stroke:#111;stroke-width:3}
            table{width:100%;border-collapse:collapse;font-size:13px}th,td{text-align:left;padding:10px;border-bottom:1px solid #e5e9f0;overflow-wrap:anywhere}
            input,button{font:inherit;padding:9px;border:1px solid #bcc5d3;border-radius:6px}input{width:min(90%,480px)}.scroll{overflow:auto}
            @media print{body{background:white;padding:0}.card{break-inside:avoid}input,button{display:none}}
            </style></head><body><header><div class="eyebrow">HAZELCAST / OBSERVATIONS</div><h1>클러스터 모니터링 보고서</h1>
            """);
        w.write("<p>Cluster: <strong>"+escape(o.clusterId())+"</strong> · 표시 시간대: "+escape(o.reportZone().toString())+"</p>");
        w.write("<p class=muted>Cluster name: "+escape(rows.isEmpty()?o.clusterName():String.join(", ",rows.stream().map(Snapshot::clusterName).distinct().toList()))+"</p>");
        w.write("<p class=muted>요청 기간 ["+escape(o.start()==null?"제한 없음":time(o.start(),o))+", "+escape(o.end()==null?"제한 없음":time(o.end(),o))+") · 생성: "+escape(time(Instant.now(),o))+"</p>");
        if(!rows.isEmpty())w.write("<p class=muted>실제 데이터: "+escape(time(rows.get(0).executedAt(),o))+" ~ "+escape(time(rows.get(rows.size()-1).executedAt(),o))+"</p>");
        w.write("<button onclick=\"window.print()\">인쇄 / PDF</button></header><section class=\"card stats\">");
        w.write("<div>전체 실행<strong>"+rows.size()+"</strong></div>");
        for(var status:Snapshot.Status.values())w.write("<div>"+status+"<strong>"+rows.stream().filter(r -> r.collectionStatus()==status).count()+"</strong></div>");
        w.write("<div>객체 series<strong>"+series.size()+"</strong></div></section>");
        long unsupported=rows.stream().flatMap(r->r.objects().stream()).filter(v->v.countStatus()==Snapshot.CountStatus.UNSUPPORTED).count();
        long errors=rows.stream().flatMap(r->r.objects().stream()).filter(v->v.countStatus()==Snapshot.CountStatus.ERROR||v.countStatus()==Snapshot.CountStatus.NOT_COLLECTED).count();
        w.write("<p class=muted>미지원 관측 "+unsupported+"건 · 오류/미수집 "+errors+"건. 주황색은 미확인 값입니다. 목록에 없던 객체도 선을 끊습니다. 실제 측정된 0만 0으로 그립니다. 각 점에 마우스 또는 키보드 초점을 두면 원래 값과 실행 ID를 볼 수 있습니다.</p>");
        if(rows.isEmpty())w.write("<section class=card><h2>조회 결과 없음</h2><p>선택한 클러스터와 기간에 저장된 실행이 없습니다.</p></section>");
        else {
            List<Point> active=new ArrayList<>();
            for(int i=0;i<rows.size();i++)active.add(new Point(i,rows.get(i).activeNodeCount()==null?null:rows.get(i).activeNodeCount().longValue(),rows.get(i).collectionStatus().name()));
            w.write("<section class=card><h2>Active node count</h2><p class=muted>클라이언트가 수집 시작 시 관측한 멤버 수 · lite member 포함</p>");
            chart(w,rows,active,o,"Active node count"); w.write("</section>");
        }
        w.write("<h2>Object count</h2><input id=search type=search aria-label=\"객체 이름 또는 서비스 검색\" placeholder=\"객체 이름 또는 서비스 검색\">");
        for(var entry:series.entrySet()) {
            w.write("<section class=\"card object-series\"><h3>"+escape(entry.getKey().name())+"</h3><p class=muted>"+escape(entry.getKey().service())+"</p>");
            chart(w,rows,entry.getValue(),o,entry.getKey().name()); w.write("</section>");
        }
        w.write("<section class=card><h2>실행 이력</h2><div class=scroll><table><thead><tr><th>실행시간</th><th>실행 ID</th><th>결과</th><th>Nodes</th><th>객체 관측</th><th>Topology</th></tr></thead><tbody>");
        for(var row:rows)w.write("<tr><td>"+escape(time(row.executedAt(),o))+"</td><td>"+row.id()+"</td><td>"+row.collectionStatus()+"</td><td>"+
            (row.activeNodeCount()==null?"UNKNOWN":row.activeNodeCount())+"</td><td>"+row.objects().size()+"</td><td>"+(row.topologyChanged()?"CHANGED":"—")+"</td></tr>");
        w.write("""
            </tbody></table></div></section><footer class=muted>클라이언트의 구간 관측값이며 클러스터 전체의 원자적 스냅샷이 아닙니다. 서버 내부 cluster state는 UNKNOWN입니다.</footer>
            <script>document.getElementById('search').addEventListener('input',function(){const q=this.value.toLocaleLowerCase();document.querySelectorAll('.object-series').forEach(function(card){card.hidden=!card.textContent.toLocaleLowerCase().includes(q);});});</script>
            </body></html>
            """);
    }
    private void chart(Writer w,List<Snapshot> rows,List<Point> points,Options o,String label) throws IOException {
        double max=points.stream().filter(p->p.count()!=null).mapToDouble(p->p.count()).max().orElse(0); if(max==0)max=1;
        StringBuilder path=new StringBuilder(); int previous=-2;
        w.write("<svg viewBox=\"0 0 960 270\" role=img aria-label=\""+escape(label)+"\"><path d=\"M64 30V210H900\" fill=none stroke=\"#a7b3c5\"/>");
        w.write("<text x=8 y=35>"+String.format(Locale.ROOT,"%.0f",max)+"</text><text x=35 y=214>0</text>");
        for(var p:points) {
            double x=x(rows,p.index());
            if(p.count()==null) {
                previous=-2;
                w.write("<path class=gap data-status=\""+p.status()+"\" d=\"M"+n(x-4)+" 225l4 -7l4 7z\"><title>"+escape(time(rows.get(p.index()).executedAt(),o))+" · "+p.status()+"</title></path>");
            } else {
                double y=210-(p.count()/max)*180;
                path.append(previous+1==p.index()?"L":"M").append(n(x)).append(' ').append(n(y)).append(' '); previous=p.index();
                w.write("<circle class=dot tabindex=\"0\" data-status=\""+p.status()+"\" cx=\""+n(x)+"\" cy=\""+n(y)+"\" r=\"3.5\"><title>"+
                    escape(time(rows.get(p.index()).executedAt(),o))+" · "+p.count()+" · "+rows.get(p.index()).id()+"</title></circle>");
            }
        }
        w.write("<path class=line d=\""+path+"\"/><text x=64 y=254>"+escape(time(rows.get(0).executedAt(),o))+"</text><text x=900 y=254 text-anchor=end>"+
            escape(time(rows.get(rows.size()-1).executedAt(),o))+"</text></svg>");
    }
    private double x(List<Snapshot> rows,int index) {
        double start=seconds(rows.get(0).executedAt()), span=seconds(rows.get(rows.size()-1).executedAt())-start;
        return span==0?64+836*(rows.size()==1?.5:(double)index/(rows.size()-1)):64+836*(seconds(rows.get(index).executedAt())-start)/span;
    }
    private double seconds(Instant t) { return t.getEpochSecond()+t.getNano()/1e9; }
    private String n(double d) { return String.format(Locale.ROOT,"%.2f",d); }
    private String time(Instant t,Options o) { return DateTimes.format(t,o.reportZone()); }
    private String escape(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
}
