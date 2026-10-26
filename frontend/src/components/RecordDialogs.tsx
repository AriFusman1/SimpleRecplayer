import { useState } from 'react';
import { api, Category, PcapRecord, ReplayStatus } from '../api';
import { ceilSecond, floorSecond, formatTime, parseTime } from '../time';
import { Modal } from './Modal';

interface PlayProps {
  record: PcapRecord;
  category: Category;
  onStarted: (status: ReplayStatus) => void;
  onError: (e: unknown) => void;
  onClose: () => void;
}

/** Asks where to play: target IP + port, or only a listen port in TCP client mode. */
export function PlayDialog({ record, category, onStarted, onError, onClose }: PlayProps) {
  const listens = category.mode === 'TCP_CLIENT';
  const [host, setHost] = useState('');
  const [port, setPort] = useState('5000');
  const [busy, setBusy] = useState(false);

  async function start() {
    setBusy(true);
    try {
      onStarted(await api.startReplay(record.id, host.trim(), Number(port)));
      onClose();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={`Play "${record.name}"`}
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>Cancel</button>
          <button className="primary" disabled={busy} onClick={start}>{busy ? 'Starting...' : 'Start'}</button>
        </>
      }
    >
      {listens ? (
        <>
          <p className="muted">The player will listen on this port and wait for the service to connect.</p>
          <label>Listen port
            <input value={port} onChange={(e) => setPort(e.target.value)} inputMode="numeric" />
          </label>
        </>
      ) : (
        <>
          <label>Target IP / host
            <input value={host} onChange={(e) => setHost(e.target.value)} placeholder="e.g. 10.0.0.5" autoFocus />
          </label>
          <label>Target port
            <input value={port} onChange={(e) => setPort(e.target.value)} inputMode="numeric" />
          </label>
        </>
      )}
    </Modal>
  );
}

interface EditProps {
  record: PcapRecord;
  onSaved: () => void;
  onError: (e: unknown) => void;
  onClose: () => void;
}

/** Edits name, description and the time range (checked against the recording by the server). */
export function EditDialog({ record, onSaved, onError, onClose }: EditProps) {
  const [name, setName] = useState(record.name);
  const [description, setDescription] = useState(record.description);
  const [start, setStart] = useState(formatTime(record.startTime));
  const [end, setEnd] = useState(formatTime(record.endTime));
  const [busy, setBusy] = useState(false);

  async function save() {
    const startTime = parseTime(start);
    const endTime = parseTime(end);
    if (startTime === null || endTime === null) {
      onError('Times must look like DD/MM/YYYY HH:MM:SS');
      return;
    }
    setBusy(true);
    try {
      await api.updateRecord(record.id, { name, description, startTime, endTime });
      onSaved();
      onClose();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="Edit record"
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>Cancel</button>
          <button className="primary" disabled={busy} onClick={save}>{busy ? 'Saving...' : 'Save'}</button>
        </>
      }
    >
      <label>Name<input value={name} onChange={(e) => setName(e.target.value)} /></label>
      <label>Description<textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} /></label>
      <div className="row">
        <label>Start time<input value={start} onChange={(e) => setStart(e.target.value)} /></label>
        <label>End time<input value={end} onChange={(e) => setEnd(e.target.value)} /></label>
      </div>
      <p className="muted small">
        Recording: {formatTime(floorSecond(record.recordingStart))} - {formatTime(ceilSecond(record.recordingEnd))}
      </p>
    </Modal>
  );
}
