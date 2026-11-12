"""Verify dashboard layout isolation between admin and viewer (test environment only).
Creates temporary templates, asserts isolation, then deletes them.
"""
import datetime, http.cookiejar, json, ssl, subprocess, urllib.error, urllib.request

BASE = 'https://192.168.10.154:8443'
SSH = ['ssh','-i','E:/codex-private/credentials/mossdoom/test-154-automation','-o','UserKnownHostsFile=E:/codex-private/credentials/mossdoom-known-hosts','-o','ConnectTimeout=8','migo@192.168.10.154']
context = ssl._create_unverified_context()
checks = []

def check(label, condition):
    if not condition:
        raise AssertionError(label)
    checks.append(label)
    print('PASS ' + label, flush=True)

class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
            urllib.request.HTTPSHandler(context=context))
        self.csrf = None
    def request(self, method, path, body=None, expected=200):
        h = {'Content-Type': 'application/json'}
        if self.csrf and method != 'GET':
            h[self.csrf['headerName']] = self.csrf['token']
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        try:
            with self.opener.open(urllib.request.Request(BASE + path, data=data, headers=h, method=method), timeout=20) as r:
                status = r.status; result = r.read()
        except urllib.error.HTTPError as e:
            status = e.code; result = e.read()
        if status != expected:
            raise AssertionError(f'{method} {path}: expected {expected}, got {status}: ' + result.decode(errors='replace')[:400])
        return json.loads(result) if result else {}
    def login(self, user):
        password = subprocess.check_output(SSH + ['sudo cat /etc/mossdoom/secrets/app_' + user + '_password'], text=True).strip()
        self.csrf = self.request('GET', '/api/auth/csrf')
        self.request('POST', '/api/auth/login', {'username': user, 'password': password})
        self.csrf = self.request('GET', '/api/auth/csrf')
        sess = self.request('GET', '/api/auth/session')
        return sess

LA = [{'id': 'pipeline', 'x': 0, 'y': 0, 'w': 4, 'h': 5},
      {'id': 'chain', 'x': 4, 'y': 0, 'w': 8, 'h': 5},
      {'id': 'batches', 'x': 0, 'y': 5, 'w': 6, 'h': 8}]

suffix = datetime.datetime.now().strftime('%m%d-%H%M%S')
admin_name = 'iso-admin-' + suffix
viewer_name = 'iso-viewer-' + suffix

admin = Client(); viewer = Client()
check('admin login', admin.login('admin').get('role') == 'ADMIN')
check('viewer login', viewer.login('viewer').get('role') == 'VIEWER')

admin.request('PUT', '/api/dashboards/overview', {'name': admin_name, 'layout': LA})
viewer.request('PUT', '/api/dashboards/overview', {'name': viewer_name, 'layout': LA})

a = admin.request('GET', '/api/dashboards/overview')
v = viewer.request('GET', '/api/dashboards/overview')
a_names = [t['name'] for t in a['templates']]
v_names = [t['name'] for t in v['templates']]
check('admin sees own template', admin_name in a_names)
check('admin does NOT see viewer template', viewer_name not in a_names)
check('viewer sees own template', viewer_name in v_names)
check('viewer does NOT see admin template', admin_name not in v_names)
check('owner reported per user', a.get('owner') == 'admin' and v.get('owner') == 'viewer')

# same name, different owners must not collide
both = 'iso-shared-' + suffix
admin.request('PUT', '/api/dashboards/overview', {'name': both, 'layout': LA})
viewer.request('PUT', '/api/dashboards/overview', {'name': both, 'layout': LA[:2]})
a2 = {t['name']: t['layout'] for t in admin.request('GET', '/api/dashboards/overview')['templates']}
v2 = {t['name']: t['layout'] for t in viewer.request('GET', '/api/dashboards/overview')['templates']}
check('same template name isolated per owner', len(a2[both]) == 3 and len(v2[both]) == 2)

# invalid layout rejected for both roles
bad = [{'id': 'pipeline', 'x': 10, 'y': 0, 'w': 6, 'h': 5}]
admin.request('PUT', '/api/dashboards/overview', {'name': 'iso-bad-' + suffix, 'layout': bad}, expected=400)
viewer.request('PUT', '/api/dashboards/overview', {'name': 'iso-bad-v-' + suffix, 'layout': bad}, expected=400)
check('out-of-grid layout rejected for both roles', True)

# unauthenticated access denied
anon = Client()
anon.csrf = anon.request('GET', '/api/auth/csrf')
anon.request('GET', '/api/dashboards/overview', expected=401)
check('anonymous cannot read layouts', True)

# cleanup (iso-bad-* were rejected so they never existed; sweep any leftover iso-* from earlier runs)
for client in (admin, viewer):
    for name in [t['name'] for t in client.request('GET', '/api/dashboards/overview')['templates'] if t['name'].startswith('iso-')]:
        client.request('DELETE', f'/api/dashboards/overview/{name}', expected=200)
a3 = [t['name'] for t in admin.request('GET', '/api/dashboards/overview')['templates']]
v3 = [t['name'] for t in viewer.request('GET', '/api/dashboards/overview')['templates']]
check('templates removed after cleanup', not any(n.startswith('iso-') for n in a3 + v3))

print('ALL PASS ' + str(len(checks)))
