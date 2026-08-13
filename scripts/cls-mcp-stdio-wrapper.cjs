/**
 * Windows MCP stdio wrapper for cls-mcp-server.
 *
 * cls-mcp-server prints startup text to stdout (e.g. "Started cls-mcp-server..."),
 * which breaks MCP JSON-RPC. This wrapper forwards only JSON lines to stdout.
 */
const { spawn } = require('child_process');
const os = require('os');

const isWin = os.platform() === 'win32';
const npx = process.env.MCP_NPX || (isWin ? 'npx.cmd' : 'npx');
const pkg = process.env.CLS_MCP_PACKAGE || 'cls-mcp-server@latest';
const args = ['-y', pkg];

const child = spawn(npx, args, {
  cwd: process.cwd(),
  env: process.env,
  stdio: ['pipe', 'pipe', 'pipe'],
  shell: isWin,
});

let pending = '';

function isJsonRpcLine(line) {
  const trimmed = line.trim();
  if (!trimmed.startsWith('{')) {
    return false;
  }
  try {
    JSON.parse(trimmed);
    return true;
  } catch {
    return false;
  }
}

child.stdout.on('data', (chunk) => {
  pending += chunk.toString();
  let newline = pending.indexOf('\n');
  while (newline !== -1) {
    const line = pending.slice(0, newline + 1);
    pending = pending.slice(newline + 1);
    if (isJsonRpcLine(line)) {
      process.stdout.write(line);
    } else if (line.trim()) {
      process.stderr.write(line);
    }
    newline = pending.indexOf('\n');
  }
});

child.stderr.on('data', (chunk) => {
  process.stderr.write(chunk);
});

process.stdin.pipe(child.stdin);

child.on('error', (err) => {
  process.stderr.write(`[cls-mcp-stdio-wrapper] failed to start ${npx}: ${err.message}\n`);
  process.exit(1);
});

child.on('exit', (code, signal) => {
  if (pending.trim() && isJsonRpcLine(pending)) {
    process.stdout.write(pending.endsWith('\n') ? pending : pending + '\n');
  }
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code == null ? 1 : code);
});

process.on('SIGINT', () => child.kill('SIGINT'));
process.on('SIGTERM', () => child.kill('SIGTERM'));
process.on('exit', () => {
  if (!child.killed) {
    child.kill();
  }
});
