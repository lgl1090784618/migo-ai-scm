"""Verify real framework snapshots in an already restored, isolated environment.
Never calls a source platform. Only the validation containers are started/stopped.
"""
import hashlib,hmac,json,pathlib,subprocess,sys,time,uuid

if len(sys.argv)>1 and sys.argv[1]=='--inside':
    target=pathlib.Path(sys.argv[2]);objects=json.load(sys.stdin)
    text=pathlib.Path('smoke.py').read_text();scope={'__file__':str(pathlib.Path('smoke.py').resolve())}
    exec(compile(text[:text.index("record('postgres_roles")],'<signature-helper>','exec'),scope)
    scope['SECRET']=target/'secrets/mossdoom/secrets';scope['S3_HOST']='127.0.0.1:8333'
    for attempt in range(50):
        try:
            for obj in objects:
                data=scope['signed']('GET',obj['key'],client='s3-ingestion-client.json')
                assert hashlib.sha256(data).hexdigest()==obj['sha256']
            break
        except Exception:
            if attempt==49:raise
            time.sleep(1)
    print('PASS restored source responses and normalized raw objects match SHA256')
    sys.exit(0)

target=pathlib.Path(sys.argv[1]).resolve()
assert target.parent==pathlib.Path('/srv/mossdoom/restore-check') and target.name.isdigit()
pg='mossdoom-restore-pg-'+target.name;s3='mossdoom-restore-s3-'+target.name
def sql(container,database,query):return subprocess.check_output(['docker','exec',container,'psql','-U','moss_admin','-d',database,'-Atc',query],text=True).strip()
subprocess.run(['docker','start',pg,s3],check=True,stdout=subprocess.DEVNULL)
try:
    for _ in range(40):
        if subprocess.run(['docker','exec',pg,'pg_isready','-h','127.0.0.1','-U','moss_admin'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
        time.sleep(1)
    results=[]
    for file in ['live-source-verification.json','gateway-source-verification.json']:
        results.extend(json.loads(pathlib.Path('/opt/mossdoom/validation/framework',file).read_text())['results'])
    objects=[]
    for row in results:
        batch=str(uuid.UUID(row['batch_id']));probe=str(uuid.UUID(row['probe_id']))
        count=int(sql(pg,'moss_business',"SELECT count(*) FROM standard_event_projection WHERE batch_id='"+batch+"'"))
        assert count==row['record_count']
        pair=sql(pg,'moss_ingestion',"SELECT object_key||'|'||object_sha256 FROM ingest_batch WHERE id='"+batch+"'").split('|')
        objects.append({'key':pair[0],'sha256':pair[1]})
        pair=sql(pg,'moss_ingestion',"SELECT raw_object_key||'|'||raw_sha256 FROM collection_probe WHERE id='"+probe+"'").split('|')
        objects.append({'key':pair[0],'sha256':pair[1]})
    print('PASS four restored business batches retain 25 real observation rows')
    query="SELECT source_id::text||':'||md5(credential_cipher) FROM collection_profile WHERE credential_cipher IS NOT NULL ORDER BY source_id"
    assert sql(pg,'moss_ingestion',query)==sql('mossdoom-test-postgres-1','moss_ingestion',query)
    restored=(target/'secrets/mossdoom/secrets/connector_key').read_bytes();current=pathlib.Path('/etc/mossdoom/secrets/connector_key').read_bytes()
    assert len(restored)==32 and hmac.compare_digest(restored,current)
    print('PASS encrypted credentials and separate encryption key restored intact')
    pid=subprocess.check_output(['docker','inspect',s3,'--format','{{.State.Pid}}'],text=True).strip()
    subprocess.run(['nsenter','--target',pid,'--net','python3',str(pathlib.Path(__file__).resolve()),'--inside',str(target)],input=json.dumps(objects),text=True,check=True)
    report={'restore_directory':str(target),'platforms':4,'observation_rows':sum(r['record_count'] for r in results),'raw_objects_verified':len(objects),'encrypted_credentials_and_key_verified':True,'source_platform_calls':0,'live_data_overwritten':False}
    pathlib.Path('/opt/mossdoom/validation/framework/source-restore-verification.json').write_text(json.dumps(report,indent=2))
finally:subprocess.run(['docker','stop',pg,s3],stdout=subprocess.DEVNULL,check=True)
