import { useEffect, useRef, useState } from 'react';
import { api, date, pretty, type Row } from './api';

const names: Record<string, string> = {
  QUEUED: '待采集', RUNNING: '执行中', RETRY: '等待重试', WAITING_AUTH: '等待重新授权',
  WAITING_SOURCE: '等待启用来源', WAITING_PROJECTION: '等待入库', SUCCEEDED: '采集完成',
  LIMIT_REACHED: '达到上限，可能还有数据', SUCCEEDED_WITH_ERRORS: '完成但有异常', FAILED: '失败', CANCELLED: '已取消',
};
const active = new Set(['QUEUED', 'RUNNING', 'RETRY', 'WAITING_AUTH', 'WAITING_SOURCE', 'WAITING_PROJECTION']);
export function CollectionTaskPanel({ source, datasets, notify }: { source: string, datasets: Row[], notify: (message: string, error?: boolean) => void }) {
  const choices = datasets.filter(d => d.source_id === source && d.state === 'ACTIVE');
  const [form, setForm] = useState({ dataset_id: choices[0]?.id || '', enabled: false, interval_seconds: 3600, max_pages: 1, max_records: 1000, window_seconds: 0, query_timezone: 'Asia/Shanghai', revision: 0 });
  const [jobs, setJobs] = useState<Row[]>([]), [cursor, setCursor] = useState(''), [history, setHistory] = useState<string[]>([]), [next, setNext] = useState<string | null>(null);
  const [detail, setDetail] = useState<Row | null>(null), [busy, setBusy] = useState(false), [lastResult, setLastResult] = useState('');
  const [nextRun, setNextRun] = useState<string | null>(null), [loading, setLoading] = useState(true);
  const requestKey = useRef<string | null>(null);
  const fetchJobs = async () => {
    const page = await api(`/api/collection/sources/${source}/jobs?limit=20&cursor=${encodeURIComponent(cursor)}`);
    setJobs(page.items); setNext(page.nextCursor);
    if (detail) setDetail(await api(`/api/collection/jobs/${detail.id}`));
  };
  useEffect(() => {
    let current = true;
    api(`/api/collection/sources/${source}/plan`).then(p => {
      if (!current) return;
      if (p.configured !== false) {
        setForm({ dataset_id: p.dataset_id, enabled: p.enabled, interval_seconds: p.interval_seconds, max_pages: p.max_pages, max_records: p.max_records, window_seconds: p.window_seconds, query_timezone: p.query_timezone, revision: p.revision });
        setLastResult(p.last_result || ''); setNextRun(p.next_run_at);
      }
    }).catch(e => notify(e.message, true)).finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [source]);
  useEffect(() => {
    fetchJobs().catch(e => notify(e.message, true));
    const timer = setInterval(() => fetchJobs().catch(() => {}), 3000);
    return () => clearInterval(timer);
  }, [source, cursor, detail?.id]);
  const set = (key: keyof typeof form, value: string | number | boolean) => { requestKey.current = null; setForm(x => ({ ...x, [key]: value })); };
  const action = async (work: () => Promise<void>) => { setBusy(true); try { await work(); } catch (e) { notify((e as Error).message, true); } finally { setBusy(false); } };
  const save = async (enabled = form.enabled) => {
    if (enabled && !confirm('启用后会立即创建一轮有界只读采集任务，之后按间隔执行。继续？')) return;
    const p = await api(`/api/collection/sources/${source}/plan`, { json: { ...form, enabled } });
    setForm(x => ({ ...x, revision: p.revision, enabled: p.enabled })); setNextRun(p.next_run_at); setLastResult(p.last_result || '');
    notify(enabled ? '定时计划已启用' : '定时计划已保存为关闭状态，已有任务不会被删除');
  };
  return <section className="panel" style={{ marginTop: 24, padding: 24 }}>
    <div className="panel-heading" style={{ padding: 0, marginBottom: 16 }}><div><h2>正式采集任务与计划</h2><small>查询、原文留存、导入和入库状态均保存在数据库，重启后继续。</small></div><button className="button" disabled={busy} onClick={() => fetchJobs().catch(e => notify(e.message, true))}>刷新任务</button></div>
    <div className="info-box"><span>每轮固定分页大小与时间窗口。达到页数或记录上限会明确提示；这不等于全量完成。登录失效后任务停止并等待在平台内授权，再点击重试；不会自动识别验证码。</span></div>
    {!loading && <>
      <div className="form-grid">
        <label className="field"><span>目标数据集</span><select value={form.dataset_id} onChange={e => set('dataset_id', e.target.value)}><option value="">选择已启用的数据集</option>{choices.map(d => <option value={d.id} key={d.id}>{d.name}</option>)}</select></label>
        <label className="field"><span>每轮最多页数（1—100）</span><input type="number" min={1} max={100} value={form.max_pages} onChange={e => set('max_pages', Number(e.target.value))} /></label>
        <label className="field"><span>每轮最多记录数（不改变分页大小）</span><input type="number" min={1} max={10000} value={form.max_records} onChange={e => set('max_records', Number(e.target.value))} /><small>下一页可能超过上限时提前停止，不改变pageSize而跳过或重复分页。</small></label>
        <label className="field"><span>调度间隔秒数（60—86400）</span><input type="number" min={60} max={86400} value={form.interval_seconds} onChange={e => set('interval_seconds', Number(e.target.value))} /></label>
        <label className="field"><span>滚动时间窗口秒数</span><input type="number" min={0} max={5097600} value={form.window_seconds} onChange={e => set('window_seconds', Number(e.target.value))} /><small>0使用查询配置中的固定日期。正数仅用于新物云，任务创建时固定该窗口。</small></label>
        <label className="field"><span>查询时区</span><input value={form.query_timezone} onChange={e => set('query_timezone', e.target.value)} /></label>
      </div>
      <label className="field"><span>定时计划</span><select value={String(form.enabled)} onChange={e => set('enabled', e.target.value === 'true')}><option value="false">关闭，仅手动执行</option><option value="true">启用间隔调度</option></select></label>
      <p className="muted">{form.enabled ? `计划已启用，下次：${date(nextRun)}` : '定时计划关闭'}{lastResult && ` · 上次调度：${lastResult}`}</p>
      <div className="modal-actions" style={{ flexWrap: 'wrap' }}>
        <button className="button" disabled={busy || !form.dataset_id} onClick={() => action(() => save())}>保存计划</button>
        <button className="button" disabled={busy || !form.enabled || form.revision === 0} onClick={() => action(() => save(false))}>停用定时计划</button>
        <button className="button primary" disabled={busy || !form.dataset_id} onClick={() => action(async () => {
          requestKey.current ||= crypto.randomUUID();
          const job = await api(`/api/collection/sources/${source}/jobs`, { headers: { 'Idempotency-Key': requestKey.current }, json: { dataset_id: form.dataset_id, max_pages: form.max_pages, max_records: form.max_records, window_seconds: form.window_seconds, query_timezone: form.query_timezone } });
          requestKey.current = null; setDetail(job); setCursor(''); setHistory([]); await fetchJobs(); notify('采集任务已持久化提交，可离开页面后再查看');
        })}>立即执行一轮</button>
      </div>
    </>}
    <div className="table-scroll"><table><thead><tr>{['创建时间', '触发', '状态', '已采页 / 条', '原因', '操作'].map(s => <th key={s}>{s}</th>)}</tr></thead><tbody>{jobs.map(j => <tr key={j.id}>
      <td>{date(j.created_at)}<small>{j.id.slice(0, 8)}</small></td><td>{j.trigger_type === 'SCHEDULE' ? '定时' : '手动'}</td><td>{names[j.state] || j.state}</td><td>{j.pages_done} / {j.record_count}</td><td>{j.error_code || '—'}</td>
      <td><button className="text-link" onClick={() => api(`/api/collection/jobs/${j.id}`).then(setDetail).catch(e => notify(e.message, true))}>详情</button>{['WAITING_AUTH', 'WAITING_SOURCE', 'FAILED'].includes(j.state) && <button className="button small" disabled={busy} onClick={() => action(async () => { setDetail(await api(`/api/collection/jobs/${j.id}/retry`, { json: {} })); await fetchJobs(); notify('已恢复任务，成功留存的页会继续复用'); })}>重试</button>}{active.has(j.state) && <button className="button small" disabled={busy} onClick={() => action(async () => { if (!confirm('停止后续分页？已发出的读取和已接收数据会保留。')) return; setDetail(await api(`/api/collection/jobs/${j.id}/cancel`, { json: {} })); await fetchJobs(); })}>取消</button>}</td>
    </tr>)}</tbody></table></div>
    {!jobs.length && <p className="muted">尚未创建正式采集任务，历史单页试查记录仍保留在上方。</p>}
    <div className="pager"><span>当前页 {jobs.length} 条</span><div><button className="button small" disabled={!history.length} onClick={() => { setCursor(history[history.length - 1]); setHistory(x => x.slice(0, -1)); }}>上一页</button><button className="button small" disabled={!next} onClick={() => { if (next) { setHistory(x => [...x, cursor]); setCursor(next); } }}>下一页</button></div></div>
    {detail && <div style={{ marginTop: 24 }}><div className="panel-heading" style={{ padding: 0 }}><h3>任务 {detail.id.slice(0, 8)} · {names[detail.state] || detail.state}</h3><button className="button small" onClick={() => setDetail(null)}>收起</button></div>
      <p className="muted">来源配置v{detail.profile_revision} · 结构v{detail.schema_version} · 映射{detail.mapping_version ?? '无'} · {detail.limit_hit ? '到达本轮上限，可能仍有来源数据' : '按来源分页响应判断结束'} · {detail.error_code || ''}</p>
      <details><summary>固定的查询窗口与参数</summary><pre className="json">{pretty(detail.query_json)}</pre></details>
      <div className="table-scroll"><table><thead><tr><th>来源页码</th><th>状态</th><th>条数</th><th>批次</th><th>原文</th></tr></thead><tbody>{detail.pages.map((p: Row) => <tr key={p.page_no}><td>{p.page_no}</td><td>{p.state}</td><td>{p.record_count ?? '—'}</td><td>{p.batch_id ? <code>{p.batch_id}</code> : '—'}</td><td>{p.probe_id && <a className="text-link" href={`/api/collection/probes/${p.probe_id}/raw`} target="_blank" rel="noreferrer">查看来源响应</a>}</td></tr>)}</tbody></table></div>
      <details><summary>查询尝试与恢复记录（最多100条）</summary><pre className="json">{pretty(detail.queries)}</pre></details>
    </div>}
  </section>;
}
