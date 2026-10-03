#!/usr/bin/env bash
set -euo pipefail
evaluation_dir="${RUNNER_TEMP:?Set RUNNER_TEMP to a disposable evaluation directory}/takupoke-runtime-evaluation"
candidate=app/src/runtimeEvaluationAndroidTest/assets/litert-evaluation-candidate.json
mkdir -p "$evaluation_dir"
case "${1:-}" in
  prepare)
    # The manual property adds test sources and signs only this optimized test
    # build with the debug key. It never changes release distribution signing.
    ./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest --no-daemon --no-build-cache -Dorg.gradle.jvmargs=-Xmx3g -Ptakupoke.runtimeEvaluation=true
    ./gradlew --stop
    python3 - "$candidate" "$evaluation_dir" <<'PY'
import json,os,pathlib,re,subprocess,sys,zipfile
c=json.loads(pathlib.Path(sys.argv[1]).read_text())
assert c['modelId']=='qwen3-0.6b-int4' and not c['validated']
assert c['version']=='a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76'
assert c['url']=='https://huggingface.co/litert-community/Qwen3-0.6B/resolve/'+c['version']+'/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm'
assert c['size']==344671744 and c['sha256']=='03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856'
root=pathlib.Path(sys.argv[2])
for name in ('url','size','sha256'): (root/name).write_text(str(c[name]))
mapping=pathlib.Path('app/build/outputs/mapping/release/mapping.txt').read_text()
entries=[line[:-1].split(' -> ') for line in mapping.splitlines() if line.startswith('com.google.ai.edge.litertlm.') and line.endswith(':') and ' -> ' in line]
names=dict(entries)
# R8 can rename synthetic local lambdas; JNI's literal FindClass references
# must retain their actual class names. Check the pinned native binary itself.
with zipfile.ZipFile('app/build/outputs/apk/release/app-release.apk') as apk:
    native=b''.join(apk.read(name) for name in apk.namelist() if name.startswith('lib/x86_64/') and 'litertlm' in name and name.endswith('.so'))
refs={name.decode().replace('/','.') for name in re.findall(rb'com/google/ai/edge/litertlm/[A-Z][A-Za-z0-9_$]*',native)}
assert len(refs)>=6 and all(names.get(name)==name for name in refs), 'Native JNI class references must survive R8'
assert 'com.google.ai.edge.litertlm.LiteRtLmJniException' in refs
assert 'jp.n624.takupoke.android.TakupokeApplication -> jp.n624.takupoke.android.TakupokeApplication:' in mapping
assert 'jp.n624.takupoke.android.Transport -> jp.n624.takupoke.android.Transport:' in mapping
assert 'androidx.tracing.Trace -> androidx.tracing.Trace:' in mapping
assert 'jp.n624.takupoke.android.LiteRtRuntimeEvaluationHarness -> jp.n624.takupoke.android.LiteRtRuntimeEvaluationHarness:' in mapping
assert re.search(r'AppRepository createRepository\(\).* -> createRepository$',mapping,re.M), 'Offline repository virtual hook must survive R8'
# Check the actual optimized cross-APK entry/runner classes rather than
# assuming a successful compile proves shared library ABI compatibility.
sdk=pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
dexdump=sdk/'build-tools/36.0.0/dexdump'
dumps={}
for label,filename in [('target','app/build/outputs/apk/release/app-release.apk'),('test','app/build/outputs/apk/androidTest/release/app-release-androidTest.apk')]:
    parts=[]
    with zipfile.ZipFile(filename) as apk:
        for name in apk.namelist():
            if re.fullmatch(r'classes\d*\.dex',name):
                dex=root/(label+'-'+name);dex.write_bytes(apk.read(name))
                parts.append(subprocess.check_output([str(dexdump),'-d',str(dex)]).decode(errors='replace'))
                dex.unlink()
    dumps[label]='\n'.join(parts)
classes=set(re.findall(r"Class descriptor\s+: '(L[^']+;)'",dumps['target']+dumps['test']))
entriesToCheck={'Ljp/n624/takupoke/android/OfflineRunner;','Ljp/n624/takupoke/android/OfflineApplication;','Ljp/n624/takupoke/android/RejectNetwork;','Ljp/n624/takupoke/android/LiteRtRuntimeEvaluationTest;','Landroidx/test/runner/AndroidJUnitRunner;'}
checked=0;bridge=False
for descriptor,body in re.findall(r"Class descriptor\s+: '(L[^']+;)'([\s\S]*?)(?=Class descriptor\s+:|\Z)",dumps['test']):
    if descriptor in entriesToCheck or descriptor.startswith('Landroidx/test/platform/tracing/'):
        refs=set(re.findall(r'L(?:[a-zA-Z0-9_$]+/)*[a-zA-Z0-9_$]+;',body))
        missing={ref for ref in refs-classes if not ref.startswith(('Landroid/','Ljava/','Ljavax/','Ldalvik/','Lsun/','Lj$/'))}
        assert not missing, 'Evaluation entry shared dependency absent: '+descriptor+' '+str(sorted(missing))
        checked+=1
    if descriptor=='Ljp/n624/takupoke/android/LiteRtRuntimeEvaluationTest;':
        bridge='LiteRtRuntimeEvaluationHarness;.evaluate:(Landroid/content/Context;Landroid/content/Context;Ljava/lang/String;)V' in body
assert checked>=5 and bridge
print(json.dumps({'event':'optimized_entry_abi','classesChecked':checked,'missingClassReferences':0,'fixedBridgeInvoked':True}))
compiler=re.search(r'^# compiler_version: (.+)$',mapping,re.M)
assert compiler
print(json.dumps({'event':'r8_keep','r8Version':compiler.group(1),'nativeReferencedClassesPreserved':len(refs),'optimized':True,'offlineVirtualHookRetained':True,'distributionArtifact':False}))
PY
    curl --fail --location --proto '=https' --proto-redir '=https' --max-redirs 5 --connect-timeout 20 --max-time 360 --max-filesize 344671744 --output "$evaluation_dir/candidate.litertlm" "$(cat "$evaluation_dir/url")"
    python3 - "$candidate" "$evaluation_dir/candidate.litertlm" <<'PY'
import hashlib,json,pathlib,sys
c=json.loads(pathlib.Path(sys.argv[1]).read_text());p=pathlib.Path(sys.argv[2])
assert p.stat().st_size==c['size']
with p.open('rb') as f: digest=hashlib.file_digest(f,'sha256').hexdigest()
assert digest==c['sha256']
print(json.dumps({'event':'download_verified','bytes':p.stat().st_size,'sha256':digest,'schoolInputUploaded':False}))
PY
    ;;
  run)
    adb install -r app/build/outputs/apk/release/app-release.apk
    adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
    # The public model alone uses the target's scoped external directory.
    # An optimized non-debuggable APK cannot read shell's /data/local/tmp.
    adb shell mkdir -p /sdcard/Android/data/jp.n624.takupoke.android/files/runtime-evaluation
    adb push "$evaluation_dir/candidate.litertlm" /sdcard/Android/data/jp.n624.takupoke.android/files/runtime-evaluation/candidate.litertlm
    adb logcat -G 4M
    adb logcat -c
    adb logcat -v raw -s TakupokeRuntimeEvaluation:I '*:S' > "$evaluation_dir/runtime.log" &
    evaluation_log_pid=$!
    stop_evaluation_log() {
      if [[ -n "$evaluation_log_pid" ]]; then
        kill "$evaluation_log_pid" 2>/dev/null || true
        wait "$evaluation_log_pid" 2>/dev/null || true
        evaluation_log_pid=''
      fi
    }
    cleanup_evaluation() {
      stop_evaluation_log
      adb logcat -d -v raw -s TakupokeRuntimeEvaluation:I '*:S' > "$evaluation_dir/runtime.log" || true
      # Startup can crash before the first evaluation tag (for example a
      # cross-APK VerifyError). This disposable device has no school input.
      adb logcat -d -v threadtime -s AndroidRuntime:E DEBUG:E libc:F '*:S' > "$evaluation_dir/startup-diagnostics.log" || true
      adb logcat -b crash -d -v threadtime >> "$evaluation_dir/startup-diagnostics.log" || true
      adb shell rm -f /sdcard/Android/data/jp.n624.takupoke.android/files/runtime-evaluation/candidate.litertlm || true
      adb uninstall jp.n624.takupoke.android.test || true
      adb uninstall jp.n624.takupoke.android || true
      # Only invented input/model metrics are emitted, never an APK or model.
      python3 - "$evaluation_dir/runtime.log" <<'PY'
import json,pathlib,sys
for line in pathlib.Path(sys.argv[1]).read_text(errors='replace').splitlines():
    try: row=json.loads(line)
    except json.JSONDecodeError: continue
    if isinstance(row,dict) and row.get('event') in {'configuration','initialize','smoke','native_cancel','case','structure_case','provider_cancel','release','summary','cleanup'}:
        print('TAKUPOKE_RUNTIME_REPORT '+json.dumps(row,ensure_ascii=False))
PY
      python3 - "$evaluation_dir/startup-diagnostics.log" <<'PY'
import pathlib,sys
for line in pathlib.Path(sys.argv[1]).read_text(errors='replace').splitlines()[:500]:
    print('TAKUPOKE_RUNTIME_DIAGNOSTIC '+line)
PY
    }
    trap cleanup_evaluation EXIT
    timeout --signal=TERM --kill-after=30s 930s adb shell am instrument -w -r -e class jp.n624.takupoke.android.LiteRtRuntimeEvaluationTest -e runtimeEvaluation true -e modelPath /sdcard/Android/data/jp.n624.takupoke.android/files/runtime-evaluation/candidate.litertlm jp.n624.takupoke.android.test/jp.n624.takupoke.android.OfflineRunner | tee "$evaluation_dir/instrumentation.log"
    stop_evaluation_log
    adb logcat -d -v raw -s TakupokeRuntimeEvaluation:I '*:S' > "$evaluation_dir/runtime.log"
    python3 - "$evaluation_dir/instrumentation.log" "$evaluation_dir/runtime.log" <<'PY'
import json,pathlib,sys
text=pathlib.Path(sys.argv[1]).read_text()
assert 'OK (1 test)' in text and 'INSTRUMENTATION_CODE: -1' in text, 'Native evaluation failed; see instrumentation diagnostics'
rows=[]
for line in pathlib.Path(sys.argv[2]).read_text(errors='replace').splitlines():
    try: rows.append(json.loads(line))
    except json.JSONDecodeError: pass
summary=next(row for row in rows if isinstance(row,dict) and row.get('event')=='summary')
assert summary['cases']==16 and summary['falseAdoptions']==0
assert summary['structureCases']==1 and summary['structureFalseAdoptions']==0
assert len([row for row in rows if isinstance(row,dict) and row.get('event')=='structure_case'])==1
assert summary['initialized'] and summary['released'] and summary['nativeCancellationDemonstrated']
provider=next(row for row in rows if isinstance(row,dict) and row.get('event')=='provider_cancel')
assert provider['requestedWhileActive'] and provider['joined']
assert summary['catalogValidated'] is False and summary['qualityApproved'] is False
assert len([row for row in rows if isinstance(row,dict) and row.get('event')=='case'])==16
print('Synthetic native runtime evaluation completed; model approval remains false.')
PY
    ;;
  *) echo 'Usage: bash scripts/evaluate-litert-model.sh prepare|run' >&2;exit 2;;
esac
