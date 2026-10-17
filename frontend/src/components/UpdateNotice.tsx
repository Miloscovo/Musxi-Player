import type { AppUpdates } from '../composables/useAppUpdates';
import { Button } from './ui/button';

// Persistent, dismissible card above the dock. Unlike MessageAlerts it does not expire.
export default function UpdateNotice({ updates, onDetails }: { updates: AppUpdates; onDetails: () => void }) {
  const latest = updates.state?.latest;
  if (!updates.notify || !latest) return null;
  return <div className="update-notice" role="status" aria-label="新版本提醒">
    <div className="update-notice-text">
      <strong>发现新版本 {latest.version}</strong>
      {latest.prerelease && <span className="update-badge">预发布</span>}
    </div>
    <div className="update-notice-actions">
      <Button variant="unstyled" className="update-notice-primary" disabled={!!updates.busy}
        onClick={() => void updates.openDownload(latest.releaseId, latest.assetId)}>下载</Button>
      <Button variant="unstyled" onClick={onDetails}>查看</Button>
      <Button variant="unstyled" className="update-notice-close" aria-label="关闭新版本提醒" title="关闭"
        onClick={() => updates.dismiss(latest.releaseId)}>×</Button>
    </div>
  </div>;
}
