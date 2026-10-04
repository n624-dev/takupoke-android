# Android：同じ入力でのモデル・プロンプト比較（2026-10-04）

参照プロンプトを6モデルに適用した結果、Qwen2.5 1.5Bは1/13件（7.7%）で状態と原文本文IDが完全一致し、原文から再構築した実際の結果も既存Validatorを通過しました。追加AIモデルの品質合格は認定していません。

| 公開変換済みモデル | ファイル容量（GB、十進） | baseline：状態＋本文ID／返却正例 | reference：状態＋本文ID／返却正例 | reference：実際の再構築・Validator完全成功 | 正例の実行未評価 |
|---|---:|---:|---:|---:|---:|
| Gemma4 E2B | 2.588 | 0/13 | 0/13 | 0/13 | 0 |
| MiniCPM5 1B | 0.793 | 0/12 | 未評価（返却0） | 未評価 | 13 |
| Qwen2.5 1.5B INT8 | 1.598 | 0/13 | **1/13（7.7%）** | **1/13** | 0 |
| Phi-4 mini INT8 | 3.910 | 0/13 | 0/13 | 0/13 | 0 |
| SmolLM2 360M | 0.374 | 0/13 | 0/13 | 0/13 | 0 |
| SmolLM3 3B INT4 | 2.002 | 0/13 | 0/12 | 0/12 | 1 |

全モデルで正例は13件を予定しました。MiniCPMのbaselineは1件の実行エラー、referenceは15呼び出し全件が入力容量エラーです。SmolLM3のreferenceは20分の監督上限で1正例が途中停止し、後続の危険入力2件も未開始でした。これらは品質の0点や正しい拒否に換算していません。

| モデル | 状態正解：baseline → reference | 本文ID正解：baseline → reference | referenceの返却正例の復旧失敗 | referenceの安全制御2件：正しい拒否 | 推論前の準備拒否 |
|---|---:|---:|---:|---:|---:|
| Gemma4 E2B | 0/42 → 41/42 | 16/42 → 3/42 | 13 | 2 | 1 |
| MiniCPM5 1B | 0/36 → 未評価 | 1/36 → 未評価 | 0（返却なし） | 未評価 | 1 |
| Qwen2.5 1.5B | 0/42 → 40/42 | 19/42 → 11/42 | 12 | 2 | 1 |
| Phi-4 mini | 0/42 → 32/42 | 15/42 → 9/42 | 13 | 2 | 1 |
| SmolLM2 360M | 40/42 → 37/42 | 0/42 → 0/42 | 13 | 2 | 1 |
| SmolLM3 3B | 0/42 → 37/39 | 4/42 → 12/39 | 12 | 未評価（未開始） | 1 |

Gemmaのclear_v1は状態41/42・本文ID1/42・全体0/13でした。baseline/clearのネイティブ実行には191.409秒の重なりがありました。referenceは完了済みの同条件Gemma結果を再利用し、残る5モデルを別々のランナーで並列実行しました。モデルごとの指示・seed調整や成功出力への置換は行っていません。

状態と原文IDをprimaryとして採点しています。IDはsources配列の元の順序を保ち、ラベルID・重複・未知ID・別授業への割当を認めません。EMPTYは科目には認めず、該当teacher/roomのemptyVerified、または固定bindingのblankFieldsで確認された空欄だけです。生成valueは診断情報で、実際のPRESENT値はそのIDの原文から再構築し、通常のValidator・全40セルの結果・正式変換を検証しています。

Qwenのteacher_firstは本文IDが正しくても、生成room値が「架空教室」でした。原文p1-a14は「架空室」で、本番の再構築後は完全一致しました。生成valueだけの比較ではこの成功を落とします。Gemma・Qwen・Phiの状態判定変化はプロンプトの影響を支持しますが、本文IDは依然不安定です。Phiにはユーザープロンプトの例示「情報数学」を生成valueへ混ぜる例もありました。モデル固有の能力限界と断定できる比較ではありません。

MiniCPMは要求context4096に対し、公開artifactのメタデータとKVテンソルが1024に固定されていました。参照入力での容量エラーはログとartifactの読み取り専用検証で確認しています。SmolLM3は時間上限で停止しましたが、遅さの根本原因は未確定です。誤採用は観測0件で、危険入力の拒否には元文書の完全性を確認するValidatorも働きます。この拒否をモデルの理解だけの成果とはしていません。

全て架空の既消費開発用fieldExtractionケースです。全正例は既存RulesがProvider呼び出し0で解決できるため、追加AIが必要なPDF復旧の有用性、未見holdout、通常・試験・返却の全資料、実Android端末の品質合格を証明しません。structureProposalの過去結果は今回の分母に混ぜていません。catalogは未有効化です。

共通条件はLiteRT-LM API 0.17.1、Linux x86_64 CPU、2スレッド、要求context4096、出力上限1024、topK1、topP0.95、temperature0、seed42、thinking無効、bundle標準templateです。参照指示はユーザー原文3927 UTF-8 bytes・末尾改行なし、SHA256 `23f711aa564233963fd1a259d0403b45e3d891d6903fc0009dd6853a32371d9c`。schema・parser・Validator・入力は全条件で固定しています。

| モデル：reference | 子プロセスRSS観測最大（GB） | ネイティブ実行経過（秒） |
|---|---:|---:|
| Gemma4 E2B | 3.824 | 281.785 |
| MiniCPM5 1B（入力容量エラー） | 0.812 | 13.479 |
| Qwen2.5 1.5B | 2.940 | 603.071 |
| Phi-4 mini | 6.659 | 376.778 |
| SmolLM2 360M | 1.595 | 506.574 |
| SmolLM3 3B（時間上限） | 3.172 | 1200.047 |

RSSは100ms間隔で監督対象の子プロセスを測定した値です。ランナーのCPU機種が異なるため、厳密な速度順位やスマートフォンの必要RAMとは扱えません。モデル・cacheは終了後に所有範囲だけ削除しています。通常CIはcore165件、API29/36各69件、lint・R8・公開ファイルと画面ログの検査が成功しました。

[比較・モデル容量・pin・SHA・設定・CI結果](results.json)、[全224行の独立原因レビュー](causes.json)、[原因レビューの短いまとめ](causes.md)、[独立した全14群の本番core再採点証明](independent-core-replay.json)、[参照条件6モデルの独立採点](independent-reference-score.json)、[MiniCPM固定KV容量証明](minicpm-compiled-capacity.json)、[共通架空入力](corpus.json)、[未変更raw14群](raw/)を保存しています。results.jsonのallRawReceiptsに各rawの正確なSHA256と実行source commitがあります。rawは受領bytesをそのまま保存し、プロンプトや回答を切り詰めていません。[ファイルごとのSHA256](checksums.json)も確認できます。

実行sourceはbaseline `e46df5174de66a2e2649498db3ac8766c46fdf89`、Gemma baseline/clear `6bc4db1a19b66ca2eb81e0aa97d75dd40f509ce9`、Gemma reference `fe11312515e46727f41d3786155bfc93f756d3f2`、残5reference `1190ed045314565ffb03f5677d9a60af7d71314d`です。[残5モデルCI](https://github.com/n624-dev/takupoke-android/actions/runs/37193926852)、[通常Android検証](https://github.com/n624-dev/takupoke-android/actions/runs/37193926916)も参照できます。
