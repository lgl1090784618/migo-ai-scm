"""Exercise the designated test deployment. Credentials remain only in memory.
Creates clearly labelled acceptance fixtures; does not contact source platforms.
Usage: python verify-framework.py <report-directory>
"""
import concurrent.futures, copy, datetime, http.cookiejar, json, pathlib, ssl, subprocess, sys, time, urllib.error, urllib.request, uuid

BASE = 'https://192.168.10.154:8443'
SSH = ['ssh','-i','E:/codex-private/credentials/mossdoom/test-154-automation','-o','UserKnownHostsFile=E:/codex-private/credentials/mossdoom-known-hosts','-o','ConnectTimeout=8','migo@192.168.10.154']
context = ssl._create_unverified_context()  # Only this fixed private test host, self-signed certificate.
checks=[]
def check(label, condition):
    if not condition: raise AssertionError(label)
    checks.append(label); print('PASS '+label, flush=True)
class Client:
    def __init__(self):
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),urllib.request.HTTPSHandler(context=context));self.csrf=None
    def request(self,method,path,body=None,headers=None,expected=200,raw=False):
        h={'Content-Type':'application/json'}
        if self.csrf and method!='GET':h[self.csrf['headerName']]=self.csrf['token']
        h.update(headers or {})
        data=body if isinstance(body,bytes) else None if body is None else json.dumps(body,ensure_ascii=False).encode()
        for attempt in range(3):
            try:
                with self.opener.open(urllib.request.Request(BASE+path,data=data,headers=h,method=method),timeout=15) as r:status=r.status;result=r.read()
                break
            except urllib.error.HTTPError as e:status=e.code;result=e.read();break
            except (urllib.error.URLError,TimeoutError):
                if attempt==2 or (method!='GET' and 'Idempotency-Key' not in h):raise
                time.sleep(1)
        if status!=expected:raise AssertionError(f'{method} {path}: expected {expected}, got {status}: '+result.decode(errors='replace')[:500])
        return result if raw else json.loads(result)
    def login(self,user):
        password=(pathlib.Path('/etc/mossdoom/secrets/app_'+user+'_password').read_text() if sys.platform.startswith('linux') else subprocess.check_output(SSH+['sudo cat /etc/mossdoom/secrets/app_'+user+'_password'],text=True)).strip()
        self.csrf=self.request('GET','/api/auth/csrf')
        self.request('POST','/api/auth/login',{'username':user,'password':password})
        self.csrf=self.request('GET','/api/auth/csrf')

admin=Client();anon=Client();viewer=Client();collector=Client()
anon.request('GET','/api/overview',expected=401);check('anonymous management access denied',True)
admin.login('admin');viewer.login('viewer');check('independent administrator and viewer login',True)
overview=admin.request('GET','/api/overview');check('overview statistics and seven-day trend load',len(overview['daily'])==7)
for endpoint in ['sources','datasets','batches','jobs','quarantine','audits']:
    admin.request('GET','/api/'+endpoint)
check('all management list endpoints load',True)
viewer.request('POST','/api/platforms',{'code':'forbidden','name':'forbidden','category':'TEST'},expected=403)
no_csrf=admin.csrf;admin.csrf=None
admin.request('POST','/api/platforms',{},expected=403);admin.csrf=no_csrf
admin.request('POST','/ingestion/v1/batches',{},expected=401);check('viewer mutation, missing CSRF and session-only ingestion denied',True)
suffix=datetime.datetime.now().strftime('%m%d-%H%M%S')
platform=admin.request('GET','/api/platforms')[0]
source=admin.request('POST','/api/sources',{'platform_id':platform['id'],'name':'框架验收-'+suffix,'mode':'PUSH','connector_type':'AGENT','environment':'test','config_json':{'notes':'仅为框架验收，不连接真实平台'}})
source=admin.request('POST',f"/api/sources/{source['id']}/state",{'state':'ACTIVE','row_version':source['row_version']})
dataset=admin.request('POST','/api/datasets',{'source_id':source['id'],'code':'acceptance-'+suffix,'name':'框架验收数据-'+suffix,'entity_type':'acceptance_event','grain':'每条合成事件，不代表真实商品或库存'})
dataset_id=dataset['id'];source_id=source['id']
admin.request('POST',f'/api/datasets/{dataset_id}/state',{'state':'ACTIVE','row_version':dataset['row_version']},expected=409)
schema={'type':'object','properties':{'sku':{'type':'string'},'quantity':{'type':'integer','minimum':0},'price':{'type':'string'}},'required':['sku','quantity','price']}
version=admin.request('POST',f'/api/datasets/{dataset_id}/schemas',{'document':schema})
dataset=admin.request('POST',f'/api/datasets/{dataset_id}/schemas/1/publish',{})
admin.request('POST',f'/api/datasets/{dataset_id}/schemas/1/publish',{},expected=409)
mapping={'fields':{'sku':'/sku','quantity':'/quantity','amount':'/price'},'types':{'amount':'decimal','quantity':'integer'}}
admin.request('POST',f'/api/datasets/{dataset_id}/mappings',{'document':mapping})
dataset=admin.request('POST',f'/api/datasets/{dataset_id}/mappings/1/publish',{})
dataset=admin.request('POST',f'/api/datasets/{dataset_id}/state',{'state':'ACTIVE','row_version':dataset['row_version']})
check('dataset requires published schema; versions publish once; explicit activation',True)
identity=admin.request('POST',f'/api/datasets/{dataset_id}/clients',{'name':'验收临时身份-'+suffix})
token=identity['token'];headers={'Authorization':'Bearer '+token}
collector.request('GET','/api/overview',headers=headers,expected=401)
check('collector cannot use management API',True)
def event(name,quantity=3):
    return {'specversion':'1.0','id':name,'source':'urn:mossdoom:source:'+source_id,'type':'acceptance.snapshot','time':'2026-11-10T09:00:00Z','operation':'upsert','entity_key':{'sku':'000123'},'data':{'sku':'000123','quantity':quantity,'price':'9007199254740993.123456789012'}}
def body(events):return {'protocol_version':'1.0','source_instance_id':source_id,'dataset_id':dataset_id,'schema_version':1,'events':events}
def submit(payload,key=None):
    return collector.request('POST','/ingestion/v1/batches',payload,{**headers,'Idempotency-Key':key or str(uuid.uuid4())},202)
def wait(batch):
    for _ in range(60):
        b=collector.request('GET','/ingestion/v1/batches/'+batch['batch_id'],headers=headers)
        if b['processing_state'] in ('COMPLETED','COMPLETED_WITH_ERRORS','FAILED'):return b
        time.sleep(1)
    raise AssertionError('worker timeout '+batch['batch_id'])
original=json.dumps(body([event('ok-'+suffix)]),ensure_ascii=False,indent=2).encode()
key=str(uuid.uuid4());receipt=submit(original,key)
check('202 means durable receipt',receipt['receipt_state']=='DURABLY_RECEIVED')
repeated=submit(original,key);check('idempotent retry returns same receipt',receipt['batch_id']==repeated['batch_id'])
conflict=body([event('different-'+suffix)])
collector.request('POST','/ingestion/v1/batches',conflict,{**headers,'Idempotency-Key':key},409)
completed=wait(receipt);check('raw-to-business asynchronous processing',completed['applied_count']==1 and completed['processing_state']=='COMPLETED')
raw=admin.request('GET','/api/batches/'+receipt['batch_id']+'/raw',raw=True);check('stored raw bytes match original request exactly',raw==original)
projection=admin.request('GET',f'/api/datasets/{dataset_id}/projections')['items'][0]
check('decimal precision and zero-prefixed business identifier preserved','9007199254740993.123456789012' in projection['payload_text'] and projection['payload']['sku']=='000123')
duplicate=wait(submit(original));check('cross-batch event duplicate does not write again',duplicate['duplicate_count']==1 and duplicate['applied_count']==0)
bad=body([event('ok-'+suffix,9)]);bad_result=wait(submit(bad));check('same event ID with different payload is quarantined',bad_result['rejected_count']==1)
mixed=wait(submit(body([event('valid-'+suffix),event('invalid-'+suffix,'bad')])));check('mixed batch retains valid row and quarantines schema failure',mixed['applied_count']==1 and mixed['rejected_count']==1)
concurrent_body=body([event('race-'+suffix)]);racekey=str(uuid.uuid4())
def race(_):return Client().request('POST','/ingestion/v1/batches',concurrent_body,{**headers,'Idempotency-Key':racekey},202)['batch_id']
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:ids=list(pool.map(race,range(4)))
check('concurrent idempotent submissions create one batch',len(set(ids))==1)
wait({'batch_id':ids[0]})
check('quarantine and audit views contain actual results',len(admin.request('GET','/api/quarantine')['items'])>=2 and len(admin.request('GET','/api/audits')['items'])>0)
page=admin.request('GET','/api/batches?limit=2');page2=admin.request('GET','/api/batches?limit=2&cursor='+urllib.parse.quote(page['nextCursor']))
check('cursor pages have no repeated batch IDs',not ({r['id'] for r in page['items']}&{r['id'] for r in page2['items']}))
viewer.request('GET','/api/batches/'+receipt['batch_id']+'/raw',expected=403)
admin.request('POST',f"/api/clients/{identity['id']}/disable",{})
collector.request('GET','/ingestion/v1/batches/'+receipt['batch_id'],headers=headers,expected=401)
check('raw content restricted and collector revocation immediate',True)
source=admin.request('POST',f'/api/sources/{source_id}/state',{'state':'PAUSED','row_version':source['row_version']})
check('acceptance source paused after verification',source['state']=='PAUSED')
# Pause/revoke only this script's explicitly labelled acceptance fixtures left by interrupted runs.
for old in admin.request('GET','/api/sources?q='+urllib.parse.quote('框架验收-')+'&limit=100')['items']:
    if not old['name'].startswith('框架验收-') or old['config_json'].get('notes')!='仅为框架验收，不连接真实平台':continue
    for d in admin.request('GET','/api/datasets?q='+urllib.parse.quote(old['name'])+'&limit=100')['items']:
        detail=admin.request('GET','/api/datasets/'+d['id'])
        for c in detail['clients']:
            if c['enabled'] and c['name'].startswith('验收临时身份-'):admin.request('POST','/api/clients/'+c['id']+'/disable',{})
    if old['state']!='PAUSED':admin.request('POST','/api/sources/'+old['id']+'/state',{'state':'PAUSED','row_version':old['row_version']})
out=pathlib.Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
report={'at':datetime.datetime.now().astimezone().isoformat(),'target':BASE,'checks':checks,'source_id':source_id,'dataset_id':dataset_id,'first_batch_id':receipt['batch_id'],'test_fixture_state':'source paused, temporary collector revoked','real_source_calls':0}
(out/'framework-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('ALL_CHECKS_PASSED '+str(len(checks)))
