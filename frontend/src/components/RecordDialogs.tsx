import { useState } from 'react';
import { Button, Group, Modal, SimpleGrid, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { DateTimePicker } from '@mantine/dates';
import { api, Category, PcapRecord, ReplayStatus } from '../api';
import { ceilSecond, DATE_TIME_FORMAT, floorSecond, formatTime } from '../time';

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
    <Modal opened onClose={onClose} title={`Play "${record.name}"`} centered>
      <Stack>
        {listens ? (
          <>
            <Text size="sm" c="dimmed">The player will listen on this port and wait for the service to connect.</Text>
            <TextInput label="Listen port" value={port} onChange={(e) => setPort(e.currentTarget.value)} inputMode="numeric" />
          </>
        ) : (
          <>
            <TextInput label="Target IP / host" value={host} onChange={(e) => setHost(e.currentTarget.value)}
              placeholder="e.g. 10.0.0.5" data-autofocus />
            <TextInput label="Target port" value={port} onChange={(e) => setPort(e.currentTarget.value)} inputMode="numeric" />
          </>
        )}
      </Stack>
      <Group justify="flex-end" mt="lg">
        <Button variant="default" onClick={onClose}>Cancel</Button>
        <Button loading={busy} onClick={start}>Start</Button>
      </Group>
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
  const [start, setStart] = useState<Date | null>(new Date(record.startTime));
  const [end, setEnd] = useState<Date | null>(new Date(record.endTime));
  const [busy, setBusy] = useState(false);

  async function save() {
    if (!start || !end) {
      onError('Select a start and an end time');
      return;
    }
    setBusy(true);
    try {
      await api.updateRecord(record.id, { name, description, startTime: start.getTime(), endTime: end.getTime() });
      onSaved();
      onClose();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal opened onClose={onClose} title="Edit record" centered size="lg">
      <Stack>
        <TextInput label="Name" value={name} onChange={(e) => setName(e.currentTarget.value)} />
        <Textarea label="Description" value={description} onChange={(e) => setDescription(e.currentTarget.value)} rows={3} />
        <SimpleGrid cols={2}>
          <DateTimePicker label="Start time" value={start} onChange={setStart} withSeconds valueFormat={DATE_TIME_FORMAT} />
          <DateTimePicker label="End time" value={end} onChange={setEnd} withSeconds valueFormat={DATE_TIME_FORMAT} />
        </SimpleGrid>
        <Text size="xs" c="dimmed">
          Recording: {formatTime(floorSecond(record.recordingStart))} - {formatTime(ceilSecond(record.recordingEnd))} (Israel time)
        </Text>
      </Stack>
      <Group justify="flex-end" mt="lg">
        <Button variant="default" onClick={onClose}>Cancel</Button>
        <Button loading={busy} onClick={save}>Save</Button>
      </Group>
    </Modal>
  );
}
