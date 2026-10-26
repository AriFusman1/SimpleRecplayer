import { useCallback, useEffect, useState } from 'react';
import { api, Category, isReplayActive, PcapRecord, ReplayStatus, Tag } from './api';
import { Modal } from './components/Modal';
import { NowPlaying } from './components/NowPlaying';
import { CategoriesPage } from './pages/CategoriesPage';
import { PlayerPage } from './pages/PlayerPage';
import { UploadPage } from './pages/UploadPage';

type Page = 'player' | 'upload' | 'categories';

/** Data loaded once here and passed down to every page. */
export interface AppData {
  categories: Category[];
  tags: Tag[];
  records: PcapRecord[];
  replay: ReplayStatus | null;
  /** Loads categories, tags and records again (call after any change). */
  reload: () => Promise<void>;
  /** Shows the error popup. */
  showError: (error: unknown) => void;
  setReplay: (status: ReplayStatus) => void;
}

export default function App() {
  const [page, setPage] = useState<Page>('player');
  const [categories, setCategories] = useState<Category[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
  const [records, setRecords] = useState<PcapRecord[]>([]);
  const [replay, setReplay] = useState<ReplayStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  // The now-playing bar can be closed after a replay ends. We remember which status was closed,
  // so the bar shows up again as soon as the status changes (e.g. a new replay starts).
  const [closedBarKey, setClosedBarKey] = useState('');

  const showError = useCallback((e: unknown) => {
    setError(e instanceof Error ? e.message : String(e));
  }, []);

  const reload = useCallback(async () => {
    try {
      const [c, t, r] = await Promise.all([api.categories(), api.tags(), api.records()]);
      setCategories(c);
      setTags(t);
      setRecords(r);
    } catch (e) {
      showError(e);
    }
  }, [showError]);

  useEffect(() => {
    reload();
  }, [reload]);

  // Poll the replay status every second.
  useEffect(() => {
    const timer = setInterval(async () => {
      try {
        setReplay(await api.replayStatus());
      } catch {
        // server not reachable right now: keep showing the last status
      }
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  async function stopReplay() {
    try {
      setReplay(await api.stopReplay());
    } catch (e) {
      showError(e);
    }
  }

  const barKey = replay ? `${replay.recordId}|${replay.state}|${replay.sent}` : '';
  const showBar = replay !== null && replay.state !== 'IDLE' && (isReplayActive(replay) || barKey !== closedBarKey);

  const data: AppData = { categories, tags, records, replay, reload, showError, setReplay };

  return (
    <div className="app">
      <header className="nav">
        <div className="nav-title">PCAP Player</div>
        <nav>
          <button className={page === 'player' ? 'nav-link active' : 'nav-link'} onClick={() => setPage('player')}>Player</button>
          <button className={page === 'upload' ? 'nav-link active' : 'nav-link'} onClick={() => setPage('upload')}>Upload</button>
          <button className={page === 'categories' ? 'nav-link active' : 'nav-link'} onClick={() => setPage('categories')}>Categories &amp; Tags</button>
        </nav>
      </header>

      <main className="content">
        {showBar && replay && (
          <NowPlaying status={replay} onStop={stopReplay} onClose={() => setClosedBarKey(barKey)} />
        )}

        {page === 'player' && <PlayerPage data={data} />}
        {page === 'upload' && <UploadPage data={data} onSaved={() => setPage('player')} onGoToCategories={() => setPage('categories')} />}
        {page === 'categories' && <CategoriesPage data={data} />}
      </main>

      {error && (
        <Modal title="Error" onClose={() => setError(null)} footer={<button onClick={() => setError(null)}>OK</button>}>
          <p className="error-text">{error}</p>
        </Modal>
      )}
    </div>
  );
}
