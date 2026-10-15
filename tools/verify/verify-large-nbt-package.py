import hashlib
import json
import sys
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[2]
version = sys.argv[1] if len(sys.argv) > 1 else '1.0.17.6'
archive = root / f'dist/投影预览-{version}.zip'
with zipfile.ZipFile(archive) as package:
    assert package.testzip() is None, 'corrupt ZIP payload'
    names = package.namelist()
    assert len(names) == len(set(names)), 'duplicate ZIP entries'
    assert not any(n.startswith(('cache/', 'tools/', 'settings/')) for n in names), 'test or personal data shipped'
    selected = ['README.md', '评估与测评/CHANGELOG.md', '评估与测评/投影渲染与区块缓存综合评估.md',
                'webapp/main.js', 'webapp/cache-cleanup.js', 'webapp/engine-client.js',
                'webapp/preload.js', 'webapp/ui-settings.js', 'webapp/package.json',
                'webapp/scene-transfer.js', 'webapp/renderer/index.html', 'webapp/renderer/renderer.js',
                'webapp/renderer/gpu-view.js', 'webapp/renderer/lod-stream.js']
    selected += [p.relative_to(root).as_posix() for p in (root / 'bin').rglob('*.class')]
    for name in selected:
        assert package.read(name) == (root / name).read_bytes(), f'stale packaged file: {name}'
    assert json.loads(package.read('webapp/package.json'))['version'] == version
    assert '投影预览.exe' in names and 'runtime/bin/java.exe' in names
print(json.dumps({'pass': True, 'verifiedFiles': len(selected), 'bytes': archive.stat().st_size,
                  'sha256': hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest()}, indent=2))
