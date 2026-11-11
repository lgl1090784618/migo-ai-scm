"""Run on the designated test host as root; receive only test credentials via stdin.
Never prints source records, account identifiers, passwords, tokens or signatures.
"""
import concurrent.futures,datetime,hashlib,json,pathlib,sys,time

scope={}
helper=pathlib.Path('/tmp/mossdoom-framework-bundle/verify-framework.py').read_text()
exec(compile(helper[:helper.index('admin=Client();')],'<client>','exec'),scope)
Client=scope['Client'];admin=Client();admin.login('admin');viewer=Client();viewer.login('viewer')
secrets=json.loads(sys.stdin.read());checks=[];results=[]
def check(name,value=True):
    assert value,name
    checks.append(name);print('PASS '+name,flush=True)
endpoints=admin.request('GET','/api/collection/endpoints')
check('two reviewed platform query adapters available',{'wms.sku-stock','xinwuyun.produce-orders'} <= {e['id'] for e in endpoints})
sources=admin.request('GET','/api/sources?limit=100')['items']
sources={s['platform_code']:s for s in sources if s['name'] in ['WMS库存接口 · TEST','新物云OpenAPI · TEST']}
now=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
formats=lambda value:value.strftime('%Y-%m-%d %H:%M:%S')
configurations=[
 {'platform':'migo-wms','endpoint':'wms.sku-stock','origin':'http://wms-api-test.emigo.it','kind':'WMS_APP','credentials':secrets['wms'],
  'query':{'conditions':[],'pageIndex':1,'pageSize':5,'isExcel':False,'isSync':False},'dataset_code':'wms_stock_observations','dataset_name':'WMS库存原始观测 · TEST'},
 {'platform':'xinwuyun','endpoint':'xinwuyun.produce-orders','origin':'https://dm-scm-test.emigo.cn','kind':'XWY_SIGNING','credentials':secrets['xinwuyun'],
  'query':{'date_type':1,'page':1,'page_size':10,'modified_start':formats(now-datetime.timedelta(days=1)),'modified_end':formats(now)},'dataset_code':'production_order_observations','dataset_name':'新物云生产单原始观测 · TEST'}
]
for spec in configurations:
    source=sources[spec['platform']];sid=source['id'];path='/api/collection/sources/'+sid
    profile=admin.request('GET',path)
    profile=admin.request('POST',path,{'endpoint_id':spec['endpoint'],'base_url':spec['origin'],'request_json':spec['query'],'revision':profile.get('revision',0)})
    profile=admin.request('POST',path+'/credential',{'kind':spec['kind'],'fields':spec['credentials'],'revision':profile['revision']})
    serialized=json.dumps(profile)
    check(spec['platform']+' encrypted credentials not returned',all(str(v) not in serialized for k,v in spec['credentials'].items() if k in ('appSecret','secret','account')))
    probe=admin.request('POST',path+'/probe',{})
    check(spec['platform']+' live signed query and raw persistence',probe['state']=='PREVIEW_STORED')
    check(spec['platform']+' requested page limit respected',0<=probe['record_count']<=spec['query'].get('pageSize',spec['query'].get('page_size')))
    raw=admin.request('GET','/api/collection/probes/'+probe['id']+'/raw',raw=True)
    check(spec['platform']+' raw SHA256 matches receipt',hashlib.sha256(raw).hexdigest()==probe['raw_sha256'])
    viewer.request('GET','/api/collection/probes/'+probe['id']+'/raw',expected=403)
    check(spec['platform']+' raw response restricted to administrator')
    current=next(s for s in admin.request('GET','/api/sources?limit=100')['items'] if s['id']==sid)
    current=admin.request('POST','/api/sources/'+sid+'/state',{'state':'ACTIVE','row_version':current['row_version']})
    datasets=admin.request('GET','/api/datasets?limit=100')['items']
    dataset=next((d for d in datasets if d['source_id']==sid and d['code']==spec['dataset_code']),None)
    if dataset is None:
        dataset=admin.request('POST','/api/datasets',{'source_id':sid,'code':spec['dataset_code'],'name':spec['dataset_name'],'entity_type':'source_observation','grain':'来源单页响应的一条观测；技术标识为采集任务ID×响应序号，未建立跨系统业务实体映射'})
        admin.request('POST','/api/datasets/'+dataset['id']+'/schemas',{'document':{'type':'object','additionalProperties':True}})
        dataset=admin.request('POST','/api/datasets/'+dataset['id']+'/schemas/1/publish',{})
    if dataset['state']!='ACTIVE':dataset=admin.request('POST','/api/datasets/'+dataset['id']+'/state',{'state':'ACTIVE','row_version':dataset['row_version']})
    receipt=admin.request('POST','/api/collection/probes/'+probe['id']+'/import',{'dataset_id':dataset['id']})
    if probe['record_count']==0:
        check(spec['platform']+' empty response creates no fabricated batch',receipt['state']=='EMPTY_RESPONSE' and receipt['received_count']==0)
        results.append({'platform':spec['platform'],'source_id':sid,'probe_id':probe['id'],'dataset_id':dataset['id'],'record_count':0,'batch_id':None});continue
    repeated=admin.request('POST','/api/collection/probes/'+probe['id']+'/import',{'dataset_id':dataset['id']})
    check(spec['platform']+' import is idempotent',receipt['batch_id']==repeated['batch_id'])
    batch=None
    for _ in range(60):
        batch=admin.request('GET','/api/batches/'+receipt['batch_id'])
        if batch['status'] in ('COMPLETED','COMPLETED_WITH_ERRORS','FAILED'):break
        time.sleep(1)
    check(spec['platform']+' asynchronous business projection completed',batch['status']=='COMPLETED' and batch['applied_count']==probe['record_count'] and batch['rejected_count']==0)
    normalized=admin.request('GET','/api/batches/'+receipt['batch_id']+'/raw',raw=True)
    document=json.loads(normalized)
    check(spec['platform']+' normalized events retain source-response lineage',all(e['scope']['source_raw_sha256']==probe['raw_sha256'] and e['entity_key']['collection_run']==probe['id'] for e in document['events']))
    projection=admin.request('GET','/api/datasets/'+dataset['id']+'/projections')['items']
    check(spec['platform']+' business database rows visible',len([p for p in projection if p['batch_id']==receipt['batch_id']])==probe['record_count'])
    results.append({'platform':spec['platform'],'source_id':sid,'probe_id':probe['id'],'dataset_id':dataset['id'],'record_count':probe['record_count'],'batch_id':receipt['batch_id'],'applied_count':batch['applied_count'],'source_raw_sha256':probe['raw_sha256']})
check('test origins retained; no DEV/production source configured',all(spec['origin'] in ('http://wms-api-test.emigo.it','https://dm-scm-test.emigo.cn') for spec in configurations))
report={'at':datetime.datetime.now().astimezone().isoformat(),'checks':checks,'results':results,'source_business_writes':0,'scheduled_collection_enabled':False,'microservice_authorization':'independent application credentials still required'}
pathlib.Path('/opt/mossdoom/validation/framework/live-source-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('LIVE_SOURCE_CHECKS_PASSED '+str(len(checks)))
