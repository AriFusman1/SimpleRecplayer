import { useState } from 'react';
import { api, isReplayActive, PcapRecord } from '../api';
import { AppData } from '../App';
import { ConfirmDialog } from '../components/Modal';
import { RecordCard } from '../components/RecordCard';
import { EditDialog, PlayDialog } from '../components/RecordDialogs';
import { parseDate } from '../time';

/** Main page: search and filter the records, then play / edit / download / delete them. */
export function PlayerPage({ data }: { data: AppData }) {
  const { categories, tags, records, replay, reload, showError, setReplay } = data;

  const [search, setSearch] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [tagId, setTagId] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');

  const [playing, setPlaying] = useState<PcapRecord | null>(null);
  const [editing, setEditing] = useState<PcapRecord | null>(null);
  const [deleting, setDeleting] = useState<PcapRecord | null>(null);

  // Filtering is done here in the browser; the list is small enough.
  const text = search.trim().toLowerCase();
  const from = fromDate.trim() ? parseDate(fromDate, false) : null;
  const to = toDate.trim() ? parseDate(toDate, true) : null;
  const visible = records.filter((r) =>
    (!text || r.name.toLowerCase().includes(text) || r.description.toLowerCase().includes(text)) &&
    (!categoryId || r.categoryId === categoryId) &&
    (!tagId || r.tagIds.includes(tagId)) &&
    // date range: keep records whose time range overlaps [from, to]
    (from === null || r.endTime >= from) &&
    (to === null || r.startTime <= to));

  const dateError = (fromDate.trim() && from === null) || (toDate.trim() && to === null);

  async function confirmDelete() {
    if (!deleting) return;
    try {
      await api.deleteRecord(deleting.id);
      await reload();
    } catch (e) {
      showError(e);
    }
    setDeleting(null);
  }

  const categoryOf = (r: PcapRecord) => categories.find((c) => c.id === r.categoryId);
  const playingCategory = playing ? categoryOf(playing) : undefined;

  return (
    <div>
      <div className="toolbar">
        <input className="grow" placeholder="Search name or description" value={search}
          onChange={(e) => setSearch(e.target.value)} />
        <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
          <option value="">All categories</option>
          {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        <select value={tagId} onChange={(e) => setTagId(e.target.value)}>
          <option value="">All tags</option>
          {tags.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
        </select>
        <input className="date" placeholder="From DD/MM/YYYY" value={fromDate} onChange={(e) => setFromDate(e.target.value)} />
        <input className="date" placeholder="To DD/MM/YYYY" value={toDate} onChange={(e) => setToDate(e.target.value)} />
      </div>
      {dateError && <p className="error-text small">Dates must look like DD/MM/YYYY</p>}

      <p className="muted small">{visible.length} of {records.length} records</p>

      {visible.map((r) => (
        <RecordCard
          key={r.id}
          record={r}
          category={categoryOf(r)}
          tags={tags}
          playDisabled={isReplayActive(replay)}
          onPlay={() => setPlaying(r)}
          onEdit={() => setEditing(r)}
          onDelete={() => setDeleting(r)}
        />
      ))}
      {records.length === 0 && <div className="empty">No records yet. Use the Upload page to add one.</div>}

      {playing && playingCategory && (
        <PlayDialog record={playing} category={playingCategory} onStarted={setReplay}
          onError={showError} onClose={() => setPlaying(null)} />
      )}
      {editing && (
        <EditDialog record={editing} onSaved={reload} onError={showError} onClose={() => setEditing(null)} />
      )}
      {deleting && (
        <ConfirmDialog
          title="Delete record"
          message={<p>Delete <b>{deleting.name}</b> and its pcap file? This cannot be undone.</p>}
          confirmLabel="Delete"
          onConfirm={confirmDelete}
          onCancel={() => setDeleting(null)}
        />
      )}
    </div>
  );
}
