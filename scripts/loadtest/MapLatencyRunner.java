import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded closed-loop HTTP benchmark. Java 11 standard library only. */
public final class MapLatencyRunner {
    static final double LOG_STEP = Math.log(1.01);
    static final int BUCKETS = 2048;
    static final class Entry {
        final String group; final int requestId; final HttpRequest request;
        Entry(String group, int requestId, HttpRequest request) { this.group=group; this.requestId=requestId; this.request=request; }
    }
    static final class Counters {
        final LongAdder count=new LongAdder(), errors=new LongAdder(), bytes=new LongAdder(), nanos=new LongAdder();
        final AtomicLong min=new AtomicLong(Long.MAX_VALUE), max=new AtomicLong();
        final AtomicLongArray histogram=new AtomicLongArray(BUCKETS);
        final ConcurrentMap<String,LongAdder> outcomes=new ConcurrentHashMap<>();
        void record(long elapsed, long bodyBytes, String outcome, boolean failed) {
            count.increment(); if (failed) errors.increment(); bytes.add(bodyBytes); nanos.add(elapsed);
            min.accumulateAndGet(elapsed,Math::min); max.accumulateAndGet(elapsed,Math::max);
            histogram.incrementAndGet(bucket(elapsed)); outcomes.computeIfAbsent(outcome,k->new LongAdder()).increment();
        }
        double percentile(double q) {
            long target=(long)Math.ceil(count.sum()*q), total=0; if(target==0) return 0;
            for(int i=0;i<BUCKETS;i++) { total+=histogram.get(i); if(total>=target) return upperNanos(i)/1e6; }
            return max.get()/1e6;
        }
        Map<String,Object> report() {
            Map<String,Object> result=new LinkedHashMap<>(); long n=count.sum();
            result.put("completed",n); result.put("errors",errors.sum()); result.put("body_bytes_completed",bytes.sum());
            result.put("mean_ms",n==0?0:nanos.sum()/1e6/n); result.put("min_ms",n==0?0:min.get()/1e6);
            result.put("p50_ms",percentile(.50)); result.put("p95_ms",percentile(.95)); result.put("p99_ms",percentile(.99));
            result.put("max_ms",max.get()/1e6); Map<String,Long> codes=new TreeMap<>();
            outcomes.forEach((k,v)->codes.put(k,v.sum())); result.put("outcomes",codes); return result;
        }
    }
    // Consume the complete response without allocating a byte[] for a large map JSON body.
    static final class BodyCounter implements HttpResponse.BodySubscriber<Long> {
        final CompletableFuture<Long> body=new CompletableFuture<>(); long size;
        volatile Flow.Subscription subscription; volatile boolean cancelled;
        public CompletionStage<Long> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; if(cancelled) subscription.cancel(); else subscription.request(Long.MAX_VALUE); }
        void cancel() { cancelled=true; Flow.Subscription current=subscription; if(current!=null) current.cancel(); body.completeExceptionally(new TimeoutException("Body cancelled")); }
        public void onNext(List<ByteBuffer> buffers) { for(ByteBuffer buffer:buffers) size+=buffer.remaining(); }
        public void onError(Throwable failure) { body.completeExceptionally(failure); }
        public void onComplete() { body.complete(size); }
    }
    static int bucket(long nanos) { return Math.min(BUCKETS-1,Math.max(0,(int)Math.ceil(Math.log(Math.max(10000,nanos)/10000.0)/LOG_STEP))); }
    static double upperNanos(int bucket) { return 10000*Math.exp(bucket*LOG_STEP); }
    static void mark(AtomicLongArray bits,int index) {
        int word=index>>>6; long mask=1L<<(index&63), previous;
        do { previous=bits.get(word); if((previous&mask)!=0) return; } while(!bits.compareAndSet(word,previous,previous|mask));
    }
    static long cardinality(AtomicLongArray bits) { long n=0; for(int i=0;i<bits.length();i++) n+=Long.bitCount(bits.get(i)); return n; }
    static int[] order(int size,long seed) {
        int[] order=new int[size]; for(int i=0;i<size;i++) order[i]=i;
        Random random=new Random(seed); for(int i=size-1;i>0;i--) { int j=random.nextInt(i+1), value=order[i]; order[i]=order[j]; order[j]=value; }
        return order;
    }
    static final class Phase {
        final HttpClient client; final ExecutorService executor; final List<Entry> entries; final int[] order;
        final Counters overall=new Counters(); final Map<String,Counters> groups=new TreeMap<>();
        final AtomicLong issued=new AtomicLong(); final LongAdder inWindow=new LongAdder();
        final AtomicLongArray indices, requests; final CountDownLatch done; final long start,deadline; final int seconds;
        Phase(HttpClient client,ExecutorService executor,List<Entry> entries,int unique,int concurrency,int seconds,long seed) {
            this.client=client; this.executor=executor; this.entries=entries; this.seconds=seconds; order=order(entries.size(),seed);
            indices=new AtomicLongArray((entries.size()+63)/64); requests=new AtomicLongArray((unique+63)/64);
            for(Entry entry:entries) groups.computeIfAbsent(entry.group,k->new Counters());
            done=new CountDownLatch(concurrency); start=System.nanoTime(); deadline=start+TimeUnit.SECONDS.toNanos(seconds);
        }
        void dispatch() {
            if(System.nanoTime()>=deadline) { done.countDown(); return; }
            long sequence=issued.getAndIncrement(); int index=order[(int)(sequence%order.length)]; Entry entry=entries.get(index);
            mark(indices,index); mark(requests,entry.requestId); long begin=System.nanoTime();
            BodyCounter body=new BodyCounter();
            try {
                client.sendAsync(entry.request,info->body)
                        .orTimeout(entry.request.timeout().orElseThrow().toMillis(),TimeUnit.MILLISECONDS)
                        .whenComplete((response,failure)-> {
                            if(failure!=null) body.cancel(); // Also cover a server that sends headers but stalls its body.
                            finish(entry,begin,response,failure); executor.execute(this::dispatch);
                        });
            } catch(RuntimeException failure) { body.cancel(); finish(entry,begin,null,failure); executor.execute(this::dispatch); }
        }
        void finish(Entry entry,long begin,HttpResponse<Long> response,Throwable failure) {
            long end=System.nanoTime(), elapsed=end-begin; if(end<=deadline) inWindow.increment();
            while(failure instanceof CompletionException && failure.getCause()!=null) failure=failure.getCause();
            String outcome=failure==null?"HTTP_"+response.statusCode():failure.getClass().getSimpleName();
            boolean failed=failure!=null || response.statusCode()<200 || response.statusCode()>=300;
            long bytes=response==null?0:response.body(); overall.record(elapsed,bytes,outcome,failed);
            groups.get(entry.group).record(elapsed,bytes,outcome,failed);
        }
        Map<String,Object> run(int concurrency) throws InterruptedException {
            for(int i=0;i<concurrency;i++) executor.execute(this::dispatch); done.await();
            double wall=(System.nanoTime()-start)/1e9; Map<String,Object> result=overall.report();
            result.put("request_window_seconds",seconds); result.put("wall_seconds_including_drain",wall);
            result.put("issued",issued.get()); result.put("completed_within_window",inWindow.sum());
            result.put("completed_tps_including_drain",overall.count.sum()/wall);
            result.put("successful_tps_including_drain",(overall.count.sum()-overall.errors.sum())/wall);
            result.put("completed_tps_within_window",inWindow.sum()/(double)seconds);
            result.put("distinct_corpus_indices_issued",cardinality(indices)); result.put("distinct_request_paths_issued",cardinality(requests));
            result.put("full_corpus_cycles_issued",issued.get()/order.length); result.put("partial_cycle_entries_issued",issued.get()%order.length);
            Map<String,Object> byGroup=new TreeMap<>(); groups.forEach((k,v)-> { Map<String,Object> report=v.report(); report.put("completed_tps_including_drain",v.count.sum()/wall); report.put("successful_tps_including_drain",(v.count.sum()-v.errors.sum())/wall); byGroup.put(k,report); }); result.put("groups",byGroup);
            if(issued.get()!=overall.count.sum()) throw new IllegalStateException("Incomplete request accounting"); return result;
        }
    }
    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<>(); Set<String> allowed=Set.of("base-url","corpus","concurrency","duration","warmup","seed","output","timeout","accept-encoding","allow-remote","self-test");
        for(int i=0;i<args.length;i++) {
            if(!args[i].startsWith("--") || !allowed.contains(args[i].substring(2))) throw new IllegalArgumentException("Unknown option: "+args[i]);
            String key=args[i].substring(2); if(options.containsKey(key)) throw new IllegalArgumentException("Duplicate option: "+key);
            options.put(key,key.equals("allow-remote")||key.equals("self-test")?"true":args[++i]);
        }
        if(options.containsKey("self-test")) { selfTest(); return; }
        URI base=URI.create(required(options,"base-url")); validateBase(base,options.containsKey("allow-remote"));
        int concurrency=integer(options,"concurrency",500,1,10000), duration=integer(options,"duration",60,1,86400);
        int warmup=integer(options,"warmup",20,0,86400), timeout=integer(options,"timeout",10,1,3600);
        long seed=Long.parseLong(options.getOrDefault("seed","20260911")); int threads=Math.min(32,Math.max(4,Runtime.getRuntime().availableProcessors()*2));
        Path corpus=Paths.get(required(options,"corpus")), output=Paths.get(required(options,"output"));
        String encoding=options.getOrDefault("accept-encoding","identity");
        if(!encoding.equals("identity")&&!encoding.equals("gzip")) throw new IllegalArgumentException("accept-encoding must be identity or gzip");
        byte[] raw=Files.readAllBytes(corpus); List<Entry> entries=new ArrayList<>(); Map<String,Integer> requestIds=new HashMap<>();
        int lineNumber=0; for(String line:new String(raw,StandardCharsets.UTF_8).split("\\R")) {
            lineNumber++; if(line.isBlank() || line.startsWith("#")) continue; String[] fields=line.split("\\t",-1);
            if(fields.length!=2 || fields[0].isBlank()) throw new IllegalArgumentException("Expected group TAB path at line "+lineNumber);
            URI path=URI.create(fields[1]); if(!fields[1].startsWith("/") || path.isAbsolute() || path.getRawAuthority()!=null || path.getRawFragment()!=null) throw new IllegalArgumentException("Expected origin-relative path at line "+lineNumber);
            int id=requestIds.computeIfAbsent(fields[1],k->requestIds.size());
            HttpRequest request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(timeout))
                    .header("Accept","application/json").header("Accept-Encoding",encoding).GET().build();
            entries.add(new Entry(fields[0],id,request));
        }
        if(entries.isEmpty()) throw new IllegalArgumentException("Corpus is empty");
        ExecutorService executor=Executors.newFixedThreadPool(threads); Map<String,Object> result=new LinkedHashMap<>();
        try {
            HttpClient client=HttpClient.newBuilder().executor(executor).version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(timeout)).build();
            result.put("schema_version",1); result.put("started_at_utc",java.time.Instant.now().toString()); result.put("base_url",base.toString()); result.put("java_version",System.getProperty("java.version"));
            result.put("max_heap_bytes",Runtime.getRuntime().maxMemory()); result.put("available_processors",Runtime.getRuntime().availableProcessors()); result.put("client_executor_threads",threads);
            result.put("concurrency",concurrency); result.put("seed",seed); result.put("request_timeout_seconds",timeout);
            result.put("accept_encoding",encoding); result.put("corpus_entries",entries.size()); result.put("distinct_corpus_request_paths",requestIds.size());
            Map<String,Integer> groupSizes=new TreeMap<>(); entries.forEach(entry->groupSizes.merge(entry.group,1,Integer::sum)); result.put("corpus_group_entries",groupSizes);
            result.put("corpus_sha256",hex(MessageDigest.getInstance("SHA-256").digest(raw)));
            result.put("latency_histogram","upper bound; 10us minimum, <=1% relative bucket width above 10us");
            result.put("scheduling","closed loop; seeded Fisher-Yates cycle; phase sequence resets; warmup uses seed XOR 0x5DEECE66D");
            if(warmup>0) result.put("warmup",new Phase(client,executor,entries,requestIds.size(),concurrency,warmup,seed^0x5DEECE66DL).run(concurrency));
            result.put("measurement",new Phase(client,executor,entries,requestIds.size(),concurrency,duration,seed).run(concurrency));
        } finally { executor.shutdown(); executor.awaitTermination(timeout+5L,TimeUnit.SECONDS); }
        Path parent=output.toAbsolutePath().getParent(); Files.createDirectories(parent); Files.writeString(output,json(result)+"\n");
        System.out.println("Result: "+output.toAbsolutePath());
    }
    static void validateBase(URI base,boolean allowRemote) {
        if(base.getHost()==null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getRawUserInfo()!=null || base.getRawQuery()!=null || base.getRawFragment()!=null || !(base.getPath().isEmpty()||base.getPath().equals("/"))) throw new IllegalArgumentException("Expected http(s) origin without credentials/path/query");
        if(!allowRemote && !Set.of("localhost","127.0.0.1","[::1]","::1").contains(base.getHost())) throw new IllegalArgumentException("Remote target requires --allow-remote");
    }
    static String required(Map<String,String> options,String key) { String value=options.get(key); if(value==null) throw new IllegalArgumentException("Missing --"+key); return value; }
    static int integer(Map<String,String> options,String key,int fallback,int min,int max) {
        int value=Integer.parseInt(options.getOrDefault(key,Integer.toString(fallback))); if(value<min||value>max) throw new IllegalArgumentException("Invalid --"+key); return value;
    }
    static String hex(byte[] bytes) { StringBuilder out=new StringBuilder(); for(byte b:bytes) out.append(String.format("%02x",b&255)); return out.toString(); }
    static String json(Object value) {
        if(value==null) return "null"; if(value instanceof Number || value instanceof Boolean) return value.toString();
        if(value instanceof Map) { StringJoiner out=new StringJoiner(",","{","}"); ((Map<?,?>)value).forEach((k,v)->out.add(json(k.toString())+":"+json(v))); return out.toString(); }
        StringBuilder out=new StringBuilder("\""); for(char c:value.toString().toCharArray()) {
            if(c=='"'||c=='\\') out.append('\\').append(c); else if(c<32) out.append(String.format("\\u%04x",(int)c)); else out.append(c);
        } return out.append('"').toString();
    }
    static void selfTest() {
        if(!Arrays.equals(order(100,7),order(100,7)) || Arrays.stream(order(100,7)).distinct().count()!=100) throw new AssertionError("shuffle");
        for(long n:new long[]{10000,12345,1000000,1000000000,3600000000000L}) if(upperNanos(bucket(n))+0.001<n || upperNanos(bucket(n))>n*1.010001) throw new AssertionError("histogram");
        AtomicLongArray bits=new AtomicLongArray(2); mark(bits,0); mark(bits,64); mark(bits,64); if(cardinality(bits)!=2) throw new AssertionError("coverage");
        Counters counters=new Counters(); counters.record(1000000,10,"HTTP_200",false); counters.record(3000000,0,"HttpTimeoutException",true);
        if(counters.count.sum()!=2 || counters.errors.sum()!=1 || counters.percentile(.95)<3) throw new AssertionError("accounting");
        if(!json("a\n\"\\").equals("\"a\\u000a\\\"\\\\\"")) throw new AssertionError("json");
        try { validateBase(URI.create("https://example.com"),false); throw new AssertionError("remote guard"); } catch(IllegalArgumentException expected) { }
        System.out.println("Self-check passed");
    }
}
