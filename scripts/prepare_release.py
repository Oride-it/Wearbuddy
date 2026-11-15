"""Prepare exact release assets; never includes signing material."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil

parser = argparse.ArgumentParser()
parser.add_argument('--tag', required=True)
args = parser.parse_args()
if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', args.tag):
    raise SystemExit('Tag must be vMAJOR.MINOR.PATCH')
root = pathlib.Path(__file__).resolve().parent.parent
metadata = json.loads((root / 'wear/build/outputs/apk/release/output-metadata.json').read_text())
assert metadata['variantName'] == 'release'
assert metadata['applicationId'] == 'com.wear.wearbuddy'
assert metadata['elements'][0]['versionName'] == args.tag[1:], 'Tag and APK versions differ'
assert (root / f'docs/releases/{args.tag[1:]}.md').exists(), 'Release notes are required'
source = root / 'wear/build/outputs/apk/release/wear-release.apk'
output = root / 'dist'
output.mkdir(exist_ok=True)
name = f'Wearbuddy-{args.tag}-release.apk'
shutil.copyfile(source, output / name)
(output / 'SHA256SUMS').write_text(hashlib.sha256(source.read_bytes()).hexdigest() + '  ' + name + '\n')
print('Prepared', name)
