"""Run on the Linux sync host: python3 tests/scripts/test_sync_release_assets.py."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts/sync-release-assets.sh'


def archive(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as z:
        for name, content in entries.items():
            z.writestr(name, content)
    return output.getvalue()


with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    mirror = root / 'mirror'
    (mirror / 'server/release').mkdir(parents=True)
    assets = archive({'Cosmetics/CharacterCreator/Capes.json': '[{"Id":"NewCape"}]'})
    jar = archive({'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\n'})
    bundle = archive({'Assets.zip': assets, 'Server/HytaleServer.jar': jar})
    key = 'server/release/0.10.0.zip'
    (mirror / key).write_bytes(bundle)
    manifest = {'files': {
        'server/release/0.9.0.zip': {'sha256': '0' * 64},
        'server/pre-release/0.11.0.zip': {'sha256': '0' * 64},
        key: {'sha256': hashlib.sha256(bundle).hexdigest()},
    }}
    manifest_path = mirror / 'manifest.json'
    manifest_path.write_text(json.dumps(manifest))
    env = dict(os.environ, AUTH_ROOT=str(root / 'auth'), MIRROR_URL=mirror.as_uri(),
               RESTART_AUTH='0', LOCK_FILE=str(root / 'sync.lock'))

    def run():
        return subprocess.run(['bash', str(SCRIPT)], env=env, capture_output=True, text=True)

    result = run()
    assert result.returncode == 0, result.stdout + result.stderr
    installed = root / 'auth/hytale-assets/Assets.zip'
    state = root / 'auth/hytale-auth-data/release-sync/release.version'
    assert installed.read_bytes() == assets
    assert state.read_text().strip() == '0.10.0'
    (mirror / key).unlink()
    result = run()
    assert result.returncode == 0 and 'already installed' in result.stdout
    # A corrupt newer download must preserve both the installed assets and state.
    bad_key = 'server/release/0.10.1.zip'
    (mirror / bad_key).write_bytes(b'corrupt')
    manifest['files'][bad_key] = {'sha256': '0' * 64}
    manifest_path.write_text(json.dumps(manifest))
    assert run().returncode != 0
    assert installed.read_bytes() == assets
    assert state.read_text().strip() == '0.10.0'
    assert not list(state.parent.glob('server-*.zip'))
    print('PASS: latest stable selection, extraction, unchanged skip, corrupt download preservation')
