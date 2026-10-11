'use strict';

/**
 * JSON-RPC client for the Java render engine.
 *
 * The engine speaks one JSON object per line on stdout. Requests are serialised through a single
 * worker slot because the engine holds one "currently open schematic" plus one render at a time;
 * letting two renders overlap would produce confusing results and waste memory.
 */

const { spawn } = require('node:child_process');
const readline = require('node:readline');

class EngineClient {
  /**
   * @param {object} opts
   * @param {string} opts.javaExe
   * @param {string} opts.classpath
   * @param {string} opts.home            project root, passed as -Dlitematicrender.home
   * @param {(evt: object) => void} [opts.onEvent]
   * @param {(info: object) => void} [opts.onExit]
   */
  constructor(opts) {
    this.opts = opts;
    this.proc = null;
    this.nextId = 1;
    this.pending = new Map();
    /** Serialises calls: each entry is a thunk returning a promise. */
    this.queue = [];
    this.busy = false;
    this.stderrTail = [];
  }

  isRunning() {
    return !!this.proc && this.proc.exitCode === null && !this.proc.killed;
  }

  start() {
    return new Promise((resolve, reject) => {
      const args = [
        '-Xmx4g',
        '-Dfile.encoding=UTF-8',
        `-Dlitematicrender.home=${this.opts.home}`,
        '-cp',
        this.opts.classpath,
        'io.github.litematicrender.Main',
        '--serve',
      ];
      let child;
      try {
        child = spawn(this.opts.javaExe, args, { cwd: this.opts.home, windowsHide: true });
      } catch (err) {
        reject(err);
        return;
      }
      this.proc = child;

      // Both streams must be read as UTF-8. Node otherwise decodes a child's stdout/stderr with
      // the process default (GBK on a zh-CN Windows), which turns any non-ASCII byte sequence —
      // Chinese pack names, for instance — into replacement characters before JSON.parse sees it.
      child.stdout.setEncoding('utf8');
      const rl = readline.createInterface({ input: child.stdout });
      rl.on('line', (line) => this._onLine(line));

      child.stderr.setEncoding('utf8');
      child.stderr.on('data', (chunk) => {
        // Keep a bounded tail for diagnostics; the engine logs resolution failures here.
        for (const l of String(chunk).split(/\r?\n/)) {
          if (!l.trim()) continue;
          this.stderrTail.push(l);
          if (this.stderrTail.length > 200) this.stderrTail.shift();
        }
        if (this.opts.onEvent) this.opts.onEvent({ event: 'stderr', message: String(chunk) });
      });

      child.on('error', (err) => {
        this._failAll(err);
        if (this.opts.onExit) this.opts.onExit({ error: String(err.message || err) });
        reject(err);
      });

      child.on('exit', (code, signal) => {
        this.proc = null;
        const info = { code, signal, stderrTail: this.stderrTail.slice(-40) };
        this._failAll(new Error(`render engine exited (code=${code} signal=${signal})`));
        if (this.opts.onExit) this.opts.onExit(info);
      });

      // The engine announces readiness itself once stdin is being read.
      const onReady = (evt) => {
        if (evt.event === 'ready') {
          cleanup();
          resolve({ pid: evt.pid });
        }
      };
      this._readyHook = onReady;
      const cleanup = () => {
        this._readyHook = null;
      };
      setTimeout(() => {
        if (this._readyHook) {
          cleanup();
          resolve({ pid: child.pid, slow: true });
        }
      }, 8000).unref?.();
    });
  }

  _onLine(line) {
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      return;
    }
    if (msg.event === 'ready') {
      if (this._readyHook) this._readyHook(msg);
      if (this.opts.onEvent) this.opts.onEvent(msg);
      return;
    }
    if (msg.id === undefined) {
      if (this.opts.onEvent) this.opts.onEvent(msg);
      return;
    }
    const entry = this.pending.get(msg.id);
    if (!entry) return;
    this.pending.delete(msg.id);
    if (msg.ok) entry.resolve(msg.result);
    else entry.reject(Object.assign(new Error(msg.error || 'engine error'), { errorType: msg.errorType }));
  }

  _failAll(err) {
    for (const [, entry] of this.pending) entry.reject(err);
    this.pending.clear();
  }

  /** Queues a call; resolves with the engine's result object. */
  call(method, params) {
    return new Promise((resolve, reject) => {
      this.queue.push({ method, params, resolve, reject });
      this._pump();
    });
  }

  _pump() {
    if (this.busy) return;
    const job = this.queue.shift();
    if (!job) return;
    this.busy = true;
    this._send(job.method, job.params)
      .then(job.resolve, job.reject)
      .finally(() => {
        this.busy = false;
        this._pump();
      });
  }

  _send(method, params) {
    return new Promise((resolve, reject) => {
      if (!this.isRunning()) {
        reject(new Error('render engine is not running'));
        return;
      }
      const id = this.nextId++;
      this.pending.set(id, { resolve, reject });
      const line = JSON.stringify({ id, method, params: params || {} }) + '\n';
      this.proc.stdin.write(line, 'utf8', (err) => {
        if (err) {
          this.pending.delete(id);
          reject(err);
        }
      });
    });
  }

  stop() {
    const proc = this.proc;
    if (!proc) return;
    try {
      proc.stdin.write(JSON.stringify({ id: 0, method: 'shutdown', params: {} }) + '\n');
      proc.stdin.end();
    } catch {
      /* already gone */
    }
    const killTimer = setTimeout(() => {
      try {
        proc.kill();
      } catch {
        /* ignore */
      }
    }, 1500);
    proc.once('exit', () => clearTimeout(killTimer));
    this.proc = null;
  }

  getStderrTail() {
    return this.stderrTail.slice(-40);
  }
}

module.exports = { EngineClient };
