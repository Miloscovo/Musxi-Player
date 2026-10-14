import { createRoot } from 'react-dom/client';
import App from './App';
import TrayMenu from './TrayMenu';
import DesktopLyrics from './DesktopLyrics';
import './style.css';
import './shadcn.css';

const root = createRoot(document.getElementById('app')!);
const params = new URLSearchParams(location.search);
root.render(params.has('desktopLyrics') ? <DesktopLyrics /> : params.has('tray') ? <TrayMenu /> : <App />);
window.addEventListener('pagehide', () => root.unmount(), { once: true });
