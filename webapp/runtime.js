'use strict';

/**
 * Locates a Java runtime and the engine classpath.
 *
 * Search order (first hit wins) so the app is portable but still usable during development:
 *   1. `<home>/runtime/`            — a jlink image shipped inside the green zip
 *   2. `<home>/../../jdk-*`         — the workspace JDK used while developing
 *   3. `JAVA_HOME`
 *   4. `java` on PATH
 */

const fs = require('node:fs');
const path = require('node:path');

function javaExeName() {
  return process.platform === 'win32' ? 'java.exe' : 'java';
}

function isJavaExe(p) {
  try {
    return fs.statSync(p).isFile();
  } catch {
    return false;
  }
}

/** A directory that looks like a Java home. */
function asJavaHome(dir) {
  if (!dir) return null;
  const exe = path.join(dir, 'bin', javaExeName());
  return isJavaExe(exe) ? exe : null;
}

function findJdkBeside(home) {
  // `<home>/../../jdk-*` — the layout of this workspace during development.
  const parent = path.resolve(home, '..', '..');
  let entries;
  try {
    entries = fs.readdirSync(parent, { withFileTypes: true });
  } catch {
    return null;
  }
  const candidates = entries
    .filter((e) => e.isDirectory() && /^jdk/i.test(e.name))
    .map((e) => path.join(parent, e.name))
    .sort()
    .reverse();
  for (const dir of candidates) {
    const exe = asJavaHome(dir);
    if (exe) return exe;
  }
  return null;
}

/**
 * @returns {{javaExe: string, classpath: string, source: string}}
 */
function resolveRuntime(home) {
  const classes = path.join(home, 'bin');
  const classpath = classes;

  const bundled = asJavaHome(path.join(home, 'runtime'));
  if (bundled) return { javaExe: bundled, classpath, source: 'bundled runtime/' };

  const beside = findJdkBeside(home);
  if (beside) return { javaExe: beside, classpath, source: 'workspace JDK' };

  const fromEnv = asJavaHome(process.env.JAVA_HOME);
  if (fromEnv) return { javaExe: fromEnv, classpath, source: 'JAVA_HOME' };

  return { javaExe: javaExeName(), classpath, source: 'PATH' };
}

module.exports = { resolveRuntime };
