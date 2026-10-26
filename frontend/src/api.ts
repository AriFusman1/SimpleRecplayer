// Types that match the backend JSON, and one small function per API call.

export type Mode = 'TCP_SERVER' | 'UDP_SERVER' | 'TCP_CLIENT';

export const MODE_LABELS: Record<Mode, string> = {
  TCP_SERVER: 'TCP server',
  UDP_SERVER: 'UDP server',
  TCP_CLIENT: 'TCP client',
};

/** What the player does in each mode (the service under test plays the original role). */
export const MODE_HINTS: Record<Mode, string> = {
  TCP_SERVER: 'Player connects to the service as a TCP client',
  UDP_SERVER: 'Player sends UDP datagrams to the service',
  TCP_CLIENT: 'Player listens on a port and waits for the service to connect',
};

/** TCP_CLIENT mode filters on the source side; the others on the destination side. */
export function filtersOnSource(mode: Mode): boolean {
  return mode === 'TCP_CLIENT';
}

export function protocolOf(mode: Mode): string {
  return mode === 'UDP_SERVER' ? 'UDP' : 'TCP';
}

export interface Category {
  id: string;
  name: string;
  mode: Mode;
}

export interface Tag {
  id: string;
  name: string;
  categoryId: string | null; // null = global
}

export interface PcapRecord {
  id: string;
  name: string;
  description: string;
  categoryId: string;
  tagIds: string[];
  ports: number[];
  ipFilter: string | null;
  startTime: number;
  endTime: number;
  recordingStart: number;
  recordingEnd: number;
  stats: { messageCount: number; durationMillis: number; protocol: string; warnings: string[] };
  fileName: string;
  fileSize: number;
  uploadDate: number;
}

export interface PortCount {
  protocol: string;
  port: number;
  packets: number;
}

export interface IpCount {
  ip: string;
  packets: number;
}

export interface Summary {
  recordingStart: number;
  recordingEnd: number;
  totalPackets: number;
  usablePackets: number;
  protocols: string[];
  dstPorts: PortCount[];
  srcPorts: PortCount[];
  dstIps: IpCount[];
  srcIps: IpCount[];
  warnings: string[];
}

export interface AnalyzeResult {
  uploadId: string;
  fileName: string;
  fileSize: number;
  summary: Summary;
}

export interface ValidateResult {
  messageCount: number;
  durationMillis: number;
  protocol: string;
  categoryName: string;
  mode: Mode;
  warnings: string[];
}

export type ReplayState = 'IDLE' | 'WAITING' | 'PLAYING' | 'FINISHED' | 'STOPPED' | 'FAILED';

export interface ReplayStatus {
  state: ReplayState;
  recordId: string | null;
  recordName: string | null;
  mode: Mode | null;
  target: string | null;
  listenPort: number;
  sent: number;
  total: number;
  elapsedMillis: number;
  remainingMillis: number;
  recordingClock: number;
  error: string | null;
}

export function isReplayActive(status: ReplayStatus | null): boolean {
  return status !== null && (status.state === 'WAITING' || status.state === 'PLAYING');
}

/** Sends a request and returns the parsed JSON. Throws an Error with the server's message on failure. */
async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const options: RequestInit = { method };
  if (body instanceof FormData) {
    options.body = body; // the browser sets the multipart Content-Type itself
  } else if (body !== undefined) {
    options.body = JSON.stringify(body);
    options.headers = { 'Content-Type': 'application/json' };
  }
  const response = await fetch(url, options);
  const text = await response.text();
  let data: any = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    // not JSON (e.g. a proxy error page)
  }
  if (!response.ok) {
    throw new Error(data?.error ?? `Request failed (HTTP ${response.status})`);
  }
  return data as T;
}

export const api = {
  analyze(file: File) {
    const form = new FormData();
    form.append('file', file);
    return request<AnalyzeResult>('POST', '/api/pcap/analyze', form);
  },
  validate(body: {
    uploadId: string; categoryId: string; ports: number[]; ipFilter: string | null; startTime: number; endTime: number;
  }) {
    return request<ValidateResult>('POST', '/api/pcap/validate', body);
  },
  saveRecord(body: {
    uploadId: string; name: string; description: string; categoryId: string; tagIds: string[];
    ports: number[]; ipFilter: string | null; startTime: number; endTime: number;
  }) {
    return request<PcapRecord>('POST', '/api/records', body);
  },

  records: () => request<PcapRecord[]>('GET', '/api/records'),
  updateRecord: (id: string, body: { name: string; description: string; startTime: number; endTime: number }) =>
    request<PcapRecord>('PUT', `/api/records/${id}`, body),
  deleteRecord: (id: string) => request<unknown>('DELETE', `/api/records/${id}`),
  downloadUrl: (id: string) => `/api/records/${id}/download`,

  categories: () => request<Category[]>('GET', '/api/categories'),
  createCategory: (name: string, mode: Mode) => request<Category>('POST', '/api/categories', { name, mode }),
  renameCategory: (id: string, name: string) => request<Category>('PUT', `/api/categories/${id}`, { name }),
  deleteCategory: (id: string) => request<unknown>('DELETE', `/api/categories/${id}`),

  tags: () => request<Tag[]>('GET', '/api/tags'),
  createTag: (name: string, categoryId: string | null) => request<Tag>('POST', '/api/tags', { name, categoryId }),
  renameTag: (id: string, name: string) => request<Tag>('PUT', `/api/tags/${id}`, { name }),
  deleteTag: (id: string) => request<unknown>('DELETE', `/api/tags/${id}`),

  startReplay: (recordId: string, host: string, port: number) =>
    request<ReplayStatus>('POST', '/api/replay/start', { recordId, host, port }),
  stopReplay: () => request<ReplayStatus>('POST', '/api/replay/stop'),
  replayStatus: () => request<ReplayStatus>('GET', '/api/replay/status'),
};
