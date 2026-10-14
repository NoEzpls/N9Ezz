#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

# No network, Gradle, repository lookup, or downloaded dependencies are needed.
# LOADER_JAR supplies the real custom Loader API, which is never bundled in the mod.
: "${LOADER_JAR:?Set LOADER_JAR to your N9Ezz Universal Loader 0.4.1 or later JAR}"
if [[ ! -f "$LOADER_JAR" ]]; then
  echo "Loader JAR does not exist: $LOADER_JAR" >&2
  exit 1
fi
LOADER_JAR=$(realpath -- "$LOADER_JAR")
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA="$JAVA_HOME/bin/java"
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
else
  JAVA=java
  JAVAC=javac
  JAR=jar
fi

rm -rf -- build/classes build/test-classes
mkdir -p build/classes build/test-classes dist
find src/main/java -name '*.java' -type f | LC_ALL=C sort > build/main-sources.txt
"$JAVAC" -proc:none --release 21 -encoding UTF-8 -cp "$LOADER_JAR" -d build/classes @build/main-sources.txt
cp -R src/main/resources/. build/classes/
find src/test/java -name '*.java' -type f | LC_ALL=C sort > build/test-sources.txt
if [[ -s build/test-sources.txt ]]; then
  "$JAVAC" -proc:none --release 21 -encoding UTF-8 -cp "build/classes:$LOADER_JAR" -d build/test-classes @build/test-sources.txt
fi

# Each independent test suite has a main method; helper files are only compiled.
python3 - <<'PY' > build/test-mains.txt
from pathlib import Path
import re
for path in sorted(Path('src/test/java').rglob('*.java')):
    text = path.read_text(encoding='utf-8')
    if re.search(r'public\s+static\s+void\s+main\s*\(', text):
        package = re.search(r'^\s*package\s+([\w.]+)\s*;', text, re.M)
        print((package.group(1) + '.' if package else '') + path.stem)
PY
if [[ ! -s build/test-mains.txt ]]; then
  echo 'No executable test suites found; refusing to package an untested release.' >&2
  exit 1
fi
{
  "$JAVA" -version 2>&1
  while IFS= read -r test_class; do
    echo "RUN $test_class"
    "$JAVA" -ea -cp "build/classes:build/test-classes:$LOADER_JAR" "$test_class"
  done < build/test-mains.txt
  echo 'ALL TEST SUITES PASSED'
} | tee build/test-results.txt

# A fixed release timestamp and sorted archive entries make repeated builds comparable.
python3 - <<'PY'
from pathlib import Path
import hashlib
import zipfile

version = '0.2.2-experimental'
timestamp = (2026, 10, 13, 0, 0, 0)
dist = Path('dist')

def archive(path, files):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for name, content in sorted(files):
            info = zipfile.ZipInfo(name, timestamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name.endswith('build.sh') else 0o100644) << 16
            output.writestr(info, content)

manifest = ('Manifest-Version: 1.0\r\n'
            'Implementation-Title: N9Ezz CF Assistant\r\n'
            f'Implementation-Version: {version}\r\n\r\n').encode()
classes = Path('build/classes')
jar_files = [('META-INF/MANIFEST.MF', manifest)]
jar_files.extend((p.relative_to(classes).as_posix(), p.read_bytes())
                 for p in classes.rglob('*') if p.is_file())
archive(dist / f'N9Ezz-CF-Assistant-{version}.jar', jar_files)

# Explicit allowlist: never ship decompiler output, uploaded logs/state, local Loader,
# generated test sandboxes, private configuration, or a pre-trained user model.
sources = [(p.as_posix(), p.read_bytes()) for p in Path('src').rglob('*') if p.is_file()]
for name in ('build.sh', 'provenance.json', 'README.md', 'CHANGELOG.md',
             'TEST-RESULTS.md', 'LIMITATIONS.md'):
    path = Path(name)
    if path.is_file():
        sources.append((name, path.read_bytes()))
if Path('docs').exists():
    sources.extend((p.as_posix(), p.read_bytes()) for p in Path('docs').rglob('*')
                   if p.is_file() and p.suffix == '.md')
sources.append(('test-results.txt', Path('build/test-results.txt').read_bytes()))
archive(dist / f'N9Ezz-CF-Assistant-{version}-sources.zip', sources)

# One deliverable also includes the binary, sources and readable release notes.
inner = [dist / f'N9Ezz-CF-Assistant-{version}.jar',
         dist / f'N9Ezz-CF-Assistant-{version}-sources.zip']
inner_checksums = ''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in inner)
bundle = [(p.name, p.read_bytes()) for p in inner]
bundle.append(('SHA256SUMS', inner_checksums.encode('ascii')))
for name in ('README.md', 'CHANGELOG.md', 'TEST-RESULTS.md', 'provenance.json'):
    path = Path(name)
    if path.is_file():
        bundle.append((name, path.read_bytes()))
if Path('docs').exists():
    bundle.extend((p.as_posix(), p.read_bytes()) for p in Path('docs').rglob('*.md') if p.is_file())
bundle.append(('test-results.txt', Path('build/test-results.txt').read_bytes()))
archive(dist / f'N9Ezz-CF-Assistant-{version}-bundle.zip', bundle)

files = sorted(dist.glob(f'N9Ezz-CF-Assistant-{version}*'))
checksums = ''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n'
                    for p in files if p.suffix in {'.jar', '.zip'})
(dist / 'SHA256SUMS').write_text(checksums, encoding='ascii')
print(checksums, end='')
PY
