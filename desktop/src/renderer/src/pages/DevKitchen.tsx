import { useState } from 'react';
import {
	FilledButtonM3, FilledTonalButtonM3, OutlinedButtonM3, TextButtonM3, ElevatedButtonM3,
	IconButtonM3, FilledIconButtonM3, FilledTonalIconButtonM3, OutlinedIconButtonM3, FabM3,
} from '../components/m3/buttons';
import { AssistChipM3, FilterChipM3, InputChipM3, SuggestionChipM3 } from '../components/m3/chips';
import { DialogM3 } from '../components/m3/dialog';
import { FilledTextFieldM3, OutlinedTextFieldM3 } from '../components/m3/fields';
import { SwitchM3, CheckboxM3, RadioM3, SliderM3 } from '../components/m3/controls';
import { FilledSelectM3, SelectOptionM3 } from '../components/m3/selects';
import { ListM3, ListItemM3 } from '../components/m3/lists';
import { TabsM3, PrimaryTabM3 } from '../components/m3/tabs';
import { IconM3 } from '../components/m3/icons';
import { DividerM3, LinearProgressM3, CircularProgressM3 } from '../components/m3/display';
import { Settings, Plus, Star, Share2, Inbox, Layers, FileText } from 'lucide-react';
import { Card, TopBar, SideNav, Segmented, SnackbarProvider, useSnackbar, Tooltip } from '../components/m3/chrome';
import './dev-kitchen.css';

/** /dev/kitchen —— 方案 E′ 的撞墙试验场（P2 首日），入口：#dev-kitchen（仅开发用，不进导航）。 */
export default function DevKitchen() {
	const [dlgOpen, setDlgOpen] = useState(false);
	const [tabMsg, setTabMsg] = useState('（点标签试 change 事件）');
	const [sel, setSel] = useState('');
	const [sw, setSw] = useState(true);
	const [cb, setCb] = useState([true, false]);
	const [radio, setRadio] = useState('b');
	const [chips, setChips] = useState<string[]>(['已选 A']);
	const [prog, setProg] = useState(0.4);
	const [seg, setSeg] = useState<'day' | 'week' | 'month'>('day');
	const [nav, setNav] = useState<'home' | 'notes' | 'files'>('home');

	return (
		<SnackbarProvider>
		<div className="kitchen">
			<h2 className="kitchen-title">/dev/kitchen · M3 组件撞墙试验场</h2>
			<p className="kitchen-note">
				目标：每个 md-* 组件过一遍「能不能用、能不能按我们的 token 换肤、内部样式有没有
				::part 覆盖不到的地方」。撞墙记录写进 UI_FRAMEWORK_PLAN §5 P2 验收。
			</p>

			<section className="kitchen-sec">
				<h3>Button（四式 + elevated）</h3>
				<div className="kitchen-row">
					<FilledButtonM3 onClick={() => setDlgOpen(true)}>打开 Dialog</FilledButtonM3>
					<FilledTonalButtonM3>tonal</FilledTonalButtonM3>
					<OutlinedButtonM3>outlined</OutlinedButtonM3>
					<TextButtonM3>text</TextButtonM3>
					<ElevatedButtonM3>elevated</ElevatedButtonM3>
					<IconButtonM3 aria-label="设置"><IconM3><Settings size={18} /></IconM3></IconButtonM3>
					<FilledIconButtonM3 aria-label="添加"><IconM3><Plus size={18} /></IconM3></FilledIconButtonM3>
					<FilledTonalIconButtonM3 aria-label="收藏"><IconM3><Star size={18} /></IconM3></FilledTonalIconButtonM3>
					<OutlinedIconButtonM3 aria-label="分享"><IconM3><Share2 size={18} /></IconM3></OutlinedIconButtonM3>
					<FabM3 variant="primary" size="small" aria-label="新建">
						<IconM3><Plus size={20} /></IconM3>
					</FabM3>
				</div>
			</section>

			<section className="kitchen-sec">
				<h3>Chips（filter 可多选试验）</h3>
				<div className="kitchen-row">
					<AssistChipM3><IconM3 slot="icon"><Layers size={16} /></IconM3>辅助</AssistChipM3>
					{['A', 'B'].map(x => (
						<FilterChipM3
							key={x}
							selected={chips.includes(x)}
							onClick={() => setChips(c => c.includes(x) ? c.filter(y => y !== x) : [...c, x])}
						>选项 {x}</FilterChipM3>
					))}
					<InputChipM3>可移除 <IconM3 slot="trailing-icon"><Inbox size={14} /></IconM3></InputChipM3>
					<SuggestionChipM3>建议</SuggestionChipM3>
				</div>
			</section>

			<section className="kitchen-sec">
				<h3>TextField（撞墙重点：label / supporting / 内部 padding 可改性）</h3>
				<div className="kitchen-row kitchen-fields">
					<FilledTextFieldM3 label=" filled 标签" supportingText="filled" />
					<OutlinedTextFieldM3 label=" outlined 标签" supportingText="outlined" />
				</div>
			</section>

			<section className="kitchen-sec">
				<h3>Switch / Checkbox / Radio / Select / Slider</h3>
				<div className="kitchen-row">
					<SwitchM3 selected={sw} onClick={() => setSw(s => !s)} />
					<CheckboxM3 checked={cb[0]} onClick={() => setCb(c => [!c[0], c[1]])} />
					<CheckboxM3 checked={cb[1]} onClick={() => setCb(c => [c[0], !c[1]])} />
					{['a', 'b', 'c'].map(v => (
						<RadioM3 key={v} name="kitchen-radio" value={v} checked={radio === v}
							onClick={() => setRadio(v)} />
					))}
					<FilledSelectM3 value={sel} onChange={e => setSel((e.target as HTMLInputElement).value)}>
						<SelectOptionM3 value="">（未选）</SelectOptionM3>
						<SelectOptionM3 value="m1">选项一</SelectOptionM3>
						<SelectOptionM3 value="m2">选项二</SelectOptionM3>
					</FilledSelectM3>
					<SliderM3 value={prog} min={0} max={1} step={0.1}
						onChange={e => setProg(Number((e.target as HTMLInputElement).value))} />
					<span className="kitchen-dim">slider={prog.toFixed(1)} · select={sel || '—'} · radio={radio}</span>
				</div>
			</section>

			<section className="kitchen-sec">
				<h3>Tabs（change → activeTabIndex）</h3>
				<TabsM3 onChange={e => {
					const t = e.target as HTMLElement & { activeTabIndex?: number };
					setTabMsg(`activeTabIndex=${t.activeTabIndex}`);
				}}>
					<PrimaryTabM3>概览</PrimaryTabM3>
					<PrimaryTabM3>明细</PrimaryTabM3>
					<PrimaryTabM3>设置</PrimaryTabM3>
				</TabsM3>
				<span className="kitchen-dim">{tabMsg}</span>
			</section>

			<section className="kitchen-sec">
				<h3>List / Progress / Divider</h3>
				<div className="kitchen-row kitchen-list">
					<ListM3>
						{/* 撞墙记录①：headline/supportingText 在 v2 d.ts 里只是接口成员、类上未声明，
						    属性方式类型丢失 → 用 slot（或包装层 cast），见 UI_FRAMEWORK_PLAN §5 P2 */}
						<ListItemM3><span slot="headline">列表行一</span><IconM3 slot="start"><Inbox size={18} /></IconM3></ListItemM3>
						<ListItemM3><span slot="headline">列表行二</span><IconM3 slot="start"><FileText size={18} /></IconM3></ListItemM3>
					</ListM3>
					<LinearProgressM3 value={prog} />
					<LinearProgressM3 indeterminate />
					<CircularProgressM3 indeterminate />
					<DividerM3 />
				</div>
			</section>

			<section className="kitchen-sec">
				<h3>自绘 chrome 样板（Card：大形状自绘模式）</h3>
				<div className="kitchen-cards">
					<div className="k3-card">
						<div className="k3-card-title">tonal 卡</div>
						<div className="k3-card-body">surface-container + shape-lg 圆角 + 无描边；
							「大形状自己画」的基准样板：简单几何 + token 色，零组件依赖。</div>
					</div>
					<div className="k3-card k3-card--outline">
						<div className="k3-card-title">outline 卡</div>
						<div className="k3-card-body">outline-variant 描边变体。</div>
					</div>
				</div>
			</section>

			<section className="kitchen-sec" id="chrome">
				<h3>自绘 chrome 六件（Card / TopBar / SideNav / Segmented / Snackbar / Tooltip）</h3>
				<div className="kitchen-chrome">
					<div className="kitchen-chrome-col">
						<div className="kitchen-dim">TopBar</div>
						<TopBar title="页面标题" leading={<IconM3><Inbox size={18} /></IconM3>}
							actions={<Tooltip label="设置"><IconButtonM3 aria-label="设置"><IconM3><Settings size={18} /></IconM3></IconButtonM3></Tooltip>} />
						<div className="kitchen-dim" style={{ marginTop: 12 }}>Card 三变体</div>
						<div className="kitchen-row">
							<Card variant="tonal"><div className="k3-card-title">tonal</div></Card>
							<Card variant="outline"><div className="k3-card-title">outline</div></Card>
							<Card variant="elevated"><div className="k3-card-title">elevated</div></Card>
						</div>
						<div className="kitchen-dim" style={{ marginTop: 12 }}>Snackbar（点按钮弹，4s 自动消失）</div>
						<SnackbarDemo />
					</div>
					<div className="kitchen-chrome-col">
						<div className="kitchen-dim">SideNav（hover 可用）</div>
						<SideNav items={[
							{ key: 'home', label: '首页', icon: <Inbox size={16} /> },
							{ key: 'notes', label: '笔记', icon: <FileText size={16} /> },
							{ key: 'files', label: '文件', icon: <Layers size={16} />, badge: 3 },
						]} active={nav} onSelect={setNav} />
						<div className="kitchen-dim" style={{ marginTop: 12 }}>
							Segmented（Tab 进组后 ←→ 循环）· 当前 {seg}
						</div>
						<Segmented value={seg} onChange={setSeg} ariaLabel="时间范围" options={[
							{ value: 'day', label: '日' },
							{ value: 'week', label: '周' },
							{ value: 'month', label: '月' },
						]} />
					</div>
				</div>
			</section>

			<DialogM3 open={dlgOpen} onClose={() => setDlgOpen(false)}>
				<div slot="headline">撞墙试验：Dialog</div>
				<form slot="content" id="kitchen-form" className="kitchen-form">
					<OutlinedTextFieldM3 label="表单字段" supportingText="焦点陷阱应把 Tab 圈在本对话框内" />
					<div className="kitchen-dim">Esc / 遮罩 / 取消 / 确认 四路关闭都试一遍</div>
				</form>
				<div slot="actions">
					<TextButtonM3 onClick={() => setDlgOpen(false)}>取消</TextButtonM3>
					<TextButtonM3 autoFocus onClick={() => setDlgOpen(false)}>确认</TextButtonM3>
				</div>
		</DialogM3>
		</div>
		</SnackbarProvider>
	);
}

/** Snackbar 演示（必须在 Provider 内取 hook） */
function SnackbarDemo() {
	const show = useSnackbar();
	return (
		<OutlinedButtonM3 onClick={() => show('已保存（示例）', { label: '撤销', run: () => show('已撤销') })}>
			弹 Snackbar
		</OutlinedButtonM3>
	);
}
