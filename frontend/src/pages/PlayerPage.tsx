import { useState } from 'react';
import { Card, Group, Select, Text, TextInput } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { api, isReplayActive, PcapRecord } from '../api';
import { AppData } from '../App';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { RecordCard } from '../components/RecordCard';
import { EditDialog, PlayDialog } from '../components/RecordDialogs';
import { DATE_FORMAT, dayBoundary } from '../time';

/** Main page: search and filter the records, then play / edit / download / delete them. */
export function PlayerPage({ data }: { data: AppData }) {
  const { categories, tags, records, replay, reload, showError, setReplay } = data;

  const [search, setSearch] = useState('');
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [tagId, setTagId] = useState<string | null>(null);
  const [fromDate, setFromDate] = useState<Date | null>(null);
  const [toDate, setToDate] = useState<Date | null>(null);

  const [playing, setPlaying] = useState<PcapRecord | null>(null);
  const [editing, setEditing] = useState<PcapRecord | null>(null);
  const [deleting, setDeleting] = useState<PcapRecord | null>(null);

  // Filtering is done here in the browser; the list is small enough.
  const text = search.trim().toLowerCase();
  const from = fromDate ? dayBoundary(fromDate, false) : null;
  const to = toDate ? dayBoundary(toDate, true) : null;
  const visible = records.filter((r) =>
    (!text || r.name.toLowerCase().includes(text) || r.description.toLowerCase().includes(text)) &&
    (!categoryId || r.categoryId === categoryId) &&
    (!tagId || r.tagIds.includes(tagId)) &&
    // date range: keep records whose time range overlaps [from, to]
    (from === null || r.endTime >= from) &&
    (to === null || r.startTime <= to));

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
      <Group gap="sm" mb="xs" align="flex-end">
        <TextInput style={{ flex: 1, minWidth: 200 }} placeholder="Search name or description" value={search}
          onChange={(e) => setSearch(e.currentTarget.value)} />
        <Select w={180} placeholder="All categories" clearable value={categoryId} onChange={setCategoryId}
          data={categories.map((c) => ({ value: c.id, label: c.name }))} />
        <Select w={160} placeholder="All tags" clearable value={tagId} onChange={setTagId}
          data={tags.map((t) => ({ value: t.id, label: t.name }))} />
        <DatePickerInput w={170} placeholder="From DD/MM/YYYY" clearable value={fromDate} onChange={setFromDate}
          valueFormat={DATE_FORMAT} />
        <DatePickerInput w={170} placeholder="To DD/MM/YYYY" clearable value={toDate} onChange={setToDate}
          valueFormat={DATE_FORMAT} />
      </Group>

      <Text size="sm" c="dimmed" mb="sm">{visible.length} of {records.length} records</Text>

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
      {records.length === 0 && (
        <Card withBorder py="xl">
          <Text ta="center" c="dimmed">No records yet. Use the Upload page to add one.</Text>
        </Card>
      )}

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
          message={<Text size="sm">Delete <b>{deleting.name}</b> and its pcap file? This cannot be undone.</Text>}
          confirmLabel="Delete"
          onConfirm={confirmDelete}
          onCancel={() => setDeleting(null)}
        />
      )}
    </div>
  );
}
