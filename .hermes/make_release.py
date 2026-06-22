"""Create a GitHub release and upload the APK for ThermalRadar v0.1.8"""
import os
import subprocess
import json
import urllib.request
import base64

TOKEN = os.environ.get('GITHUB_TOKEN', '')
if not TOKEN:
    # Try extracting from git remote
    r = subprocess.run(['git', '-C', 'D:/tradar', 'remote', 'get-url', 'origin'],
                       capture_output=True, text=True, cwd='D:/tradar')
    url = r.stdout.strip()
    import re
    m = re.search(r'https://[^:]+:([^@]+)@', url)
    if m:
        TOKEN = m.group(1)

OWNER_REPO = 'isemaster/ThermalRadar'
TAG = 'v0.1.8'
APK_PATH = 'D:/tradar/ThermalRadar-v0.1.8.apk'

def api(method, path, data=None):
    url = f'https://api.github.com/repos/{OWNER_REPO}{path}'
    headers = {
        'Authorization': f'token {TOKEN}',
        'Accept': 'application/vnd.github.v3+json',
        'User-Agent': 'Hermes-Agent'
    }
    if data is not None:
        body = json.dumps(data).encode('utf-8')
        headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(url, data=body, headers=headers, method=method)
    else:
        req = urllib.request.Request(url, headers=headers, method=method)
    
    try:
        with urllib.request.urlopen(req) as resp:
            text = resp.read().decode('utf-8')
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        error_text = e.read().decode('utf-8')
        print(f"HTTP {e.code}: {error_text}")
        return None

# Check if tag exists
print(f"Checking existing releases for tag {TAG}...")
releases = api('GET', '/releases?per_page=10')
if releases:
    for rel in releases:
        if rel.get('tag_name') == TAG:
            print(f"Release {TAG} already exists: {rel['html_url']}")
            release_id = rel['id']
            break
    else:
        release_id = None
else:
    release_id = None

if not release_id:
    # Try to find existing tag
    tags = api('GET', '/tags?per_page=10')
    tag_exists = any(t.get('name') == TAG for t in tags) if tags else False
    
    if tag_exists:
        print(f"Tag {TAG} exists but no release. Creating release...")
    else:
        print(f"Tag {TAG} doesn't exist. Creating tag from main...")
        # Get latest commit on main
        main_ref = api('GET', '/git/ref/heads/main')
        if main_ref:
            sha = main_ref['object']['sha']
            # Create tag
            tag_data = {
                'tag': TAG,
                'message': f'v0.1.8',
                'object': sha,
                'type': 'commit'
            }
            result = api('POST', '/git/tags', tag_data)
            if result:
                # Create ref
                ref_result = api('POST', '/git/refs', {
                    'ref': f'refs/tags/{TAG}',
                    'sha': sha
                })
                if ref_result:
                    print(f"Tag {TAG} created")
                else:
                    print("Tag ref already exists, continuing...")
    
    # Create release
    release_data = {
        'tag_name': TAG,
        'name': 'v0.1.8',
        'body': '## v0.1.8 — Исправление критических багов симуляции\n\n' +
                '### 🐛 Баги\n' +
                '- **Clock conflict**: `System.currentTimeMillis()` → `elapsedRealtime()`' +
                ' в симуляции (симуляция умирала в первом кадре)\n' +
                '- **Noise calibration**: первый puff отложен на 5с (калибровка на шуме, а не на термике)\n' +
                '- **Puff geometry**: генерация по курсу полёта (а не случайно) — сигнал растёт, детектор подтверждает\n' +
                '- **Zero-crossing**: убрано лишнее деление на 2 (частота была вдвое ниже)\n' +
                '- **TH_SUSPECT**: 0.010 → 0.020g (синхронизация с README)\n\n' +
                '### 📦 Сборка\n' +
                '- versionCode 14, versionName 0.1.8',
        'draft': False,
        'prerelease': False
    }
    release = api('POST', '/releases', release_data)
    if release:
        release_id = release.get('id')
        print(f"Release created: {release['html_url']}")
    else:
        print("Failed to create release")
        exit(1)

# Upload APK asset
print(f"\nUploading APK...")
headers = {
    'Authorization': f'token {TOKEN}',
    'Content-Type': 'application/vnd.android.package-archive',
    'Accept': 'application/vnd.github.v3+json',
    'User-Agent': 'Hermes-Agent'
}

apk_name = os.path.basename(APK_PATH)
upload_url = f'https://uploads.github.com/repos/{OWNER_REPO}/releases/{release_id}/assets?name={apk_name}'

with open(APK_PATH, 'rb') as f:
    apk_data = f.read()

req = urllib.request.Request(upload_url, data=apk_data, headers=headers, method='POST')
try:
    with urllib.request.urlopen(req) as resp:
        result = json.loads(resp.read().decode('utf-8'))
        print(f"APK uploaded: {result.get('browser_download_url', '?')}")
except urllib.error.HTTPError as e:
    error_text = e.read().decode('utf-8')
    print(f"Upload failed HTTP {e.code}: {error_text}")
    exit(1)

print("\n✅ Release complete!")
