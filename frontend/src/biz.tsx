import type { Row } from './api';
import { DashBoard, type DashCard } from './grid';
import { Head, useInsight } from './insight';
import './insight.css';
import './biz.css';

type Notice = (message: string, error?: boolean) => void;

const num = (value: unknown) => Number(value || 0);
const fmt = (value: unknown) => num(value).toLocaleString('zh-CN', { maximumFractionDigits: 0 });
const empty = (text: string) => <div className="notice-line">{text}</div>;

/** 统一显示到分钟，并保留年份——WMS 测试快照是 2023 年的数据，只显示月日会误导。 */
const when = (value: unknown) => {
  if (!value) return '—';
  const d = new Date(String(value).replace(' ', 'T'));
  if (Number.isNaN(d.getTime())) return String(value);
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(d);
};

/** 数据落后天数：用于在页面上明确标注「这不是今天的库存」。 */
const staleDays = (value: unknown) => {
  if (!value) return null;
  const d = new Date(String(value).replace(' ', 'T'));
  if (Number.isNaN(d.getTime())) return null;
  return Math.floor((Date.now() - d.getTime()) / 86400000);
};

const staleNote = (value: unknown, prefix: string) => {
  const days = staleDays(value);
  if (days === null) return prefix;
  return days >= 1 ? `${prefix}（落后 ${days} 天）` : prefix;
};

/** 横向占比条：用于状态、店铺、地区这类维度分布。 */
function Dist({ items, unit }: { items: Row[], unit: string }) {
  const max = Math.max(1, ...items.map(i => num(i.qty)));
  return <div className="dist">{items.map(i => (
    <div className="dist-row" key={String(i.name)}>
      <div className="dist-name" title={String(i.name)}>{String(i.name)}</div>
      <div className="dist-track"><span style={{ width: Math.max(2, Math.round(num(i.qty) / max * 100)) + '%' }} /></div>
      <div className="dist-value">{fmt(i.qty)} <small>{unit}</small><small className="muted"> · {i.count} 单</small></div>
    </div>
  ))}</div>;
}

/* ------------------------------ 生产 ------------------------------ */

export function ProductionPage({ notify }: { notify: Notice }) {
  const { data, busy, load } = useInsight('/api/biz/production', notify);
  const rows = (data?.rows as Row[]) || [];
  const byStatus = (data?.byStatus as Row[]) || [];
  const byShop = (data?.byShop as Row[]) || [];
  const byRegion = (data?.byRegion as Row[]) || [];

  if (data && data.available === false) {
    return <>
      <Head title="生产" chip="业务模块 · 新物云生产订单" onRefresh={load} busy={busy} />
      {empty('生产数据集 production_order_observations 尚未登记，先在「数据接入」创建连接并完成一次采集。')}
    </>;
  }

  const cards: DashCard[] = [
    {
      id: 'kpi', title: '生产概览', w: 12, h: 8,
      content: <>
        <div className="kpi-grid">
          <div className="kpi"><div className="kpi-label">生产单</div><div className="kpi-value">{fmt(data?.orders)}<small>单</small></div><div className="kpi-note">按生产单号去重</div></div>
          <div className="kpi"><div className="kpi-label">在产数量</div><div className="kpi-value">{fmt(data?.totalQty)}<small>件</small></div><div className="kpi-note">每单取最新状态数量</div></div>
          <div className="kpi"><div className="kpi-label">原始事件</div><div className="kpi-value">{fmt(data?.events)}<small>条</small></div><div className="kpi-note">新物云生产订单观测</div></div>
          <div className="kpi"><div className="kpi-label">数据截至</div><div className="kpi-value kpi-small">{when(data?.asOf)}</div><div className="kpi-note">首次可见 {when(data?.firstSeen)}</div></div>
        </div>
        <div className="notice-line">只读接入新物云生产订单，状态取每张生产单的最后一条事件。入库进度要等入库数据接入后才能与「数量」核对，现在不能直接当在途库存用。</div>
      </>,
    },
    { id: 'status', title: '状态分布', w: 6, h: 6, content: byStatus.length ? <Dist items={byStatus} unit="件" /> : empty('暂无状态数据') },
    { id: 'region', title: '按地区', w: 6, h: 6, content: byRegion.length ? <Dist items={byRegion} unit="件" /> : empty('暂无地区数据') },
    { id: 'shop', title: '按店铺', w: 12, h: 6, content: byShop.length ? <Dist items={byShop} unit="件" /> : empty('暂无店铺数据') },
    {
      id: 'orders', title: '生产单明细', w: 12, h: 15,
      content: <div className="table-scroll"><table><thead><tr><th>生产单号</th><th>订单号</th><th>货品</th><th>数量</th><th>状态</th><th>店铺</th><th>地区</th><th>最后更新</th></tr></thead>
        <tbody>{rows.map((r, i) => <tr key={String(r.apply_no || i)}>
          <td><code>{String(r.apply_no || '—')}</code></td>
          <td><code>{String(r.order_no || '—')}</code></td>
          <td title={String(r.goods_name || '')}>{String(r.goods_name || r.goods_no || '—')}<small className="muted">{String(r.goods_no || '')}</small></td>
          <td>{fmt(r.quantity)}</td>
          <td>{String(r.status || '—')}</td>
          <td title={String(r.shop || '')}>{String(r.shop || '—')}</td>
          <td>{String(r.region || '—')}</td>
          <td>{when(r.modified)}</td>
        </tr>)}</tbody></table></div>,
    },
  ];

  return <>
    <Head title="生产" chip="业务模块 · 新物云生产订单（只读）" onRefresh={load} busy={busy} />
    <DashBoard page="production" cards={cards} notify={notify} />
  </>;
}

/* ------------------------------ 库存 ------------------------------ */

export function InventoryPage({ notify }: { notify: Notice }) {
  const { data, busy, load } = useInsight('/api/biz/inventory', notify);
  const wms = (data?.wms as Row) || {}, factory = (data?.factory as Row) || {}, fba = (data?.fba as Row) || [];
  const rows = (spec: Row) => (spec?.rows as Row[]) || [];

  const block = (key: string, title: string, spec: Row): DashCard => {
    const list = rows(spec);
    const stale = staleDays(spec.as_of);
    return {
      id: key, title, w: 12, h: 15,
      content: spec.available === false
        ? empty(`${title}数据集尚未登记，先在「数据接入」完成连接与采集。`)
        : <>
          <div className="kpi-grid">
            <div className="kpi"><div className="kpi-label">SKU</div><div className="kpi-value">{fmt(spec.skus)}<small>个</small></div><div className="kpi-note">{fmt(spec.events)} 条观测</div></div>
            <div className={`kpi ${stale !== null && stale >= 7 ? 'kpi-warn' : ''}`}><div className="kpi-label">总库存</div><div className="kpi-value">{fmt(spec.total)}<small>件</small></div><div className="kpi-note">{staleNote(spec.as_of, '数据截至 ' + when(spec.as_of))}</div></div>
            <div className="kpi kpi-ok"><div className="kpi-label">可用</div><div className="kpi-value">{fmt(spec.usable)}<small>件</small></div><div className="kpi-note">{num(spec.total) > 0 ? Math.round(num(spec.usable) / num(spec.total) * 100) : 0}% 可售</div></div>
            {key === 'wms' && <>
              <div className="kpi kpi-warn"><div className="kpi-label">冻结</div><div className="kpi-value">{fmt(spec.frozen)}<small>件</small></div></div>
              <div className="kpi"><div className="kpi-label">库存金额</div><div className="kpi-value">¥{fmt(spec.amount)}</div><div className="kpi-note">stockMoney 汇总</div></div>
            </>}
            {key === 'factory' && <div className="kpi kpi-warn"><div className="kpi-label">占用</div><div className="kpi-value">{fmt(spec.occupied)}<small>件</small></div><div className="kpi-note">工厂已占用未发</div></div>}
            {key === 'fba' && <>
              <div className="kpi kpi-warn"><div className="kpi-label">不可用</div><div className="kpi-value">{fmt(spec.unable)}<small>件</small></div></div>
              <div className="kpi"><div className="kpi-label">在途入库</div><div className="kpi-value">{fmt(spec.inbound)}<small>件</small></div><div className="kpi-note">处理中 + 已发货 + 接收中</div></div>
            </>}
          </div>
          {stale !== null && stale >= 30 && <div className="notice-line">这批 {title} 观测已陈旧 {stale} 天，是接入联调时的历史快照；要作为经营口径使用，需要先补一次完整采集。</div>}
          <div className="table-scroll"><table><thead><tr>
            <th>SKU</th>{key === 'factory' ? <th>SPU / 工厂</th> : key === 'fba' ? <th>MSKU / 店铺</th> : <th>条码</th>}
            <th>总库存</th><th>可用</th>{key === 'wms' ? <><th>冻结</th><th>金额</th></> : key === 'factory' ? <th>占用</th> : <><th>预留</th><th>不可用</th></>}
            <th>更新</th></tr></thead>
            <tbody>{list.map((r, i) => <tr key={String(r.sku || i)}>
              <td><code>{String(r.sku || '—')}</code></td>
              <td>{key === 'factory' ? <>{String(r.spu || '—')}<small className="muted">{String(r.factory || '')}</small></> : key === 'fba' ? <>{String(r.msku || '—')}<small className="muted">{String(r.shop || '')}</small></> : <code>{String(r.code || '—')}</code>}</td>
              <td>{fmt(r.total)}</td>
              <td>{fmt(r.usable)}</td>
              {key === 'wms' ? <><td>{fmt(r.frozen)}</td><td>¥{fmt(r.amount)}</td></> : key === 'factory' ? <td>{fmt(r.occupied)}</td> : <><td>{fmt(r.reserved)}</td><td>{fmt(r.unable)}</td></>}
              <td>{when(r.updated)}</td>
            </tr>)}</tbody></table></div>
        </>,
    };
  };

  return <>
    <Head title="库存" chip="业务模块 · 国内仓 / 工厂仓 / 平台仓（只读）" onRefresh={load} busy={busy} />
    <DashBoard page="inventory" notify={notify}
      cards={[block('wms', '国内仓 WMS', wms), block('factory', '工厂仓', factory), block('fba', '平台仓 FBA', fba)]} />
  </>;
}
