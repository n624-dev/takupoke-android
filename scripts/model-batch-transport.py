"""Checksummed raw research receipt in CI logs; no persistent artifact/cache storage."""
import base64
import gzip
import hashlib
import json
import pathlib
import sys

root=pathlib.Path(sys.argv[1])
files={};invalid={}
for name in ['corpus.json','native.json','resource.json','score.json']:
    path=root/name
    if not path.is_file():files[name]=None;continue
    raw=path.read_bytes()
    try:files[name]=json.loads(raw)
    except (ValueError,UnicodeDecodeError):
        files[name]=None
        invalid[name]={'sha256':hashlib.sha256(raw).hexdigest(),'base64':base64.b64encode(raw).decode()}

files['transportStatus']={'model':sys.argv[2], 'complete':all(files.values()), 'plannedCases':16, 'missingFiles':[name for name,value in files.items() if value is None], 'invalidFiles':invalid}
# Missing setup/native/scorer receipts remain explicit unavailable denominators, never empty successes.
payload=json.dumps(files,ensure_ascii=False,separators=(',',':')).encode()
encoded=base64.b64encode(gzip.compress(payload,mtime=0)).decode()
print('TAKUPOKE_MODEL_BATCH_BEGIN '+hashlib.sha256(payload).hexdigest()+' '+str(len(payload)))
for index in range(0,len(encoded),3000):print('TAKUPOKE_MODEL_BATCH_DATA '+str(index//3000)+' '+encoded[index:index+3000])
print('TAKUPOKE_MODEL_BATCH_END')
