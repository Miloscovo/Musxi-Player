import { request, type CefTransport } from './transport.ts';
import { NativeError, type RequestOptions } from './types.ts';
// Contract: docs/app-updates.md and docs/app-update-installation.md. IDs stay opaque strings; times are Unix seconds.
export const checkStatuses = ['idle', 'checking', 'succeeded', 'failed'] as const;
export const updateResults = ['not_checked', 'development_build', 'no_releases', 'no_newer_release', 'no_compatible_asset', 'update_available'] as const;
export const downloadStatuses = ['idle', 'preparing', 'downloading', 'verifying', 'ready', 'installing', 'failed', 'cancelled'] as const;
export type CheckStatus = typeof checkStatuses[number];
export type UpdateResult = typeof updateResults[number];
export type DownloadStatus = typeof downloadStatuses[number];
export interface UpdateRelease {
  releaseId: string; assetId: string; version: string; tag: string; prerelease: boolean; publishedAt: string;
  notes: string; assetName: string; bytes: number; downloadAvailable: boolean;
  inAppDownloadAvailable?: boolean;
}
export interface DownloadCapabilities {
  installerDownload: boolean;
}
export interface DownloadState {
  status: DownloadStatus;
  reason: string;
  releaseId: string;
  assetId: string;
  version: string;
  receivedBytes: number;
  totalBytes: number;
  canCancel: boolean;
  canInstall: boolean;
  superseded: boolean;
}
export interface UpdateState {
  current: { version: string; commit: string; releaseBuild: boolean; architecture: string };
  source: string; includesPrereleases: boolean;
  // Optional for older backends; current native builds always emit this preference.
  preferences: { autoCheck: boolean; ignoredReleaseId?: string };
  automaticCheckEnabled: boolean; checkStatus: CheckStatus; result: UpdateResult; reason: string;
  latest: UpdateRelease | null; lastAttemptAt: number; lastSuccessAt: number; stale: boolean; retryAt: number; canCheck: boolean;
  // Phase 2: in-app download capabilities and state (optional for older backends)
  capabilities?: DownloadCapabilities;
  download?: DownloadState | null;
}
const fail = (field: string): never => { throw new NativeError(502, `Invalid update field: ${field}`); };
const object = (value: unknown, field: string) =>
  value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : fail(field);
const text = (v: Record<string, unknown>, name: string, max = 256) =>
  typeof v[name] === 'string' && (v[name] as string).length <= max ? v[name] as string : fail(name);
const flag = (v: Record<string, unknown>, name: string) => typeof v[name] === 'boolean' ? v[name] as boolean : fail(name);
const seconds = (v: Record<string, unknown>, name: string) =>
  Number.isSafeInteger(v[name]) && (v[name] as number) >= 0 ? v[name] as number : fail(name);
const id = (v: Record<string, unknown>, name: string, empty = false) => {
  const value = text(v, name, 32);
  return (empty && !value) || /^[1-9][0-9]*$/.test(value) ? value : fail(name);
};
function parseRelease(value: unknown): UpdateRelease {
  const v = object(value, 'latest');
  const release = { releaseId: id(v, 'releaseId'), assetId: id(v, 'assetId', true), version: text(v, 'version', 96), tag: text(v, 'tag', 96),
    prerelease: flag(v, 'prerelease'), publishedAt: text(v, 'publishedAt', 64), notes: text(v, 'notes', 16384),
    assetName: text(v, 'assetName', 200), bytes: seconds(v, 'bytes'), downloadAvailable: flag(v, 'downloadAvailable'),
    ...(v.inAppDownloadAvailable !== undefined ? { inAppDownloadAvailable: flag(v, 'inAppDownloadAvailable') } : {}) };
  if (!release.version) fail('version');
  if (!release.assetId !== !release.assetName || (!release.assetId && release.bytes)) fail('assetId');
  if (release.downloadAvailable && !release.assetId) fail('downloadAvailable');
  return release;
}
function parseDownloadState(value: unknown): DownloadState {
  const v = object(value, 'download');
  const status = text(v, 'status', 32);
  if (!(downloadStatuses as readonly string[]).includes(status)) fail('download.status');
  // idle and cancelled states may have empty IDs/version when no task has been selected yet
  const isEmptyIdAllowed = status === 'idle' || status === 'cancelled';
  const releaseId = id(v, 'releaseId', isEmptyIdAllowed);
  const assetId = id(v, 'assetId', isEmptyIdAllowed);
  const version = text(v, 'version', 96);
  // Validate non-empty IDs for states that require a task
  if (!isEmptyIdAllowed && (!releaseId || !assetId || !version)) fail('download.releaseId');
  if (!releaseId !== !assetId || (!releaseId && (version || seconds(v, 'receivedBytes') || seconds(v, 'totalBytes') ||
    flag(v, 'canCancel') || flag(v, 'canInstall') || flag(v, 'superseded')))) fail('download.releaseId');
  if (flag(v, 'canInstall') && (status !== 'ready' || flag(v, 'superseded'))) fail('download.canInstall');
  return {
    status: status as DownloadStatus,
    reason: text(v, 'reason', 64),
    releaseId,
    assetId,
    version,
    receivedBytes: seconds(v, 'receivedBytes'),
    totalBytes: seconds(v, 'totalBytes'),
    canCancel: flag(v, 'canCancel'),
    canInstall: flag(v, 'canInstall'),
    superseded: flag(v, 'superseded')
  };
}
export function parseUpdateState(value: unknown): UpdateState {
  const v = object(value, 'state');
  const current = object(v.current, 'current'), preferences = object(v.preferences, 'preferences');
  const status = text(v, 'checkStatus', 32), result = text(v, 'result', 32);
  if (!(checkStatuses as readonly string[]).includes(status)) fail('checkStatus');
  if (!(updateResults as readonly string[]).includes(result)) fail('result');
  const ignored = preferences.ignoredReleaseId === undefined ? undefined : id(preferences, 'ignoredReleaseId', true);
  const capabilities = v.capabilities !== undefined ? { installerDownload: flag(object(v.capabilities, 'capabilities'), 'installerDownload') } : undefined;
  const download = v.download === undefined ? undefined : v.download === null ? null : parseDownloadState(v.download);
  return { current: { version: text(current, 'version', 96), commit: text(current, 'commit', 64), releaseBuild: flag(current, 'releaseBuild'), architecture: text(current, 'architecture', 32) },
    source: text(v, 'source'), includesPrereleases: flag(v, 'includesPrereleases'),
    preferences: { autoCheck: flag(preferences, 'autoCheck'), ...(ignored !== undefined ? { ignoredReleaseId: ignored } : {}) },
    automaticCheckEnabled: flag(v, 'automaticCheckEnabled'), checkStatus: status as CheckStatus, result: result as UpdateResult,
    reason: text(v, 'reason', 64), latest: v.latest === null ? null : parseRelease(v.latest),
    lastAttemptAt: seconds(v, 'lastAttemptAt'), lastSuccessAt: seconds(v, 'lastSuccessAt'), stale: flag(v, 'stale'),
    retryAt: seconds(v, 'retryAt'), canCheck: flag(v, 'canCheck'),
    ...(capabilities !== undefined ? { capabilities } : {}),
    ...(download !== undefined ? { download } : {}) };
}
function opened(value: unknown) {
  if (object(value, 'reply').opened !== true) fail('opened');
  return true as const;
}
export function createUpdatesClient(host: CefTransport) {
  const state = async (command: string, params: object, options?: RequestOptions) =>
    parseUpdateState(await request(host, command, params, options));
  return Object.freeze({
    getState: (options?: RequestOptions) => state('updates.getState', {}, options),
    check: (options?: RequestOptions) => state('updates.check', {}, options),
    setPreferences: (autoCheck: boolean, options?: RequestOptions) => state('updates.setPreferences', { autoCheck }, options),
    // Empty releaseId clears the ignored version; nonempty must match native latest.
    ignoreRelease: (releaseId: string, options?: RequestOptions) => state('updates.ignoreRelease', { releaseId }, options),
    // Demo transport only (?demo); the native backend answers 404.
    demoScenario: async (scenario: string) => { await request(host, 'updates.demoScenario', { scenario }); },
    openDownload: async (releaseId: string, assetId: string, options?: RequestOptions) =>
      opened(await request(host, 'updates.openDownload', { releaseId, assetId }, options)),
    openReleaseNotes: async (releaseId: string, options?: RequestOptions) =>
      opened(await request(host, 'updates.openReleaseNotes', { releaseId }, options)),
    // Phase 2: in-app download and installation
    startDownload: (releaseId: string, assetId: string, options?: RequestOptions) =>
      state('updates.startDownload', { releaseId, assetId }, options),
    cancelDownload: (options?: RequestOptions) => state('updates.cancelDownload', {}, options),
    install: (releaseId: string, assetId: string, options?: RequestOptions) =>
      state('updates.install', { releaseId, assetId }, options)
  });
}
