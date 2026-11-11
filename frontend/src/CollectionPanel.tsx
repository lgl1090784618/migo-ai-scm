import { useEffect, useState, useRef } from 'react';
import { api, date, pretty, type Row } from './api';
import { CollectionTaskPanel } from './CollectionTaskPanel';

const runLabels: Record<string, string> = { PREVIEW_STORED: '原文已留存', RUNNING: '查询中', FAILED: '失败' };

export function CollectionPanel({ notify }: { notify: (text: string, error?: boolean) => void }) {
  const loadVersion = useRef(0);
  const [datasets, setDatasets] = useState<Row[]>([]), [dataset, setDataset] = useState('');
  const [sources, setSources] = useState<Row[]>([]), [targets, setTargets] = useState<Row[]>([]), [endpoints, setEndpoints] = useState<Row[]>([]);
  const [source, setSource] = useState(''), [profile, setProfile] = useState<Row>({ revision: 0 }), [runs, setRuns] = useState<Row[]>([]);
  const [target, setTarget] = useState(''), [endpoint, setEndpoint] = useState(''), [request, setRequest] = useState('{}'), [kind, setKind] = useState('BEARER');
  const [challenge, setChallenge] = useState<Row | null>(null), [captchaAnswer, setCaptchaAnswer] = useState('');
  const [fields, setFields] = useState<Record<string, string>>({}), [busy, setBusy] = useState(false);
  const load = async (id = source) => {
    if (!id) return;
    const version = ++loadVersion.current;
    const [p, nextRuns] = await Promise.all([api(`/api/collection/sources/${id}`), api(`/api/collection/sources/${id}/runs`)]);
    if(version !== loadVersion.current) return;
    const selected = endpoints.find(e => e.platform_code === sources.find(s => s.id === id)?.platform_code);
    setProfile(p); setTarget(p.base_url || targets.find(t => t.endpoint_ids.includes(selected?.id))?.origin || ''); setEndpoint(p.endpoint_id || selected?.id || '');
    setRequest(pretty(p.request_json || selected?.request_template || {})); setKind(p.credential_kind || 'BEARER'); setFields({});
    setRuns(nextRuns); setDataset(''); setChallenge(null); setCaptchaAnswer('');
  };
  useEffect(() => {
    Promise.all([api('/api/sources?limit=100'), api('/api/collection/targets'), api('/api/collection/endpoints'), api('/api/datasets?limit=100')]).then(([s, t, e, d]) => {
      setSources(s.items.filter((r: Row) => r.connector_type === 'HTTP' && r.mode === 'PULL' && r.environment === 'test'));
      setDatasets(d.items); setTargets(t); setEndpoints(e); setTarget(t[0]?.origin || ''); setEndpoint(e[0]?.id || ''); setRequest(pretty(e[0]?.request_template || {}));
    }).catch(e => notify(e.message, true));
  }, []);
  useEffect(() => { if (source) load().catch(e => notify(e.message, true)); }, [source]);
  const action = async (fn: () => Promise<void>) => { setBusy(true); try { await fn(); } catch (e) { notify((e as Error).message, true); } finally { setBusy(false); } };
  const credentialFields = kind === 'WMS_APP' ? [{ key: 'appId', label: '应用标识 AppId' }, { key: 'appSecret', label: '应用签名密钥' }, { key: 'timeZone', label: '时间戳时区（如 Asia/Shanghai）' }, { key: 'tokenMinutes', label: 'Token 有效分钟（1—60）' }] : kind === 'BEARER' ? [{ key: 'token', label: '登录取得的 Bearer Token' }] : (kind === 'LOGIN_ACCOUNT' || kind === 'GATEWAY_SESSION') ? [{ key: 'username', label: '登录账号' }, { key: 'password', label: '登录密码' }, { key: 'passwordEncoding', label: '登录前密码编码规则' }] : [{ key: 'account', label: 'OpenAPI account' }, { key: 'secret', label: 'OpenAPI secret' }, { key: 'serverName', label: '签名 serverName' }];
  const platformCode = sources.find(s => s.id === source)?.platform_code;
  const availableEndpoints = endpoints.filter(e => e.platform_code === platformCode && e.review_status === 'APPROVED');
  return <>
    <div className="page-heading"><div><div className="eyebrow">READ ONLY CONNECTIONS</div><h1>接口配置与试查</h1><p>管理测试目标、查询参数及凭据。仅执行已核实的只读接口。</p></div></div>
    <div className="info-box"><span>先在“平台与连接”登记 test / HTTP / 主动查询连接。支持单页只读试查、来源原文下载与显式导入已启用的数据集。WMS应用授权和新物云签名由服务端完成；正式任务可按配置执行有界分页，定时计划需要显式启用。</span></div>
    <section className="panel" style={{ padding: 24 }}>
      <label className="field"><span>测试连接（当前最多显示100条连接中的可配置项）</span><select value={source} onChange={e => setSource(e.target.value)}><option value="">请选择连接</option>{sources.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
      {source && !availableEndpoints.length && <div className="info-box"><span>此平台的查询接口适配尚未核实，当前仅完成测试来源登记，不能执行请求。微服务应用授权仍需与当前授权体系单独核实。</span></div>}{source && availableEndpoints.length > 0 && <>
        <div className="form-grid"><label className="field"><span>目标地址</span><select value={target} onChange={e => setTarget(e.target.value)}>{targets.filter(t => t.endpoint_ids.includes(endpoint)).map(t => <option key={t.origin} value={t.origin}>{t.name} · {t.origin}</option>)}</select><small>地址列表由部署配置管理；更换目标地址将清除旧凭据，避免误发。</small></label>
          <label className="field"><span>已核实查询接口</span><select value={endpoint} onChange={e => { setEndpoint(e.target.value); const selected = endpoints.find(o => o.id === e.target.value); if (selected) setRequest(pretty(selected.request_template)); }}>{availableEndpoints.map(e => <option key={e.id} value={e.id}>{e.name}</option>)}</select><small>服务文档分组不等于只读授权，不提供任意URL执行。</small></label></div>
        <label className="field"><span>查询参数</span><textarea className="code-input" rows={10} value={request} onChange={e => setRequest(e.target.value)} spellCheck={false} /></label>
        <div className="modal-actions"><button className="button primary" disabled={busy} onClick={() => action(async () => { const p = await api(`/api/collection/sources/${source}`, { json: { endpoint_id: endpoint, base_url: target, request_json: JSON.parse(request), revision: profile.revision || 0 } }); setProfile(p); notify('查询配置已保存'); })}>保存查询配置</button></div>
        <hr /><h2>凭据管理</h2><p className="muted">{profile.credential_configured ? `已加密保存 · ${date(profile.credential_updated_at)}` : '尚未保存凭据'}。授权状态：{profile.auth_state || '待配置'}。现有秘密不会回显；输入新值后整组替换。</p>
        <label className="field"><span>认证资料类型</span><select value={kind} onChange={e => { setKind(e.target.value); setFields(e.target.value === 'WMS_APP' ? {timeZone: 'Asia/Shanghai', tokenMinutes: '10'} : e.target.value === 'LOGIN_ACCOUNT' ? {passwordEncoding: 'MD5_LOWER'} : {}); setChallenge(null); }}>{(platformCode === 'migo-wms') && <option value="WMS_APP">WMS应用签名授权 · 查询前获取Token</option>}{(platformCode !== 'xinwuyun') && <option value="BEARER">已登录取得的 Bearer Token · 支持试查</option>}{(platformCode === 'migo-supply' || platformCode === 'migo-tms') && <option value="LOGIN_ACCOUNT">微服务登录账号 · 验证码登录</option>}{(platformCode === 'migo-supply' || platformCode === 'migo-tms') && <option value="GATEWAY_SESSION" disabled>微服务会话 · Token已安全保存</option>}{(platformCode === 'xinwuyun') && <option value="XWY_SIGNING">新物云OpenAPI签名 · 支持试查</option>}</select></label>

        {(kind === 'LOGIN_ACCOUNT' || kind === 'GATEWAY_SESSION') && <div className="info-box"><div><p>先保存账号资料，再获取验证码并填写；Token保留在服务端，到期后重新登录。{profile.token_expires_at && ` 当前会话到期：${date(profile.token_expires_at)}`}</p><button className="button" disabled={busy || !profile.credential_configured} onClick={() => action(async () => {setChallenge(await api(`/api/collection/sources/${source}/captcha`, {json:{}}));setCaptchaAnswer('');})}>获取登录验证码</button>{challenge && <div className="form-grid" style={{marginTop:16}}><img src={challenge.image} alt="请由用户填写登录验证码" style={{maxWidth:220}} /><input aria-label="登录验证码" value={captchaAnswer} onChange={e => setCaptchaAnswer(e.target.value)} /><button className="button primary" disabled={busy || !captchaAnswer} onClick={() => action(async () => {try{const p=await api(`/api/collection/sources/${source}/login`,{json:{challenge_id:challenge.challenge_id,captcha:captchaAnswer}});setProfile(p);setKind(p.credential_kind);setFields({});notify('登录成功，Token已加密保存');}finally{setChallenge(null);setCaptchaAnswer('');}})}>登录并保存Token</button></div>}</div></div>}
        <div className="form-grid">{credentialFields.map(f => <label className="field" key={f.key}><span>{f.label}</span>{f.key === 'passwordEncoding' ? <select value={fields[f.key] || 'MD5_LOWER'} onChange={e => setFields(x => ({...x,[f.key]: e.target.value}))}><option value="MD5_LOWER">MD5小写 · 当前微服务前端规则</option><option value="PLAIN">原文 · 仅用于明确要求原文的认证</option></select> : <input type="password" autoComplete="new-password" value={fields[f.key] || ''} onChange={e => setFields(x => ({ ...x, [f.key]: e.target.value }))} />}</label>)}</div>
        <div className="modal-actions"><button className="button" disabled={busy || !profile.source_id} onClick={() => action(async () => { const p = await api(`/api/collection/sources/${source}/credential`, { json: { kind: kind === 'GATEWAY_SESSION' ? 'LOGIN_ACCOUNT' : kind, fields: (kind === 'LOGIN_ACCOUNT' || kind === 'GATEWAY_SESSION') ? {passwordEncoding: 'MD5_LOWER', ...fields} : fields, revision: profile.revision } }); setProfile(p); setFields({}); notify('凭据已加密保存，不回显原值'); })}>替换凭据</button><button className="button" disabled={busy || !profile.credential_configured} onClick={() => action(async () => { const p = await api(`/api/collection/sources/${source}/credential/clear`, { json: {} }); setProfile(p); setFields({}); notify('凭据已清除'); })}>清除凭据</button>
          <button className="button primary" disabled={busy || !profile.credential_configured} onClick={() => action(async () => { if (!confirm('使用已保存配置执行一次只读查询并保存本页原文？不会启动定时任务。')) return; const r = await api(`/api/collection/sources/${source}/probe`, { json: {} }); notify(r.state === 'PREVIEW_STORED' ? `试查成功：${r.record_count} 条，原文已保存` : `试查未完成：${r.error_code}`, r.state !== 'PREVIEW_STORED'); setRuns(await api(`/api/collection/sources/${source}/runs`)); })}>{busy ? '处理中…' : '单次只读试查'}</button></div>
      </>}
    </section>
    {source && <section className="panel" style={{ marginTop: 24 }}><div className="panel-heading"><h2>最近30次试查</h2><button className="button" disabled={busy} onClick={() => load().catch(e => notify(e.message, true))}>刷新</button></div><label className="field" style={{padding: 24}}><span>导入目标数据集（须属于当前来源且已启用）</span><select value={dataset} onChange={e => setDataset(e.target.value)}><option value="">请选择数据集</option>{datasets.filter(d => d.source_id === source && d.state === 'ACTIVE').map(d => <option key={d.id} value={d.id}>{d.name}</option>)}</select><small>先在平台连接中启用已试查成功的来源，再发布并启用数据集。导入生成通用快照事件，尚未建立经营实体关联。</small></label><div className="table-scroll"><table><thead><tr>{['开始时间', '配置版本', '状态', 'HTTP', '记录数', '错误代码', '原文 / 批次'].map(t => <th key={t}>{t}</th>)}</tr></thead><tbody>{runs.map(r => <tr key={r.id}><td>{date(r.started_at)}</td><td>v{r.profile_revision}</td><td>{runLabels[r.state] || r.state}</td><td>{r.http_status || '—'}</td><td>{r.record_count ?? '—'}</td><td>{r.error_code || '—'}</td><td>{r.state === 'PREVIEW_STORED' && <><a className="text-link" href={`/api/collection/probes/${r.id}/raw`} target="_blank" rel="noreferrer">原始响应</a><button className="button small" disabled={busy || !dataset} onClick={() => action(async () => {const receipt = await api(`/api/collection/probes/${r.id}/import`, {json: {dataset_id: dataset}}); notify(receipt.batch_id ? `已接收批次 ${receipt.batch_id.slice(0,8)}，请到接收批次查看处理结果` : receipt.notice); setRuns(await api(`/api/collection/sources/${source}/runs`));})}>{r.import_batch_id ? '重取回执' : '导入数据集'}</button>{r.import_batch_id && <code>{r.import_batch_id.slice(0,8)}</code>}</>}</td></tr>)}</tbody></table></div>{!runs.length && <p className="muted" style={{ padding: 24 }}>尚未执行试查，不代表已连接业务系统。</p>}</section>}
    {source && availableEndpoints.length > 0 && <CollectionTaskPanel key={source} source={source} datasets={datasets} notify={notify} />}
  </>;
}
