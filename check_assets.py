import os, sys, json, urllib.request

# Read token
with open('D:\\Tradar\\.github_token.sh') as f:
    for line in f:
        if 'GITHUB_TOKEN' in line and '=' in line:
            token = line.split('=')[1].strip().strip('"').strip("'").strip('"')
            break

if not token:
    print("NO TOKEN")
    sys.exit(1)

# Check release assets
url = "https://api.github.com/repos/isemaster/ThermalRadar/releases/342913781/assets"
req = urllib.request.Request(url)
req.add_header('Authorization', f'token {token}')
req.add_header('Accept', 'application/vnd.github+json')

resp = urllib.request.urlopen(req)
assets = json.loads(resp.read())
print(f"Existing assets: {len(assets)}")
for a in assets:
    print(f"  {a['name']} - {a['state']} - {a['size']} bytes")
    
# If our APK exists, delete it first
for a in assets:
    if a['name'] == 'ThermalRadar-v0.1.7.apk':
        print(f"\nDeleting existing asset {a['id']}...")
        del_req = urllib.request.Request(
            f"https://api.github.com/repos/isemaster/ThermalRadar/releases/assets/{a['id']}",
            method='DELETE'
        )
        del_req.add_header('Authorization', f'token {token}')
        del_req.add_header('Accept', 'application/vnd.github+json')
        urllib.request.urlopen(del_req)
        print("Deleted!")
        break
