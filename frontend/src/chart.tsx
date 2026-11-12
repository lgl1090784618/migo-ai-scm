import { useMemo } from 'react';
import './chart.css';

type Point = { label: string; events: number; batches: number };

const PALETTE = ['#2f6bff', '#12b981', '#f59e0b', '#8b5cf6', '#ef4444', '#0ea5e9', '#64748b'];

/** 采集趋势：每日观测行数用柱高表示，批次量与峰值写在图例里。稀疏数据下柱状比折线更可读。 */
export function TrendChart({ points, height = 132 }: { points: Point[]; height?: number }) {
  const W = 1000, H = height, PAD_T = 10, PAD_B = 6;
  const plotH = H - PAD_T - PAD_B;
  const maxE = Math.max(1, ...points.map(p => p.events));
  const total = points.reduce((s, p) => s + p.events, 0);
  const totalBatches = points.reduce((s, p) => s + p.batches, 0);
  const slot = W / Math.max(1, points.length);
  const barW = Math.max(3, slot * 0.52);
  const peak = points.reduce((m, p) => (p.events > m.events ? p : m), points[0] || { label: '', events: 0, batches: 0 });

  const labelAt = (i: number) => (i === 0 || i === points.length - 1 || points.length % 2 === 0 && i === points.length / 2);

  return <div className="chart">
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="chart-svg" role="img" aria-label="每日观测行数">
      {[0, 0.5, 1].map(f => <line key={f} x1="0" x2={W} y1={PAD_T + plotH * f} y2={PAD_T + plotH * f} className="chart-grid" vectorEffect="non-scaling-stroke" />)}
      {points.map((p, i) => {
        const h = p.events > 0 ? Math.max(2, (p.events / maxE) * plotH) : 2;
        return <rect key={p.label} x={i * slot + (slot - barW) / 2} y={PAD_T + plotH - h} width={barW} height={h} rx="2"
          className={p.events > 0 ? 'chart-bar' : 'chart-bar empty'}>
          <title>{`${p.label} · ${p.events} 行 · ${p.batches} 批`}</title>
        </rect>;
      })}
    </svg>
    <div className="chart-axis">
      {points.map((p, i) => <span key={p.label} className={labelAt(i) ? '' : 'axis-hide'}>{labelAt(i) ? p.label : ''}</span>)}
    </div>
    <div className="chart-legend">
      <i className="lg-area" />观测行数合计 {total.toLocaleString()} 行
      <i className="lg-bar" />批次合计 {totalBatches} 批
      <span className="chart-peak">{peak.events ? `峰值 ${peak.events} 行（${peak.label}）` : '近 14 天暂无数据'}</span>
    </div>
  </div>;
}

/** 来源占比环形图。值全部为真实计数，没有数据时直接说明。 */
export function ShareChart({ items, unit = '行' }: { items: { label: string; value: number }[]; unit?: string }) {
  const total = items.reduce((s, i) => s + i.value, 0);
  const arcs = useMemo(() => {
    let acc = 0;
    return items.map((it, idx) => {
      const frac = total ? it.value / total : 0;
      const arc = { ...it, frac, color: PALETTE[idx % PALETTE.length], dash: `${(frac * 100).toFixed(2)} ${(100 - frac * 100).toFixed(2)}`, offset: -acc * 100 };
      acc += frac;
      return arc;
    });
  }, [items, total]);

  if (!total) return <p className="muted padded">最近 30 天没有接收到事件，接入数据源后这里会出现来源占比。</p>;

  return <div className="share">
    <svg viewBox="0 0 120 120" className="share-donut" role="img" aria-label="来源占比">
      <circle cx="60" cy="60" r="46" fill="none" stroke="#eef2f7" strokeWidth="16" />
      {arcs.map(a => <circle key={a.label} cx="60" cy="60" r="46" fill="none" stroke={a.color} strokeWidth="16"
        strokeDasharray={a.dash} strokeDashoffset={a.offset} transform="rotate(-90 60 60)" />)}
      <text x="60" y="56" textAnchor="middle" className="share-total">{total.toLocaleString()}</text>
      <text x="60" y="72" textAnchor="middle" className="share-unit">{unit}</text>
    </svg>
    <ul className="share-legend">
      {arcs.map(a => <li key={a.label}><i style={{ background: a.color }} />
        <span className="share-name">{a.label}</span>
        <span className="share-val">{a.value.toLocaleString()} · {(a.frac * 100).toFixed(0)}%</span></li>)}
    </ul>
  </div>;
}
