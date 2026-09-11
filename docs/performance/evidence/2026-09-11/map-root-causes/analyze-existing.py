from pathlib import Path
import datetime,json,math,re,sys
if len(sys.argv) != 3: raise SystemExit('Usage: python3 analyze-existing.py RAW_CORRECTED_DIRECTORY OUTPUT_DIRECTORY')
OLD=Path(sys.argv[1]);OUT=Path(sys.argv[2]);OUT.mkdir(parents=True,exist_ok=True)
def parse(lines):
 rows=[]
 for line in lines:
  if not line or line.startswith('#'):continue
  k,v=line.rsplit(' ',1);v=float(v)
  if math.isfinite(v):rows.append((k.split('{')[0],dict(re.findall(r'(\w+)="([^"\\]*)"',k)),v))
 return rows
def get(rows,name,**labels):return sum(v for n,ls,v in rows if n==name and all(ls.get(k)==x for k,x in labels.items()))
def scope(samples):
 assert len(samples)>=2
 duration=samples[-1][0]-samples[0][0]
 def gauge(name,**labels):
  vals=[get(ms,name,**labels) for t,ms in samples];mean=sum((vals[i]+vals[i+1])/2*(samples[i+1][0]-samples[i][0]) for i in range(len(vals)-1))/duration
  return dict(min=min(vals),max=max(vals),time_weighted_mean=mean,sampled_values=vals)
 def delta(name,**labels):return get(samples[-1][1],name,**labels)-get(samples[0][1],name,**labels)
 result={'sample_count':len(samples),'first_epoch':samples[0][0],'last_epoch':samples[-1][0],'counter_window_seconds':duration,'process_cpu_mean_cores_from_counter':delta('process_cpu_time_ns_total')/1e9/duration,'process_cpu_load_fraction':gauge('process_cpu_usage'),'host_cpu_load_fraction':gauge('system_cpu_usage'),'heap_used_bytes':gauge('jvm_memory_used_bytes',area='heap'),'old_gen_used_bytes':gauge('jvm_memory_used_bytes',area='heap',id='G1 Old Gen'),'gc_pause_count':delta('jvm_gc_pause_seconds_count'),'gc_pause_sum_seconds':delta('jvm_gc_pause_seconds_sum'),'gc_pause_fraction_of_wall':delta('jvm_gc_pause_seconds_sum')/duration,'snapshot_hits':delta('map_cluster_snapshot_requests_total',result='hit'),'snapshot_fallbacks':delta('map_cluster_snapshot_requests_total',result='fallback'),'snapshot_failures':delta('map_cluster_snapshot_failures_total'),'pools':{}}
 for pool in ('replica-pool','source-pool'):
  d={kind:gauge('hikaricp_connections_'+kind,pool=pool) for kind in ('active','pending','idle','max')}
  for kind in ('usage','acquire'):
   count=delta('hikaricp_connections_'+kind+'_seconds_count',pool=pool);seconds=delta('hikaricp_connections_'+kind+'_seconds_sum',pool=pool);d[kind]={'count':count,'sum_seconds':seconds,'mean_ms':seconds/count*1000 if count else None,'operations_per_second':count/duration,'concurrent_operations_from_duration':seconds/duration}
  d['timeouts']=delta('hikaricp_connections_timeout_total',pool=pool);result['pools'][pool]=d
 return result
uris={'clusters':['cluster5','cluster7','cluster10'],'preview':['preview'],'keyword':['keyword'],'nearby':['nearby']}
paths={'clusters':'/api/v1/maps/stores/in-view/clusters','preview':'/api/v1/maps/stores/in-view/previews/compact','keyword':'/api/v1/maps/nearby/search/previews/compact','nearby':'/api/v1/maps/nearby/previews/compact'}
result={'method':'Read existing corrected500 runs only. Interior window is conservatively inside inferred measurement: started_at+warmupWall+1s through +measurementDuration-1s. Runner lacks exact phase wall timestamps; first/last retained samples define actual counter window. Gauges trapezoid time weighted, sampling roughly2s+scrape delay. HTTP comparisons use matching warmup+measurement counts.','trials':[]}
for i in (1,2,3):
 r=json.loads((OLD/f'corrected-{i}.json').read_text());rows=[json.loads(l) for l in (OLD/f'corrected-{i}-metrics.jsonl').read_text().splitlines()];samples=[(x['time'],parse(x['metrics'])) for x in rows if 'metrics' in x]
 t=datetime.datetime.fromisoformat(r['started_at_utc'].replace('Z','+00:00')).timestamp();lo=t+r['warmup']['wall_seconds_including_drain']+1;hi=t+r['warmup']['wall_seconds_including_drain']+r['measurement']['request_window_seconds']-1
 before=parse((OLD/f'corrected-{i}-before.prom').read_text().splitlines());after=parse((OLD/f'corrected-{i}-after.prom').read_text().splitlines());http={}
 for group,gs in uris.items():
  count=sum(r[phase]['groups'][g]['completed'] for phase in ('warmup','measurement') for g in gs);client_ms=sum(r[phase]['groups'][g]['completed']*r[phase]['groups'][g]['mean_ms'] for phase in ('warmup','measurement') for g in gs)/count
  labels=dict(uri=paths[group],method='GET',status='200');sc=get(after,'http_server_requests_seconds_count',**labels)-get(before,'http_server_requests_seconds_count',**labels);ss=get(after,'http_server_requests_seconds_sum',**labels)-get(before,'http_server_requests_seconds_sum',**labels);assert sc==count
  http[group]={'matched_count':count,'client_mean_ms':client_ms,'spring_observed_mean_ms':ss/sc*1000,'outside_spring_observation_mean_ms':client_ms-ss/sc*1000}
 m=r['measurement'];result['trials'].append({'trial':i,'full_sample_window':scope(samples),'measurement_interior':scope([x for x in samples if lo<=x[0]<=hi]),'http_full_warmup_and_measurement':http,'closed_loop':{'concurrency':r['concurrency'],'tps':m['successful_tps_including_drain'],'mean_ms':m['mean_ms'],'tps_times_mean_seconds':m['successful_tps_including_drain']*m['mean_ms']/1000,'note':'Little-law-style consistency check; finite window/drain/client scheduling means slightly below500. TPS and mean at fixed concurrency are coupled, not independent evidence.'},'scrape_intervals_seconds':[samples[j+1][0]-samples[j][0] for j in range(len(samples)-1)],'full_prom_counters':{'gc_allocated_bytes':get(after,'jvm_gc_memory_allocated_bytes_total')-get(before,'jvm_gc_memory_allocated_bytes_total'),'gc_promoted_bytes':get(after,'jvm_gc_memory_promoted_bytes_total')-get(before,'jvm_gc_memory_promoted_bytes_total'),'heap_max_bytes':get(after,'jvm_gc_max_data_size_bytes')}})
result['limits']=['Host CPU includes API, PostgreSQL/Redis Docker VM,Java load generator and unrelated host work; processCPU gauge/counter only isolates API. Cannot assign remaining CPU without more measurement.','HTTP client minus Spring timer includes servlet/container queue, client scheduling,network/socket transfer and instrumentation differences; not a direct Tomcat queue timer.','Snapshot hit counter proves use of hit path; DB-free hit behavior still relies on source contract, not endpoint-tagged DB trace.','GC pause sums observed small do not prove allocation cost or long-term memory leak absent.','No Tomcat busy/max/queue metrics were captured in periodic samples; a thread dump or additional metrics is needed to confirm exact servlet-thread contention.']
(OUT/'existing-500-summary.json').write_text(json.dumps(result,indent=2)+'\n')
for t in result['trials']:
 w=t['measurement_interior'];p=w['pools']['replica-pool'];print(t['trial'],{'samples':w['sample_count'],'seconds':w['counter_window_seconds'],'api_cores':w['process_cpu_mean_cores_from_counter'],'host_cpu':w['host_cpu_load_fraction']['time_weighted_mean'],'heap_MiB':[w['heap_used_bytes'][k]/1048576 for k in ('min','max')],'gc_count':w['gc_pause_count'],'gc_seconds':w['gc_pause_sum_seconds'],'active':p['active']['time_weighted_mean'],'pending':p['pending']['time_weighted_mean'],'acquire_ms':p['acquire']['mean_ms'],'hold_ms':p['usage']['mean_ms'],'snapshot_hit':w['snapshot_hits'],'fallback':w['snapshot_fallbacks'],'L':t['closed_loop']['tps_times_mean_seconds']})
