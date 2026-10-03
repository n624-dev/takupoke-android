# Android PDF端末内復旧

対象は通常・試験・試験返却のPDF。時間割変更XLSX、学校行事APIは対象外。Strict Parserを常に先に実行し、対象となる書式・読み取り失敗だけを復旧待ちとして保存する。破損・暗号化・上限・重複クラス・キャンセル・保存失敗をAIで成功へ変えない。復旧失敗時は前回の正式Analysisを保持し、新しい原本が未反映であることをホーム・時間割・資料詳細に表示する。

## 接続した操作

資料詳細から復旧を開始し、原文から決定できる部分をRulesで処理する。Strict途中成果のページ状態complete / partial / rasterOnlyを区別し、完全に取得した文字・座標・罫線・原文順序を再利用する。部分・未取得ページだけPdfRendererとバンドル日本語OCRで取得する。OCR boxの外に未認識のインクがあるページは完全として扱わない。Rasterの空欄はピクセルで確認する。薄い灰色や色付きの未読印も空欄と区別し、背景ノイズが残るページも推測せず失敗する。罫線は両端が直交する線へ接続する線のみ認め、未認識の「一」「I」を罫線へ変えない。

Vector Readerは可視性を証明できるsubsetだけをcompleteにする。黒以外やstrokeを含む文字、透明度・blend・clip・破線・transfer functionや未対応gsパラメータ、文字と重なるstroke、描画後のfill/image、回転後CropBox外の文字/線はpartialへ落とす。partialに残った文字を原文として再利用せず、実PdfRendererで表示されるページをOCRする。strokeは厳密な縦横軸と、直交・等長basisの座標変換だけを取得する。近似的な横線の急角度miterが元segment外の文字を覆う場合もRasterへ戻す。文字とstrokeの比較はページ20M件に制限し、比較ごとに中止を確認する。上限超過をAIで回避しない。Raster罫線のmaskは実際の連続strokeだけに限定し、セル全域の薄い灰色・色付き画素も確認する。

RecoveryLayoutは年度・学期・クラス・曜日/日付・時限・時刻の原文と位置を結び付ける。Strictは正規化前の年度数値原文を先に捕捉し、数字へ確定できないローマ数字等を無視しない。同じ見出しに異なる年度があればStrictと復旧の両方で拒否し、同年度の重複や一致する西暦/令和表記は原文証拠を保つ。ページ数・フォント・固定列幅を正しさの条件にしない。未分類Sourceを捨てずに失敗する。既知の三行セル、独立した原文役割ラベル、明示的な三行並記区切り、結合時限と専用時計、返却の適用日が一致するPDF注記を扱う。初日の専用時刻だけがある返却PDFは、注記に明記された残り4日に限って通常時刻をコードで生成する。AIの知識から時刻を補わない。時刻・役割・クラスを独立証明できないレイアウトは安全に失敗する。領域と原文/見出しの比較は文書全体20M件、Rasterの罫線比較はページ1M件・画素訪問は32M件を上限とし、内側の走査でも128件ごとに取消を確認する。OCR後の幾何解析はrunInterruptibleでアプリの取消へ接続する。上限を超えた資料の推測や再試行で予算を迂回しない。

Validator通過後も正式Analysisを保存しない。原文と復旧プレビューを利用者が確認し、資料全体の採用を明示した時だけ、原本SHA・選択URI・文書種別・保存期間・年度/学期・Doc/Result・取消状態を再検証する。SQLite transactionで正式Analysis、Metadata、Acceptanceを一緒に保存する。クラス/曜日の絞り込みは表示のみで、採用は全資料である。初回は自動採用しない。同じPDF hash、文書全体fingerprint、結果、各versionが一致する確認記録だけを再利用し、Validatorは再実行する。

プレビューと確認記録はアプリ専用noBackup SQLiteに保存し、学校資料と同じ学期の保存期限で削除する。モデルは公開データとして別の専用フォルダに保存し、学校データの期限切替でモデルを削除しない。処理中のモデルはAppRepository mutexで保護する。モバイルの背景更新は待ち状態の記録までとし、重い復旧・モデル準備は前景の明示操作だけで行う。背景移行・中止後の出力は採用しない。

## 役割と原文の契約v2

固定lessonBindingsは維持する。roleProposalは独立の原文label/columnHeader、役割scope、lessonIndex、矩形、原文atom IDを持つ。外部の役割列見出しは本文inventoryへ混ぜず、header region・axis・page・完全な役割名を検証する。本文全atomの完全partition、並記scope、空欄証明を要求する。モデルの自由なvalueを使わずEvidenceの原文から再構築する。

独立scopeで割当が一意なセルもRulesを最優先にしてモデルをロードしない。Proofから決められる部分を故意にモデルへ回さない。役割の根拠がない欠落を、モデル知識や確認ボタンだけで確定扱いにしない。Strictでもラベル付き行は位置だけで役割を決めず復旧へ止める。無ラベル2行や余剰行は停止し、科目だけの1行はページ内の完全な三行セルで上段位置を校正できる場合だけ受理する。教員/教室だけ残った行を科目へ詰めない。

## 折り返し見出しの有限構造提案

同じ行と原文の隣接行だけで解けるラベルはRulesを優先する。左列の「担当教」/「員:」の間に右列の本文行が挟まる場合など、既知Builderで未解決のセルだけをstructureProposalへ渡す。全ページの年度・学期・クラス・日付・時限・時刻・原文inventoryを先に検証し、保存半期が違えばProviderを作らない。binding検査の一時保留は指定pendingセルだけで、他セルの不正scopeはAI前に拒否する。

モデルが選べるものは元文字group IDと、元セル辺/文字のない測定gapからコードが作ったcut IDだけ。自由な文字・座標・boxは使わない。別コードが完全な原文ラベル、左位置、非重複band、ラベル全消費、本文全atomの一度だけのpartition、役割のラベルbbox内への本文位置を証明する。原本ページを候補で再buildし、Rulesと通常Validatorを通してから未採用プレビューへ進む。実Providerのmetadataを結果に残す。偽ID・役割交換・孤立本文・モデルの自由値は拒否する。

原本のPDF viewerは100〜400%の拡大/縮小・全体表示と縦横移動を提供する。ページ変更や原本digest更新では先頭/全体へ戻る。画面上の17クラス表を利用者が拡大して確認できるようにする。これはAI/OCRの正確性を証明する機能とは分ける。

## モデル

Android生成AI ProviderはLiteRT-LM 0.17.1。ML Kit GenAI / Gemini Nano Prompt APIは採用しない。日本語OCRはGenAIとは別のML Kit Text Recognition 16.0.1。端末内推論のみで、PDF・画像・OCR・Prompt・科目・教員・結果を外部LLMへ送信する経路はない。

追加モデルの配信候補はapp releaseに固定したmodelId、revision、HTTPS URL、size、SHA-256、runtime、最低OS、空きメモリ、backend、licenseで管理する。「latest」を検索・自動採用しない。明示ダウンロード→size/SHA→端末内Runtime準備→架空prompt smoke→atomic active pointer切替の順序。失敗・取消では旧モデルを保持する。起動時の一時ファイル回収と削除操作も接続した。取得UIは容量と「学校の資料は外部へ送信されません」を表示する。native同期推論が戻る前にConversationを閉じず、cancelProcessと終了待ちで解放する。

Qwen3-0.6B INT4は最初の比較候補で、最終採用ではない。公式固定revision a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76、344671744 bytes、SHA-256 03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856、Apache-2.0を登録した。2026-10-03のLiteRT-LM 0.17.1 Linux CPU・context2048で、完全架空の原文atom役割割当3ケースを比較した。Qwen3-0.6B INT4のpeakは約2.15GiB、Qwen3-1.7B INT4は約4.11GiB、Qwen2.5-1.5B INT8は約10.64GiB。各候補とも完全一致2件・誤出力1件だった。これはValidator採用後の誤時間割件数ではなく、モデル単体の小規模smoke結果である。学校入力は使用していない。

この比較では候補を合格にできないためvalidated=falseを維持し、配信/download操作へは公開しない。Linux CPUのcontext2048を、production context4096やAndroid実端末のメモリ・速度・復旧精度へ置き換えない。追加モデル未提供でもRules復旧は動く。実端末メモリ・backend・復旧精度の比較を終えた候補だけを公開する。32-bitプロセスはLiteRT非対応で、OCR/既存機能は維持する。

## 検証と残る確認

公開テストは完全な架空データのみ。通常40slot、5ページ・17クラス・5日の試験/返却、連続時限専用時計、3行並記、返却初日のみの時刻＋残4日PDF注記、未認識インク、曖昧/空欄、余剰Source、役割入替、孤立Source、別日時刻、保存期間/URI/SHA/文書種別の採用競合、モデル切替保持、監視通知queue、保留通知のクラス/日付/現行行の再照合を回帰検証する。

OfflineRunnerのRecoveryScreenTestは実Compose・SQLite・PdfRendererで開始、未採用プレビュー、原本閲覧、全資料採用、中止、モデル未提供、fakeモデル取得・準備・削除・取消、学校資料全削除後のモデル削除を操作する。RecoveryServicesの完全架空mockを使い、学校URL・モデルURLへ通信しない。既知scopeではmock providerの呼出し0回を確認する。JDK21でcore tests、debug APK、androidTest APK、lint、optimized releaseをビルドする。実Android emulator/deviceでのUI、OCR、LiteRT CPU/GPU/NPU、nativeキャンセル、性能は別途実行する。

日本語OCRは認識単位のconfidenceが0.8未満、0/NaN等で未提供、または未認識インクが残る場合partialとして保持する。実バンドル日本語Recognizerの完全架空テストは通常native suiteで実行し、専用tagのJSONだけを取り出して原文・生confidence・取得状態・空欄証明数を記録する。Gradle失敗statusをreport処理で消さず、report欠落もCIで失敗する。テストの成功と、実OCRがcompleteになったことは区別する。

Kotlin 2.4のmetadataを扱うため、[Androidの公式互換表](https://developer.android.com/build/kotlin-support)の最低R8 9.1.29を満たす9.1.31を固定する。[R8の公式override手順](https://r8.googlesource.com/r8/+/refs/heads/main/README.md#replacing-r8-in-agp)に従い、AGP 8.13.2 / Gradle 8.13を維持する。LiteRT 0.17.1 AARにはconsumer keep rulesがなく、JNIはDTO・例外・callbackの名前を参照するため、そのRuntimeパッケージを明示保持する。最適化APKでの端末内Runtime smokeは、モデル配信の合格判定までに別途必要である。

最重要指標は誤採用数。少数の架空fixtureやLinux CPU候補評価を、学校資料での誤採用率・実端末動作確認とみなさない。自動採用は初期版へ追加しない。

2026-10-03の[manual評価37131519065](https://github.com/n624-dev/takupoke-android/actions/runs/37131519065)はParser8・Schema2・Validator4・Prompt3・Recovery2、LiteRT-LM 0.17.1 / x86_64 API36 emulator CPU / context4096で完了した。固定モデル344671744 bytesのsize/SHA、R8 9.1.31のJNI実名6とRunner参照閉包524class/3517member、initialize 15.0秒、smoke、実行中cancel→join、Provider cancel→join、close後の再実行拒否を確認した。peak PSS 2513548 KiB/private footprint 2510684 KiB（約2.40 GiB）から解放後PSS 170451 KiBへ下がった。

16架空ケース中14件でnative構造化出力をdecodeできたが、原文完全一致・Validator採用は0件だった。残り2件は前処理で安全に拒否した。別の本文挟み込み型structure proposal 1件もcertificateで拒否し、誤採用は0件、危険な出力control 13件を拒否した。これはRuntime接続・安全な失敗の実証であり、復旧品質の合格ではない。candidateのvalidated=falseを維持する。今回の実行はParser8であり、後続のParser9 stroke文字対策のnative回帰とは区別する。通常API29/36はそれぞれ54 native testsを通過した。実バンドル日本語OCRは原文「架空科目」と独立空欄を取得したが、一部confidenceが0.8未満のため両APIでPARTIALに保持した。OCRのcomplete復旧成功を証明した結果として扱わない。

## Android native candidate評価（manualのみ）

既存[android.yml](.github/workflows/android.yml)の手動入力`evaluatePinnedCandidate=true`で、同じfeature commitの[runtime-evaluation.yml](.github/workflows/runtime-evaluation.yml)を呼び出せる。通常pushの実行対象、OfflineRunnerのsuiteとネット拒否は変更せず、`-Ptakupoke.runtimeEvaluation=true`の時だけ独立の`src/runtimeEvaluationAndroidTest`と評価用target側`src/runtimeEvaluation`を組み込む。最小JUnit入口から固定bridgeを呼び、SDK・schema・Validator・oracleは同じ最適化APK内で実行するため、別APK間で最適化された共有ライブラリ名へ依存しない。この評価用optimized releaseはdebug鍵で署名し、配布物として公開しない。hostが固定Qwen3-0.6B INT4の344671744 bytesだけをHTTPS取得し、size/SHAを検証して端末へコピーする。アプリ側でもsize/SHAを確認する。学校URL、実在資料、学校入力の送信は使わない。

実LiteRT-LM 0.17.1 / CPU / context4096でinitialize、smoke、Structured Outputのdecode、既存Validator、native cancel、Provider cancel、終了後closeを検証する。16ケースは役割順序、ラベルalias、教員/教室の明示空欄、並記2、同じ文字列の別ID、1/I・0/Oの原文、指示を装った本文、欠落とpartial pageを含む。欠落/partialは前処理やValidatorで安全に失敗するかを記録する。原文atomと独立scopeからRulesで一意に解けるセルについて、**モデル単体の評価**としてProviderを直接呼び、製品のRules優先経路を変更しない。原文再構築後の完全一致、raw完全一致、Validator採否、誤採用、危険な役割混入出力の拒否を別々に記録する。加えて、cheap Rulesで未解決の本文挟み込み型ラベル1件を実structureProposal→原文certificate→元ページ再build→Rules/Validatorで評価し、16件のfield評価とは別のmetricsで記録する。

PSS、private footprint、native PSS、native heap、Java heap、初期化/各推論/取消の時間をActionsログへJSON行で出力する。R8後のJNI class名保持と、Test APK入口から参照するclass/memberの閉包を確認する。評価時だけ別APKのOfflineApplicationから呼ぶvirtual repository hook・constructor・Transport interfaceを保持し、起動時に通信拒否transportの注入を実証する。起動前クラッシュでは架空評価専用のAndroidRuntime/crash診断を出力する。モデル、APK、入力、ログをActions artifact/cacheへ永続保存しない。比較候補の`validated=false`はこの評価でも維持する。16架空ケースやx86_64 emulator CPUの成功は、学校資料の誤採用率、ARM端末メモリ、GPU/NPU性能や配信合格の証明にはしない。manual評価はコードを追加した段階と実行済みの結果を区別する。
