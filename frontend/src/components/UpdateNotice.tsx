import type { AppUpdates } from '../composables/useAppUpdates';
import { Button } from './ui/button';

// Persistent, dismissible card above the dock. Unlike MessageAlerts it does not expire.
export default function UpdateNotice({ updates, onDetails }: { updates: AppUpdates; onDetails: () => void }) {
  const latest = updates.state?.latest;
  const download = updates.state?.download;
  const hasInAppDownload = updates.state?.capabilities?.installerDownload && latest?.inAppDownloadAvailable;

  if (!updates.notify || !latest) return null;

  // Determine button action based on download state
  const isDownloading = download && (download.status === 'preparing' || download.status === 'downloading' || download.status === 'verifying');
  const isReady = download?.status === 'ready' && !download.superseded;
  const canStartDownload = hasInAppDownload && (!download || download.status === 'idle' || download.status === 'cancelled' || download.status === 'failed');

  let primaryLabel = '查看更新';
  let primaryAction: () => void = onDetails;

  if (isReady) {
    primaryLabel = '查看安装';
    primaryAction = onDetails;
  } else if (isDownloading) {
    primaryLabel = '查看进度';
    primaryAction = onDetails;
  } else if (canStartDownload) {
    primaryLabel = '下载';
    primaryAction = () => void updates.startDownload(latest.releaseId, latest.assetId);
  } else if (!hasInAppDownload && latest.downloadAvailable) {
    primaryLabel = '下载';
    primaryAction = () => void updates.openDownload(latest.releaseId, latest.assetId);
  }

  return <div className="update-notice" role="status" aria-label="新版本提醒">
    <div className="update-notice-text">
      <strong>发现新版本 {latest.version}</strong>
      {latest.prerelease && <span className="update-badge">预发布</span>}
    </div>
    <div className="update-notice-actions">
      <Button variant="unstyled" className="update-notice-primary" disabled={!!updates.busy}
        onClick={primaryAction}>
        {primaryLabel}</Button>
      <Button variant="unstyled" onClick={onDetails}>查看</Button>
      <Button variant="unstyled" className="update-notice-close" aria-label="关闭新版本提醒" title="关闭"
        onClick={() => updates.dismiss(latest.releaseId)}>×</Button>
    </div>
  </div>;
}
