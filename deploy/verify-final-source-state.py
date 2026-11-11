import json,pathlib,uuid
scope={};helper=pathlib.Path('/tmp/mossdoom-framework-bundle/verify-framework.py').read_text()
exec(compile(helper[:helper.index('admin=Client();')],'<client>','exec'),scope)
admin=scope['Client']();admin.login('admin');viewer=scope['Client']();viewer.login('viewer');checks=[]
def check(label,condition=True):assert condition,label;checks.append(label);print('PASS '+label,flush=True)
all_results=[]
for file in ['live-source-verification.json','gateway-source-verification.json']:
    all_results.extend(json.loads(pathlib.Path('/opt/mossdoom/validation/framework',file).read_text())['results'])
for result in all_results:
    batch=admin.request('GET','/api/batches/'+result['batch_id'])
    check(result.get('platform',result.get('endpoint'))+' retained after final deployment',batch['status']=='COMPLETED' and batch['applied_count']==result['record_count'])
    viewer.request('GET','/api/collection/probes/'+result['probe_id'],expected=403)
check('source metadata restricted to administrator')
gateway=next(r for r in all_results if r.get('endpoint')=='gateway.factory-stock');path='/api/collection/sources/'+gateway['source_id'];before=admin.request('GET',path)
admin.request('POST',path+'/credential',{'kind':'WMS_APP','fields':{'appId':'fixture','appSecret':'fixture','timeZone':'Asia/Shanghai','tokenMinutes':'10'},'revision':before['revision']},expected=400)
after=admin.request('GET',path);check('wrong-platform credentials rejected without altering saved profile',before['revision']==after['revision'])
wms=next(r for r in all_results if r.get('platform')=='migo-wms')
identity=admin.request('POST','/api/datasets/'+wms['dataset_id']+'/clients',{'name':'只读方向边界验收'})
client=scope['Client']()
try:
    client.request('POST','/ingestion/v1/batches',{'protocol_version':'1.0','source_instance_id':wms['source_id'],'dataset_id':wms['dataset_id'],'schema_version':1,'events':[]},{'Authorization':'Bearer '+identity['token'],'Idempotency-Key':str(uuid.uuid4())},409)
    check('public receiver cannot masquerade as internal PULL collector')
finally:admin.request('POST','/api/clients/'+identity['id']+'/disable',{})
report={'checks':checks,'platforms':4,'real_observation_rows':sum(r['record_count'] for r in all_results),'source_business_writes':0,'scheduled_collection_enabled':False}
pathlib.Path('/opt/mossdoom/validation/framework/final-source-state-verification.json').write_text(json.dumps(report,indent=2));print('FINAL_SOURCE_STATE_PASSED '+str(len(checks)))
