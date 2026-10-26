import { ReactNode } from 'react';
import { api, Category, filtersOnSource, MODE_LABELS, PcapRecord, Tag } from '../api';
import { formatDuration, formatSize, formatTime } from '../time';

interface Props {
  record: PcapRecord;
  category: Category | undefined;
  tags: Tag[];
  playDisabled: boolean;
  onPlay: () => void;
  onEdit: () => void;
  onDelete: () => void;
}

/** One record on the Player page, with all its details and action buttons. */
export function RecordCard({ record, category, tags, playDisabled, onPlay, onEdit, onDelete }: Props) {
  const recordTags = tags.filter((t) => record.tagIds.includes(t.id));
  const side = category && filtersOnSource(category.mode) ? 'Source' : 'Destination';

  return (
    <div className="card">
      <div className="card-header">
        <div>
          <div className="card-title">{record.name}</div>
          {record.description && <div className="muted">{record.description}</div>}
        </div>
        <div className="card-actions">
          <button className="primary" disabled={playDisabled} onClick={onPlay}
            title={playDisabled ? 'Another replay is running' : ''}>Play</button>
          <button onClick={onEdit}>Edit</button>
          <a className="button" href={api.downloadUrl(record.id)}>Download</a>
          <button className="danger" onClick={onDelete}>Delete</button>
        </div>
      </div>

      <div className="fields">
        <Field label="Category">
          {category ? `${category.name} (${MODE_LABELS[category.mode]})` : 'Unknown'}
        </Field>
        <Field label="Tags">
          {recordTags.length === 0 ? '-' : recordTags.map((t) => <span key={t.id} className="tag">{t.name}</span>)}
        </Field>
        <Field label="Time range">{formatTime(record.startTime)} - {formatTime(record.endTime)}</Field>
        <Field label="Duration">{formatDuration(record.stats.durationMillis)}</Field>
        <Field label="Messages">{record.stats.messageCount}</Field>
        <Field label={`${side} ports`}>{record.stats.protocol} {record.ports.join(', ')}</Field>
        <Field label={`${side} IP`}>{record.ipFilter ?? 'All IPs'}</Field>
        <Field label="File">{record.fileName} ({formatSize(record.fileSize)})</Field>
        <Field label="Uploaded">{formatTime(record.uploadDate)}</Field>
      </div>

      {record.stats.warnings.length > 0 && (
        <ul className="warnings">
          {record.stats.warnings.map((w) => <li key={w}>{w}</li>)}
        </ul>
      )}
    </div>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="field">
      <div className="field-label">{label}</div>
      <div>{children}</div>
    </div>
  );
}
