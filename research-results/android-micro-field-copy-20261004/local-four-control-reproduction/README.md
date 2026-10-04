# ローカルLinux CPUでの4項目だけの再現

既存Qwen COPY課題からbaselineの科目・担当・教室と、empty_roomの教室を選び、既存の固定weightsで再実行しました。4件ともCIの回答UTF-8 bytesと完全一致しました。3件は正しいIDコピー、1件は空候補に対する48ID重複という同じ失敗です。追加weightsのダウンロードはありません。

元入力・instruction・schema・モデルSHA・LiteRT native SHA・sampler/context/backendは同じです。ローカルはIntel Xeon Platinum8573C、CPU quota4、cgroup16GiBのLinuxです。全OS/別backendの一致、全42件の再現、意味理解、実Android端末品質を保証しません。これは消費済み4項目のCPU再現確認です。

ローカル監督上限は180秒、CIは900秒で異なります。実際は36.855秒で正常終了し、制限の差は今回の回答に影響していません。ローカル最大RSS2,447,044,608 bytesは `getrusage(RUSAGE_CHILDREN).ru_maxrss` の最大子プロセスhigh-water値です。CIの100msサンプリングRSSとは測定方式が違うので同じ意味の速度/RAM比較にはしません。

[比較と全回答](comparison.json)、[actual local native](native.json)、[同じ元CI native](ci-native.json)、[4件の元入力](corpus.json)、[resource](resource.json)、[実行スクリプト原文](run.py)、[nativeログの元bytesとSHA/base64](native-log.json)、[Android独立レビュー](independent-android.json)、[referee](independent-referee.json)、[第三レビュー](independent-third.json)を保存しています。run.pyのパスは実行した環境の記録であり、利用者の環境に同じパスがあるという意味ではありません。既存の厳密decoderは重複出力を拒否し、修復していません。品質合格・catalog有効化は行っていません。
