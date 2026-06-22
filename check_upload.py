import json,sys
d=json.load(open('/tmp/release_resp.json'))
print('Upload:', d.get('name','?'), d.get('state','FAIL'))
