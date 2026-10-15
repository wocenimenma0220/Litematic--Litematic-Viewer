import hashlib
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[2]
archive = root / 'dist/投影预览-1.0.17.6.zip'
with zipfile.ZipFile(archive) as package:
    assert package.testzip() is None, 'corrupt ZIP payload'
    names = package.namelist()
    assert len(names) == len(set(names)), 'duplicate ZIP entries'
    assert not any(n.startswith(('cache/', 'tools/', 'settings/')) for n in names), 'test or personal data shipped'
    selected = ['README.md', 'CHANGELOG.md', '投影渲染与区块缓存综合评估.md',
                'webapp/main.js', 'webapp/cache-cleanup.js', 'webapp/engine-client.js',
                'webapp/preload.js', 'webapp/ui-settings.js', 'webapp/package.json',
                'webapp/renderer/index.html', 'webapp/renderer/renderer.js']
    selected += [p.relative_to(root).as_posix() for p in (root / 'bin').rglob('*.class')]
    for name in selected:
        assert package.read(name) == (root / name).read_bytes(), f'stale packaged file: {name}'
    assert json.loads(package.read('webapp/package.json'))['version'] == '1.0.17.6'
    assert '投影预览.exe' in names and 'runtime/bin/java.exe' in names
print(json.dumps({'pass': True, 'verifiedFiles': len(selected), 'bytes': archive.stat().st_size,
                  'sha256': hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest()}, indent=2))
