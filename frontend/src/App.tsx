import { useCallback, useEffect, useState } from 'react';
import { AppShell, Button, Container, Group, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { api, Category, isReplayActive, PcapRecord, ReplayStatus, Tag } from './api';
import { NowPlaying } from './components/NowPlaying';
import { CategoriesPage } from './pages/CategoriesPage';
import { PlayerPage } from './pages/PlayerPage';
import { UploadPage } from './pages/UploadPage';

type Page = 'player' | 'upload' | 'categories';

const PAGES: { page: Page; label: string }[] = [
  { page: 'player', label: 'Player' },
  { page: 'upload', label: 'Upload' },
  { page: 'categories', label: 'Categories & Tags' },
];

/** Data loaded once here and passed down to every page. */
export interface AppData {
  categories: Category[];
  tags: Tag[];
  records: PcapRecord[];
  replay: ReplayStatus | null;
  /** Loads categories, tags and records again (call after any change). */
  reload: () => Promise<void>;
  /** Shows an error notification. */
  showError: (error: unknown) => void;
  setReplay: (status: ReplayStatus) => void;
}

export default function App() {
  const [page, setPage] = useState<Page>('player');
  const [categories, setCategories] = useState<Category[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
  const [records, setRecords] = useState<PcapRecord[]>([]);
  const [replay, setReplay] = useState<ReplayStatus | null>(null);
  // The now-playing bar can be closed after a replay ends. We remember which status was closed,
  // so the bar shows up again as soon as the status changes (e.g. a new replay starts).
  const [closedBarKey, setClosedBarKey] = useState('');

  const showError = useCallback((e: unknown) => {
    notifications.show({
      color: 'red',
      title: 'Error',
      message: e instanceof Error ? e.message : String(e),
      autoClose: 8000,
    });
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
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" gap="lg">
          <Title order={4}>PCAP Player</Title>
          <Group gap={4}>
            {PAGES.map((p) => (
              <Button key={p.page} variant={page === p.page ? 'light' : 'subtle'}
                color={page === p.page ? undefined : 'gray'} onClick={() => setPage(p.page)}>
                {p.label}
              </Button>
            ))}
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Main>
        <Container size="lg" px={0}>
          {showBar && replay && (
            <NowPlaying status={replay} onStop={stopReplay} onClose={() => setClosedBarKey(barKey)} />
          )}

          {page === 'player' && <PlayerPage data={data} />}
          {page === 'upload' && <UploadPage data={data} onSaved={() => setPage('player')} onGoToCategories={() => setPage('categories')} />}
          {page === 'categories' && <CategoriesPage data={data} />}
        </Container>
      </AppShell.Main>
    </AppShell>
  );
}
