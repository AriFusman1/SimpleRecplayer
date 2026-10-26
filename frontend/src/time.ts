// All times in the UI are shown in Israel time as DD/MM/YYYY HH:MM:SS.
// The backend always sends and receives epoch milliseconds.

const formatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Jerusalem',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

/** The Israel wall-clock parts of an epoch time. */
function israelParts(epochMillis: number) {
  const parts: Record<string, string> = {};
  for (const p of formatter.formatToParts(new Date(epochMillis))) {
    parts[p.type] = p.value;
  }
  return parts;
}

/** epoch millis -> "DD/MM/YYYY HH:MM:SS" (Israel time) */
export function formatTime(epochMillis: number): string {
  if (!epochMillis) return '';
  const p = israelParts(epochMillis);
  return `${p.day}/${p.month}/${p.year} ${p.hour}:${p.minute}:${p.second}`;
}

/** How far Israel time is ahead of UTC at this moment, in millis (changes with daylight saving). */
function israelOffset(epochMillis: number): number {
  const p = israelParts(epochMillis);
  const asUtc = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  return asUtc - Math.floor(epochMillis / 1000) * 1000;
}

/** "DD/MM/YYYY HH:MM:SS" (Israel time) -> epoch millis, or null if the text is not a valid time. */
export function parseTime(text: string): number | null {
  const m = /^(\d{2})\/(\d{2})\/(\d{4}) (\d{2}):(\d{2}):(\d{2})$/.exec(text.trim());
  if (!m) return null;
  const [, day, month, year, hour, minute, second] = m.map(Number);
  // First pretend the time is UTC, then subtract Israel's offset. Done twice in case the
  // first guess lands on the other side of a daylight-saving change.
  const wallAsUtc = Date.UTC(year, month - 1, day, hour, minute, second);
  let guess = wallAsUtc - israelOffset(wallAsUtc);
  guess = wallAsUtc - israelOffset(guess);
  // Reject impossible dates like 31/02 by checking that the result formats back to the same text.
  return formatTime(guess) === text.trim() ? guess : null;
}

/** "DD/MM/YYYY" -> epoch millis at the start (00:00:00) or end (23:59:59) of that day. */
export function parseDate(text: string, endOfDay: boolean): number | null {
  return parseTime(`${text.trim()} ${endOfDay ? '23:59:59' : '00:00:00'}`);
}

/** Whole seconds, rounded down / up (the UI only shows seconds). */
export function floorSecond(millis: number): number {
  return Math.floor(millis / 1000) * 1000;
}
export function ceilSecond(millis: number): number {
  return Math.ceil(millis / 1000) * 1000;
}

/** 3725000 -> "01:02:05" */
export function formatDuration(millis: number): string {
  const total = Math.max(0, Math.round(millis / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return [h, m, s].map((n) => String(n).padStart(2, '0')).join(':');
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}
