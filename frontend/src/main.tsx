import { createRoot } from 'react-dom/client';
import App from './App';
import TrayMenu from './TrayMenu';
import './style.css';
import './shadcn.css';

const root = createRoot(document.getElementById('app')!);
root.render(new URLSearchParams(location.search).has('tray') ? <TrayMenu /> : <App />);
window.addEventListener('pagehide', () => root.unmount(), { once: true });
