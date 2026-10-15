'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');

/** Delete generated rendering data only, after the engine and scene reader have stopped. */
async function clearRenderingCache(home) {
  const base = await fs.realpath(home);
  const inside = target => {
    const relative = path.relative(base, target);
    return relative !== '' && relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative);
  };
  async function validate(directory) {
    if (!inside(await fs.realpath(directory))) throw new Error('缓存路径位于软件目录之外，未清理：' + directory);
    for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
      const target = path.join(directory, entry.name);
      if (entry.isSymbolicLink()) throw new Error('缓存包含链接，未清理：' + target);
      if (entry.isDirectory()) await validate(target);
      else if (!inside(await fs.realpath(target))) throw new Error('缓存文件位于软件目录之外，未清理：' + target);
    }
  }
  for (const parts of [['cache','scenes'], ['cache','previews'], ['renders','.gpu']]) {
    const directory = path.resolve(base, ...parts);
    if (!inside(directory)) throw new Error('无效缓存清理路径');
    try {
      const stat = await fs.lstat(directory);
      if (stat.isSymbolicLink() || !stat.isDirectory()) throw new Error('无效缓存目录：' + directory);
      await validate(directory);
      await fs.rm(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
  }
}

module.exports = { clearRenderingCache };
