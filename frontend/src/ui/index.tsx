import React from 'react';

/**
 * 原子组件层。
 * 约定：页面只描述"是什么"，不写结构细节；样式全部来自 style.css 的令牌体系。
 * 命名对齐 shadcn/ui 的语义（Card / Badge / Separator / Skeleton / Empty），
 * 但不引入第三方依赖——本项目离线环境无法安装 npm 包。
 */

/* ---------- 版式 ---------- */

export function PageHeader({ eyebrow, title, description, actions }: {
  eyebrow?: string; title: string; description?: React.ReactNode; actions?: React.ReactNode;
}) {
  return <div className="page-heading">
    <div>
      {eyebrow && <div className="eyebrow">{eyebrow}</div>}
      <h1>{title}</h1>
      {description && <p>{description}</p>}
    </div>
    {actions && <div className="heading-actions">{actions}</div>}
  </div>;
}

export function Section({ title, extra, children, padded = true }: {
  title?: React.ReactNode; extra?: React.ReactNode; children: React.ReactNode; padded?: boolean;
}) {
  return <section className="panel">
    {title && <div className="panel-heading"><h2>{title}</h2>{extra && <div style={{ marginLeft: 'auto' }}>{extra}</div>}</div>}
    <div className={padded ? 'padded' : undefined}>{children}</div>
  </section>;
}

/* ---------- 容器 ---------- */

export function Card({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <div className={`panel ${className}`.trim()}>{children}</div>;
}

export function StatGrid({ children, columns }: { children: React.ReactNode; columns?: number }) {
  return <div className="kpi-grid" style={columns ? { gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` } : undefined}>{children}</div>;
}

export function Stat({ label, value, unit, note, tone }: {
  label: React.ReactNode; value: React.ReactNode; unit?: string; note?: React.ReactNode; tone?: 'ok' | 'warn';
}) {
  return <div className={`kpi${tone ? ` kpi-${tone}` : ''}`}>
    <div className="kpi-label">{label}</div>
    <div className="kpi-value">{value}{unit && <small>&nbsp;{unit}</small>}</div>
    {note && <div className="kpi-note">{note}</div>}
  </div>;
}

/* ---------- 状态 ---------- */

export function Badge({ text, tone }: { text: React.ReactNode; tone?: 'ok' | 'warn' | 'danger' | 'info' }) {
  return <span className={`pill${tone ? ` pill-${tone}` : ''}`}>{text}</span>;
}

export function Empty({ title, text, action, icon }: {
  title: string; text?: React.ReactNode; action?: React.ReactNode; icon?: React.ReactNode;
}) {
  return <div className="empty">
    {icon}
    <h3>{title}</h3>
    {text && <p>{text}</p>}
    {action}
  </div>;
}

export function Loading({ text = '正在读取' }: { text?: string }) {
  return <div className="page-loading"><span className="skeleton" style={{ width: 16, height: 16, borderRadius: '50%' }} />{text}</div>;
}

export function Skeleton({ width = '100%', height = 14, style }: { width?: number | string; height?: number; style?: React.CSSProperties }) {
  return <span className="skeleton" style={{ display: 'block', width, height, ...style }} />;
}

/* ---------- 数据 ---------- */

export type Column<T = Record<string, unknown>> = {
  title: React.ReactNode;
  render: (row: T) => React.ReactNode;
  className?: string;
  align?: 'left' | 'right';
};

export function DataTable<T extends Record<string, any>>({ columns, rows, rowKey = 'id', empty }: {
  columns: Column<T>[]; rows: T[]; rowKey?: string; empty?: React.ReactNode;
}) {
  if (!rows.length) return <>{empty ?? <Empty title="暂无数据" />}</>;
  return <div className="table-scroll">
    <table>
      <thead><tr>{columns.map((c, i) => <th key={i} className={c.className} style={c.align === 'right' ? { textAlign: 'right' } : undefined}>{c.title}</th>)}</tr></thead>
      <tbody>{rows.map((r, index) => <tr key={String(r[rowKey] ?? index)}>
        {columns.map((c, i) => <td key={i} className={c.className} style={c.align === 'right' ? { textAlign: 'right' } : undefined}>{c.render(r)}</td>)}
      </tr>)}</tbody>
    </table>
  </div>;
}

/* ---------- 交互 ---------- */

export function Button({ children, variant = 'default', size, onClick, disabled, type = 'button', title }: {
  children: React.ReactNode; variant?: 'default' | 'primary' | 'ghost' | 'danger'; size?: 'small';
  onClick?: () => void; disabled?: boolean; type?: 'button' | 'submit'; title?: string;
}) {
  const cls = ['button', variant !== 'default' ? variant : '', size || ''].filter(Boolean).join(' ');
  return <button type={type} className={cls} onClick={onClick} disabled={disabled} title={title}>{children}</button>;
}

export function Toolbar({ children }: { children: React.ReactNode }) {
  return <div className="filter-row">{children}</div>;
}

export function Notice({ children, tone }: { children: React.ReactNode; tone?: 'info' | 'warn' }) {
  return <div className={tone === 'warn' ? 'scope-note' : 'info-box'}>{children}</div>;
}
