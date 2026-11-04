import { createRoot } from 'react-dom/client';
import App from './App';
import TrayMenu from './TrayMenu';
import DesktopLyrics from './DesktopLyrics';
import GameOverlay from './GameOverlay';
import './style.css';
import './shadcn.css';

const root = createRoot(document.getElementById('app')!);
const params = new URLSearchParams(location.search);
const overlay = params.get('gameOverlay');
root.render(overlay === 'lyrics' || overlay === 'track' ? <GameOverlay kind={overlay} /> : params.has('desktopLyrics') ? <DesktopLyrics /> : params.has('tray') ? <TrayMenu /> : <App />);
window.addEventListener('pagehide', () => root.unmount(), { once: true });
