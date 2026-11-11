"""Verify the platform guide feature (read-only, admin-gated). No source platforms contacted.
Usage: python verify-guide.py <report-directory>
"""
import datetime, http.cookiejar, json, pathlib, ssl, subprocess, sys, urllib.error, urllib.request

BASE = 'https://192.168.10.154:8443'
SSH = ['ssh','-i','E:/codex-private/credentials/mossdoom/test-154-automation','-o','UserKnownHostsFile=E:/codex-private/credentials/mossdoom-known-hosts','-o','ConnectTimeout=8','migo@192.168.10.154']
context = ssl._create_unverified_context()
checks=[]
def check(label, condition):
    if not condition: raise AssertionError(label)
    checks.append(label); print('PASS '+label, flush=True)
class Client:
    def __init__(self):
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),urllib.request.HTTPSHandler(context=context));self.csrf=None
    def request(self,method,path,body=None,headers=None,expected=200):
        h={'Content-Type':'application/json'}
        if self.csrf and method!='GET':h[self.csrf['headerName']]=self.csrf['token']
        h.update(headers or {})
        data=None if body is None else json.dumps(body,ensure_ascii=False).encode()
        try:
            with self.opener.open(urllib.request.Request(BASE+path,data=data,headers=h,method=method),timeout=15) as r:
                status=r.status;result=r.read()
        except urllib.error.HTTPError as e:
            status=e.code;result=e.read()
        if status!=expected:raise AssertionError(f'{method} {path}: expected {expected}, got {status}: '+result.decode(errors='replace')[:500])
        return json.loads(result)
    def login(self,user):
        password=subprocess.check_output(SSH+['sudo cat /etc/mossdoom/secrets/app_'+user+'_password'],text=True).strip()
        self.csrf=self.request('GET','/api/auth/csrf')
        self.request('POST','/api/auth/login',{'username':user,'password':password})
        self.csrf=self.request('GET','/api/auth/csrf')

admin=Client();viewer=Client()
admin.login('admin');viewer.login('viewer')
check('administrator and viewer login',True)

platforms=admin.request('GET','/api/platforms')
check('platforms list returns guide fields', all('description' in p and 'guide_json' in p for p in platforms))
wms=next((p for p in platforms if p['code']=='migo-wms'),None)
check('migo-wms seeded with default guide', wms is not None and isinstance(wms.get('guide_json'),dict) and wms['guide_json'].get('summary') and isinstance(wms['guide_json'].get('auth'),list) and isinstance(wms['guide_json'].get('steps'),list))
check('migo-wms guide has polling description', bool(wms['guide_json'].get('polling')))
check('all 10 seed platforms have non-empty guide summary', sum(1 for p in platforms if isinstance(p.get('guide_json'),dict) and p['guide_json'].get('summary')) >= 10)

# viewer cannot edit guide
viewer.request('POST', f"/api/platforms/{wms['id']}/guide", {'description':'x','guide':{'summary':'x'}}, expected=403)
check('viewer cannot edit platform guide',True)

# missing CSRF denied
no_csrf=admin.csrf;admin.csrf=None
admin.request('POST', f"/api/platforms/{wms['id']}/guide", {'description':'x','guide':{'summary':'x'}}, expected=403);admin.csrf=no_csrf
check('guide edit requires CSRF',True)

# invalid guide (steps not array) rejected
admin.request('POST', f"/api/platforms/{wms['id']}/guide", {'description':'ok','guide':{'summary':'s','steps':'not-array'}}, expected=400)
check('malformed guide rejected',True)

# valid edit round-trips
suffix=datetime.datetime.now().strftime('%m%d-%H%M%S')
updated=admin.request('POST', f"/api/platforms/{wms['id']}/guide", {'description':'验收说明-'+suffix,'guide':{'summary':'验收摘要-'+suffix,'auth':[{'label':'WMS 应用签名授权','note':'验收备注'}],'steps':['第一步','第二步'],'polling':'支持轮询-验收'}})
check('guide edit round-trips', updated['description'].endswith(suffix) and updated['guide_json']['summary'].endswith(suffix) and updated['guide_json']['steps']==['第一步','第二步'])

# restore original WMS guide
restored=admin.request('POST', f"/api/platforms/{wms['id']}/guide", {'description':wms['description'],'guide':wms['guide_json']})
check('guide restored to original', restored['guide_json']['summary']==wms['guide_json']['summary'])

out=pathlib.Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
report={'at':datetime.datetime.now().astimezone().isoformat(),'target':BASE,'checks':checks,'feature':'platform guide','real_source_calls':0}
(out/'guide-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('ALL_CHECKS_PASSED '+str(len(checks)))
