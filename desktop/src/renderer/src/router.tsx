import { createContext, useContext } from 'react';

/** Seven-tab shell (phone NavShell parity) + sub-pages reachable via the info drawer. */
export type PageKey =
	| 'home' | 'plans' | 'focus' | 'notes' | 'info' | 'files' | 'mine'
	// info-drawer sub pages (no own tab; the 信息 tab stays highlighted)
	| 'automations' | 'assistants' | 'mcp' | 'plugins' | 'stats' | 'memories'
	// M17 multi-agent driver (信息 tab stays highlighted)
	| 'agents'
	// M20 orchestration: workflow run/replay + team assembly + handoff (信息 tab)
	| 'workflows'
	// M19-4 skills management (信息 tab)
	| 'skills'
	// files sub page (同步中心; the 文件 tab stays highlighted)
	| 'sync'
	// P2 撞墙试验场（方案 E′）：仅 location.hash === '#dev-kitchen' 进入，不进导航
	| 'devkitchen';

export const INFO_SUBPAGES: PageKey[] = ['automations', 'assistants', 'mcp', 'plugins', 'stats', 'memories', 'agents', 'workflows', 'skills'];

/** Sub-pages that keep the 文件 tab lit (phone: SyncthingScreen lives under 文件). */
export const FILES_SUBPAGES: PageKey[] = ['sync'];

export const NavContext = createContext<(page: PageKey) => void>(() => { /* noop */ });
export const useNavigate = () => useContext(NavContext);
