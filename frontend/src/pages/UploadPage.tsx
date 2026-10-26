import { useState } from 'react';
import { Anchor, Button, Card, Group, MultiSelect, Select, SimpleGrid, Stack, Text, Textarea, TextInput, Title } from '@mantine/core';
import { DateTimePicker } from '@mantine/dates';
import { Dropzone } from '@mantine/dropzone';
import { AnalyzeResult, api, filtersOnSource, MODE_HINTS, MODE_LABELS, protocolOf, ValidateResult } from '../api';
import { AppData } from '../App';
import { Field, Warnings } from '../components/Field';
import { ceilSecond, DATE_TIME_FORMAT, floorSecond, formatDuration, formatSize, formatTime } from '../time';

interface Props {
  data: AppData;
  onSaved: () => void;
  onGoToCategories: () => void;
}

/**
 * Upload flow:
 *  1. drop / pick a file -> POST /api/pcap/analyze -> summary
 *  2. fill the form
 *  3. "Check" -> POST /api/pcap/validate -> message count, duration, warnings
 *  4. "Save"  -> POST /api/records
 */
export function UploadPage({ data, onSaved, onGoToCategories }: Props) {
  const { categories, tags, reload, showError } = data;

  const [analyzing, setAnalyzing] = useState(false);
  const [file, setFile] = useState<AnalyzeResult | null>(null);

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [ports, setPorts] = useState<number[]>([]);
  const [ipFilter, setIpFilter] = useState('');
  const [start, setStart] = useState<Date | null>(null);
  const [end, setEnd] = useState<Date | null>(null);
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
      setStart(new Date(floorSecond(result.summary.recordingStart)));
      setEnd(new Date(ceilSecond(result.summary.recordingEnd)));
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

  function onCategoryChange(id: string | null) {
    changed(setCategoryId)(id);
    setPorts([]); // the port list depends on the mode
    setIpFilter('');
    setTagIds((ids) => ids.filter((tid) => tags.find((t) => t.id === tid)?.categoryId === null));
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
    if (!start || !end) {
      showError('Select a start and an end time');
      return null;
    }
    return {
      uploadId: file.uploadId, categoryId: category.id, ports, ipFilter: ipFilter || null,
      startTime: start.getTime(), endTime: end.getTime(),
    };
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
  const side = onSource ? 'Source' : 'Destination';

  return (
    <div>
      <Title order={3} mb="md">Upload a recording</Title>

      <Dropzone
        onDrop={(files) => analyze(files[0])}
        onReject={() => showError('Drop exactly one .pcap file')}
        multiple={false}
        loading={analyzing}
        mb="md"
      >
        <Stack align="center" gap={4} py="lg" style={{ pointerEvents: 'none' }}>
          <Text>Drag a .pcap file here, or click to browse</Text>
          <Text size="sm" c="dimmed">One classic pcap file (not pcapng)</Text>
        </Stack>
      </Dropzone>

      {file && summary && (
        <>
          <Card withBorder mb="md">
            <Text fw={600} mb="sm">File</Text>
            <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md" verticalSpacing="sm">
              <Field label="Name">{file.fileName} ({formatSize(file.fileSize)})</Field>
              <Field label="Recording start">{formatTime(summary.recordingStart)}</Field>
              <Field label="Recording end">{formatTime(summary.recordingEnd)}</Field>
              <Field label="Packets">{summary.totalPackets} total, {summary.usablePackets} with TCP/UDP data</Field>
              <Field label="Protocols">{summary.protocols.join(', ') || '-'}</Field>
            </SimpleGrid>
            <Warnings warnings={summary.warnings} />
          </Card>

          <Card withBorder mb="md">
            <Text fw={600} mb="sm">Record</Text>
            <Stack>
              <TextInput label="Name" value={name} onChange={(e) => setName(e.currentTarget.value)} />
              <Textarea label="Description" rows={2} value={description} onChange={(e) => setDescription(e.currentTarget.value)} />

              <div>
                <Select label="Category" placeholder="Select a category" value={categoryId} onChange={onCategoryChange}
                  data={categories.map((c) => ({ value: c.id, label: `${c.name} (${MODE_LABELS[c.mode]})` }))} />
                {categories.length === 0 && (
                  <Text size="sm" mt={4}>
                    No categories yet. <Anchor component="button" size="sm" onClick={onGoToCategories}>Create one</Anchor> first.
                  </Text>
                )}
                {category && <Text size="sm" c="dimmed" mt={4}>{MODE_HINTS[category.mode]}.</Text>}
              </div>

              {category && (
                <>
                  {portList.length === 0
                    ? <Text size="sm" c="red">The file has no {protocolOf(category.mode)} packets with data.</Text>
                    : (
                      <MultiSelect label={`${side} ports (${protocolOf(category.mode)})`} placeholder="Select ports"
                        value={ports.map(String)} onChange={(values) => changed(setPorts)(values.map(Number))}
                        data={portList.map((p) => ({ value: String(p.port), label: `${p.port} (${p.packets} packets)` }))} />
                    )}

                  <Select label={`${side} IP`} placeholder="All IPs" clearable value={ipFilter || null}
                    onChange={(value) => changed(setIpFilter)(value ?? '')}
                    data={ipList.map((i) => ({ value: i.ip, label: `${i.ip} (${i.packets} packets)` }))} />
                </>
              )}

              <SimpleGrid cols={2}>
                <DateTimePicker label="Start time" value={start} onChange={changed(setStart)} withSeconds valueFormat={DATE_TIME_FORMAT} />
                <DateTimePicker label="End time" value={end} onChange={changed(setEnd)} withSeconds valueFormat={DATE_TIME_FORMAT} />
              </SimpleGrid>

              {category && (
                availableTags.length === 0
                  ? <Text size="sm" c="dimmed">No tags for this category.</Text>
                  : (
                    <MultiSelect label="Tags" placeholder="Select tags" value={tagIds} onChange={setTagIds}
                      data={availableTags.map((t) => ({ value: t.id, label: t.categoryId ? t.name : `${t.name} (global)` }))} />
                  )
              )}

              <Group>
                <Button variant="default" loading={busy && !check} onClick={runCheck}>Check</Button>
              </Group>
            </Stack>
          </Card>

          {check && (
            <Card withBorder mb="md">
              <Text fw={600} mb="sm">Before saving</Text>
              <SimpleGrid cols={{ base: 2, sm: 4 }} spacing="md">
                <Field label="Messages">{check.messageCount}</Field>
                <Field label="Duration">{formatDuration(check.durationMillis)}</Field>
                <Field label="Category">{check.categoryName} ({MODE_LABELS[check.mode]})</Field>
                <Field label="Protocol">{check.protocol}</Field>
              </SimpleGrid>
              <Warnings warnings={check.warnings} />
              <Group mt="md">
                <Button loading={busy} onClick={save}>Save record</Button>
              </Group>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
