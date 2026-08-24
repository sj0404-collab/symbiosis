#!/usr/bin/env node
/**
 * OpenCode Unified — CLI Agent (Termux / PC)
 * TUI Edition: блоки, индикаторы, панели, спиннеры, прогресс-бары.
 * Использует curl для Zen API (обход Cloudflare TLS fingerprinting).
 * Встроенный MCP API: http://localhost:8765/mcp/call по умолчанию (MCP_PORT для смены).
 * Порт 3000 оставлен приложению пользователя.
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const readline = require('readline');
const { execSync, execFileSync, spawn } = require('child_process');
const os = require('os');
const crypto = require('crypto');
const vm = require('vm');
// Capabilities: agent-authored tools that run as real processes. Kept in a
// sibling file because, unlike custom_tool_*/plugin_*, they are deliberately
// NOT sandboxed - adb, RDP and GUI screenshots cannot exist without spawn().
// Optional require: a missing file must not stop the agent from starting.
let capabilitiesModule = null;
try { capabilitiesModule = require('./capabilities.js'); }
catch (e) { capabilitiesModule = null; }
// Optional: this ships as a single file, and ../lib/local-ai is not part of
// it. A hard require made the agent refuse to start at all with
// "Cannot find module", which is a poor trade for a feature most runs never
// touch. Absent it, the local-AI endpoints report that it is unavailable.
let GitHubApi = null;
try { ({ GitHubApi } = require('../lib/github-api')); } catch {}

let LocalAiManager = null;
try { ({ LocalAiManager } = require('../lib/local-ai')); } catch {}
if (!LocalAiManager) {
  const unavailable = () => ({ success: false, error: 'local-ai module not installed' });
  // Every method the agent calls has to exist here, or the fallback is worse
  // than no fallback: /api/local-ai/models called listModels() and threw a
  // TypeError instead of reporting that local AI is unavailable, so asking the
  // hub for the model list failed outright rather than simply showing none.
  LocalAiManager = class {
    constructor() {}
    root() { return null; }
    runtimeRoot() { return null; }
    catalog() { return []; }
    runtimeCatalog() { return []; }
    listModels() { return []; }
    listRuntimes() { return []; }
    task() { return null; }
    publicConfig() { return { available: false }; }
    async status() { return { success: false, available: false, error: 'local-ai module not installed' }; }
    async start() { return unavailable(); }
    async stop() { return unavailable(); }
    async download() { return unavailable(); }
    async startDownload() { return unavailable(); }
    async startRuntimeDownload() { return unavailable(); }
    async installRuntimeToTermux() { return unavailable(); }
    async remove() { return unavailable(); }
    async chat() { return unavailable(); }
    async prepare() { return unavailable(); }
    configure() { return unavailable(); }
    updateConfig() { return unavailable(); }
  };
}

// Optional at module-load time so MCP/CLI still starts with a clear terminal
// capability error if npm dependencies have not been installed yet.
let WebSocketServer = null;
let nodePty = null;
try { ({ WebSocketServer } = require('ws')); } catch {}
try { nodePty = require('/data/data/com.termux/files/home/pty-helper/pty-bridge.js'); } catch {}

// MCP/agent API никогда не должна занимать порт проекта (обычно 3000).
// Переопределение: MCP_PORT=8765 node cli-agent-unified-termux-mcp.js
let UI_PORT = parseInt(process.env.MCP_PORT || process.env.AGENT_PORT || '8765', 10) || 8765;

// ═══════════════════════════════════════════════════════════════════
//  CONFIG
// ═══════════════════════════════════════════════════════════════════
let CONFIG = {
  defaultModel: 'laguna-s-2.1-free',   // measured ~8.9s vs ~18.1s for deepseek
  maxHistory: 25,
  maxTokens: 32000,
  temperature: 0.5,
  autoUseTools: true,
  verbose: process.env.VERBOSE === '1',
  maxAgentSteps: parseInt(process.env.MAX_STEPS || '25', 10),
  sessionHistoryLimit: Math.max(50, parseInt(process.env.SESSION_HISTORY_LIMIT || '500', 10) || 500),
  longTaskMode: process.env.ZEN_LONG_TASKS === '1',
  longTaskMaxSteps: Math.max(50, parseInt(process.env.ZEN_LONG_MAX_STEPS || '250', 10) || 250),
  longCommandTimeoutMs: Math.max(300000, parseInt(process.env.ZEN_LONG_COMMAND_TIMEOUT_MS || '3600000', 10) || 3600000),
  maxProviderRetries: Math.max(1, parseInt(process.env.MAX_PROVIDER_RETRIES || '3', 10) || 3),
  // Fall back to another model when the chosen one is rate-limited.
  //
  // On by default because the Zen free tier runs out constantly and an answer
  // from a second model beats no answer. Turn it off (/autoswitch off, or
  // ZEN_AUTO_SWITCH=0) when the model matters: a local model has no quota to
  // hit, a paid key is being paid for, and a silent substitution makes any
  // comparison between models worthless.
  autoSwitchModel: process.env.ZEN_AUTO_SWITCH !== '0',
  // Ответ модели выводится по мере поступления; /stream переключает режим.
  streamMode: true,
  autoApprove: false,
  agentMode: 'build',
  askClarifyingQuestions: true,
  showThinking: true,
  reasoningEffort: 'low',
  compactMode: false,
  showDashboard: true,
  proxy: process.env.HTTP_PROXY || process.env.HTTPS_PROXY || '',
  curlIpv4: true,
  // На Termux все относительные пути MCP идут в общую память Android, а не в $HOME Termux.
  workspaceRoot: process.env.ZEN_WORKSPACE || process.env.MCP_WORKSPACE || '',
  // Реальный stdout/stderr команд сразу печатается в терминал, без скрывающего спиннера.
  liveToolLogs: process.env.ZEN_LIVE_LOGS !== '0',
  // В некоторых UI-обёртках Termux ANSI cursorTo вырезается и кадры склеиваются.
  // Включается вручную только для настоящего ANSI-терминала: /animation on.
  animatedIndicator: process.env.ZEN_ANIMATION === '1',
  indicatorAnimationMs: Math.max(80, parseInt(process.env.ZEN_ANIMATION_MS || '120', 10) || 120),
  indicatorFallbackMs: Math.max(500, parseInt(process.env.ZEN_INDICATOR_MS || '1000', 10) || 1000),
  indicatorStyle: process.env.ZEN_INDICATOR_STYLE || 'game',
  provider: 'zen',
  openRouterApiKey: process.env.OPENROUTER_API_KEY || '',
  openRouterModel: process.env.OPENROUTER_MODEL || '',
  visionModel: process.env.OPENROUTER_VISION_MODEL || 'google/gemma-3-27b-it:free',
};

const AGENT_MODES = {
  build: { label: '🔨 Build', description: 'Разработка и выполнение инструментов с подтверждениями.' },
  plan: { label: '🗺️ Plan', description: 'Только анализ, уточняющие вопросы и план; изменения запрещены.' },
  explore: { label: '🔎 Explore', description: 'Только чтение, поиск и диагностика; изменения запрещены.' }
};
function normalizedAgentMode(value) { return AGENT_MODES[value] ? value : 'build'; }
function providerDisplayName() { return currentProvider === 'openrouter' ? 'OpenRouter' : currentProvider === 'zen' ? 'Zen' : currentProvider === 'local' ? 'Local AI' : currentProvider; }
function agentStepLimit() { return CONFIG.longTaskMode ? Math.max(CONFIG.maxAgentSteps, CONFIG.longTaskMaxSteps) : CONFIG.maxAgentSteps; }
function safeCommandTimeout(value, defaultValue = 18000) {
  const requested = parseInt(value || String(defaultValue), 10) || defaultValue;
  const max = CONFIG.longTaskMode ? CONFIG.longCommandTimeoutMs : 120000;
  return Math.min(Math.max(requested, 1000), max);
}
function setAgentMode(mode) {
  CONFIG.agentMode = normalizedAgentMode(mode);
  saveHistory();
  return { mode: CONFIG.agentMode, ...AGENT_MODES[CONFIG.agentMode] };
}

// ═══════════════════════════════════════════════════════════════════
//  BOX DRAWING & TUI UTILS
// ═══════════════════════════════════════════════════════════════════
const B = {
  h: '─', v: '│', tl: '┌', tr: '┐', bl: '└', br: '┘',
  tj: '┬', bj: '┴', lj: '├', rj: '┤', cj: '┼',
  h2: '═', v2: '║', tl2: '╔', tr2: '╗', bl2: '╚', br2: '╝',
  tj2: '╦', bj2: '╩', lj2: '╠', rj2: '╣', cj2: '╬',
};

const SPINNERS = {
  dots:    ['⠋','⠙','⠹','⠸','⠼','⠴','⠦','⠧','⠇','⠏'],
  line:    ['-','\\','|','/'],
  pulse:   ['▁','▃','▄','▅','▆','▇','█','▇','▆','▅','▄','▃'],
  arrow:   ['←','↖','↑','↗','→','↘','↓','↙'],
  moon:    ['🌑','🌒','🌓','🌔','🌕','🌖','🌗','🌘'],
  bounce:  ['( ●    )','(  ●   )','(   ●  )','(    ● )','(   ●  )','(  ●   )','( ●    )','(●     )'],
};
const INDICATOR_THEMES = {
  game: { label: '🎮 Игра — движущийся заряд' },
  dots: { label: '⠋ Точки — braille spinner' },
  line: { label: '| Линия — классический spinner' },
  pulse: { label: '▇ Пульс — волна загрузки' },
  minimal: { label: '⏳ Минимальный — только статус' }
};

const COL = {
  reset: '\x1b[0m',
  bold:  '\x1b[1m',
  dim:   '\x1b[2m',
  italic:'\x1b[3m',
  ul:    '\x1b[4m',
  blink: '\x1b[5m',
  rev:   '\x1b[7m',
  hide:  '\x1b[8m',
  // Foreground
  black: '\x1b[30m', red: '\x1b[31m', green: '\x1b[32m', yellow: '\x1b[33m',
  blue: '\x1b[34m', magenta: '\x1b[35m', cyan: '\x1b[36m', white: '\x1b[37m',
  gray: '\x1b[90m', brightRed: '\x1b[91m', brightGreen: '\x1b[92m', brightYellow: '\x1b[93m',
  brightBlue: '\x1b[94m', brightMagenta: '\x1b[95m', brightCyan: '\x1b[96m', brightWhite: '\x1b[97m',
  // Background
  bgBlack: '\x1b[40m', bgRed: '\x1b[41m', bgGreen: '\x1b[42m', bgYellow: '\x1b[43m',
  bgBlue: '\x1b[44m', bgMagenta: '\x1b[45m', bgCyan: '\x1b[46m', bgWhite: '\x1b[47m',
  bgGray: '\x1b[100m',
};

function c(s, ...styles) {
  if (!styles.length) return s;
  const codes = styles.map(st => {
    if (COL[st]) return COL[st];
    const m = st.match(/^(bg)?([a-z]+)$/i);
    if (!m) return '';
    const key = (m[1] ? 'bg' : '') + m[2].toLowerCase();
    return COL[key] || '';
  }).join('');
  return codes + s + COL.reset;
}

function termWidth() { return process.stdout.columns || 80; }
function termHeight() { return process.stdout.rows || 24; }
function pad(s, w, align = 'left') {
  const str = String(s);
  if (str.length >= w) return str.slice(0, w);
  const pad = w - str.length;
  if (align === 'right') return ' '.repeat(pad) + str;
  if (align === 'center') return ' '.repeat(Math.floor(pad/2)) + str + ' '.repeat(Math.ceil(pad/2));
  return str + ' '.repeat(pad);
}

// ═══════════════════════════════════════════════════════════════════
//  BOX DRAWING
// ═══════════════════════════════════════════════════════════════════
function box(textLines, opts = {}) {
  const { width, title, style = 'single', color = 'cyan', titleColor = 'brightCyan', padding = 1 } = opts;
  const w = width || Math.max(...textLines.map(l => stripAnsi(l).length)) + padding * 2 + 2;
  const useDouble = style === 'double';
  const h = useDouble ? B.h2 : B.h;
  const v = useDouble ? B.v2 : B.v;
  const tl = useDouble ? B.tl2 : B.tl;
  const tr = useDouble ? B.tr2 : B.tr;
  const bl = useDouble ? B.bl2 : B.bl;
  const br = useDouble ? B.br2 : B.br;
  const tj = useDouble ? B.tj2 : B.tj;
  const bj = useDouble ? B.bj2 : B.bj;
  const lj = useDouble ? B.lj2 : B.lj;
  const rj = useDouble ? B.rj2 : B.rj;

  const cc = COL[color] || COL.cyan;
  const tc = COL[titleColor] || COL.brightCyan;

  let top = tl + h.repeat(w - 2) + tr;
  if (title) {
    const t = ` ${title} `;
    const pos = Math.floor((w - 2 - stripAnsi(t).length) / 2);
    top = tl + h.repeat(pos) + tc + t + cc + h.repeat(w - 2 - pos - stripAnsi(t).length) + tr;
  }

  const body = textLines.map(line => {
    const plain = stripAnsi(line);
    const padR = w - 2 - plain.length;
    return v + ' '.repeat(padding) + line + ' '.repeat(Math.max(0, padR - padding)) + v;
  });

  return [cc + top + COL.reset, ...body, cc + bl + h.repeat(w - 2) + br + COL.reset];
}

function stripAnsi(s) { return s.replace(/\x1b\[[0-9;]*m/g, ''); }

function progressBar(value, max, width = 20, opts = {}) {
  const { filled = '█', empty = '░', color = 'green', bgColor = 'gray' } = opts;
  const pct = Math.min(1, Math.max(0, value / max));
  const filledW = Math.round(width * pct);
  const emptyW = width - filledW;
  const bar = c(filled.repeat(filledW), color) + c(empty.repeat(emptyW), bgColor);
  const pctStr = `${Math.round(pct * 100)}%`;
  return `[${bar}] ${pctStr}`;
}

function miniProgress(value, max, width = 12) {
  const pct = Math.min(1, Math.max(0, value / max));
  const filled = Math.round(width * pct);
  const blocks = ['▏','▎','▍','▌','▋','▊','▉','█'];
  let bar = '█'.repeat(Math.floor(filled));
  const frac = (filled % 1);
  if (frac > 0 && bar.length < width) bar += blocks[Math.round(frac * 7)];
  bar += ' '.repeat(Math.max(0, width - bar.length));
  return `[${c(bar.slice(0, width), 'green')}]`;
}

function horizontalRule(char = '─', color = 'gray') {
  const w = termWidth();
  return c(char.repeat(w), color);
}

function badge(text, bg = 'bgGreen', fg = 'black') {
  return c(` ${text} `, fg, bg);
}

function tag(text, color = 'cyan') {
  return c(`[${text}]`, color);
}

// ═══════════════════════════════════════════════════════════════════
//  SPINNER ENGINE
// ═══════════════════════════════════════════════════════════════════
class Spinner {
  constructor(text, style = 'dots') {
    this.text = text;
    this.style = style;
    this.frames = SPINNERS[style] || SPINNERS.dots;
    this.idx = 0;
    this.timer = null;
    this.active = false;
  }
  gameBar(width = 18) {
    // Не показываем фальшивый процент: у модели неизвестен реальный прогресс.
    // Вместо него движущийся «заряд» как в игровом loading screen.
    const pos = (this.idx % (width + 6)) - 3;
    const cells = [];
    for (let i = 0; i < width; i++) {
      const distance = Math.abs(i - pos);
      if (distance === 0) cells.push(c('█', 'brightCyan'));
      else if (distance === 1) cells.push(c('▓', 'cyan'));
      else if (distance === 2) cells.push(c('▒', 'blue'));
      else cells.push(c('░', 'gray'));
    }
    return cells.join('');
  }
  indicatorLine() {
    const theme = INDICATOR_THEMES[CONFIG.indicatorStyle] ? CONFIG.indicatorStyle : 'game';
    const themeFrames = SPINNERS[theme] || this.frames;
    const frame = themeFrames[this.idx % themeFrames.length];
    let prefix;
    if (theme === 'game') prefix = `${c('🎮 ЗАГРУЗКА', 'brightMagenta')} ${c('[', 'gray')}${this.gameBar()}${c(']', 'gray')}`;
    else if (theme === 'dots') prefix = `${c(frame, 'magenta')} ${c('ДУМАЮ', 'brightMagenta')}`;
    else if (theme === 'line') prefix = `${c(frame, 'cyan')} ${c('ОЖИДАНИЕ', 'brightCyan')}`;
    else if (theme === 'pulse') prefix = `${c('[' + frame.repeat(2) + ']', 'brightGreen')} ${c('ОБРАБОТКА', 'green')}`;
    else prefix = `${c('⏳', 'yellow')} ${c('ЗАПРОС', 'yellow')}`;
    this.idx++;
    return `${prefix} ${c(this.text, 'gray')}`;
  }
  render() {
    // 1G = первый столбец, 2K = стереть всю текущую строку.
    readline.cursorTo(process.stdout, 0);
    readline.clearLine(process.stdout, 0);
    process.stdout.write(this.indicatorLine());
  }
  start() {
    this.active = true;
    // Не включаем перерисовку автоматически: некоторые агентские UI вырезают ANSI-коды,
    // превращая кадры в одну длинную склеенную строку.
    this.animated = !!CONFIG.animatedIndicator && !!process.stdout.isTTY && process.env.ZEN_NO_ANIMATION !== '1';
    if (!this.animated) {
      console.log(this.indicatorLine());
      this.timer = setInterval(() => { if (this.active) console.log(this.indicatorLine()); }, CONFIG.indicatorFallbackMs);
      return;
    }
    process.stdout.write('\x1b[?25l'); // скрыть курсор, пока перерисовывается статус
    this.render();
    this.timer = setInterval(() => { if (this.active) this.render(); }, CONFIG.indicatorAnimationMs);
  }
  stop(finalText = '') {
    this.active = false;
    if (this.timer) clearInterval(this.timer);
    if (this.animated) {
      readline.cursorTo(process.stdout, 0);
      readline.clearLine(process.stdout, 0);
      process.stdout.write('\x1b[?25h'); // вернуть курсор
    }
    if (finalText) console.log(finalText);
  }
}

// ═══════════════════════════════════════════════════════════════════
//  DASHBOARD / STATUS PANEL
// ═══════════════════════════════════════════════════════════════════
function drawDashboard() {
  if (!CONFIG.showDashboard) return;
  const tw = termWidth();
  const w = Math.min(72, tw - 4);
  const half = Math.floor((w - 3) / 2);

  const mem = process.memoryUsage();
  const memMB = (mem.heapUsed / 1024 / 1024).toFixed(1);
  const memTotal = (mem.heapTotal / 1024 / 1024).toFixed(1);
  const uptime = process.uptime();
  const upStr = uptime < 60 ? `${Math.floor(uptime)}s` : `${Math.floor(uptime/60)}m`;

  const leftLines = [
    `${c('Платформа:', 'gray')} ${c(PLATFORM.name, 'brightCyan')}`,
    `${c('Провайдер:', 'gray')} ${c(providerDisplayName(), currentProvider === 'openrouter' ? 'brightMagenta' : 'brightCyan')}`,
    `${c('Режим:', 'gray')}     ${c(AGENT_MODES[CONFIG.agentMode]?.label || CONFIG.agentMode, 'brightYellow')}`,
    `${c('Модель:', 'gray')}     ${c(currentModel, 'brightGreen')}`,
    `${c('MCP:', 'gray')}       ${mcpAvailable ? c('● подключён', 'green') : c('○ не запущен', 'gray')}`,
    `${c('Прокси:', 'gray')}    ${CONFIG.proxy ? c('● ' + maskProxy(CONFIG.proxy), 'green') : c('○ нет', 'gray')}`,
    `${c('Папка MCP:', 'gray')} ${c(WORKSPACE_ROOT, 'gray')}`,
    `${c('Память:', 'gray')}    ${progressBar(memMB, memTotal, 12, {color:'cyan', bgColor:'gray'})}`,
  ];
  const rightLines = [
    `${c('Авто-одобрение:', 'gray')} ${CONFIG.autoApprove ? c('✓ ON', 'green') : c('✗ OFF', 'gray')}`,
    `${c('Долгая задача:', 'gray')}  ${CONFIG.longTaskMode ? c('✓ ON', 'green') : c('✗ OFF', 'gray')}`,
    `${c('Стриминг:', 'gray')}       ${CONFIG.streamMode ? c('✓ ON', 'green') : c('✗ OFF', 'gray')}`,
    `${c('Размышления:', 'gray')}    ${CONFIG.showThinking ? c('✓ ON', 'green') : c('✗ OFF', 'gray')}`,
    `${c('Аптайм:', 'gray')}         ${c(upStr, 'yellow')}`,
  ];

  const maxH = Math.max(leftLines.length, rightLines.length);
  const body = [];
  for (let i = 0; i < maxH; i++) {
    const l = leftLines[i] || '';
    const r = rightLines[i] || '';
    const lPlain = stripAnsi(l).length;
    const rPlain = stripAnsi(r).length;
    const gap = w - 2 - lPlain - rPlain;
    body.push(l + ' '.repeat(Math.max(1, gap)) + r);
  }

  const lines = box(body, { width: w, title: ' Статус ', style: 'double', color: 'cyan', titleColor: 'brightCyan' });
  lines.forEach(ln => console.log(ln));
}

function drawMiniStatus() {
  const parts = [
    badge(PLATFORM.type.toUpperCase(), 'bgCyan', 'black'),
    badge(CONFIG.agentMode.toUpperCase(), CONFIG.agentMode === 'build' ? 'bgGreen' : 'bgYellow', 'black'),
    badge(currentProvider === 'openrouter' ? 'OPEN' : 'ZEN', currentProvider === 'openrouter' ? 'bgMagenta' : 'bgBlue', 'white'),
    badge(currentModel, 'bgBlue', 'white'),
    badge(mcpAvailable ? 'MCP●' : 'MCP○', mcpAvailable ? 'bgGreen' : 'bgGray', 'black'),
    badge(CONFIG.autoApprove ? 'AUTO●' : 'AUTO○', CONFIG.autoApprove ? 'bgGreen' : 'bgGray', 'black'),
  ];
  console.log(parts.join(' '));
}

// ═══════════════════════════════════════════════════════════════════
//  TOOL OUTPUT FORMATTERS
// ═══════════════════════════════════════════════════════════════════
function formatToolResult(name, result, args) {
  const iconMap = {
    list_dir: '📂', read_file: '📖', write_file: '✏️',
    edit_file: '📝', delete_file: '🗑️', append_file: '➕',
    execute_command: '⚙️', web_search: '🔍', download_file: '⬇️',
    image_info: '🖼️', ocr_image: '🔤', vision_analyze: '👁️', analyze_image: '👁️', vision_ui_audit: '🧩', vision_compare: '🆚', custom_tool_list: '🧰', custom_tool_create: '🛠️', custom_tool_inspect: '🔎', custom_tool_run: '▶️', custom_tool_delete: '🗑️', subagent_list: '👥', subagent_create: '👤', subagent_task: '🤝', subagent_delete: '🗑️', plugin_list: '🧩', plugin_create: '🧩', plugin_inspect: '🔎', plugin_delete: '🗑️', plugin_tool_list: '🧰', plugin_tool_run: '▶️', plugin_provider_list: '🔌',
    ...(capabilitiesModule ? capabilitiesModule.CAPABILITY_ICONS : {}),
    workspace_info: '📍', set_workspace: '📍', project_inspect: '🧭', termux_info: '📱', network_check: '🌐', tree_dir: '🌳', search_text: '🔎', file_info: 'ℹ️', find_files: '🔎',
    file_backup: '💾', file_diff: '🧩', mkdir: '📁', copy_file: '📋', move_file: '🚚', archive_create: '🗜️', archive_extract: '📦',
    process_start: '▶️', process_status: '📊', process_logs: '📜', process_stop: '⏹️', monitor_start: '🩺', monitor_list: '🩺', monitor_logs: '📜', monitor_stop: '⏹️',
    terminal_create: '💻', terminal_write: '⌨️', terminal_read: '📟', terminal_list: '💻', terminal_close: '⏹️',
    http_request: '🌐', health_check: '💓', websocket_test: '🔌', npm_install: '📦', npm_run: '▶️', sqlite_info: '🗃️', sqlite_query: '🗃️', sqlite_schema: '🗃️', sqlite_backup: '💾', env_list: '🔐', env_set: '🔐', env_delete: '🔐', run_tests: '🧪', run_lint: '🧹', code_check: '✅', dependency_audit: '🔐',
    git_status: '🌿', git_diff: '🌿', git_branch: '🌿', git_log: '🌿', git_init: '🌿', git_commit: '🌿', git_clone: '⬇', git_pull: '⬇', git_push: '⬆', open_url: '🌐', clipboard_read: '📋', clipboard_write: '📋', notify: '🔔', termux_api_status: '📱', termux_battery: '🔋', termux_wifi: '📶', termux_toast: '💬', termux_vibrate: '📳', termux_share: '📤', termux_volume: '🔊', termux_location: '📍',
    todo_list: '📋', todo_add: '➕', todo_done: '✅', todo_remove: '🗑️',
  };
  const icon = iconMap[name] || '🔧';
  const tw = termWidth();
  const w = Math.min(76, tw - 2);
  let json = null;
  try { json = JSON.parse(result); } catch {}

  let content = '';
  if (COMMAND_RESULT_TOOLS.has(name)) {
    content = result;
  } else if (name === 'workspace_info' && json) {
    content = [
      `${c('Рабочая папка:', 'gray')} ${c(json.workspace || '', 'brightCyan')}`,
      `${c('Относительные пути:', 'gray')} ${json.relativePathsResolveTo || ''}`,
      `${c('Правило:', 'gray')} ${json.policy || ''}`
    ].join('\n');
  } else if (name === 'set_workspace' && json) {
    content = `${c('✓ Активная MCP-папка:', 'green')} ${json.workspace || ''}`;
  } else if (name === 'list_dir' && json) {
    const items = json.items || [];
    content = `${c('Папка:', 'gray')} ${json.path || ''}\n` +
      (items.length ? items.map(it => `  ${it.type === 'directory' ? c('▸', 'cyan') : c('•', 'gray')} ${it.name}`).join('\n') : c('  Папка пуста', 'gray'));
  } else if (name === 'find_files' && json) {
    const matches = json.matches || [];
    content = `${c('Искали в:', 'gray')} ${json.searched || ''}\n` +
      (matches.length ? matches.map(it => `  ${it.type === 'directory' ? c('▸', 'cyan') : c('•', 'gray')} ${it.path}`).join('\n') : c('  Ничего не найдено', 'gray'));
    if (json.truncated) content += '\n' + c('  Показаны первые результаты; сузь запрос.', 'yellow');
  } else if (name === 'todo_list' && json) {
    const items = json.todos || [];
    content = `${c('Проект:', 'gray')} ${json.workspace || ''}\n` +
      (items.length ? items.map(t => `  ${t.done ? c('✓', 'green') : c('○', 'gray')} #${t.id} ${t.text}`).join('\n') : c('  Нет задач', 'gray'));
  } else if (name === 'read_file' || name === 'process_logs' || name === 'monitor_logs' || name === 'terminal_read') {
    content = frag(result, 30, 15);
  } else if (name === 'write_file' || name === 'append_file') {
    const lines = (args.content || '').split('\n');
    content = `${c('Результат:', 'gray')} ${redactSecrets(result)}\n${c(lines.length + ' строк передано', 'gray')}\n` + frag(redactSecrets(args.content || ''), 8, 4);
  } else if (name === 'edit_file') {
    content = `${c('Результат:', 'gray')} ${result}\n${c('−', 'brightRed')} ${frag(args.old || '', 3, 2)}\n${c('+', 'brightCyan')} ${frag(args.new || '', 3, 2)}`;
  } else {
    content = redactSecrets(result).slice(0, 900);
  }

  const body = [
    `${icon} ${c(name, 'brightCyan')} ${args.path ? c(args.path, 'gray') : ''}`,
    '',
    content,
  ];
  const lines = box(body, { width: w, style: 'single', color: 'blue' });
  lines.forEach(ln => console.log(ln));
}

function formatFinalAnswer(text) {
  const tw = termWidth();
  const w = Math.min(78, tw - 2);
  const lines = redactSecrets(text).split('\n');
  const body = lines.map(l => {
    const plain = stripAnsi(l);
    if (plain.length > w - 4) {
      const chunks = [];
      let i = 0;
      while (i < plain.length) {
        chunks.push(plain.slice(i, i + w - 4));
        i += w - 4;
      }
      return chunks.join('\n');
    }
    return l;
  });
  const boxed = box(body, { width: w, style: 'single', color: 'green' });
  boxed.forEach(ln => console.log(ln));
}

// ═══════════════════════════════════════════════════════════════════
//  BANNER
// ═══════════════════════════════════════════════════════════════════
function printBanner() {
  const art = [
    c('    ╔══════════════════════════════════════════════════════════════╗', 'cyan'),
    c('    ║  ██████╗ ██████╗ ███████╗███╗   ██╗ ██████╗ ██████╗ ███████╗║', 'cyan'),
    c('    ║  ██╔═══██╗██╔══██╗██╔════╝████╗  ██║██╔════╝██╔═══██╗██╔════╝║', 'cyan'),
    c('    ║  ██║   ██║██████╔╝█████╗  ██╔██╗ ██║██║     ██║   ██║█████╗  ║', 'cyan'),
    c('    ║  ██║   ██║██╔═══╝ ██╔══╝  ██║╚██╗██║██║     ██║   ██║██╔══╝  ║', 'cyan'),
    c('    ║  ╚██████╔╝██║     ███████╗██║ ╚████║╚██████╗╚██████╔╝██║     ║', 'cyan'),
    c('    ║   ╚═════╝ ╚═╝     ╚══════╝╚═╝  ╚═══╝ ╚═════╝ ╚═════╝ ╚═╝     ║', 'cyan'),
    c('    ║                                                              ║', 'cyan'),
    c('    ║         Unified CLI Agent  •  Termux / Linux / PC            ║', 'cyan'),
    c('    ╚══════════════════════════════════════════════════════════════╝', 'cyan'),
  ];
  art.forEach(l => console.log(l));

  const tw = termWidth();
  const infoW = Math.min(60, tw - 4);
  const info = [
    `${c('🤖', 'brightCyan')} ${c('Модель:', 'gray')} ${c(currentModel, 'brightGreen')}`,
    `${c('🔀', 'brightMagenta')} ${c('Провайдер:', 'gray')} ${c(providerDisplayName(), currentProvider === 'openrouter' ? 'brightMagenta' : 'brightCyan')}`,
    `${c('📱', 'yellow')} ${c('Платформа:', 'gray')} ${c(PLATFORM.name, 'brightCyan')}`,
    `${c('🔗', 'green')} ${c('MCP:', 'gray')} ${mcpAvailable ? c('● встроенный сервер', 'green') : c('○ недоступен', 'gray')}`,
    `${c('🌐', 'yellow')} ${c('Прокси:', 'gray')} ${c(CONFIG.proxy ? maskProxy(CONFIG.proxy) : 'не задан', CONFIG.proxy ? 'green' : 'gray')}`,
    `${c('💾', 'magenta')} ${c('MCP-папка:', 'gray')} ${c(WORKSPACE_ROOT, 'gray')}`,
  ];
  box(info, { width: infoW, title: ' Инфо ', style: 'single', color: 'gray' }).forEach(l => console.log(l));

  console.log();
  const cmds = [
    c('/help', 'brightCyan'), c('/tools', 'brightCyan'), c('/mode', 'brightCyan'), c('/zen', 'brightCyan'), c('/open', 'brightCyan'), c('/models', 'brightCyan'), c('/session', 'brightCyan'), c('/mcp', 'brightCyan'), c('/net', 'brightCyan'), c('/vpn', 'brightCyan'), c('/proxy', 'brightCyan'),
    c('/stream', 'brightCyan'), c('/auto', 'brightCyan'), c('/think', 'brightCyan'), c('/logs', 'brightCyan'),
    c('/clear', 'brightCyan'), c('/save', 'brightCyan'), c('/exit', 'brightCyan'),
  ];
  console.log(c('Команды:', 'gray') + ' ' + cmds.join(c(' │ ', 'gray')));
  console.log(horizontalRule('─', 'gray'));
}

// ═══════════════════════════════════════════════════════════════════
//  ZEN FREE MODELS
// ═══════════════════════════════════════════════════════════════════
// Free models only, ordered by measured reliability rather than by name.
//
// Measured against the live endpoint, three requests each:
//   laguna-s-2.1-free       3/3   ~8.9s   fastest that always answers
//   mimo-v2.5-free          3/3   ~5.9s   quickest overall
//   deepseek-v4-flash-free  3/3  ~18.1s   slowest; was the default
//   north-mini-code-free    0/3          upstream returns server_error
//   nemotron-3-ultra-free   0/3          times out past 30s
//   ling-3.0-*, longcat     0/3          server_error
//
// The dead ones are kept out of the list entirely: offering a model that
// never answers is what made the agent feel broken rather than slow.
const ZEN_MODELS = [
  { id: 'laguna-s-2.1-free', name: 'Laguna S 2.1', ctx: '128K', note: 'быстрая, стабильная' },
  { id: 'mimo-v2.5-free', name: 'MiMo V2.5', ctx: '128K', note: 'самая быстрая' },
  { id: 'deepseek-v4-flash-free', name: 'DeepSeek V4 Flash', ctx: '200K', note: 'медленная, большой контекст' }
];

/** Models to try, in order, when the chosen one fails. */
const ZEN_FALLBACK_ORDER = ZEN_MODELS.map(m => m.id);

// ═══════════════════════════════════════════════════════════════════
//  LEGACY COLOR HELPER (backward compat)
// ═══════════════════════════════════════════════════════════════════
const col = (s, color) => c(s, color);

function frag(s, head = 10, tail = 6) {
  const lines = String(s).split('\n');
  if (lines.length <= head + tail + 2) return s;
  return lines.slice(0, head).join('\n') + `\n${c('… (' + (lines.length - head - tail) + ' строк скрыто) …', 'gray')}\n` + lines.slice(-tail).join('\n');
}

// ═══════════════════════════════════════════════════════════════════
//  PLATFORM
// ═══════════════════════════════════════════════════════════════════
function detectPlatform() {
  const env = process.env;
  let isTermux = false;
  if (env.TERMUX_VERSION || env.TERMUX ||
      (env.PREFIX && env.PREFIX.includes('termux')) ||
      (env.HOME && env.HOME.includes('com.termux')) ||
      /termux/i.test(env.SHELL || '')) {
    isTermux = true;
  }
  if (!isTermux) {
    try {
      const termuxPaths = [
        '/data/data/com.termux/files/usr/bin/termux-info',
        '/data/data/com.termux/files/usr/bin/pkg',
        '/data/data/com.termux/files/home'
      ];
      for (const p of termuxPaths) {
        if (fs.existsSync(p)) { isTermux = true; break; }
      }
    } catch {}
  }
  const cwd = process.cwd();
  if (!isTermux && (cwd.includes('storage/emulated') || cwd.includes('emulated/0') || cwd.includes('Download'))) {
    isTermux = true;
  }
  return {
    type: isTermux ? 'termux' : 'pc',
    name: isTermux ? 'Termux (Android)' : (os.platform() === 'linux' ? 'Linux PC' : os.platform()),
    isTermux,
    cwd,
    recommendedStorage: isTermux ? '/storage/emulated/0/Download/zenai' : cwd
  };
}
const PLATFORM = detectPlatform();

// ═══════════════════════════════════════════════════════════════════
//  TERMUX WORKSPACE POLICY
//  На Android проекты должны жить в общей памяти. Внутренний $HOME
//  Termux предназначен только для самого агента и его настроек.
// ═══════════════════════════════════════════════════════════════════
const TERMUX_SHARED_ROOT = '/storage/emulated/0';
const WORKSPACE_FILE = path.join(os.homedir(), '.zen_workspace.json');

function isPathInside(candidate, parent) {
  const rel = path.relative(path.resolve(parent), path.resolve(candidate));
  return rel === '' || (!rel.startsWith('..' + path.sep) && rel !== '..' && !path.isAbsolute(rel));
}

function isTermuxSharedPath(candidate) {
  return !PLATFORM.isTermux || isPathInside(candidate, TERMUX_SHARED_ROOT);
}

function loadWorkspaceConfig() {
  try {
    const saved = JSON.parse(fs.readFileSync(WORKSPACE_FILE, 'utf8'));
    if (saved && typeof saved.root === 'string' && fs.existsSync(saved.root) &&
        fs.statSync(saved.root).isDirectory() && isTermuxSharedPath(saved.root)) return saved;
  } catch {}
  return {};
}

function defaultWorkspaceRoot() {
  const saved = loadWorkspaceConfig();
  const requested = CONFIG.workspaceRoot || saved.root;
  const candidates = [
    requested,
    PLATFORM.isTermux ? '/storage/emulated/0/Download/zenai' : null,
    PLATFORM.isTermux ? TERMUX_SHARED_ROOT : null,
    !PLATFORM.isTermux ? process.cwd() : null
  ].filter(Boolean);
  for (const candidate of candidates) {
    try {
      const root = path.resolve(candidate);
      if (fs.existsSync(root) && fs.statSync(root).isDirectory() && isTermuxSharedPath(root)) return root;
    } catch {}
  }
  // Не откатываемся к $HOME Termux: там только настройки агента, не проекты.
  return PLATFORM.isTermux ? TERMUX_SHARED_ROOT : process.cwd();
}

let WORKSPACE_ROOT = defaultWorkspaceRoot();
// Keep downloaded offline models with the Collection server files, not in the
// Termux private home and not in the browser cache. ZEN_MODEL_ROOT can move
// this only to a user-controlled location.
const COLLECTION_STORAGE_ROOT = path.resolve(process.env.ZEN_MODEL_ROOT || process.env.ZEN_WORKSPACE || WORKSPACE_ROOT);
const localAi = new LocalAiManager({ storageRoot: () => COLLECTION_STORAGE_ROOT, logger: console });

function saveWorkspaceConfig() {
  try {
    fs.writeFileSync(WORKSPACE_FILE, JSON.stringify({
      root: WORKSPACE_ROOT,
      updated: new Date().toISOString(),
      platform: PLATFORM.type
    }, null, 2));
  } catch {}
}

function setWorkspaceRoot(rawPath) {
  const requested = String(rawPath || '').trim();
  if (!requested) return { error: 'Укажи папку проекта в общей памяти Android.' };
  const resolved = path.resolve(path.isAbsolute(requested) ? requested : path.join(WORKSPACE_ROOT, requested));
  try {
    if (!fs.existsSync(resolved) || !fs.statSync(resolved).isDirectory()) {
      return { error: 'Папка не найдена: ' + resolved };
    }
    if (!isTermuxSharedPath(resolved)) {
      return { error: 'На Termux рабочая папка должна быть в /storage/emulated/0, а не во внутреннем $HOME.' };
    }
  } catch (e) { return { error: 'Не удалось открыть папку: ' + e.message }; }
  WORKSPACE_ROOT = resolved;
  CONFIG.workspaceRoot = resolved;
  saveWorkspaceConfig();
  return { success: true, workspace: WORKSPACE_ROOT };
}

function resolveWorkspacePath(rawPath, label = 'path') {
  const supplied = rawPath === undefined || rawPath === null || String(rawPath).trim() === '' ? '.' : String(rawPath).trim();
  const resolved = path.resolve(path.isAbsolute(supplied) ? supplied : path.join(WORKSPACE_ROOT, supplied));
  if (PLATFORM.isTermux && !isTermuxSharedPath(resolved)) {
    return { error: `${label} вне общей памяти Android: ${resolved}. Используй /storage/emulated/0/...` };
  }
  return { path: resolved };
}

function workspaceInfo() {
  return {
    platform: PLATFORM.name,
    workspace: WORKSPACE_ROOT,
    storageRoot: PLATFORM.isTermux ? TERMUX_SHARED_ROOT : null,
    relativePathsResolveTo: WORKSPACE_ROOT,
    policy: PLATFORM.isTermux
      ? 'MCP работает только с общей памятью Android (/storage/emulated/0). Внутренний $HOME Termux не используется для проектов.'
      : 'Относительные пути MCP разрешаются от рабочей папки агента.'
  };
}

// ═══════════════════════════════════════════════════════════════════
//  PROXY / VPN-AWARE NETWORK CONFIG
//  VPN itself is managed by Android. This setting routes agent curl traffic
//  through an HTTP(S) or SOCKS proxy when Wi-Fi blocks the direct route.
// ═══════════════════════════════════════════════════════════════════
const NETWORK_CONFIG_FILE = path.join(os.homedir(), '.zen_network.json');

function maskProxy(proxy) {
  if (!proxy) return 'выключен (прямое подключение)';
  try {
    const u = new URL(proxy);
    if (u.username || u.password) {
      const auth = u.username ? `${decodeURIComponent(u.username)}:***@` : '';
      return `${u.protocol}//${auth}${u.host}`;
    }
    return proxy;
  } catch { return proxy.replace(/:\/\/([^:@/]+):[^@/]+@/, '://$1:***@'); }
}

function loadNetworkConfig() {
  // Переменная окружения имеет приоритет над сохранённой настройкой.
  if (process.env.ZEN_PROXY || process.env.HTTP_PROXY || process.env.HTTPS_PROXY) return;
  try {
    const saved = JSON.parse(fs.readFileSync(NETWORK_CONFIG_FILE, 'utf8'));
    if (saved && typeof saved.proxy === 'string' && saved.proxy.trim()) CONFIG.proxy = saved.proxy.trim();
  } catch {}
}

function saveNetworkConfig() {
  try {
    fs.writeFileSync(NETWORK_CONFIG_FILE, JSON.stringify({
      proxy: CONFIG.proxy || '',
      updated: new Date().toISOString()
    }, null, 2), { mode: 0o600 });
    try { fs.chmodSync(NETWORK_CONFIG_FILE, 0o600); } catch {}
  } catch (e) { return { error: 'Не удалось сохранить прокси: ' + e.message }; }
  return { success: true };
}

function setProxy(value, persist = true) {
  const raw = String(value || '').trim().replace(/^['"]|['"]$/g, '');
  if (!raw || /^(off|none|disable|выкл|нет)$/i.test(raw)) {
    CONFIG.proxy = '';
    const saved = persist ? saveNetworkConfig() : { success: true };
    return saved.error ? saved : { success: true, proxy: '', message: 'Прокси выключен. Используется прямое соединение.' };
  }
  // curl understands http://, https://, socks4://, socks4a://, socks5:// and socks5h://.
  if (!/^(https?|socks4a?|socks5h?):\/\/[^\s]+$/i.test(raw)) {
    return { error: 'Неверный адрес. Пример: socks5h://127.0.0.1:1080 или http://user:pass@host:port' };
  }
  CONFIG.proxy = raw;
  const saved = persist ? saveNetworkConfig() : { success: true };
  return saved.error ? saved : { success: true, proxy: CONFIG.proxy, message: 'Прокси сохранён и будет применён к запросам Zen/curl.' };
}

function proxyStatus() {
  return {
    enabled: !!CONFIG.proxy,
    proxy: CONFIG.proxy ? maskProxy(CONFIG.proxy) : '',
    source: (process.env.ZEN_PROXY || process.env.HTTP_PROXY || process.env.HTTPS_PROXY) ? 'environment' : 'saved',
    note: CONFIG.proxy
      ? 'Прокси применяется к Zen API, потоковой выдаче и download_file.'
      : 'Прокси не задан. Android VPN всё равно может работать системно, если Termux не исключён из VPN.'
  };
}

loadNetworkConfig();

function findWorkspaceEntries(query, options = {}) {
  const baseResult = resolveWorkspacePath(options.path || '.');
  if (baseResult.error) return baseResult;
  const base = baseResult.path;
  const needle = String(query || '').toLowerCase();
  const maxDepth = Math.min(Math.max(parseInt(options.max_depth || options.maxDepth || 3, 10) || 3, 0), 6);
  const limit = Math.min(Math.max(parseInt(options.limit || 80, 10) || 80, 1), 300);
  const directoriesOnly = !!options.directories_only;
  const result = [];
  const skip = new Set(['node_modules', '.git', '.cache', 'Android', 'DCIM', 'Pictures', 'Movies', 'Music']);

  function walk(dir, depth) {
    if (result.length >= limit || depth > maxDepth) return;
    let entries = [];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      if (result.length >= limit) return;
      const full = path.join(dir, entry.name);
      const matches = !needle || entry.name.toLowerCase().includes(needle);
      if (matches && (!directoriesOnly || entry.isDirectory())) {
        result.push({ name: entry.name, path: full, type: entry.isDirectory() ? 'directory' : 'file' });
      }
      if (entry.isDirectory() && depth < maxDepth && !skip.has(entry.name)) walk(full, depth + 1);
    }
  }
  if (!fs.existsSync(base) || !fs.statSync(base).isDirectory()) return { error: 'Папка не найдена: ' + base };
  walk(base, 0);
  return { workspace: WORKSPACE_ROOT, searched: base, matches: result, truncated: result.length >= limit };
}

// ═══════════════════════════════════════════════════════════════════
//  curl helper
// ═══════════════════════════════════════════════════════════════════
function curlPath() {
  if (process.platform === 'win32') {
    const fsPath = 'C:\\Windows\\System32\\curl.exe';
    return fs.existsSync(fsPath) ? fsPath : 'curl';
  }
  return 'curl';
}

function curlProxyArgs() {
  let args = '';
  if (CONFIG.proxy) args += ` -x "${CONFIG.proxy}"`;
  if (CONFIG.curlIpv4) args += ' --ipv4';
  return args;
}

function isRateLimit(s) {
  return /FreeUsageLimitError|Rate limit exceeded|rate.?limit/i.test(s || '');
}


// ═══════════════════════════════════════════════════════════════════
//  EMBEDDED MCP SERVER + ZEN PROXY (no external server.js needed)
// ═══════════════════════════════════════════════════════════════════
const MCP_TOOLS = {
  workspace_info: 'Показать текущую рабочую папку MCP и правила путей',
  set_workspace: 'Сменить рабочую папку проекта в общей памяти Android',
  project_inspect: 'Проверить package.json, скрипты, зависимости и файлы проекта',
  termux_info: 'Проверить среду Termux, Node, npm и доступ к общей памяти',
  network_check: 'Проверить доступ к серверу моделей через текущую сеть/VPN',
  tree_dir: 'Показать дерево папки с ограничением глубины',
  search_text: 'Найти текст в файлах проекта без shell grep',
  file_info: 'Размер, даты и SHA-256 файла',
  list_dir: 'Список файлов и папок в рабочей папке',
  find_files: 'Поиск файлов и папок только внутри рабочей папки',
  read_file: 'Прочитать содержимое файла',
  write_file: 'Записать / создать файл',
  edit_file: 'Точечное редактирование',
  delete_file: 'Удалить файл или папку',
  append_file: 'Добавить текст в конец файла',
  file_backup: 'Создать резервную копию файла в папке проекта',
  file_diff: 'Показать различия файла и его резервной копии',
  mkdir: 'Создать папку в рабочем проекте',
  copy_file: 'Скопировать файл или папку внутри общей памяти',
  move_file: 'Переместить или переименовать файл/папку',
  archive_create: 'Создать tar.gz-архив проекта',
  archive_extract: 'Распаковать tar.gz-архив',
  download_file: 'Скачать файл по URL',
  execute_command: 'Выполнить shell-команду из рабочей папки проекта',
  process_start: 'Запустить именованный процесс в фоне с отдельным логом',
  process_status: 'Проверить только процессы, запущенные этим агентом',
  process_logs: 'Прочитать или кратко отслеживать лог управляемого процесса',
  process_stop: 'Безопасно остановить именованный процесс агента',
  monitor_start: 'Следить за процессом/health URL и при необходимости перезапускать',
  monitor_list: 'Список локальных health-мониторов',
  monitor_logs: 'Лог проверок health-монитора',
  monitor_stop: 'Остановить health-монитор',
  terminal_create: 'Создать постоянную локальную shell-сессию',
  terminal_write: 'Отправить текст или команду в постоянную shell-сессию',
  terminal_read: 'Прочитать накопленный вывод постоянной shell-сессии',
  terminal_list: 'Список текущих terminal-сессий',
  terminal_close: 'Закрыть terminal-сессию',
  http_request: 'Отправить HTTP-запрос и показать статус, заголовки и тело',
  health_check: 'HTTP-проверка доступности локального сервера',
  websocket_test: 'Проверить обычный WebSocket или Socket.IO-соединение',
  npm_install: 'Установить npm-пакеты с видимыми логами',
  npm_run: 'Запустить npm script с видимыми логами',
  sqlite_info: 'Проверить локальную доступность SQLite',
  sqlite_query: 'Выполнить SQL-запрос к SQLite-файлу проекта',
  sqlite_schema: 'Показать SQLite-схему',
  sqlite_backup: 'Создать SQLite backup без внешнего сервиса',
  env_list: 'Показать ключи .env без раскрытия значений',
  env_set: 'Создать или изменить переменную .env',
  env_delete: 'Удалить переменную из .env',
  run_tests: 'Запустить npm test или указанный тестовый script',
  run_lint: 'Запустить npm lint или указанный lint script',
  code_check: 'Проверить синтаксис JavaScript-файла',
  dependency_audit: 'Выполнить npm audit без автоматических исправлений',
  github_read: 'Прочитать файл прямо из GitHub, без клонирования',
  github_write: 'Записать файл прямо в GitHub — это сразу коммит',
  github_list: 'Список файлов в папке репозитория на GitHub',
  github_delete: 'Удалить файл в GitHub одним коммитом',
  github_commit_files: 'Несколько файлов одним коммитом через GitHub API',
  github_search: 'Поиск кода в репозитории на GitHub',
  github_commits: 'История коммитов без клонирования',
  github_branches: 'Список веток репозитория',
  github_create_branch: 'Создать ветку на GitHub',
  github_pr: 'Открыть pull request',
  github_repo: 'Сведения о репозитории',
  github_my_repos: 'Список репозиториев, видимых токену',
  github_runs: 'Последние запуски GitHub Actions',
  github_run_workflow: 'Запустить workflow на GitHub',
  preset_list: 'Показать пресеты и какие включены',
  preset_set: 'Включить или выключить пресет (id, on)',
  preset_save: 'Сохранить свой пресет (id, text)',
  git_status: 'Статус Git-репозитория',
  git_diff: 'Показать Git diff',
  git_branch: 'Показать текущую и доступные Git-ветки',
  git_log: 'Последние Git-коммиты',
  git_init: 'Инициализировать Git-репозиторий',
  git_clone: 'Склонировать ЛЮБОЙ репозиторий в work/ — repo: owner/name или URL',
  git_pull: 'Обновить репозиторий из origin',
  git_push: 'Отправить коммиты в origin',
  git_commit: 'Добавить изменения и создать Git-коммит',
  open_url: 'Открыть URL через Android/Termux',
  clipboard_read: 'Прочитать буфер обмена Android',
  clipboard_write: 'Записать текст в буфер обмена Android',
  notify: 'Показать Android-уведомление через Termux:API',
  termux_api_status: 'Проверить доступные команды Termux:API',
  termux_battery: 'Прочитать состояние батареи Android через Termux:API',
  termux_wifi: 'Прочитать Wi-Fi connection info через Termux:API',
  termux_toast: 'Показать короткое Android toast-сообщение',
  termux_vibrate: 'Вибрация Android через Termux:API',
  termux_share: 'Открыть Android share sheet для текста или файла',
  termux_volume: 'Установить громкость Android stream через Termux:API',
  termux_location: 'Запросить location Android через Termux:API',
  todo_list: 'Показать задачи агента для текущего проекта',
  todo_add: 'Добавить задачу в список проекта',
  todo_done: 'Отметить задачу выполненной',
  todo_remove: 'Удалить задачу',
  web_search: 'Поиск по Wikipedia (энциклопедический)',
  web_fetch: 'Открыть страницу по URL и прочитать её как текст',
  image_info: 'Локальные metadata, размер, dimensions и SHA-256 изображения',
  ocr_image: 'Локально распознать текст на изображении через Tesseract',
  vision_analyze: 'Vision-анализ одного изображения через выбранную OpenRouter vision-модель',
  analyze_image: 'Псевдоним vision_analyze для совместимости',
  vision_ui_audit: 'Найти UI/UX-проблемы на скриншоте',
  vision_compare: 'Visual compare двух скриншотов через vision-модель',
  custom_tool_list: 'Показать локальные custom tools из .zen-agent/custom-tools',
  custom_tool_create: 'Создать и подключить локальный custom tool в специальной папке',
  custom_tool_inspect: 'Прочитать manifest и код custom tool',
  custom_tool_run: 'Запустить подключённый custom tool в ограниченном API-контексте',
  custom_tool_delete: 'Удалить локальный custom tool',
  subagent_list: 'Показать встроенные и локальные subagents',
  subagent_create: 'Создать локального subagent с ролью и isolated prompt',
  subagent_task: 'Поручить read-only аналитическую подзадачу subagent',
  subagent_delete: 'Удалить локального subagent',
  plugin_list: 'Показать локальные lifecycle plugins',
  plugin_create: 'Создать локальный plugin с hooks/tools/provider definitions',
  plugin_inspect: 'Прочитать manifest и код plugin',
  plugin_delete: 'Удалить локальный plugin',
  plugin_tool_list: 'Показать tools, зарегистрированные plugins',
  plugin_tool_run: 'Запустить tool из подключённого plugin',
  plugin_provider_list: 'Показать providers, зарегистрированные plugins',
  ...(capabilitiesModule ? capabilitiesModule.CAPABILITY_TOOLS : {}),
  read_image: 'Прочитать изображение как base64 (технический инструмент)'
};

// Capabilities need the host's workspace resolution, environment and audit
// trail, so they are wired through a context object rather than importing
// state across the module boundary.
const capabilities = capabilitiesModule ? capabilitiesModule.createCapabilities({
  workspaceRoot: () => WORKSPACE_ROOT,
  commandEnvironment: () => commandEnvironment(),
  resolvePath: (input, label) => mcpPathOrError(input, label, true, true),
  auditEvent: (event, data) => auditEvent(event, data)
}) : null;

function mcpPathOrError(input, label = 'path', mustExist = false, directoryOnly = false) {
  const resolved = resolveWorkspacePath(input, label);
  if (resolved.error) return resolved;
  try {
    if (mustExist && !fs.existsSync(resolved.path)) return { error: `${label} не найден: ${resolved.path}` };
    if (directoryOnly && (!fs.existsSync(resolved.path) || !fs.statSync(resolved.path).isDirectory())) {
      return { error: `${label} не является папкой: ${resolved.path}` };
    }
  } catch (e) { return { error: `Не удалось проверить ${label}: ${e.message}` }; }
  return resolved;
}

function printMcpTrace(lines) {
  if (!CONFIG.liveToolLogs) return;
  for (const line of lines) console.log(c('  [MCP] ', 'magenta') + line);
}

function appendCommandOutput(previous, chunk, max = 10 * 1024 * 1024) {
  if (previous.length >= max) return previous;
  const remaining = max - previous.length;
  return previous + chunk.slice(0, remaining);
}

function printLiveCommandChunk(stream, chunk) {
  const color = stream === 'stderr' ? 'brightYellow' : 'brightCyan';
  const text = chunk.toString('utf8');
  // Это прямой поток дочернего процесса. Ничего не генерируется и не сокращается.
  const prefix = c(`  [${stream}] `, color);
  const rendered = text.replace(/\n(?!$)/g, '\n' + prefix);
  process.stdout.write(prefix + rendered);
  if (!text.endsWith('\n')) process.stdout.write('\n');
}

function runCommandWithLiveLogs(command, runCwd, opts) {
  return new Promise((resolve) => {
    const timeoutMs = opts.timeout;
    const childEnv = opts.env;
    const isWin = process.platform === 'win32';
    const executable = isWin ? 'powershell.exe' : (process.env.SHELL || 'sh');
    const childArgs = isWin
      ? ['-NoProfile', '-Command', String(command)]
      : ['-lc', String(command)];
    let stdout = '', stderr = '', settled = false, timedOut = false;
    let proc;

    printMcpTrace([
      c('LIVE EXEC — реальный процесс запущен', 'brightGreen'),
      `${c('cwd:', 'gray')} ${runCwd}`,
      `${c('$', 'gray')} ${command}`
    ]);

    const finish = (exit, error = '') => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) stderr = appendCommandOutput(stderr, error);
      console.log(c(`  [MCP] процесс завершён: exit=${typeof exit === 'number' ? exit : 1}${timedOut ? ' (timeout)' : ''}`, timedOut || exit ? 'yellow' : 'green'));
      resolve({
        stdout,
        stderr,
        exit: typeof exit === 'number' ? exit : 1,
        cwd: runCwd,
        workspace: WORKSPACE_ROOT,
        live: true,
        timedOut
      });
    };

    let timer;
    try {
      proc = spawn(executable, childArgs, { cwd: runCwd, env: childEnv, stdio: ['ignore', 'pipe', 'pipe'] });
      proc.stdout.on('data', (chunk) => {
        const text = chunk.toString('utf8');
        stdout = appendCommandOutput(stdout, text);
        printLiveCommandChunk('stdout', chunk);
      });
      proc.stderr.on('data', (chunk) => {
        const text = chunk.toString('utf8');
        stderr = appendCommandOutput(stderr, text);
        printLiveCommandChunk('stderr', chunk);
      });
      proc.on('error', err => finish(1, err.message || String(err)));
      proc.on('close', code => finish(code));
      timer = setTimeout(() => {
        timedOut = true;
        console.log(c(`  [MCP] достигнут лимит ${timeoutMs}ms; отправляю SIGTERM процессу.`, 'yellow'));
        try { proc.kill('SIGTERM'); } catch {}
      }, timeoutMs);
    } catch (e) { finish(1, e.message || String(e)); }
  });
}


// ═══════════════════════════════════════════════════════════════════
//  TERMUX PROJECT OPERATIONS: processes, HTTP, packages and checks
// ═══════════════════════════════════════════════════════════════════
const PROCESS_REGISTRY_FILE = path.join(os.homedir(), '.zen_managed_processes.json');

function commandEnvironment() {
  return {
    ...process.env,
    ZEN_WORKSPACE: WORKSPACE_ROOT,
    MCP_WORKSPACE: WORKSPACE_ROOT,
    ...(CONFIG.proxy ? {
      HTTP_PROXY: CONFIG.proxy, HTTPS_PROXY: CONFIG.proxy, ALL_PROXY: CONFIG.proxy,
      http_proxy: CONFIG.proxy, https_proxy: CONFIG.proxy, all_proxy: CONFIG.proxy,
      NO_PROXY: [process.env.NO_PROXY, 'localhost,127.0.0.1,::1'].filter(Boolean).join(','),
      no_proxy: [process.env.no_proxy, 'localhost,127.0.0.1,::1'].filter(Boolean).join(',')
    } : {})
  };
}

function readProcessRegistry() {
  try {
    const data = JSON.parse(fs.readFileSync(PROCESS_REGISTRY_FILE, 'utf8'));
    return data && typeof data === 'object' && !Array.isArray(data) ? data : {};
  } catch { return {}; }
}
function writeProcessRegistry(registry) {
  try {
    fs.writeFileSync(PROCESS_REGISTRY_FILE, JSON.stringify(registry, null, 2), { mode: 0o600 });
    try { fs.chmodSync(PROCESS_REGISTRY_FILE, 0o600); } catch {}
    return true;
  } catch { return false; }
}
function safeProcessName(name) {
  const value = String(name || '').trim();
  // Разрешаем русские и другие Unicode-буквы, но не пробелы, слеши и shell-символы.
  return /^[\p{L}\p{N}._-]{1,64}$/u.test(value) ? value : null;
}
function managedProcessLogPath(name) {
  const dir = path.join(WORKSPACE_ROOT, '.zen-agent', 'processes');
  fs.mkdirSync(dir, { recursive: true });
  return path.join(dir, `${name}.log`);
}
function processIsAlive(pid) {
  const number = Number(pid);
  if (!Number.isInteger(number) || number <= 0) return false;
  try { process.kill(number, 0); return true; }
  catch (e) { return e && e.code === 'EPERM'; }
}
function tailFile(filePath, maxLines = 120) {
  try {
    const content = fs.readFileSync(filePath, 'utf8');
    const lines = content.split('\n');
    const take = Math.min(Math.max(Number(maxLines) || 120, 1), 1000);
    return { content: lines.slice(-take).join('\n'), totalLines: Math.max(0, lines.length - 1) };
  } catch (e) { return { error: 'Не удалось прочитать лог: ' + e.message }; }
}
function startManagedProcess(args) {
  const name = safeProcessName(args.name);
  if (!name) return { error: 'name: только латинские буквы, цифры, точка, подчёркивание или дефис (до 64 символов).' };
  const cwdResult = mcpPathOrError(args.cwd || '.', 'cwd', true, true);
  if (cwdResult.error) return cwdResult;
  const command = String(args.command || '').trim();
  if (!command) return { error: 'Для process_start нужна command.' };
  const registry = readProcessRegistry();
  const existing = registry[name];
  if (existing && processIsAlive(existing.pid)) return { error: `Процесс '${name}' уже запущен (PID ${existing.pid}). Используй process_status, process_logs или process_stop.` };
  const logPath = managedProcessLogPath(name);
  const logFd = fs.openSync(logPath, 'a');
  const isWin = process.platform === 'win32';
  const executable = isWin ? 'powershell.exe' : (process.env.SHELL || 'sh');
  const childArgs = isWin ? ['-NoProfile', '-Command', command] : ['-lc', command];
  try {
    const child = spawn(executable, childArgs, {
      cwd: cwdResult.path,
      env: commandEnvironment(),
      detached: !isWin,
      stdio: ['ignore', logFd, logFd]
    });
    child.once('error', err => {
      try { fs.appendFileSync(logPath, `\n[agent] Не удалось запустить процесс: ${err.message}\n`); } catch {}
    });
    child.unref();
    fs.closeSync(logFd);
    registry[name] = {
      name, pid: child.pid, command, cwd: cwdResult.path, logPath,
      startedAt: new Date().toISOString(), workspace: WORKSPACE_ROOT
    };
    writeProcessRegistry(registry);
    return { success: true, name, pid: child.pid, cwd: cwdResult.path, logPath, workspace: WORKSPACE_ROOT,
      message: 'Процесс запущен в фоне. Для реальных логов вызови process_logs.' };
  } catch (e) {
    try { fs.closeSync(logFd); } catch {}
    return { error: 'Не удалось запустить процесс: ' + e.message };
  }
}
function managedProcessStatus(name) {
  const registry = readProcessRegistry();
  const names = name ? [name] : Object.keys(registry);
  const processes = names.map(processName => {
    const p = registry[processName];
    if (!p) return { name: processName, found: false };
    const running = processIsAlive(p.pid);
    const status = { ...p, found: true, running };
    // Если сервер упал, причина обычно уже записана в его лог. Показываем хвост,
    // чтобы модель не гадала по порту и не путала проект с MCP-сервером.
    if (!running && p.logPath) {
      const tail = tailFile(p.logPath, 30);
      if (!tail.error) status.lastLog = tail.content;
    }
    return status;
  });
  return { workspace: WORKSPACE_ROOT, processes };
}
function stopManagedProcess(name, force = false) {
  const valid = safeProcessName(name);
  if (!valid) return { error: 'Укажи корректное имя процесса.' };
  const registry = readProcessRegistry();
  const item = registry[valid];
  if (!item) return { error: `Процесс '${valid}' не зарегистрирован.` };
  if (!processIsAlive(item.pid)) {
    delete registry[valid]; writeProcessRegistry(registry);
    return { success: true, name: valid, alreadyStopped: true, message: 'Процесс уже не запущен; запись очищена.' };
  }
  const signal = force ? 'SIGKILL' : 'SIGTERM';
  try {
    // detached-процесс — лидер отдельной группы; завершаем только эту группу, не все node-процессы.
    if (process.platform !== 'win32') process.kill(-Number(item.pid), signal);
    else process.kill(Number(item.pid), signal);
  } catch (e) {
    try { process.kill(Number(item.pid), signal); }
    catch (inner) { return { error: 'Не удалось остановить процесс: ' + inner.message }; }
  }
  item.stoppedAt = new Date().toISOString(); item.lastSignal = signal;
  registry[valid] = item; writeProcessRegistry(registry);
  return { success: true, name: valid, pid: item.pid, signal, logPath: item.logPath, message: 'Сигнал отправлен только управляемому процессу.' };
}
async function followManagedLog(item, args) {
  const seconds = Math.min(Math.max(Number(args.follow_seconds || args.followSeconds || 0), 0), 30);
  const first = tailFile(item.logPath, args.lines || 120);
  if (first.error || !seconds) return { path: item.logPath, ...first, following: false };
  let offset = 0;
  try { offset = fs.statSync(item.logPath).size; } catch {}
  const added = await new Promise(resolve => {
    let collected = '';
    const readNew = () => {
      try {
        const size = fs.statSync(item.logPath).size;
        if (size < offset) offset = 0; // лог был очищен / пересоздан
        if (size <= offset) return;
        const fd = fs.openSync(item.logPath, 'r');
        const buffer = Buffer.alloc(size - offset);
        fs.readSync(fd, buffer, 0, buffer.length, offset);
        fs.closeSync(fd);
        offset = size;
        const text = buffer.toString('utf8');
        collected += text;
        // В CLI новые строки появляются сразу, а не после окончания follow_seconds.
        if (args.__cliLive && text) printLiveCommandChunk('process-log', Buffer.from(text));
      } catch {}
    };
    const interval = setInterval(readNew, 250);
    setTimeout(() => {
      clearInterval(interval);
      readNew();
      resolve(collected);
    }, seconds * 1000);
  });
  return { path: item.logPath, content: (first.content || '') + (added ? `\n--- новые строки за ${seconds}s ---\n${added}` : ''), totalLines: first.totalLines, following: true, followSeconds: seconds };
}
function shellQuote(value) { return `'${String(value).replace(/'/g, `'\\''`)}'`; }
function safeNpmTokens(value) {
  const values = Array.isArray(value) ? value : String(value || '').split(/[\s,]+/);
  const tokens = values.map(x => String(x).trim()).filter(Boolean);
  if (!tokens.length) return { error: 'Не указаны npm-пакеты.' };
  if (tokens.some(x => /[;&|`$<>()\\]/.test(x))) return { error: 'Недопустимые символы в имени npm-пакета.' };
  return { tokens };
}
function inspectProject(root) {
  const resolved = mcpPathOrError(root || '.', 'path', true, true);
  if (resolved.error) return resolved;
  const project = resolved.path;
  const packagePath = path.join(project, 'package.json');
  let pkg = null, packageError = null;
  try { pkg = JSON.parse(fs.readFileSync(packagePath, 'utf8')); } catch (e) { packageError = e.message; }
  let entries = [];
  try { entries = fs.readdirSync(project, { withFileTypes: true }).map(e => ({ name: e.name, type: e.isDirectory() ? 'directory' : 'file' })); } catch {}
  const source = entries.filter(e => /\.(js|mjs|cjs|ts|tsx|jsx|html|css|json)$/i.test(e.name)).map(e => e.name).slice(0, 80);
  return {
    workspace: WORKSPACE_ROOT, path: project, packageJson: fs.existsSync(packagePath) ? packagePath : null,
    packageError, name: pkg?.name || null, version: pkg?.version || null,
    scripts: pkg?.scripts || {}, dependencies: Object.keys(pkg?.dependencies || {}), devDependencies: Object.keys(pkg?.devDependencies || {}),
    nodeModules: fs.existsSync(path.join(project, 'node_modules')), entries: entries.slice(0, 120), sourceFiles: source
  };
}
function backupFile(args) {
  const source = mcpPathOrError(args.path, 'path', true);
  if (source.error) return source;
  try {
    const dir = path.join(WORKSPACE_ROOT, '.zen-agent', 'backups');
    fs.mkdirSync(dir, { recursive: true });
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const backupPath = path.join(dir, `${path.basename(source.path)}.${stamp}.bak`);
    fs.copyFileSync(source.path, backupPath);
    return { success: true, path: source.path, backupPath, size: fs.statSync(backupPath).size };
  } catch (e) { return { error: 'Не удалось создать резервную копию: ' + e.message }; }
}
function simpleFileDiff(args) {
  const current = mcpPathOrError(args.path, 'path', true);
  if (current.error) return current;
  const previous = mcpPathOrError(args.backup || args.other_path, 'backup', true);
  if (previous.error) return previous;
  try {
    const before = fs.readFileSync(previous.path, 'utf8').split('\n');
    const after = fs.readFileSync(current.path, 'utf8').split('\n');
    let head = 0; while (head < before.length && head < after.length && before[head] === after[head]) head++;
    let tail = 0; while (tail < before.length - head && tail < after.length - head && before[before.length - 1 - tail] === after[after.length - 1 - tail]) tail++;
    if (head === before.length && head === after.length) return { equal: true, path: current.path, backup: previous.path, diff: 'Файлы идентичны.' };
    const removed = before.slice(head, before.length - tail).slice(0, 250);
    const added = after.slice(head, after.length - tail).slice(0, 250);
    const diff = [`--- ${previous.path}`, `+++ ${current.path}`, `@@ строка ${head + 1} @@`, ...removed.map(x => '- ' + x), ...added.map(x => '+ ' + x)].join('\n');
    return { equal: false, path: current.path, backup: previous.path, changedAtLine: head + 1, truncated: removed.length < before.length - head - tail || added.length < after.length - head - tail, diff };
  } catch (e) { return { error: 'Не удалось сравнить файлы: ' + e.message }; }
}
function termuxInfoTool() {
  let storage = { path: PLATFORM.isTermux ? TERMUX_SHARED_ROOT : null, accessible: false };
  try { if (storage.path) storage.accessible = fs.existsSync(storage.path) && fs.statSync(storage.path).isDirectory(); } catch {}
  let npmVersion = null;
  try { npmVersion = execFileSync('npm', ['--version'], { encoding: 'utf8', timeout: 5000 }).trim(); } catch {}
  return { platform: PLATFORM, workspace: WORKSPACE_ROOT, storage, node: process.version, npm: npmVersion, shell: process.env.SHELL || null, curl: curlPath(), proxy: proxyStatus() };
}
function networkCheckTool() {
  const args = ['-s', '--connect-timeout', '5', '--max-time', '10', '-o', '/dev/null', '-w', '%{http_code}', ...(CONFIG.proxy ? ['-x', CONFIG.proxy] : []), 'https://opencode.ai/zen/v1/models'];
  try {
    const code = execFileSync(curlPath(), args, { encoding: 'utf8', timeout: 12000 }).trim();
    return { reachable: /^2|^3|^4/.test(code), httpStatus: code, proxy: proxyStatus(), hint: 'Проверка выполнена через текущую системную сеть Android/VPN.' };
  } catch (e) { return { reachable: false, error: (e.stderr || e.message || '').toString().slice(0, 500), proxy: proxyStatus(), hint: 'Если Wi‑Fi блокирует сервер моделей, включи Android VPN и убедись, что Termux не исключён из VPN.' }; }
}
function normalizeHttpUrl(value) {
  let raw = String(value || '').trim();
  // Модели иногда передают Markdown-ссылку вместо URL: [label](http://127.0.0.1:3000/).
  const markdown = raw.match(/^\[[^\]]*\]\((https?:\/\/[^)\s]+)\)$/i);
  if (markdown) raw = markdown[1];
  return raw.replace(/^<|>$/g, '');
}
function httpRequestTool(args) {
  return new Promise(resolve => {
    let parsed;
    try { parsed = new URL(normalizeHttpUrl(args.url)); } catch { resolve({ error: 'Нужен корректный URL без Markdown. Пример: http://127.0.0.1:3000/' }); return; }
    if (!/^https?:$/.test(parsed.protocol)) { resolve({ error: 'http_request поддерживает только http:// и https://.' }); return; }
    const client = parsed.protocol === 'https:' ? https : http;
    const method = String(args.method || 'GET').toUpperCase();
    const body = args.body === undefined ? null : (typeof args.body === 'string' ? args.body : JSON.stringify(args.body));
    const headers = { ...(args.headers && typeof args.headers === 'object' ? args.headers : {}) };
    if (body && !headers['Content-Length']) headers['Content-Length'] = Buffer.byteLength(body);
    const started = Date.now(); let responseBody = ''; let completed = false;
    const finish = result => { if (!completed) { completed = true; resolve({ url: parsed.toString(), method, ms: Date.now() - started, ...result }); } };
    const req = client.request(parsed, { method, headers, timeout: Math.min(Math.max(Number(args.timeout || 8000), 1000), 30000) }, res => {
      res.setEncoding('utf8');
      res.on('data', chunk => { if (responseBody.length < 1024 * 1024) responseBody += chunk; });
      res.on('end', () => finish({ ok: res.statusCode >= 200 && res.statusCode < 400, status: res.statusCode, headers: res.headers, body: responseBody }));
    });
    req.on('timeout', () => { req.destroy(new Error('HTTP timeout')); });
    req.on('error', err => finish({ ok: false, error: err.message }));
    if (body) req.write(body);
    req.end();
  });
}
async function websocketTestTool(args) {
  const url = String(args.url || '').trim();
  if (!/^wss?:\/\//i.test(url)) return { error: 'Нужен ws:// или wss:// URL.' };
  const timeout = Math.min(Math.max(Number(args.timeout || 8000), 1000), 30000);
  const payload = args.payload === undefined ? 'ping' : (typeof args.payload === 'string' ? args.payload : JSON.stringify(args.payload));
  if (String(args.protocol || '').toLowerCase() === 'socket.io') {
    let io;
    try { io = require(require.resolve('socket.io-client', { paths: [WORKSPACE_ROOT] })); }
    catch { return { error: 'Для Socket.IO-теста установи socket.io-client через npm_install. Для обычного WebSocket protocol не указывай socket.io.' }; }
    return await new Promise(resolve => {
      const socket = io(url, { transports: ['websocket'], timeout }); let done = false;
      const finish = result => { if (!done) { done = true; try { socket.close(); } catch {} resolve(result); } };
      const timer = setTimeout(() => finish({ ok: false, error: 'Socket.IO timeout' }), timeout);
      socket.on('connect_error', err => { clearTimeout(timer); finish({ ok: false, error: err.message }); });
      socket.on('connect', () => {
        const event = String(args.event || 'message');
        socket.emit(event, args.payload === undefined ? 'ping' : args.payload);
        if (!args.expect_event) { clearTimeout(timer); finish({ ok: true, protocol: 'socket.io', connected: true, sentEvent: event }); }
      });
      if (args.expect_event) socket.on(String(args.expect_event), data => { clearTimeout(timer); finish({ ok: true, protocol: 'socket.io', receivedEvent: args.expect_event, data }); });
    });
  }
  const WS = globalThis.WebSocket;
  if (!WS) return { error: 'В этой версии Node нет WebSocket-клиента. Установи ws через npm_install или используй Socket.IO-клиент.' };
  return await new Promise(resolve => {
    let done = false; let socket;
    const finish = result => { if (!done) { done = true; clearTimeout(timer); try { socket?.close(); } catch {} resolve(result); } };
    const timer = setTimeout(() => finish({ ok: false, error: 'WebSocket timeout' }), timeout);
    try {
      socket = new WS(url);
      socket.addEventListener('open', () => { socket.send(payload); if (!args.wait_for_message) finish({ ok: true, protocol: 'websocket', sent: payload }); });
      socket.addEventListener('message', event => finish({ ok: true, protocol: 'websocket', received: String(event.data) }));
      socket.addEventListener('error', () => finish({ ok: false, error: 'WebSocket error' }));
    } catch (e) { finish({ ok: false, error: e.message }); }
  });
}


// ═══════════════════════════════════════════════════════════════════
//  FIRST-CLASS PROJECT WORKFLOW: filesystem, Git, QA, archives, Android
// ═══════════════════════════════════════════════════════════════════
function boundedInt(value, fallback, min, max) {
  const parsed = parseInt(value, 10);
  return Number.isFinite(parsed) ? Math.min(Math.max(parsed, min), max) : fallback;
}
function treeDirectory(args) {
  const base = mcpPathOrError(args.path || '.', 'path', true, true);
  if (base.error) return base;
  const maxDepth = boundedInt(args.max_depth || args.maxDepth, 3, 0, 8);
  const limit = boundedInt(args.limit, 300, 1, 2000);
  const entries = []; const skip = new Set(['node_modules', '.git', '.cache', '.zen-agent']);
  function walk(dir, depth) {
    if (entries.length >= limit || depth > maxDepth) return;
    let list = []; try { list = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const entry of list) {
      if (entries.length >= limit) return;
      const full = path.join(dir, entry.name);
      entries.push({ depth, name: entry.name, path: full, type: entry.isDirectory() ? 'directory' : 'file' });
      if (entry.isDirectory() && depth < maxDepth && !skip.has(entry.name)) walk(full, depth + 1);
    }
  }
  walk(base.path, 0);
  return { path: base.path, maxDepth, entries, truncated: entries.length >= limit };
}
function searchTextInFiles(args) {
  const base = mcpPathOrError(args.path || '.', 'path', true, true);
  if (base.error) return base;
  const query = String(args.query || args.text || '');
  if (!query) return { error: 'Для search_text нужен query.' };
  const caseSensitive = !!args.case_sensitive;
  const needle = caseSensitive ? query : query.toLowerCase();
  const maxFiles = boundedInt(args.max_files || args.maxFiles, 50, 1, 200);
  const maxMatches = boundedInt(args.max_matches || args.maxMatches, 200, 1, 1000);
  const maxDepth = boundedInt(args.max_depth || args.maxDepth, 4, 0, 8);
  const matches = []; let filesScanned = 0;
  const skip = new Set(['node_modules', '.git', '.cache', '.zen-agent']);
  function walk(dir, depth) {
    if (matches.length >= maxMatches || depth > maxDepth || filesScanned >= maxFiles) return;
    let list = []; try { list = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const entry of list) {
      if (matches.length >= maxMatches || filesScanned >= maxFiles) return;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) { if (!skip.has(entry.name)) walk(full, depth + 1); continue; }
      let stat; try { stat = fs.statSync(full); } catch { continue; }
      if (stat.size > 2 * 1024 * 1024) continue;
      let content; try { content = fs.readFileSync(full, 'utf8'); } catch { continue; }
      if (content.includes('\u0000')) continue;
      filesScanned++;
      const lines = content.split('\n');
      for (let i = 0; i < lines.length && matches.length < maxMatches; i++) {
        const subject = caseSensitive ? lines[i] : lines[i].toLowerCase();
        if (subject.includes(needle)) matches.push({ path: full, line: i + 1, text: lines[i].slice(0, 500) });
      }
    }
  }
  walk(base.path, 0);
  return { path: base.path, query, filesScanned, matches, truncated: matches.length >= maxMatches || filesScanned >= maxFiles };
}
function fileInfoTool(args) {
  const target = mcpPathOrError(args.path, 'path', true);
  if (target.error) return target;
  try {
    const stat = fs.statSync(target.path);
    const info = { path: target.path, type: stat.isDirectory() ? 'directory' : 'file', size: stat.size, modified: stat.mtime.toISOString(), created: stat.birthtime.toISOString() };
    if (stat.isFile() && stat.size <= 100 * 1024 * 1024 && args.hash !== false) {
      const hash = crypto.createHash(String(args.algorithm || 'sha256').toLowerCase());
      hash.update(fs.readFileSync(target.path)); info.hash = { algorithm: String(args.algorithm || 'sha256').toLowerCase(), value: hash.digest('hex') };
    } else if (stat.isFile() && stat.size > 100 * 1024 * 1024) info.hashNote = 'Хеш пропущен: файл больше 100 MiB.';
    return info;
  } catch (e) { return { error: 'Не удалось получить сведения: ' + e.message }; }
}
function mkdirTool(args) {
  const target = mcpPathOrError(args.path, 'path');
  if (target.error) return target;
  try { fs.mkdirSync(target.path, { recursive: args.recursive !== false }); return { success: true, path: target.path }; }
  catch (e) { return { error: 'Не удалось создать папку: ' + e.message }; }
}
function copyOrMoveTool(args, move = false) {
  const source = mcpPathOrError(args.source || args.from, 'source', true);
  if (source.error) return source;
  const target = mcpPathOrError(args.destination || args.to, 'destination');
  if (target.error) return target;
  if (source.path === target.path) return { error: 'Исходный и целевой путь совпадают.' };
  try {
    if (fs.statSync(source.path).isDirectory() && isPathInside(target.path, source.path)) {
      return { error: 'Нельзя копировать или перемещать папку внутрь неё самой.' };
    }
  } catch {}
  if (fs.existsSync(target.path) && !args.overwrite) return { error: 'Целевой путь уже существует. Передай overwrite:true, если уверен.' };
  try {
    fs.mkdirSync(path.dirname(target.path), { recursive: true });
    if (move) {
      try { fs.renameSync(source.path, target.path); }
      catch { fs.cpSync(source.path, target.path, { recursive: true, force: !!args.overwrite }); fs.rmSync(source.path, { recursive: true, force: true }); }
    } else fs.cpSync(source.path, target.path, { recursive: true, force: !!args.overwrite, errorOnExist: !args.overwrite });
    return { success: true, operation: move ? 'move' : 'copy', source: source.path, destination: target.path };
  } catch (e) { return { error: `Не удалось ${move ? 'переместить' : 'скопировать'}: ` + e.message }; }
}
function archiveCreateTool(args) {
  const source = mcpPathOrError(args.source || args.path || '.', 'source', true, true);
  if (source.error) return source;
  const destination = mcpPathOrError(args.destination || args.output, 'destination');
  if (destination.error) return destination;
  if (!/\.(tar\.gz|tgz)$/i.test(destination.path)) return { error: 'archive_create поддерживает .tar.gz или .tgz.' };
  if (fs.existsSync(destination.path) && !args.overwrite) return { error: 'Архив уже существует. Передай overwrite:true, если уверен.' };
  try {
    fs.mkdirSync(path.dirname(destination.path), { recursive: true });
    // Создаём архив вне исходной папки: иначе tar пытается читать файл, который сам же дописывает.
    const temporary = path.join(os.tmpdir(), `zen_archive_${Date.now()}_${Math.random().toString(36).slice(2)}.tar.gz`);
    try {
      execFileSync('tar', ['-czf', temporary, '-C', source.path, '.'], { timeout: safeCommandTimeout(args.timeout, 120000), stdio: ['ignore', 'pipe', 'pipe'] });
      try { fs.renameSync(temporary, destination.path); }
      catch { fs.copyFileSync(temporary, destination.path); fs.unlinkSync(temporary); }
    } finally { try { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); } catch {} }
    return { success: true, source: source.path, archive: destination.path, size: fs.statSync(destination.path).size };
  } catch (e) { return { error: 'Не удалось создать tar.gz: ' + (e.stderr || e.message || '').toString() }; }
}
function archiveExtractTool(args) {
  const archive = mcpPathOrError(args.archive || args.path, 'archive', true);
  if (archive.error) return archive;
  const destination = mcpPathOrError(args.destination || args.output || '.', 'destination');
  if (destination.error) return destination;
  if (!/\.(tar\.gz|tgz)$/i.test(archive.path)) return { error: 'archive_extract поддерживает .tar.gz или .tgz.' };
  try {
    fs.mkdirSync(destination.path, { recursive: true });
    execFileSync('tar', ['-xzf', archive.path, '-C', destination.path], { timeout: safeCommandTimeout(args.timeout, 120000), stdio: ['ignore', 'pipe', 'pipe'] });
    return { success: true, archive: archive.path, destination: destination.path };
  } catch (e) { return { error: 'Не удалось распаковать архив: ' + (e.stderr || e.message || '').toString() }; }
}
function gitCwd(args) { return mcpPathOrError(args.cwd || '.', 'cwd', true, true); }
async function gitLiveTool(command, cwdResult, args) {
  const opts = { cwd: cwdResult.path, timeout: safeCommandTimeout(args.timeout, 30000), env: commandEnvironment() };
  if (args.__cliLive && CONFIG.liveToolLogs) return await runCommandWithLiveLogs(command, cwdResult.path, opts);
  try { return { success: true, cwd: cwdResult.path, stdout: execSync(command, { ...opts, encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 }) }; }
  catch (e) { return { error: (e.stderr || e.message || '').toString() }; }
}
function safeGitRef(ref) { return /^[a-zA-Z0-9._/-]{1,120}$/.test(String(ref || '')) ? String(ref) : null; }
function codeCheckCommand(args) {
  const file = mcpPathOrError(args.path, 'path', true);
  if (file.error) return file;
  const extension = path.extname(file.path).toLowerCase();
  if (!['.js', '.mjs', '.cjs'].includes(extension)) return { error: 'code_check пока поддерживает JS-файлы: .js, .mjs, .cjs.' };
  return { file, command: `${shellQuote(process.execPath)} --check ${shellQuote(file.path)}` };
}
function decodeHtml(value) { return String(value || '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#x27;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>'); }
function stripHtml(value) { return decodeHtml(String(value || '').replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim()); }
function webSearchTool(args) {
  const query = String(args.query || '').trim();
  if (!query) return { error: 'Для web_search нужен query.' };
  const limit = boundedInt(args.limit, 5, 1, 10);

  // The old implementation scraped html.duckduckgo.com with a regex for
  // class="result__a". Measured today: DuckDuckGo answers 202 with a bot
  // interstitial and that class no longer appears, so the tool returned an
  // empty list every single time - the "browser doesn't work" complaint.
  // lite.duckduckgo.com behaves the same way, and mojeek returns 403.
  //
  // Wikipedia's OpenSearch API needs no key, is not rate-limited in practice
  // and returns structured JSON, so it cannot silently rot into an empty list
  // the way a scraper does. It is narrower than a web search, which is stated
  // in the result rather than hidden.
  const errors = [];

  for (const lang of ['ru', 'en']) {
    try {
      const url = `https://${lang}.wikipedia.org/w/api.php?action=opensearch&search=`
        + encodeURIComponent(query) + `&limit=${limit}&namespace=0&format=json`;
      const raw = execFileSync(curlPath(), ['-s', '-L', '--connect-timeout', '8',
        '--max-time', '20', ...(CONFIG.proxy ? ['-x', CONFIG.proxy] : []), url],
        { encoding: 'utf8', timeout: 25000 });
      const parsed = JSON.parse(raw);
      const titles = parsed[1] || [], descs = parsed[2] || [], links = parsed[3] || [];
      if (titles.length) {
        return {
          query,
          results: titles.map((t, i) => ({
            title: t,
            url: links[i] || '',
            snippet: descs[i] || ''
          })).slice(0, limit),
          provider: `Wikipedia (${lang})`,
          note: 'Энциклопедический поиск. Для конкретной страницы используй web_fetch с её URL.'
        };
      }
    } catch (e) {
      errors.push(`${lang}: ` + (e.message || '').toString().slice(0, 120));
    }
  }

  return {
    query,
    results: [],
    provider: 'Wikipedia',
    note: 'Ничего не найдено. Если нужен произвольный сайт — web_fetch по прямому URL.',
    errors: errors.length ? errors : undefined
  };
}

/**
 * Fetches a page as readable Markdown.
 *
 * This is what the agent actually lacked: a way to *look at* a page rather
 * than guess from a search snippet. r.jina.ai renders the target server-side
 * and returns Markdown, so JavaScript-heavy pages work and no HTML parsing is
 * needed here. Verified against kotlinlang.org: HTTP 200, 17 KB of Markdown.
 * Falls back to fetching the URL directly and stripping tags when the reader
 * is unavailable, so the tool degrades instead of failing.
 */
function webFetchTool(args) {
  let url = String(args.url || '').trim();
  if (!url) return { error: 'Для web_fetch нужен url.' };
  if (!/^https?:\/\//i.test(url)) url = 'https://' + url;
  const maxChars = boundedInt(args.max_chars, 12000, 500, 60000);

  const curlBase = ['-s', '-L', '--connect-timeout', '10', '--max-time', '45',
    ...(CONFIG.proxy ? ['-x', CONFIG.proxy] : []),
    '-A', 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120'];

  try {
    const md = execFileSync(curlPath(),
      [...curlBase, 'https://r.jina.ai/' + url], { encoding: 'utf8', timeout: 50000 });
    // Guard against two failure modes seen in testing: a JSON error object,
    // and an HTML holding page served instead of the rendered Markdown. Both
    // are "successful" HTTP responses, so only the body distinguishes them.
    const looksHtml = /^\s*<(!doctype|html|head|meta)/i.test(md);
    const looksJsonError = /^\s*\{\s*"(data|code|error)"/.test(md);
    if (md && md.length > 200 && !looksHtml && !looksJsonError) {
      return {
        url,
        provider: 'r.jina.ai (Markdown)',
        truncated: md.length > maxChars,
        content: md.slice(0, maxChars)
      };
    }
  } catch (e) { /* fall through to the direct fetch */ }

  try {
    const html = execFileSync(curlPath(), [...curlBase, url],
      { encoding: 'utf8', timeout: 50000 });
    const text = stripHtml(
      html.replace(/<script[\s\S]*?<\/script>/gi, ' ')
          .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    );
    if (!text) return { error: 'Страница пустая или недоступна: ' + url };
    return {
      url,
      provider: 'direct (HTML stripped)',
      truncated: text.length > maxChars,
      content: text.slice(0, maxChars)
    };
  } catch (e) {
    return { error: 'Не удалось получить страницу: ' + (e.message || '').toString().slice(0, 300) };
  }
}

function runTermuxApi(command, values = []) {
  if (!PLATFORM.isTermux) return { error: 'Этот инструмент доступен только в Termux/Android.' };
  try { return { success: true, output: execFileSync(command, values, { encoding: 'utf8', timeout: 15000 }).trim() }; }
  catch (e) { return { error: `Не удалось выполнить ${command}. Установи Termux:API и соответствующее Android-приложение. ` + (e.stderr || e.message || '').toString().slice(0, 300) }; }
}
function termuxApiStatus() {
  if (!PLATFORM.isTermux) return { available: false, platform: PLATFORM.name, error: 'Termux:API доступен только когда Core запущен внутри Termux.' };
  const bin = process.env.PREFIX ? path.join(process.env.PREFIX, 'bin') : '';
  const commands = ['termux-battery-status','termux-wifi-connectioninfo','termux-clipboard-get','termux-clipboard-set','termux-notification','termux-toast','termux-vibrate','termux-share','termux-volume','termux-location'];
  const available = commands.filter(name => bin && fs.existsSync(path.join(bin, name)));
  return { available: available.length > 0, platform: PLATFORM.name, prefix: process.env.PREFIX || null, commands: available, missing: commands.filter(name => !available.includes(name)), installHint: available.length ? null : 'Установи пакет Termux:API в Termux и Android-приложение Termux:API.' };
}



// ═══════════════════════════════════════════════════════════════════
//  VISION / OCR / POLLINATIONS
//  Local tools work with every text model; visual reasoning is routed
//  only to a vision-capable OpenRouter model.
// ═══════════════════════════════════════════════════════════════════
function detectImageMime(buffer, filePath = '') {
  if (buffer.length >= 8 && buffer.subarray(0, 8).equals(Buffer.from([0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a]))) return 'image/png';
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) return 'image/jpeg';
  if (buffer.length >= 12 && buffer.subarray(0, 4).toString() === 'RIFF' && buffer.subarray(8, 12).toString() === 'WEBP') return 'image/webp';
  if (/\.gif$/i.test(filePath)) return 'image/gif';
  return null;
}
function imageDimensions(buffer, mime) {
  try {
    if (mime === 'image/png' && buffer.length >= 24) return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
    if (mime === 'image/jpeg') {
      let i = 2;
      while (i + 9 < buffer.length) {
        if (buffer[i] !== 0xff) { i++; continue; }
        const marker = buffer[i + 1]; const length = buffer.readUInt16BE(i + 2);
        if ([0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf].includes(marker)) return { width: buffer.readUInt16BE(i + 7), height: buffer.readUInt16BE(i + 5) };
        i += 2 + length;
      }
    }
    if (mime === 'image/webp' && buffer.length >= 30 && buffer.subarray(12,16).toString() === 'VP8X') return { width: 1 + buffer.readUIntLE(24,3), height: 1 + buffer.readUIntLE(27,3) };
  } catch {}
  return { width: null, height: null };
}
function resolveImageFile(rawPath) {
  const image = mcpPathOrError(rawPath, 'path', true);
  if (image.error) return image;
  try {
    const stat = fs.statSync(image.path);
    if (!stat.isFile()) return { error: 'Это не файл изображения: ' + image.path };
    if (stat.size > 12 * 1024 * 1024) return { error: 'Изображение больше 12 MiB. Сожми его перед vision-анализом.' };
    const buffer = fs.readFileSync(image.path); const mime = detectImageMime(buffer, image.path);
    if (!mime) return { error: 'Поддерживаются PNG, JPEG, WebP и GIF.' };
    return { path: image.path, buffer, mime, stat, dimensions: imageDimensions(buffer, mime) };
  } catch (e) { return { error: 'Не удалось прочитать изображение: ' + e.message }; }
}
function imageInfoTool(args) {
  const image = resolveImageFile(args.path); if (image.error) return image;
  return { path: image.path, mime: image.mime, size: image.stat.size, modified: image.stat.mtime.toISOString(), dimensions: image.dimensions, sha256: crypto.createHash('sha256').update(image.buffer).digest('hex') };
}
function ocrImageTool(args) {
  const image = resolveImageFile(args.path); if (image.error) return image;
  const language = String(args.language || 'eng+rus').replace(/[^a-zA-Z+_]/g, '') || 'eng';
  const psm = Math.min(Math.max(Number(args.psm || 6), 3), 13);
  try {
    const text = execFileSync('tesseract', [image.path, 'stdout', '-l', language, '--psm', String(psm)], { encoding: 'utf8', timeout: 90000, maxBuffer: 5 * 1024 * 1024 });
    return { path: image.path, mime: image.mime, dimensions: image.dimensions, language, text };
  } catch (e) { return { error: 'OCR недоступен. Установи локально: pkg install tesseract tesseract-data-rus. ' + (e.stderr || e.message || '').toString().slice(0, 300) }; }
}
async function openRouterVision(images, prompt, model) {
  const key = openRouterKey();
  if (!key) return { error: 'Для visual analysis нужен OpenRouter key. Добавь его командой /key.' };
  const selectedModel = model || CONFIG.visionModel;
  const content = [{ type: 'text', text: String(prompt || 'Опиши изображение подробно и только по видимым фактам.') }];
  for (const image of images) content.push({ type: 'image_url', image_url: { url: `data:${image.mime};base64,${image.buffer.toString('base64')}` } });
  try {
    const json = await openRouterRequest({ model: selectedModel, messages: [{ role: 'user', content }], max_tokens: 3000, temperature: 0.2, stream: false });
    const message = json.choices?.[0]?.message || {}; const analysis = Array.isArray(message.content) ? message.content.map(x => x.text || '').join('') : (message.content || '');
    return { model: json.model || selectedModel, analysis, usage: json.usage || {}, images: images.map(i => ({ path: i.path, mime: i.mime, dimensions: i.dimensions })) };
  } catch (e) { return { error: 'Vision model не ответила: ' + (e.message || e) + '. Выбери vision-модель: /vision MODEL_ID' }; }
}
async function visionAnalyzeTool(args) {
  const image = resolveImageFile(args.path); if (image.error) return image;
  return await openRouterVision([image], args.prompt || args.question || 'Опиши скриншот: текст, элементы интерфейса, ошибки и важные детали.', args.model);
}
async function visionUiAuditTool(args) {
  const prompt = `Проведи UI-аудит скриншота. Найди: переполнение/перенос текста, неработающие подсказки, проблемы контраста, доступность, мобильную компоновку, визуальные ошибки. Дай список с приоритетами. ${args.prompt || ''}`;
  return await visionAnalyzeTool({ ...args, prompt });
}
async function visionCompareTool(args) {
  const first = resolveImageFile(args.path || args.first); if (first.error) return first;
  const second = resolveImageFile(args.path2 || args.second); if (second.error) return second;
  const prompt = args.prompt || 'Сравни два изображения. Назови видимые изменения, регрессии интерфейса и совпадающие элементы.';
  return await openRouterVision([first, second], prompt, args.model);
}
// Pollinations image generation removed.
//
// The free tier now answers 402 Payment Required on both the GET and the
// OpenAI-compatible endpoints, so pollinations_generate could only ever fail
// unless a paid key was supplied. A tool that cannot work without money does
// not belong in a free-models agent - it just produces confusing errors.

// ═══════════════════════════════════════════════════════════════════
//  LOCAL SELF-EXTENDING CUSTOM TOOLS
//  Each plugin lives under .zen-agent/custom-tools in the active workspace.
// ═══════════════════════════════════════════════════════════════════
function customToolDirectory() {
  const dir = path.join(WORKSPACE_ROOT, '.zen-agent', 'custom-tools');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}
function customToolRegistryPath() { return path.join(customToolDirectory(), 'registry.json'); }
function safeCustomToolName(name) {
  const value = String(name || '').trim();
  return /^[a-z][a-z0-9_]{2,48}$/i.test(value) ? value : null;
}
function readCustomToolRegistry() {
  try {
    const parsed = JSON.parse(fs.readFileSync(customToolRegistryPath(), 'utf8'));
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch { return {}; }
}
function writeCustomToolRegistry(registry) {
  fs.writeFileSync(customToolRegistryPath(), JSON.stringify(registry, null, 2), 'utf8');
}
function customToolListTool() {
  const registry = readCustomToolRegistry();
  return { directory: customToolDirectory(), tools: Object.values(registry) };
}
function customToolCreateTool(args) {
  const name = safeCustomToolName(args.name);
  if (!name) return { error: 'Имя custom tool: 3–48 символов, латиница/цифры/_, начинается с буквы.' };
  const description = String(args.description || '').trim();
  const code = String(args.code || '');
  if (!description || !code.trim()) return { error: 'Для custom_tool_create нужны description и code.' };
  if (code.length > 60000) return { error: 'Код custom tool больше 60 000 символов.' };
  // Plugin gets only api; direct process/require escapes are rejected before storage.
  if (/\brequire\s*\(|\bprocess\b|child_process|\bimport\s*\(|\beval\s*\(|\bFunction\s*\(/.test(code)) {
    return { error: 'Custom tool не может использовать require/process/import/eval. Используй переданный api.readText/api.writeText/api.list/api.httpGet.' };
  }
  const registry = readCustomToolRegistry();
  if (registry[name] && !args.overwrite) return { error: `Custom tool '${name}' уже существует. Передай overwrite:true для замены.` };
  const file = path.join(customToolDirectory(), `${name}.js`);
  try {
    fs.writeFileSync(file, code, 'utf8');
    registry[name] = {
      name,
      description,
      file,
      parameters: args.parameters && typeof args.parameters === 'object' ? args.parameters : {},
      createdAt: registry[name]?.createdAt || new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };
    writeCustomToolRegistry(registry);
    return { success: true, name, file, directory: customToolDirectory(), message: 'Custom tool создан и подключён. Запускай через custom_tool_run.' };
  } catch (e) { return { error: 'Не удалось создать custom tool: ' + e.message }; }
}
function customToolInspectTool(args) {
  const name = safeCustomToolName(args.name); if (!name) return { error: 'Укажи name.' };
  const item = readCustomToolRegistry()[name]; if (!item) return { error: `Custom tool '${name}' не найден.` };
  try { return { ...item, code: fs.readFileSync(item.file, 'utf8') }; }
  catch (e) { return { error: 'Не удалось прочитать plugin: ' + e.message }; }
}
function customToolDeleteTool(args) {
  const name = safeCustomToolName(args.name); if (!name) return { error: 'Укажи name.' };
  const registry = readCustomToolRegistry(); const item = registry[name];
  if (!item) return { error: `Custom tool '${name}' не найден.` };
  try { fs.unlinkSync(item.file); } catch {}
  delete registry[name]; writeCustomToolRegistry(registry);
  return { success: true, name, directory: customToolDirectory() };
}
function customToolApi() {
  return Object.freeze({
    workspace: WORKSPACE_ROOT,
    async readText(relativePath) {
      const target = mcpPathOrError(relativePath, 'path', true);
      if (target.error) throw new Error(target.error);
      const stat = fs.statSync(target.path); if (stat.size > 1024 * 1024) throw new Error('readText limit: 1 MiB');
      return fs.readFileSync(target.path, 'utf8');
    },
    async writeText(relativePath, text) {
      const target = mcpPathOrError(relativePath, 'path');
      if (target.error) throw new Error(target.error);
      fs.mkdirSync(path.dirname(target.path), { recursive: true });
      fs.writeFileSync(target.path, String(text), 'utf8');
      return { path: target.path, bytes: Buffer.byteLength(String(text), 'utf8') };
    },
    async list(relativePath = '.') {
      const target = mcpPathOrError(relativePath, 'path', true, true);
      if (target.error) throw new Error(target.error);
      return fs.readdirSync(target.path, { withFileTypes: true }).map(x => ({ name: x.name, type: x.isDirectory() ? 'directory' : 'file' }));
    },
    async httpGet(url) {
      const result = await httpRequestTool({ url, method: 'GET', timeout: 15000 });
      if (result.error) throw new Error(result.error);
      return result;
    },
    async imageInfo(relativePath) {
      const result = imageInfoTool({ path: relativePath });
      if (result.error) throw new Error(result.error);
      return result;
    }
  });
}
async function customToolRunTool(args) {
  const name = safeCustomToolName(args.name); if (!name) return { error: 'Укажи name.' };
  const item = readCustomToolRegistry()[name]; if (!item) return { error: `Custom tool '${name}' не найден. Сначала custom_tool_list или custom_tool_create.` };
  let source;
  try { source = fs.readFileSync(item.file, 'utf8'); } catch (e) { return { error: 'Не удалось загрузить custom tool: ' + e.message }; }
  const logs = [];
  const sandbox = {
    module: { exports: {} }, exports: {},
    console: Object.freeze({ log: (...parts) => logs.push(parts.map(String).join(' ')) }),
    JSON, Math, Date, Array, Object, String, Number, Boolean, RegExp, Promise,
    setTimeout, clearTimeout
  };
  try {
    vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
    new vm.Script(`"use strict";\n${source}`, { filename: item.file }).runInContext(sandbox, { timeout: 1000 });
    const plugin = sandbox.module.exports?.default || sandbox.module.exports;
    if (typeof plugin !== 'function') return { error: 'Custom tool должен экспортировать async function(args, api): module.exports = async (args, api) => ({...});' };
    let timeoutId = null;
    const timeout = new Promise((_, reject) => { timeoutId = setTimeout(() => reject(new Error('Custom tool timeout (15s)')), 15000); timeoutId.unref?.(); });
    const result = await Promise.race([Promise.resolve(plugin(args.tool_args || args.args || {}, customToolApi())), timeout]);
    if (timeoutId) clearTimeout(timeoutId);
    return { success: true, name, result, logs, file: item.file };
  } catch (e) { return { error: `Custom tool '${name}' failed: ${e.message || e}`, logs, file: item.file }; }
}



// ═══════════════════════════════════════════════════════════════════
//  LIFECYCLE PLUGINS — OpenCode-inspired hooks in a single-file agent
// ═══════════════════════════════════════════════════════════════════
function pluginDirectory() { const dir = path.join(WORKSPACE_ROOT, '.zen-agent', 'plugins'); fs.mkdirSync(dir, { recursive: true }); return dir; }
function pluginRegistryPath() { return path.join(pluginDirectory(), 'registry.json'); }
function safePluginName(name) { const value = String(name || '').trim(); return /^[a-z][a-z0-9_-]{2,48}$/i.test(value) ? value : null; }
function readPluginRegistry() { try { const x = JSON.parse(fs.readFileSync(pluginRegistryPath(), 'utf8')); return x && typeof x === 'object' && !Array.isArray(x) ? x : {}; } catch { return {}; } }
function writePluginRegistry(registry) { fs.writeFileSync(pluginRegistryPath(), JSON.stringify(registry, null, 2), 'utf8'); }
function pluginListTool() { const registry = readPluginRegistry(); return { directory: pluginDirectory(), plugins: Object.values(registry) }; }
function pluginCreateTool(args) {
  const name = safePluginName(args.name); if (!name) return { error: 'Имя plugin: 3–48 символов, латиница/цифры/_/-, начинается с буквы.' };
  const description = String(args.description || '').trim(); const code = String(args.code || '');
  if (!description || !code.trim()) return { error: 'Для plugin_create нужны description и code.' };
  if (code.length > 80000) return { error: 'Код plugin больше 80 000 символов.' };
  if (/\brequire\s*\(|\bprocess\b|child_process|\bimport\s*\(|\beval\s*\(|\bFunction\s*\(/.test(code)) return { error: 'Plugin не может использовать require/process/import/eval. Используй api из plugin context.' };
  const registry = readPluginRegistry(); if (registry[name] && !args.overwrite) return { error: `Plugin '${name}' уже существует. Передай overwrite:true для замены.` };
  const file = path.join(pluginDirectory(), `${name}.js`);
  try {
    fs.writeFileSync(file, code, 'utf8');
    registry[name] = { name, description, file, enabled: args.enabled !== false, createdAt: registry[name]?.createdAt || new Date().toISOString(), updatedAt: new Date().toISOString() };
    writePluginRegistry(registry); return { success: true, plugin: registry[name], directory: pluginDirectory(), message: 'Plugin создан и автоматически подключается в следующем lifecycle event.' };
  } catch (e) { return { error: 'Не удалось создать plugin: ' + e.message }; }
}
function pluginInspectTool(args) { const name = safePluginName(args.name); if (!name) return { error: 'Укажи name.' }; const item = readPluginRegistry()[name]; if (!item) return { error: `Plugin '${name}' не найден.` }; try { return { ...item, code: fs.readFileSync(item.file, 'utf8') }; } catch (e) { return { error: e.message }; } }
function pluginDeleteTool(args) { const name = safePluginName(args.name); if (!name) return { error: 'Укажи name.' }; const registry = readPluginRegistry(); const item = registry[name]; if (!item) return { error: `Plugin '${name}' не найден.` }; try { fs.unlinkSync(item.file); } catch {} delete registry[name]; writePluginRegistry(registry); return { success: true, name }; }
function pluginApi() {
  const api = customToolApi();
  return Object.freeze({ ...api, emit: (event, data = {}) => auditEvent(`plugin_event:${event}`, { data }) });
}
function loadPlugins() {
  const registry = readPluginRegistry(); const loaded = [];
  for (const item of Object.values(registry)) {
    if (!item.enabled) continue;
    try {
      const source = fs.readFileSync(item.file, 'utf8');
      const sandbox = { module: { exports: {} }, exports: {}, console: Object.freeze({ log: () => {} }), JSON, Math, Date, Array, Object, String, Number, Boolean, RegExp, Promise };
      vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
      new vm.Script(`"use strict";\n${source}`, { filename: item.file }).runInContext(sandbox, { timeout: 1000 });
      const factory = sandbox.module.exports?.default || sandbox.module.exports;
      if (typeof factory !== 'function') throw new Error('Plugin must export function(context) => hooks');
      const descriptor = factory(Object.freeze({ name: item.name, workspace: WORKSPACE_ROOT, api: pluginApi() }));
      if (descriptor && typeof descriptor.then === 'function') throw new Error('Plugin factory must be synchronous; hooks may be async.');
      if (!descriptor || typeof descriptor !== 'object') throw new Error('Plugin factory must return object.');
      loaded.push({ item, descriptor });
    } catch (e) { auditEvent('plugin_load_error', { plugin: item.name, error: String(e.message || e) }); }
  }
  return loaded;
}
async function pluginHook(hook, payload) {
  let current = payload;
  for (const { item, descriptor } of loadPlugins()) {
    const fn = descriptor[hook] || descriptor.hooks?.[hook];
    if (typeof fn !== 'function') continue;
    try {
      const timeout = new Promise((_, reject) => { const id = setTimeout(() => reject(new Error('plugin hook timeout')), 5000); id.unref?.(); });
      const result = await Promise.race([Promise.resolve(fn(current, pluginApi())), timeout]);
      if (result && typeof result === 'object') current = { ...current, ...result };
      auditEvent('plugin_hook', { plugin: item.name, hook });
    } catch (e) { auditEvent('plugin_hook_error', { plugin: item.name, hook, error: String(e.message || e) }); }
  }
  return current;
}
function pluginSystemPrompts() {
  return loadPlugins().map(({ descriptor }) => descriptor.systemPrompt).filter(value => typeof value === 'string' && value.trim()).join('\n\n');
}
async function pluginPermission(call) {
  let decision = null;
  for (const { item, descriptor } of loadPlugins()) {
    const fn = descriptor.permission || descriptor.hooks?.permission;
    if (typeof fn !== 'function') continue;
    try {
      const result = await Promise.resolve(fn(call, pluginApi()));
      if (['allow', 'ask', 'deny'].includes(result)) { decision = result; auditEvent('plugin_permission', { plugin: item.name, tool: call.name, decision }); }
    } catch (e) { auditEvent('plugin_permission_error', { plugin: item.name, error: String(e.message || e) }); }
  }
  return decision;
}
function pluginToolListTool() {
  const tools = [];
  for (const { item, descriptor } of loadPlugins()) {
    for (const [name, tool] of Object.entries(descriptor.tools || {})) if (tool && typeof tool.run === 'function') tools.push({ plugin: item.name, name, description: tool.description || '' });
  }
  return { directory: pluginDirectory(), tools };
}
async function pluginToolRunTool(args) {
  const pluginName = safePluginName(args.plugin); const toolName = safeCustomToolName(args.name || args.tool);
  if (!pluginName || !toolName) return { error: 'Нужны plugin и name/tool.' };
  const plugin = loadPlugins().find(x => x.item.name === pluginName);
  if (!plugin) return { error: `Plugin '${pluginName}' не найден/выключен.` };
  const tool = plugin.descriptor.tools?.[toolName]; if (!tool || typeof tool.run !== 'function') return { error: `Tool '${toolName}' в plugin '${pluginName}' не найден.` };
  try {
    const timeout = new Promise((_, reject) => { const id = setTimeout(() => reject(new Error('plugin tool timeout (15s)')), 15000); id.unref?.(); });
    const result = await Promise.race([Promise.resolve(tool.run(args.tool_args || args.args || {}, pluginApi())), timeout]);
    return { success: true, plugin: pluginName, tool: toolName, result };
  } catch (e) { return { error: `Plugin tool failed: ${e.message || e}` }; }
}
function pluginProviderListTool() {
  const providers = [];
  for (const { item, descriptor } of loadPlugins()) {
    for (const [id, provider] of Object.entries(descriptor.providers || {})) if (provider && typeof provider === 'object') providers.push({ plugin: item.name, id, description: provider.description || '', endpoint: provider.endpoint || '', apiKeyEnv: provider.apiKeyEnv || '' });
  }
  return { providers };
}
function findPluginProvider(id) {
  return pluginProviderListTool().providers.find(provider => provider.id === id) || null;
}

async function callPluginProvider(messages, model, provider) {
  const endpoint = String(provider.endpoint || '').trim();
  const apiKeyEnv = String(provider.apiKeyEnv || '').trim();
  if (!endpoint.startsWith('https://')) throw new Error(`Plugin provider '${provider.id}' requires HTTPS endpoint.`);
  if (!apiKeyEnv || !process.env[apiKeyEnv]) throw new Error(`Plugin provider '${provider.id}' needs environment variable ${apiKeyEnv}.`);
  const url = new URL(endpoint);
  const payload = JSON.stringify({ model: model || provider.defaultModel, messages, tools: buildNativeToolDefinitions(), tool_choice: 'auto', max_tokens: CONFIG.maxTokens, temperature: CONFIG.temperature, stream: false });
  return await new Promise((resolve, reject) => {
    const req = https.request({ hostname: url.hostname, port: url.port || 443, path: url.pathname + url.search, method: 'POST', timeout: 90000, headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload), 'Authorization': `Bearer ${process.env[apiKeyEnv]}` } }, res => {
      let body = ''; res.setEncoding('utf8'); res.on('data', chunk => body += chunk); res.on('end', () => {
        let json; try { json = JSON.parse(body); } catch { reject(new Error(`Plugin provider returned non-JSON: ${body.slice(0, 300)}`)); return; }
        if (res.statusCode < 200 || res.statusCode >= 300) { reject(new Error(`Plugin provider HTTP ${res.statusCode}: ${json.error?.message || body.slice(0, 300)}`)); return; }
        const msg = json.choices?.[0]?.message || {}; const text = Array.isArray(msg.content) ? msg.content.map(x => x.text || '').join('') : (msg.content || '');
        resolve({ text, toolCalls: msg.tool_calls || [], model: json.model || model || provider.defaultModel, usage: json.usage || {}, outputShown: false, provider: provider.id });
      });
    });
    req.on('error', reject); req.on('timeout', () => req.destroy(new Error('Plugin provider timeout'))); req.write(payload); req.end();
  });
}

// ═══════════════════════════════════════════════════════════════════
//  SUBAGENTS — isolated role prompts and separate short-lived contexts
// ═══════════════════════════════════════════════════════════════════
const BUILTIN_SUBAGENTS = {
  explore: { description: 'Read-only исследователь: анализирует структуру, логи и риски, не предлагает изменений как выполненные.', mode: 'explore' },
  general: { description: 'Независимый аналитик: даёт второе мнение, проверяет план и крайние случаи.', mode: 'plan' },
  reviewer: { description: 'Ревьюер: ищет ошибки, риски безопасности и недостающие проверки.', mode: 'plan' }
};
function subagentDirectory() { const dir = path.join(WORKSPACE_ROOT, '.zen-agent', 'subagents'); fs.mkdirSync(dir, { recursive: true }); return dir; }
function subagentRegistryPath() { return path.join(subagentDirectory(), 'registry.json'); }
function safeSubagentName(name) { const value = String(name || '').trim(); return /^[a-z][a-z0-9_-]{2,48}$/i.test(value) ? value : null; }
function readSubagentRegistry() { try { const x = JSON.parse(fs.readFileSync(subagentRegistryPath(), 'utf8')); return x && typeof x === 'object' && !Array.isArray(x) ? x : {}; } catch { return {}; } }
function writeSubagentRegistry(registry) { fs.writeFileSync(subagentRegistryPath(), JSON.stringify(registry, null, 2), 'utf8'); }
function subagentListTool() { const custom = readSubagentRegistry(); return { directory: subagentDirectory(), builtins: BUILTIN_SUBAGENTS, custom: Object.values(custom) }; }
function subagentCreateTool(args) {
  const name = safeSubagentName(args.name); if (!name) return { error: 'Имя subagent: 3–48 символов, латиница/цифры/_/-, начинается с буквы.' };
  const prompt = String(args.prompt || '').trim(); const description = String(args.description || '').trim();
  if (!prompt || !description) return { error: 'Для subagent_create нужны description и prompt.' };
  const mode = ['build', 'plan', 'explore'].includes(args.mode) ? args.mode : 'plan';
  const registry = readSubagentRegistry();
  if (registry[name] && !args.overwrite) return { error: `Subagent '${name}' уже существует. Передай overwrite:true для замены.` };
  registry[name] = { name, description, prompt, mode, model: args.model || null, createdAt: registry[name]?.createdAt || new Date().toISOString(), updatedAt: new Date().toISOString() };
  writeSubagentRegistry(registry); return { success: true, agent: registry[name], directory: subagentDirectory() };
}
function subagentDeleteTool(args) {
  const name = safeSubagentName(args.name); if (!name) return { error: 'Укажи name.' };
  const registry = readSubagentRegistry(); if (!registry[name]) return { error: `Custom subagent '${name}' не найден.` };
  delete registry[name]; writeSubagentRegistry(registry); return { success: true, name };
}
function resolveSubagent(name) { return BUILTIN_SUBAGENTS[name] ? { name, ...BUILTIN_SUBAGENTS[name], builtin: true } : readSubagentRegistry()[name] || null; }
async function subagentTaskTool(args) {
  const name = String(args.agent || args.name || 'explore'); const agent = resolveSubagent(name);
  if (!agent) return { error: `Subagent '${name}' не найден. Используй subagent_list.` };
  const task = String(args.prompt || args.task || '').trim(); if (!task) return { error: 'Для subagent_task нужен prompt или task.' };
  const model = args.model || agent.model || currentModel;
  const system = [
    'Ты — изолированный subagent. Не выполняй изменения и не утверждай, что что-то изменил.',
    `Роль: ${agent.description}`,
    `Режим: ${agent.mode}.`,
    agent.prompt || '',
    `Рабочая папка: ${WORKSPACE_ROOT}.`,
    'Дай краткий проверяемый отчёт: факты, неопределённости, следующий безопасный шаг.'
  ].join('\n');
  const messages = [{ role: 'system', content: system }, { role: 'user', content: task }];
  try {
    // Subagent intentionally has no tool loop: it is an isolated second opinion.
    const result = currentProvider === 'openrouter' ? await callOpenRouter(messages, model) : await callZenDirect(messages, model, false);
    return { success: true, agent: name, model: result.model || model, output: result.text || '', usage: result.usage || {}, mode: agent.mode };
  } catch (e) { return { error: `Subagent '${name}' failed: ${e.message || e}` }; }
}

// ═══════════════════════════════════════════════════════════════════
//  LOCAL AGENT RUNTIME: terminal sessions, SQLite, monitors, .env
// ═══════════════════════════════════════════════════════════════════
const TERMINAL_SESSIONS = new Map();
const PROCESS_MONITORS = new Map();

function appendTerminalOutput(session, stream, chunk) {
  const text = chunk.toString('utf8');
  session.output += text;
  const max = 2 * 1024 * 1024;
  if (session.output.length > max) {
    const removed = session.output.length - max;
    session.output = session.output.slice(removed);
    session.baseCursor += removed;
    if (session.readCursor < session.baseCursor) session.readCursor = session.baseCursor;
  }
  if (session.live) printLiveCommandChunk(`terminal:${session.id}:${stream}`, chunk);
}
function terminalCreateTool(args) {
  const cwd = mcpPathOrError(args.cwd || '.', 'cwd', true, true);
  if (cwd.error) return cwd;
  const id = safeProcessName(args.id || `term-${crypto.randomBytes(4).toString('hex')}`);
  if (!id) return { error: 'Некорректный id терминала.' };
  if (TERMINAL_SESSIONS.has(id)) return { error: `Терминал '${id}' уже существует.` };
  const isWin = process.platform === 'win32';
  const executable = args.shell || (isWin ? 'powershell.exe' : (process.env.SHELL || 'sh'));
  const shellArgs = isWin ? ['-NoLogo', '-NoExit'] : ['-i'];
  try {
    const proc = spawn(executable, shellArgs, { cwd: cwd.path, env: commandEnvironment(), stdio: ['pipe', 'pipe', 'pipe'] });
    const session = { id, proc, cwd: cwd.path, createdAt: new Date().toISOString(), output: '', baseCursor: 0, readCursor: 0, live: !!args.__cliLive, closed: false };
    TERMINAL_SESSIONS.set(id, session);
    proc.stdout.on('data', chunk => appendTerminalOutput(session, 'stdout', chunk));
    proc.stderr.on('data', chunk => appendTerminalOutput(session, 'stderr', chunk));
    proc.on('close', (code, signal) => { session.closed = true; session.exit = { code, signal, at: new Date().toISOString() }; });
    proc.on('error', err => { session.closed = true; session.error = err.message; });
    if (args.initial_command) proc.stdin.write(String(args.initial_command) + '\n');
    return { success: true, id, cwd: cwd.path, shell: executable, note: 'Постоянная shell-сессия создана. Используй terminal_write и terminal_read.' };
  } catch (e) { return { error: 'Не удалось создать терминал: ' + e.message }; }
}
function terminalWriteTool(args) {
  const id = String(args.id || ''); const session = TERMINAL_SESSIONS.get(id);
  if (!session) return { error: `Терминал '${id}' не найден.` };
  if (session.closed || !session.proc.stdin.writable) return { error: `Терминал '${id}' уже закрыт.` };
  const input = String(args.input ?? args.command ?? '');
  if (!input) return { error: 'Для terminal_write нужен input или command.' };
  try { session.proc.stdin.write(input + (args.newline === false ? '' : '\n')); return { success: true, id, bytes: Buffer.byteLength(input, 'utf8') }; }
  catch (e) { return { error: 'Не удалось отправить текст в терминал: ' + e.message }; }
}
function terminalReadTool(args) {
  const id = String(args.id || ''); const session = TERMINAL_SESSIONS.get(id);
  if (!session) return { error: `Терминал '${id}' не найден.` };
  const requested = args.cursor === undefined ? session.readCursor : Number(args.cursor);
  const cursor = Number.isFinite(requested) ? Math.max(requested, session.baseCursor) : session.readCursor;
  const offset = cursor - session.baseCursor;
  const content = session.output.slice(Math.max(0, offset));
  const nextCursor = session.baseCursor + session.output.length;
  if (args.cursor === undefined) session.readCursor = nextCursor;
  return { id, cwd: session.cwd, closed: session.closed, exit: session.exit || null, error: session.error || null, cursor, nextCursor, content };
}
function terminalListTool() {
  return { sessions: [...TERMINAL_SESSIONS.values()].map(s => ({ id: s.id, cwd: s.cwd, createdAt: s.createdAt, closed: s.closed, exit: s.exit || null, error: s.error || null })) };
}
function terminalCloseTool(args) {
  const id = String(args.id || ''); const session = TERMINAL_SESSIONS.get(id);
  if (!session) return { error: `Терминал '${id}' не найден.` };
  try {
    if (!session.closed) {
      // Interactive shells могут игнорировать SIGTERM; сначала просим штатно выйти,
      // затем гарантированно завершаем только этот дочерний процесс.
      try { session.proc.stdin.write('exit\n'); session.proc.stdin.end(); } catch {}
      try { session.proc.kill(args.force ? 'SIGKILL' : 'SIGTERM'); } catch {}
      const forceTimer = setTimeout(() => { try { if (!session.closed) session.proc.kill('SIGKILL'); } catch {} }, 500);
      forceTimer.unref();
      try { session.proc.stdout.destroy(); session.proc.stderr.destroy(); } catch {}
    }
  } catch (e) { return { error: 'Не удалось закрыть терминал: ' + e.message }; }
  TERMINAL_SESSIONS.delete(id);
  return { success: true, id, message: 'Терминальная сессия закрыта.' };
}

function sqliteAvailable() {
  try { return { available: true, version: execFileSync('sqlite3', ['--version'], { encoding: 'utf8', timeout: 5000 }).trim() }; }
  catch { return { available: false, hint: 'Установи локально: pkg install sqlite' }; }
}
function sqliteQueryTool(args) {
  const database = mcpPathOrError(args.database || args.path, 'database');
  if (database.error) return database;
  const sql = String(args.sql || args.query || '').trim();
  if (!sql) return { error: 'Для sqlite_query нужен sql.' };
  const available = sqliteAvailable(); if (!available.available) return { error: 'sqlite3 недоступен.', ...available };
  try {
    fs.mkdirSync(path.dirname(database.path), { recursive: true });
    const output = execFileSync('sqlite3', ['-json', database.path, sql], { encoding: 'utf8', timeout: safeCommandTimeout(args.timeout, 30000), maxBuffer: 10 * 1024 * 1024 });
    let rows = null; try { rows = output.trim() ? JSON.parse(output) : []; } catch {}
    return { success: true, database: database.path, rows, output: rows === null ? output : undefined };
  } catch (e) { return { error: 'SQLite error: ' + (e.stderr || e.message || '').toString() }; }
}
function sqliteSchemaTool(args) {
  return sqliteQueryTool({ ...args, sql: "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE type IN ('table','index','view','trigger') ORDER BY type, name;" });
}
function sqliteBackupTool(args) {
  const source = mcpPathOrError(args.database || args.path, 'database', true);
  if (source.error) return source;
  const destination = mcpPathOrError(args.destination || args.output, 'destination');
  if (destination.error) return destination;
  const available = sqliteAvailable(); if (!available.available) return { error: 'sqlite3 недоступен.', ...available };
  if (fs.existsSync(destination.path) && !args.overwrite) return { error: 'Файл backup уже существует. Передай overwrite:true, если уверен.' };
  try {
    fs.mkdirSync(path.dirname(destination.path), { recursive: true });
    execFileSync('sqlite3', [source.path, `.backup '${destination.path.replace(/'/g, "''")}'`], { encoding: 'utf8', timeout: 60000 });
    return { success: true, source: source.path, backup: destination.path, size: fs.statSync(destination.path).size };
  } catch (e) { return { error: 'SQLite backup error: ' + (e.stderr || e.message || '').toString() }; }
}

function envFilePath(args, mustExist = false) {
  const raw = args.path || '.env';
  const resolved = mcpPathOrError(raw, 'path', false);
  if (resolved.error) return resolved;
  try {
    if (fs.existsSync(resolved.path) && fs.statSync(resolved.path).isDirectory()) {
      const envPath = path.join(resolved.path, '.env');
      if (mustExist && !fs.existsSync(envPath)) return { error: 'Файл .env не найден: ' + envPath };
      return { path: envPath };
    }
    if (mustExist && !fs.existsSync(resolved.path)) return { error: 'Файл .env не найден: ' + resolved.path };
  } catch (e) { return { error: 'Не удалось проверить .env: ' + e.message }; }
  return resolved;
}
function parseEnvText(text) {
  const values = []; const lines = String(text || '').split('\n');
  for (let index = 0; index < lines.length; index++) {
    const match = lines[index].match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
    if (match) values.push({ key: match[1], value: match[2], line: index + 1 });
  }
  return values;
}
function envListTool(args) {
  const file = envFilePath(args, true); if (file.error) return file;
  try { return { path: file.path, variables: parseEnvText(fs.readFileSync(file.path, 'utf8')).map(x => ({ key: x.key, value: x.value ? '***' : '', line: x.line })) }; }
  catch (e) { return { error: 'Не удалось прочитать .env: ' + e.message }; }
}
function envSetTool(args) {
  const file = envFilePath(args, false); if (file.error) return file;
  const key = String(args.key || '').trim();
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) return { error: 'Некорректное имя переменной окружения.' };
  if (key === 'OPENROUTER_API_KEY') return { error: 'OpenRouter key хранится только через /key, не в .env проекта.' };
  if (args.value === undefined) return { error: 'Для env_set нужен value.' };
  const value = String(args.value).replace(/[\r\n]/g, '');
  try {
    let text = fs.existsSync(file.path) ? fs.readFileSync(file.path, 'utf8') : '';
    const rx = new RegExp(`^(\\s*${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*=).*$`, 'm');
    text = rx.test(text) ? text.replace(rx, `$1${value}`) : text + (text && !text.endsWith('\n') ? '\n' : '') + `${key}=${value}\n`;
    fs.mkdirSync(path.dirname(file.path), { recursive: true }); fs.writeFileSync(file.path, text, 'utf8');
    return { success: true, path: file.path, key, value: '***' };
  } catch (e) { return { error: 'Не удалось записать .env: ' + e.message }; }
}
function envDeleteTool(args) {
  const file = envFilePath(args, true); if (file.error) return file;
  const key = String(args.key || '').trim(); if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) return { error: 'Некорректное имя переменной.' };
  try {
    const lines = fs.readFileSync(file.path, 'utf8').split('\n');
    const filtered = lines.filter(line => !new RegExp(`^\\s*${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*=`).test(line));
    fs.writeFileSync(file.path, filtered.join('\n'), 'utf8'); return { success: true, path: file.path, key };
  } catch (e) { return { error: 'Не удалось удалить переменную: ' + e.message }; }
}

function monitorLogPath(id) { const dir = path.join(WORKSPACE_ROOT, '.zen-agent', 'monitors'); fs.mkdirSync(dir, { recursive: true }); return path.join(dir, `${id}.log`); }
function writeMonitorLog(monitor, text) { const line = `[${new Date().toISOString()}] ${text}\n`; try { fs.appendFileSync(monitor.logPath, line); } catch {} if (monitor.live) printLiveCommandChunk(`monitor:${monitor.id}`, Buffer.from(line)); }
async function runMonitorCheck(monitor) {
  if (monitor.busy) return; monitor.busy = true;
  try {
    const item = readProcessRegistry()[monitor.processName];
    if (!item) { writeMonitorLog(monitor, `Процесс ${monitor.processName} не зарегистрирован.`); return; }
    let healthy = processIsAlive(item.pid); let detail = healthy ? `PID ${item.pid} работает` : `PID ${item.pid} не работает`;
    if (healthy && monitor.url) {
      const check = await httpRequestTool({ url: monitor.url, timeout: monitor.timeout });
      healthy = !!check.ok; detail = healthy ? `HTTP ${check.status}` : `HTTP ошибка: ${check.error || check.status}`;
    }
    if (!healthy && monitor.restart) {
      writeMonitorLog(monitor, `${detail}; перезапуск разрешён.`);
      if (processIsAlive(item.pid)) stopManagedProcess(monitor.processName, false);
      const started = startManagedProcess({ name: monitor.processName, command: item.command, cwd: item.cwd });
      writeMonitorLog(monitor, started.success ? `Перезапущен PID ${started.pid}` : `Перезапуск не удался: ${started.error}`);
    } else writeMonitorLog(monitor, healthy ? `OK: ${detail}` : `FAIL: ${detail}`);
  } finally { monitor.busy = false; }
}
function monitorStartTool(args) {
  const processName = safeProcessName(args.process_name || args.process || args.name);
  if (!processName) return { error: 'Для monitor_start укажи process_name.' };
  const id = safeProcessName(args.id || processName); if (!id) return { error: 'Некорректный id монитора.' };
  if (PROCESS_MONITORS.has(id)) return { error: `Монитор '${id}' уже запущен.` };
  if (!readProcessRegistry()[processName]) return { error: `Управляемый процесс '${processName}' не найден. Сначала process_start.` };
  const intervalSeconds = boundedInt(args.interval_seconds || args.interval, 15, 3, 3600);
  const monitor = { id, processName, url: args.url || null, restart: args.restart !== false, timeout: boundedInt(args.timeout, 5000, 1000, 30000), intervalSeconds, createdAt: new Date().toISOString(), logPath: monitorLogPath(id), live: !!args.__cliLive, busy: false };
  monitor.timer = setInterval(() => { runMonitorCheck(monitor); }, intervalSeconds * 1000);
  PROCESS_MONITORS.set(id, monitor); runMonitorCheck(monitor);
  return { success: true, id, processName, url: monitor.url, restart: monitor.restart, intervalSeconds, logPath: monitor.logPath };
}
function monitorListTool() { return { monitors: [...PROCESS_MONITORS.values()].map(m => ({ id: m.id, processName: m.processName, url: m.url, restart: m.restart, intervalSeconds: m.intervalSeconds, logPath: m.logPath, createdAt: m.createdAt })) }; }
function monitorLogsTool(args) { const id = String(args.id || args.name || ''); const monitor = PROCESS_MONITORS.get(id); if (!monitor) return { error: `Монитор '${id}' не найден.` }; return { id, path: monitor.logPath, ...tailFile(monitor.logPath, args.lines || 120) }; }
function monitorStopTool(args) { const id = String(args.id || args.name || ''); const monitor = PROCESS_MONITORS.get(id); if (!monitor) return { error: `Монитор '${id}' не найден.` }; clearInterval(monitor.timer); PROCESS_MONITORS.delete(id); writeMonitorLog(monitor, 'Монитор остановлен.'); return { success: true, id }; }

async function handleMCPTool(tool, args = {}) {
  switch (tool) {
    case 'workspace_info':
      return workspaceInfo();

    case 'set_workspace':
      return setWorkspaceRoot(args.path || args.workspace);

    case 'project_inspect':
      return inspectProject(args.path || '.');

    case 'termux_info':
      return termuxInfoTool();

    case 'network_check':
      return networkCheckTool();

    case 'tree_dir':
      return treeDirectory(args);

    case 'search_text':
      return searchTextInFiles(args);

    case 'file_info':
      return fileInfoTool(args);

    case 'find_files':
      return findWorkspaceEntries(args.query || args.name || '', args);

    case 'list_dir': {
      const resolved = mcpPathOrError(args.path || '.', 'path', true, true);
      if (resolved.error) return resolved;
      try {
        const items = fs.readdirSync(resolved.path, { withFileTypes: true })
          .map(e => ({ name: e.name, path: path.join(resolved.path, e.name), type: e.isDirectory() ? 'directory' : 'file' }));
        return { workspace: WORKSPACE_ROOT, path: resolved.path, items };
      } catch (e) { return { error: 'Не удалось прочитать папку: ' + e.message }; }
    }

    case 'read_file': {
      const resolved = mcpPathOrError(args.path, 'path', true);
      if (resolved.error) return resolved;
      try { return { path: resolved.path, content: fs.readFileSync(resolved.path, 'utf8') }; }
      catch (e) { return { error: 'Не удалось прочитать файл: ' + e.message }; }
    }

    case 'write_file': {
      const resolved = mcpPathOrError(args.path, 'path');
      if (resolved.error) return resolved;
      try {
        fs.mkdirSync(path.dirname(resolved.path), { recursive: true });
        const content = args.content || '';
        if (/OPENROUTER_API_KEY\s*=|sk-or-(?:v1-)?[A-Za-z0-9_-]{16,}/i.test(content)) {
          return { error: 'OpenRouter key нельзя записывать в проектный файл или .env. Используй интерфейсную команду /key.' };
        }
        fs.writeFileSync(resolved.path, content, 'utf8');
        return { success: true, path: resolved.path, size: Buffer.byteLength(content, 'utf8'), lines: content.split('\n').length, workspace: WORKSPACE_ROOT };
      } catch (e) { return { error: 'Не удалось записать файл: ' + e.message }; }
    }

    case 'edit_file': {
      const resolved = mcpPathOrError(args.path, 'path', true);
      if (resolved.error) return resolved;
      try {
        let content = fs.readFileSync(resolved.path, 'utf8');
        const op = args.operation || 'replace';
        if (op === 'replace') {
          const oldText = args.old || '';
          if (!oldText) return { error: 'Для replace обязательно передай непустой old.' };
          if (!content.includes(oldText)) return { error: 'Текст для замены не найден: ' + resolved.path };
          content = content.split(oldText).join(args.new || '');
        } else if (op === 'insert') {
          const lines = content.split('\n');
          lines.splice(parseInt(args.line || '0', 10), 0, args.content || '');
          content = lines.join('\n');
        } else if (op === 'delete_lines') {
          const lines = content.split('\n');
          const range = (args.lines || '').split(',').map(Number);
          for (let i = range.length - 1; i >= 0; i--) if (range[i] >= 0 && range[i] < lines.length) lines.splice(range[i], 1);
          content = lines.join('\n');
        } else if (op === 'append') {
          content += (content.endsWith('\n') ? '' : '\n') + (args.content || '');
        } else return { error: 'Неизвестная операция edit_file: ' + op };
        fs.writeFileSync(resolved.path, content, 'utf8');
        return { success: true, path: resolved.path, workspace: WORKSPACE_ROOT };
      } catch (e) { return { error: 'Edit failed: ' + e.message }; }
    }

    case 'delete_file': {
      const resolved = mcpPathOrError(args.path, 'path', true);
      if (resolved.error) return resolved;
      try {
        const stat = fs.statSync(resolved.path);
        if (stat.isDirectory()) fs.rmSync(resolved.path, { recursive: true, force: true });
        else fs.unlinkSync(resolved.path);
        return { success: true, path: resolved.path, workspace: WORKSPACE_ROOT };
      } catch (e) { return { error: 'Delete failed: ' + e.message }; }
    }

    case 'append_file': {
      const resolved = mcpPathOrError(args.path, 'path');
      if (resolved.error) return resolved;
      try {
        fs.mkdirSync(path.dirname(resolved.path), { recursive: true });
        fs.appendFileSync(resolved.path, (args.content || '') + '\n', 'utf8');
        return { success: true, path: resolved.path, workspace: WORKSPACE_ROOT };
      } catch (e) { return { error: 'Append failed: ' + e.message }; }
    }

    case 'file_backup':
      return backupFile(args);

    case 'file_diff':
      return simpleFileDiff(args);

    case 'mkdir':
      return mkdirTool(args);

    case 'copy_file':
      return copyOrMoveTool(args, false);

    case 'move_file':
      return copyOrMoveTool(args, true);

    case 'archive_create':
      return archiveCreateTool(args);

    case 'archive_extract':
      return archiveExtractTool(args);

    case 'download_file': {
      const resolved = mcpPathOrError(args.path, 'path');
      if (resolved.error) return resolved;
      if (!args.url) return { error: 'Для download_file нужен url.' };
      try {
        fs.mkdirSync(path.dirname(resolved.path), { recursive: true });
        execFileSync(curlPath(), ['-L', '--fail', '--max-time', '60', ...(CONFIG.proxy ? ['-x', CONFIG.proxy] : []), '-o', resolved.path, String(args.url)], {
          cwd: WORKSPACE_ROOT, timeout: 65000, stdio: ['ignore', 'pipe', 'pipe']
        });
        return { success: true, path: resolved.path, size: fs.statSync(resolved.path).size, workspace: WORKSPACE_ROOT };
      } catch (e) { return { error: 'Не удалось скачать файл: ' + (e.stderr || e.message || '').toString() }; }
    }

    case 'execute_command': {
      const cwdResult = mcpPathOrError(args.cwd || '.', 'cwd', true, true);
      if (cwdResult.error) return cwdResult;
      const runCwd = cwdResult.path;
      if (!args.command || !String(args.command).trim()) return { error: 'Для execute_command нужна command.' };
      const commandText = String(args.command).trim();
      // Фоновый сервер через «&» теряет управляемый PID и делает логи ненадёжными.
      if (/(^|[^&])&\s*$/.test(commandText) || /\bnohup\b|\bdisown\b/.test(commandText)) {
        return { error: 'Не запускай фоновые процессы через execute_command. Используй process_start с name, command и cwd — тогда будут PID, process_logs и безопасный process_stop.' };
      }
      const opts = {
        cwd: runCwd,
        timeout: safeCommandTimeout(args.timeout, 18000),
        maxBuffer: 10 * 1024 * 1024,
        env: {
          ...process.env,
          ZEN_WORKSPACE: WORKSPACE_ROOT,
          MCP_WORKSPACE: WORKSPACE_ROOT,
          ...(CONFIG.proxy ? {
            HTTP_PROXY: CONFIG.proxy, HTTPS_PROXY: CONFIG.proxy, ALL_PROXY: CONFIG.proxy,
            http_proxy: CONFIG.proxy, https_proxy: CONFIG.proxy, all_proxy: CONFIG.proxy,
            // Локальный сервер MCP/Node никогда не должен уходить в удалённый прокси.
            NO_PROXY: [process.env.NO_PROXY, 'localhost,127.0.0.1,::1'].filter(Boolean).join(','),
            no_proxy: [process.env.no_proxy, 'localhost,127.0.0.1,::1'].filter(Boolean).join(',')
          } : {})
        }
      };

      // В CLI используем потоковый режим: stdout/stderr видны сразу, а не после спиннера.
      if (args.__cliLive && CONFIG.liveToolLogs) {
        return await runCommandWithLiveLogs(args.command, runCwd, opts);
      }

      const isWin = process.platform === 'win32';
      if (isWin) {
        opts.shell = 'powershell.exe'; opts.encoding = 'utf8';
        const psCmd = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; $OutputEncoding=[Text.Encoding]::UTF8; " + args.command;
        try { const out = execSync(psCmd, opts); return { stdout: out, stderr: '', exit: 0, cwd: runCwd, workspace: WORKSPACE_ROOT, live: false }; }
        catch (e) { return { stdout: (e.stdout || '').toString(), stderr: (e.stderr || e.message || '').toString(), exit: typeof e.status === 'number' ? e.status : 1, cwd: runCwd, workspace: WORKSPACE_ROOT, live: false }; }
      }
      try { const out = execSync(args.command, { ...opts, encoding: 'utf8' }); return { stdout: out, stderr: '', exit: 0, cwd: runCwd, workspace: WORKSPACE_ROOT, live: false }; }
      catch (e) { return { stdout: (e.stdout || '').toString(), stderr: (e.stderr || e.message || '').toString(), exit: typeof e.status === 'number' ? e.status : 1, cwd: runCwd, workspace: WORKSPACE_ROOT, live: false }; }
    }

    case 'process_start':
      return startManagedProcess(args);

    case 'process_status':
      return managedProcessStatus(args.name ? safeProcessName(args.name) || args.name : null);

    case 'process_logs': {
      const name = safeProcessName(args.name);
      if (!name) return { error: 'Для process_logs нужно имя name.' };
      const item = readProcessRegistry()[name];
      if (!item) return { error: `Процесс '${name}' не зарегистрирован.` };
      return await followManagedLog(item, args);
    }

    case 'process_stop':
      return stopManagedProcess(args.name, !!args.force);

    case 'monitor_start':
      return monitorStartTool(args);

    case 'monitor_list':
      return monitorListTool();

    case 'monitor_logs':
      return monitorLogsTool(args);

    case 'monitor_stop':
      return monitorStopTool(args);

    case 'terminal_create':
      return terminalCreateTool(args);

    case 'terminal_write':
      return terminalWriteTool(args);

    case 'terminal_read':
      return terminalReadTool(args);

    case 'terminal_list':
      return terminalListTool();

    case 'terminal_close':
      return terminalCloseTool(args);

    case 'http_request':
    case 'health_check':
      return await httpRequestTool(args);

    case 'websocket_test':
      return await websocketTestTool(args);

    case 'npm_install': {
      const cwdResult = mcpPathOrError(args.cwd || '.', 'cwd', true, true);
      if (cwdResult.error) return cwdResult;
      const packages = safeNpmTokens(args.packages || args.package);
      if (packages.error) return packages;
      const command = `npm install ${packages.tokens.map(shellQuote).join(' ')}`;
      const opts = { cwd: cwdResult.path, timeout: safeCommandTimeout(args.timeout, 120000), env: commandEnvironment() };
      if (args.__cliLive && CONFIG.liveToolLogs) return await runCommandWithLiveLogs(command, cwdResult.path, opts);
      try {
        const out = execSync(command, { ...opts, encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });
        return { success: true, command, cwd: cwdResult.path, stdout: out, workspace: WORKSPACE_ROOT };
      } catch (e) { return { error: 'npm install failed: ' + (e.stderr || e.message || '').toString() }; }
    }

    case 'npm_run': {
      const cwdResult = mcpPathOrError(args.cwd || '.', 'cwd', true, true);
      if (cwdResult.error) return cwdResult;
      const script = String(args.script || '').trim();
      if (!/^[a-zA-Z0-9:_-]+$/.test(script)) return { error: 'Для npm_run укажи безопасное имя script из package.json.' };
      const extra = args.args ? safeNpmTokens(args.args) : { tokens: [] };
      if (extra.error) return extra;
      const command = `npm run ${shellQuote(script)}${extra.tokens.length ? ' -- ' + extra.tokens.map(shellQuote).join(' ') : ''}`;
      const opts = { cwd: cwdResult.path, timeout: safeCommandTimeout(args.timeout, 120000), env: commandEnvironment() };
      if (args.__cliLive && CONFIG.liveToolLogs) return await runCommandWithLiveLogs(command, cwdResult.path, opts);
      try {
        const out = execSync(command, { ...opts, encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });
        return { success: true, command, cwd: cwdResult.path, stdout: out, workspace: WORKSPACE_ROOT };
      } catch (e) { return { error: 'npm run failed: ' + (e.stderr || e.message || '').toString() }; }
    }

    case 'sqlite_info':
      return sqliteAvailable();

    case 'sqlite_query':
      return sqliteQueryTool(args);

    case 'sqlite_schema':
      return sqliteSchemaTool(args);

    case 'sqlite_backup':
      return sqliteBackupTool(args);

    case 'env_list':
      return envListTool(args);

    case 'env_set':
      return envSetTool(args);

    case 'env_delete':
      return envDeleteTool(args);

    case 'run_tests':
      return await handleMCPTool('npm_run', { ...args, script: args.script || 'test' });

    case 'run_lint':
      return await handleMCPTool('npm_run', { ...args, script: args.script || 'lint' });

    case 'code_check': {
      const check = codeCheckCommand(args);
      if (check.error) return check;
      return await gitLiveTool(check.command, { path: path.dirname(check.file.path) }, args);
    }

    case 'dependency_audit': {
      const cwdResult = gitCwd(args); if (cwdResult.error) return cwdResult;
      return await gitLiveTool('npm audit --json', cwdResult, args);
    }

    case 'git_status': {
      const cwdResult = gitCwd(args); if (cwdResult.error) return cwdResult;
      return await gitLiveTool('git status --short --branch', cwdResult, args);
    }

    case 'git_diff': {
      const cwdResult = gitCwd(args); if (cwdResult.error) return cwdResult;
      const target = args.path ? safeGitRef(args.path) : null;
      if (args.path && !target) return { error: 'Для git_diff path допускаются только безопасные относительные Git-пути.' };
      return await gitLiveTool(`git diff${args.staged ? ' --staged' : ''}${target ? ' -- ' + shellQuote(target) : ''}`, cwdResult, args);
    }

    case 'git_branch': {
      const cwdResult = gitCwd(args); if (cwdResult.error) return cwdResult;
      return await gitLiveTool('git branch --show-current && git branch --all', cwdResult, args);
    }

    case 'git_log': {
      const cwdResult = gitCwd(args); if (cwdResult.error) return cwdResult;
      const limit = boundedInt(args.limit, 10, 1, 100);
      return await gitLiveTool(`git log --oneline -n ${limit}`, cwdResult, args);
    }

    case 'git_clone': {
      // Clones into work/ beside the current checkout, then reports the path so
      // the model can set_workspace into it. Credentials are already configured
      // on the runner, so any repository the token can see will clone.
      const repo = String(args.repo || args.url || '').trim();
      if (!repo) return { error: 'Нужен repo: owner/name или полный URL.' };
      const url = /^https?:\/\//.test(repo) ? repo : `https://github.com/${repo}.git`;
      const name = String(args.name || repo.replace(/\.git$/, '').split('/').pop() || 'repo');
      const parent = path.join(WORKSPACE_ROOT, 'work');
      const target = path.join(parent, name);
      try {
        fs.mkdirSync(parent, { recursive: true });
        if (fs.existsSync(target)) {
          return { success: true, path: target, note: 'Уже склонировано. Обнови через git_pull.' };
        }
        const depth = args.full ? [] : ['--depth', '1'];
        const out = execFileSync('git', ['clone', ...depth, url, target],
          { encoding: 'utf8', timeout: 300000, stdio: ['ignore', 'pipe', 'pipe'] });
        return {
          success: true, path: target, repo: url,
          note: `Склонировано. Чтобы работать внутри: set_workspace {"path":"${target}"}`,
          output: String(out || '').slice(0, 500)
        };
      } catch (e) {
        return { error: 'Клонирование не удалось: ' + (e.stderr || e.message || '').toString().slice(0, 400) };
      }
    }

    case 'git_pull': {
      const cwdResult = gitCwd(args); if (cwdResult.error) return cwdResult;
      return await gitLiveTool('git pull --ff-only', cwdResult, args);
    }

    case 'git_push': {
      const cwdResult = gitCwd(args); if (cwdResult.error) return cwdResult;
      const branch = String(args.branch || '').trim();
      return await gitLiveTool(branch ? `git push origin ${branch}` : 'git push', cwdResult, args);
    }

    case 'git_init': {
      const cwdResult = gitCwd(args); if (cwdResult.error) return cwdResult;
      return await gitLiveTool('git init', cwdResult, args);
    }

    case 'git_commit': {
      const cwdResult = gitCwd(args); if (cwdResult.error) return cwdResult;
      const message = String(args.message || '').trim();
      if (!message) return { error: 'Для git_commit нужен message.' };
      return await gitLiveTool(`git add -A && git commit -m ${shellQuote(message)}`, cwdResult, args);
    }

    // ── GitHub, without a checkout ────────────────────────────────
    // Every one of these is a single API call against the live repository.
    // No clone, no working copy, and a write is already a commit - which is
    // why there is no github_push: there is nothing left to push.
    case 'github_read': case 'github_write': case 'github_list':
    case 'github_delete': case 'github_commit_files': case 'github_search':
    case 'github_commits': case 'github_branches': case 'github_create_branch':
    case 'github_pr': case 'github_repo': case 'github_my_repos':
    case 'github_runs': case 'github_run_workflow': {
      const api = githubApi();
      if (!api) return { error: 'Модуль GitHub API недоступен (lib/github-api.js).' };
      const map = {
        github_read: 'readFile', github_write: 'writeFile', github_list: 'list',
        github_delete: 'deleteFile', github_commit_files: 'commitFiles',
        github_search: 'search', github_commits: 'commits', github_branches: 'branches',
        github_create_branch: 'createBranch', github_pr: 'pullRequest',
        github_repo: 'repoInfo', github_my_repos: 'myRepos',
        github_runs: 'runs', github_run_workflow: 'dispatch'
      };
      try {
        return await api[map[tool]](args || {});
      } catch (e) {
        // The API's own message names the cause - a missing scope, a bad
        // path, a protected branch - so it is passed through rather than
        // flattened into "request failed".
        return { error: String(e && e.message || e) };
      }
    }

    case 'preset_list': {
      const all = allPresets();
      return {
        active: PRESETS.active,
        presets: Object.entries(all).map(([id, p]) => ({
          id, label: p.label, builtIn: !!p.builtIn,
          on: PRESETS.active.includes(id),
          preview: String(p.text).split('\n')[0].slice(0, 90)
        }))
      };
    }

    case 'preset_set': {
      const id = String(args.id || '').trim();
      const on = args.on === undefined ? true : !(args.on === false || args.on === 'false' || args.on === 'off');
      const result = setPresetActive(id, on);
      if (result.error) return result;
      return { ...result, note: on ? `Пресет '${id}' включён.` : `Пресет '${id}' выключен.` };
    }

    case 'preset_save': {
      const id = String(args.id || '').trim();
      const text = String(args.text || '').trim();
      if (!/^[\w-]{2,32}$/.test(id)) return { error: 'id пресета: 2-32 символа, буквы, цифры, _ и -.' };
      if (!text) return { error: 'Нужен text — что именно агент должен всегда делать.' };
      PRESETS.custom[id] = text;
      savePresets();
      if (args.activate !== false) setPresetActive(id, true);
      return { id, saved: true, active: PRESETS.active };
    }

    case 'open_url': {
      const url = String(args.url || '').trim();
      if (!/^https?:\/\//i.test(url)) return { error: 'open_url принимает только http:// или https:// URL.' };
      return runTermuxApi('termux-open-url', [url]);
    }

    case 'clipboard_read':
      return runTermuxApi('termux-clipboard-get');

    case 'clipboard_write': {
      const text = String(args.text || args.content || '');
      if (!text) return { error: 'Для clipboard_write нужен text.' };
      return runTermuxApi('termux-clipboard-set', [text]);
    }

    case 'notify': {
      const title = String(args.title || 'Zen Agent');
      const content = String(args.content || args.text || '');
      if (!content) return { error: 'Для notify нужен content.' };
      return runTermuxApi('termux-notification', ['--title', title, '--content', content]);
    }

    case 'termux_api_status':
      return termuxApiStatus();

    case 'termux_battery':
      return runTermuxApi('termux-battery-status');

    case 'termux_wifi':
      return runTermuxApi('termux-wifi-connectioninfo');

    case 'termux_toast': {
      const text = String(args.text || args.content || ''); if (!text) return { error: 'Для termux_toast нужен text.' };
      return runTermuxApi('termux-toast', [text]);
    }

    case 'termux_vibrate':
      return runTermuxApi('termux-vibrate', args.duration ? ['-d', String(Math.max(1, Math.min(10000, Number(args.duration) || 200)))] : []);

    case 'termux_share': {
      const file = String(args.file || args.path || '').trim();
      if (!file) return { error: 'termux_share принимает только существующий file/path. Для текста используй clipboard_write.' };
      const resolved = mcpPathOrError(file, 'file', true); if (resolved.error) return resolved;
      return runTermuxApi('termux-share', [resolved.path]);
    }

    case 'termux_volume': {
      const stream = String(args.stream || 'music'); const volume = Math.max(0, Math.min(15, Number(args.volume)));
      if (!Number.isFinite(volume)) return { error: 'Для termux_volume укажи volume от 0 до 15.' };
      return runTermuxApi('termux-volume', [stream, String(volume)]);
    }

    case 'termux_location':
      return runTermuxApi('termux-location', args.provider ? ['-p', String(args.provider)] : []);

    case 'todo_list': {
      loadTodos();
      return { workspace: WORKSPACE_ROOT, todos: todos.filter(t => !t.workspace || t.workspace === WORKSPACE_ROOT) };
    }

    case 'todo_add': {
      const text = String(args.text || args.content || '').trim();
      if (!text) return { error: 'Для todo_add нужен text.' };
      const id = addTodo(text, { workspace: WORKSPACE_ROOT, source: 'mcp' });
      return { success: true, id, workspace: WORKSPACE_ROOT };
    }

    case 'todo_done': {
      const id = parseInt(args.id, 10);
      return doneTodo(id, WORKSPACE_ROOT) ? { success: true, id, workspace: WORKSPACE_ROOT } : { error: 'Задача не найдена в текущем проекте: #' + args.id };
    }

    case 'todo_remove': {
      const id = parseInt(args.id, 10);
      return removeTodo(id, WORKSPACE_ROOT) ? { success: true, id, workspace: WORKSPACE_ROOT } : { error: 'Задача не найдена в текущем проекте: #' + args.id };
    }

    case 'web_search': return webSearchTool(args);
    case 'web_fetch': return webFetchTool(args);

    case 'read_image': {
      const resolved = mcpPathOrError(args.path, 'path', true);
      if (resolved.error) return resolved;
      try {
        const ext = path.extname(resolved.path).slice(1) || 'png';
        const base64 = fs.readFileSync(resolved.path).toString('base64');
        return { path: resolved.path, base64, mime: `image/${ext}` };
      } catch (e) { return { error: 'Не удалось прочитать изображение: ' + e.message }; }
    }

    case 'image_info':
      return imageInfoTool(args);

    case 'ocr_image':
      return ocrImageTool(args);

    case 'vision_analyze':
    case 'analyze_image':
      return await visionAnalyzeTool(args);

    case 'vision_ui_audit':
      return await visionUiAuditTool(args);

    case 'vision_compare':
      return await visionCompareTool(args);



    case 'custom_tool_list':
      return customToolListTool();

    case 'custom_tool_create':
      return customToolCreateTool(args);

    case 'custom_tool_inspect':
      return customToolInspectTool(args);

    case 'custom_tool_run':
      return await customToolRunTool(args);

    case 'custom_tool_delete':
      return customToolDeleteTool(args);

    case 'subagent_list':
      return subagentListTool();

    case 'subagent_create':
      return subagentCreateTool(args);

    case 'subagent_task':
      return await subagentTaskTool(args);

    case 'subagent_delete':
      return subagentDeleteTool(args);

    case 'plugin_list':
      return pluginListTool();

    case 'plugin_create':
      return pluginCreateTool(args);

    case 'plugin_inspect':
      return pluginInspectTool(args);

    case 'plugin_delete':
      return pluginDeleteTool(args);

    case 'plugin_tool_list':
      return pluginToolListTool();

    case 'plugin_tool_run':
      return await pluginToolRunTool(args);

    case 'plugin_provider_list':
      return pluginProviderListTool();
    default:
      // Capabilities register their own tool names; check before giving up.
      if (capabilities && capabilities.handles(tool)) return await capabilities.handle(tool, args);
      return { error: `Unknown tool: ${tool}` };
  }
}

// ═══════════════════════════════════════════════════════════════════
//  EMBEDDED ZEN PROXY
// ═══════════════════════════════════════════════════════════════════
function zenSleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function zenChatOnce(body) {
  // Do not use execSync here: on Termux it blocks the Node event loop and
  // manifests as `spawnSync ... sh ETIMEDOUT` in the browser Site UI.
  const source = Array.isArray(body.messages) ? body.messages : [];
  const messages = source.map(item => ({ role: item.role, content: item.content }));
  if (!messages.length || messages[0].role !== 'system') messages.unshift({ role: 'system', content: buildSystemPrompt() });
  const payload = { model: body.model || CONFIG.defaultModel, messages, max_tokens: body.max_tokens || CONFIG.maxTokens, temperature: body.temperature || CONFIG.temperature, stream: false };
  // Site is a compact project editor: request a direct final response rather
  // than spending its answer budget on a visible reasoning channel.
  if (CONFIG.reasoningEffort && !body.siteMode) payload.reasoning_effort = CONFIG.reasoningEffort;
  const tmpFile = path.join(os.tmpdir(), 'zen_req_' + Date.now() + '_' + Math.random().toString(36).slice(2) + '.json');
  fs.writeFileSync(tmpFile, JSON.stringify(payload), 'utf8');
  return await new Promise((resolve, reject) => {
    const args = ['-s', '--connect-timeout', '10', '--max-time', '60', ...(CONFIG.proxy ? ['-x', CONFIG.proxy] : []), ...(CONFIG.curlIpv4 ? ['--ipv4'] : []), '-X', 'POST', 'https://opencode.ai/zen/v1/chat/completions', '-H', 'Content-Type: application/json', '-d', '@' + tmpFile];
    const proc = spawn(curlPath(), args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '', settled = false;
    const finish = (error, value) => { if (settled) return; settled = true; clearTimeout(timer); try { fs.unlinkSync(tmpFile); } catch {} if (error) reject(error); else resolve(value); };
    const timer = setTimeout(() => { try { proc.kill('SIGTERM'); } catch {} finish(new Error('Zen request timeout (65s)')); }, 65000);
    proc.stdout.on('data', chunk => stdout += chunk.toString());
    proc.stderr.on('data', chunk => stderr += chunk.toString());
    proc.on('error', err => finish(new Error('Zen request failed: ' + err.message)));
    proc.on('close', code => {
      if (settled) return;
      if (code !== 0) { const msg = stderr || `curl exit ${code}`; if (isRateLimit(msg)) { finish(null, { __rateLimit: true, raw: msg }); return; } finish(new Error('Zen request failed: ' + msg.slice(0, 300))); return; }
      try {
        const json = JSON.parse(stdout);
        if (json.type === 'error' || json.error || isRateLimit(stdout)) { finish(null, { __rateLimit: true, raw: stdout }); return; }
        finish(null, json);
      } catch {
        if (isRateLimit(stdout)) { finish(null, { __rateLimit: true, raw: stdout }); return; }
        finish(new Error('Zen parse error: ' + stdout.slice(0, 400)));
      }
    });
  });
}

async function proxyZenChat(body) {
  let model = body.model || CONFIG.defaultModel;
  let lastErr;
  for (let i = 0; i < ZEN_MODELS.length; i++) {
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const json = await zenChatOnce({ ...body, model });
        if (json && json.__rateLimit) { if (attempt < 1) { await zenSleep(1500); continue; } break; }
        json._model = model; return json;
      } catch (e) {
        lastErr = e;
        if (isRateLimit(e.message)) { if (attempt < 1) { await zenSleep(1500); continue; } break; }
        throw e;
      }
    }
    if (i < ZEN_MODELS.length - 1) { model = nextModel(model); await zenSleep(1500); }
    else throw lastErr || new Error('Zen rate limit: лимит исчерпан на всех моделях.');
  }
  throw lastErr;
}

function streamOnce(body, res) {
  return new Promise((resolve) => {
    const messages = body.messages || [];
    if (!messages.length || messages[0].role !== 'system') messages.unshift({ role: 'system', content: buildSystemPrompt() });
    const payload = { model: body.model || CONFIG.defaultModel, messages, max_tokens: body.max_tokens || CONFIG.maxTokens, temperature: body.temperature || CONFIG.temperature, stream: true };
    if (CONFIG.reasoningEffort && !body.siteMode) payload.reasoning_effort = CONFIG.reasoningEffort;
    const data = JSON.stringify(payload);
    const tmpFile = path.join(os.tmpdir(), 'zen_req_' + Date.now() + '_' + Math.random().toString(36).slice(2) + '.json');
    fs.writeFileSync(tmpFile, data, 'utf8');
    let flushed = false, buffer = '', cleanedUp = false, done = false;
    const cleanup = () => { if (!cleanedUp) { cleanedUp = true; try { fs.unlinkSync(tmpFile); } catch {} } };
    const safeResolve = (v) => { if (!done) { done = true; resolve(v); } };
    const endWith = (msg) => {
      if (done) return;
      try { if (curlProc && !curlProc.killed) curlProc.kill(); } catch {}
      cleanup();
      if (!res.headersSent) res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
      if (!res.writableEnded) { res.write('data: {"error":"' + String(msg || 'Zen error').replace(/"/g, "'") + '"}\n\n'); res.end(); }
      safeResolve(false);
    };
    const proxyArgs = curlProxyArgs();
      const curlArgs = ['-s', '--no-buffer', '--connect-timeout', '10', '--max-time', '60'];
      if (proxyArgs) { curlArgs.push('-x', CONFIG.proxy); }
      curlArgs.push('-X', 'POST', 'https://opencode.ai/zen/v1/chat/completions', '-H', 'Content-Type: application/json', '-d', '@' + tmpFile);
      const curlProc = spawn(curlPath(), curlArgs);
    const hardCap = setTimeout(() => endWith('Zen proxy timeout (75s)'), 75000);
    curlProc.stdout.on('data', (chunk) => {
      buffer += chunk.toString();
      if (!flushed) {
        if (isRateLimit(buffer)) { try { curlProc.kill(); } catch {} cleanup(); clearTimeout(hardCap); safeResolve(true); return; }
        if (buffer.includes('data: ') && !/data:\s*\{\s*"type"\s*:\s*"error"/.test(buffer)) {
          flushed = true;
          if (!res.headersSent) res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
          res.write(buffer); buffer = '';
        }
      } else { res.write(chunk); }
    });
    curlProc.on('close', () => {
      clearTimeout(hardCap); cleanup();
      if (!flushed) {
        if (isRateLimit(buffer)) { safeResolve(true); return; }
        if (done) return;
        if (!res.headersSent) res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
        if (!res.writableEnded) { res.write(buffer || 'data: {"error":"Zen stream closed unexpectedly"}\n\n'); res.end(); }
        safeResolve(false); return;
      }
      if (!res.writableEnded) res.end();
      safeResolve(false);
    });
    curlProc.on('error', (err) => { clearTimeout(hardCap); cleanup(); endWith('Zen proxy error: ' + (err && err.message)); });
  });
}

async function proxyZenStream(body, res) {
  let model = body.model || CONFIG.defaultModel;
  for (let i = 0; i < ZEN_MODELS.length; i++) {
    const rateLimited = await streamOnce({ ...body, model }, res);
    if (!rateLimited) return;
    if (i < ZEN_MODELS.length - 1) { model = nextModel(model); await zenSleep(700); }
    else if (!res.writableEnded) { res.write('data: {"error":"Zen rate limit: лимит исчерпан на всех моделях."}\n\n'); res.end(); }
  }
}

// ═══════════════════════════════════════════════════════════════════
//  EMBEDDED HTTP SERVER
// ═══════════════════════════════════════════════════════════════════
let embeddedServer = null;

function startEmbeddedServer() {
  if (embeddedServer) return;

  const HUB_BIND_HOST = process.env.ZEN_BIND_HOST || '127.0.0.1';
  const HUB_REMOTE_TOKEN = String(process.env.ZEN_REMOTE_TOKEN || '');
  // Collection mode keeps agent/MCP in Core and serves AIN from an external UI tree.
  const CORE_ONLY = process.env.ZEN_CORE_ONLY === '1';
  const STATIC_UI_ROOT = process.env.ZEN_UI_DIR ? path.resolve(process.env.ZEN_UI_DIR) : __dirname;
  const HUB_STATE_FILE = path.join(os.homedir(), '.zen_agent_hub_state.json');
  const HUB_PTY_SESSIONS = new Map();
  const HUB_WEB_RUNS = new Map();
  const HUB_PTY_TTL_MS = Math.max(60_000, parseInt(process.env.ZEN_PTY_TTL_MS || '600000', 10) || 600000);
  // Open the full Hub only once after this Node process has successfully bound a port.
  // Disable for headless/automated use with ZEN_OPEN_BROWSER=0.
  const HUB_AUTO_OPEN_BROWSER = process.env.ZEN_OPEN_BROWSER !== '0';
  let hubBrowserOpenAttempted = false;
  const openHubInBrowser = targetUrl => {
    if (!HUB_AUTO_OPEN_BROWSER || hubBrowserOpenAttempted) return;
    hubBrowserOpenAttempted = true;
    let command, args;
    if (PLATFORM.isTermux) { command = 'termux-open-url'; args = [targetUrl]; }
    else if (process.platform === 'win32') { command = process.env.COMSPEC || 'cmd.exe'; args = ['/c', 'start', '', targetUrl]; }
    else if (process.platform === 'darwin') { command = 'open'; args = [targetUrl]; }
    else { command = 'xdg-open'; args = [targetUrl]; }
    try {
      const child = spawn(command, args, { detached: true, stdio: 'ignore', windowsHide: true });
      child.unref();
      child.on('error', () => console.log(c(`ℹ️ Не удалось автоматически открыть браузер. Открой: ${targetUrl}`, 'gray')));
    } catch { console.log(c(`ℹ️ Открой браузер вручную: ${targetUrl}`, 'gray')); }
  };
  const HUB_TOOL_DEFS = [
    { id: '_terminal', name: 'Terminal', cmd: '', color: '#58a6ff', icon: '>_', installed: true },
    { id: 'opencode', name: 'OpenCode', cmd: 'opencode', color: '#00d4aa', icon: 'OC' },
    { id: 'ccb', name: 'Claude Code', cmd: 'ccb', color: '#d97706', icon: 'CB' },
    { id: 'koda', name: 'Koda', cmd: 'koda', color: '#8b5cf6', icon: 'KD' },
    { id: 'openclaude', name: 'OpenClaude', cmd: 'openclaude', color: '#06b6d4', icon: 'OC' },
    { id: 'openrouter', name: 'OpenRouter CLI', cmd: 'openrouter', color: '#6366f1', icon: 'OR' },
    { id: 'qwen', name: 'Qwen Code', cmd: 'qwen', color: '#ef4444', icon: 'QW' },
    { id: 'http-server', name: 'HTTP Server', cmd: 'http-server', color: '#22c55e', icon: 'HS' },
    // This is intentionally a route, not a second child copy of this agent.
    { id: 'agent-web', name: 'AIN Agent', cmd: '', color: '#f59e0b', icon: 'AI', installed: true, launch: 'web' }
  ];

  const isLoopback = address => ['127.0.0.1', '::1', '::ffff:127.0.0.1', ''].includes(String(address || ''));
  const parseCookies = raw => Object.fromEntries(String(raw || '').split(';').map(x => x.trim()).filter(Boolean).map(x => {
    const i = x.indexOf('='); return i < 0 ? [x, ''] : [x.slice(0, i), decodeURIComponent(x.slice(i + 1))];
  }));
  const equalToken = value => {
    const a = Buffer.from(String(value || '')); const b = Buffer.from(HUB_REMOTE_TOKEN);
    return !!HUB_REMOTE_TOKEN && a.length === b.length && crypto.timingSafeEqual(a, b);
  };
  const hubAccess = (req, url) => {
    const local = isLoopback(req.socket && req.socket.remoteAddress);
    const cookies = parseCookies(req.headers.cookie);
    const supplied = req.headers['x-zen-token'] || req.headers.authorization?.replace(/^Bearer\s+/i, '') || cookies.zen_remote_token || url.searchParams.get('token');
    return { local, authorized: local || equalToken(supplied), suppliedByQuery: !local && equalToken(url.searchParams.get('token')) };
  };
  const json = (res, status, data, headers = {}) => {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers });
    res.end(JSON.stringify(data));
  };
  const text = (res, status, body, type = 'text/plain; charset=utf-8', headers = {}) => {
    res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', ...headers }); res.end(body);
  };
  const readBody = req => new Promise((resolve, reject) => {
    let size = 0; const parts = [];
    req.on('data', part => { size += part.length; if (size > 8 * 1024 * 1024) { reject(new Error('Request body exceeds 8 MB')); req.destroy(); return; } parts.push(part); });
    req.on('end', () => resolve(Buffer.concat(parts)));
    req.on('error', reject);
  });
  const readJson = async req => {
    const raw = await readBody(req); if (!raw.length) return {};
    try { return JSON.parse(raw.toString('utf8')); } catch { throw new Error('Invalid JSON body'); }
  };
  const loadHubState = () => { try { return JSON.parse(fs.readFileSync(HUB_STATE_FILE, 'utf8')); } catch { return { lastDirs: {}, recentPaths: [] }; } };
  const saveHubState = value => { try { fs.writeFileSync(HUB_STATE_FILE, JSON.stringify(value, null, 2), { mode: 0o600 }); } catch {} };
  const hubPath = raw => {
    const root = path.resolve(WORKSPACE_ROOT);
    const supplied = String(raw || '.').trim() || '.';
    const target = path.resolve(path.isAbsolute(supplied) ? supplied : path.join(root, supplied));
    const rel = path.relative(root, target);
    if (rel === '..' || rel.startsWith('..' + path.sep) || path.isAbsolute(rel)) return { error: 'Hub file manager is limited to the active agent workspace.' };
    return { path: target };
  };
  const installed = cmd => {
    if (!cmd) return true;
    try { execFileSync(process.platform === 'win32' ? 'where' : 'which', [cmd], { stdio: 'ignore', timeout: 2500 }); return true; } catch { return false; }
  };
  // Every model the agent can actually reach, from every configured provider.
  //
  // This used to return the three Zen models and nothing else - the hub's
  // dropdown showed three entries even with an OpenRouter key set, a GitHub
  // token present and a model downloaded into the runner. Those providers were
  // all implemented; they simply were not listed, so they could not be picked.
  //
  // A provider with no credentials is still listed, marked configured:false,
  // so the reason a model is unavailable is visible rather than the model
  // silently missing.
  const getHubModels = () => {
    const rows = [];
    const add = (id, name, providerId, providerName, providerIcon, extra = {}) => {
      if (!id) return;
      rows.push({
        id, name: name || id, providerId, providerName, providerIcon,
        ctx: extra.ctx ?? 128000, out: extra.out ?? 32000,
        desc: extra.desc || providerName, free: extra.free ?? false,
        configured: extra.configured ?? true,
        selected: id === currentModel && providerId === currentProvider
      });
    };

    for (const m of ZEN_MODELS) {
      add(m.id, m.name, 'zen', 'OpenCode Zen', '🟢',
        { free: true, desc: m.note || 'OpenCode Zen' });
    }

    // OpenRouter: whatever the catalogue returned - free and paid alike.
    // openRouterFreeModels is refreshed by fetchOpenRouterModels().
    const orConfigured = !!openRouterKey();
    for (const m of openRouterFreeModels) {
      add(m.id, m.name, 'openrouter', 'OpenRouter', '🟣', {
        free: String(m.id).endsWith(':free'),
        ctx: Number(m.ctx) || 128000,
        desc: orConfigured ? 'OpenRouter' : 'OpenRouter — нужен ключ (/key)',
        configured: orConfigured
      });
    }

    const ghConfigured = !!githubModelsToken();
    for (const id of GITHUB_MODELS) {
      add(id, id, 'github', 'GitHub Models', '🐙', {
        desc: ghConfigured ? 'GitHub Models' : 'GitHub Models — нужен GITHUB_TOKEN',
        configured: ghConfigured
      });
    }

    const hfConfigured = !!huggingFaceToken();
    for (const id of HUGGINGFACE_MODELS) {
      add(id, id, 'huggingface', 'Hugging Face', '🤗', {
        desc: hfConfigured ? 'Hugging Face' : 'Hugging Face — нужен HF_TOKEN',
        configured: hfConfigured
      });
    }

    // Models downloaded into the runner and served by a local engine. These
    // have no quota and no rate limit at all, which is the whole point of
    // having them, so they must be selectable.
    try {
      const local = typeof localAi.listModels === 'function' ? localAi.listModels() : [];
      for (const m of (Array.isArray(local) ? local : [])) {
        const id = m.id || m.name || m.file;
        add(id, m.name || id, 'local', 'Local AI', '💾', {
          free: true, desc: m.engine ? `Локальная модель (${m.engine})` : 'Локальная модель',
          configured: true
        });
      }
    } catch {}

    // A model chosen from the CLI that no catalogue lists - a paid OpenRouter
    // id, a freshly pulled local file - must not vanish from the dropdown.
    if (currentModel && !rows.some(m => m.id === currentModel && m.providerId === currentProvider)) {
      rows.unshift({
        id: currentModel, name: currentModel,
        providerId: currentProvider, providerName: providerDisplayName(),
        providerIcon: '⭐', ctx: 128000, out: 32000,
        desc: 'выбрана сейчас', free: false, configured: true, selected: true
      });
    }
    return rows;
  };
  const safeTerminalId = value => /^[A-Za-z0-9_.-]{1,80}$/.test(String(value || '')) ? String(value) : null;
  const ptySend = (session, message) => {
    const payload = JSON.stringify(message);
    for (const client of [...session.clients]) {
      if (client.readyState === 1) client.send(payload); else session.clients.delete(client);
    }
  };
  const schedulePtyClose = session => {
    if (!session || session.clients.size) return;
    if (session.closeTimer) clearTimeout(session.closeTimer);
    session.closeTimer = setTimeout(() => {
      if (session.clients.size) return;
      try { session.pty.kill(); } catch {}
      HUB_PTY_SESSIONS.delete(session.id);
    }, HUB_PTY_TTL_MS);
    session.closeTimer.unref?.();
  };
  const detachPtyClient = (session, ws) => {
    if (!session) return;
    session.clients.delete(ws); schedulePtyClose(session);
  };
  const attachPtyClient = (session, ws) => {
    if (session.closeTimer) { clearTimeout(session.closeTimer); session.closeTimer = null; }
    session.clients.add(ws);
    ws.send(JSON.stringify({ type: 'opened', id: session.id, resumed: !!session.resumed }));
    if (session.output) ws.send(JSON.stringify({ type: 'output', id: session.id, data: session.output, replay: true }));
  };
  const addWebLog = (name, role, content) => {
    const session = sessionStore.sessions[name] || (sessionStore.sessions[name] = { history: [], createdAt: new Date().toISOString() });
    session.webLog ||= [];
    session.webLog.push({ id: crypto.randomUUID(), role, content: String(content || ''), ts: Date.now() });
    if (session.webLog.length > 500) session.webLog = session.webLog.slice(-500);
    session.updatedAt = new Date().toISOString();
  };
  const displayHistory = session => {
    if (Array.isArray(session?.webLog) && session.webLog.length) return session.webLog;
    const source = Array.isArray(session?.history) ? session.history : [];
    return source.filter(m => (m.role === 'user' || m.role === 'assistant') && !/^Результат инструмента /i.test(String(m.content || '')))
      .map((m, index) => ({ id: `legacy_${index}`, role: m.role, content: String(m.content || '').replace(/\n?TOOL_JSON\s*:\s*\{[\s\S]*$/i, '').trim(), ts: Date.parse(session.updatedAt || session.createdAt || '') || Date.now() }))
      .filter(m => m.content);
  };
  const sessionView = name => {
    const valid = safeSessionName(name); const data = valid && sessionStore.sessions[valid];
    if (!data) return null;
    return { id: valid, title: data.title || valid, active: valid === activeSession, ts: Date.parse(data.updatedAt || data.createdAt || '') || Date.now(), model: data.model || currentModel, provider: data.provider || currentProvider, messages: displayHistory(data) };
  };
  const ensureSession = name => {
    const valid = safeSessionName(name); if (!valid) return { error: 'Session name may contain up to 48 letters, digits, _, - and .' };
    if (!sessionStore.sessions[valid]) sessionStore.sessions[valid] = { history: [], createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), provider: currentProvider, model: currentModel, workspace: WORKSPACE_ROOT, title: valid, webLog: [] };
    return { name: valid, data: sessionStore.sessions[valid] };
  };
  const safeWebModel = value => {
    const v = String(value || '').trim(); return /^[A-Za-z0-9._:/-]{1,160}$/.test(v) ? v : null;
  };
  const safeWebProvider = value => ['zen', 'openrouter', 'github', 'huggingface', 'local'].includes(String(value || '').trim()) ? String(value).trim() : null;
  // A run is over the moment its status is not one of these. Both sides need
  // the same list, and they used to disagree: the server wrote 'error' while
  // the browser only ever ended on completed/failed/aborted, so a failed run
  // was polled forever and the console just went quiet. `done` is now sent
  // explicitly so the client never has to re-derive it from a string.
  const WEB_RUN_LIVE = ['queued', 'running', 'awaiting_approval'];
  const webRunDone = run => !WEB_RUN_LIVE.includes(run.status);
  const webRunSummary = run => ({ id: run.id, session: run.session, status: run.status, done: webRunDone(run), createdAt: run.createdAt, finishedAt: run.finishedAt || null, answer: run.answer || null, error: run.error || null, approval: run.approval || null, events: (run.events || []).slice(-160) });
  // Completed runs are kept only long enough for a slow client to read the
  // answer. Without this the map grows for the whole session and every POST
  // walks it looking for stale entries.
  const HUB_WEB_RUN_TTL_MS = Math.max(60_000, parseInt(process.env.ZEN_WEB_RUN_TTL_MS || '900000', 10) || 900_000);
  const pruneWebRuns = () => {
    const now = Date.now();
    for (const [id, r] of HUB_WEB_RUNS) {
      if (!webRunDone(r)) continue;
      const at = Date.parse(r.finishedAt || r.createdAt || '') || now;
      if (now - at > HUB_WEB_RUN_TTL_MS) HUB_WEB_RUNS.delete(id);
    }
  };
  const launchWebRun = (sessionName, input, requestedModel, requestedProvider) => {
    const run = { id: 'run_' + crypto.randomUUID(), session: sessionName, status: 'queued', createdAt: new Date().toISOString(), startedMs: Date.now(), answer: null, error: null, approval: null, resolveApproval: null, events: [] };
    HUB_WEB_RUNS.set(run.id, run);
    // Claimed synchronously, before the loop is scheduled. agentBusy used to be
    // raised inside agentLoop, one setImmediate later, and in that window the
    // run was 'queued' with no owner - so a second POST arriving right then saw
    // "not busy, stale entry" and reaped a run that was about to start. The
    // browser then polled a run marked failed while the agent kept working on
    // it: exactly the "answered once, now silent" symptom.
    agentBusy = true;
    WEB_AGENT_RUN_CONTEXT = run;
    setImmediate(async () => {
      const oldStreamMode = CONFIG.streamMode;
      try {
        run.status = 'running';
        run.events.push({ id: 'evt_start', type: 'task_started', at: new Date().toISOString(), input: redactSecrets(String(input)).slice(0, 500) });
        const switched = switchSession(sessionName);
        if (switched.error) throw new Error(switched.error);
        if (requestedProvider) currentProvider = requestedProvider;
        if (requestedModel) currentModel = requestedModel;
        if (requestedProvider || requestedModel) saveHistory();
        addWebLog(sessionName, 'user', input); saveSessionStore();
        CONFIG.streamMode = false;
        WEB_AGENT_RUN_CONTEXT = run;
        const answer = await agentLoop(input);
        // A blank reply used to become "Задача завершена", which reads as
        // "I did the work" when in fact nothing happened at all - the hub
        // answered that to every message, including a bare "Ну". Say what is
        // actually true instead.
        run.answer = String(answer || '').trim() ||
          'Модель вернула пустой ответ — задача не выполнена. Переформулируйте запрос или смените модель в настройках.';
        addWebLog(sessionName, 'assistant', run.answer); saveSessionStore();
        run.status = 'completed';
        run.events.push({ id: 'evt_done', type: 'report', at: new Date().toISOString(), text: redactSecrets(run.answer).slice(0, 4000) });
      } catch (e) {
        run.error = redactSecrets(String(e && e.message || e)); run.status = 'error';
        run.events.push({ id: 'evt_error', type: 'error', at: new Date().toISOString(), text: run.error });
        try { addWebLog(sessionName, 'assistant', 'Ошибка: ' + run.error); saveSessionStore(); } catch {}
      } finally {
        CONFIG.streamMode = oldStreamMode;
        if (WEB_AGENT_RUN_CONTEXT === run) WEB_AGENT_RUN_CONTEXT = null;
        // The claim above must be released here as well, not only by
        // agentLoop's own finally: anything that throws before the loop is
        // entered - a bad session name, a provider that will not initialise -
        // never reaches that code, and the flag would stay raised, answering
        // 409 to every later message with no way back but a restart.
        agentBusy = false;
        run.approval = null; run.resolveApproval = null; run.finishedAt = new Date().toISOString();
        pruneWebRuns();
      }
    });
    return run;
  };

  const srv = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    // A file:// copy of the standalone UI has origin "null". Allow CORS only
    // for its local, text-only Zen bridge; no MCP, files, PTY or agent controls.
    const fileStandaloneZen = url.pathname.startsWith('/api/site/zen') && req.headers.origin === 'null';
    if (req.method === 'OPTIONS' && fileStandaloneZen) {
      res.writeHead(204, { 'Access-Control-Allow-Origin': 'null', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type', 'Access-Control-Max-Age': '600', 'Vary': 'Origin' });
      res.end(); return;
    }
    const access = hubAccess(req, url);
    if (!access.authorized) { json(res, 401, { error: 'Unauthorized. LAN use requires ZEN_REMOTE_TOKEN.' }); return; }
    if (fileStandaloneZen) { res.setHeader('Access-Control-Allow-Origin', 'null'); res.setHeader('Vary', 'Origin'); }
    if (access.suppliedByQuery && req.method === 'GET' && url.searchParams.has('token')) {
      // Store the token in a same-origin HttpOnly cookie, then remove it from
      // the visible URL so it is not retained in browser history/referrers.
      url.searchParams.delete('token');
      res.writeHead(302, { 'Set-Cookie': `zen_remote_token=${encodeURIComponent(HUB_REMOTE_TOKEN)}; Path=/; HttpOnly; SameSite=Strict`, 'Location': url.pathname + (url.search || '') });
      res.end(); return;
    }
    if (access.suppliedByQuery) res.setHeader('Set-Cookie', `zen_remote_token=${encodeURIComponent(HUB_REMOTE_TOKEN)}; Path=/; HttpOnly; SameSite=Strict`);
    if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type,X-Zen-Token,Authorization' }); res.end(); return; }

    try {
      // Core collection mode serves only AIN and its decomposed assets.
      // Hub and standalone have their own servers and proxy only their required APIs here.
      if (CORE_ONLY && req.method === 'GET' && url.pathname === '/collection-config.js') {
        text(res, 200, `window.ZEN_COLLECTION={corePort:${UI_PORT},hubPort:${parseInt(process.env.HUB_PORT || '8766', 10) || 8766},sitePort:${parseInt(process.env.SITE_PORT || '8767', 10) || 8767}};`, 'application/javascript; charset=utf-8'); return;
      }
      const staticFiles = CORE_ONLY ? {
        '/': ['ain/index.html', 'text/html; charset=utf-8'], '/agent': ['ain/index.html', 'text/html; charset=utf-8'],
        '/agent/': ['ain/index.html', 'text/html; charset=utf-8'], '/ain.html': ['ain/index.html', 'text/html; charset=utf-8']
      } : {
        '/hub': ['hub/index.html', 'text/html; charset=utf-8'], '/hub/': ['hub/index.html', 'text/html; charset=utf-8'], '/': ['hub/index.html', 'text/html; charset=utf-8'],
        '/agent': ['ain/index.html', 'text/html; charset=utf-8'], '/agent/': ['ain/index.html', 'text/html; charset=utf-8'], '/ain.html': ['ain/index.html', 'text/html; charset=utf-8'],
        '/site': ['standalone/index.html', 'text/html; charset=utf-8'], '/site/': ['standalone/index.html', 'text/html; charset=utf-8'], '/index.html': ['standalone/index.html', 'text/html; charset=utf-8'], '/standalone.html': ['standalone/index.html', 'text/html; charset=utf-8'],
        '/hub/app.js': ['hub/app.js', 'application/javascript; charset=utf-8'],
        '/hub/vendor/xterm.css': ['hub/vendor/xterm.css', 'text/css; charset=utf-8'], '/hub/vendor/xterm.js': ['hub/vendor/xterm.js', 'application/javascript; charset=utf-8'],
        '/hub/vendor/addon-fit.js': ['hub/vendor/addon-fit.js', 'application/javascript; charset=utf-8'], '/hub/vendor/addon-web-links.js': ['hub/vendor/addon-web-links.js', 'application/javascript; charset=utf-8']
      };
      if (CORE_ONLY && req.method === 'GET' && url.pathname.startsWith('/assets/')) {
        const rel = url.pathname.slice('/assets/'.length); const target = path.resolve(STATIC_UI_ROOT, 'ain', 'assets', rel);
        const allowed = path.resolve(STATIC_UI_ROOT, 'ain', 'assets');
        if (target.startsWith(allowed + path.sep) && fs.existsSync(target) && fs.statSync(target).isFile()) {
          const ext = path.extname(target).toLowerCase(); const type = ext === '.js' ? 'application/javascript; charset=utf-8' : ext === '.css' ? 'text/css; charset=utf-8' : 'application/octet-stream';
          text(res, 200, fs.readFileSync(target), type); return;
        }
      }
      if (req.method === 'GET' && staticFiles[url.pathname]) {
        const [file, type] = staticFiles[url.pathname]; text(res, 200, fs.readFileSync(path.join(STATIC_UI_ROOT, file)), type); return;
      }

      if (url.pathname === '/mcp/status' && req.method === 'GET') { json(res, 200, { tools: Object.keys(MCP_TOOLS).map(k => ({ name: k, description: MCP_TOOLS[k] })), connectedClients: 1, platform: PLATFORM, models: ZEN_MODELS, workspace: WORKSPACE_ROOT }); return; }
      if (url.pathname === '/mcp/call' && req.method === 'POST') {
        const body = await readJson(req); const result = await handleMCPTool(body.tool, body.args || {}); json(res, 200, { success: !(result && result.error), result, error: result?.error || undefined }); return;
      }
      // Narrow bridge for standalone/index.html. It deliberately exposes
      // Zen text chat only and accepts no tool calls, workspace paths or keys.
      if (url.pathname === '/api/site/zen/models' && req.method === 'GET') {
        json(res, 200, { success: true, models: ZEN_MODELS }); return;
      }
      if (url.pathname === '/api/site/zen' && req.method === 'POST') {
        const body = await readJson(req);
        const requested = String(body.model || '').trim();
        const model = ZEN_MODELS.some(m => m.id === requested) ? requested : CONFIG.defaultModel;
        const source = Array.isArray(body.messages) ? body.messages : [];
        const messages = source.slice(-24).map(item => ({
          role: ['system', 'user', 'assistant'].includes(item?.role) ? item.role : 'user',
          content: String(item?.content || '').slice(0, 100000)
        })).filter(item => item.content);
        if (!messages.length) { json(res, 400, { error: 'messages are required' }); return; }
        // Reasoning-capable Zen models can consume a tiny budget before emitting
        // any visible answer. Keep Site requests large enough for a final response.
        const maxTokens = Math.max(512, Math.min(8192, parseInt(body.max_tokens || '4096', 10) || 4096));
        const temperature = Math.max(0, Math.min(2, Number(body.temperature ?? 0.7) || 0.7));
        if (body.stream) { await proxyZenStream({ model, messages, max_tokens: maxTokens, temperature, siteMode: true }, res); return; }
        const result = await proxyZenChat({ model, messages, max_tokens: maxTokens, temperature, siteMode: true });
        const textResult = result?.choices?.[0]?.message?.content || '';
        if (!textResult) throw new Error('Zen returned an empty response');
        json(res, 200, { success: true, text: textResult, model: result?._model || model, usage: result?.usage || {} }); return;
      }
      // Narrow Site bridge for Core-held online provider tokens. The Site/APK
      // receives only text and usage; it never receives or stores a token.
      if (url.pathname === '/api/site/provider-chat' && req.method === 'POST') {
        const body = await readJson(req); const provider = ['openrouter', 'github', 'huggingface'].includes(String(body.provider || '')) ? String(body.provider) : null;
        if (!provider) { json(res, 400, { error: 'provider must be openrouter, github or huggingface' }); return; }
        if (provider === 'openrouter' && !openRouterKey()) { json(res, 400, { error: 'OpenRouter key is not configured in Core. Set it locally with /key; never paste it into Site.' }); return; }
        if (provider === 'github' && !githubModelsToken()) { json(res, 400, { error: 'GitHub token is not configured in Core. Set GITHUB_TOKEN/GITHUB_MODELS_TOKEN with models:read.' }); return; }
        if (provider === 'huggingface' && !huggingFaceToken()) { json(res, 400, { error: 'Hugging Face token is not configured in Core. Set HF_TOKEN/HUGGINGFACE_TOKEN.' }); return; }
        const requested = safeWebModel(body.model); const source = Array.isArray(body.messages) ? body.messages : [];
        const messages = source.slice(-24).map(item => ({ role: ['system', 'user', 'assistant'].includes(item?.role) ? item.role : 'user', content: String(item?.content || '').slice(0, 100000) })).filter(item => item.content);
        if (!messages.length) { json(res, 400, { error: 'messages are required' }); return; }
        let result;
        if (provider === 'openrouter') {
          const model = requested || CONFIG.openRouterModel || 'openrouter/free';
          const payload = { model, messages, temperature: Math.max(0, Math.min(2, Number(body.temperature ?? CONFIG.temperature) || CONFIG.temperature)), max_tokens: Math.max(16, Math.min(8192, parseInt(body.max_tokens || '2048', 10) || 2048)), stream: false };
          const data = await openRouterRequest(payload); const msg = data.choices?.[0]?.message || {}; const content = Array.isArray(msg.content) ? msg.content.map(item => item.text || '').join('') : String(msg.content || '');
          result = { text: content, model: data.model || model, usage: data.usage || {} };
        } else {
          const model = requested || (provider === 'github' ? 'openai/gpt-4.1' : 'openai/gpt-oss-120b:cerebras');
          const data = await callCompatibleProvider(provider, messages, model); result = { text: data.text, model: data.model || model, usage: data.usage || {} };
        }
        if (!result.text) { json(res, 502, { error: provider + ' returned an empty answer' }); return; }
        json(res, 200, { success: true, ...result, provider }); return;
      }
      // Compatibility endpoint for clients that intentionally need plain Zen chat.
      if (url.pathname === '/api/chat' && req.method === 'POST') {
        const data = await readJson(req);
        if (data.stream) await proxyZenStream(data, res);
        else json(res, 200, await proxyZenChat(data));
        return;
      }

      // ── Local AI: catalog, real runtime probes and model downloads. ──
      // Files are saved only under <Collection>/models; a downloaded model is
      // not presented as running until its localhost runtime answers a probe.
      if (url.pathname === '/api/local-ai/catalog' && req.method === 'GET') { json(res, 200, { success: true, storagePath: localAi.root(), catalog: localAi.catalog() }); return; }
      if (url.pathname === '/api/local-ai/status' && req.method === 'GET') { json(res, 200, await localAi.status()); return; }
      if (url.pathname === '/api/local-ai/config' && req.method === 'GET') { json(res, 200, { success: true, ...localAi.publicConfig() }); return; }
      if (url.pathname === '/api/local-ai/config' && req.method === 'POST') {
        const body = await readJson(req); json(res, 200, { success: true, ...localAi.configure(body) }); return;
      }
      if (url.pathname === '/api/local-ai/models' && req.method === 'GET') { json(res, 200, { success: true, storagePath: localAi.root(), models: localAi.listModels() }); return; }
      if (url.pathname === '/api/local-ai/runtimes' && req.method === 'GET') { json(res, 200, { success: true, storagePath: localAi.runtimeRoot(), runtimes: localAi.listRuntimes(), termuxPrefix: PLATFORM.isTermux ? (process.env.PREFIX || null) : null }); return; }
      if (url.pathname === '/api/local-ai/runtimes/catalog' && req.method === 'GET') { json(res, 200, { success: true, runtimes: await localAi.runtimeCatalog() }); return; }
      if (url.pathname === '/api/local-ai/runtimes/downloads' && req.method === 'POST') {
        const body = await readJson(req); const task = await localAi.startRuntimeDownload(body); json(res, 202, { success: true, task }); return;
      }
      if (url.pathname === '/api/local-ai/runtimes/install-termux' && req.method === 'POST') {
        const body = await readJson(req); json(res, 200, localAi.installRuntimeToTermux(body.runtimeId, PLATFORM.isTermux ? (process.env.PREFIX || '') : '')); return;
      }
      if (url.pathname === '/api/local-ai/downloads' && req.method === 'POST') {
        const body = await readJson(req); const task = localAi.startDownload(body); json(res, 202, { success: true, task }); return;
      }
      const localDownloadMatch = url.pathname.match(/^\/api\/local-ai\/downloads\/((?:download|runtime)_[A-Za-z0-9-]+)$/);
      if (localDownloadMatch && req.method === 'GET') {
        const task = localAi.task(localDownloadMatch[1]); if (!task) { json(res, 404, { error: 'Local model download not found in this Core session.' }); return; }
        json(res, 200, { success: true, task }); return;
      }
      if (url.pathname === '/api/local-ai/chat' && req.method === 'POST') {
        const body = await readJson(req); json(res, 200, await localAi.chat(body)); return;
      }
      // One button for "use a local model instead": fetches llama.cpp if it is
      // missing, fetches the weights if they are missing, starts the server,
      // and only then switches the agent over. Doing it in three separate
      // calls from the UI meant a half-finished state on any failure.
      if (url.pathname === '/api/presets' && req.method === 'GET') {
        const all = allPresets();
        json(res, 200, { success: true, active: PRESETS.active,
          presets: Object.entries(all).map(([id, p]) => ({
            id, label: p.label, builtIn: !!p.builtIn, on: PRESETS.active.includes(id), text: p.text })) });
        return;
      }
      if (url.pathname === '/api/presets' && req.method === 'POST') {
        const body = await readJson(req);
        if (body.save) {
          const id = String(body.id || '').trim();
          if (!/^[\w-]{2,32}$/.test(id)) { json(res, 400, { error: 'id: 2-32 символа, буквы, цифры, _ и -' }); return; }
          if (!String(body.text || '').trim()) { json(res, 400, { error: 'Нужен text' }); return; }
          PRESETS.custom[id] = String(body.text).trim();
          savePresets();
          setPresetActive(id, body.on !== false);
          json(res, 200, { success: true, active: PRESETS.active }); return;
        }
        const r = setPresetActive(String(body.id || ''), body.on !== false);
        if (r.error) { json(res, 400, r); return; }
        json(res, 200, { success: true, ...r }); return;
      }
      if (url.pathname === '/api/local-ai/prepare' && req.method === 'POST') {
        const body = await readJson(req);
        if (typeof localAi.prepare !== 'function') { json(res, 400, { error: 'local-ai module not installed' }); return; }
        const result = await localAi.prepare(body);
        if (result && result.success) {
          currentProvider = 'local';
          currentModel = result.modelId || currentModel;
          saveHistory();
        }
        json(res, result && result.success ? 200 : 400, { ...result, provider: currentProvider, model: currentModel });
        return;
      }
      if (url.pathname === '/api/local-ai/stop' && req.method === 'POST') {
        if (typeof localAi.stop !== 'function') { json(res, 400, { error: 'local-ai module not installed' }); return; }
        json(res, 200, { success: true, ...(await localAi.stop()) }); return;
      }
      // ── Termux:API capability status for the hybrid APK/Site. ──
      if (url.pathname === '/api/termux/status' && req.method === 'GET') { json(res, 200, { success: true, ...termuxApiStatus() }); return; }
      // ── Optional online voice through Hugging Face. Browser pages never see HF_TOKEN. ──
      if (url.pathname === '/api/voice/status' && req.method === 'GET') { json(res, 200, { success: true, huggingFaceConfigured: !!huggingFaceToken(), sttModel: 'openai/whisper-large-v3', ttsModel: 'facebook/mms-tts-rus' }); return; }
      if (url.pathname === '/api/voice/stt' && req.method === 'POST') {
        if (!huggingFaceToken()) { json(res, 400, { error: 'Online STT requires HF_TOKEN or HUGGINGFACE_TOKEN in Core environment.' }); return; }
        const body = await readJson(req); const encoded=String(body.audioBase64 || ''); if (!encoded || encoded.length > 6 * 1024 * 1024) { json(res, 400, { error: 'audioBase64 is required and must be at most 6 MB' }); return; }
        const audio=Buffer.from(encoded, 'base64'); const result=await huggingFaceStt(audio, String(body.mime || 'audio/webm'), safeWebModel(body.model) || 'openai/whisper-large-v3'); json(res, 200, { success: true, ...result }); return;
      }
      if (url.pathname === '/api/voice/tts' && req.method === 'POST') {
        if (!huggingFaceToken()) { json(res, 400, { error: 'Online TTS requires HF_TOKEN or HUGGINGFACE_TOKEN in Core environment.' }); return; }
        const body = await readJson(req); const textValue=String(body.text || '').trim(); if (!textValue || textValue.length > 4000) { json(res, 400, { error: 'text is required and must be at most 4000 characters' }); return; }
        const result=await huggingFaceTts(textValue, safeWebModel(body.model) || 'facebook/mms-tts-rus'); json(res, 200, { success: true, ...result }); return;
      }
      // Dynamic catalog avoids a stale hand-written list: it is fetched by Core,
      // so the GitHub token never reaches Site/APK WebView JavaScript.
      if (url.pathname === '/api/agent/github-models' && req.method === 'GET') {
        try { json(res, 200, { success: true, ...(await fetchGitHubModelsCatalog()) }); }
        catch (error) { json(res, 400, { error: error.message }); }
        return;
      }
      // ── Agent Console provider state. Keys are intentionally never accepted here. ──
      if (url.pathname === '/api/agent/settings' && req.method === 'GET') {
        const localConfig = localAi.publicConfig();
        json(res, 200, { success: true, provider: currentProvider, model: currentModel, openRouterConfigured: !!openRouterKey(), githubConfigured: !!githubModelsToken(), huggingFaceConfigured: !!huggingFaceToken(), githubRetiresOn: '2026-07-30', zenModels: ZEN_MODELS, githubModels: ['openai/gpt-4.1', 'openai/gpt-4o', 'meta/llama-3.3-70b-instruct'], huggingFaceModels: ['openai/gpt-oss-120b:cerebras', 'google/gemma-4-31B-it:cerebras', 'deepseek-ai/DeepSeek-R1:fastest'], localAi: { storagePath: localConfig.storagePath, activeEngine: localConfig.activeEngine, selectedModel: localConfig.selectedModel, configuredModel: localConfig.engines?.[localConfig.activeEngine]?.model || '' } }); return;
      }
      if (url.pathname === '/api/agent/settings' && req.method === 'POST') {
        const body = await readJson(req); const provider = safeWebProvider(body.provider); const model = body.model ? safeWebModel(body.model) : null;
        if (!provider) { json(res, 400, { error: 'provider must be zen, openrouter, github, huggingface or local' }); return; }
        if (provider === 'openrouter' && !openRouterKey()) { json(res, 400, { error: 'OpenRouter key is not configured. Set it locally with the CLI command /key; never paste it into this web console.' }); return; }
        if (provider === 'github' && !githubModelsToken()) { json(res, 400, { error: 'GitHub Models token is not configured. Set GITHUB_TOKEN or GITHUB_MODELS_TOKEN in the Core environment; never paste it into this web console.' }); return; }
        if (provider === 'huggingface' && !huggingFaceToken()) { json(res, 400, { error: 'Hugging Face token is not configured. Set HF_TOKEN or HUGGINGFACE_TOKEN in the Core environment; never paste it into this web console.' }); return; }
        currentProvider = provider; if (model) currentModel = model; saveHistory();
        json(res, 200, { success: true, provider: currentProvider, model: currentModel, openRouterConfigured: !!openRouterKey(), localAi: localAi.publicConfig() }); return;
      }
      // ── AIN web-agent session API: same persistent store as the CLI agent. ──
      if (url.pathname === '/api/agent/sessions' && req.method === 'GET') {
        loadSessionStore();
        const sessions = listSessions().map(row => { const view = sessionView(row.name); return { ...row, id: row.name, title: view?.title || row.name, ts: view?.ts || Date.now(), webMessages: view?.messages?.length || 0 }; });
        json(res, 200, { success: true, active: activeSession, sessions }); return;
      }
      if (url.pathname === '/api/agent/sessions' && req.method === 'POST') {
        const body = await readJson(req); const ensured = ensureSession(body.name || body.id || crypto.randomUUID());
        if (ensured.error) { json(res, 400, ensured); return; }
        if (body.title) ensured.data.title = String(body.title).slice(0, 120);
        saveSessionStore(); json(res, 201, { success: true, session: sessionView(ensured.name) }); return;
      }
      const sessionMatch = url.pathname.match(/^\/api\/agent\/sessions\/([A-Za-z0-9а-яА-ЯёЁ._-]{1,48})$/);
      if (sessionMatch && req.method === 'GET') { loadSessionStore(); const view = sessionView(decodeURIComponent(sessionMatch[1])); view ? json(res, 200, { success: true, session: view }) : json(res, 404, { error: 'Session not found' }); return; }
      if (sessionMatch && req.method === 'DELETE') {
        const name = decodeURIComponent(sessionMatch[1]); loadSessionStore();
        if (!sessionStore.sessions[name]) { json(res, 404, { error: 'Session not found' }); return; }
        if (name === activeSession || agentBusy) { json(res, 409, { error: 'Cannot delete the active or running session' }); return; }
        delete sessionStore.sessions[name]; saveSessionStore(); json(res, 200, { success: true }); return;
      }
      if (url.pathname === '/api/agent/run' && req.method === 'POST') {
        const body = await readJson(req); const input = String(body.input || body.message || '').trim();
        if (!input) { json(res, 400, { error: 'input is required' }); return; }
        // A run is only genuinely active while the agent loop is running. If
        // the loop has finished but a run object was left in a live-looking
        // state - the old agentBusy leak, a crashed run, or an approval whose
        // browser tab went away - the entry is stale and holding the console
        // hostage with a 409 nobody can clear. Reap those first, then decide.
        const stale = [];
        for (const r of HUB_WEB_RUNS.values()) {
          if (!WEB_RUN_LIVE.includes(r.status)) continue;
          // The owner of the agent is never stale, whatever its status.
          if (WEB_AGENT_RUN_CONTEXT === r) continue;
          // Nor is a run that was claimed moments ago. The reaper used to key
          // off agentBusy alone, which is raised a tick after the run object
          // appears; a message sent inside that tick killed a healthy run.
          if (Date.now() - (r.startedMs || Date.parse(r.createdAt) || 0) < 5000) continue;
          r.status = 'error';
          r.error = r.error || 'Прошлый запуск не завершился корректно и был сброшен.';
          r.approval = null;
          if (typeof r.resolveApproval === 'function') { try { r.resolveApproval('no'); } catch {} }
          r.resolveApproval = null;
          r.finishedAt = new Date().toISOString();
          stale.push(r.id);
        }
        if (stale.length) auditEvent('web_run_reaped', { runs: stale });

        if (agentBusy) {
          json(res, 409, {
            error: 'The agent is already busy. Continue/correct the active task first.',
            hint: 'Останови текущую задачу кнопкой стоп, либо POST /api/agent/reset, если она зависла.',
            activeRun: WEB_AGENT_RUN_CONTEXT ? WEB_AGENT_RUN_CONTEXT.id : null
          });
          return;
        }
        const ensured = ensureSession(body.session || body.sessionId || activeSession || 'default');
        if (ensured.error) { json(res, 400, ensured); return; }
        const model = body.model ? safeWebModel(body.model) : null;
        const provider = body.provider ? safeWebProvider(body.provider) : currentProvider;
        if (body.model && !model) { json(res, 400, { error: 'Invalid model id' }); return; }
        if (!provider) { json(res, 400, { error: 'Invalid provider' }); return; }
        if (provider === 'openrouter' && !openRouterKey()) { json(res, 400, { error: 'OpenRouter key is not configured. Set it locally with the CLI command /key; never paste it into this web console.' }); return; }
        if (provider === 'github' && !githubModelsToken()) { json(res, 400, { error: 'GitHub Models token is not configured. Set GITHUB_TOKEN or GITHUB_MODELS_TOKEN in the Core environment; never paste it into this web console.' }); return; }
        if (provider === 'huggingface' && !huggingFaceToken()) { json(res, 400, { error: 'Hugging Face token is not configured. Set HF_TOKEN or HUGGINGFACE_TOKEN in the Core environment; never paste it into this web console.' }); return; }
        const run = launchWebRun(ensured.name, input, model, provider); json(res, 202, { success: true, run: webRunSummary(run) }); return;
      }
      // Escape hatch. Without it a stuck run can only be cleared by killing
      // the process - which on a runner means losing the whole session.
      if (url.pathname === '/api/agent/reset' && req.method === 'POST') {
        const cleared = [];
        abortRequested = true;
        try { activeProviderAbort?.(); } catch {}
        for (const r of HUB_WEB_RUNS.values()) {
          if (!['queued', 'running', 'awaiting_approval'].includes(r.status)) continue;
          if (typeof r.resolveApproval === 'function') { try { r.resolveApproval('no'); } catch {} }
          r.resolveApproval = null;
          r.approval = null;
          r.status = 'error';
          r.error = 'Запуск сброшен вручную через /api/agent/reset.';
          r.finishedAt = new Date().toISOString();
          cleared.push(r.id);
        }
        const wasBusy = agentBusy;
        agentBusy = false;
        WEB_AGENT_RUN_CONTEXT = null;
        pendingConfirmation = null;
        // Lower the abort flag again. It is raised above to stop whatever is
        // running, and agentLoop clears it on entry - but a reset issued while
        // nothing was running left it raised, and the next task then died at
        // its very first step with "Задача остановлена пользователем", which
        // reads as the agent ignoring the message.
        abortRequested = false;
        activeProviderAbort = null;
        pruneWebRuns();
        setRunPhase('user-control', 'сброшено пользователем');
        auditEvent('web_agent_reset', { wasBusy, cleared });
        json(res, 200, { success: true, wasBusy, cleared, message: 'Агент свободен. Можно отправлять новую задачу.' });
        return;
      }

      const runMatch = url.pathname.match(/^\/api\/agent\/run\/(run_[A-Za-z0-9-]+)(?:\/(approve|abort))?$/);
      if (runMatch) {
        const run = HUB_WEB_RUNS.get(runMatch[1]); if (!run) { json(res, 404, { error: 'Run not found or expired' }); return; }
        if (!runMatch[2] && req.method === 'GET') { json(res, 200, { success: true, run: webRunSummary(run) }); return; }
        if (runMatch[2] === 'approve' && req.method === 'POST') {
          const body = await readJson(req); if (run.status !== 'awaiting_approval' || typeof run.resolveApproval !== 'function') { json(res, 409, { error: 'No approval is pending' }); return; }
          const allow = body.decision === 'allow'; const resolve = run.resolveApproval; run.resolveApproval = null; run.approval = null; run.status = 'running'; resolve(allow ? 'yes' : 'no'); json(res, 200, { success: true, decision: allow ? 'allow' : 'deny' }); return;
        }
        if (runMatch[2] === 'abort' && req.method === 'POST') {
          abortRequested = true; try { activeProviderAbort?.(); } catch {}
          if (run.resolveApproval) {
            const resolve = run.resolveApproval;
            run.resolveApproval = null;
            // Clear the pending approval and leave 'awaiting_approval', or the
            // run reports that it is still waiting on the user after they have
            // already stopped it - and a client polling status never sees the
            // stop take effect.
            run.approval = null;
            run.status = 'running';
            resolve('no');
          }
          webRunEvent('abort_requested', { at: new Date().toISOString() });
          json(res, 200, { success: true }); return;
        }
      }

      // ── Hub dashboard / workspace-only file manager endpoints. ──
      if (url.pathname === '/api/tools' && req.method === 'GET') { json(res, 200, { success: true, tools: HUB_TOOL_DEFS.filter(t => t.id !== '_terminal').map(t => ({ ...t, installed: t.launch ? true : installed(t.cmd), version: null })) }); return; }
      if (url.pathname === '/api/info' && req.method === 'GET') {
        const state = loadHubState(); const address = String(req.socket.remoteAddress || '').replace(/^::ffff:/, '');
        // Session clock. A GitHub runner is killed at six hours with no
        // warning, so "how long left" is the difference between finishing a
        // task and losing it. SESSION_LIMIT_MS is set by the workflow;
        // uptime falls back to this process's own age.
        const limitMs = parseInt(process.env.SESSION_LIMIT_MS || '0', 10) || 0;
        const startedMs = parseInt(process.env.SESSION_STARTED_MS || '0', 10) || (Date.now() - Math.round(process.uptime() * 1000));
        const elapsedMs = Date.now() - startedMs;
        json(res, 200, { home: WORKSPACE_ROOT, workspace: WORKSPACE_ROOT, platform: process.platform, mode: access.local ? 'local' : 'remote', ip: address, state, terminalAvailable: !!nodePty && !!WebSocketServer, terminalTtlMs: HUB_PTY_TTL_MS,
          session: { startedMs, elapsedMs, limitMs, remainingMs: limitMs ? Math.max(0, limitMs - elapsedMs) : null },
          // Which commit is actually serving this page. The hub is served by
          // the agent, not by the APK, so a stale session keeps showing old
          // UI long after a fix is merged - and there was no way to tell.
          build: agentBuildInfo(),
          // What the single key resolved to. Never the values themselves.
          keys: symbiosisKeyReport() }); return;
      }
      if (url.pathname === '/api/path-history' && req.method === 'GET') { const state = loadHubState(); json(res, 200, { success: true, recentPaths: state.recentPaths || [] }); return; }
      if (url.pathname === '/api/path-history' && req.method === 'POST') { const body = await readJson(req); const p = hubPath(body.p); if (p.error) { json(res, 400, p); return; } const state = loadHubState(); state.recentPaths = [p.path, ...(state.recentPaths || []).filter(x => x !== p.path)].slice(0, 50); saveHubState(state); json(res, 200, { success: true, recentPaths: state.recentPaths }); return; }
      const lastDirMatch = url.pathname.match(/^\/api\/last-dir(?:\/([^/]+))?$/);
      if (lastDirMatch && req.method === 'GET') { const state = loadHubState(); json(res, 200, { success: true, dir: state.lastDirs?.[decodeURIComponent(lastDirMatch[1] || '')] || WORKSPACE_ROOT }); return; }
      if (url.pathname === '/api/last-dir' && req.method === 'POST') { const body = await readJson(req); const p = hubPath(body.dir); if (p.error) { json(res, 400, p); return; } const state = loadHubState(); state.lastDirs ||= {}; state.lastDirs[String(body.toolId || '_terminal').slice(0, 80)] = p.path; saveHubState(state); json(res, 200, { success: true }); return; }
      if (url.pathname === '/api/storages' && req.method === 'GET') { json(res, 200, { success: true, storages: [{ id: 'local', name: 'Agent workspace', icon: '📁', root: WORKSPACE_ROOT }] }); return; }
      if (url.pathname === '/api/devices' && req.method === 'GET') { json(res, 200, { success: true, devices: [{ type: 'workspace', id: WORKSPACE_ROOT, name: 'Agent workspace', icon: '📁' }] }); return; }
      if ((url.pathname === '/api/storages/add' || url.pathname.startsWith('/api/adb/')) && ['POST', 'GET'].includes(req.method)) { json(res, 501, { success: false, error: 'Remote storage and ADB from the old Hub were not imported: this unified build deliberately exposes only the active agent workspace.' }); return; }
      if (url.pathname === '/api/browse' && req.method === 'GET') {
        if ((url.searchParams.get('backend') || 'local') !== 'local') { json(res, 400, { success: false, error: 'Only local agent workspace is available' }); return; }
        const target = hubPath(url.searchParams.get('path') || '.'); if (target.error) { json(res, 400, { success: false, error: target.error }); return; }
        const stat = fs.statSync(target.path); if (!stat.isDirectory()) throw new Error('Not a directory');
        const items = fs.readdirSync(target.path, { withFileTypes: true }).filter(e => e.name !== 'node_modules').map(e => {
          const full = path.join(target.path, e.name); let s = null; try { s = fs.statSync(full); } catch {}
          return { name: e.name, path: full, isDir: e.isDirectory(), size: s?.isFile() ? s.size : 0, mtime: s?.mtime || null };
        }).sort((a,b) => a.isDir === b.isDir ? a.name.localeCompare(b.name) : a.isDir ? -1 : 1);
        json(res, 200, { success: true, backend: 'local', path: target.path, parent: path.dirname(target.path), items }); return;
      }
      if (url.pathname === '/api/fs/upload' && req.method === 'POST') {
        // The historical Hub client follows this legacy raw upload with /api/fs/write
        // containing the path metadata. Consume it successfully instead of pretending it wrote a file.
        await readBody(req); json(res, 200, { success: true, deferred: true }); return;
      }
      if (url.pathname.startsWith('/api/fs/') && req.method === 'POST') {
        const op = url.pathname.slice('/api/fs/'.length); const body = await readJson(req);
        if ((body.backend || 'local') !== 'local') { json(res, 400, { success: false, error: 'Only local agent workspace is available' }); return; }
        const target = hubPath(body.path || body.oldPath); if (target.error) { json(res, 400, { success: false, error: target.error }); return; }
        if (op === 'mkdir') fs.mkdirSync(target.path, { recursive: true });
        else if (op === 'delete') fs.rmSync(target.path, { recursive: true, force: true });
        else if (op === 'rename') { const next = hubPath(body.newPath); if (next.error) { json(res, 400, { success: false, error: next.error }); return; } fs.renameSync(target.path, next.path); }
        else if (op === 'read') { json(res, 200, { success: true, content: fs.readFileSync(target.path, 'utf8') }); return; }
        else if (op === 'write') { const content = String(body.content || ''); fs.mkdirSync(path.dirname(target.path), { recursive: true }); fs.writeFileSync(target.path, content, 'utf8'); }
        else { json(res, 404, { success: false, error: 'Unknown file operation' }); return; }
        json(res, 200, { success: true }); return;
      }
      if (url.pathname === '/api/fs/download' && req.method === 'GET') { const target = hubPath(url.searchParams.get('path')); if (target.error) { json(res, 400, { error: target.error }); return; } const fileName = path.basename(target.path).replace(/[\r\n"]/g, '_'); res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Disposition': `attachment; filename="${fileName}"` }); fs.createReadStream(target.path).pipe(res); return; }
      if (url.pathname === '/api/models' && req.method === 'GET') { json(res, 200, { success: true, models: getHubModels(), selected: currentModel, provider: currentProvider }); return; }
      if (url.pathname === '/api/models/full' && req.method === 'GET') { const allModels = getHubModels(); json(res, 200, { success: true, models: allModels, providers: [{ id: 'zen', name: 'OpenCode Zen', icon: '🟢', free: true, configured: true, modelCount: allModels.filter(m => m.providerId === 'zen').length }, { id: 'openrouter', name: 'OpenRouter', icon: '🟣', free: false, configured: !!openRouterKey(), modelCount: allModels.filter(m => m.providerId === 'openrouter').length }], selected: currentModel, provider: currentProvider }); return; }
      if (url.pathname === '/api/models/select' && req.method === 'POST') { const body = await readJson(req); const model = safeWebModel(body.modelId); if (!model) { json(res, 400, { error: 'Invalid model id' }); return; } currentModel = model; if (body.providerId === 'openrouter' || body.providerId === 'zen') currentProvider = body.providerId; saveHistory(); json(res, 200, { success: true, selected: currentModel, provider: currentProvider }); return; }
      if (url.pathname === '/api/models/current' && req.method === 'GET') { json(res, 200, { success: true, model: currentModel, provider: currentProvider, apiKeyConfigured: !!openRouterKey() }); return; }
      if ((url.pathname === '/api/models/key' || url.pathname === '/api/models/apikey') && req.method === 'POST') { json(res, 400, { success: false, error: 'API keys are not accepted over the Hub web form. Use the local CLI command /key or a secure environment variable.' }); return; }

      json(res, 404, { error: 'Not found', path: url.pathname });
    } catch (e) { json(res, 500, { error: redactSecrets(String(e && e.message || e)) }); }
  });

  if (WebSocketServer && nodePty) {
    const wss = new WebSocketServer({ server: srv, path: '/ws', verifyClient: info => {
      try { const u = new URL(info.req.url, `http://${info.req.headers.host || 'localhost'}`); return hubAccess(info.req, u).authorized; } catch { return false; }
    } });
    wss.on('connection', ws => {
      let session = null;
      ws.on('message', raw => {
        let msg; try { msg = JSON.parse(String(raw)); } catch { return; }
        try {
          if (msg.type === 'open') {
            const id = safeTerminalId(msg.sessionId) || `term_${Date.now()}`;
            const existing = HUB_PTY_SESSIONS.get(id);
            if (existing) { session = existing; session.resumed = true; attachPtyClient(session, ws); return; }
            const cwd = hubPath(msg.cwd || '.'); if (cwd.error || !fs.existsSync(cwd.path) || !fs.statSync(cwd.path).isDirectory()) throw new Error(cwd.error || 'Terminal working directory does not exist');
            const tool = HUB_TOOL_DEFS.find(t => t.id === msg.toolId);
            if (tool?.launch === 'web') { ws.send(JSON.stringify({ type: 'route', target: '/agent' })); return; }
            const shell = process.platform === 'win32' ? (process.env.COMSPEC || 'cmd.exe') : (process.env.SHELL || '/bin/sh');
            const proc = nodePty.spawn(shell, [], { name: 'xterm-256color', cols: Math.max(20, Math.min(500, Number(msg.cols) || 120)), rows: Math.max(5, Math.min(200, Number(msg.rows) || 30)), cwd: cwd.path, env: { ...process.env, TERM: 'xterm-256color' } });
            session = { id, pty: proc, cwd: cwd.path, clients: new Set(), output: '', closeTimer: null, resumed: false };
            HUB_PTY_SESSIONS.set(id, session);
            proc.onData(data => { session.output = (session.output + data).slice(-262144); ptySend(session, { type: 'output', id: session.id, data }); });
            proc.onExit(({ exitCode }) => { ptySend(session, { type: 'exit', id: session.id, code: exitCode }); HUB_PTY_SESSIONS.delete(session.id); });
            attachPtyClient(session, ws);
            if (tool && tool.cmd) setTimeout(() => { try { proc.write(tool.cmd + '\r'); } catch {} }, 120);
            return;
          }
          if (!session) return;
          if (msg.type === 'input') { const data = String(msg.data || ''); if (data.length <= 32768) session.pty.write(data); }
          else if (msg.type === 'resize') session.pty.resize(Math.max(20, Math.min(500, Number(msg.cols) || 120)), Math.max(5, Math.min(200, Number(msg.rows) || 30)));
          else if (msg.type === 'close') detachPtyClient(session, ws);
        } catch (e) { if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'error', error: redactSecrets(String(e.message || e)) })); }
      });
      ws.on('close', () => detachPtyClient(session, ws));
    });
  } else console.log(c('⚠️ Hub xterm disabled: run npm install to install ws and node-pty.', 'yellow'));

  let portAttempts = 0;
  const listenMcp = () => srv.listen(UI_PORT, HUB_BIND_HOST, () => {
    console.log(c(`\n🌐 ${CORE_ONLY ? 'Agent Core' : 'Unified Agent Hub'}: http://${HUB_BIND_HOST}:${UI_PORT}`, 'green'));
    console.log(c(CORE_ONLY ? `   AIN: /agent | MCP: /mcp/call | Hub/Site are separate collection servers | Terminal WS: ${WebSocketServer && nodePty ? '/ws' : 'disabled'}` : `   Hub: /hub | AIN Agent: /agent | MCP: /mcp/call | Terminal WS: ${WebSocketServer && nodePty ? '/ws' : 'disabled'}`, 'gray'));
    console.log(c(`   Workspace: ${WORKSPACE_ROOT} | bind: ${HUB_BIND_HOST}`, 'gray'));
    if (!isLoopback(HUB_BIND_HOST) && !HUB_REMOTE_TOKEN) console.log(c('⚠️ LAN bind without ZEN_REMOTE_TOKEN is refused by API auth; set a strong token.', 'yellow'));
    openHubInBrowser(`http://127.0.0.1:${UI_PORT}${CORE_ONLY ? '/agent' : '/hub'}`);
  });
  srv.on('error', err => {
    if (!CORE_ONLY && err.code === 'EADDRINUSE' && portAttempts++ < 10) { const oldPort = UI_PORT; UI_PORT++; console.log(c(`⚠️ MCP-порт ${oldPort} занят; пробую ${UI_PORT}.`, 'yellow')); setTimeout(listenMcp, 20); }
    else { console.log(c('⚠️ MCP server error: ' + err.message, 'yellow')); if (CORE_ONLY) process.exitCode = 1; }
  });
  listenMcp();
  embeddedServer = srv;
}

// ═══════════════════════════════════════════════════════════════════
//  NETWORK DIAGNOSTICS
// ═══════════════════════════════════════════════════════════════════
async function checkNetwork() {
  console.log(c('🔍 Проверка сети...', 'gray'));
  const checks = [];

  // Check opencode.ai via curl
  let reachable = false;
  try {
    const t0 = Date.now();
    const out = execSync(`"${curlPath()}"${curlProxyArgs()} -s --connect-timeout 5 --max-time 8 -o /dev/null -w "%{http_code}" https://opencode.ai/zen/v1/models`, { encoding: 'utf8', timeout: 10000 });
    if (out.includes('200')) {
      checks.push(`${c('●', 'green')} opencode.ai доступен (${Date.now() - t0}ms)`);
      reachable = true;
    }
  } catch (e) {}
  if (!reachable) {
    // Try without ipv4
    try {
      const out = execSync(`"${curlPath()}"${CONFIG.proxy ? ` -x "${CONFIG.proxy}"` : ''} -s --connect-timeout 5 --max-time 8 -o /dev/null -w "%{http_code}" https://opencode.ai/zen/v1/models`, { encoding: 'utf8', timeout: 10000 });
      if (out.includes('200')) { checks.push(`${c('●', 'green')} opencode.ai доступен (без --ipv4)`); reachable = true; CONFIG.curlIpv4 = false; }
    } catch (e) {}
  }
  if (!reachable) {
    checks.push(`${c('○', 'red')} opencode.ai НЕДОСТУПЕН — проблема сети/блокировка`);
    checks.push(`  ${c('💡', 'yellow')} Wi‑Fi может блокировать opencode.ai. Включи VPN в Android-приложении.`);
    checks.push(`  ${c('💡', 'yellow')} VPN применяется к Termux автоматически, если Termux не добавлен в исключения / split tunneling.`);
    checks.push(`  ${c('💡', 'yellow')} После подключения повтори: /net. Прокси (/proxy) — только необязательная альтернатива.`);
  }

  // Check if proxy is set
  if (CONFIG.proxy) {
    checks.push(`${c('🔗', 'cyan')} Прокси: ${maskProxy(CONFIG.proxy)}`);
  }

  const w = Math.min(68, termWidth() - 4);
  box(checks, { width: w, title: ' Сеть ', style: 'single', color: 'gray' }).forEach(l => console.log(l));
}

const SYSTEM_PROMPT = `Ты — AI-ассистент с доступом к файлам и командам только через MCP-инструменты.

ОСОБЫЙ РЕЖИМ TERMUX / ANDROID:
- Рабочая папка MCP находится в общей памяти Android: /storage/emulated/0/...
- Внутренний каталог Termux ($HOME, обычно /data/data/com.termux/files/home) НЕ является папкой проекта. Никогда не ищи там файлы пользователя и не используй его как исходную директорию.
- Все ОТНОСИТЕЛЬНЫЕ пути инструментов автоматически относятся к текущей MCP-рабочей папке. Не предполагая её имя, сначала вызови workspace_info.
- Для поиска файлов используй list_dir или find_files. Не используй pwd, ls, find, pgrep или grep для поиска проекта во внутренней папке Termux.
- Если пользователь назвал путь /storage/emulated/0/..., передай его инструменту явно. Если нужного проекта нет в текущей папке, используй find_files с path:/storage/emulated/0 и ограниченной глубиной, затем set_workspace с найденной папкой.
- Для проекта Node сначала используй project_inspect. Для сервера используй process_start/process_logs/process_status/process_stop, а для HTTP — health_check или http_request. process_start всегда требует name, command и cwd. Никогда не запускай сервер через node server.js с символом &: такой вызов будет отклонён; также не используй nohup или disown. Для долгой работы используй monitor_start, а не бесконечный polling shell-командами.
- Встроенный MCP-сервер агента по умолчанию работает на 8765, а порт 3000 оставлен проекту. Если process_status показывает running:false, сначала вызови process_logs и прочитай lastLog. Не делай вывод о EADDRINUSE, работающем приложении или его API только по 404: проверяй body и точный ответ.
- Для SQLite используй sqlite_*; для постоянной интерактивной shell-сессии используй terminal_*; для .env используй env_list/env_set/env_delete и никогда не печатай секретные значения в финальном ответе.
- Никогда не используй fuser, lsof, netstat, pgrep/ps|grep или массовое завершение процессов для проверки порта: Android может блокировать /proc. Проверяй только процессы, зарегистрированные через process_start, и реальный HTTP-ответ.
- Для запуска остальных команд используй execute_command только после определения папки проекта. Всегда передавай ARG:cwd:абсолютный_путь_проекта или используй текущую рабочую папку MCP.
- Сеть: Android VPN работает ниже уровня Termux и не требует URL или настройки в агенте. Если сеть моделей недоступна, сообщи, что пользователь должен включить своё VPN-приложение Android и убрать Termux из исключений VPN. Прокси /proxy — лишь необязательная альтернатива; не запрашивай его сам.
- НИКОГДА не проси API key в обычном сообщении, не повторяй его, не записывай в .env/файлы проекта и не передавай в custom tools. Для OpenRouter key существует только UI-команда /key. Если ключ уже настроен, просто используй vision_analyze без упоминания секрета.
- Не применяй pkill node, killall node или другие массовые команды уничтожения процессов. Не объявляй сервер запущенным без реальной проверки ответа.
- Не оборачивай URL в Markdown. В shell-командах URL должен быть обычным текстом: http://127.0.0.1:3000/.

ДОСТУПНЫЕ MCP-ИНСТРУМЕНТЫ:
- workspace_info() — показать активную папку и правила путей
- set_workspace(path) — выбрать папку проекта в общей памяти Android
- project_inspect(path) — сначала проверь package.json, скрипты, зависимости и структуру проекта
- termux_info(), network_check() — диагностика Termux и доступа к серверу моделей через Android VPN
- tree_dir(path), list_dir(path), find_files(query, path), search_text(query, path), file_info(path)
- read_file(path), write_file(path, content), edit_file(path, old, new), append_file(path, content), delete_file(path), mkdir(path), copy_file(source, destination), move_file(source, destination)
- file_backup(path), file_diff(path, backup), archive_create(source, destination), archive_extract(archive, destination)
- process_start(name, command, cwd), process_status(name), process_logs(name, lines, follow_seconds), process_stop(name) — управляемые фоновые серверы и их реальные логи
- monitor_start(process_name, url, interval_seconds), monitor_list(), monitor_logs(id), monitor_stop(id) — локальный health-monitor и автоперезапуск
- terminal_create(id, cwd), terminal_write(id, input), terminal_read(id), terminal_list(), terminal_close(id) — постоянные локальные shell-сессии
- http_request(url, method), health_check(url), websocket_test(url, protocol, event, payload) — реальные HTTP/WebSocket-проверки
- npm_install(packages, cwd), npm_run(script, cwd), run_tests(), run_lint(), code_check(path), dependency_audit()
- sqlite_info(), sqlite_query(database, sql), sqlite_schema(database), sqlite_backup(database, destination) — локальная SQLite
- env_list(path), env_set(key, value), env_delete(key) — .env без показа секретных значений
- git_status(), git_diff(), git_branch(), git_log(), git_init(), git_commit(message) — Git без угадывания состояния
- GITHUB БЕЗ КЛОНИРОВАНИЯ — быстрее и не занимает диск:
  github_read({repo,path}), github_list({repo,path}), github_write({repo,path,content,message})
  github_commit_files({repo,files:[{path,content}],message}) — несколько файлов одним коммитом
  github_delete, github_search({query}), github_commits, github_branches, github_create_branch
  github_pr({head,title}), github_repo, github_my_repos, github_runs, github_run_workflow({workflow})
  Запись через github_write и github_commit_files — это СРАЗУ коммит в ветку;
  отдельный push не нужен. repo можно не указывать: берётся репозиторий сессии.
  Клонируй только когда нужно всё дерево: сборка, тесты, массовый рефакторинг.
- preset_list(), preset_set({id,on}), preset_save({id,text}) — постоянные указания пользователя
- git_clone(repo), git_pull(), git_push(branch) — работа с ЛЮБЫМ репозиторием, не только текущим.
  Ты НЕ ограничен текущей папкой: git_clone({"repo":"owner/name"}) кладёт репозиторий
  в work/<name>, затем set_workspace({"path":"<путь из ответа>"}) делает его рабочим.
  Учётные данные уже настроены — доступно всё, что видит токен. После правок: git_commit и git_push.
- image_info(path), ocr_image(path), vision_analyze(path, prompt, model), vision_ui_audit(path), vision_compare(path, path2) — изображения и скриншоты
- custom_tool_list(), custom_tool_create(name, description, code), custom_tool_inspect(name), custom_tool_run(name, tool_args), custom_tool_delete(name) — локальные само-созданные plugins
- subagent_list(), subagent_create(name, description, prompt), subagent_task(agent, prompt), subagent_delete(name) — isolated second-opinion subagents
- plugin_list(), plugin_create(name, description, code), plugin_inspect(name), plugin_tool_list(), plugin_tool_run(plugin, name, tool_args), plugin_provider_list(), plugin_delete(name) — lifecycle plugins
- capability_templates(), capability_list(), capability_create(name, template|code, runtime, description), capability_install(name), capability_run(name, args, background), capability_logs(name), capability_stop(name), capability_inspect(name), capability_delete(name) — САМОДЕЛЬНЫЕ ИНСТРУМЕНТЫ С РЕАЛЬНЫМ ДОСТУПОМ К СИСТЕМЕ: adb, RDP, GUI-скриншоты, Python-пакеты, фоновые задачи
- web_search(query) — Wikipedia; web_fetch(url) — прочитать любую страницу как текст
- open_url(url), clipboard_read(), clipboard_write(text), notify(title, content) — сеть и Android Termux:API
- execute_command(command, cwd, timeout) — только для команд, которым нет специального инструмента
- todo_list(), todo_add(text), todo_done(id), todo_remove(id) — постоянный план задач, привязанный к проекту

ТАБЛИЦЫ:
Когда в ответе сравниваются несколько объектов - файлы, модели, запуски,
ветки, варианты решения - оформляй markdown-таблицей, а не длинным списком.
Интерфейс её рисует, и на телефоне это читается заметно лучше.

| Что | Значение |
|---|---|
| файл | src/main.kt |

Одиночный факт таблицей не оформляй - для него хватит фразы.

ФОРМАТ ВЫЗОВА:
- В режиме OpenRouter тебе переданы нативные function tools. Вызывай их через API tool_calls; не печатай TOOL_JSON в обычном тексте.
- В режиме Zen используй строгий текстовый JSON fallback:
  TOOL_JSON:{"tool":"имя_инструмента","args":{"ключ":"значение"}}

Пример Zen fallback:
TOOL_JSON:{"tool":"workspace_info","args":{}}

Для многострочного content, объектов headers, массивов packages и булевых значений используй только TOOL_JSON. Старый формат TOOL:/ARG: поддерживается только для простых аргументов.

ПРИМЕР ПЕРЕД РАБОТОЙ:
TOOL_JSON:{"tool":"workspace_info","args":{}}

ВОПРОС О СРЕДЕ = ВСЕГДА ИНСТРУМЕНТ. ЭТО ГЛАВНОЕ ПРАВИЛО.
Любой вопрос о том, ГДЕ ты находишься, ЧТО вокруг тебя и В КАКОМ состоянии
проект, отвечается ВЫЗОВОМ ИНСТРУМЕНТА, а не рассуждением. У тебя есть
реальный доступ — не отвечай "я не совсем понял вопрос" и не говори, что ты
"просто в этом чате". Ты находишься в конкретной папке на конкретной машине,
и это можно посмотреть.

Вопрос пользователя               -> что вызвать
"где ты", "где ты открыт"          -> workspace_info
"твоя рабочая сессия где"          -> workspace_info, затем termux_info
"какая директория", "где мы"       -> workspace_info
"что в папке", "покажи файлы"      -> list_dir
"какой репозиторий", "что в гите"  -> git_status, затем git_log
"какая ветка"                      -> git_branch
"что на гитхабе", "какие репо"     -> git_status; для чужого репо git_clone
"что ты видишь", "что тут есть"    -> workspace_info + list_dir
"это запущено?", "сервер работает?"-> process_status, затем health_check

Если формулировка непонятна, но речь явно про папку, файлы, git, GitHub,
сессию или окружение — СНАЧАЛА вызови workspace_info и покажи факты, и только
потом, при необходимости, уточняй. Показать реальный путь всегда полезнее,
чем переспросить.

Отвечая про среду, называй КОНКРЕТИКУ из результата инструмента: полный путь,
имя ветки, число файлов. Ответ без конкретики означает, что ты не посмотрел.

КОГДА ИНСТРУМЕНТЫ НЕ НУЖНЫ:
Только если сообщение вообще не касается среды: приветствие ("Привет"),
короткая реплика ("Ну", "ок", "спасибо"), вопрос о том, что ты умеешь в
принципе, объяснение или совет по коду, который не требует смотреть файлы.
Тогда просто ответь текстом.

Никогда не отвечай пустотой: пустой ответ превращается в бессмысленное
"Задача завершена", и человек видит, будто ты его проигнорировал. Одна
короткая фраза лучше молчания.

ПРИМЕР СМЕНЫ ПРОЕКТА:
TOOL_JSON:{"tool":"set_workspace","args":{"path":"/storage/emulated/0/Alarms/месенджер"}}

ПРИМЕР БЕЗОПАСНОЙ ПРОВЕРКИ:
TOOL_JSON:{"tool":"health_check","args":{"url":"http://127.0.0.1:3000/","timeout":5000}}

ПЛАН И TODO:
- Для задачи из нескольких действий сначала вызови todo_list, затем todo_add для коротких реальных шагов.
- После успешного шага вызывай todo_done. Не создавай фиктивные задачи и не отмечай задачу готовой без результата инструмента.
- Перед изменением файла сначала прочитай его; после изменения проверь нужный результат.

ПРАВИЛА РАБОТЫ:
- Используй MCP-инструменты для файлов и команд, а не догадки.
- Если отсутствует критичное требование (цель приложения, путь проекта, технология, формат данных, риск удаления/перезаписи), сначала задай 1–3 коротких уточняющих вопроса. Не начинай инструменты, пока ответ меняет результат. Не спрашивай очевидное, если текущая MCP-папка и запрос уже однозначны.
- Для нетривиальной задачи сначала дай пользователю короткий публичный блок «🗒 План» из 2–4 шагов и явно назови допущение. После каждого важного результата сообщай короткое наблюдение или решение. Не раскрывай скрытые внутренние рассуждения: показывай только проверяемый план, факты и решения.
- Выполняй одну диагностическую операцию за раз. Проверяй код завершения, stdout и stderr перед следующим шагом.
- Никогда не утверждай, что прочитал package.json, знаешь зависимости, точку входа, порт или состояние сервера, пока текущая сессия не получила реальный результат соответствующего MCP-инструмента.
- Не выдумывай содержимое скриншота. Сначала вызови image_info/ocr_image для локальных фактов либо vision_analyze/vision_ui_audit для visual-анализа. Текстовая модель без vision не видит изображение. Если встроенных инструментов недостаточно, создай изолированный custom tool через custom_tool_create.
- Если ты выводишь JSON-объект с полями tool и args, это обязательно вызов инструмента, а не финальный ответ: после него дождись результата и продолжай задачу.
- Если путь не существует, не продолжай команду через && и не утверждай, что запуск удался.
- Если существующего инструмента действительно недостаточно, сначала вызови custom_tool_list. Затем создай local tool только через custom_tool_create. Код tool должен экспортировать module.exports = async (args, api) => ({...}) и использовать только api.readText/api.writeText/api.list/api.httpGet/api.imageInfo; require/process/import/eval запрещены. После создания вызови custom_tool_run и проверь результат.
- Для lifecycle поведения используй plugin_create. Plugin экспортирует синхронную factory module.exports = (context) => ({ systemPrompt, beforeModel, afterModel, beforeTool, afterTool, permission, event, tools, providers }). Hooks могут быть async, но factory — нет. Plugin не имеет require/process/import/eval. Не создавай plugin, если есть подходящий built-in tool.
- Для больших проектов создавай реальные законченные файлы; не оставляй заглушки, TODO, "реализовать позже" или оборванный код.
- Сначала inspect/read существующий файл. Для изменения используй точечный edit_file; не переписывай весь проект или весь файл без явной необходимости (новый файл, повреждённый файл или прямое требование пользователя).
- В web-console показывай только публичный краткий план, todo, блоки инструментов и финальный отчёт. Не выводи скрытые рассуждения.
- Пиши TOOL: и ARG: как есть, без Markdown.
- Отвечай на русском, кратко объясняя фактический результат.

${capabilitiesModule ? capabilitiesModule.CAPABILITY_PROMPT : ''}`;

function buildSystemPrompt() {
  const providerRule = currentProvider === 'openrouter'
    ? 'Провайдер OpenRouter: используй только переданные нативные function tools/tool_calls. Инструментальный JSON в тексте не нужен.'
    : currentProvider === 'github'
      ? 'Провайдер GitHub Models: используй переданные нативные function tools/tool_calls, если выбранная модель их поддерживает; иначе дай текстовый ответ без фейкового вызова.'
      : currentProvider === 'huggingface'
        ? 'Провайдер Hugging Face Inference Providers: используй нативные function tools/tool_calls только если модель/маршрутизатор их вернул; иначе дай текстовый ответ без фейкового вызова.'
        : currentProvider === 'local'
          ? 'Провайдер Local AI: локальный runtime может не поддерживать tool_calls. Не имитируй вызовы инструментов; если нужен инструмент, явно объясни ограничение.'
          : 'Провайдер Zen: нативные tools могут быть недоступны; используй TOOL_JSON fallback строго по схеме.';
  const clarifyRule = CONFIG.askClarifyingQuestions
    ? 'Уточнения включены: при критичной неоднозначности задай короткие вопросы до инструментов. В начале задачи максимум 10 вопросов суммарно; затем зафиксируй допущения и действуй.'
    : 'Уточнения выключены: действуй по разумным допущениям и явно перечисли их в публичном плане.';
  const modeRule = CONFIG.agentMode === 'build'
    ? 'Режим Build: можно выполнять изменения после permission/подтверждения.'
    : `Режим ${AGENT_MODES[CONFIG.agentMode].label}: только анализ, вопросы и план. Изменяющие tools заблокированы permission engine.`;
  const pluginPrompt = pluginSystemPrompts();
  // Live facts about the workspace, so "где ты открыт" has an answer already
  // in the prompt instead of being met with "я не совсем понял вопрос".
  //
  // The model was told its working directory but nothing about what is in it,
  // so a question about the environment looked to it like a question about
  // itself - and it answered with philosophy rather than a path. Reading a
  // directory listing costs nothing and turns that into a fact.
  // The repository the github_* tools default to. The code knew it and the
  // model did not, so with the github preset on it kept asking "укажите
  // owner/name" for a repository it was already pointed at.
  let repoFact = '';
  try {
    const repo = process.env.SYMBIOSIS_REPO || detectSessionRepo();
    if (repo) {
      repoFact = `\n- Репозиторий по умолчанию для github_*: ${repo} ` +
        `(аргумент repo можно не передавать — он подставится сам).`;
    } else if (PRESETS.active.includes('github')) {
      repoFact = `\n- Репозиторий по умолчанию не задан. Спроси owner/name ` +
        `или предложи задать SYMBIOSIS_REPO.`;
    }
  } catch {}

  let envFacts = '';
  try {
    const entries = fs.readdirSync(WORKSPACE_ROOT, { withFileTypes: true });
    const dirs = entries.filter(e => e.isDirectory() && !e.name.startsWith('.')).map(e => e.name);
    const files = entries.filter(e => e.isFile()).map(e => e.name);
    const isRepo = fs.existsSync(path.join(WORKSPACE_ROOT, '.git'));
    let branch = '';
    if (isRepo) {
      try {
        branch = fs.readFileSync(path.join(WORKSPACE_ROOT, '.git', 'HEAD'), 'utf8')
          .trim().replace(/^ref:\s*refs\/heads\//, '');
      } catch {}
    }
    envFacts =
      `\n\n${PRESETS.active.includes('github')
        ? 'ЛОКАЛЬНАЯ ПАПКА (справочно — работаешь ты через GitHub API, а не здесь):'
        : 'ЧТО СЕЙЧАС В РАБОЧЕЙ ПАПКЕ (снимок на момент запроса):'}\n` +
      `- Полный путь: ${WORKSPACE_ROOT}\n` +
      `- Папок: ${dirs.length}${dirs.length ? ' — ' + dirs.slice(0, 12).join(', ') + (dirs.length > 12 ? ', …' : '') : ''}\n` +
      `- Файлов: ${files.length}${files.length ? ' — ' + files.slice(0, 12).join(', ') + (files.length > 12 ? ', …' : '') : ''}\n` +
      `- Git-репозиторий: ${isRepo ? `да${branch ? `, ветка ${branch}` : ''}` : 'нет'}\n` +
      `Эти данные — снимок. Если пользователь спрашивает про содержимое или git, ` +
      `всё равно вызови инструмент: снимок мог устареть, а инструмент даст точный ответ.` +
      // Which tool that is depends on the active preset. Naming list_dir and
      // git_status unconditionally here contradicted the github preset three
      // lines further down - and the nearer, more concrete instruction won:
      // the model answered "в этой папке нет git-репозитория" about a
      // repository it was supposed to read over the API.
      (PRESETS.active.includes('github')
        ? ` В режиме работы через GitHub API это github_list / github_commits / github_read, а не локальные list_dir / git_status.`
        : ` Обычно это list_dir / git_status.`);
  } catch (e) {
    envFacts = `\n\nРАБОЧАЯ ПАПКА ${WORKSPACE_ROOT} сейчас не читается (${String(e.message || e).slice(0, 80)}). ` +
      `На вопрос о папке вызови workspace_info и скажи об этой ошибке прямо.`;
  }
  const longRule = CONFIG.longTaskMode
    ? `Долгая задача включена: разрешено до ${agentStepLimit()} шагов и длительные команды. Для серверов используй process_start/process_logs, регулярно давай checkpoint и принимай /correct или /abort.`
    : 'Обычный лимит задачи: используй короткие безопасные шаги; для многочасовой работы пользователь включает /long on.';
  return SYSTEM_PROMPT + `\n\nТЕКУЩИЙ КОНТЕКСТ MCP:\n- Платформа: ${PLATFORM.name}\n- Провайдер: ${currentProvider}\n- Модель: ${currentModel}\n- Режим: ${CONFIG.agentMode}\n- Активная AI-сессия: ${activeSession}\n- Активная рабочая папка: ${WORKSPACE_ROOT}\n- ${providerRule}\n- ${clarifyRule}\n- ${modeRule}\n- ${longRule}\n- Относительные пути разрешаются от неё; внутренняя папка Termux не используется.${repoFact}${envFacts}${presetPrompt()}${pluginPrompt ? `\n\nPLUGIN SYSTEM INSTRUCTIONS:\n${pluginPrompt}` : ''}`;
}

// ═══════════════════════════════════════════════════════════════════
//  ZEN API (curl-based)
// ═══════════════════════════════════════════════════════════════════
async function callZenDirect(messages, model = currentModel, stream = false) {
  const payload = {
    model,
    messages,
    max_tokens: CONFIG.maxTokens,
    temperature: CONFIG.temperature,
    stream: !!stream
  };
  if (CONFIG.reasoningEffort) payload.reasoning_effort = CONFIG.reasoningEffort;

  const data = JSON.stringify(payload);

  if (stream) {
    return new Promise((resolve, reject) => {
      let outputShown = false, fullText = '', usage = {}, thinking = '', sseBuffer = '', settled = false;
      const tmpFile = path.join(os.tmpdir(), 'zen_cli_req_' + Date.now() + '.json');
      fs.writeFileSync(tmpFile, data, 'utf8');
      startAiStream('Zen', model);
      const curlProc = spawn(curlPath(), [
        '-s', '--no-buffer', '--connect-timeout', '10', '--max-time', '60',
        ...(CONFIG.proxy ? ['-x', CONFIG.proxy] : []), ...(CONFIG.curlIpv4 ? ['--ipv4'] : []),
        '-X', 'POST', 'https://opencode.ai/zen/v1/chat/completions',
        '-H', 'Content-Type: application/json', '-d', '@' + tmpFile
      ]);
      const abortThis = () => { try { curlProc.kill('SIGTERM'); } catch {}; finish(new Error('Zen stream aborted by user')); };
      activeProviderAbort = abortThis;
      const finish = (error = null) => {
        if (settled) return; settled = true;
        if (activeProviderAbort === abortThis) activeProviderAbort = null;
        try { fs.unlinkSync(tmpFile); } catch {}
        if (error) { finishAiStream('error'); reject(error); }
        else { finishAiStream('completed'); resolve({ text: fullText || 'Модель вернула пустой ответ.', model, usage, thinking, outputShown, provider: 'zen' }); }
      };
      const consumeEvent = event => {
        for (const line of event.replace(/\r/g, '').split('\n')) {
          if (!line.startsWith('data:')) continue;
          const jsonStr = line.slice(5).trim();
          if (!jsonStr || jsonStr === '[DONE]') continue;
          try {
            const j = JSON.parse(jsonStr); const delta = j.choices?.[0]?.delta;
            if (delta?.content) { fullText += delta.content; writeAiStreamText(delta.content); outputShown = true; }
            if (delta?.reasoning_content || delta?.reasoning) thinking += (delta.reasoning_content || delta.reasoning);
            if (j.usage) usage = j.usage;
          } catch {}
        }
      };
      curlProc.stdout.on('data', chunk => {
        sseBuffer += chunk.toString();
        const events = sseBuffer.split(/\r?\n\r?\n/); sseBuffer = events.pop() || '';
        events.forEach(consumeEvent);
      });
      let stderr = '';
      curlProc.stderr.on('data', chunk => stderr += chunk.toString());
      curlProc.on('close', code => { if (sseBuffer) consumeEvent(sseBuffer); if (code !== 0) finish(new Error(('Zen stream failed: ' + (stderr || `curl exit ${code}`)).slice(0, 300))); else finish(); });
      curlProc.on('error', err => finish(err));
    });
  } else {
    const tmpFile = path.join(os.tmpdir(), 'zen_cli_req_' + Date.now() + '.json');
    fs.writeFileSync(tmpFile, data, 'utf8');
    // Не execSync: event loop остаётся живым, поэтому индикатор, /correct и /abort не «зависают».
    return await new Promise((resolve, reject) => {
      const curlArgs = [
        '-s', '--connect-timeout', '10', '--max-time', '60',
        ...(CONFIG.proxy ? ['-x', CONFIG.proxy] : []),
        ...(CONFIG.curlIpv4 ? ['--ipv4'] : []),
        '-X', 'POST', 'https://opencode.ai/zen/v1/chat/completions',
        '-H', 'Content-Type: application/json', '-d', '@' + tmpFile
      ];
      let output = '', stderr = '', settled = false, abortThis = null, timeout = null;
      const finish = (error, value) => {
        if (settled) return; settled = true; if (timeout) clearTimeout(timeout);
        if (activeProviderAbort === abortThis) activeProviderAbort = null;
        try { fs.unlinkSync(tmpFile); } catch {}
        if (error) reject(error); else resolve(value);
      };
      const proc = spawn(curlPath(), curlArgs, { stdio: ['ignore', 'pipe', 'pipe'] });
      abortThis = () => { try { proc.kill('SIGTERM'); } catch {}; finish(new Error('Zen request aborted by user')); };
      activeProviderAbort = abortThis;
      timeout = setTimeout(() => { try { proc.kill('SIGTERM'); } catch {}; finish(new Error('Zen request timeout (65s)')); }, 65000);
      proc.stdout.on('data', chunk => output += chunk.toString());
      proc.stderr.on('data', chunk => stderr += chunk.toString());
      proc.on('error', err => finish(new Error('Zen request failed: ' + err.message)));
      proc.on('close', code => {
        if (settled) return;
        if (code !== 0) { finish(new Error('Zen request failed: ' + (stderr || `curl exit ${code}`).slice(0, 300))); return; }
        if (isRateLimit(output)) { finish(new Error('Rate limit: ' + output.slice(0, 200))); return; }
        try {
          const json = JSON.parse(output);
          const choice = json.choices?.[0] || {};
          const msg = choice.message || {};
          let text = msg.content || '';
          const reasoning = msg.reasoning_content || msg.reasoning || '';
          if (!text && reasoning) text = reasoning;
          finish(null, { text, reasoning: reasoning || null, model: json.model || model, usage: json.usage || {}, thinking: '', outputShown: false, provider: 'zen' });
        } catch (e) {
          const cm = output.match(/"content"\s*:\s*"([\s\S]*?)"\s*,\s*"refusal"/);
          if (cm) finish(null, { text: cm[1].replace(/\\n/g, '\n').replace(/\\"/g, '"'), reasoning: null, model, usage: {}, outputShown: false, provider: 'zen' });
          else finish(new Error('Zen parse error: ' + output.slice(0, 300)));
        }
      });
    });
  }
}

function nextModel(model) {
  const idx = ZEN_MODELS.findIndex(m => m.id === model);
  return ZEN_MODELS[(idx + 1) % ZEN_MODELS.length].id;
}

async function callZenWithRetry(messages, model = currentModel, maxAttempts = CONFIG.maxProviderRetries, stream = false) {
  let lastErr = new Error('Неизвестная ошибка Zen API');
  let usedModel = model;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    if (agentBusy) setRunPhase('model', `Zen • попытка ${attempt}/${maxAttempts}`);
    try {
      const res = await callZenDirect(messages, usedModel, stream);
      if (usedModel !== model) {
        currentModel = usedModel;
        console.log(`\n${c('✅ Переключено на модель: ' + usedModel, 'green')}`);
      }
      return res;
    } catch (e) {
      lastErr = e;
      const rateLimited = isRateLimit(e.message);
      if (attempt < maxAttempts) {
        // Swapping the model behind the user's back is only acceptable as a
        // last resort on a free tier. With autoSwitchModel off the chosen
        // model is the one that runs - a local model has no quota to hit, and
        // silently answering from a different model makes a comparison
        // meaningless. Retry the same one instead.
        if (rateLimited && CONFIG.autoSwitchModel) {
          const prev = usedModel;
          usedModel = nextModel(usedModel);
          console.log(`\n${c('⚠️ Лимит API на ' + prev + '. Переключаюсь на ' + usedModel + '...', 'yellow')}`);
          await new Promise(r => setTimeout(r, 700));
        } else if (rateLimited) {
          console.log(`\n${c('⚠️ Лимит API на ' + usedModel + '. Автопереключение выключено — повторяю на этой же модели.', 'yellow')}`);
          await new Promise(r => setTimeout(r, 1200));
        } else {
          const wait = 650 * attempt;
          console.log(`\n${c('⚠️ Попытка ' + attempt + '/' + maxAttempts + ' не удалась. Повтор через ' + (wait / 1000) + 's...', 'yellow')}`);
          await new Promise(r => setTimeout(r, wait));
        }
      }
    }
  }
  throw lastErr;
}


// ═══════════════════════════════════════════════════════════════════
//  OPENROUTER PROVIDER — native function/tool calling when available
// ═══════════════════════════════════════════════════════════════════
const OPENROUTER_FREE_FALLBACK = [
  { id: 'openrouter/free', name: 'OpenRouter Free Router', ctx: 'provider-selected' },
  { id: 'google/gemma-3-27b-it:free', name: 'Gemma 3 27B IT', ctx: 'free' },
  { id: 'meta-llama/llama-3.3-70b-instruct:free', name: 'Llama 3.3 70B Instruct', ctx: 'free' },
  { id: 'qwen/qwen3-30b-a3b:free', name: 'Qwen 3 30B A3B', ctx: 'free' }
];
let openRouterFreeModels = [...OPENROUTER_FREE_FALLBACK];

const OPENROUTER_KEY_FILE = path.join(os.homedir(), '.zen_openrouter_key.json');
function looksLikeOpenRouterKey(value) {
  return /^sk-or-(?:v1-)?[A-Za-z0-9_-]{16,}$/i.test(String(value || '').trim());
}
function redactSecrets(value) {
  if (typeof value !== 'string') return value;
  return value.replace(/sk-or-(?:v1-)?[A-Za-z0-9_-]{16,}/gi, '[OPENROUTER_KEY_REDACTED]');
}
function scrubHistorySecrets() {
  for (const message of history || []) {
    if (typeof message.content === 'string') message.content = redactSecrets(message.content);
    if (Array.isArray(message.tool_calls)) {
      for (const call of message.tool_calls) if (typeof call?.function?.arguments === 'string') call.function.arguments = redactSecrets(call.function.arguments);
    }
  }
}
function saveKeyFromCommand(value) {
  const raw = String(value || '').trim().replace(/^set\s+/i, '');
  if (!looksLikeOpenRouterKey(raw)) return { error: 'Нужен OpenRouter key формата sk-or-... Используй /key без аргумента для Android password-dialog.' };
  const result = saveOpenRouterKey(raw);
  scrubHistorySecrets();
  return result;
}
function maskOpenRouterKey(key) {
  const value = String(key || '');
  return value.length >= 12 ? `${value.slice(0, 6)}…${value.slice(-4)}` : (value ? '***' : 'не задан');
}
function loadOpenRouterKey() {
  // Явная переменная окружения всегда имеет приоритет над локальным секретом.
  if (process.env.OPENROUTER_API_KEY) { CONFIG.openRouterApiKey = process.env.OPENROUTER_API_KEY; return; }
  try {
    const saved = JSON.parse(fs.readFileSync(OPENROUTER_KEY_FILE, 'utf8'));
    if (saved && typeof saved.key === 'string' && saved.key.trim()) CONFIG.openRouterApiKey = saved.key.trim();
  } catch {}
}
function saveOpenRouterKey(key) {
  const value = String(key || '').trim().replace(/[\r\n]/g, '');
  if (value.length < 8) return { error: 'Ключ слишком короткий.' };
  try {
    fs.writeFileSync(OPENROUTER_KEY_FILE, JSON.stringify({ key: value, updatedAt: new Date().toISOString() }, null, 2), { mode: 0o600 });
    try { fs.chmodSync(OPENROUTER_KEY_FILE, 0o600); } catch {}
    CONFIG.openRouterApiKey = value;
    return { success: true, masked: maskOpenRouterKey(value), source: 'local secure file' };
  } catch (e) { return { error: 'Не удалось сохранить ключ: ' + e.message }; }
}
function clearOpenRouterKey() {
  CONFIG.openRouterApiKey = '';
  try { fs.unlinkSync(OPENROUTER_KEY_FILE); } catch {}
  return { success: true, environmentStillSet: !!process.env.OPENROUTER_API_KEY };
}
function openRouterKeyStatus() {
  const key = openRouterKey();
  return { configured: !!key, masked: maskOpenRouterKey(key), source: process.env.OPENROUTER_API_KEY ? 'environment' : (fs.existsSync(OPENROUTER_KEY_FILE) ? 'local secure file' : 'none') };
}
function openSecretKeyInput() {
  // termux-dialog password показывает системное Android password-поле, а не TTY.
  // Поэтому ключ не попадает ни в scrollback, ни в историю shell.
  if (!PLATFORM.isTermux) return false;
  try {
    const raw = execFileSync('termux-dialog', ['-t', 'password', '-i', 'OpenRouter API key'], { encoding: 'utf8', timeout: 120000 });
    const result = JSON.parse(raw || '{}');
    if (result.code !== undefined && Number(result.code) !== 0) { console.log(c('Ввод ключа отменён.', 'gray')); return true; }
    const key = String(result.text || '').trim();
    if (!key) { console.log(c('Ключ не введён.', 'gray')); return true; }
    const saved = saveOpenRouterKey(key);
    console.log(saved.error ? c('✗ ' + saved.error, 'red') : c(`✓ OpenRouter key сохранён: ${saved.masked}`, 'green'));
    return true;
  } catch {
    return false;
  }
}
function openRouterKey() { return CONFIG.openRouterApiKey || process.env.OPENROUTER_API_KEY || symbiosisKeys().openrouter || ''; }
function openRouterRequest(payload) {
  return new Promise((resolve, reject) => {
    const key = openRouterKey();
    if (!key) { reject(new Error('Не задан OPENROUTER_API_KEY. В Termux: export OPENROUTER_API_KEY="..."')); return; }
    const body = JSON.stringify(payload);
    const req = https.request({
      hostname: 'openrouter.ai', port: 443, path: '/api/v1/chat/completions', method: 'POST', timeout: 90000,
      headers: {
        'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body),
        'Authorization': `Bearer ${key}`, 'HTTP-Referer': 'https://termux-local-agent', 'X-Title': 'Termux MCP Agent'
      }
    }, res => {
      let raw = '';
      res.setEncoding('utf8');
      res.on('data', part => raw += part);
      res.on('end', () => {
        let parsed = null; try { parsed = JSON.parse(raw); } catch {}
        if (res.statusCode < 200 || res.statusCode >= 300) {
          const message = parsed?.error?.message || raw.slice(0, 500) || `HTTP ${res.statusCode}`;
          reject(new Error(`OpenRouter HTTP ${res.statusCode}: ${message}`)); return;
        }
        if (!parsed) { reject(new Error('OpenRouter вернул не-JSON ответ.')); return; }
        resolve(parsed);
      });
    });
    const abortThisRequest = () => req.destroy(new Error('OpenRouter request aborted by user'));
    activeProviderAbort = abortThisRequest;
    req.on('error', err => { if (activeProviderAbort === abortThisRequest) activeProviderAbort = null; reject(err); });
    req.on('timeout', () => req.destroy(new Error('OpenRouter timeout')));
    req.on('close', () => { if (activeProviderAbort === abortThisRequest) activeProviderAbort = null; });
    req.write(body); req.end();
  });
}

async function fetchOpenRouterFreeModels() {
  return await new Promise(resolve => {
    const req = https.request({ hostname: 'openrouter.ai', port: 443, path: '/api/v1/models', method: 'GET', timeout: 15000, headers: openRouterKey() ? { Authorization: `Bearer ${openRouterKey()}` } : {} }, res => {
      let raw = ''; res.setEncoding('utf8'); res.on('data', part => raw += part);
      res.on('end', () => {
        try {
          const all = JSON.parse(raw).data || [];
          // Everything the catalogue offers, not just the ':free' ones.
          //
          // Filtering to free models meant that a paid key - which is the
          // reason to configure OpenRouter at all - still showed the same
          // handful of free entries, and the hundreds of models the key could
          // reach were unreachable from the UI. Free ones are sorted first
          // because they are the safe default, but nothing is dropped.
          const mapped = all
            .map(model => ({
              id: model.id,
              name: model.name || model.id,
              ctx: model.context_length ? String(model.context_length) : '',
              free: String(model.id || '').endsWith(':free') || model.id === 'openrouter/free'
            }))
            .filter(m => m.id);
          mapped.sort((a, b) => (a.free === b.free ? a.id.localeCompare(b.id) : (a.free ? -1 : 1)));
          if (mapped.length) openRouterFreeModels = mapped;
        } catch {}
        resolve(openRouterFreeModels);
      });
    });
    req.on('error', () => resolve(openRouterFreeModels));
    req.on('timeout', () => { req.destroy(); resolve(openRouterFreeModels); });
    req.end();
  });
}

// ═══════════════════════════════════════════════════════════════════
//  GITHUB API CLIENT
// ═══════════════════════════════════════════════════════════════════
//
// Built lazily and cached: the token can arrive after start-up (SYMBIOSIS_KEY
// set later, /key), so binding it once at load time would leave the tools
// permanently unauthenticated.
//
// The default repository is the one the session is working on, so a task can
// say "прочитай README" without repeating owner/name every call. It is taken
// from the workspace's own git remote when there is one.
let GITHUB_API_CACHE = null;
function detectSessionRepo() {
  try {
    const out = execFileSync('git', ['remote', 'get-url', 'origin'],
      { cwd: WORKSPACE_ROOT, encoding: 'utf8', timeout: 2500 }).trim();
    const m = out.match(/github\.com[/:]([\w.-]+\/[\w.-]+?)(?:\.git)?$/i);
    return m ? m[1] : '';
  } catch { return ''; }
}
function githubApi() {
  if (!GitHubApi) return null;
  if (!GITHUB_API_CACHE) {
    GITHUB_API_CACHE = new GitHubApi(
      () => githubModelsToken(),
      () => process.env.SYMBIOSIS_REPO || detectSessionRepo()
    );
  }
  return GITHUB_API_CACHE;
}

// ═══════════════════════════════════════════════════════════════════
//  PRESETS
// ═══════════════════════════════════════════════════════════════════
//
// A preset is standing instruction: state once how you want the agent to
// work, and it applies to every task in the session instead of being retyped
// - "here is the token, work directly on GitHub, do not clone anything, push
// when you are done".
//
// It is appended to the system prompt, so it steers the model the same way
// the built-in rules do, and it survives a restart because it is saved with
// the session.
const PRESETS_FILE = path.join(os.homedir(), '.zen_presets.json');

const BUILT_IN_PRESETS = {
  github: {
    label: 'Прямо в GitHub, без клонирования',
    text: [
      'РЕЖИМ РАБОТЫ: напрямую через GitHub API, без клонирования.',
      '',
      'ЭТО ПРАВИЛО ВАЖНЕЕ ОСТАЛЬНЫХ. Работа идёт с удалённым репозиторием,',
      'а не с локальной папкой. Локальная папка может быть пустой и не быть',
      'git-репозиторием — это нормально и не мешает работе.',
      '',
      'НЕ используй: git_clone, git_status, git_log, git_diff, read_file,',
      'list_dir, write_file для содержимого репозитория. Они смотрят в',
      'локальную папку, а нужного там нет.',
      'Никогда не отвечай "тут нет git-репозитория" — вместо этого вызови',
      'соответствующий github_* и покажи данные с GitHub.',
      '',
      'Используй github_*:',
      '  github_read / github_list      — посмотреть файл или папку',
      '  github_write                   — записать файл (это сразу коммит)',
      '  github_commit_files            — несколько файлов одним коммитом',
      '  github_search                  — найти код в репозитории',
      '  github_commits / github_branches — история и ветки',
      '  github_run_workflow / github_runs — запустить сборку и посмотреть её',
      '',
      'Каждая запись через github_write и github_commit_files — это уже',
      'коммит в ветку, отдельный git_push не нужен и не существует для них.',
      'После изменений покажи ссылку на коммит.',
      '',
      'Клонируй только если задача требует всего дерева сразу: сборка,',
      'прогон тестов, массовый рефакторинг. Тогда скажи об этом явно.'
    ].join('\n')
  },
  tables: {
    label: 'Ответы таблицами',
    text: [
      'ФОРМАТ ОТВЕТА: где сравниваются несколько объектов - файлы, модели,',
      'запуски, варианты - оформляй markdown-таблицей, а не списком.',
      'Таблица рендерится в интерфейсе и читается на телефоне лучше.'
    ].join('\n')
  },
  local: {
    label: 'Только локальная модель',
    text: [
      'Работай на локальной модели в раннере. Не переключайся на онлайн-',
      'провайдеров с лимитами; если локальная модель не запущена, скажи об',
      'этом и предложи запустить её через панель локальных моделей.'
    ].join('\n')
  }
};

let PRESETS = { active: [], custom: {} };

function loadPresets() {
  try {
    const saved = JSON.parse(fs.readFileSync(PRESETS_FILE, 'utf8'));
    if (saved && typeof saved === 'object') {
      PRESETS.active = Array.isArray(saved.active) ? saved.active : [];
      PRESETS.custom = saved.custom && typeof saved.custom === 'object' ? saved.custom : {};
    }
  } catch {}
}

function savePresets() {
  try { fs.writeFileSync(PRESETS_FILE, JSON.stringify(PRESETS, null, 2), { mode: 0o600 }); } catch {}
}

/** Every preset that can be switched on, built-in and user-defined alike. */
function allPresets() {
  const out = {};
  for (const [id, p] of Object.entries(BUILT_IN_PRESETS)) out[id] = { ...p, builtIn: true };
  for (const [id, text] of Object.entries(PRESETS.custom)) {
    out[id] = { label: id, text: String(text), builtIn: false };
  }
  return out;
}

/** The text appended to the system prompt for the active presets. */
function presetPrompt() {
  const all = allPresets();
  const parts = PRESETS.active.map(id => all[id] && all[id].text).filter(Boolean);
  if (!parts.length) return '';
  return '\n\nПОСТОЯННЫЕ УКАЗАНИЯ ПОЛЬЗОВАТЕЛЯ (пресеты):\n' + parts.join('\n\n');
}

function setPresetActive(id, on) {
  const known = allPresets();
  if (!known[id]) return { error: `Нет пресета '${id}'. Доступны: ${Object.keys(known).join(', ')}` };
  const set = new Set(PRESETS.active);
  if (on) set.add(id); else set.delete(id);
  PRESETS.active = [...set];
  savePresets();
  return { active: PRESETS.active };
}

// ═══════════════════════════════════════════════════════════════════
//  BUILD IDENTITY
// ═══════════════════════════════════════════════════════════════════
//
// The hub is served by whichever agent session is running, so the UI in front
// of the user can be hours behind main while the repository is already fixed -
// which is exactly what happened: a full-screen chat shipped, the model list
// did not, because the session predated it. Reporting the commit makes that
// visible instead of looking like the fix never landed.
let AGENT_BUILD_CACHE = null;
function agentBuildInfo() {
  if (AGENT_BUILD_CACHE) return AGENT_BUILD_CACHE;
  const run = args => {
    try {
      return execFileSync('git', args, { cwd: __dirname, encoding: 'utf8', timeout: 2500 }).trim();
    } catch { return ''; }
  };
  const sha = run(['rev-parse', '--short', 'HEAD']);
  const count = run(['rev-list', '--count', 'HEAD']);
  const when = run(['log', '-1', '--format=%cI']);
  AGENT_BUILD_CACHE = {
    version: count && sha ? `${count}.${sha}` : 'dev',
    commit: sha || null,
    committedAt: when || null,
    startedAt: new Date(Date.now() - Math.round(process.uptime() * 1000)).toISOString()
  };
  return AGENT_BUILD_CACHE;
}

// ═══════════════════════════════════════════════════════════════════
//  ONE KEY
// ═══════════════════════════════════════════════════════════════════
//
// Six secrets had to be set by hand - OPENROUTER_API_KEY, HF_TOKEN,
// GITHUB_TOKEN, GITHUB_MODELS_TOKEN, HUGGINGFACE_TOKEN, OPENAI_API_KEY - each
// with its own name, and getting one wrong showed up only as a provider
// silently listed as unavailable. SYMBIOSIS_KEY takes them all: one secret,
// newline- or comma-separated, and each token is routed by its own prefix.
//
// Prefixes are unambiguous and issuer-assigned, so nothing has to be labelled:
//   sk-or-...        OpenRouter
//   hf_...           Hugging Face
//   ghp_ / gho_ /
//   ghu_ / ghs_ /
//   github_pat_...   GitHub
//   sk-ant-...       Anthropic
//   sk-...           OpenAI (checked last: sk-or- and sk-ant- are narrower)
//
// The individual variables still win when set, so an existing setup keeps
// working and a single provider can be overridden without touching the rest.
let SYMBIOSIS_KEY_CACHE = null;
function symbiosisKeys() {
  if (SYMBIOSIS_KEY_CACHE) return SYMBIOSIS_KEY_CACHE;
  const out = { openrouter: '', huggingface: '', github: '', anthropic: '', openai: '', unknown: [] };
  const raw = process.env.SYMBIOSIS_KEY || process.env.SYMBIOSIS_KEYS || '';
  for (const piece of String(raw).split(/[\s,;]+/)) {
    const t = piece.trim();
    if (t.length < 8) continue;
    if (/^sk-or-/i.test(t)) out.openrouter ||= t;
    else if (/^hf_/i.test(t)) out.huggingface ||= t;
    else if (/^(ghp_|gho_|ghu_|ghs_|github_pat_)/i.test(t)) out.github ||= t;
    else if (/^sk-ant-/i.test(t)) out.anthropic ||= t;
    else if (/^sk-/i.test(t)) out.openai ||= t;
    else out.unknown.push(t.slice(0, 6) + '…');
  }
  SYMBIOSIS_KEY_CACHE = out;
  return out;
}

/** What the one key resolved to, for the UI. Never returns a secret value. */
function symbiosisKeyReport() {
  const k = symbiosisKeys();
  const present = name => !!k[name];
  return {
    configured: !!(process.env.SYMBIOSIS_KEY || process.env.SYMBIOSIS_KEYS),
    providers: {
      openrouter: { fromKey: present('openrouter'), active: !!openRouterKey() },
      huggingface: { fromKey: present('huggingface'), active: !!huggingFaceToken() },
      github: { fromKey: present('github'), active: !!githubModelsToken() }
    },
    unrecognised: k.unknown
  };
}

function githubModelsToken() {
  return process.env.GITHUB_MODELS_TOKEN || process.env.GITHUB_TOKEN || symbiosisKeys().github || '';
}
async function fetchGitHubModelsCatalog() {
  const token = githubModelsToken();
  if (!token) throw new Error('GitHub Models token is not configured. Set GITHUB_TOKEN or GITHUB_MODELS_TOKEN with models:read in the Core environment.');
  return await new Promise((resolve, reject) => {
    const req = https.request({ hostname: 'models.github.ai', port: 443, path: '/catalog/models', method: 'GET', timeout: 20000, headers: { 'Accept': 'application/vnd.github+json', 'Authorization': `Bearer ${token}`, 'X-GitHub-Api-Version': '2026-03-10', 'User-Agent': 'Zen-Workflow-Local-Core' } }, res => {
      let raw = ''; res.setEncoding('utf8'); res.on('data', chunk => { raw += chunk; if (raw.length > 4 * 1024 * 1024) req.destroy(new Error('GitHub catalog response is too large')); });
      res.on('end', () => {
        let parsed; try { parsed = JSON.parse(raw); } catch { reject(new Error('GitHub Models catalog returned non-JSON')); return; }
        if (res.statusCode < 200 || res.statusCode >= 300) { reject(new Error(`GitHub Models catalog HTTP ${res.statusCode}: ${parsed?.message || parsed?.error?.message || raw.slice(0, 300)}`)); return; }
        const rows = Array.isArray(parsed) ? parsed : (parsed.models || parsed.data || []);
        const models = rows.slice(0, 500).map(item => ({ id: String(item.id || item.name || ''), name: String(item.name || item.id || ''), publisher: item.publisher || item.provider || null, version: item.version || null, capabilities: Array.isArray(item.capabilities) ? item.capabilities : [], input: item.supported_input_modalities || [], output: item.supported_output_modalities || [], limits: item.limits || null, tags: Array.isArray(item.tags) ? item.tags : [] })).filter(item => item.id);
        resolve({ models, truncated: rows.length > models.length, fetchedAt: new Date().toISOString() });
      });
    });
    req.on('error', reject); req.on('timeout', () => req.destroy(new Error('GitHub Models catalog timeout'))); req.end();
  });
}
function huggingFaceToken() { return process.env.HF_TOKEN || process.env.HUGGINGFACE_TOKEN || symbiosisKeys().huggingface || ''; }
// Named here rather than inline in the settings response, so the hub's model
// list and that response cannot drift apart. Both read these.
const GITHUB_MODELS = [
  'openai/gpt-4.1', 'openai/gpt-4.1-mini', 'openai/gpt-4o', 'openai/gpt-4o-mini',
  'openai/o4-mini', 'meta/llama-3.3-70b-instruct', 'meta/llama-4-maverick-17b-128e-instruct-fp8',
  'mistral-ai/mistral-medium-2505', 'deepseek/deepseek-v3-0324', 'xai/grok-3-mini'
];
const HUGGINGFACE_MODELS = [
  'openai/gpt-oss-120b:cerebras', 'google/gemma-4-31B-it:cerebras',
  'deepseek-ai/DeepSeek-R1:fastest', 'Qwen/Qwen3-235B-A22B-Instruct-2507:fastest',
  'meta-llama/Llama-3.3-70B-Instruct:fastest'
];

const COMPATIBLE_PROVIDERS = {
  github: { label: 'GitHub Models', hostname: 'models.github.ai', path: '/inference/chat/completions', key: githubModelsToken, defaultModel: 'openai/gpt-4.1', headers: { 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' } },
  huggingface: { label: 'Hugging Face Inference Providers', hostname: 'router.huggingface.co', path: '/v1/chat/completions', key: huggingFaceToken, defaultModel: 'openai/gpt-oss-120b:cerebras', headers: {} }
};
async function callCompatibleProvider(providerId, messages, model = currentModel) {
  const provider = COMPATIBLE_PROVIDERS[providerId];
  if (!provider) throw new Error(`Unknown compatible provider '${providerId}'.`);
  const key = provider.key();
  if (!key) throw new Error(`${provider.label} token is not configured in Core environment.`);
  const payload = JSON.stringify({ model: model || provider.defaultModel, messages, tools: buildNativeToolDefinitions(), tool_choice: 'auto', max_tokens: CONFIG.maxTokens, temperature: CONFIG.temperature, stream: false });
  return await new Promise((resolve, reject) => {
    const req = https.request({ hostname: provider.hostname, port: 443, path: provider.path, method: 'POST', timeout: 90000, headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload), 'Authorization': `Bearer ${key}`, ...(provider.headers || {}) } }, res => {
      let body = ''; res.setEncoding('utf8'); res.on('data', chunk => body += chunk); res.on('end', () => {
        let json; try { json = JSON.parse(body); } catch { reject(new Error(`${provider.label} returned non-JSON: ${body.slice(0, 300)}`)); return; }
        if (res.statusCode < 200 || res.statusCode >= 300) { reject(new Error(`${provider.label} HTTP ${res.statusCode}: ${json.error?.message || body.slice(0, 300)}`)); return; }
        const msg = json.choices?.[0]?.message || {}; const content = Array.isArray(msg.content) ? msg.content.map(item => item.text || '').join('') : (msg.content || '');
        resolve({ text: content, toolCalls: msg.tool_calls || [], model: json.model || model || provider.defaultModel, usage: json.usage || {}, reasoning: msg.reasoning || null, outputShown: false, provider: providerId });
      });
    });
    req.on('error', reject); req.on('timeout', () => req.destroy(new Error(`${provider.label} timeout`))); req.write(payload); req.end();
  });
}

async function hfInferenceBinary(model, payload, contentType, accept) {
  const token = huggingFaceToken();
  if (!token) throw new Error('Hugging Face token is not configured in Core environment. Set HF_TOKEN or HUGGINGFACE_TOKEN.');
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
  return await new Promise((resolve, reject) => {
    const req = https.request({ hostname: 'router.huggingface.co', port: 443, path: '/hf-inference/models/' + model.split('/').map(encodeURIComponent).join('/'), method: 'POST', timeout: 90000, headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': contentType, 'Accept': accept, 'Content-Length': body.length } }, res => {
      const parts=[];res.on('data',chunk=>parts.push(chunk));res.on('end',()=>{const data=Buffer.concat(parts);if(res.statusCode<200||res.statusCode>=300){let message=data.toString('utf8').slice(0,500);try{message=JSON.parse(message).error||message;}catch{}reject(new Error(`Hugging Face inference HTTP ${res.statusCode}: ${message}`));return;}resolve({ data, contentType: res.headers['content-type'] || '' });});
    });
    req.on('error',reject);req.on('timeout',()=>req.destroy(new Error('Hugging Face inference timeout')));req.write(body);req.end();
  });
}
async function huggingFaceStt(audio, mime, model = 'openai/whisper-large-v3') {
  const result=await hfInferenceBinary(model, audio, mime || 'audio/webm', 'application/json');let json;try{json=JSON.parse(result.data.toString('utf8'));}catch{throw new Error('Hugging Face STT returned non-JSON');}const text=String(json.text||json.generated_text||'').trim();if(!text)throw new Error('Hugging Face STT returned empty transcript');return { text, model };
}
async function huggingFaceTts(text, model = 'facebook/mms-tts-rus') {
  const result=await hfInferenceBinary(model, JSON.stringify({ inputs: text }), 'application/json', 'audio/wav, audio/mpeg, audio/*');if(!result.data.length)throw new Error('Hugging Face TTS returned empty audio');return { base64: result.data.toString('base64'), mime: String(result.contentType).split(';')[0] || 'audio/wav', model };
}

async function callOpenRouter(messages, model = currentModel) {
  const payload = {
    model,
    messages,
    tools: buildNativeToolDefinitions(),
    tool_choice: 'auto',
    max_tokens: CONFIG.maxTokens,
    temperature: CONFIG.temperature,
    stream: false
  };
  const json = await openRouterRequest(payload);
  const msg = json.choices?.[0]?.message || {};
  const content = Array.isArray(msg.content) ? msg.content.map(x => x.text || '').join('') : (msg.content || '');
  return { text: content, toolCalls: msg.tool_calls || [], model: json.model || model, usage: json.usage || {}, reasoning: msg.reasoning || null, outputShown: false, provider: 'openrouter' };
}

async function callOpenRouterStream(messages, model = currentModel) {
  const key = openRouterKey();
  if (!key) throw new Error('Не задан OpenRouter key. Используй /key.');
  const payload = JSON.stringify({ model, messages, tools: buildNativeToolDefinitions(), tool_choice: 'auto', max_tokens: CONFIG.maxTokens, temperature: CONFIG.temperature, stream: true });
  return await new Promise((resolve, reject) => {
    let buffer = '', text = '', usage = {}, settled = false; const toolCalls = new Map();
    startAiStream('OpenRouter', model);
    const req = https.request({
      hostname: 'openrouter.ai', port: 443, path: '/api/v1/chat/completions', method: 'POST', timeout: 90000,
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload), 'Authorization': `Bearer ${key}`, 'HTTP-Referer': 'https://termux-local-agent', 'X-Title': 'Termux MCP Agent' }
    });
    const abortThis = () => { req.destroy(new Error('OpenRouter stream aborted by user')); };
    activeProviderAbort = abortThis;
    const finish = error => {
      if (settled) return; settled = true; if (activeProviderAbort === abortThis) activeProviderAbort = null;
      if (error) { finishAiStream('error'); reject(error); return; }
      const calls = [...toolCalls.entries()].sort((a, b) => a[0] - b[0]).map(([, value]) => value);
      finishAiStream('completed'); resolve({ text: text || '', toolCalls: calls, model, usage, outputShown: text.length > 0, provider: 'openrouter' });
    };
    const consume = event => {
      for (const line of event.replace(/\r/g, '').split('\n')) {
        if (!line.startsWith('data:')) continue;
        const data = line.slice(5).trim(); if (!data || data === '[DONE]') continue;
        try {
          const json = JSON.parse(data); const choice = json.choices?.[0] || {}; const delta = choice.delta || {};
          const content = Array.isArray(delta.content) ? delta.content.map(x => x.text || '').join('') : (delta.content || '');
          if (content) { text += content; writeAiStreamText(content); }
          for (const part of delta.tool_calls || []) {
            const index = Number(part.index ?? 0); const current = toolCalls.get(index) || { id: part.id || '', type: 'function', function: { name: '', arguments: '' } };
            if (part.id) current.id = part.id;
            if (part.function?.name) current.function.name += part.function.name;
            if (part.function?.arguments) current.function.arguments += part.function.arguments;
            toolCalls.set(index, current);
          }
          if (json.usage) usage = json.usage;
        } catch {}
      }
    };
    req.on('response', res => {
      let statusError = ''; res.setEncoding('utf8');
      res.on('data', chunk => {
        if (res.statusCode && (res.statusCode < 200 || res.statusCode >= 300)) { statusError += chunk; return; }
        buffer += chunk; const events = buffer.split(/\r?\n\r?\n/); buffer = events.pop() || ''; events.forEach(consume);
      });
      res.on('end', () => { if (buffer) consume(buffer); if (res.statusCode && (res.statusCode < 200 || res.statusCode >= 300)) finish(new Error(`OpenRouter HTTP ${res.statusCode}: ${statusError.slice(0, 300)}`)); else finish(); });
    });
    req.on('error', finish); req.on('timeout', () => req.destroy(new Error('OpenRouter stream timeout')));
    req.write(payload); req.end();
  });
}

async function callOpenRouterWithRetry(messages, model = currentModel) {
  let lastError = null;
  // With auto-switch off the chosen model is the only candidate: a paid key is
  // being paid for deliberately, and quietly answering from a different model
  // is worse than reporting the failure.
  const candidates = CONFIG.autoSwitchModel
    ? [model, ...openRouterFreeModels.map(m => m.id).filter(id => id !== model)].slice(0, 5)
    : [model];
  for (const candidate of candidates) {
    try {
      const result = CONFIG.streamMode ? await callOpenRouterStream(messages, candidate) : await callOpenRouter(messages, candidate);
      if (candidate !== currentModel) {
        currentModel = candidate;
        console.log(c(`✅ OpenRouter переключён на: ${candidate}`, 'green'));
      }
      return result;
    } catch (e) {
      lastError = e;
      if (!/429|rate|limit|503|502/i.test(e.message || '')) break;
    }
  }
  throw lastError || new Error('OpenRouter request failed');
}

/**
 * Repair a history window before it is sent to an OpenAI-compatible provider.
 *
 * Two ways the window becomes invalid, both of which the API rejects outright
 * with a 400 rather than answering:
 *
 *   1. slice(-maxHistory) can cut between an assistant message carrying
 *      tool_calls and the role:'tool' results that answer it. What is left is
 *      an orphan tool message whose tool_call_id refers to nothing.
 *   2. A run that ends mid-tool - an abort, a thrown provider error - leaves
 *      the assistant's tool_calls in history with no results after them.
 *
 * Either one poisons every later message in the session, which is why the chat
 * would answer once, then refuse everything until it was rephrased into a new
 * session or the window finally slid past the damage. Dropping the unmatched
 * halves costs a little context and keeps the conversation usable.
 */
function repairToolPairs(messages) {
  const answered = new Set();
  for (const m of messages) if (m.role === 'tool' && m.tool_call_id) answered.add(m.tool_call_id);

  const out = [];
  const known = new Set();
  for (const m of messages) {
    if (m.role === 'assistant' && Array.isArray(m.tool_calls) && m.tool_calls.length) {
      const kept = m.tool_calls.filter(call => answered.has(call?.id));
      if (!kept.length) {
        // No results survived: keep the prose, drop the dangling calls.
        const content = String(m.content || '').trim();
        if (content) out.push({ role: 'assistant', content });
        continue;
      }
      for (const call of kept) known.add(call.id);
      out.push(kept.length === m.tool_calls.length ? m : { ...m, tool_calls: kept });
      continue;
    }
    if (m.role === 'tool') {
      if (!m.tool_call_id || !known.has(m.tool_call_id)) continue;  // orphan
      out.push(m);
      continue;
    }
    out.push(m);
  }
  return out;
}

function messagesForProvider() {
  scrubHistorySecrets();
  const base = { role: 'system', content: buildSystemPrompt() };
  if (currentProvider !== 'zen') return [base, ...repairToolPairs(history.slice(-CONFIG.maxHistory))];
  // Zen не гарантирует поддержку role:tool/tool_calls; превращаем результаты в обычный контекст.
  const normalized = history.slice(-CONFIG.maxHistory).map(message => {
    if (message.role === 'tool') return { role: 'user', content: `Результат MCP-инструмента:\n${message.content}` };
    if (message.role === 'assistant' && message.tool_calls) return { role: 'assistant', content: message.content || 'Вызваны MCP-инструменты.' };
    return { role: message.role, content: message.content || '' };
  });
  return [base, ...normalized];
}

async function callCurrentProvider() {
  let request = await pluginHook('beforeModel', { provider: currentProvider, model: currentModel, messages: messagesForProvider(), temperature: CONFIG.temperature, maxTokens: CONFIG.maxTokens });
  const provider = request.provider || currentProvider;
  const model = request.model || currentModel;
  let result;
  if (provider === 'openrouter') result = await callOpenRouterWithRetry(request.messages, model);
  else if (provider === 'zen') result = await callZenWithRetry(request.messages, model, undefined, CONFIG.streamMode);
  else if (provider === 'github' || provider === 'huggingface') result = await callCompatibleProvider(provider, request.messages, model);
  else if (provider === 'local') {
    // Do not pass a stale Zen/OpenRouter model name into a local runtime. The
    // Local AI manager uses the model explicitly configured for its active
    // llama.cpp/Ollama/MNN/OpenAI-compatible engine.
    const localConfig = localAi.publicConfig();
    const configuredModel = localConfig.engines?.[localConfig.activeEngine]?.model || '';
    const answer = await localAi.chat({ messages: request.messages, model: configuredModel, temperature: CONFIG.temperature, max_tokens: CONFIG.maxTokens });
    result = { text: answer.text, toolCalls: [], model: answer.model, usage: answer.usage || {}, reasoning: null, outputShown: false, provider: 'local' };
  } else {
    const customProvider = findPluginProvider(provider);
    if (!customProvider) throw new Error(`Unknown provider '${provider}'. Use /provider to select zen, openrouter or a plugin provider.`);
    result = await callPluginProvider(request.messages, model, customProvider);
  }
  result = await pluginHook('afterModel', { provider, model, request, result });
  return result.result || result;
}

// ═══════════════════════════════════════════════════════════════════
//  MCP
// ═══════════════════════════════════════════════════════════════════
let mcpAvailable = false;
async function callMCP(tool, args = {}) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify({ tool, args });
    const req = http.request({
      hostname: 'localhost',
      port: UI_PORT,
      path: '/mcp/call',
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) }
    }, (res) => {
      let body = '';
      res.on('data', c => body += c);
      res.on('end', () => {
        try {
          const j = JSON.parse(body);
          resolve(j.success ? j.result : { error: j.error || 'MCP error' });
        } catch { reject(new Error('Bad MCP response')); }
      });
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

async function checkMCP() {
  // If we have an embedded server, it's always available
  if (embeddedServer && embeddedServer.listening) { mcpAvailable = true; return true; }
  return new Promise((resolve) => {
    const req = http.get(`http://localhost:${UI_PORT}/mcp/status`, res => {
      let body = '';
      res.on('data', c => body += c);
      res.on('end', () => {
        try { const st = JSON.parse(body); mcpAvailable = !!(st && st.tools && st.tools.length > 0); }
        catch { mcpAvailable = false; }
        resolve(mcpAvailable);
      });
    });
    req.on('error', () => { mcpAvailable = false; resolve(false); });
    req.setTimeout(2000, () => req.destroy());
  });
}

const WRITE_TOOLS = new Set([
  'write_file', 'execute_command', 'delete_file', 'append_file', 'edit_file', 'file_backup', 'mkdir', 'copy_file', 'move_file', 'archive_create', 'archive_extract',
  'set_workspace', 'process_start', 'process_stop', 'monitor_start', 'monitor_stop', 'terminal_create', 'terminal_write', 'terminal_close',
  'npm_install', 'npm_run', 'sqlite_query', 'sqlite_backup', 'env_set', 'env_delete', 'git_init', 'git_commit',
  'open_url', 'clipboard_write', 'notify', 'termux_toast', 'termux_vibrate', 'termux_share', 'termux_volume', 'termux_location', 'custom_tool_create', 'custom_tool_run', 'custom_tool_delete', 'plugin_create', 'plugin_delete', 'plugin_tool_run', 'subagent_create', 'subagent_delete', 'todo_add', 'todo_done', 'todo_remove',
  // Capabilities spawn real processes: they get the same approval gate as
  // execute_command, never the softer treatment of a sandboxed custom tool.
  ...(capabilitiesModule ? capabilitiesModule.CAPABILITY_WRITE_TOOLS : [])
]);
function toolPermissionDecision(name, args = {}) {
  const mode = normalizedAgentMode(CONFIG.agentMode);
  const planningSafe = new Set(['todo_list', 'todo_add', 'todo_done', 'todo_remove', 'custom_tool_list', 'custom_tool_inspect']);
  if ((mode === 'plan' || mode === 'explore') && WRITE_TOOLS.has(name) && !planningSafe.has(name)) {
    return { action: 'deny', reason: `${AGENT_MODES[mode].label}: изменения, процессы и внешние действия запрещены. Переключись: /mode build` };
  }
  if (mode === 'explore' && ['custom_tool_run', 'vision_analyze', 'vision_ui_audit', 'vision_compare'].includes(name)) {
    return { action: 'deny', reason: '🔎 Explore: разрешены только встроенные read/search/diagnostic tools.' };
  }
  if (WRITE_TOOLS.has(name)) return { action: CONFIG.autoApprove ? 'allow' : 'ask', reason: 'изменяющее действие' };
  return { action: 'allow', reason: 'read-only действие' };
}

const COMMAND_RESULT_TOOLS = new Set([
  'execute_command', 'npm_install', 'npm_run', 'run_tests', 'run_lint', 'code_check', 'dependency_audit',
  'git_status', 'git_diff', 'git_branch', 'git_log', 'git_init', 'git_commit'
]);
const LIVE_OUTPUT_TOOLS = new Set([...COMMAND_RESULT_TOOLS, 'process_logs', 'terminal_create', 'terminal_write', 'monitor_start']);

async function useTool(name, args) {
  // Все операции идут через единый MCP-обработчик, даже когда HTTP недоступен.
  try {
    const toolArgs = LIVE_OUTPUT_TOOLS.has(name) ? { ...args, __cliLive: true } : args;
    const r = await handleMCPTool(name, toolArgs);
    if (typeof r === 'string') return r;
    if (r.error) return 'Ошибка: ' + r.error;
    if (COMMAND_RESULT_TOOLS.has(name)) {
      const mode = r.live ? 'Режим: LIVE — stdout/stderr уже были показаны выше без скрытия.' : 'Режим: MCP HTTP/обычный — вывод получен после завершения.';
      return `${mode}\nРабочая папка: ${r.cwd || WORKSPACE_ROOT}\nКод выхода: ${r.exit ?? (r.success ? 0 : 1)}${r.timedOut ? ' (таймаут)' : ''}\n\nstdout:\n${r.stdout || '(пусто)'}\n\nstderr:\n${r.stderr || '(пусто)'}`;
    }
    if (r.content !== undefined) return `Файл: ${r.path || args.path || ''}\n\n${r.content}`;
    if (name === 'ocr_image' && r.text !== undefined) return `OCR: ${r.path || args.path || ''}\n\n${r.text}`;
    if (r.analysis !== undefined) return `VISION • ${r.model || 'model'}\n\n${r.analysis}`;
    if (r.diff !== undefined) return r.diff;
    if (r.output !== undefined) return r.output || 'OK';
    if (['process_start', 'process_status', 'process_stop', 'monitor_start', 'monitor_list', 'monitor_stop', 'terminal_create', 'terminal_write', 'terminal_list', 'terminal_close', 'file_backup', 'termux_info', 'network_check', 'http_request', 'health_check', 'websocket_test', 'project_inspect', 'tree_dir', 'search_text', 'file_info', 'copy_file', 'move_file', 'mkdir', 'archive_create', 'archive_extract', 'sqlite_info', 'sqlite_query', 'sqlite_schema', 'sqlite_backup', 'env_list', 'env_set', 'env_delete', 'image_info', 'vision_compare', 'vision_ui_audit', 'custom_tool_list', 'custom_tool_create', 'custom_tool_inspect', 'custom_tool_run', 'custom_tool_delete', 'subagent_list', 'subagent_create', 'subagent_task', 'subagent_delete', 'plugin_list', 'plugin_create', 'plugin_inspect', 'plugin_delete', 'plugin_tool_list', 'plugin_tool_run', 'plugin_provider_list', 'web_search', 'web_fetch'].includes(name)
      || (capabilities && capabilities.handles(name))) {
      return JSON.stringify(r, null, 2);
    }
    if (r.success) {
      let text = 'OK: ' + (r.path || r.workspace || name);
      if (r.size !== undefined) text += ` (${r.size} bytes, ${r.lines || 0} lines)`;
      if (r.workspace && name !== 'set_workspace') text += `\nMCP-папка: ${r.workspace}`;
      return text;
    }
    return JSON.stringify(r, null, 2);
  } catch (e) {
    return 'Ошибка вызова ' + name + ': ' + e.message;
  }
}

// ═══════════════════════════════════════════════════════════════════
//  UI HELPERS
// ═══════════════════════════════════════════════════════════════════
const log = (...a) => console.log(...a);
let currentProvider = CONFIG.provider || 'zen';
let currentModel = CONFIG.defaultModel;
let history = [];
let agentBusy = false;
let abortRequested = false;
let correctionQueue = [];
let activeProviderAbort = null;
let pendingConfirmation = null;
// When a task is launched from AIN, confirmation is delivered through the
// authenticated web API instead of silently auto-approving a write operation.
let WEB_AGENT_RUN_CONTEXT = null;
function webRunEvent(type, payload = {}) {
  const run = WEB_AGENT_RUN_CONTEXT;
  if (!run) return;
  run.events ||= [];
  const safe = {};
  for (const [key, value] of Object.entries(payload || {})) safe[key] = redactSecrets(String(value ?? '')).slice(0, 1200);
  run.events.push({ id: 'evt_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7), type, at: new Date().toISOString(), ...safe });
  if (run.events.length > 160) run.events.splice(0, run.events.length - 160);
}
let activeArrowMenu = null;
let promptRenderer = () => {};
let rl = null;

// Runtime telemetry: реальные usage от провайдера, когда он их возвращает,
// и помеченная оценка до получения ответа.
const TELEMETRY = {
  phase: 'user-control', detail: 'Ожидание ввода', startedAt: null, phaseStartedAt: Date.now(),
  inputChars: 0, outputChars: 0, toolCalls: 0, step: 0, usage: null, requestChars: 0,
  estimatedInputTokens: 0, provider: 'zen', model: CONFIG.defaultModel, stalled: false
};
let ACTIVE_STREAM = null;
function startAiStream(provider, model) {
  ACTIVE_STREAM = { provider, model, startedAt: Date.now(), firstTokenAt: null, chars: 0 };
  console.log(c(`\n▶ AI stream started • ${provider} • ${model}`, 'brightCyan'));
}
function writeAiStreamText(text) {
  if (!ACTIVE_STREAM) startAiStream(currentProvider, currentModel);
  if (!ACTIVE_STREAM.firstTokenAt) {
    ACTIVE_STREAM.firstTokenAt = Date.now();
    console.log(c(`⏱ First token: ${((ACTIVE_STREAM.firstTokenAt - ACTIVE_STREAM.startedAt) / 1000).toFixed(1)}s`, 'gray'));
    process.stdout.write(c('│ ', 'cyan'));
  }
  ACTIVE_STREAM.chars += String(text || '').length;
  process.stdout.write(c(String(text || ''), 'brightCyan'));
}
function finishAiStream(status = 'completed') {
  if (!ACTIVE_STREAM) return;
  const now = Date.now();
  const total = ((now - ACTIVE_STREAM.startedAt) / 1000).toFixed(1);
  const first = ACTIVE_STREAM.firstTokenAt ? ((ACTIVE_STREAM.firstTokenAt - ACTIVE_STREAM.startedAt) / 1000).toFixed(1) : '—';
  if (ACTIVE_STREAM.firstTokenAt) process.stdout.write('\n');
  console.log(c(`■ AI stream ${status} • total ${total}s • first token ${first}s • ${ACTIVE_STREAM.chars} chars`, status === 'completed' ? 'green' : 'yellow'));
  ACTIVE_STREAM = null;
}

function estimateTokens(chars) { return Math.max(0, Math.ceil(Number(chars || 0) / 3.6)); }
function elapsedText(ms) {
  const seconds = Math.max(0, Math.floor(ms / 1000));
  return seconds >= 60 ? `${Math.floor(seconds / 60)}m ${seconds % 60}s` : `${seconds}s`;
}
function setRunPhase(phase, detail = '') {
  TELEMETRY.phase = phase; TELEMETRY.detail = detail; TELEMETRY.phaseStartedAt = Date.now();
  TELEMETRY.stalled = false;
}
function phaseLabel() {
  const labels = {
    'user-control': '▣ управление у вас', model: '⠿ модель отвечает', tool: '⚙ инструмент выполняется',
    confirmation: '⚠ ждёт вашего подтверждения', correction: '✎ принята корректировка',
    stopped: '⏹ остановлено', error: '✖ ошибка', complete: '✓ задача завершена'
  };
  return labels[TELEMETRY.phase] || TELEMETRY.phase;
}
function telemetryLiveText() {
  const now = Date.now(); const total = TELEMETRY.startedAt ? elapsedText(now - TELEMETRY.startedAt) : '0s';
  const phase = elapsedText(now - TELEMETRY.phaseStartedAt);
  const actual = TELEMETRY.usage?.total_tokens;
  const tokens = actual ? `${actual} tok` : `≈${TELEMETRY.estimatedInputTokens + estimateTokens(TELEMETRY.outputChars)} tok`;
  const warning = (TELEMETRY.phase === 'model' && now - TELEMETRY.phaseStartedAt >= 20000) ? ' • ⚠ ожидание сети/модели' : '';
  return `${phaseLabel()}: ${TELEMETRY.detail || currentProvider} • ${total} • ${tokens} • ${TELEMETRY.inputChars + TELEMETRY.outputChars} симв.${warning}`;
}
function beginAgentTelemetry(input) {
  TELEMETRY.startedAt = Date.now(); TELEMETRY.inputChars = String(input || '').length; TELEMETRY.outputChars = 0;
  TELEMETRY.toolCalls = 0; TELEMETRY.step = 0; TELEMETRY.usage = null; TELEMETRY.provider = currentProvider; TELEMETRY.model = currentModel;
  TELEMETRY.requestChars = 0; TELEMETRY.estimatedInputTokens = estimateTokens(TELEMETRY.inputChars);
  setRunPhase('model', providerDisplayName());
}
function recordProviderResult(res) {
  if (!res) return;
  TELEMETRY.outputChars += String(res.text || '').length;
  if (res.usage && Object.keys(res.usage).length) TELEMETRY.usage = res.usage;
}
function startTelemetryTicker(spinner) {
  if (!spinner) return null;
  return setInterval(() => { spinner.text = telemetryLiveText(); }, 500);
}
function stopTelemetryTicker(timer) { if (timer) clearInterval(timer); }
function canUseArrowMenu() {
  return !!(rl && process.stdin.isTTY && typeof process.stdin.setRawMode === 'function');
}
function openArrowMenu(title, options, onSelect, onCancel = null) {
  if (!canUseArrowMenu() || !options.length || activeArrowMenu) return false;
  const menu = { title, options, index: 0, rendered: false, lineCount: 0, onSelect, onCancel, handler: null };
  activeArrowMenu = menu;
  rl.pause();
  try { process.stdin.setRawMode(true); } catch { activeArrowMenu = null; rl.resume(); return false; }
  const render = () => {
    // Не допускаем visual wrap: иначе ↑/↓ перерисовывает не те строки на узком телефоне.
    const width = Math.max(34, Math.min(76, termWidth() - 2));
    const clip = text => text.length <= width ? text : text.slice(0, Math.max(1, width - 1)) + '…';
    const titleLine = `┌─ ${title} `;
    const lines = [c(clip(titleLine + '─'.repeat(Math.max(0, width - titleLine.length))), 'cyan')];
    options.forEach((option, i) => {
      const selected = i === menu.index;
      const plain = `${selected ? '▶' : ' '} ${option.label}${option.description ? ' — ' + option.description : ''}`;
      lines.push(selected ? c(clip(plain), 'brightCyan', 'bold') : c(clip(plain), 'white'));
    });
    lines.push(c(clip('↑/↓ — выбор • Enter — подтвердить • Esc/q/0 — отмена'), 'gray'));
    lines.push(c('└' + '─'.repeat(Math.max(0, width - 1)), 'cyan'));
    if (menu.rendered) {
      for (let i = 0; i < menu.lineCount; i++) { readline.moveCursor(process.stdout, 0, -1); readline.clearLine(process.stdout, 0); }
    }
    process.stdout.write((menu.rendered ? '' : '\n') + lines.join('\n') + '\n');
    menu.lineCount = lines.length; menu.rendered = true;
  };
  const close = async (selected = null) => {
    if (menu.closed) return;
    menu.closed = true;
    if (menu.timeout) clearTimeout(menu.timeout);
    process.stdin.off('data', menu.handler);
    try { process.stdin.setRawMode(false); } catch {}
    rl.resume(); activeArrowMenu = null;
    if (selected) await menu.onSelect(selected, menu.index);
    else if (menu.onCancel) await menu.onCancel();
    setTimeout(() => promptRenderer(), 0);
  };
  // Android extra-keyboard sends ESC [ A / ESC [ B directly. Обрабатываем байты
  // сами, а не keypress: readline в Termux иногда поглощает keypress-события.
  menu.handler = chunk => {
    const data = Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk || '');
    if (data === '\x1b[A' || data === '\x1bOA' || data === 'A') { menu.index = (menu.index - 1 + options.length) % options.length; render(); }
    else if (data === '\x1b[B' || data === '\x1bOB' || data === 'B') { menu.index = (menu.index + 1) % options.length; render(); }
    else if (data === '\r' || data === '\n') { void close(options[menu.index]); }
    else if (data === '\x1b' || data === '\x03' || data.toLowerCase() === 'q' || data === '0') { void close(); }
    else if (/^[1-9]$/.test(data)) { const i = Number(data) - 1; if (options[i]) { menu.index = i; render(); } }
  };
  process.stdin.on('data', menu.handler);
  // rl.pause() останавливает поток; raw-меню должно снова включить чтение байтов.
  process.stdin.resume();
  // Страховка от застревания в raw mode: через минуту меню отменится само.
  menu.timeout = setTimeout(() => { console.log(c('\n⌛ Меню закрыто по таймауту.', 'yellow')); void close(); }, 60000);
  render();
  return true;
}
function setIndicatorStyle(style) {
  if (!INDICATOR_THEMES[style]) return { error: 'Неизвестный индикатор: ' + style };
  CONFIG.indicatorStyle = style;
  saveHistory();
  return { success: true, style, label: INDICATOR_THEMES[style].label };
}

function auditFilePath() { return path.join(WORKSPACE_ROOT, '.zen-agent', 'audit.jsonl'); }
function redactAudit(value, key = '') {
  if (/password|secret|token|api[_-]?key|authorization/i.test(key)) return '***';
  if (Array.isArray(value)) return value.map(item => redactAudit(item));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, redactAudit(v, k)]));
  if (typeof value === 'string') { const clean = redactSecrets(value); return clean.length > 500 ? clean.slice(0, 500) + '…' : clean; }
  return value;
}
function auditEvent(event, data = {}) {
  try {
    const file = auditFilePath(); fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.appendFileSync(file, JSON.stringify({ at: new Date().toISOString(), event, provider: currentProvider, session: activeSession, ...redactAudit(data) }) + '\n', 'utf8');
  } catch {}
}
function readAudit(limit = 30) {
  try {
    const lines = fs.readFileSync(auditFilePath(), 'utf8').split('\n').filter(Boolean);
    return lines.slice(-Math.min(Math.max(Number(limit) || 30, 1), 200)).map(line => { try { return JSON.parse(line); } catch { return null; } }).filter(Boolean);
  } catch { return []; }
}

function drawTelemetryPanel(title = ' Выполнение ') {
  const actual = TELEMETRY.usage || {};
  const actualTotal = actual.total_tokens || ((actual.prompt_tokens || 0) + (actual.completion_tokens || 0));
  const tokenLine = actualTotal
    ? `Токены: ${actual.prompt_tokens ?? '?'} вход • ${actual.completion_tokens ?? '?'} выход • ${actualTotal} всего (провайдер)`
    : `Токены: ≈${TELEMETRY.estimatedInputTokens + estimateTokens(TELEMETRY.outputChars)} (оценка по символам)`;
  const duration = TELEMETRY.startedAt ? elapsedText(Date.now() - TELEMETRY.startedAt) : '—';
  const lines = [
    `${phaseLabel()}${TELEMETRY.detail ? ' • ' + TELEMETRY.detail : ''}`,
    `Провайдер: ${TELEMETRY.provider} • Модель: ${TELEMETRY.model}`,
    tokenLine,
    `Символы: ${TELEMETRY.inputChars} вход • ${TELEMETRY.outputChars} выход • ${TELEMETRY.inputChars + TELEMETRY.outputChars} всего`,
    `Время: ${duration} • шагов: ${TELEMETRY.step}/${agentStepLimit()} • инструментов: ${TELEMETRY.toolCalls}`
  ];
  box(lines, { width: Math.min(84, termWidth() - 2), title, style: 'single', color: 'gray' }).forEach(line => console.log(line));
}

function previewTool(name, args) {
  const w = Math.min(68, termWidth() - 4);
  const lines = [
    `${c('MCP-папка:', 'gray')} ${c(WORKSPACE_ROOT, 'brightCyan')}`,
    c('Параметры:', 'gray')
  ];
  for (const [k, v] of Object.entries(args)) {
    const val = redactSecrets(String(v));
    lines.push(`  ${c('•', 'cyan')} ${c(k, 'yellow')}: ${val.length > 120 ? val.slice(0, 120) + '…' : val}`);
  }
  if (args.path) {
    const resolved = resolveWorkspacePath(args.path);
    lines.push(resolved.error ? c('  ✗ ' + resolved.error, 'red') : `${c('Полный путь:', 'gray')} ${resolved.path}`);
  }
  if (name === 'execute_command') {
    const resolved = resolveWorkspacePath(args.cwd || '.');
    lines.push(resolved.error ? c('  ✗ ' + resolved.error, 'red') : `${c('Запуск из:', 'gray')} ${resolved.path}`);
  }
  box(lines, { width: w, title: ' ' + name + ' ', style: 'single', color: 'yellow' }).forEach(l => console.log(l));
}

function askConfirm(tool, args = {}) {
  return new Promise((resolve) => {
    if (WEB_AGENT_RUN_CONTEXT) {
      const run = WEB_AGENT_RUN_CONTEXT;
      run.status = 'awaiting_approval';
      run.approval = {
        tool,
        // Arguments are shown for an informed decision, but known secrets are masked.
        args: Object.fromEntries(Object.entries(args || {}).map(([k, v]) => [k, redactSecrets(String(v))])),
        requestedAt: new Date().toISOString()
      };
      run.resolveApproval = resolve;
      webRunEvent('approval_required', { tool, args: JSON.stringify(run.approval.args) });
      setRunPhase('confirmation', tool);
      console.log(c(`\n⚠ Web Agent ждёт подтверждение: ${tool}.`, 'yellow'));
      return;
    }
    if (!rl || CONFIG.autoApprove) { resolve('yes'); return; }
    pendingConfirmation = { tool, resolve };
    setRunPhase('confirmation', tool);
    console.log(c(`\n⚠ Управление передано вам: подтвердите ${tool}.`, 'yellow'));
    process.stdout.write(c(`  Разрешить ${tool}? [y/N] `, 'yellow'));
  });
}

const SESSIONS_FILE = path.join(os.homedir(), '.zen_chat_sessions.json');
let activeSession = 'default';
let sessionStore = { active: 'default', sessions: {}, settings: {} };
function safeSessionName(name) {
  const value = String(name || '').trim();
  return /^[a-zA-Z0-9а-яА-ЯёЁ._-]{1,48}$/.test(value) ? value : null;
}
function loadSessionStore() {
  try {
    const stored = JSON.parse(fs.readFileSync(SESSIONS_FILE, 'utf8'));
    if (stored && stored.sessions && typeof stored.sessions === 'object') sessionStore = stored;
  } catch {}
  sessionStore.settings ||= {};
  if (typeof sessionStore.settings.autoApprove === 'boolean') CONFIG.autoApprove = sessionStore.settings.autoApprove;
  if (typeof sessionStore.settings.askClarifyingQuestions === 'boolean') CONFIG.askClarifyingQuestions = sessionStore.settings.askClarifyingQuestions;
  if (typeof sessionStore.settings.animatedIndicator === 'boolean') CONFIG.animatedIndicator = sessionStore.settings.animatedIndicator;
  if (typeof sessionStore.settings.indicatorStyle === 'string' && INDICATOR_THEMES[sessionStore.settings.indicatorStyle]) CONFIG.indicatorStyle = sessionStore.settings.indicatorStyle;
  if (typeof sessionStore.settings.visionModel === 'string' && sessionStore.settings.visionModel) CONFIG.visionModel = sessionStore.settings.visionModel;
  if (typeof sessionStore.settings.agentMode === 'string') CONFIG.agentMode = normalizedAgentMode(sessionStore.settings.agentMode);
  if (typeof sessionStore.settings.longTaskMode === 'boolean') CONFIG.longTaskMode = sessionStore.settings.longTaskMode;
  activeSession = safeSessionName(sessionStore.active) || 'default';
  if (!sessionStore.sessions[activeSession]) sessionStore.sessions[activeSession] = { history: [], createdAt: new Date().toISOString() };
}
function saveSessionStore() {
  try {
    sessionStore.active = activeSession;
    sessionStore.settings = { ...(sessionStore.settings || {}), autoApprove: CONFIG.autoApprove, askClarifyingQuestions: CONFIG.askClarifyingQuestions, animatedIndicator: CONFIG.animatedIndicator, indicatorStyle: CONFIG.indicatorStyle, visionModel: CONFIG.visionModel, agentMode: CONFIG.agentMode, longTaskMode: CONFIG.longTaskMode };
    fs.writeFileSync(SESSIONS_FILE, JSON.stringify(sessionStore, null, 2), { mode: 0o600 });
    try { fs.chmodSync(SESSIONS_FILE, 0o600); } catch {}
  } catch {}
}
function saveHistory() {
  scrubHistorySecrets();
  if (!sessionStore.sessions) loadSessionStore();
  sessionStore.sessions[activeSession] = {
    ...(sessionStore.sessions[activeSession] || {}), history: history.slice(-CONFIG.sessionHistoryLimit),
    provider: currentProvider, model: currentModel, workspace: WORKSPACE_ROOT, updatedAt: new Date().toISOString()
  };
  saveSessionStore();
}
function loadHistory() {
  loadSessionStore();
  const selected = sessionStore.sessions[activeSession];
  if (selected && Array.isArray(selected.history)) {
    history = selected.history;
    if (typeof selected.provider === 'string' && selected.provider) currentProvider = selected.provider;
    if (typeof selected.model === 'string' && selected.model) currentModel = selected.model;
    return;
  }
  try { const legacy = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.oc_history.json'), 'utf8')); if (Array.isArray(legacy)) history = legacy; }
  catch {}
}
function listSessions() {
  loadSessionStore();
  return Object.entries(sessionStore.sessions).map(([name, data]) => ({ name, active: name === activeSession, messages: Array.isArray(data.history) ? data.history.length : 0, updatedAt: data.updatedAt || data.createdAt || null, provider: data.provider || 'zen', model: data.model || CONFIG.defaultModel })).sort((a, b) => String(b.updatedAt || '').localeCompare(String(a.updatedAt || '')));
}
function switchSession(name) {
  const valid = safeSessionName(name); if (!valid) return { error: 'Имя сессии: до 48 букв/цифр, _, -, . .' };
  saveHistory(); loadSessionStore();
  if (!sessionStore.sessions[valid]) sessionStore.sessions[valid] = { history: [], createdAt: new Date().toISOString(), provider: currentProvider, model: currentModel, workspace: WORKSPACE_ROOT };
  activeSession = valid; sessionStore.active = valid;
  const data = sessionStore.sessions[valid]; history = Array.isArray(data.history) ? data.history : [];
  if (typeof data.provider === 'string' && data.provider) currentProvider = data.provider;
  if (data.model) currentModel = data.model;
  if (data.workspace && fs.existsSync(data.workspace) && isTermuxSharedPath(data.workspace)) { WORKSPACE_ROOT = data.workspace; CONFIG.workspaceRoot = data.workspace; }
  saveSessionStore(); return { success: true, name: valid, messages: history.length, provider: currentProvider, model: currentModel, workspace: WORKSPACE_ROOT };
}
function deleteSession(name) {
  const valid = safeSessionName(name); if (!valid) return { error: 'Некорректное имя сессии.' };
  if (valid === activeSession) return { error: 'Нельзя удалить активную сессию. Сначала переключись на другую.' };
  loadSessionStore(); if (!sessionStore.sessions[valid]) return { error: 'Сессия не найдена.' };
  delete sessionStore.sessions[valid]; saveSessionStore(); return { success: true, name: valid };
}

function sessionInfoTool(name = activeSession) {
  const valid = safeSessionName(name); if (!valid) return { error: 'Некорректное имя сессии.' };
  loadSessionStore(); const data = sessionStore.sessions[valid]; if (!data) return { error: 'Сессия не найдена.' };
  return { name: valid, active: valid === activeSession, title: data.title || valid, parent: data.parent || null, messages: Array.isArray(data.history) ? data.history.length : 0, createdAt: data.createdAt || null, updatedAt: data.updatedAt || null, provider: data.provider || 'zen', model: data.model || CONFIG.defaultModel, workspace: data.workspace || null };
}
function forkSession(name) {
  const valid = safeSessionName(name); if (!valid) return { error: 'Имя новой сессии некорректно.' };
  saveHistory(); loadSessionStore(); if (sessionStore.sessions[valid]) return { error: `Сессия '${valid}' уже существует.` };
  const source = sessionStore.sessions[activeSession] || {};
  const clone = JSON.parse(JSON.stringify(source));
  clone.parent = activeSession; clone.createdAt = new Date().toISOString(); clone.updatedAt = clone.createdAt; clone.title = valid;
  sessionStore.sessions[valid] = clone; activeSession = valid; sessionStore.active = valid; history = Array.isArray(clone.history) ? clone.history : [];
  saveSessionStore(); return { success: true, name: valid, parent: clone.parent, messages: history.length };
}
function renameSession(name) {
  const valid = safeSessionName(name); if (!valid) return { error: 'Новое имя сессии некорректно.' };
  loadSessionStore(); if (valid !== activeSession && sessionStore.sessions[valid]) return { error: `Сессия '${valid}' уже существует.` };
  const data = sessionStore.sessions[activeSession]; if (!data) return { error: 'Активная сессия не найдена.' };
  delete sessionStore.sessions[activeSession]; data.title = valid; data.updatedAt = new Date().toISOString(); sessionStore.sessions[valid] = data; activeSession = valid; sessionStore.active = valid;
  saveSessionStore(); return { success: true, name: valid };
}
function exportSession(filePath) {
  saveHistory();
  const target = mcpPathOrError(filePath || path.join('.zen-agent', 'sessions', `${activeSession}.json`), 'path');
  if (target.error) return target;
  try {
    fs.mkdirSync(path.dirname(target.path), { recursive: true });
    const payload = { format: 'zen-agent-session-v1', exportedAt: new Date().toISOString(), active: activeSession, session: sessionStore.sessions[activeSession] };
    fs.writeFileSync(target.path, JSON.stringify(payload, null, 2), 'utf8'); return { success: true, path: target.path, session: activeSession };
  } catch (e) { return { error: 'Не удалось экспортировать сессию: ' + e.message }; }
}
function importSession(filePath, name) {
  const source = mcpPathOrError(filePath, 'path', true); if (source.error) return source;
  const valid = safeSessionName(name); if (!valid) return { error: 'Для import укажи новое имя сессии.' };
  try {
    const payload = JSON.parse(fs.readFileSync(source.path, 'utf8'));
    const data = payload.session || payload;
    if (!data || !Array.isArray(data.history)) return { error: 'Файл не похож на экспорт Zen Agent session.' };
    loadSessionStore(); if (sessionStore.sessions[valid]) return { error: `Сессия '${valid}' уже существует.` };
    data.title = valid; data.importedAt = new Date().toISOString(); data.updatedAt = data.importedAt; sessionStore.sessions[valid] = data;
    saveSessionStore(); return { success: true, name: valid, messages: data.history.length };
  } catch (e) { return { error: 'Не удалось импортировать сессию: ' + e.message }; }
}

// ═══════════════════════════════════════════════════════════════════
//  TODO SYSTEM
// ═══════════════════════════════════════════════════════════════════
const TODO_FILE = path.join(os.homedir(), '.zen_todo.json');
let todos = [];

function loadTodos() {
  try {
    const t = JSON.parse(fs.readFileSync(TODO_FILE, 'utf8'));
    if (Array.isArray(t)) todos = t;
  } catch { todos = []; }
}
function saveTodos() {
  try { fs.writeFileSync(TODO_FILE, JSON.stringify(todos, null, 2)); }
  catch {}
}
function projectTodos(workspace = WORKSPACE_ROOT) {
  loadTodos();
  return todos.filter(t => !t.workspace || t.workspace === workspace);
}
function addTodo(text, options = {}) {
  loadTodos();
  const id = todos.reduce((max, t) => Math.max(max, Number(t.id) || 0), 0) + 1;
  todos.push({
    id,
    text: String(text).trim(),
    done: false,
    workspace: options.workspace || WORKSPACE_ROOT,
    source: options.source || 'cli',
    created: Date.now()
  });
  saveTodos();
  return id;
}
function doneTodo(id, workspace = null) {
  loadTodos();
  const t = todos.find(x => x.id === id && (!workspace || !x.workspace || x.workspace === workspace));
  if (t) { t.done = true; t.completed = Date.now(); saveTodos(); return true; }
  return false;
}
function removeTodo(id, workspace = null) {
  loadTodos();
  const before = todos.length;
  todos = todos.filter(x => !(x.id === id && (!workspace || !x.workspace || x.workspace === workspace)));
  saveTodos();
  return before !== todos.length;
}
function clearTodos(workspace = WORKSPACE_ROOT) {
  loadTodos();
  todos = todos.filter(x => x.workspace && x.workspace !== workspace);
  saveTodos();
}
function drawTodos() {
  const activeTodos = projectTodos();
  const tw = termWidth();
  const w = Math.min(72, tw - 4);
  if (!activeTodos.length) {
    box([
      c('Нет задач в текущем проекте.', 'gray'),
      c('Добавь: /todo текст', 'gray'),
      c('Папка: ' + WORKSPACE_ROOT, 'gray')
    ], { width: w, title: ' TODO ', style: 'single', color: 'yellow' }).forEach(l => console.log(l));
    return;
  }
  const lines = [c('Проект: ' + WORKSPACE_ROOT, 'gray'), ''];
  for (const t of activeTodos) {
    const status = t.done ? c('✓', 'green') : c('○', 'gray');
    const text = t.done ? c(t.text, 'gray') + c(' (готово)', 'green') : c(t.text, 'white');
    lines.push(`  ${status} ${c('#' + t.id, 'yellow')} ${text}`);
  }
  box(lines, { width: w, title: ` TODO (${activeTodos.filter(t => !t.done).length}/${activeTodos.length}) `, style: 'single', color: 'yellow' }).forEach(l => console.log(l));
}

// ═══════════════════════════════════════════════════════════════════
//  TOOL CALL HANDLER
// ═══════════════════════════════════════════════════════════════════
const TOOL_REQUIRED_ARGS = {
  set_workspace: ['path'], read_file: ['path'], write_file: ['path'], edit_file: ['path'], delete_file: ['path'], append_file: ['path'],
  file_backup: ['path'], file_diff: ['path', 'backup'], copy_file: ['source', 'destination'], move_file: ['source', 'destination'],
  archive_create: ['source', 'destination'], archive_extract: ['archive', 'destination'], download_file: ['url', 'path'],
  execute_command: ['command'], process_start: ['name', 'command'], process_logs: ['name'], process_stop: ['name'],
  monitor_start: ['process_name'], monitor_logs: ['id'], monitor_stop: ['id'], terminal_write: ['id'], terminal_read: ['id'], terminal_close: ['id'],
  http_request: ['url'], health_check: ['url'], websocket_test: ['url'], npm_install: ['packages'], npm_run: ['script'],
  sqlite_query: ['database', 'sql'], sqlite_backup: ['database', 'destination'], env_set: ['key', 'value'], env_delete: ['key'],
  code_check: ['path'], git_commit: ['message'], git_clone: ['repo'], open_url: ['url'], clipboard_write: ['text'], notify: ['content'], todo_add: ['text'], todo_done: ['id'], todo_remove: ['id'], web_search: ['query'], web_fetch: ['url'], search_text: ['query'],
  image_info: ['path'], ocr_image: ['path'], vision_analyze: ['path'], analyze_image: ['path'], vision_ui_audit: ['path'], vision_compare: ['path', 'path2'],
  custom_tool_create: ['name', 'description', 'code'], custom_tool_inspect: ['name'], custom_tool_run: ['name'], custom_tool_delete: ['name'],
  subagent_create: ['name', 'description', 'prompt'], subagent_task: ['agent', 'prompt'], subagent_delete: ['name'],
  plugin_create: ['name', 'description', 'code'], plugin_inspect: ['name'], plugin_delete: ['name'], plugin_tool_run: ['plugin', 'name'],
  ...(capabilitiesModule ? capabilitiesModule.CAPABILITY_REQUIRED_ARGS : {})
};
const NATIVE_TOOL_PROPERTIES = {
  path: { type: 'string' }, cwd: { type: 'string' }, dir: { type: 'string' }, query: { type: 'string' }, text: { type: 'string' }, content: { type: 'string' },
  old: { type: 'string' }, new: { type: 'string' }, operation: { type: 'string' }, line: { type: 'integer' }, lines: { type: 'string' },
  source: { type: 'string' }, destination: { type: 'string' }, archive: { type: 'string' }, backup: { type: 'string' }, url: { type: 'string' }, method: { type: 'string' },
  command: { type: 'string' }, timeout: { type: 'integer' }, name: { type: 'string' }, id: { type: 'string' }, process_name: { type: 'string' },
  follow_seconds: { type: 'integer' }, interval_seconds: { type: 'integer' }, restart: { type: 'boolean' }, force: { type: 'boolean' },
  input: { type: 'string' }, initial_command: { type: 'string' }, shell: { type: 'string' }, cursor: { type: 'integer' }, newline: { type: 'boolean' },
  headers: { type: 'object', additionalProperties: true }, body: {}, payload: {}, protocol: { type: 'string' }, event: { type: 'string' }, expect_event: { type: 'string' },
  packages: { type: 'array', items: { type: 'string' } }, package: { type: 'string' }, script: { type: 'string' }, args: { type: 'string' },
  database: { type: 'string' }, sql: { type: 'string' }, key: { type: 'string' }, value: { type: 'string' }, message: { type: 'string' }, title: { type: 'string' }, agent: { type: 'string' }, plugin: { type: 'string' }, description: { type: 'string' }, code: { type: 'string' }, tool_args: { type: 'object', additionalProperties: true }, parameters: { type: 'object', additionalProperties: true },
  path2: { type: 'string' }, first: { type: 'string' }, second: { type: 'string' }, question: { type: 'string' }, prompt: { type: 'string' }, model: { type: 'string' }, language: { type: 'string' }, psm: { type: 'integer' }, width: { type: 'integer' }, height: { type: 'integer' }, seed: { type: 'integer' }, enhance: { type: 'boolean' }, safe: { type: 'boolean' }, output: { type: 'string' },
  limit: { type: 'integer' }, max_depth: { type: 'integer' }, recursive: { type: 'boolean' }, overwrite: { type: 'boolean' }, case_sensitive: { type: 'boolean' }
};
function buildNativeToolDefinitions() {
  return Object.entries(MCP_TOOLS).map(([name, description]) => ({
    type: 'function',
    function: {
      name,
      description,
      parameters: { type: 'object', properties: NATIVE_TOOL_PROPERTIES, required: TOOL_REQUIRED_ARGS[name] || [], additionalProperties: true }
    }
  }));
}

function extractBalancedJsonObject(text, startAt = 0) {
  const start = text.indexOf('{', startAt);
  if (start < 0) return null;
  let depth = 0, quoted = false, escaped = false;
  for (let i = start; i < text.length; i++) {
    const ch = text[i];
    if (quoted) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === '"') quoted = false;
      continue;
    }
    if (ch === '"') { quoted = true; continue; }
    if (ch === '{') depth++;
    else if (ch === '}') { depth--; if (depth === 0) return text.slice(start, i + 1); }
  }
  return null;
}
function parseJsonToolCall(text) {
  // Zen-модели иногда оборачивают корректный JSON tool call в ```json, хотя
  // системная инструкция просит TOOL_JSON. Принимаем только явный JSON-объект
  // с полем tool — обычное упоминание инструмента по-прежнему не запускается.
  const marker = text.search(/TOOL_JSON\s*:/i);
  let candidate = marker >= 0 ? extractBalancedJsonObject(text, marker) : null;
  if (!candidate) {
    const fenced = String(text || '').match(/```(?:json)?\s*([\s\S]*?)```/i);
    if (fenced) {
      const body = fenced[1].trim();
      candidate = body.startsWith('{') ? extractBalancedJsonObject(body, 0) : null;
    }
  }
  if (!candidate) {
    const trimmed = String(text || '').trim();
    if (trimmed.startsWith('{') && trimmed.endsWith('}')) candidate = extractBalancedJsonObject(trimmed, 0);
  }

  // <tool_call> ... </tool_call>, which is what Qwen-family models emit.
  //
  // Not a hypothetical: the hub answered "Задача завершена" to every message,
  // including a bare "Ну". The run record showed why - the model had replied
  //     <tool_call>workspace_info</tool_call>
  // and nothing here recognised it, so the tag was handed back as the final
  // answer. The loop ended after one round having done nothing, and the empty
  // reply fell through to the "task complete" placeholder.
  //
  // Two shapes appear in the wild: a bare tool name, and a JSON object. Both
  // are accepted; anything else falls through and is treated as prose, so a
  // model merely talking about a tool still cannot trigger one.
  if (!candidate) {
    const tagged = String(text || '').match(/<tool_call>\s*([\s\S]*?)\s*<\/tool_call>/i);
    if (tagged) {
      const body = tagged[1].trim();
      if (body.startsWith('{')) {
        candidate = extractBalancedJsonObject(body, 0);
      } else if (/^[a-z0-9_]{2,64}$/i.test(body)) {
        // A bare name means "call this with no arguments".
        return { tool: body.toLowerCase(), args: {} };
      } else {
        // Laguna and a few compatible models emit the legacy XML-like form:
        // <tool_call>edit_file<arg_key>path</arg_key><arg_value>…</arg_value></tool_call>
        // It is not XML we execute: accept only a tool identifier plus complete,
        // named key/value pairs, then let the regular schema validator decide
        // whether that tool and its arguments are permitted.
        const name = body.match(/^\s*([a-z0-9_]{2,64})(?=\s*<arg_key>)/i);
        const args = {};
        const pairs = /<arg_key>\s*([^<\s][^<]*?)\s*<\/arg_key>\s*<arg_value>([\s\S]*?)<\/arg_value>/gi;
        let pair, count = 0;
        while ((pair = pairs.exec(body)) !== null) {
          const key = pair[1].trim();
          // Do not let a malformed tag silently overwrite an earlier argument.
          if (!/^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/.test(key) || Object.prototype.hasOwnProperty.call(args, key)) return null;
          args[key] = pair[2]
            .replace(/&lt;/g, '<').replace(/&gt;/g, '>')
            .replace(/&amp;/g, '&').replace(/&quot;/g, '\"');
          count++;
        }
        if (name && count) {
          // Reject arbitrary prose or stray markup outside recognised pairs.
          const remainder = body
            .replace(/^\s*[a-z0-9_]{2,64}\s*/i, '')
            .replace(/<arg_key>\s*[^<\s][^<]*?\s*<\/arg_key>\s*<arg_value>[\s\S]*?<\/arg_value>/gi, '')
            .trim();
          if (!remainder) return { tool: name[1].toLowerCase(), args };
        }
      }
    }
  }

  if (!candidate) return null;
  try {
    const parsed = JSON.parse(candidate);
    if (!parsed || typeof parsed.tool !== 'string' || (parsed.args !== undefined && (typeof parsed.args !== 'object' || Array.isArray(parsed.args)))) return null;
    return { tool: parsed.tool.toLowerCase().trim(), args: parsed.args || {} };
  } catch { return null; }
}
function validateToolArguments(tool, args) {
  if (!MCP_TOOLS[tool]) return `Неизвестный MCP-инструмент: ${tool}`;
  if (!args || typeof args !== 'object' || Array.isArray(args)) return 'args должен быть JSON-объектом.';
  const required = TOOL_REQUIRED_ARGS[tool] || [];
  for (const key of required) {
    // packages может быть массивом, всё остальное — непустой строкой/числом/булевым значением.
    if (args[key] === undefined || args[key] === null || args[key] === '') return `Не хватает обязательного аргумента '${key}' для ${tool}.`;
  }
  for (const key of ['path', 'path2', 'cwd', 'source', 'destination', 'archive', 'database', 'url', 'name', 'id', 'agent', 'plugin', 'command', 'model', 'description', 'code']) {
    if (args[key] !== undefined && typeof args[key] !== 'string') return `Аргумент '${key}' для ${tool} должен быть строкой.`;
  }
  return null;
}

async function handleToolCall(text, writtenFiles) {
  const jsonCall = parseJsonToolCall(text);
  // Не считаем простое упоминание имени инструмента вызовом. Иначе фраза
  // «использую execute_command» превращалась в пустую опасную команду.
  const toolMatch = jsonCall ? null : text.match(/^\s*TOOL:\s*([a-z_]+)/im);
  if (!jsonCall && !toolMatch) return false;

  const toolName = jsonCall ? jsonCall.tool : toolMatch[1].toLowerCase().trim();
  const args = jsonCall ? { ...jsonCall.args } : {};

  if (!jsonCall) {
    const argRegex = /ARG:([^:]+):([\s\S]*?)(?=\nARG:|\nTOOL:|$)/gi;
    let m;
    while ((m = argRegex.exec(text)) !== null) args[m[1].trim()] = m[2].trim();

    if (Object.keys(args).length === 0) {
      const pm = text.match(/PATH:\s*([^\n]+)/i);
      const cm = text.match(/CONTENT:\s*([\s\S]*?)(?:\n\n|TOOL:|$)/i);
      const cmd = text.match(/COMMAND:\s*([^\n]+)/i);
      if (pm) args.path = pm[1].trim();
      if (cm) args.content = cm[1].trim();
      if (cmd) args.command = cmd[1].trim();
    }
  }

  const validationError = validateToolArguments(toolName, args);
  if (validationError) {
    console.log(c(`⚠️ MCP schema: ${validationError}`, 'yellow'));
    history.push({ role: 'assistant', content: text });
    history.push({ role: 'user', content: `Ошибка схемы инструмента: ${validationError}. Исправь вызов, используя TOOL_JSON.` });
    return true;
  }

  printPublicAssistantNote(text);
  const hookCall = await pluginHook('beforeTool', { name: toolName, args: { ...args } });
  if (hookCall?.args && typeof hookCall.args === 'object') Object.assign(args, hookCall.args);
  auditEvent('tool_requested', { tool: toolName, args });
  webRunEvent('tool_requested', { tool: toolName, args: JSON.stringify(args) });

  const iconMap = {
    list_dir: '📂', read_file: '📖', write_file: '✏️',
    edit_file: '📝', delete_file: '🗑️', append_file: '➕',
    execute_command: '⚙️', web_search: '🔍',
    image_info: '🖼️', ocr_image: '🔤', vision_analyze: '👁️', analyze_image: '👁️', vision_ui_audit: '🧩', vision_compare: '🆚', custom_tool_list: '🧰', custom_tool_create: '🛠️', custom_tool_inspect: '🔎', custom_tool_run: '▶️', custom_tool_delete: '🗑️', subagent_list: '👥', subagent_create: '👤', subagent_task: '🤝', subagent_delete: '🗑️', plugin_list: '🧩', plugin_create: '🧩', plugin_inspect: '🔎', plugin_delete: '🗑️', plugin_tool_list: '🧰', plugin_tool_run: '▶️', plugin_provider_list: '🔌',
    ...(capabilitiesModule ? capabilitiesModule.CAPABILITY_ICONS : {}),
    workspace_info: '📍', set_workspace: '📍', project_inspect: '🧭', termux_info: '📱', network_check: '🌐', tree_dir: '🌳', search_text: '🔎', file_info: 'ℹ️', find_files: '🔎',
    file_backup: '💾', file_diff: '🧩', mkdir: '📁', copy_file: '📋', move_file: '🚚', archive_create: '🗜️', archive_extract: '📦',
    process_start: '▶️', process_status: '📊', process_logs: '📜', process_stop: '⏹️', monitor_start: '🩺', monitor_list: '🩺', monitor_logs: '📜', monitor_stop: '⏹️',
    terminal_create: '💻', terminal_write: '⌨️', terminal_read: '📟', terminal_list: '💻', terminal_close: '⏹️',
    http_request: '🌐', health_check: '💓', websocket_test: '🔌', npm_install: '📦', npm_run: '▶️', sqlite_info: '🗃️', sqlite_query: '🗃️', sqlite_schema: '🗃️', sqlite_backup: '💾', env_list: '🔐', env_set: '🔐', env_delete: '🔐', run_tests: '🧪', run_lint: '🧹', code_check: '✅', dependency_audit: '🔐',
    git_status: '🌿', git_diff: '🌿', git_branch: '🌿', git_log: '🌿', git_init: '🌿', git_commit: '🌿', git_clone: '⬇', git_pull: '⬇', git_push: '⬆', open_url: '🌐', clipboard_read: '📋', clipboard_write: '📋', notify: '🔔',
    todo_list: '📋', todo_add: '➕', todo_done: '✅', todo_remove: '🗑️',
  };
  const icon = iconMap[toolName] || '🔧';

  console.log();
  console.log(c(`${icon} ${toolName.toUpperCase()}`, 'brightCyan'));

  let permission = toolPermissionDecision(toolName, args);
  const pluginDecision = await pluginPermission({ name: toolName, args, base: permission.action, mode: CONFIG.agentMode });
  if (pluginDecision) permission = { action: pluginDecision, reason: 'решение lifecycle plugin' };
  if (permission.action === 'deny') {
    const message = `Permission denied: ${permission.reason}`;
    console.log(c(`⛔ ${message}`, 'red'));
    auditEvent('tool_blocked', { tool: toolName, reason: permission.reason });
    webRunEvent('tool_blocked', { tool: toolName, reason: permission.reason });
    history.push({ role: 'assistant', content: text });
    history.push({ role: 'user', content: `Инструмент ${toolName} заблокирован. ${permission.reason}` });
    return true;
  }
  if (permission.action === 'ask') {
    previewTool(toolName, args);
    const decision = await askConfirm(toolName, args);
    if (decision === 'no') {
      auditEvent('tool_denied', { tool: toolName, args });
      webRunEvent('tool_denied', { tool: toolName });
      history.push({ role: 'assistant', content: text });
      history.push({ role: 'user', content: `Пользователь отклонил ${toolName}. Другой подход?` });
      return true;
    }
  }

  TELEMETRY.toolCalls++;
  setRunPhase('tool', toolName);
  const isLiveCommand = LIVE_OUTPUT_TOOLS.has(toolName) && CONFIG.liveToolLogs;
  if (CONFIG.liveToolLogs && !isLiveCommand) {
    const trace = [`${c('вызов:', 'gray')} ${toolName}`, `${c('MCP-папка:', 'gray')} ${WORKSPACE_ROOT}`];
    if (args.path) {
      const resolved = resolveWorkspacePath(args.path);
      trace.push(`${c('путь:', 'gray')} ${resolved.error || resolved.path}`);
    }
    printMcpTrace(trace);
  }
  // Спиннер специально отключён для команд: он не должен прятать живой stdout/stderr.
  const spinner = isLiveCommand ? null : new Spinner(telemetryLiveText(), 'dots');
  if (spinner) spinner.start();
  const telemetryTimer = startTelemetryTicker(spinner);
  const t0 = Date.now();
  webRunEvent('tool_started', { tool: toolName, args: JSON.stringify(args || {}).slice(0, 600), index: String(TELEMETRY.toolCalls) });
  const result = await useTool(toolName, args);
  await pluginHook('afterTool', { name: toolName, args, result });
  TELEMETRY.outputChars += String(result || '').length;
  const ms = Date.now() - t0;
  auditEvent('tool_finished', { tool: toolName, durationMs: ms, result: String(result || '').slice(0, 500) });
  webRunEvent('tool_finished', { tool: toolName, ms: String(ms), durationMs: String(ms), ok: String(!/^(Ошибка|Error|Permission denied)/i.test(String(result || ''))), preview: String(result || '').slice(0, 700) });
  stopTelemetryTicker(telemetryTimer);
  if (spinner) spinner.stop();

  if ((toolName === 'write_file' || toolName === 'append_file') && args.path) {
    const resolved = resolveWorkspacePath(args.path);
    if (!resolved.error) writtenFiles.add(resolved.path);
  }

  let sizeInfo = '';
  if (args.content) {
    sizeInfo = ` | ${Buffer.byteLength(args.content, 'utf8')} bytes, ${args.content.split('\n').length} lines`;
  } else if (args.command) {
    sizeInfo = ` | ${ms}ms`;
  }

  console.log(c(`  ⏱ ${ms}ms${sizeInfo}`, 'gray'));
  formatToolResult(toolName, result, args);

  history.push({ role: 'assistant', content: text });
  history.push({ role: 'user', content: `Результат инструмента ${toolName}:\n${result}` });
  return true;
}

function printPublicAssistantNote(text) {
  if (!CONFIG.showThinking) return;
  let note = String(text || '');
  // Убираем машинные части вызова, оставляя только публичный план/наблюдение.
  note = note
    .replace(/TOOL_JSON\s*:\s*\{[\s\S]*$/i, '')
    .replace(/<tool_call>[\s\S]*?<\/tool_call>/gi, '')
    .replace(/```(?:json)?\s*\{\s*"tool"[\s\S]*?\}\s*```/i, '')
    .replace(/^\s*TOOL:\s*[a-z_]+[\s\S]*$/im, '')
    .trim();
  if (!note || note.length < 3) return;
  const lines = note.split('\n').filter(Boolean).slice(0, 6).map(line => line.slice(0, 260));
  box(lines, { width: Math.min(82, termWidth() - 2), title: ' 🗒 План / наблюдение ', style: 'single', color: 'magenta' }).forEach(line => console.log(line));
}

async function handleNativeToolCalls(toolCalls, writtenFiles) {
  for (const call of toolCalls) {
    const toolName = String(call?.function?.name || '').toLowerCase().trim();
    let args = {};
    try { args = JSON.parse(call?.function?.arguments || '{}'); } catch { args = {}; }
    const validation = validateToolArguments(toolName, args);
    if (validation) {
      const result = `Ошибка схемы native tool call: ${validation}`;
      console.log(c(`⚠️ ${result}`, 'yellow'));
      history.push({ role: 'tool', tool_call_id: call.id, name: toolName || 'unknown', content: result });
      continue;
    }
    console.log(`\n${c('🔧 NATIVE TOOL: ' + toolName, 'brightCyan')}`);
    const hookCall = await pluginHook('beforeTool', { name: toolName, args: { ...args } });
    if (hookCall?.args && typeof hookCall.args === 'object') Object.assign(args, hookCall.args);
    auditEvent('native_tool_requested', { tool: toolName, args });
    webRunEvent('tool_requested', { tool: toolName, args: JSON.stringify(args), native: 'true' });
    let permission = toolPermissionDecision(toolName, args);
    const pluginDecision = await pluginPermission({ name: toolName, args, base: permission.action, mode: CONFIG.agentMode });
    if (pluginDecision) permission = { action: pluginDecision, reason: 'решение lifecycle plugin' };
    if (permission.action === 'deny') {
      const result = `Permission denied: ${permission.reason}`;
      console.log(c(`⛔ ${result}`, 'red'));
      auditEvent('native_tool_blocked', { tool: toolName, reason: permission.reason });
      webRunEvent('tool_blocked', { tool: toolName, reason: permission.reason });
      history.push({ role: 'tool', tool_call_id: call.id, name: toolName, content: result });
      continue;
    }
    if (permission.action === 'ask') {
      previewTool(toolName, args);
      const decision = await askConfirm(toolName, args);
      if (decision === 'no') {
        auditEvent('native_tool_denied', { tool: toolName, args });
        const result = 'Пользователь отклонил выполнение этого инструмента.';
        history.push({ role: 'tool', tool_call_id: call.id, name: toolName, content: result });
        continue;
      }
    }
    TELEMETRY.toolCalls++;
    setRunPhase('tool', toolName);
    const live = LIVE_OUTPUT_TOOLS.has(toolName) && CONFIG.liveToolLogs;
    const spinner = live ? null : new Spinner(telemetryLiveText(), 'dots');
    if (spinner) spinner.start();
    const telemetryTimer = startTelemetryTicker(spinner);
    const t0 = Date.now();
    webRunEvent('tool_started', { tool: toolName, native: 'true' });
    const result = await useTool(toolName, args);
    webRunEvent('tool_finished', { tool: toolName, ms: String(Date.now() - t0), durationMs: String(Date.now() - t0), ok: String(!/^(Ошибка|Error|Permission denied)/i.test(String(result || ''))), preview: String(result || '').slice(0, 700), native: 'true' });
    await pluginHook('afterTool', { name: toolName, args, result });
    TELEMETRY.outputChars += String(result || '').length;
    auditEvent('native_tool_finished', { tool: toolName, durationMs: Date.now() - t0, result: String(result || '').slice(0, 500) });
    stopTelemetryTicker(telemetryTimer);
    if (spinner) spinner.stop();
    console.log(c(`  ⏱ ${Date.now() - t0}ms`, 'gray'));
    formatToolResult(toolName, result, args);
    if ((toolName === 'write_file' || toolName === 'append_file') && args.path) {
      const resolved = resolveWorkspacePath(args.path); if (!resolved.error) writtenFiles.add(resolved.path);
    }
    history.push({ role: 'tool', tool_call_id: call.id, name: toolName, content: result });
  }
}

// ═══════════════════════════════════════════════════════════════════
//  MAIN AGENT LOOP
// ═══════════════════════════════════════════════════════════════════
async function agentLoop(userInput) {
  const startTime = Date.now();
  const writtenFiles = new Set();
  agentBusy = true;
  abortRequested = false;
  activeProviderAbort = null;
  beginAgentTelemetry(userInput);
  auditEvent('task_started', { inputChars: String(userInput || '').length, model: currentModel });
  await pluginHook('event', { type: 'task.started', provider: currentProvider, model: currentModel, mode: CONFIG.agentMode, inputChars: String(userInput || '').length });

  history.push({ role: 'user', content: userInput });

  let finalAnswer = '';
  let lastRes = null;

  // agentBusy used to be cleared only by the successful path at the very
  // bottom of this function. Anything that threw on the way - a provider
  // error, a bad tool result, a network drop - left it stuck at true, and
  // from then on every /api/agent/run answered "The agent is already busy"
  // with no way back except restarting the process. The whole body is now
  // wrapped so the flag is released on every exit path.
  try {

  for (let step = 0; step < agentStepLimit(); step++) {
    TELEMETRY.step = step + 1;
    webRunEvent('round_started', {
      step: String(TELEMETRY.step),
      limit: String(agentStepLimit()),
      model: String(currentModel),
      toolCalls: String(TELEMETRY.toolCalls),
      elapsedMs: String(TELEMETRY.startedAt ? Date.now() - TELEMETRY.startedAt : 0)
    });
    if (abortRequested) { finalAnswer = 'Задача остановлена пользователем.'; setRunPhase('stopped', 'пользователь'); break; }
    if (correctionQueue.length) {
      const correction = correctionQueue.splice(0).join('\n');
      history.push({ role: 'user', content: `Корректировка пользователя во время выполнения:
${correction}` });
      console.log(c('↪ Корректировка добавлена в контекст.', 'yellow'));
    }
    try {
      setRunPhase('model', providerDisplayName());
      const requestMessages = messagesForProvider();
      TELEMETRY.requestChars = requestMessages.reduce((sum, msg) => sum + String(msg.content || '').length, 0);
      TELEMETRY.estimatedInputTokens = estimateTokens(TELEMETRY.requestChars);
      auditEvent('model_request', { step: TELEMETRY.step, model: currentModel, requestChars: TELEMETRY.requestChars, estimatedInputTokens: TELEMETRY.estimatedInputTokens });
      // Поток Zen/OpenRouter сам выводит токены и start/end timing; plugin provider пока non-stream.
      const willStream = CONFIG.streamMode && (currentProvider === 'zen' || currentProvider === 'openrouter');
      const spinner = willStream ? null : new Spinner(telemetryLiveText(), 'dots');
      if (spinner) spinner.start();
      const telemetryTimer = startTelemetryTicker(spinner);
      let res;
      try { res = await callCurrentProvider(); }
      catch (firstError) {
        // The fallback used to be gated on streamMode. The web console turns
        // streaming off for every run it launches, so in the browser this
        // branch never ran: the first hiccup from a free model - a 500, a
        // dropped socket, a rate limit - ended the whole task. In the terminal
        // the same failure quietly switched models and carried on, which is
        // why the CLI felt reliable and the chat did not. Retry either way.
        if (currentProvider === 'zen') {
          console.log(c('⚠️ Zen не ответил, пробую ещё раз с запасной моделью…', 'yellow'));
          setRunPhase('model', 'повтор после ошибки');
          res = await callZenWithRetry(messagesForProvider(), currentModel, undefined, false);
        }
        else { stopTelemetryTicker(telemetryTimer); if (spinner) spinner.stop(); throw firstError; }
      }
      recordProviderResult(res);
      webRunEvent('model_reply', {
        step: String(TELEMETRY.step),
        chars: String(String(res.text || '').length),
        promptTokens: String(res.usage?.prompt_tokens ?? TELEMETRY.estimatedInputTokens ?? 0),
        completionTokens: String(res.usage?.completion_tokens ?? 0),
        totalTokens: String(res.usage?.total_tokens ?? 0),
        // The hub must never show a raw fallback tool payload as chat prose.
        // handleToolCall below will turn a recognised payload into a tool card.
        preview: String(res.text || '').replace(/<tool_call>[\s\S]*$/i, '').replace(/TOOL_JSON\s*:\s*\{[\s\S]*$/i, '').slice(0, 400)
      });
      auditEvent('model_response', { step: TELEMETRY.step, model: res.model || currentModel, outputChars: String(res.text || '').length, usage: res.usage || {}, toolCalls: Array.isArray(res.toolCalls) ? res.toolCalls.length : 0 });
      stopTelemetryTicker(telemetryTimer);
      if (spinner) spinner.stop();
      lastRes = res;

      let text = res.text || '';

      if (res.thinking && CONFIG.showThinking && CONFIG.verbose) {
        console.log(c('[💭 Провайдер вернул reasoning-канал. Вместо скрытой цепочки показан публичный план и проверяемые действия.]', 'gray'));
      }

      // Native OpenRouter tool_calls имеют приоритет над текстовым fallback-протоколом.
      if (currentProvider !== 'zen' && Array.isArray(res.toolCalls) && res.toolCalls.length) {
        printPublicAssistantNote(text);
        history.push({ role: 'assistant', content: text || '', tool_calls: res.toolCalls });
        await handleNativeToolCalls(res.toolCalls, writtenFiles);
        continue;
      }

      if (correctionQueue.length) continue;

      // The stream path substitutes this string for an empty body, so a blank
      // reply arrives here as prose and is reported as a real answer.
      if (text.trim() === 'Модель вернула пустой ответ.') text = '';

      if (!text || text.length < 8) {
        // The nudge is a throwaway - it must not stay in history, or every
        // later turn is trained to expect it and the window fills with
        // instructions to nobody. It used to be pushed unconditionally, and a
        // second empty reply pushed a second copy.
        const nudge = { role: 'user', content: 'Используй доступные инструменты или дай конкретный ответ.' };
        try {
          history.push(nudge);
          setRunPhase('model', 'повторный запрос');
          const r2 = await callCurrentProvider();
          recordProviderResult(r2);
          if (r2.text && r2.text.trim() && r2.text.trim() !== 'Модель вернула пустой ответ.') text = r2.text;
          if (currentProvider !== 'zen' && r2.toolCalls?.length) {
            const at = history.indexOf(nudge); if (at !== -1) history.splice(at, 1);
            history.push({ role: 'assistant', content: r2.text || '', tool_calls: r2.toolCalls });
            await handleNativeToolCalls(r2.toolCalls, writtenFiles);
            continue;
          }
        } catch (e) {
          auditEvent('empty_reply_retry_failed', { step: TELEMETRY.step, error: String(e && e.message || e) });
        } finally {
          const at = history.indexOf(nudge); if (at !== -1) history.splice(at, 1);
        }
      }

      if (CONFIG.autoUseTools && await handleToolCall(text, writtenFiles)) {
        continue;
      }

      // Still nothing after the retry. Ending the loop with an empty
      // finalAnswer produced a run marked "completed" carrying no text - the
      // console printed nothing at all and looked frozen. Say what happened
      // and name the model, since the usual cause is a free model that has
      // stopped serving this session.
      if (!text.trim()) {
        finalAnswer = `Модель ${currentModel} вернула пустой ответ дважды подряд. ` +
          'Обычно это исчерпанный лимит бесплатной модели или слишком длинный контекст. ' +
          'Смените модель или начните новую сессию — /clear.';
        setRunPhase('error', 'пустой ответ модели');
        webRunEvent('empty_reply', { step: String(TELEMETRY.step), model: String(currentModel) });
        history.push({ role: 'assistant', content: finalAnswer });
        break;
      }

      finalAnswer = text;
      if (CONFIG.streamMode && res.outputShown) finalAnswer = '';
      history.push({ role: 'assistant', content: text });
      break;
    } catch (err) {
      if (abortRequested) { finalAnswer = 'Задача остановлена пользователем.'; setRunPhase('stopped', 'пользователь'); break; }
      const em = (err && err.message) ? err.message : (String(err) || 'неизвестная ошибка');
      finalAnswer = `Ошибка: ${em}`;
      auditEvent('model_error', { step: TELEMETRY.step, error: em });
      setRunPhase('error', em.slice(0, 80));
      console.log(c('\n❌ ' + finalAnswer, 'red'));
      break;
    }
  }

  // ═════════════════════════════════════════════════════════════════
  //  VERIFY: no stubs / no truncated JS
  // ═════════════════════════════════════════════════════════════════
  if (!finalAnswer.startsWith('Ошибка') && writtenFiles.size) {
    const jsFiles = [...writtenFiles].filter(f => f.endsWith('.js') && fs.existsSync(f));
    if (jsFiles.length) {
      for (let v = 0; v < 3; v++) {
        const errors = [];
        for (const f of jsFiles) {
          try {
            execSync(`"${process.execPath}" --check "${f}"`, { encoding: 'utf8', timeout: 10000, stdio: ['ignore', 'pipe', 'pipe'] });
          } catch (e) {
            const msg = (e.stderr || e.stdout || e.message || '').split('\n').slice(0, 8).join('\n');
            errors.push(`${f}:\n${msg}`);
          }
        }
        if (!errors.length) {
          if (v > 0) console.log(c('\n✅ Синтаксис всех файлов корректен', 'green'));
          break;
        }
        console.log(c(`\n🔍 Ошибки синтаксиса (попытка ${v + 1}/3), исправляю...`, 'yellow'));
        const prevAuto = CONFIG.autoApprove;
        CONFIG.autoApprove = true;
        try {
          const fixMsg = 'Исправь ошибки синтаксиса в следующих файлах, используя edit_file или append_file. Пиши ПОЛНЫЙ рабочий код, без заглушек, TODO и "реализовать позже".\n\n' + errors.join('\n\n');
          history.push({ role: 'user', content: fixMsg });
          const r = await callCurrentProvider();
          if (currentProvider !== 'zen' && r.toolCalls?.length) {
            history.push({ role: 'assistant', content: r.text || '', tool_calls: r.toolCalls });
            await handleNativeToolCalls(r.toolCalls, writtenFiles);
          } else {
            let guard = 0;
            let cur = r.text || '';
            while (cur && guard++ < agentStepLimit()) {
              if (await handleToolCall(cur, writtenFiles)) {
                const nr = await callCurrentProvider();
                if (currentProvider !== 'zen' && nr.toolCalls?.length) {
                  history.push({ role: 'assistant', content: nr.text || '', tool_calls: nr.toolCalls });
                  await handleNativeToolCalls(nr.toolCalls, writtenFiles);
                  break;
                }
                cur = nr.text || '';
              } else break;
            }
          }
        } catch (e) {
          console.log(c('⚠️ Не удалось авто-исправить: ' + (e.message || e), 'red'));
        } finally {
          CONFIG.autoApprove = prevAuto;
        }
      }
    }
  }

  const took = ((Date.now() - startTime) / 1000).toFixed(1);
  if (finalAnswer && !CONFIG.streamMode) {
    console.log();
    formatFinalAnswer(finalAnswer);
  }
  if (lastRes && lastRes.usage) {
    const u = lastRes.usage;
    const tot = u.total_tokens || ((u.prompt_tokens || 0) + (u.completion_tokens || 0));
    if (tot) {
      const bar = miniProgress(tot, 32000, 16);
      console.log(c(`\n${bar} ≈${tot} tok • ${took}s`, 'gray'));
    }
  } else if (finalAnswer) {
    console.log(c(`\n${took}s`, 'gray'));
  }

  if (TELEMETRY.phase !== 'error' && TELEMETRY.phase !== 'stopped') setRunPhase('complete', 'результат готов');
  auditEvent('task_finished', { phase: TELEMETRY.phase, durationMs: TELEMETRY.startedAt ? Date.now() - TELEMETRY.startedAt : 0, steps: TELEMETRY.step, toolCalls: TELEMETRY.toolCalls, usage: TELEMETRY.usage || {} });
  await pluginHook('event', { type: 'task.finished', phase: TELEMETRY.phase, steps: TELEMETRY.step, toolCalls: TELEMETRY.toolCalls });
  drawTelemetryPanel(' Итог выполнения ');
  saveHistory();
  setRunPhase('user-control', 'ожидание следующей команды');
  console.log(c('▣ Управление снова у вас. Можно дать следующую задачу или корректировку.', 'brightGreen'));
  return finalAnswer;

  } finally {
    // The single place the flag is released. Runs on success, on a thrown
    // error and on an early return alike.
    agentBusy = false;
    activeProviderAbort = null;
  }
}

// ═══════════════════════════════════════════════════════════════════
//  INTERACTIVE MODE
// ═══════════════════════════════════════════════════════════════════
function getHelp() {
  const tw = termWidth();
  const w = Math.min(64, tw - 4);
  const lines = [
    `${c('КОМАНДЫ', 'cyan')}`,
    `  ${c('/help', 'brightCyan')}        — показать справку`,
    `  ${c('/tools', 'brightCyan')}       — полный список MCP-инструментов`,
    `  ${c('/custom-tools', 'brightCyan')} — локальные само-созданные tools`,
    `  ${c('/plugins', 'brightCyan')}     — lifecycle plugins`,
    `  ${c('/subagents', 'brightCyan')}   — встроенные и local subagents`,
    `  ${c('/mode [build|plan|explore]', 'brightCyan')} — режим агента`,
    `  ${c('/long [on|off]', 'brightCyan')} — долгие задачи и команды до 1 часа`,
    `  ${c('/zen [модель]', 'brightCyan')} — режим бесплатных Zen-моделей`,
    `  ${c('/open [модель]', 'brightCyan')} — OpenRouter + native tool calls`,
    `  ${c('/provider [id]', 'brightCyan')} — выбрать встроенный/plugin provider`,
    `  ${c('/key', 'brightCyan')}         — задать OpenRouter key через Android password-dialog`,
    `  ${c('/key status|clear', 'brightCyan')} — проверить / удалить ключ`,
    `  ${c('/vision [модель]', 'brightCyan')} — vision-модель для скриншотов`,
    `  ${c('/models [N|id]', 'brightCyan')} — список / выбор модели провайдера`,
    `  ${c('/session', 'brightCyan')}      — список AI-сессий`,
    `  ${c('/session new ИМЯ', 'brightCyan')} — новая / переключение сессии`,
    `  ${c('/session fork ИМЯ', 'brightCyan')} — ветка текущей сессии`,
    `  ${c('/session export [путь]', 'brightCyan')} — экспорт JSON`,
    `  ${c('/session import путь ИМЯ', 'brightCyan')} — импорт JSON`,
    `  ${c('/continue', 'brightCyan')}     — продолжить активную сессию`,
    `  ${c('/correct текст', 'brightCyan')} — корректировка во время работы`,
    `  ${c('/abort', 'brightCyan')}        — остановить работу после текущего шага`,
    `  ${c('/mcp', 'brightCyan')}         — статус MCP и рабочая папка`,
    `  ${c('/status', 'brightCyan')}      — токены, время и текущая фаза`,
    `  ${c('/audit [N]', 'brightCyan')}     — журнал действий текущего проекта`,
    `  ${c('/workspace [путь]', 'brightCyan')} — показать / сменить MCP-папку`,
    `  ${c('/net', 'brightCyan')}         — проверить доступ к серверу моделей`,
    `  ${c('/vpn', 'brightCyan')}         — подсказка для системного Android VPN`,
    `  ${c('/proxy [URL|off|test]', 'brightCyan')} — необязательный прокси для Wi‑Fi`,
    `  ${c('/stream', 'brightCyan')}      — переключить стриминг`,
    `  ${c('/logs', 'brightCyan')}        — включить/выключить live stdout/stderr`,
    `  ${c('/animation [on|off]', 'brightCyan')} — ANSI-перерисовка loading bar`,
    `  ${c('/indicator [вид]', 'brightCyan')} — выбор вида индикатора стрелками`,
    `  ${c('/auto [on|off]', 'brightCyan')} — авто-одобрение инструментов`,
    `  ${c('/think', 'brightCyan')}       — показать/скрыть публичный план и наблюдения`,
    `  ${c('/clarify [on|off]', 'brightCyan')} — уточняющие вопросы перед работой`,
    `  ${c('/clear', 'brightCyan')}       — очистить историю`,
    `  ${c('/save', 'brightCyan')}        — сохранить историю`,
    `  ${c('/dash', 'brightCyan')}        — показать/скрыть дашборд`,
    `  ${c('/compact', 'brightCyan')}      — компактный режим`,
    `  ${c('/todo текст', 'brightCyan')}   — добавить задачу`,
    `  ${c('/todos', 'brightCyan')}        — список задач`,
    `  ${c('/done N', 'brightCyan')}       — отметить задачу выполненной`,
    `  ${c('/rm N', 'brightCyan')}         — удалить задачу`,
    `  ${c('/clear-todo', 'brightCyan')}   — очистить все задачи`,
    `  ${c('/exit', 'brightCyan')}        — выход`,
    ``,
    `${c('ПРИМЕРЫ', 'cyan')}`,
    `  создай файл test.txt с текстом Привет`,
    `  прочитай package.json`,
    `  выполни ls -la`,
    `  что в текущей папке?`,
  ];
  box(lines, { width: w, title: ' Справка ', style: 'double', color: 'cyan' }).forEach(l => console.log(l));
}

function showTools() {
  const groups = [
    ['Рабочая папка и файлы', ['workspace_info','set_workspace','project_inspect','tree_dir','list_dir','find_files','search_text','file_info','read_file','write_file','edit_file','append_file','delete_file','mkdir','copy_file','move_file','file_backup','file_diff','archive_create','archive_extract']],
    ['Процессы, мониторинг и терминал', ['process_start','process_status','process_logs','process_stop','monitor_start','monitor_list','monitor_logs','monitor_stop','terminal_create','terminal_write','terminal_read','terminal_list','terminal_close','http_request','health_check','websocket_test']],
    ['Код, npm, SQLite и Git', ['npm_install','npm_run','run_tests','run_lint','code_check','dependency_audit','sqlite_info','sqlite_query','sqlite_schema','sqlite_backup','env_list','env_set','env_delete','git_status','git_diff','git_branch','git_log','git_init','git_commit']],
    ['Vision и изображения', ['image_info','ocr_image','vision_analyze','vision_ui_audit','vision_compare','read_image']],
    ['Саморасширение (песочница vm)', ['custom_tool_list','custom_tool_create','custom_tool_inspect','custom_tool_run','custom_tool_delete']],
    ['Capabilities (реальные процессы: adb, RDP, GUI, Python)', ['capability_templates','capability_list','capability_create','capability_install','capability_run','capability_logs','capability_stop','capability_inspect','capability_delete']],
    ['Subagents', ['subagent_list','subagent_create','subagent_task','subagent_delete']],
    ['Lifecycle plugins', ['plugin_list','plugin_create','plugin_inspect','plugin_tool_list','plugin_tool_run','plugin_provider_list','plugin_delete']],
    ['Сеть и Android', ['network_check','web_search','download_file','open_url','clipboard_read','clipboard_write','notify','termux_info']],
    ['Планирование', ['todo_list','todo_add','todo_done','todo_remove','execute_command']]
  ];
  const w = Math.min(90, termWidth() - 2);
  const lines = [`${c(`Всего MCP-инструментов: ${Object.keys(MCP_TOOLS).length}`, 'brightCyan')}`, ''];
  for (const [title, names] of groups) {
    lines.push(c(title, 'yellow'));
    for (const name of names) if (MCP_TOOLS[name]) lines.push(`  ${c(name, 'brightCyan')} — ${MCP_TOOLS[name]}`);
    lines.push('');
  }
  box(lines, { width: w, title: ' MCP Tools ', style: 'double', color: 'cyan' }).forEach(line => console.log(line));
}

async function selectModel(rl) {
  const tw = termWidth();
  const w = Math.min(56, tw - 4);
  const lines = ZEN_MODELS.map((m, i) => `  ${c(String(i + 1) + '.', 'yellow')} ${c(m.id, 'brightCyan')} — ${m.name} (${m.ctx})`);
  box(lines, { width: w, title: ' Модели ', style: 'single', color: 'cyan' }).forEach(l => console.log(l));

  const ans = await new Promise(r => rl.question(c('Выбери [1-' + ZEN_MODELS.length + ']: ', 'yellow'), r));
  const n = parseInt(ans.trim(), 10);
  if (n >= 1 && n <= ZEN_MODELS.length) {
    currentModel = ZEN_MODELS[n - 1].id;
    console.log(c('✓ Модель: ' + currentModel, 'green'));
  }
}

async function chooseCurrentProviderModel(spec = '') {
  const models = currentProvider === 'openrouter' ? await fetchOpenRouterFreeModels() : ZEN_MODELS;
  const raw = String(spec || '').trim();
  if (!raw) {
    const opened = openArrowMenu(`Модели • ${currentProvider}`, models.map(m => ({ label: m.id, description: `${m.name} (${m.ctx})`, model: m })), async option => {
      currentModel = option.model.id;
      if (currentProvider === 'openrouter') CONFIG.openRouterModel = currentModel;
      saveHistory(); console.log(c(`✓ ${currentProvider}: ${currentModel}`, 'green'));
    });
    if (opened) return;
    const lines = models.map((m, i) => `  ${c(String(i + 1) + '.', 'yellow')} ${c(m.id, 'brightCyan')} — ${m.name} (${m.ctx})`);
    lines.unshift(c(`Провайдер: ${currentProvider}`, 'gray'));
    lines.push(c('Выбор: /models N  или  /models id_модели', 'gray'));
    box(lines, { width: Math.min(78, termWidth() - 2), title: ' Модели ', style: 'single', color: 'cyan' }).forEach(line => console.log(line));
    return;
  }
  const index = parseInt(raw, 10);
  const selected = Number.isInteger(index) && index >= 1 && index <= models.length ? models[index - 1] : models.find(m => m.id === raw) || { id: raw, name: raw };
  currentModel = selected.id;
  if (currentProvider === 'openrouter') CONFIG.openRouterModel = currentModel;
  saveHistory();
  console.log(c(`✓ ${currentProvider}: ${currentModel}`, 'green'));
}
function drawSessions() {
  const rows = listSessions();
  const lines = rows.length ? rows.map(item => `${item.active ? c('●', 'green') : c('○', 'gray')} ${c(item.name, 'brightCyan')} — ${item.messages} msg • ${item.provider} • ${item.model}`) : [c('Сессий нет.', 'gray')];
  lines.push('', c('Команды: /session new ИМЯ | /session ИМЯ | /session delete ИМЯ', 'gray'));
  box(lines, { width: Math.min(90, termWidth() - 2), title: ' Сессии ', style: 'single', color: 'cyan' }).forEach(line => console.log(line));
}

async function main() {
  loadOpenRouterKey();
loadPresets();
  loadHistory();
  rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  let inputClosed = false;
  rl.on('close', () => { inputClosed = true; });
  startEmbeddedServer();
  await new Promise(r => setTimeout(r, 300));
  await checkNetwork();
  await checkMCP();
  printBanner();

  const prompt = () => {
    if (inputClosed || agentBusy || pendingConfirmation || activeArrowMenu) return;
    process.stdout.write(CONFIG.compactMode ? c('▶ ', 'green') : c('\n┌─[zen]─▶ ', 'green'));
  };
  promptRenderer = prompt;
  const finishCommand = () => { if (!agentBusy && !pendingConfirmation && !activeArrowMenu) prompt(); };

  async function handleIdleInput(text) {
    const lower = text.toLowerCase();
    if (looksLikeOpenRouterKey(text)) {
      const result = saveKeyFromCommand(text);
      console.log(result.error ? c('✗ ' + result.error, 'red') : c(`✓ OpenRouter key сохранён: ${result.masked}. Не отправляю ключ модели.`, 'green'));
      finishCommand(); return;
    }
    if (lower === '/exit' || lower === '/quit' || lower === '/q') {
      saveHistory();
      for (const monitor of PROCESS_MONITORS.values()) clearInterval(monitor.timer);
      for (const session of TERMINAL_SESSIONS.values()) { try { session.proc.kill('SIGTERM'); } catch {} }
      rl.close();
      if (embeddedServer?.listening) embeddedServer.close(() => process.exit(0));
      else process.exit(0);
      return;
    }
    if (lower === '/help' || lower === '/?') { getHelp(); finishCommand(); return; }
    if (lower === '/tools') { showTools(); finishCommand(); return; }
    if (lower === '/custom-tools' || lower === '/customtools') {
      const custom = customToolListTool();
      const lines = custom.tools.length ? custom.tools.map(tool => `${tool.name} — ${tool.description}`) : [c('Нет custom tools. Агент создаст их при необходимости в .zen-agent/custom-tools.', 'gray')];
      lines.unshift(c('Папка: ' + custom.directory, 'gray'));
      box(lines, { width: Math.min(90, termWidth() - 2), title: ' Custom tools ', style: 'single', color: 'cyan' }).forEach(line => console.log(line));
      finishCommand(); return;
    }
    if (lower === '/mode' || lower.startsWith('/mode ')) {
      const value = lower.replace(/^\/mode\s*/, '').trim();
      if (!value) {
        const options = Object.entries(AGENT_MODES).map(([id, meta]) => ({ label: meta.label, description: meta.description, mode: id }));
        const opened = openArrowMenu('Режим агента', options, async option => {
          const changed = setAgentMode(option.mode);
          console.log(c(`✓ Режим: ${changed.label}`, 'green'));
        });
        if (!opened) console.log(c('Режимы: build, plan, explore. Пример: /mode plan', 'gray'));
      } else if (AGENT_MODES[value]) {
        const changed = setAgentMode(value);
        console.log(c(`✓ Режим: ${changed.label} — ${changed.description}`, 'green'));
      } else console.log(c('Неизвестный режим. Используй build, plan или explore.', 'red'));
      finishCommand(); return;
    }
    if (lower === '/plugins') {
      const plugins = pluginListTool();
      const lines = plugins.plugins.length ? plugins.plugins.map(p => `${p.enabled ? '●' : '○'} ${p.name} — ${p.description}`) : [c('Нет lifecycle plugins.', 'gray')];
      lines.unshift(c('Папка: ' + plugins.directory, 'gray'));
      box(lines, { width: Math.min(90, termWidth() - 2), title: ' Plugins ', style: 'single', color: 'cyan' }).forEach(line => console.log(line));
      finishCommand(); return;
    }
    if (lower === '/subagents') {
      const agents = subagentListTool();
      const lines = [c('Built-in:', 'yellow'), ...Object.entries(agents.builtins).map(([name, value]) => `${name} — ${value.description}`), '', c('Local:', 'yellow'), ...(agents.custom.length ? agents.custom.map(a => `${a.name} — ${a.description}`) : [c('нет', 'gray')])];
      box(lines, { width: Math.min(90, termWidth() - 2), title: ' Subagents ', style: 'single', color: 'cyan' }).forEach(line => console.log(line));
      finishCommand(); return;
    }
    if (lower === '/long' || lower.startsWith('/long ')) {
      const value = lower.replace(/^\/long\s*/, '').trim();
      CONFIG.longTaskMode = value === 'on' || value === '1' || value === 'да' ? true : value === 'off' || value === '0' || value === 'нет' ? false : !CONFIG.longTaskMode;
      saveHistory();
      console.log(c(`Долгие задачи: ${CONFIG.longTaskMode ? 'ВКЛ' : 'ВЫКЛ'} • шагов до ${agentStepLimit()} • команда до ${Math.round((CONFIG.longTaskMode ? CONFIG.longCommandTimeoutMs : 120000) / 60000)} мин`, CONFIG.longTaskMode ? 'green' : 'yellow'));
      finishCommand(); return;
    }
    if (lower === '/models' || lower === '/model' || lower.startsWith('/models ') || lower.startsWith('/model ')) {
      const spec = text.replace(/^\/models?\s*/i, '').trim(); await chooseCurrentProviderModel(spec); finishCommand(); return;
    }
    if (lower === '/key' || lower.startsWith('/key ')) {
      const value = text.replace(/^\/key\s*/i, '').trim();
      if (!value) {
        if (!openSecretKeyInput()) console.log(c('Защищённый Android password-dialog недоступен. Установи Termux:API либо используй /key set ТВОЙ_КЛЮЧ (ключ будет виден в scrollback).', 'yellow'));
      } else if (value.toLowerCase() === 'status') {
        const status = openRouterKeyStatus();
        console.log(c(`OpenRouter key: ${status.configured ? status.masked : 'не задан'} • ${status.source}`, status.configured ? 'green' : 'yellow'));
      } else if (value.toLowerCase() === 'clear' || value.toLowerCase() === 'remove') {
        const cleared = clearOpenRouterKey();
        console.log(c(cleared.environmentStillSet ? 'Локальный ключ удалён, но OPENROUTER_API_KEY всё ещё задан в окружении.' : '✓ Локальный OpenRouter key удалён.', 'yellow'));
      } else {
        const result = saveKeyFromCommand(value);
        console.log(result.error ? c('✗ ' + result.error, 'red') : c(`✓ OpenRouter key сохранён: ${result.masked}`, 'green'));
      }
      finishCommand(); return;
    }
    if (lower === '/vision' || lower.startsWith('/vision ')) {
      const model = text.replace(/^\/vision\s*/i, '').trim();
      if (!model) console.log(c(`Vision model: ${CONFIG.visionModel}. Используй: /vision MODEL_ID`, 'brightCyan'));
      else { CONFIG.visionModel = model; saveHistory(); console.log(c('✓ Vision model: ' + CONFIG.visionModel, 'green')); }
      finishCommand(); return;
    }
    if (lower === '/provider' || lower.startsWith('/provider ')) {
      const value = text.replace(/^\/provider\s*/i, '').trim();
      const providers = [{ id: 'zen', label: 'Zen', description: 'Встроенные Zen модели' }, { id: 'openrouter', label: 'OpenRouter', description: 'OpenAI-compatible native tools' }, { id: 'local', label: 'Local AI', description: 'Только при реально запущенном локальном runtime; настрой в /local-ai.' }, ...pluginProviderListTool().providers.map(p => ({ id: p.id, label: `Plugin: ${p.id}`, description: p.description || p.endpoint }))];
      if (!value) {
        const opened = openArrowMenu('Provider', providers.map(p => ({ label: p.label, description: p.description, provider: p })), async option => { currentProvider = option.provider.id; console.log(c(`✓ Provider: ${currentProvider}`, 'green')); saveHistory(); });
        if (!opened) console.log(c('Providers: ' + providers.map(p => p.id).join(', '), 'gray'));
      } else {
        const selected = providers.find(p => p.id === value);
        if (!selected) console.log(c('Provider не найден. Используй /provider без аргумента.', 'red'));
        else { currentProvider = selected.id; saveHistory(); console.log(c(`✓ Provider: ${currentProvider}`, 'green')); }
      }
      finishCommand(); return;
    }
    if (lower === '/open' || lower.startsWith('/open ')) {
      currentProvider = 'openrouter';
      await fetchOpenRouterFreeModels();
      const spec = text.replace(/^\/open\s*/i, '').trim();
      currentModel = spec || CONFIG.openRouterModel || openRouterFreeModels[0]?.id || 'openrouter/free';
      CONFIG.openRouterModel = currentModel;
      saveHistory();
      console.log(c(`✓ Режим OpenRouter: ${currentModel}`, 'green'));
      if (!openRouterKey()) console.log(c('⚠️ Сначала добавь ключ: /key', 'yellow'));
      console.log(c('Бесплатные модели: /models', 'gray'));
      finishCommand(); return;
    }
    if (lower === '/zen' || lower.startsWith('/zen ')) {
      currentProvider = 'zen';
      const spec = text.replace(/^\/zen\s*/i, '').trim();
      currentModel = spec || CONFIG.defaultModel;
      saveHistory(); console.log(c(`✓ Режим Zen: ${currentModel}`, 'green')); finishCommand(); return;
    }
    if (lower === '/continue') {
      loadHistory();
      const info = sessionInfoTool();
      console.log(info.error ? c('✗ ' + info.error, 'red') : c(`✓ Продолжена сессия: ${info.name} • ${info.messages} сообщений • ${info.provider}/${info.model}`, 'green'));
      finishCommand(); return;
    }
    if (lower === '/sessions' || lower === '/session') {
      const rows = listSessions();
      const opened = openArrowMenu('AI-сессии', rows.map(item => ({ label: item.name, description: `${item.messages} msg • ${item.provider} • ${item.model}`, session: item })), async option => {
        const changed = switchSession(option.session.name);
        console.log(changed.error ? c('✗ ' + changed.error, 'red') : c(`✓ Сессия: ${changed.name} (${changed.messages} msg)`, 'green'));
      });
      if (!opened) drawSessions();
      finishCommand(); return;
    }
    if (lower.startsWith('/session ')) {
      const parts = text.slice('/session '.length).trim().split(/\s+/); const action = (parts.shift() || '').toLowerCase(); const name = parts.join(' ');
      if (action === 'new' || action === 'switch') {
        const changed = switchSession(name); console.log(changed.error ? c('✗ ' + changed.error, 'red') : c(`✓ Сессия: ${changed.name} (${changed.messages} msg)`, 'green'));
      } else if (action === 'fork') {
        const forked = forkSession(name); console.log(forked.error ? c('✗ ' + forked.error, 'red') : c(`✓ Ветка сессии: ${forked.name} ← ${forked.parent}`, 'green'));
      } else if (action === 'rename') {
        const renamed = renameSession(name); console.log(renamed.error ? c('✗ ' + renamed.error, 'red') : c('✓ Сессия переименована: ' + renamed.name, 'green'));
      } else if (action === 'delete' || action === 'rm') {
        const removed = deleteSession(name); console.log(removed.error ? c('✗ ' + removed.error, 'red') : c('✓ Сессия удалена: ' + removed.name, 'green'));
      } else if (action === 'info') {
        const info = sessionInfoTool(name || activeSession); console.log(info.error ? c('✗ ' + info.error, 'red') : JSON.stringify(info, null, 2));
      } else if (action === 'export') {
        const exported = exportSession(name || undefined); console.log(exported.error ? c('✗ ' + exported.error, 'red') : c('✓ Экспорт: ' + exported.path, 'green'));
      } else if (action === 'import') {
        const importPath = parts.shift(); const importName = parts.join(' ');
        const imported = importSession(importPath, importName); console.log(imported.error ? c('✗ ' + imported.error, 'red') : c(`✓ Импортирована сессия: ${imported.name}`, 'green'));
      } else {
        const changed = switchSession([action, ...parts].join(' ')); console.log(changed.error ? c('✗ ' + changed.error, 'red') : c(`✓ Сессия: ${changed.name} (${changed.messages} msg)`, 'green'));
      }
      finishCommand(); return;
    }
    if (lower === '/audit' || lower.startsWith('/audit ')) {
      const count = parseInt(lower.replace(/^\/audit\s*/, ''), 10) || 30;
      const records = readAudit(count);
      const lines = records.length ? records.map(item => `${item.at} • ${item.event}${item.tool ? ' • ' + item.tool : ''}${item.durationMs !== undefined ? ' • ' + item.durationMs + 'ms' : ''}${item.error ? ' • ' + item.error : ''}`) : [c('Журнал пока пуст.', 'gray')];
      box(lines, { width: Math.min(100, termWidth() - 2), title: ' Audit trail ', style: 'single', color: 'cyan' }).forEach(line => console.log(line));
      finishCommand(); return;
    }
    if (lower === '/mcp' || lower === '/status') {
      await checkMCP();
      console.log(c('MCP: ', 'gray') + (mcpAvailable ? c('● подключён', 'green') : c('○ недоступен', 'gray')));
      console.log(c(`Провайдер: ${currentProvider} • Модель: ${currentModel} • Сессия: ${activeSession}`, 'brightCyan'));
      console.log(c('Рабочая папка MCP: ', 'gray') + c(WORKSPACE_ROOT, 'brightCyan'));
      if (lower === '/status') drawTelemetryPanel(' Текущий runtime ');
      finishCommand(); return;
    }
    if (lower === '/net' || lower === '/network') { await checkNetwork(); finishCommand(); return; }
    if (lower === '/vpn') {
      box([c('VPN включается в Android-приложении.', 'brightCyan'), 'Termux не должен быть в исключениях split tunneling.', 'После подключения: /net'], { width: Math.min(72, termWidth() - 2), title: ' Android VPN ', style: 'single', color: 'cyan' }).forEach(line => console.log(line));
      finishCommand(); return;
    }
    if (lower === '/proxy' || lower.startsWith('/proxy ')) {
      const value = text.replace(/^\/proxy\s*/i, '').trim();
      if (!value) { const st = proxyStatus(); console.log(c('Прокси: ', 'gray') + (st.enabled ? c(st.proxy, 'green') : c('не задан', 'yellow'))); console.log(c('Используй: /proxy socks5h://host:port | /proxy off | /proxy test', 'gray')); finishCommand(); return; }
      if (/^(test|check)$/i.test(value)) { await checkNetwork(); finishCommand(); return; }
      const changed = setProxy(value); console.log(changed.error ? c('✗ ' + changed.error, 'red') : c('✓ ' + changed.message, 'green')); if (!changed.error) await checkNetwork(); finishCommand(); return;
    }
    if (lower === '/workspace' || lower === '/ws' || lower.startsWith('/workspace ') || lower.startsWith('/ws ')) {
      const rawPath = text.replace(/^\/(workspace|ws)\s*/i, '').trim();
      if (!rawPath) console.log(c('Рабочая папка MCP: ', 'gray') + c(WORKSPACE_ROOT, 'brightCyan'));
      else { const changed = setWorkspaceRoot(rawPath); console.log(changed.error ? c('✗ ' + changed.error, 'red') : c('✓ MCP-папка: ' + changed.workspace, 'green')); }
      finishCommand(); return;
    }
    if (lower === '/logs') { CONFIG.liveToolLogs = !CONFIG.liveToolLogs; console.log(c('Live-логи: ' + (CONFIG.liveToolLogs ? 'ВКЛ' : 'ВЫКЛ'), CONFIG.liveToolLogs ? 'green' : 'yellow')); finishCommand(); return; }
    if (lower === '/animation' || lower.startsWith('/animation ')) {
      const value = lower.replace(/^\/animation\s*/, '').trim();
      CONFIG.animatedIndicator = value === 'on' || value === '1' || value === 'да' ? true : value === 'off' || value === '0' || value === 'нет' ? false : !CONFIG.animatedIndicator;
      saveHistory();
      console.log(c('ANSI-анимация loading bar: ' + (CONFIG.animatedIndicator ? 'ВКЛ' : 'ВЫКЛ'), CONFIG.animatedIndicator ? 'green' : 'yellow'));
      if (CONFIG.animatedIndicator) console.log(c('Если кадры снова склеятся, выполни /animation off.', 'yellow'));
      finishCommand(); return;
    }
    if (lower === '/indicator' || lower.startsWith('/indicator ')) {
      const value = lower.replace(/^\/indicator\s*/, '').trim();
      if (value) {
        const changed = setIndicatorStyle(value);
        console.log(changed.error ? c('✗ ' + changed.error, 'red') : c('✓ Индикатор: ' + changed.label, 'green'));
      } else {
        const options = Object.entries(INDICATOR_THEMES).map(([id, meta]) => ({ label: meta.label, description: id === CONFIG.indicatorStyle ? 'текущий' : id, style: id }));
        const opened = openArrowMenu('Выбор индикатора', options, async option => {
          const changed = setIndicatorStyle(option.style);
          console.log(c('✓ Индикатор: ' + changed.label, 'green'));
        });
        if (!opened) console.log(c('Варианты: ' + Object.keys(INDICATOR_THEMES).join(', ') + '. Пример: /indicator game', 'gray'));
      }
      finishCommand(); return;
    }
    if (lower === '/stream') { CONFIG.streamMode = !CONFIG.streamMode; console.log(c('AI-стриминг: ' + (CONFIG.streamMode ? 'ВКЛ' : 'ВЫКЛ'), CONFIG.streamMode ? 'green' : 'yellow')); finishCommand(); return; }
    // Checked before /auto, or "/autoswitch off" would match the /auto prefix
    // and silently toggle auto-approval instead.
    if (lower === '/preset' || lower.startsWith('/preset ')) {
      const rest = text.replace(/^\/preset\s*/i, '').trim();
      const all = allPresets();
      if (!rest) {
        console.log(c('Пресеты — постоянные указания, действуют на каждую задачу.', 'gray'));
        for (const [id, p] of Object.entries(all)) {
          const on = PRESETS.active.includes(id);
          console.log(`  ${on ? c('[вкл]', 'green') : c('[выкл]', 'gray')} ${c(id, 'brightCyan')} — ${p.label}`);
        }
        console.log(c('  /preset <id> on|off · /preset save <id> <текст>', 'gray'));
        finishCommand(); return;
      }
      const saveMatch = rest.match(/^save\s+([\w-]{2,32})\s+([\s\S]+)$/i);
      if (saveMatch) {
        PRESETS.custom[saveMatch[1]] = saveMatch[2].trim();
        savePresets();
        setPresetActive(saveMatch[1], true);
        console.log(c(`Пресет '${saveMatch[1]}' сохранён и включён.`, 'green'));
        finishCommand(); return;
      }
      const [id, state] = rest.split(/\s+/);
      const on = !/^(off|выкл|0|нет)$/i.test(state || 'on');
      const r = setPresetActive(id, on);
      console.log(r.error ? c(r.error, 'red')
        : c(`Пресет '${id}' ${on ? 'включён' : 'выключен'}. Активны: ${r.active.join(', ') || '—'}`, 'green'));
      finishCommand(); return;
    }
    if (lower === '/autoswitch' || lower.startsWith('/autoswitch ')) {
      const value = lower.replace(/^\/autoswitch\s*/, '').trim();
      CONFIG.autoSwitchModel = value === 'on' || value === '1' || value === 'да' ? true
        : value === 'off' || value === '0' || value === 'нет' ? false
        : !CONFIG.autoSwitchModel;
      saveHistory();
      console.log(c('Автопереключение модели при лимите: ' + (CONFIG.autoSwitchModel ? 'ВКЛ' : 'ВЫКЛ'), CONFIG.autoSwitchModel ? 'green' : 'yellow'));
      if (!CONFIG.autoSwitchModel) console.log(c(`Отвечать будет только ${currentModel}; при лимите — ошибка, а не подмена.`, 'gray'));
      finishCommand(); return;
    }
    if (lower === '/auto' || lower.startsWith('/auto ')) {
      const value = lower.replace(/^\/auto\s*/,'').trim();
      CONFIG.autoApprove = value === 'on' || value === '1' || value === 'да' ? true : value === 'off' || value === '0' || value === 'нет' ? false : !CONFIG.autoApprove;
      saveHistory();
      console.log(c('Авто-одобрение: ' + (CONFIG.autoApprove ? 'ВКЛ' : 'ВЫКЛ'), CONFIG.autoApprove ? 'green' : 'yellow')); finishCommand(); return;
    }
    if (lower === '/think') { CONFIG.showThinking = !CONFIG.showThinking; console.log(c('План и наблюдения: ' + (CONFIG.showThinking ? 'ВКЛ' : 'ВЫКЛ'), CONFIG.showThinking ? 'green' : 'yellow')); finishCommand(); return; }
    if (lower === '/clarify' || lower.startsWith('/clarify ')) {
      const value = lower.replace(/^\/clarify\s*/, '').trim();
      CONFIG.askClarifyingQuestions = value === 'on' || value === '1' || value === 'да' ? true : value === 'off' || value === '0' || value === 'нет' ? false : !CONFIG.askClarifyingQuestions;
      saveHistory();
      console.log(c('Уточняющие вопросы: ' + (CONFIG.askClarifyingQuestions ? 'ВКЛ' : 'ВЫКЛ'), CONFIG.askClarifyingQuestions ? 'green' : 'yellow')); finishCommand(); return;
    }
    if (lower === '/clear') { history = []; saveHistory(); console.log(c('История активной сессии очищена', 'green')); finishCommand(); return; }
    if (lower === '/save') { saveHistory(); console.log(c('Сессия сохранена', 'green')); finishCommand(); return; }
    if (lower === '/dash') { CONFIG.showDashboard = !CONFIG.showDashboard; console.log(c('Дашборд: ' + (CONFIG.showDashboard ? 'ВКЛ' : 'ВЫКЛ'), CONFIG.showDashboard ? 'green' : 'yellow')); finishCommand(); return; }
    if (lower === '/compact') { CONFIG.compactMode = !CONFIG.compactMode; console.log(c('Компактный режим: ' + (CONFIG.compactMode ? 'ВКЛ' : 'ВЫКЛ'), CONFIG.compactMode ? 'green' : 'yellow')); finishCommand(); return; }
    if (lower === '/todo' || lower.startsWith('/todo ')) { const todoText = text.slice(5).trim(); if (todoText) { addTodo(todoText); console.log(c('✓ Добавлено: ' + todoText, 'green')); } drawTodos(); finishCommand(); return; }
    if (lower === '/todos' || lower === '/list') { drawTodos(); finishCommand(); return; }
    if (lower.startsWith('/done ')) { const id = parseInt(lower.slice(5).trim(), 10); console.log(doneTodo(id, WORKSPACE_ROOT) ? c('✓ Задача выполнена', 'green') : c('✗ Задача не найдена', 'red')); drawTodos(); finishCommand(); return; }
    if (lower.startsWith('/rm ')) { const id = parseInt(lower.slice(3).trim(), 10); console.log(removeTodo(id, WORKSPACE_ROOT) ? c('✓ Задача удалена', 'green') : c('✗ Задача не найдена', 'red')); drawTodos(); finishCommand(); return; }
    if (lower === '/clear-todo' || lower === '/cleartodo') { clearTodos(WORKSPACE_ROOT); console.log(c('✓ Задачи очищены', 'green')); finishCommand(); return; }
    if (CONFIG.showDashboard) drawDashboard();
    void agentLoop(text).then(() => { if (!CONFIG.compactMode) drawMiniStatus(); }).catch(e => console.log(c('❌ ' + e.message, 'red'))).finally(prompt);
  }

  rl.on('line', line => {
    const text = String(line || '').trim();
    if (pendingConfirmation) {
      if (/^(y|yes|да|1)$/i.test(text)) {
        const pending = pendingConfirmation; pendingConfirmation = null; pending.resolve('yes'); return;
      }
      if (/^(n|no|нет|0)$/i.test(text)) {
        const pending = pendingConfirmation; pendingConfirmation = null; pending.resolve('no'); return;
      }
      // Корректировку можно дать даже пока ожидается опасная операция.
      correctionQueue.push(text.replace(/^\/(correct|fix)\s*/i, '').trim() || text);
      console.log(c('✎ Корректировка сохранена. Для текущего инструмента всё ещё ответь y или n.', 'yellow'));
      return;
    }
    if (!text) { finishCommand(); return; }
    if (agentBusy) {
      // Ключ — управляющая команда, а не корректировка для модели. Никогда не передаём его в history.
      if (/^\/key\s+/i.test(text)) {
        const result = saveKeyFromCommand(text.replace(/^\/key\s*/i, ''));
        console.log(result.error ? c('✗ ' + result.error, 'red') : c(`✓ OpenRouter key сохранён: ${result.masked}`, 'green'));
        if (result.success) correctionQueue.push('OpenRouter key теперь настроен. При необходимости повтори vision_analyze, не выводи и не записывай ключ.');
        return;
      }
      if (looksLikeOpenRouterKey(text)) {
        const result = saveKeyFromCommand(text);
        console.log(result.error ? c('✗ ' + result.error, 'red') : c(`✓ OpenRouter key сохранён: ${result.masked}. Не отправляю ключ модели.`, 'green'));
        if (result.success) correctionQueue.push('OpenRouter key теперь настроен. Повтори vision_analyze без вывода ключа.');
        return;
      }
      if (/^\/(abort|stop)$/i.test(text)) {
        abortRequested = true;
        auditEvent('task_abort_requested', { step: TELEMETRY.step });
        setRunPhase('stopped', 'запрошено пользователем');
        try { activeProviderAbort?.(); } catch {}
        console.log(c('⏹ Остановка запрошена; OpenRouter-запрос будет прерван, текущая локальная команда завершится безопасно.', 'yellow'));
        return;
      }
      const correction = text.replace(/^\/(correct|fix)\s*/i, '').trim() || text;
      correctionQueue.push(correction);
      setRunPhase('correction', 'ожидает следующего шага');
      console.log(c('✎ Корректировка принята и будет применена на следующем шаге.', 'yellow'));
      return;
    }
    void handleIdleInput(text).catch(e => { console.log(c('❌ ' + e.message, 'red')); finishCommand(); });
  });
  prompt();
}
// ═══════════════════════════════════════════════════════════════════
//  CLI ARGS
// ═══════════════════════════════════════════════════════════════════
const args = process.argv.slice(2);
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--stream') CONFIG.streamMode = true;
  if (args[i] === '--auto-approve') CONFIG.autoApprove = true;
  if (args[i] === '--verbose' || args[i] === '-v') CONFIG.verbose = true;
  if (args[i] === '--model' && args[i + 1]) currentModel = args[++i];
  if (args[i] === '--openrouter' || args[i] === '--open') currentProvider = 'openrouter';
  if (args[i] === '--zen') currentProvider = 'zen';
  if (args[i] === '--openrouter-model' && args[i + 1]) { currentProvider = 'openrouter'; currentModel = args[++i]; }
  if (args[i] === '--compact') CONFIG.compactMode = true;
  if (args[i] === '--no-dash') CONFIG.showDashboard = false;
  if (args[i] === '--proxy' && args[i + 1]) CONFIG.proxy = args[++i];
  if (args[i] === '--no-ipv4') CONFIG.curlIpv4 = false;
}

// Нужен и в одноразовом режиме: node cli-agent-termux-mcp.js "...".
loadOpenRouterKey();

if (args.length === 0 || (args.length >= 1 && args[0].startsWith('--'))) {
  main();
} else {
  (async () => {
    await checkMCP();
    const prompt = args.join(' ');
    await agentLoop(prompt);
    process.exit(0);
  })();
}
