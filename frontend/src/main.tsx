import { createRoot } from 'react-dom/client';
import App from './App';
import TrayMenu from './TrayMenu';
import FullscreenAppOverlay from './FullscreenAppOverlay';
import './style.css';
import './shadcn.css';
import './liquid-glass.css';

const root = createRoot(document.getElementById('app')!);
const params = new URLSearchParams(location.search);
const overlay = params.get('fullscreenAppOverlay');
root.render(overlay === 'lyrics' || overlay === 'track' ? <FullscreenAppOverlay kind={overlay} /> : params.has('desktopLyrics') ? <FullscreenAppOverlay kind="lyrics" /> : params.has('tray') ? <TrayMenu /> : <App />);
window.addEventListener('pagehide', () => root.unmount(), { once: true });
