import { createRoot } from 'react-dom/client';
import App from './App';
import TrayMenu from './TrayMenu';
import GameOverlay from './GameOverlay';
import './style.css';
import './shadcn.css';
import './liquid-glass.css';

const root = createRoot(document.getElementById('app')!);
const params = new URLSearchParams(location.search);
const overlay = params.get('gameOverlay');
root.render(overlay === 'lyrics' || overlay === 'track' ? <GameOverlay kind={overlay} /> : params.has('desktopLyrics') ? <GameOverlay kind="lyrics" /> : params.has('tray') ? <TrayMenu /> : <App />);
window.addEventListener('pagehide', () => root.unmount(), { once: true });
