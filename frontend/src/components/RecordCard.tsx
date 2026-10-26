import { Badge, Button, Card, Group, SimpleGrid, Text } from '@mantine/core';
import { api, Category, filtersOnSource, MODE_LABELS, PcapRecord, Tag } from '../api';
import { formatDuration, formatSize, formatTime } from '../time';
import { Field, Warnings } from './Field';

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
    <Card withBorder mb="md">
      <Group justify="space-between" align="flex-start" mb="md">
        <div>
          <Text fw={600}>{record.name}</Text>
          {record.description && <Text size="sm" c="dimmed">{record.description}</Text>}
        </div>
        <Group gap="xs">
          <Button size="xs" disabled={playDisabled} onClick={onPlay}
            title={playDisabled ? 'Another replay is running' : ''}>Play</Button>
          <Button size="xs" variant="default" onClick={onEdit}>Edit</Button>
          <Button size="xs" variant="default" component="a" href={api.downloadUrl(record.id)}>Download</Button>
          <Button size="xs" color="red" variant="light" onClick={onDelete}>Delete</Button>
        </Group>
      </Group>

      <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md" verticalSpacing="sm">
        <Field label="Category">
          {category ? `${category.name} (${MODE_LABELS[category.mode]})` : 'Unknown'}
        </Field>
        <Field label="Tags">
          {recordTags.length === 0 ? '-' : (
            <Group gap={4}>{recordTags.map((t) => <Badge key={t.id} variant="light" size="sm">{t.name}</Badge>)}</Group>
          )}
        </Field>
        <Field label="Time range">{formatTime(record.startTime)} - {formatTime(record.endTime)}</Field>
        <Field label="Duration">{formatDuration(record.stats.durationMillis)}</Field>
        <Field label="Messages">{record.stats.messageCount}</Field>
        <Field label={`${side} ports`}>{record.stats.protocol} {record.ports.join(', ')}</Field>
        <Field label={`${side} IP`}>{record.ipFilter ?? 'All IPs'}</Field>
        <Field label="File">{record.fileName} ({formatSize(record.fileSize)})</Field>
        <Field label="Uploaded">{formatTime(record.uploadDate)}</Field>
      </SimpleGrid>

      <Warnings warnings={record.stats.warnings} />
    </Card>
  );
}
