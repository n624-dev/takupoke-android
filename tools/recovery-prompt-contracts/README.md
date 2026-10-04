# OS共通の研究用プロンプト

同じ判断課題には、iOS・Android・Windowsで同じUTF-8指示文を使います。課題ごとに分けた指示文、SHA256、入力と出力の仕様をこのディレクトリで固定します。指示文だけを変更して既存の実測結果を上書きしません。

| 課題 | 指示文 | 状態 |
|---|---|---|
| `single_header_role` | `prompts/single-header-role-ja-v1.txt` | Windowsの既存小分けHEAD課題と同じ1422 bytes。全原文IDから1役割の項目名IDを選ぶ研究用対照 |
| `deterministic_body_id_copy_control` | `prompts/deterministic-body-id-copy-v1.txt` | Androidの既存COPY課題と同じ449 bytes。アプリが確定した本文IDのコピー遵守だけを測る |
| `fieldExtraction_reference` | `prompts/field-extraction-user-reference-v1.txt` | 利用者指定の3927 bytesをそのまま保存した、評価済み参照条件の記録。新しい実行用には選べない |
| `field_extraction` | `prompts/field-extraction-v4.txt` | 共通contract版2・本番Providerの指示版4。読み取れる確定本文をPRESENTにする条件、モード別空欄証明、元配列順を明記 |

HEAD選択と、確定済みBODY IDのCOPYは異なる課題です。COPYの高得点を意味理解やAIが必要な復旧の合格と扱いません。現在の対照では通常のRulesが確定できるため、有用なAI復旧の分母は0です。

`manifest.json`は各指示文の版・byte数・SHA・課題・状態・入出力schemaを記録します。`protocol.json`は共通の入力包絡と厳密な検証を記録します。HEADは `{targetRole,cellData:{sources,allowedRoleLabels}}`、COPYは `{mode,lessonIndex,role,bodyCandidates}` です。全元IDのschema enumは、HEADでは全sources、COPYではアプリが保持する全元セルIDから設定します。元配列の順序を守り、並べ替えや出力修復はしません。空の `ids` は単独でEMPTYを証明しません。

`check_shared_prompts.py`は、manifestを書き換えても別の指示文・課題を認可できない固定ハッシュを持ちます。完全架空の30個の共通JSON検証例も確認します。JSON/grammarへの適合と意味の正しさは別で、他役のIDが構文上選べても採用の根拠にはなりません。最終的な座標・原文・完全性・状態・正式結果の検証は、各OSの既存certificate/Validatorに残します。アーカイブのfieldExtraction用に代替Validatorは実装しません。

```sh
python3 tools/recovery-prompt-contracts/check_shared_prompts.py
python3 tools/recovery-prompt-contracts/test_shared_prompts.py
python3 tools/recovery-prompt-contracts/check_shared_prompts.py --request single_header_role --input fictional-head.json
python3 tools/recovery-prompt-contracts/check_shared_prompts.py --request deterministic_body_id_copy_control --input fictional-copy.json --all-source-ids original-cell-ids.json
```

`--request`は実際にこの固定指示文を読み、検証した入力と全元IDの出力schemaを用意する研究用CLIです。推論は行いません。Windows/Androidの同一課題の研究ハーネスは、現在の固定実行と結果公開を保持した後にこの資産をbyte同一で読み込む形へ接続します。iOSには対応する新しいnative小分けコホートを追加しません。既存の成績の悪いbaselineは測定履歴として残し、この共有のために再実行しません。

旧fieldExtraction指示には、読める確定本文をPRESENTにする条件の説明不足と、roleScopesのemptyVerifiedをblankFieldsと混同する記述が見つかっています。新しい`field_extraction`資産は本番Providerへの接続対象として別の版に固定し、評価済み利用者参照文を変更しません。状態名は各native schemaの既存の綴りに従わせます。native schemaを渡さないWindowsは、既存セルJSONに公開5状態名のresponseStateTokensだけを追加します。意味・割当の答えは追加しません。

状態がPRESENT以外ならvalueとevidenceを空にすること、元sources配列順で全本文をコピーすることを共通化します。roleScopesがある場合はmatching scopeのemptyVerified、ない場合はbindingとblankFieldsを使い分けます。EMPTYはteacher/roomだけです。既存のgeometry/Validator/Strict-first、runtime、モデル、catalogは変更しません。指示版4の採用は指示の整合性改善であり、実測精度向上やモデル品質合格ではありません。llamaの既存closed-think処理も維持します。


iOSの実ProviderはSystemLanguageModel、llama.cpp、CoreAIの3経路です。共通資産をアプリ本体と別ビルドのCoreAI runtimeへ同梱し、実際のloaderが2939 bytesとSHA256を確認してから指示に使います。`check_ios_adapters.py`は元資産と両コピー、SDKビルド後にはアプリ内とCoreAI resource bundle内の実ファイルを照合します。fieldExtractionのmetadataはpromptVersion4、変更していないstructureProposalの成功metadataは3です。RecoveryVersion2、Schema2、Validator4は維持します。Swiftのloader検証と既存native回帰、iPhone向けSDKビルドを専用CIで確認し、モデルの推論成功・精度確認とは区別します。


| OS | 実際のfieldExtraction呼出し元 | 指示/状態表記の適合方法 |
|---|---|---|
| iOS | `SystemLanguageRecoveryProvider`、`LocalLlamaRecoveryProvider`、`CoreAIRecoveryProvider`→別runtime | app/runtimeの同一資産をSHA検証、既存lowercase guided/grammar schemaを維持 |
| Android | `LiteRtRecoveryProvider` | APKの同一資産をbuild/runtimeでSHA検証、既存UPPERCASE native schemaを維持 |
| Windows | `WindowsLanguageRecoveryProvider`、`FoundryLocalRecoveryProvider`→`RecoveryStructure.Instruction` | 同一assembly resourceをSHA検証、schemaを渡さないwireには公開lowercase5状態の`responseStateTokens`を付加 |

この一覧は実装対象を示します。各OSの実際の適合・パッケージ確認結果はそのadapterのCIで記録し、共通テキストのレビューだけで完了とは扱いません。
