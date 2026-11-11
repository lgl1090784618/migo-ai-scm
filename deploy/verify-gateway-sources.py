"""Use the normally obtained test gateway token supplied through stdin; never print it."""
import datetime,hashlib,json,pathlib,sys,time
scope={};helper=pathlib.Path('/tmp/mossdoom-framework-bundle/verify-framework.py').read_text()
exec(compile(helper[:helper.index('admin=Client();')],'<client>','exec'),scope)
admin=scope['Client']();admin.login('admin');token=sys.stdin.read().strip();assert len(token)>40
checks=[];results=[]
def check(name,value=True):assert value,name;checks.append(name);print('PASS '+name,flush=True)
sources=admin.request('GET','/api/sources?limit=100')['items']
cases=[('协同微服务接口 · TEST','gateway.factory-stock','factory_stock_observations','协同工厂库存原始观测 · TEST'),('TMS微服务接口 · TEST','gateway.fba-stock','logistics_fba_observations','物流FBA库存原始观测 · TEST')]
for name,endpoint,code,dataset_name in cases:
    source=next(s for s in sources if s['name']==name);sid=source['id'];path='/api/collection/sources/'+sid
    profile=admin.request('GET',path)
    profile=admin.request('POST',path,{'endpoint_id':endpoint,'base_url':'http://gateway-test.emigo.it','request_json':{'pageNum':1,'pageSize':5},'revision':profile.get('revision',0)})
    profile=admin.request('POST',path+'/credential',{'kind':'BEARER','fields':{'token':token},'revision':profile['revision']})
    check(endpoint+' token not returned',token not in json.dumps(profile) and profile['credential_configured'])
    run=admin.request('POST',path+'/probe',{})
    check(endpoint+' source query and raw persistence',run['state']=='PREVIEW_STORED' and 0<=run['record_count']<=5)
    raw=admin.request('GET','/api/collection/probes/'+run['id']+'/raw',raw=True)
    check(endpoint+' source raw SHA256 matches',hashlib.sha256(raw).hexdigest()==run['raw_sha256'])
    metadata=admin.request('GET','/api/collection/probes/'+run['id'])
    check(endpoint+' non-secret request configuration pinned',metadata['request_snapshot']=={'pageNum':1,'pageSize':5} and metadata['target_origin']=='http://gateway-test.emigo.it' and 'token' not in metadata)
    current=next(s for s in admin.request('GET','/api/sources?limit=100')['items'] if s['id']==sid)
    admin.request('POST','/api/sources/'+sid+'/state',{'state':'ACTIVE','row_version':current['row_version']})
    datasets=admin.request('GET','/api/datasets?limit=100')['items']
    dataset=next((d for d in datasets if d['source_id']==sid and d['code']==code),None)
    if dataset is None:
        dataset=admin.request('POST','/api/datasets',{'source_id':sid,'code':code,'name':dataset_name,'entity_type':'source_observation','grain':'来源单页响应的一条观测；技术标识为采集任务ID×响应序号，未建立跨系统业务实体映射'})
        admin.request('POST','/api/datasets/'+dataset['id']+'/schemas',{'document':{'type':'object','additionalProperties':True}})
        dataset=admin.request('POST','/api/datasets/'+dataset['id']+'/schemas/1/publish',{})
    if dataset['state']!='ACTIVE':dataset=admin.request('POST','/api/datasets/'+dataset['id']+'/state',{'state':'ACTIVE','row_version':dataset['row_version']})
    receipt=admin.request('POST','/api/collection/probes/'+run['id']+'/import',{'dataset_id':dataset['id']})
    if run['record_count']==0:
        check(endpoint+' empty response has no fabricated events',receipt['state']=='EMPTY_RESPONSE');results.append({'endpoint':endpoint,'records':0,'probe_id':run['id']});continue
    repeated=admin.request('POST','/api/collection/probes/'+run['id']+'/import',{'dataset_id':dataset['id']})
    check(endpoint+' importing same response returns same batch',receipt['batch_id']==repeated['batch_id'])
    for _ in range(60):
        batch=admin.request('GET','/api/batches/'+receipt['batch_id'])
        if batch['status'] in ('COMPLETED','COMPLETED_WITH_ERRORS','FAILED'):break
        time.sleep(1)
    check(endpoint+' business projection completed',batch['status']=='COMPLETED' and batch['applied_count']==run['record_count'])
    projected=admin.request('GET','/api/datasets/'+dataset['id']+'/projections')['items']
    check(endpoint+' actual business-database records visible',len([p for p in projected if p['batch_id']==receipt['batch_id']])==run['record_count'])
    results.append({'endpoint':endpoint,'source_id':sid,'probe_id':run['id'],'dataset_id':dataset['id'],'batch_id':receipt['batch_id'],'record_count':run['record_count'],'applied_count':batch['applied_count'],'source_raw_sha256':run['raw_sha256']})
report={'at':datetime.datetime.now().astimezone().isoformat(),'checks':checks,'results':results,'authentication':'normal username/password login, MD5 input per existing frontend, CAPTCHA supplied by user; JWT obtained from auth-service','source_business_writes':0,'scheduled_collection_enabled':False}
pathlib.Path('/opt/mossdoom/validation/framework/gateway-source-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('GATEWAY_SOURCE_CHECKS_PASSED '+str(len(checks)))
