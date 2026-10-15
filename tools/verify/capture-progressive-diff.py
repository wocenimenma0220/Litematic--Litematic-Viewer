"""Use Git to review this change independently of the pre-existing dirty working tree."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[2]
baseline = root / 'tools/verify/progressive-baseline'
builder = 'src/io/github/litematicrender/render/scene/SceneBuilder.java'
before = (baseline / 'before.diff').read_text(encoding='utf-8-sig')
header = 'diff --git a/' + builder + ' b/' + builder
start = before.index(header)
end = before.find('\ndiff --git ', start + len(header))
patch = before[start:end if end >= 0 else len(before)].splitlines()
old_blob = re.search(r'^index ([0-9a-f]+)\.\.', before[start:end if end >= 0 else len(before)], re.M)[1]
original = subprocess.check_output(['git', 'cat-file', '-p', old_blob], cwd=root).decode('utf-8').splitlines()
reconstructed = []
cursor = 0
at = 0
while at < len(patch):
    match = re.match(r'@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@', patch[at])
    if not match:
        at += 1
        continue
    begin = int(match[1]) - 1
    reconstructed.extend(original[cursor:begin])
    cursor = begin
    at += 1
    while at < len(patch) and not patch[at].startswith('@@ '):
        line = patch[at]
        if line.startswith((' ', '-')):
            assert original[cursor] == line[1:], (cursor, line)
            cursor += 1
        if line.startswith((' ', '+')):
            reconstructed.append(line[1:])
        at += 1
reconstructed.extend(original[cursor:])
target = baseline / builder
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text('\n'.join(reconstructed) + '\n', encoding='utf-8')

paths = [p.relative_to(baseline).as_posix() for p in baseline.rglob('*') if p.is_file() and p.name not in {'before.diff', 'status.txt'}]
paths += ['src/io/github/litematicrender/serve/PreviewPriority.java',
          'tools/verify/ProgressiveFixture.java', 'tools/verify/ProgressiveWriterRegression.java',
          'tools/verify/progressive-transfer-regression.cjs', 'tools/verify/progressive-sections-ui.cjs',
          'tools/verify/capture-progressive-diff.py']
parts = []
changed = []
for name in sorted(set(paths)):
    previous = baseline / name
    old = previous.relative_to(root).as_posix() if previous.exists() else '/dev/null'
    result = subprocess.run(['git', '-c', 'core.whitespace=cr-at-eol', 'diff', '--no-index', '--binary',
                             '--ignore-space-at-eol', '--', old, name], cwd=root, capture_output=True)
    assert result.returncode in (0, 1), result.stderr.decode(errors='replace')
    if result.stdout:
        parts.append(result.stdout.replace(b'a/tools/verify/progressive-baseline/', b'a/'))
        changed.append(name)
out = root / 'tools/verify/progressive-output/this-change.patch'
out.write_bytes(b''.join(parts))
print(f'Git review patch: {len(changed)} files, {out.stat().st_size} bytes')
print('\n'.join(changed))
