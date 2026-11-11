import { useEffect, useMemo, useState } from 'react';
import { Activity, AlertTriangle, ArrowRight, Boxes, CheckCircle2, Clock, Database, FolderOpen, GitBranch, LoaderCircle, RefreshCw, Timer } from 'lucide-react';
import { api, date, type Row } from './api';
import { DashBoard, type DashCard } from './grid';
import './insight.css';

type Notice = (message: string, error?: boolean) => void;

const stateText: Record<string, string> = { ACTIVE: '已启用', PAUSED: '已暂停', DRAFT: '待配置', COMPLETED: '已完成', COMPLETED_WITH_ERRORS: '含异常', FAILED: '失败', RETRY: '等待重试', RUNNING: '运行中', QUEUED: '排队中', SUCCEEDED: '成功', LIMIT_REACHED: '到达上限', CANCELLED: '已取消' };
const tone = (state?: string) => state === 'ACTIVE' || state === 'COMPLETED' || state === 'SUCCEEDED' ? 'ok' : state === 'FAILED' ? 'bad' : state === 'COMPLETED_WITH_ERRORS' || state === 'LIMIT_REACHED' || state === 'PAUSED' ? 'warn' : 'idle';

function useInsight(path: string, notify: Notice) {
  const [data, setData] = useState<Row | null>(null), [busy, setBusy] = useState(true), [at, setAt] = useState<string>('');
  const load = () => { setBusy(true); api(path).then(d => { setData(d); setAt(new Date().toISOString()); }).catch(e => notify(e.message, true)).finally(() => setBusy(false)); };
  useEffect(() => { load(); /* eslint-disable-next-line */ }, []);
  return { data, busy, at, load };
}

function Head({ title, chip, onRefresh, busy }: { title: string, chip: string, onRefresh: () => void, busy: boolean }) {
  return <div className="page-heading"><div><div className="eyebrow">{chip}</div><h1>{title}</h1></div>
    <div className="heading-actions"><button className="button" onClick={onRefresh} disabled={busy}>{busy ? <LoaderCircle size={16} className="spin" /> : <RefreshCw size={16} />}刷新</button></div></div>;
}

function Kpi({ label, value, unit, note, tone: t }: { label: string, value: React.ReactNode, unit?: string, note?: string, tone?: string }) {
  return <div className={`kpi ${t ? 'kpi-' + t : ''}`}><div className="kpi-label">{label}</div><div className="kpi-value">{value}{unit && <small>{unit}</small>}</div>{note && <div className="kpi-note">{note}</div>}</div>;
}

function Badge({ text, t }: { text: string, t?: string }) { return <span className={`pill ${t ? 'pill-' + t : ''}`}>{text}</span>; }

/* ------------------------------ 经营总览 ------------------------------ */

export function BusinessOverview({ navigate, notify }: { navigate: (id: string) => void, notify: Notice }) {
  const { data, busy, load } = useInsight('/api/insight/overview', notify);
  const { data: cover } = useInsight('/api/insight/coverage', notify);
  const p = data?.pipeline as Row | undefined;
  const datasets = (cover?.datasets as Row[]) || [];
  const byPlatform = useMemo(() => {
    const map: Record<string, { events: number, datasets: number, last: string | null }> = {};
    datasets.forEach(d => {
      const k = d.platform_code || 'other';
      map[k] = map[k] || { events: 0, datasets: 0, last: null };
      map[k].events += Number(d.events || 0);
      map[k].datasets += 1;
      if (d.last_received_at && (!map[k].last || d.last_received_at > map[k].last!)) map[k].last = d.last_received_at;
    });
    return map;
  }, [datasets]);

  const segments = [
    { key: 'market', title: '市场', icon: Activity, value: '未接入', unit: '', note: '待接 FastMoss / 广告投放数据', to: 'market' },
    { key: 'plan', title: '计划', icon: Timer, value: '未接入', unit: '', note: '待接采购计划与补货建议', to: 'plan' },
    { key: 'production', title: '生产', icon: Boxes, value: byPlatform.xinwuyun?.events ?? 0, unit: '行', note: '新物云生产单观测', to: 'production' },
    { key: 'logistics', title: '物流', icon: GitBranch, value: (byPlatform['migo-tms']?.events ?? 0) + (byPlatform['migo-supply']?.events ?? 0), unit: '行', note: 'TMS + 供应链协同观测', to: 'logistics' },
    { key: 'inventory', title: '库存', icon: Database, value: byPlatform['migo-wms']?.events ?? 0, unit: '行', note: 'WMS 库存快照观测', to: 'inventory' },
    { key: 'sales', title: '销售', icon: FolderOpen, value: '未接入', unit: '', note: '待接领星 / 平台订单与广告', to: 'sales' },
  ];

  const batches = (data?.recentBatches as Row[]) || [];
  const sources = (data?.sources as Row[]) || [];

  const cards: DashCard[] = [
    {
      id: 'pipeline', title: '数据管道', h: 5, content: <div className="kpi-grid">
        <Kpi label="已启用数据源" value={p?.sources?.active ?? '—'} unit={`/ ${p?.sources?.total ?? '—'}`} note="HTTP · 只读" />
        <Kpi label="近 24 小时批次" value={p?.batches?.last24h ?? '—'} unit="批" note={`累计 ${p?.batches?.total ?? '—'} 批`} />
        <Kpi label="定时采集计划" value={p?.schedules?.enabled ?? '—'} unit={`/ ${p?.schedules?.total ?? '—'}`} note="启用 / 已配置" tone={p?.schedules?.enabled ? 'ok' : 'warn'} />
        <Kpi label="处理中任务" value={p?.jobs?.running ?? '—'} unit="个" note={`近 7 天失败 ${p?.jobs?.failed7d ?? 0} 次`} tone={p?.jobs?.failed7d ? 'warn' : undefined} />
        <Kpi label="异常数据" value={p?.quarantined ?? '—'} unit="条" note="不静默清除" tone={p?.quarantined ? 'bad' : 'ok'} />
        <Kpi label="数据集" value={p?.datasets?.active ?? '—'} unit={`/ ${p?.datasets?.total ?? '—'}`} note="已启用 / 总数" />
      </div>
    },
    {
      id: 'chain', title: '全局链路：市场 → 计划 → 生产 → 物流 → 库存 → 销售', h: 9, content: <>
        <p className="dash-note">每段显示当前可用的真实观测行数；未接入段直接标注，不拿示例数字充数。</p>
        <div className="chain-grid">
          {segments.map(s => <button key={s.key} className={`chain-card tone-${s.value === '未接入' ? 'idle' : 'ok'}`} onClick={() => navigate(s.to)}>
            <div className="chain-head"><s.icon size={16} /><span>{s.title}</span><i /></div>
            <div className="chain-value">{s.value}{s.unit && <small>{s.unit}</small>}</div>
            <div className="chain-note">{s.note}</div>
          </button>)}
        </div>
      </>
    },
    {
      id: 'batches', title: '最近接收批次', w: 6, h: 10, content: batches.length
        ? <div className="table-scroll"><table><thead><tr><th>接收时间</th><th>来源</th><th>数据集</th><th>状态</th><th>事件数</th></tr></thead>
          <tbody>{batches.map(b => <tr key={b.id}><td>{date(b.received_at)}</td><td>{b.source_name || '—'}</td><td><code>{b.dataset_code || '—'}</code></td><td><Badge text={stateText[b.status] || b.status} t={tone(b.status)} /></td><td>{b.event_count}</td></tr>)}</tbody></table></div>
        : <p className="muted padded">暂无批次</p>
    },
    {
      id: 'health', title: '数据源健康', w: 6, h: 10, content: <>
        <p className="dash-note">落后小时按最近批次时间计算。<button className="text-link" onClick={() => navigate('health')}>查看全部 <ArrowRight size={13} /></button></p>
        <div className="table-scroll"><table><thead><tr><th>来源</th><th>状态</th><th>数据截至</th><th>落后</th></tr></thead>
          <tbody>{sources.slice(0, 6).map(s => <tr key={s.id}><td>{s.name}<small className="muted">{s.platform_name}</small></td>
            <td><Badge text={stateText[s.state] || s.state} t={tone(s.state)} /></td><td>{date(s.lastBatchAt)}</td>
            <td>{s.hoursBehind == null ? '—' : `${s.hoursBehind} 小时`}</td></tr>)}</tbody></table></div>
      </>
    },
  ];

  return <>
    <Head title="经营总览" chip="OVERVIEW · 标准模板" onRefresh={load} busy={busy} />
    <div className="notice-line"><span>数据管道指标来自本地采集库（真实）；业务经营指标尚未接入，相关位置标注「待接入」，不使用示例数字。</span></div>
    <DashBoard page="overview" cards={cards} notify={notify} />
  </>;
}

/* ------------------------------ 数据健康 ------------------------------ */

export function DataHealth({ notify }: { notify: Notice }) {
  const { data, busy, load } = useInsight('/api/insight/overview', notify);
  const sources = (data?.sources as Row[]) || [];
  const lag = (h: number | null) => h == null ? { t: 'bad', text: '从未接收' } : h > 24 ? { t: 'bad', text: `${h} 小时` } : h > 6 ? { t: 'warn', text: `${h} 小时` } : { t: 'ok', text: `${h} 小时` };

  const cards: DashCard[] = [
    {
      id: 'kpi', title: '健康概览', h: 4, content: <div className="kpi-grid">
        <Kpi label="数据源总数" value={sources.length} unit="个" />
        <Kpi label="正常" value={sources.filter(s => s.hoursBehind != null && s.hoursBehind <= 24).length} unit="个" tone="ok" />
        <Kpi label="需要关注" value={sources.filter(s => s.hoursBehind == null || s.hoursBehind > 24).length} unit="个" tone="warn" />
        <Kpi label="近 24 小时批次" value={data?.pipeline?.batches?.last24h ?? '—'} unit="批" />
      </div>
    },
    {
      id: 'sources', title: '数据源健康', h: 14, content: <div className="table-scroll"><table><thead><tr><th>数据源</th><th>平台</th><th>连接状态</th><th>数据截至</th><th>落后</th><th>近 24h 批次</th><th>定时任务</th><th>最近错误</th></tr></thead>
        <tbody>{sources.map(s => { const l = lag(s.hoursBehind as number | null); return <tr key={s.id}>
          <td>{s.name}</td><td>{s.platform_name}<small className="muted">{s.platform_code}</small></td>
          <td><Badge text={stateText[s.state] || s.state} t={tone(s.state)} /></td>
          <td>{date(s.lastBatchAt)}</td><td><Badge text={l.text} t={l.t} /></td><td>{s.cnt24 ?? 0}</td>
          <td>{s.schedule_enabled ? <Badge text={`每 ${Math.round((s.interval_seconds || 0) / 60)} 分钟`} t="ok" /> : <Badge text="未启用" t="idle" />}</td>
          <td><code>{s.last_error || '—'}</code></td></tr>; })}</tbody></table></div>
    },
  ];

  return <>
    <Head title="数据健康" chip="DATA HEALTH · 标准模板" onRefresh={load} busy={busy} />
    <div className="notice-line"><span>只统计本地采集库中的接收批次与定时任务，不向源平台发请求。</span></div>
    <DashBoard page="health" cards={cards} notify={notify} />
  </>;
}

/* ------------------------------ 调度中心 ------------------------------ */

export function ScheduleBoard({ notify }: { notify: Notice }) {
  const { data, busy, load } = useInsight('/api/insight/overview', notify);
  const sources = (data?.sources as Row[]) || [];
  const jobs = (data?.recentJobs as Row[]) || [];

  const cards: DashCard[] = [
    {
      id: 'kpi', title: '调度概览', h: 4, content: <div className="kpi-grid">
        <Kpi label="任务总数" value={(data?.pipeline?.schedules?.total as number) ?? '—'} unit="个" />
        <Kpi label="已启用" value={(data?.pipeline?.schedules?.enabled as number) ?? '—'} unit="个" tone={data?.pipeline?.schedules?.enabled ? 'ok' : 'warn'} />
        <Kpi label="处理中" value={data?.pipeline?.jobs?.running ?? '—'} unit="个" />
        <Kpi label="近 7 天失败" value={data?.pipeline?.jobs?.failed7d ?? '—'} unit="次" tone={data?.pipeline?.jobs?.failed7d ? 'warn' : 'ok'} />
      </div>
    },
    {
      id: 'schedules', title: '定时采集计划', w: 6, h: 11, content: <div className="table-scroll"><table><thead><tr><th>来源</th><th>平台</th><th>状态</th><th>间隔</th><th>下次运行</th><th>近 24h 批次</th></tr></thead>
        <tbody>{sources.map(s => <tr key={s.id}><td>{s.name}</td><td>{s.platform_name}</td>
          <td>{s.schedule_enabled ? <Badge text="已启用" t="ok" /> : <Badge text="未启用" t="idle" />}</td>
          <td>{s.interval_seconds ? `${Math.round(s.interval_seconds / 60)} 分钟` : '—'}</td>
          <td>{date(s.next_run_at)}</td><td>{s.cnt24 ?? 0}</td></tr>)}</tbody></table></div>
    },
    {
      id: 'jobs', title: '最近采集任务', w: 6, h: 11, content: jobs.length
        ? <div className="table-scroll"><table><thead><tr><th>创建时间</th><th>来源</th><th>数据集</th><th>状态</th><th>页数</th><th>记录数</th><th>错误</th></tr></thead>
          <tbody>{jobs.map(j => <tr key={j.id}><td>{date(j.created_at)}</td><td>{j.source_name}</td><td><code>{j.dataset_code}</code></td>
            <td><Badge text={stateText[j.state] || j.state} t={tone(j.state)} /></td><td>{j.pages_done}</td><td>{j.record_count}</td><td><code>{j.error_code || '—'}</code></td></tr>)}</tbody></table></div>
        : <p className="muted padded">还没有采集任务。启用定时计划或手动执行一轮后出现在这里。</p>
    },
  ];

  return <>
    <Head title="调度中心" chip="SCHEDULER · 标准模板" onRefresh={load} busy={busy} />
    <div className="notice-line"><span>定时计划在「接口配置与试查」中按来源保存并启用；这里统一查看运行情况。</span></div>
    <DashBoard page="scheduler" cards={cards} notify={notify} />
  </>;
}

/* ------------------------------ 业务模块占位 ------------------------------ */

const moduleCopy: Record<string, { title: string, need: string, owner: string }> = {
  market: { title: '市场', need: 'FastMoss 选品榜单、平台广告投放数据', owner: '市场' },
  plan: { title: '计划', need: '采购计划、补货建议、预测参数', owner: '计划/PMC' },
  production: { title: '生产', need: '新物云生产单与打样单（已登记）', owner: '生产' },
  logistics: { title: '物流', need: 'TMS 头程发货单与在途批次（已登记）', owner: '物流' },
  inventory: { title: '库存', need: 'WMS 库存快照与批次（已登记）', owner: '仓储' },
  sales: { title: '销售', need: '领星多平台订单、广告与利润', owner: '运营' },
  sku: { title: 'SKU 全链路', need: '跨段主键贯通（款号 ↔ 平台 SKU）', owner: '全链路' },
  replenish: { title: '补货与预测', need: '库存 + 在途 + 销量的联合模型', owner: '计划' },
};

export function ModulePage({ id, navigate, notify }: { id: string, navigate: (id: string) => void, notify: Notice }) {
  const copy = moduleCopy[id] || { title: id, need: '—', owner: '—' };
  const { data } = useInsight('/api/insight/coverage', notify);
  const datasets = ((data?.datasets as Row[]) || []).filter(d => {
    if (id === 'production') return d.platform_code === 'xinwuyun';
    if (id === 'logistics') return d.platform_code === 'migo-tms' || d.platform_code === 'migo-supply';
    if (id === 'inventory') return d.platform_code === 'migo-wms';
    return false;
  });

  const cards: DashCard[] = [
    {
      id: 'coverage', title: '当前数据覆盖', w: 7, h: 11, content: datasets.length
        ? <div className="table-scroll"><table><thead><tr><th>数据集</th><th>平台</th><th>粒度</th><th>状态</th><th>观测行</th><th>最近接收</th></tr></thead>
          <tbody>{datasets.map(d => <tr key={d.code}><td><code>{d.code}</code><small className="muted">{d.name}</small></td><td>{d.platform_name}</td><td>{d.grain}</td>
            <td><Badge text={stateText[d.state] || d.state} t={tone(d.state)} /></td><td>{d.events}</td><td>{date(d.last_received_at)}</td></tr>)}</tbody></table></div>
        : <p className="muted padded">本模块尚未登记任何数据集。</p>
    },
    {
      id: 'todo', title: '还缺什么', w: 5, h: 11, content: <div className="padded"><p><strong>所需数据：</strong>{copy.need}</p><p><strong>归属：</strong>{copy.owner}</p>
        <p className="muted">数据源登记 → 接口配置与试查 → 发布数据集 → 启用定时计划，四个步骤走完即可在该模块看到真实数据。</p>
        <button className="button" onClick={() => navigate('sources')}>去登记数据源 <ArrowRight size={15} /></button></div>
    },
  ];

  return <>
    <Head title={copy.title} chip={`${copy.title.toUpperCase()} · 标准模板`} onRefresh={() => { }} busy={false} />
    <div className="notice-line"><span>该模块的界面与指标口径按同事系统对齐中；下方是本项目当前真实的数据覆盖情况，未接入的部分不做示例填充。</span></div>
    <DashBoard page={`module-${id}`} cards={cards} notify={notify} />
  </>;
}
