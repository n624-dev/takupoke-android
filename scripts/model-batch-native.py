"""ONE fixed research recipe, actual native CPU outputs; inference never opens an oracle."""
import argparse
import hashlib
import json
import pathlib
import os
import resource
import time
import tempfile


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


def digest(path):
    sha=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1048576),b''):sha.update(block)
    return sha.hexdigest()


def run(args):
    import litert_lm
    from litert_lm import (Engine, Backend, ThinkingConfig, SamplerConfig, ConstrainedDecodingConfig,
                          LiteRtLmConstraintProviderType, ResponseFormat)
    corpus=json.loads(args.corpus.read_text())
    manifest=json.loads(args.manifest.read_text())
    model=next(m for m in manifest['models'] if m['id']==args.model_id)
    assert args.model.stat().st_size==model['bytes'] and digest(args.model)==model['sha256']
    native=pathlib.Path(litert_lm.__file__).parent/'liblitert-lm.so'
    assert digest(native)==manifest['nativeLibrarySHA256'], 'Native runtime changed'
    assert args.cache.is_dir(), 'On-disk owned cache must exist before native initialization'
    rows=[dict(name=c['name'],attempted=False,stage='not_attempted') if c['prepared'] else dict(name=c['name'],attempted=False,stage='preparation_rejected') for c in corpus['cases']]
    report=dict(model=model,corpusSHA256=digest(args.corpus),nativeSHA256=digest(native),providerFingerprint=corpus['providerFingerprint'],
                configuration=dict(runtime=manifest['runtime'],backend='CPU',threads=2,contextTokens=4096,maxOutputTokens=1024,topK=1,topP=.95,temperature=0,seed=42,thinking=False,chatTemplate='unmodified bundle default',automaticToolCalling=False),
                initialization='pending',rows=rows)
    def save():atomic_json(args.output,report)
    save();start=time.monotonic()
    try:
        engine=Engine(str(args.model),backend=Backend.CPU(thread_count=2),max_num_tokens=4096,cache_dir=str(args.cache))
    except Exception as error:
        report.update(initialization='error',error=repr(error),initSeconds=time.monotonic()-start)
        for row in rows:
            if row['stage']=='not_attempted':row['stage']='initialization_error'
        save();return
    report.update(initialization='succeeded',initSeconds=time.monotonic()-start);save()
    try:
        for case,row in zip(corpus['cases'],rows):
            if not case['prepared']:continue
            row.update(attempted=True,stage='inference_running');save();start=time.monotonic()
            try:
                with engine.create_conversation(system_message=case['instruction'],thinking_config=ThinkingConfig(enable_thinking=False),
                    sampler_config=SamplerConfig(top_k=1,top_p=.95,temperature=0,seed=42),max_output_tokens=1024,
                    automatic_tool_calling=False,tools=[],constrained_decoding_config=ConstrainedDecodingConfig(enable=True,provider=LiteRtLmConstraintProviderType.LL_GUIDANCE)) as conversation:
                    response=conversation.send_message(json.dumps(case['prompt'],ensure_ascii=False,separators=(',',':')),response_format=ResponseFormat.json(case['schema']))
                row.update(stage='output',completeResponse=response,raw=''.join(part.get('text','') for part in response.get('content',[])))
            except Exception as error:row.update(stage='inference_error',error=repr(error))
            row.update(seconds=time.monotonic()-start,peakRSSKiB=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss);save()
    finally:engine.close()
    report['released']=True;save()


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ['corpus','manifest','model','cache','output']:parser.add_argument('--'+name,type=pathlib.Path,required=True)
    parser.add_argument('--model-id',required=True)
    run(parser.parse_args())
