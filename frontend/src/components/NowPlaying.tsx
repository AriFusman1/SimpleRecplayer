import { Alert, Badge, Button, Card, Group, Progress, Text } from '@mantine/core';
import { MODE_LABELS, ReplayStatus } from '../api';
import { formatDuration, formatTime } from '../time';

interface Props {
  status: ReplayStatus;
  onStop: () => void;
  onClose: () => void;
}

const STATE_LABELS: Record<string, string> = {
  PLAYING: 'Playing',
  FINISHED: 'Finished',
  STOPPED: 'Stopped',
  FAILED: 'Failed',
};

const STATE_COLORS: Record<string, string> = {
  WAITING: 'yellow',
  PLAYING: 'blue',
  FINISHED: 'green',
  STOPPED: 'gray',
  FAILED: 'red',
};

/** The bar at the top of every page that shows the current (or just ended) replay. */
export function NowPlaying({ status, onStop, onClose }: Props) {
  const active = status.state === 'WAITING' || status.state === 'PLAYING';
  const totalMillis = status.elapsedMillis + status.remainingMillis;
  const percent = status.state === 'FINISHED' ? 100 : totalMillis > 0 ? (status.elapsedMillis / totalMillis) * 100 : 0;
  const stateText = status.state === 'WAITING'
    ? `Waiting for connection on port ${status.listenPort}`
    : STATE_LABELS[status.state] ?? status.state;
  const color = STATE_COLORS[status.state] ?? 'gray';

  return (
    <Card withBorder mb="md">
      <Group justify="space-between" wrap="nowrap">
        <Text>
          <Text span fw={600}>{status.recordName}</Text>
          <Text span c="dimmed"> &middot; {status.mode ? MODE_LABELS[status.mode] : ''} &middot; {status.target}</Text>
        </Text>
        <Group gap="sm" wrap="nowrap">
          <Badge color={color} variant="light">{stateText}</Badge>
          {active
            ? <Button size="xs" color="red" variant="light" onClick={onStop}>Stop</Button>
            : <Button size="xs" variant="default" onClick={onClose}>Close</Button>}
        </Group>
      </Group>

      <Progress value={percent} color={color} mt="sm" />

      <Group gap="xl" mt="xs">
        <Text size="sm" c="dimmed">Elapsed {formatDuration(status.elapsedMillis)}</Text>
        <Text size="sm" c="dimmed">Remaining {formatDuration(status.remainingMillis)}</Text>
        <Text size="sm" c="dimmed">Recording time {status.recordingClock ? formatTime(status.recordingClock) : '-'}</Text>
        <Text size="sm" c="dimmed">Messages {status.sent} / {status.total}</Text>
      </Group>

      {status.state === 'FAILED' && <Alert color="red" mt="sm" p="xs">Error: {status.error}</Alert>}
    </Card>
  );
}
