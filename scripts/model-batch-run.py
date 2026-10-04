"""Owned download/cache and bounded child lifecycle. Resource failures remain planned rows."""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import platform
import shutil
import subprocess
import sys
import time
import tempfile
import urllib.request


def atomic_json(path, value):
    partial=None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, prefix=path.name+'.', suffix='.tmp', delete=False) as stream:
            partial=pathlib.Path(stream.name)
            json.dump(value,stream,ensure_ascii=False,indent=2)
            stream.flush();os.fsync(stream.fileno())
        os.replace(partial,path)
    finally:
        if partial is not None:partial.unlink(missing_ok=True)


def memory_available():
    values=dict(line.split(':',1) for line in pathlib.Path('/proc/meminfo').read_text().splitlines())
    available=int(values['MemAvailable'].split()[0])*1024
    limit=pathlib.Path('/sys/fs/cgroup/memory.max');current=pathlib.Path('/sys/fs/cgroup/memory.current')
    if limit.exists() and limit.read_text().strip()!='max':available=min(available,int(limit.read_text())-int(current.read_text()))
    return available


def cgroup_snapshot():
    return {name:(pathlib.Path('/sys/fs/cgroup')/name).read_text() for name in ['memory.current','memory.peak','memory.max','memory.events'] if (pathlib.Path('/sys/fs/cgroup')/name).exists()}


def acquire(url,path,expected_size,expected_hash,receipt):
    partial=path.with_suffix('.part');owned=False
    assert not path.exists(), 'Fresh per-run owned download required'
    try:
        with partial.open('xb') as output:
            owned=True;sha=hashlib.sha256();size=0
            with urllib.request.urlopen(url,timeout=120) as response:
                while block:=response.read(1048576):
                    size+=len(block);assert size<=expected_size;sha.update(block);output.write(block)
                    assert shutil.disk_usage(path.parent).free>1073741824, 'Download disk reserve'
        assert size==expected_size and sha.hexdigest()==expected_hash, 'Artifact size/SHA mismatch'
        partial.rename(path)
        receipt.update(bytes=size,sha256=sha.hexdigest(),verified=True)
    finally:
        if owned:partial.unlink(missing_ok=True) # Runs even if later receipt writing fails; preserves unknown partials.


def run(args):
    args.directory.mkdir(parents=True,exist_ok=False)
    corpus=json.loads(args.corpus.read_text());manifest=json.loads(args.manifest.read_text())
    model=next(m for m in manifest['models'] if m['id']==args.model_id)
    native=args.directory/'native.json';receipt=args.directory/'resource.json'
    report=dict(model=model,stage='acquisition',host=dict(os=platform.platform(),arch=platform.machine(),cpu=platform.processor(),cpuModel=next((line.split(':',1)[1].strip() for line in pathlib.Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('model name')),None),cpuCount=os.cpu_count(),cpuTopology=sorted({line for line in pathlib.Path('/proc/cpuinfo').read_text().splitlines() if line.startswith(('physical id','cpu cores','siblings'))}),meminfo=pathlib.Path('/proc/meminfo').read_text(),cgroupLimit=pathlib.Path('/sys/fs/cgroup/memory.max').read_text() if pathlib.Path('/sys/fs/cgroup/memory.max').exists() else None),
                hashes={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [args.corpus,args.manifest,pathlib.Path(__file__),pathlib.Path(__file__).with_name('model-batch-native.py')]},peakChildRSSBytes=0,guardFailure=None,resourceBefore=dict(cgroup=cgroup_snapshot(),diskFreeBytes=shutil.disk_usage(args.directory).free))
    def save():atomic_json(receipt,report)
    def unfinished(stage):
        fallback=dict(model=model,initialization='unknown',attemptsUnknown=True,rows=[dict(name=c['name'],attempted=None if c['prepared'] else False,stage='not_attempted' if c['prepared'] else 'preparation_rejected') for c in corpus['cases']])
        data=fallback
        if native.exists():
            raw=native.read_bytes()
            try:
                data=json.loads(raw)
                assert isinstance(data,dict) and [r['name'] for r in data['rows']]==[c['name'] for c in corpus['cases']]
            except (ValueError,TypeError,KeyError,AssertionError):
                data=fallback
                data['damagedReceipt']=dict(sha256=hashlib.sha256(raw).hexdigest(),base64=base64.b64encode(raw).decode())
        else:
            data['initialization']='not_started'
            for row in data['rows']:row['attempted']=False
        for row in data['rows']:
            if row['stage'] in ['not_attempted','inference_running']:row['stage']=stage
        atomic_json(native,data)
    modelpath=args.directory/'candidate.litertlm';cache=args.directory/'cache';cache.mkdir()
    child=None
    try:
        save();assert not model['gated'] and not model['qualityApproved']
        assert shutil.disk_usage(args.directory).free>2*model['bytes']+1073741824, 'Model plus on-disk cache reserve'
        assert memory_available()>2147483648, 'Startup memory reserve'
        url='https://huggingface.co/'+model['repo']+'/resolve/'+model['revision']+'/'+model['file']
        report['download']={};start=time.monotonic();acquire(url,modelpath,model['bytes'],model['sha256'],report['download']);report['downloadSeconds']=time.monotonic()-start
        # Preserve actual pinned publisher card; it is provenance, not runtime instructions.
        card=urllib.request.urlopen('https://huggingface.co/'+model['repo']+'/resolve/'+model['revision']+'/README.md',timeout=120).read()
        report['publisherCard']=card.decode();report['publisherCardSHA256']=hashlib.sha256(card).hexdigest()
        report['stage']='native';save();start=time.monotonic()
        command=[sys.executable,str(pathlib.Path(__file__).with_name('model-batch-native.py')),'--corpus',str(args.corpus),'--manifest',str(args.manifest),'--model-id',args.model_id,'--model',str(modelpath),'--cache',str(cache),'--output',str(native)]
        with (args.directory/'native-stderr.txt').open('w') as errors:
            child=subprocess.Popen(command,stderr=errors,stdout=errors)
            while child.poll() is None:
                try:
                    status=pathlib.Path('/proc')/str(child.pid)/'status'
                    rss=next(int(line.split()[1])*1024 for line in status.read_text().splitlines() if line.startswith('VmRSS:'))
                    report['peakChildRSSBytes']=max(report['peakChildRSSBytes'],rss)
                except (OSError,StopIteration):pass
                if shutil.disk_usage(args.directory).free<1073741824:report['guardFailure']='disk_reserve'
                elif memory_available()<1073741824:report['guardFailure']='memory_reserve'
                elif time.monotonic()-start>1200:report['guardFailure']='walltime_limit'
                if report['guardFailure']:
                    child.terminate()
                    try:child.wait(timeout=10)
                    except subprocess.TimeoutExpired:child.kill();child.wait()
                    break
                time.sleep(.1)
        report.update(exitCode=child.returncode,nativeWallSeconds=time.monotonic()-start,stage='closed')
        if report['guardFailure'] or child.returncode:unfinished('resource_stop' if report['guardFailure'] else 'native_process_error')
        report['nativeStderr']=(args.directory/'native-stderr.txt').read_text()[-65536:]
    except Exception as error:
        report['error']=repr(error);unfinished('acquisition_error' if report['stage']=='acquisition' else 'native_process_error')
    finally:
        if child is not None and child.poll() is None:
            child.terminate()
            try:child.wait(timeout=10)
            except subprocess.TimeoutExpired:child.kill();child.wait()
        modelpath.unlink(missing_ok=True);shutil.rmtree(cache)
        report['ownedModelCacheRemoved']=True
        report['resourceAfter']=dict(cgroup=cgroup_snapshot(),diskFreeBytes=shutil.disk_usage(args.directory).free)
        save()


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ['manifest','corpus','directory']:parser.add_argument('--'+name,type=pathlib.Path,required=True)
    parser.add_argument('--model-id',required=True)
    run(parser.parse_args())
