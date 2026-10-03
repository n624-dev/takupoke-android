# Android PDF端末内復旧

対象は通常・試験・試験返却のPDF。時間割変更XLSX、学校行事APIは対象外。Strict Parserを常に先に実行し、対象となる書式・読み取り失敗だけを復旧待ちとして保存する。破損・暗号化・上限・重複クラス・キャンセル・保存失敗をAIで成功へ変えない。復旧失敗時は前回の正式Analysisを保持し、新しい原本が未反映であることをホーム・時間割・資料詳細に表示する。

## 接続した操作

資料詳細から復旧を開始し、原文から決定できる部分をRulesで処理する。Strict途中成果のページ状態complete / partial / rasterOnlyを区別し、完全に取得した文字・座標・罫線・原文順序を再利用する。部分・未取得ページだけPdfRendererとバンドル日本語OCRで取得する。OCR boxの外に未認識のインクがあるページは完全として扱わない。Rasterの空欄はピクセルで確認する。薄い灰色や色付きの未読印も空欄と区別し、背景ノイズが残るページも推測せず失敗する。罫線は両端が直交する線へ接続する線のみ認め、未認識の「一」「I」を罫線へ変えない。

RecoveryLayoutは年度・学期・クラス・曜日/日付・時限・時刻の原文と位置を結び付ける。ページ数・フォント・固定列幅を正しさの条件にしない。未分類Sourceを捨てずに失敗する。既知の三行セル、独立した原文役割ラベル、明示的な三行並記区切り、結合時限と専用時計、返却の適用日が一致するPDF注記を扱う。初日の専用時刻だけがある返却PDFは、注記に明記された残り4日に限って通常時刻をコードで生成する。AIの知識から時刻を補わない。時刻・役割・クラスを独立証明できないレイアウトは安全に失敗する。

Validator通過後も正式Analysisを保存しない。原文と復旧プレビューを利用者が確認し、資料全体の採用を明示した時だけ、原本SHA・選択URI・文書種別・保存期間・年度/学期・Doc/Result・取消状態を再検証する。SQLite transactionで正式Analysis、Metadata、Acceptanceを一緒に保存する。クラス/曜日の絞り込みは表示のみで、採用は全資料である。初回は自動採用しない。同じPDF hash、文書全体fingerprint、結果、各versionが一致する確認記録だけを再利用し、Validatorは再実行する。

プレビューと確認記録はアプリ専用noBackup SQLiteに保存し、学校資料と同じ学期の保存期限で削除する。モデルは公開データとして別の専用フォルダに保存し、学校データの期限切替でモデルを削除しない。処理中のモデルはAppRepository mutexで保護する。モバイルの背景更新は待ち状態の記録までとし、重い復旧・モデル準備は前景の明示操作だけで行う。背景移行・中止後の出力は採用しない。

## 役割と原文の契約v2

固定lessonBindingsは維持する。roleProposalは独立の原文label/columnHeader、役割scope、lessonIndex、矩形、原文atom IDを持つ。外部の役割列見出しは本文inventoryへ混ぜず、header region・axis・page・完全な役割名を検証する。本文全atomの完全partition、並記scope、空欄証明を要求する。モデルの自由なvalueを使わずEvidenceの原文から再構築する。

独立scopeで割当が一意なセルもRulesを最優先にしてモデルをロードしない。Proofから決められる部分を故意にモデルへ回さない。役割の根拠がない欠落・折返しを、モデル知識や確認ボタンだけで確定扱いにしない。Strictでもラベル付き行は位置だけで役割を決めず復旧へ止める。無ラベル2行や余剰行は停止し、科目だけの1行はページ内の完全な三行セルで上段位置を校正できる場合だけ受理する。教員/教室だけ残った行を科目へ詰めない。

## モデル

Android生成AI ProviderはLiteRT-LM 0.17.1。ML Kit GenAI / Gemini Nano Prompt APIは採用しない。日本語OCRはGenAIとは別のML Kit Text Recognition 16.0.1。端末内推論のみで、PDF・画像・OCR・Prompt・科目・教員・結果を外部LLMへ送信する経路はない。

追加モデルの配信候補はapp releaseに固定したmodelId、revision、HTTPS URL、size、SHA-256、runtime、最低OS、空きメモリ、backend、licenseで管理する。「latest」を検索・自動採用しない。明示ダウンロード→size/SHA→端末内Runtime準備→架空prompt smoke→atomic active pointer切替の順序。失敗・取消では旧モデルを保持する。起動時の一時ファイル回収と削除操作も接続した。取得UIは容量と「学校の資料は外部へ送信されません」を表示する。native同期推論が戻る前にConversationを閉じず、cancelProcessと終了待ちで解放する。

Qwen3-0.6B INT4は最初の比較候補で、最終採用ではない。公式固定revision a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76、344671744 bytes、SHA-256 03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856、Apache-2.0を登録した。2026-10-03のLiteRT-LM 0.17.1 Linux CPU・context2048で、完全架空の原文atom役割割当3ケースを比較した。Qwen3-0.6B INT4のpeakは約2.15GiB、Qwen3-1.7B INT4は約4.11GiB、Qwen2.5-1.5B INT8は約10.64GiB。各候補とも完全一致2件・誤出力1件だった。これはValidator採用後の誤時間割件数ではなく、モデル単体の小規模smoke結果である。学校入力は使用していない。

この比較では候補を合格にできないためvalidated=falseを維持し、配信/download操作へは公開しない。Linux CPUのcontext2048を、production context4096やAndroid実端末のメモリ・速度・復旧精度へ置き換えない。追加モデル未提供でもRules復旧は動く。実端末メモリ・backend・復旧精度の比較を終えた候補だけを公開する。32-bitプロセスはLiteRT非対応で、OCR/既存機能は維持する。

## 検証と残る確認

公開テストは完全な架空データのみ。通常40slot、5ページ・17クラス・5日の試験/返却、連続時限専用時計、3行並記、返却初日のみの時刻＋残4日PDF注記、未認識インク、曖昧/空欄、余剰Source、役割入替、孤立Source、別日時刻、保存期間/URI/SHA/文書種別の採用競合、モデル切替保持、監視通知queue、保留通知のクラス/日付/現行行の再照合を回帰検証する。

OfflineRunnerのRecoveryScreenTestは実Compose・SQLite・PdfRendererで開始、未採用プレビュー、原本閲覧、全資料採用、中止、モデル未提供、fakeモデル取得・準備・削除・取消、学校資料全削除後のモデル削除を操作する。RecoveryServicesの完全架空mockを使い、学校URL・モデルURLへ通信しない。既知scopeではmock providerの呼出し0回を確認する。JDK21でcore tests、debug APK、androidTest APK、lint、optimized releaseをビルドする。実Android emulator/deviceでのUI、OCR、LiteRT CPU/GPU/NPU、nativeキャンセル、性能は別途実行する。

Kotlin 2.4のmetadataを扱うため、[Androidの公式互換表](https://developer.android.com/build/kotlin-support)の最低R8 9.1.29を満たす9.1.31を固定する。[R8の公式override手順](https://r8.googlesource.com/r8/+/refs/heads/main/README.md#replacing-r8-in-agp)に従い、AGP 8.13.2 / Gradle 8.13を維持する。LiteRT 0.17.1 AARにはconsumer keep rulesがなく、JNIはDTO・例外・callbackの名前を参照するため、そのRuntimeパッケージを明示保持する。最適化APKでの端末内Runtime smokeは、モデル配信の合格判定までに別途必要である。

最重要指標は誤採用数。少数の架空fixtureやLinux CPU候補評価を、学校資料での誤採用率・実端末動作確認とみなさない。自動採用は初期版へ追加しない。
