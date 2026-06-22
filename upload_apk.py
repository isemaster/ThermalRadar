import os, sys, json, urllib.request

with open('D:\\Tradar\\.github_token.sh') as f:
    for line in f:
        if 'GITHUB_TOKEN' in line and '=' in line:
            token = line.split('=')[1].strip().strip('"').strip("'").strip('"')
            break

with open('D:\\Tradar\\ThermalRadar-v0.1.7.apk', 'rb') as f:
    apk_data = f.read()

print(f"Uploading {len(apk_data)} bytes...")

url = "https://uploads.github.com/repos/isemaster/ThermalRadar/releases/342913781/assets?name=ThermalRadar-v0.1.7.apk"
req = urllib.request.Request(url, data=apk_data, method='POST')
req.add_header('Authorization', f'token {token}')
req.add_header('Accept', 'application/vnd.github+json')
req.add_header('Content-Type', 'application/vnd.android.package-archive')

resp = urllib.request.urlopen(req)
result = json.loads(resp.read())
print(f"Done: {result.get('name')} state={result.get('state')} size={result.get('size')} bytes")
