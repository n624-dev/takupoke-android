# Android：1フィールドずつのIDコピー比較（2026-10-04）

Gemma4 E2Bは42項目中42項目、Qwen2.5 1.5Bは42項目中41項目が完全一致しました。アプリが座標・元配列順・本文所属を確定し、モデルに既存IDのコピーだけを任せた実験です。追加AIが必要な復旧やモデル品質合格は証明していません。

| 同じモデル・設定 | 従来の参照指示：全セル一致 | 小分けCOPY：ID配列一致 | 小分け：既存Validator・全結果・正式変換の一致 | 実行未評価／誤採用 |
|---|---:|---:|---:|---:|
| Gemma4 E2B（2,588,147,712 bytes） | 0/13 | **42/42（100%）** | **13/13（100%）** | 0／0 |
| Qwen2.5 1.5B INT8（1,597,931,520 bytes） | 1/13 | **41/42（97.6%）** | **12/13（92.3%）** | 0／0 |

従来の全セル課題ではモデルが状態・本文IDを選びました。今回はアプリが元のroleScopes・固定bindingと全ラベル除外から本文IDを確定し、subject/teacher/roomを別々に呼び出します。モデルは既存bodyCandidatesを元の順序で `{"ids":[...]}` とコピーします。stateやvalueは生成させず、元の根拠に従ってアプリが再構築します。task・指示・アプリの分担が同時に変わった比較なので、プロンプトだけの効果やモデルの意味理解の向上とは断定できません。

元の13正例はRulesだけで全件復旧でき、Providerのavailability/recover呼び出しはともに0でした。実運用ではこの経路を優先できます。今回のモデル呼び出しは不要なCOPY遵守診断であり、AIが必要な判断の分母は0です。未見holdout・通常/試験/返却PDF全体・実Android端末・GPUの品質合格は未認定、catalogも未有効化です。

13セルにはparallel_twoを含み42フィールドの義務があります。確認済み空欄2項目も含め、両モデルは全42項目を実際に開始・返却し、実行エラーは0でした。元の欠落/危険入力3件はアプリの推論前チェックで拒否しました。これはモデルの正しい拒否に加算していません。 `[]` だけでEMPTYを作らず、確認済みteacher/room空欄だけをEMPTYにします。科目の空欄、重複キー・重複ID・未知ID・順序違いは拒否し、ソートや出力修復はしません。

Qwenの唯一の失敗はempty_roomのroomです。入力候補は空でしたが、モデルはp1-a10/p1-a11/p1-a12/p1-a13を各12回、計48ID返しました。重複を厳密に拒否し、実際の提案の組立前に止めました。他の2役の正解を保持しつつ、このセル全体は復旧失敗としています。Gemmaには返却失敗がありませんでした。独立レビュー3名が全84出力と原文順・型・重複・所属を確認し、実際の変更していないcore採点を再実行してCIと完全一致しました。

共通条件はLiteRT-LM API 0.17.1、Linux x86_64 CPU、2スレッド、context4096、出力上限1024、topK1、topP0.95、temperature0、seed42、thinking無効、bundle標準templateです。モデルbytes/revision/hashは従来参照条件と同一です。2つの独立ランナーで同時実行し、モデルごとの指示・seed調整はしていません。所有子プロセスに900秒の上限を設けました。

| モデル | 子プロセスRSS観測最大（GB、十進） | ネイティブ経過（秒） |
|---|---:|---:|
| Gemma4 E2B | 2.821 | 177.087 |
| Qwen2.5 1.5B | 2.612 | 147.432 |

RSSは100ms間隔で監督対象の子だけを観測した値です。GemmaはAMD EPYC 7763、QwenはIntel Xeon Platinum 8370CのLinuxランナーです。CPUが異なるため厳密な速度順位は表さず、全プロセス群のピークやスマートフォンの必要RAMでもありません。通常Android CIはcore165件、API29/36各69件、lintDebug・R8・公開ファイル/画面ログ検査が成功しました。研究用テストもKotlin12件・Python12件が成功しています。

[モデルpin・設定・分母・実行資源・原文SHA](results.json)、[固定45行入力](corpus.json)、[実際のCOPY指示](instruction.txt)、[未変更回答raw](raw/)、[全フィールド/セルの独立原因レビュー](causes.json)、[Gemma独立採点](independent-gemma.json)、[Qwen独立採点](independent-qwen.json)、[第三レビュー](independent-third-review.json)、[通常CI](required-ci.json)、[全ファイルSHA](checksums.json)を公開しています。既存参照条件の回答は[以前の公開比較](../android-model-comparison-20261004/README.md)を再利用し、追加推論していません。

[小分け2モデルCI](https://github.com/n624-dev/takupoke-android/actions/runs/37197351755)、[同source通常検証](https://github.com/n624-dev/takupoke-android/actions/runs/37197351809)。実行sourceは `74b2c06e7142017716f62cefc6bbcd91e34314b2`、元入力SHAは `d15657770607fbc1910c11bdc7a185b296b683905c5823427e05c39bf128ee5a`、COPY指示SHAは `c6d1410ebe5de98ad1934627b3f5115ae396087d758814dbc538daafc750998c` です。架空の消費済み開発資料だけを使い、学校資料・実アカウント・production credentialsは含みません。

[ローカルCPUで選んだ4項目の再現確認](local-four-control-reproduction/README.md)も追加しました。4/4回答がCIと同じbytesで、3正解と同じ空候補の重複失敗1件を再現しました。元の42項目の分母には加算せず、別scopeの記録です。
