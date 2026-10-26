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

/** The bar at the top of every page that shows the current (or just ended) replay. */
export function NowPlaying({ status, onStop, onClose }: Props) {
  const active = status.state === 'WAITING' || status.state === 'PLAYING';
  const totalMillis = status.elapsedMillis + status.remainingMillis;
  const percent = status.state === 'FINISHED' ? 100 : totalMillis > 0 ? (status.elapsedMillis / totalMillis) * 100 : 0;
  const stateText = status.state === 'WAITING'
    ? `Waiting for connection on port ${status.listenPort}`
    : STATE_LABELS[status.state] ?? status.state;

  return (
    <div className={`now-playing state-${status.state.toLowerCase()}`}>
      <div className="now-playing-top">
        <div>
          <span className="now-playing-name">{status.recordName}</span>
          <span className="muted"> &middot; {status.mode ? MODE_LABELS[status.mode] : ''} &middot; {status.target}</span>
        </div>
        <div className="now-playing-state">{stateText}</div>
        {active
          ? <button className="danger" onClick={onStop}>Stop</button>
          : <button onClick={onClose}>Close</button>}
      </div>

      <div className="progress"><div className="progress-fill" style={{ width: `${percent}%` }} /></div>

      <div className="now-playing-stats">
        <span>Elapsed {formatDuration(status.elapsedMillis)}</span>
        <span>Remaining {formatDuration(status.remainingMillis)}</span>
        <span>Recording time {status.recordingClock ? formatTime(status.recordingClock) : '-'}</span>
        <span>Messages {status.sent} / {status.total}</span>
      </div>

      {status.state === 'FAILED' && <div className="error-text">Error: {status.error}</div>}
    </div>
  );
}
