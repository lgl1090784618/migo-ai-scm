import json,pathlib
scope={};source=pathlib.Path('/tmp/mossdoom-framework-bundle/verify-framework.py').read_text()
exec(compile(source[:source.index('admin=Client();')],'<client>','exec'),scope)
client=scope['Client']();client.login('admin')
sources=client.request('GET','/api/sources?limit=100')['items']
wms=next(s for s in sources if s['name']=='WMS库存接口 · TEST')
xwy=next(s for s in sources if s['name']=='新物云OpenAPI · TEST')
p=client.request('GET','/api/collection/sources/'+wms['id'])
client.request('POST','/api/collection/sources/'+xwy['id'],{'endpoint_id':'wms.sku-stock','base_url':p['base_url'],'request_json':p['request_json'],'revision':0},expected=400)
print('PASS cross-platform endpoint configuration rejected')
run=client.request('POST','/api/collection/sources/'+wms['id']+'/probe',{})
assert run['state']=='PREVIEW_STORED'
snapshot=client.request('GET','/api/collection/probes/'+run['id'])
assert snapshot['request_snapshot']==p['request_json'] and snapshot['target_origin']==p['base_url'] and snapshot['authentication_kind']=='WMS_APP'
assert 'credential_cipher' not in snapshot and 'sign' not in snapshot and 'token' not in snapshot
print('PASS immutable non-secret request snapshot captured')
out={'checks':['cross-platform endpoint rejected','request and origin snapshot persisted','no secret or token returned'],'probe_id':run['id'],'record_count':run['record_count']}
pathlib.Path('/opt/mossdoom/validation/framework/query-snapshot-verification.json').write_text(json.dumps(out,indent=2))
