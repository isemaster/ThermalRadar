import os, sys, json, requests

token = os.environ.get('GITHUB_TOKEN', '').strip()
if not token:
    print("NO TOKEN")
    sys.exit(1)

# Upload APK to release
url = "https://uploads.github.com/repos/isemaster/ThermalRadar/releases/342913781/assets?name=ThermalRadar-v0.1.7.apk"
headers = {
    "Authorization": f"token {token}",
    "Accept": "application/vnd.github+json",
    "Content-Type": "application/vnd.android.package-archive"
}

with open("/d/Tradar/ThermalRadar-v0.1.7.apk", "rb") as f:
    resp = requests.post(url, headers=headers, data=f)

d = resp.json()
print(f"Upload: {d.get('name')} state={d.get('state')} size={d.get('size')}")
