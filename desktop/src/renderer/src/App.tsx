import { useState } from 'react';
import {
	Home as HomeIcon, CalendarRange, Timer, NotebookPen, MessagesSquare,
	FolderOpen, UserRound, type LucideIcon
} from 'lucide-react';
import { NavContext, INFO_SUBPAGES, FILES_SUBPAGES, type PageKey } from './router';
import HomePage from './pages/Home';
import PlansPage from './pages/Plans';
import FocusPage from './pages/Focus';
import NotesPage from './pages/Notes';
import ChatPage from './pages/Chat';
import FilesPage from './pages/Files';
import SyncPage from './pages/Sync';
import MinePage from './pages/Mine';
// info-drawer sub pages
import AutomationsPage from './pages/Automations';
import AssistantsPage from './pages/Assistants';
import McpServersPage from './pages/McpServers';
import PluginsPage from './pages/Plugins';
import StatsPage from './pages/Stats';
import MemoriesPage from './pages/Memories';
import AgentsPage from './pages/Agents';
import WorkflowsPage from './pages/Workflows';
import SkillsPage from './pages/Skills';
import DevKitchen from './pages/DevKitchen';

/** Seven-tab shell, mirrors the phone NavShell: 首页/计划/专注/笔记/信息/文件/我的 */
const NAV: { key: PageKey; label: string; icon: LucideIcon }[] = [
	{ key: 'home', label: '首页', icon: HomeIcon },
	{ key: 'plans', label: '计划', icon: CalendarRange },
	{ key: 'focus', label: '专注', icon: Timer },
	{ key: 'notes', label: '笔记', icon: NotebookPen },
	{ key: 'info', label: '信息', icon: MessagesSquare },
	{ key: 'files', label: '文件', icon: FolderOpen },
	{ key: 'mine', label: '我的', icon: UserRound }
];

const TAB_OF_SUB: Partial<Record<PageKey, PageKey>> = {
	...Object.fromEntries((INFO_SUBPAGES as PageKey[]).map(p => [p, 'info'])),
	...Object.fromEntries(FILES_SUBPAGES.map(p => [p, 'files']))
} as Partial<Record<PageKey, PageKey>>;

/** 板式页（宽屏走两板纸条的那些）：内容区收小内衬，板自己管理外距（spec §2 壳层相框口径）。 */
const BOARD_PAGES = new Set<PageKey>(['home', 'notes', 'memories', 'automations']);

export default function App() {
	// hash 深链：#dev-kitchen（试验场）与 #page-<key>（任意页，--shot 视觉验收的入口）
	const [page, setPage] = useState<PageKey>(() => {
		const h = typeof location !== 'undefined' ? location.hash : '';
		if (h === '#dev-kitchen') { return 'devkitchen'; }
		const m = h.match(/^#page-([a-z]+)/);
		return m ? (m[1] as PageKey) : 'home';
	});
	const activeTab = TAB_OF_SUB[page] ?? page;

	if (page === 'devkitchen') {
		return (
			<NavContext.Provider value={setPage}>
				<DevKitchen />
			</NavContext.Provider>
		);
	}

	return (
		<NavContext.Provider value={setPage}>
			<div className="shell">
				<nav className="sidebar">
					<div className="brand">Planability</div>
					{NAV.map(n => (
						<button
							key={n.key}
							className={`nav-item ${activeTab === n.key ? 'active' : ''}`}
							onClick={() => setPage(n.key)}
						>
							<span className="nav-icon"><n.icon size={17} strokeWidth={2} /></span>
							{n.label}
						</button>
					))}
					<div className="sidebar-foot">效率管理 · AI 工作台</div>
				</nav>
				<main className={`content ${BOARD_PAGES.has(page) ? 'content--boards' : ''}`}>
					{page === 'home' && <HomePage />}
					{page === 'plans' && <PlansPage />}
					{page === 'focus' && <FocusPage />}
					{page === 'notes' && <NotesPage />}
					{page === 'info' && <ChatPage />}
					{page === 'files' && <FilesPage />}
				{page === 'sync' && <SyncPage />}
					{page === 'mine' && <MinePage />}
					{page === 'automations' && <AutomationsPage />}
					{page === 'assistants' && <AssistantsPage />}
					{page === 'mcp' && <McpServersPage />}
					{page === 'plugins' && <PluginsPage />}
					{page === 'stats' && <StatsPage />}
					{page === 'memories' && <MemoriesPage />}
					{page === 'agents' && <AgentsPage />}
				{page === 'workflows' && <WorkflowsPage />}
				{page === 'skills' && <SkillsPage />}
				</main>
			</div>
		</NavContext.Provider>
	);
}
