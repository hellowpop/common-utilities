package com.inzent.apim.utilities.http.caller.scenario;

import com.inzent.apim.utilities.http.caller.payload.PayloadSpec;
import java.util.*;

/** Mixed-radix Cartesian access with an affine coprime permutation, O(1) retained state. */
public final class PayloadMatrix {
    private final Scenario.Payload p;
    private final long size;
    public PayloadMatrix(Scenario.Payload p){
        this.p=Objects.requireNonNull(p,"payload");long n=1;
        for(List<?> a:Arrays.asList(p.chunked,p.contentType,p.contentEncoding,p.contentLength,p.blockSize)){
            if(a==null||a.isEmpty()||a.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("payload arrays must be nonempty and contain no null");
            n=Math.multiplyExact(n,a.size());
            if(n>100000)throw new IllegalArgumentException("payload combinations exceed 100000");
        }size=n;
    }
    public long size(){return size;}
    public PayloadSpec at(long index){
        if(index<0||index>=size)throw new IndexOutOfBoundsException();
        long block=p.blockSize.get((int)(index%p.blockSize.size()));index/=p.blockSize.size();
        long length=p.contentLength.get((int)(index%p.contentLength.size()));index/=p.contentLength.size();
        var encoding=p.contentEncoding.get((int)(index%p.contentEncoding.size()));index/=p.contentEncoding.size();
        var type=p.contentType.get((int)(index%p.contentType.size()));index/=p.contentType.size();
        return new PayloadSpec(p.chunked.get((int)index),type,encoding,length,block);
    }
    public long index(long ordinal,boolean shuffled,long seed){
        if(!shuffled||size<=1)return ordinal;
        SplittableRandom random=new SplittableRandom(seed);
        long multiplier=random.nextLong(1,size+1);
        while(gcd(multiplier,size)!=1)multiplier=multiplier%size+1;
        return (multiplier*ordinal+random.nextLong(size))%size;
    }
    private long gcd(long a,long b){while(b!=0){long t=a%b;a=b;b=t;}return a;}
}
