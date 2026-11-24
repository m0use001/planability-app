import { createRoot } from 'react-dom/client';
import './styles.css';
import './styles/m3-tokens.css';
import './float/float.css';
import { FloatPane, detectRole } from './float/FloatPane';

/* 悬浮窗入口（electron.vite.config.ts 的第二个 HTML 入口，pc-b 建管道、pc-a 建内容）。
 * 三个浮窗加载同一个 float.html，角色按窗口高度区分（见 FloatPane.detectRole）。 */

createRoot(document.getElementById('root')!).render(<FloatPane role={detectRole()} />);
