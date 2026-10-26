import { useState } from 'react';
import { AnalyzeResult, api, filtersOnSource, MODE_HINTS, MODE_LABELS, protocolOf, ValidateResult } from '../api';
import { AppData } from '../App';
import { ceilSecond, floorSecond, formatDuration, formatSize, formatTime, parseTime } from '../time';

interface Props {
  data: AppData;
  onSaved: () => void;
  onGoToCategories: () => void;
}

/**
 * Upload flow:
 *  1. pick a file -> POST /api/pcap/analyze -> summary
 *  2. fill the form
 *  3. "Check" -> POST /api/pcap/validate -> message count, duration, warnings
 *  4. "Save"  -> POST /api/records
 */
export function UploadPage({ data, onSaved, onGoToCategories }: Props) {
  const { categories, tags, reload, showError } = data;

  const [analyzing, setAnalyzing] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [file, setFile] = useState<AnalyzeResult | null>(null);

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [ports, setPorts] = useState<number[]>([]);
  const [ipFilter, setIpFilter] = useState('');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [tagIds, setTagIds] = useState<string[]>([]);

  // Result of "Check". Cleared whenever the form changes, so Save always matches what was checked.
  const [check, setCheck] = useState<ValidateResult | null>(null);
  const [busy, setBusy] = useState(false);

  const category = categories.find((c) => c.id === categoryId);
  const summary = file?.summary;

  async function analyze(picked: File) {
    setAnalyzing(true);
    setFile(null);
    setCheck(null);
    try {
      const result = await api.analyze(picked);
      setFile(result);
      setName(picked.name.replace(/\.pcap$/i, ''));
      setDescription('');
      setPorts([]);
      setIpFilter('');
      setTagIds([]);
      setStart(formatTime(floorSecond(result.summary.recordingStart)));
      setEnd(formatTime(ceilSecond(result.summary.recordingEnd)));
    } catch (e) {
      showError(e);
    } finally {
      setAnalyzing(false);
    }
  }

  /** Wraps a setter so that changing any field clears the last check. */
  function changed<T>(setter: (value: T) => void) {
    return (value: T) => {
      setter(value);
      setCheck(null);
    };
  }

  function onCategoryChange(id: string) {
    changed(setCategoryId)(id);
    setPorts([]); // the port list depends on the mode
    setIpFilter('');
    setTagIds((ids) => ids.filter((tid) => tags.find((t) => t.id === tid)?.categoryId === null));
  }

  function togglePort(port: number) {
    changed(setPorts)(ports.includes(port) ? ports.filter((p) => p !== port) : [...ports, port]);
  }

  function toggleTag(id: string) {
    setTagIds(tagIds.includes(id) ? tagIds.filter((t) => t !== id) : [...tagIds, id]);
  }

  /** Builds the request body from the form, or shows an error and returns null. */
  function formValues() {
    if (!file) return null;
    if (!category) {
      showError('Select a category');
      return null;
    }
    if (ports.length === 0) {
      showError('Select at least one port');
      return null;
    }
    const startTime = parseTime(start);
    const endTime = parseTime(end);
    if (startTime === null || endTime === null) {
      showError('Times must look like DD/MM/YYYY HH:MM:SS');
      return null;
    }
    return { uploadId: file.uploadId, categoryId: category.id, ports, ipFilter: ipFilter || null, startTime, endTime };
  }

  async function runCheck() {
    const values = formValues();
    if (!values) return;
    setBusy(true);
    try {
      setCheck(await api.validate(values));
    } catch (e) {
      showError(e);
    } finally {
      setBusy(false);
    }
  }

  async function save() {
    const values = formValues();
    if (!values) return;
    if (!name.trim()) {
      showError('Name is required');
      return;
    }
    setBusy(true);
    try {
      await api.saveRecord({ ...values, name, description, tagIds });
      await reload();
      setFile(null);
      setCheck(null);
      onSaved();
    } catch (e) {
      showError(e);
    } finally {
      setBusy(false);
    }
  }

  // Ports and IPs shown depend on the mode: destination side for TCP/UDP server, source side for TCP client.
  const onSource = category ? filtersOnSource(category.mode) : false;
  const portList = summary && category
    ? (onSource ? summary.srcPorts : summary.dstPorts).filter((p) => p.protocol === protocolOf(category.mode))
    : [];
  const ipList = summary ? (onSource ? summary.srcIps : summary.dstIps) : [];
  const availableTags = tags.filter((t) => t.categoryId === null || t.categoryId === categoryId);

  return (
    <div>
      <h2>Upload a recording</h2>

      <div
        className={`dropzone${dragOver ? ' over' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(false);
          if (e.dataTransfer.files.length !== 1) {
            showError('Drop exactly one .pcap file');
            return;
          }
          analyze(e.dataTransfer.files[0]);
        }}
      >
        {analyzing ? 'Analyzing...' : (
          <>
            Drag a .pcap file here, or{' '}
            <label className="link">browse
              <input type="file" accept=".pcap,.cap" hidden
                onChange={(e) => { if (e.target.files?.[0]) analyze(e.target.files[0]); e.target.value = ''; }} />
            </label>
          </>
        )}
      </div>

      {file && summary && (
        <>
          <div className="panel">
            <div className="panel-title">File</div>
            <div className="fields">
              <div className="field"><div className="field-label">Name</div>{file.fileName} ({formatSize(file.fileSize)})</div>
              <div className="field"><div className="field-label">Recording start</div>{formatTime(summary.recordingStart)}</div>
              <div className="field"><div className="field-label">Recording end</div>{formatTime(summary.recordingEnd)}</div>
              <div className="field"><div className="field-label">Packets</div>{summary.totalPackets} total, {summary.usablePackets} with TCP/UDP data</div>
              <div className="field"><div className="field-label">Protocols</div>{summary.protocols.join(', ') || '-'}</div>
            </div>
            {summary.warnings.length > 0 && (
              <ul className="warnings">{summary.warnings.map((w) => <li key={w}>{w}</li>)}</ul>
            )}
          </div>

          <div className="panel">
            <div className="panel-title">Record</div>

            <label>Name<input value={name} onChange={(e) => setName(e.target.value)} /></label>
            <label>Description<textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></label>

            <label>Category
              <select value={categoryId} onChange={(e) => onCategoryChange(e.target.value)}>
                <option value="">Select a category</option>
                {categories.map((c) => <option key={c.id} value={c.id}>{c.name} ({MODE_LABELS[c.mode]})</option>)}
              </select>
            </label>
            {categories.length === 0 && (
              <p className="small">No categories yet. <button className="link" onClick={onGoToCategories}>Create one</button> first.</p>
            )}

            {category && (
              <>
                <p className="muted small">{MODE_HINTS[category.mode]}.</p>

                <div className="label">{onSource ? 'Source' : 'Destination'} ports ({protocolOf(category.mode)})</div>
                {portList.length === 0
                  ? <p className="error-text small">The file has no {protocolOf(category.mode)} packets with data.</p>
                  : (
                    <div className="checklist">
                      {portList.map((p) => (
                        <label key={p.port} className="check">
                          <input type="checkbox" checked={ports.includes(p.port)} onChange={() => togglePort(p.port)} />
                          {p.port} <span className="muted">({p.packets} packets)</span>
                        </label>
                      ))}
                    </div>
                  )}

                <label>{onSource ? 'Source' : 'Destination'} IP
                  <select value={ipFilter} onChange={(e) => changed(setIpFilter)(e.target.value)}>
                    <option value="">All IPs</option>
                    {ipList.map((i) => <option key={i.ip} value={i.ip}>{i.ip} ({i.packets} packets)</option>)}
                  </select>
                </label>
              </>
            )}

            <div className="row">
              <label>Start time<input value={start} onChange={(e) => changed(setStart)(e.target.value)} /></label>
              <label>End time<input value={end} onChange={(e) => changed(setEnd)(e.target.value)} /></label>
            </div>

            {category && (
              <>
                <div className="label">Tags</div>
                {availableTags.length === 0 ? <p className="muted small">No tags for this category.</p> : (
                  <div className="checklist">
                    {availableTags.map((t) => (
                      <label key={t.id} className="check">
                        <input type="checkbox" checked={tagIds.includes(t.id)} onChange={() => toggleTag(t.id)} />
                        {t.name} <span className="muted">{t.categoryId ? '' : '(global)'}</span>
                      </label>
                    ))}
                  </div>
                )}
              </>
            )}

            <div className="actions">
              <button disabled={busy} onClick={runCheck}>Check</button>
            </div>
          </div>

          {check && (
            <div className="panel">
              <div className="panel-title">Before saving</div>
              <div className="fields">
                <div className="field"><div className="field-label">Messages</div>{check.messageCount}</div>
                <div className="field"><div className="field-label">Duration</div>{formatDuration(check.durationMillis)}</div>
                <div className="field"><div className="field-label">Category</div>{check.categoryName} ({MODE_LABELS[check.mode]})</div>
                <div className="field"><div className="field-label">Protocol</div>{check.protocol}</div>
              </div>
              {check.warnings.length > 0 && (
                <ul className="warnings">{check.warnings.map((w) => <li key={w}>{w}</li>)}</ul>
              )}
              <div className="actions">
                <button className="primary" disabled={busy} onClick={save}>{busy ? 'Saving...' : 'Save record'}</button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
