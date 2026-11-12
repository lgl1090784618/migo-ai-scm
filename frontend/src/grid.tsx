import { useEffect, useRef, useState } from 'react';
import { GripVertical, LayoutTemplate, LoaderCircle, Pencil, Save, Trash2, X } from 'lucide-react';
import { api } from './api';
import './grid.css';

export type DashCard = { id: string, title: string, w?: number, h?: number, content: React.ReactNode };
type Item = { id: string, x: number, y: number, w: number, h: number };
type Notice = (message: string, error?: boolean) => void;

const COLS = 12, ROW_H = 26, GAP = 12, MIN_W = 3, MIN_H = 3, SYSTEM = '标准模板';

const clamp = (n: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, n));
const overlap = (a: Item, b: Item) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

/** 标准模板：按卡片宽度做简单装箱，半宽卡片自动左右并排。 */
function defaultLayout(cards: DashCard[]): Item[] {
  const placed: Item[] = [];
  for (const c of cards) {
    const w = clamp(c.w ?? COLS, MIN_W, COLS), h = clamp(c.h ?? 8, MIN_H, 40);
    let done = false;
    for (let y = 0; y < 400 && !done; y++) {
      for (let x = 0; x + w <= COLS; x += w) {
        const cand = { id: c.id, x, y, w, h };
        if (!placed.some(p => overlap(p, cand))) { placed.push(cand); done = true; break; }
      }
    }
    if (!done) placed.push({ id: c.id, x: 0, y: placed.reduce((m, i) => Math.max(m, i.y + i.h), 0), w, h });
  }
  return placed;
}

/** 保持卡片给定的位置，只把互相重叠的依次下移；不做向上压实，否则拖动后会被弹回顶部。 */
function resolveDown(items: Item[], priorityId?: string): Item[] {
  const pri = priorityId ? items.find(i => i.id === priorityId) : undefined;
  const placed: Item[] = pri ? [{ ...pri }] : [];
  for (const it of items.filter(i => i.id !== pri?.id).sort((a, b) => a.y - b.y || a.x - b.x)) {
    const cur = { ...it };
    let guard = 0;
    while (placed.some(p => overlap(p, cur)) && guard++ < 400) cur.y += 1;
    placed.push(cur);
  }
  return placed.sort((a, b) => a.y - b.y || a.x - b.x);
}

/** 已知卡片按保存的布局还原；布局里没有的新卡片追加到底部。 */
function adopt(saved: Item[], cards: DashCard[]): Item[] {
  const known = new Set(cards.map(c => c.id));
  const kept: Item[] = [];
  saved.forEach(l => {
    if (!known.has(l.id) || kept.some(k => k.id === l.id)) return;
    kept.push({ id: l.id, x: clamp(l.x | 0, 0, COLS - 1), y: Math.max(0, l.y | 0), w: clamp(l.w | 0 || COLS, MIN_W, COLS), h: clamp(l.h | 0 || 8, MIN_H, 40) });
  });
  let bottom = kept.reduce((m, i) => Math.max(m, i.y + i.h), 0);
  cards.forEach(c => {
    if (kept.some(k => k.id === c.id)) return;
    kept.push({ id: c.id, x: 0, y: bottom, w: clamp(c.w ?? COLS, MIN_W, COLS), h: clamp(c.h ?? 8, MIN_H, 40) });
    bottom += c.h ?? 8;
  });
  return resolveDown(kept);
}

export function DashBoard({ page, cards, notify }: { page: string, cards: DashCard[], notify: Notice }) {
  const [layout, setLayout] = useState<Item[]>(() => defaultLayout(cards));
  const [templates, setTemplates] = useState<{ name: string, layout: Item[] }[]>([]);
  const [current, setCurrent] = useState(SYSTEM);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [colW, setColW] = useState(0);
  const [dragId, setDragId] = useState('');
  const wrap = useRef<HTMLDivElement>(null);
  const drag = useRef<{ id: string, mode: 'move' | 'resize', sx: number, sy: number, orig: Item, unitW: number } | null>(null);

  useEffect(() => {
    api(`/api/dashboards/${page}`).then((d: any) => {
      const list = (d?.templates || []).map((t: any) => ({ name: String(t.name), layout: (t.layout || []) as Item[] }));
      setTemplates(list);
      if (list.length) { setCurrent(list[0].name); setLayout(adopt(list[0].layout, cards)); }
      else setLayout(defaultLayout(cards));
    }).catch(() => setLayout(defaultLayout(cards)));
    /* eslint-disable-next-line */
  }, [page]);

  useEffect(() => {
    const el = wrap.current; if (!el) return;
    const measure = () => setColW(Math.max(40, (el.clientWidth - (COLS - 1) * GAP) / COLS));
    measure();
    const ro = new ResizeObserver(measure); ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const onMove = (e: PointerEvent) => {
    const d = drag.current; if (!d) return;
    const dx = e.clientX - d.sx, dy = e.clientY - d.sy;
    const unitW = d.unitW + GAP;
    setLayout(prev => prev.map(it => it.id !== d.id ? it : d.mode === 'move'
      ? { ...it, x: clamp(d.orig.x + Math.round(dx / unitW), 0, COLS - it.w), y: Math.max(0, d.orig.y + Math.round(dy / (ROW_H + GAP))) }
      : { ...it, w: clamp(d.orig.w + Math.round(dx / unitW), MIN_W, COLS - it.x), h: clamp(d.orig.h + Math.round(dy / (ROW_H + GAP)), MIN_H, 40) }));
  };
  const onUp = () => {
    const dragged = drag.current?.id || '';
    drag.current = null; setDragId('');
    setLayout(prev => resolveDown(prev, dragged));
    window.removeEventListener('pointermove', onMove);
    window.removeEventListener('pointerup', onUp);
  };
  const startDrag = (e: React.PointerEvent, item: Item, mode: 'move' | 'resize') => {
    if (!editing) return;
    e.preventDefault(); e.stopPropagation();
    drag.current = { id: item.id, mode, sx: e.clientX, sy: e.clientY, orig: { ...item }, unitW: colW };
    setDragId(item.id);
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  const save = async (name: string) => {
    setBusy(true);
    try {
      await api(`/api/dashboards/${page}`, { method: 'PUT', json: { name, layout } });
      await load(name);
      notify(`模板「${name}」已保存`);
    } catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  };
  const load = async (prefer?: string) => {
    const d: any = await api(`/api/dashboards/${page}`);
    const list = (d?.templates || []).map((t: any) => ({ name: String(t.name), layout: (t.layout || []) as Item[] }));
    setTemplates(list);
    const hit = list.find((t: any) => t.name === prefer) || list[0];
    if (hit) { setCurrent(hit.name); setLayout(adopt(hit.layout, cards)); }
    else { setCurrent(SYSTEM); setLayout(defaultLayout(cards)); }
  };
  const pick = (name: string) => {
    if (name === SYSTEM) { setCurrent(SYSTEM); setLayout(defaultLayout(cards)); return; }
    const t = templates.find(x => x.name === name);
    if (t) { setCurrent(name); setLayout(adopt(t.layout, cards)); }
  };
  const removeTemplate = async () => {
    if (current === SYSTEM) return;
    if (!window.confirm(`删除模板「${current}」？`)) return;
    setBusy(true);
    try { await api(`/api/dashboards/${page}/${encodeURIComponent(current)}`, { method: 'DELETE' }); await load(); notify('模板已删除'); }
    catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  };

  const maxY = layout.reduce((m, i) => Math.max(m, i.y + i.h), 0);
  const width = (w: number) => w * colW + (w - 1) * GAP;

  return <>
    <div className="dash-toolbar">
      <div className="dash-template"><LayoutTemplate size={15} />
        <select value={current} onChange={e => pick(e.target.value)}>
          <option value={SYSTEM}>{SYSTEM}</option>
          {templates.map(t => <option key={t.name} value={t.name}>{t.name}</option>)}
        </select>
        {editing && <span className="dash-hint">拖动卡片标题移动，拖右下角改大小</span>}
      </div>
      <div className="dash-actions">
        {editing ? <>
          <button className="button" disabled={busy} onClick={() => save(current === SYSTEM ? '我的布局' : current)}>{busy ? <LoaderCircle size={15} className="spin" /> : <Save size={15} />}保存</button>
          <button className="button ghost" disabled={busy} onClick={() => { const n = window.prompt('另存为模板名称', '我的布局'); if (n && n.trim()) save(n.trim()); }}>另存为</button>
          {current !== SYSTEM && <button className="button ghost danger" disabled={busy} onClick={removeTemplate}><Trash2 size={15} />删除</button>}
          <button className="button ghost" onClick={() => { setEditing(false); setLayout(prev => adopt(prev, cards)); }}><X size={15} />退出编辑</button>
        </> : <button className="button" onClick={() => setEditing(true)}><Pencil size={15} />编辑界面</button>}
      </div>
    </div>
    <div className="dash-wrap" ref={wrap}>
      <div className="dash-canvas" style={{ height: colW ? maxY * (ROW_H + GAP) : 0 }}>
        {colW > 0 && layout.map(it => {
          const card = cards.find(c => c.id === it.id); if (!card) return null;
          return <section key={it.id} className={`dash-card ${editing ? 'editing' : ''} ${dragId === it.id ? 'dragging' : ''}`}
            style={{ left: it.x * (colW + GAP), top: it.y * (ROW_H + GAP), width: width(it.w), height: it.h * ROW_H + (it.h - 1) * GAP }}>
            <header className="dash-card-head" onPointerDown={e => startDrag(e, it, 'move')}>
              {editing && <GripVertical size={14} className="dash-grip" />}
              <span>{card.title}</span>
            </header>
            <div className="dash-card-body">{card.content}</div>
            {editing && <i className="dash-resize" onPointerDown={e => startDrag(e, it, 'resize')} />}
          </section>;
        })}
      </div>
    </div>
  </>;
}
