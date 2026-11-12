import type { Row } from './api';
import { DashBoard, type DashCard } from './grid';
import { Head, useInsight } from './insight';
import { Stat, StatGrid } from './ui';
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
      id: 'kpi', title: '生产概览', w: 12, h: 7,
      content: <>
        <StatGrid>
          <Stat label="生产单" value={fmt(data?.orders)} unit="单" note="按生产单号去重" />
          <Stat label="在产数量" value={fmt(data?.totalQty)} unit="件" note="每单取最新状态数量" />
          <Stat label="原始事件" value={fmt(data?.events)} unit="条" note="新物云生产订单观测" />
          <Stat label="数据截至" value={<span className="kpi-small">{when(data?.asOf)}</span>} note={`首次可见 ${when(data?.firstSeen)}`} />
        </StatGrid>
        <div className="notice-line">只读接入新物云生产订单，状态取每张生产单的最后一条事件。入库进度要等入库数据接入后才能与「数量」核对，现在不能直接当在途库存用。</div>
      </>,
    },
    { id: 'status', title: '状态分布', w: 6, h: 4, content: byStatus.length ? <Dist items={byStatus} unit="件" /> : empty('暂无状态数据') },
    { id: 'region', title: '按地区', w: 6, h: 4, content: byRegion.length ? <Dist items={byRegion} unit="件" /> : empty('暂无地区数据') },
    { id: 'shop', title: '按店铺', w: 12, h: 4, content: byShop.length ? <Dist items={byShop} unit="件" /> : empty('暂无店铺数据') },
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
      id: key, title, w: 12, h: 12,
      content: spec.available === false
        ? empty(`${title}数据集尚未登记，先在「数据接入」完成连接与采集。`)
        : <>
          <StatGrid>
            <Stat label="SKU" value={fmt(spec.skus)} unit="个" note={`${fmt(spec.events)} 条观测`} />
            <Stat label="总库存" value={fmt(spec.total)} unit="件" tone={stale !== null && stale >= 7 ? 'warn' : undefined}
              note={staleNote(spec.as_of, '数据截至 ' + when(spec.as_of))} />
            <Stat label="可用" value={fmt(spec.usable)} unit="件" tone="ok"
              note={`${num(spec.total) > 0 ? Math.round(num(spec.usable) / num(spec.total) * 100) : 0}% 可售`} />
            {key === 'wms' && <>
              <Stat label="冻结" value={fmt(spec.frozen)} unit="件" tone={num(spec.frozen) > 0 ? 'warn' : undefined} note={num(spec.frozen) > 0 ? '已锁定不可售' : '无冻结'} />
              <Stat label="库存金额" value={`¥${fmt(spec.amount)}`} note="stockMoney 汇总" />
            </>}
            {key === 'factory' && <Stat label="占用" value={fmt(spec.occupied)} unit="件" tone="warn" note="工厂已占用未发" />}
            {key === 'fba' && <>
              <Stat label="不可用" value={fmt(spec.unable)} unit="件" tone="warn" />
              <Stat label="在途入库" value={fmt(spec.inbound)} unit="件" note="处理中 + 已发货 + 接收中" />
            </>}
          </StatGrid>
          {stale !== null && stale >= 30 && <div className="notice-line">这批 {title} 观测已陈旧 {stale} 天，是接入联调时的历史快照；要作为经营口径使用，需要先补一次完整采集。</div>}
          <div className="table-scroll"><table><thead><tr>
            <th className="w18 nowrap">SKU</th>{key === 'factory' ? <th className="w20">SPU / 工厂</th> : key === 'fba' ? <th className="w20">MSKU / 店铺</th> : <th className="w18 nowrap">条码</th>}
            <th className="w10 nowrap">总库存</th><th className="w10 nowrap">可用</th>{key === 'wms' ? <><th className="w10 nowrap">冻结</th><th className="w12 nowrap">金额</th></> : key === 'factory' ? <th className="w10 nowrap">占用</th> : <><th className="w10 nowrap">预留</th><th className="w10 nowrap">不可用</th></>}
            <th className="w14 nowrap">更新</th></tr></thead>
            <tbody>{list.map((r, i) => <tr key={String(r.sku || i)}>
              <td className="nowrap"><code>{String(r.sku || '—')}</code></td>
              <td>{key === 'factory' ? <>{String(r.spu || '—')}<small className="muted">{String(r.factory || '')}</small></> : key === 'fba' ? <>{String(r.msku || '—')}<small className="muted">{String(r.shop || '')}</small></> : <span className="mono muted">{String(r.code || '—')}</span>}</td>
              <td className="nowrap">{fmt(r.total)}</td>
              <td className="nowrap">{fmt(r.usable)}</td>
              {key === 'wms' ? <><td className="nowrap">{fmt(r.frozen)}</td><td className="nowrap">¥{fmt(r.amount)}</td></> : key === 'factory' ? <td className="nowrap">{fmt(r.occupied)}</td> : <><td className="nowrap">{fmt(r.reserved)}</td><td className="nowrap">{fmt(r.unable)}</td></>}
              <td className="nowrap muted">{when(r.updated)}</td>
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

/* ------------------------------ SKU 全链路 ------------------------------ */

/** 段位小圆点：有数据绿、没接入灰。 */
function Stage({ on, text }: { on: boolean, text: string }) {
  return <span className={`stage ${on ? 'stage-on' : ''}`}><i />{text}</span>;
}

export function SkuChainPage({ notify }: { notify: Notice }) {
  const { data, busy, load } = useInsight('/api/biz/chain', notify);
  const spus = (data?.spus as Row[]) || [];
  const seg = (row: Row, key: string) => (row?.[key] as Row) || {};
  // 生产段按「单数」判定，其余三段按「SKU 数」判定：生产 section 里没有 skus 字段。
  const has = (row: Row, key: string) => key === 'production'
    ? Number(seg(row, key).orders || 0) > 0
    : Number(seg(row, key).skus || 0) > 0;

  const cards: DashCard[] = [
    {
      id: 'kpi', title: '全链路概览', w: 12, h: 7,
      content: <>
        <StatGrid>
          <Stat label="款号" value={fmt(data?.spuCount)} unit="款" note="生产单 + 三仓观测去重" />
          <Stat label="在产数量" value={fmt(data?.productionQty)} unit="件" note="新物云每单最新状态" />
          <Stat label="工厂仓可用" value={fmt(data?.factoryUsable)} unit="件" note="供应链协同" />
          <Stat label="国内仓可用" value={fmt(data?.wmsUsable)} unit="件" tone="warn" note="WMS 快照（较旧）" />
          <Stat label="平台仓可用" value={fmt(data?.fbaUsable)} unit="件" note="FBA 可售" />
        </StatGrid>
        <div className="notice-line">以款号为轴归并四段观测：生产单的货品编码、工厂库存的款号、WMS 与平台仓的 SKU 前缀都等于款号，所以不需要额外映射表。某一段没有数据时显示「未接入」，不显示 0。品牌与部门取自 sell 主数据快照（2026-11-12），实时主数据源尚未接入。{data?.metaError ? `（主数据读取失败：${String(data.metaError)}）` : ''}</div>
      </>,
    },
    {
      id: 'chain', title: '按款号看链路', w: 12, h: 18,
      content: <div className="table-scroll"><table>
        <thead><tr><th className="w14 nowrap">款号</th><th className="w22">品名</th><th className="w18">链路</th><th className="w16">生产</th><th className="w10 nowrap">工厂仓</th><th className="w10 nowrap">国内仓</th><th className="w10 nowrap">平台仓</th><th className="w6 nowrap">SKU</th></tr></thead>
        <tbody>{spus.map(r => {
          const p = seg(r, 'production'), f = seg(r, 'factory'), w = seg(r, 'wms'), b = seg(r, 'fba');
          const statuses = Object.entries((p.byStatus as Row) || {}).map(([k, v]) => `${k} ${fmt(v)}`).join(' · ');
          return <tr key={String(r.spu)}>
            <td><code>{String(r.spu)}</code></td>
            <td title={String(r.goodsName || r.category || r.categoryClass || '')}>
              {String(r.goodsName || r.categoryClass || r.category || '—')}
              <small className="muted">{[r.brand, r.dept, r.category].filter(Boolean).join(' · ') || '主数据待接入'}</small>
            </td>
            <td className="stages">
              <Stage on={has(r, 'production')} text="生产" />
              <Stage on={has(r, 'factory')} text="工厂" />
              <Stage on={has(r, 'wms')} text="国内" />
              <Stage on={has(r, 'fba')} text="平台" />
            </td>
            <td>{has(r, 'production') ? <>{fmt(p.orders)} 单 · {fmt(p.qty)} 件<small className="muted">{statuses}</small></> : <span className="muted">未接入</span>}</td>
            <td>{has(r, 'factory') ? <>{fmt(f.usable)} / {fmt(f.total)}<small className="muted">占用 {fmt(f.occupied)}</small></> : <span className="muted">未接入</span>}</td>
            <td>{has(r, 'wms') ? <>{fmt(w.usable)} / {fmt(w.total)}<small className="muted">冻结 {fmt(w.frozen)}</small></> : <span className="muted">未接入</span>}</td>
            <td>{has(r, 'fba') ? <>{fmt(b.usable)} / {fmt(b.total)}<small className="muted">预留 {fmt(b.reserved)}</small></> : <span className="muted">未接入</span>}</td>
            <td className="muted">{(r.skus as string[])?.length || 0}</td>
          </tr>;
        })}</tbody></table></div>,
    },
  ];

  return <>
    <Head title="SKU 全链路" chip="总览 · 生产 → 工厂仓 → 国内仓 → 平台仓（只读）" onRefresh={load} busy={busy} />
    <DashBoard page="sku" cards={cards} notify={notify} />
  </>;
}
