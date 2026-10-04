import hashlib,json,pathlib,subprocess,time,resource
root=pathlib.Path('/tmp/takupoke-local-qwen-micro-20261004')
source=pathlib.Path('/tmp/takupoke-android-micro-fields-20261004')
cmd=['/workspace/recovery-research/venv/bin/python',str(source/'scripts/model-batch-native.py'),'--corpus',str(root/'corpus.json'),'--manifest',str(source/'scripts/model-batch-manifest.json'),'--model','/workspace/recovery-research/artifacts/qwen25-1.5B-int8.litertlm','--cache',str(root/'cache'),'--output',str(root/'native.json'),'--model-id','qwen25-15b','--instruction-profile','micro_field_v1']
start=time.monotonic()
with (root/'native.log').open('w') as log:
    try:
        result=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=180)
        status={'exitCode':result.returncode,'timedOut':False}
    except subprocess.TimeoutExpired:
        status={'exitCode':None,'timedOut':True}
status.update(seconds=time.monotonic()-start,peakChildRSSBytes=resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss*1024,weightsDownloaded=False,scope='four existing COPY obligations; local Linux CPU, cached exact pinned LiteRT weights, not full model/PDF quality')
(root/'resource.json').write_text(json.dumps(status,indent=2))
print(json.dumps(status))
