import os, sys, json, urllib.request

with open('D:\\Tradar\\.github_token.sh') as f:
    for line in f:
        if 'GITHUB_TOKEN' in line and '=' in line:
            token = line.split('=')[1].strip().strip('"').strip("'").strip('"')
            break

# Get all releases
url = "https://api.github.com/repos/isemaster/ThermalRadar/releases"
req = urllib.request.Request(url)
req.add_header('Authorization', f'token {token}')
req.add_header('Accept', 'application/vnd.github+json')

resp = urllib.request.urlopen(req)
releases = json.loads(resp.read())

for r in releases:
    tag = r['tag_name']
    name = r.get('name', '')
    body = r.get('body', '')[:120].replace('\n', ' | ')
    print(f"{tag:16s} \"{name}\"  {body}...")
