# OS共通の研究用プロンプト

同じ判断課題には、iOS・Android・Windowsで同じUTF-8指示文を使います。課題ごとに分けた指示文、SHA256、入力と出力の仕様をこのディレクトリで固定します。指示文だけを変更して既存の実測結果を上書きしません。

| 課題 | 指示文 | 状態 |
|---|---|---|
| `single_header_role` | `prompts/single-header-role-ja-v1.txt` | Windowsの既存小分けHEAD課題と同じ1422 bytes。全原文IDから1役割の項目名IDを選ぶ研究用対照 |
| `deterministic_body_id_copy_control` | `prompts/deterministic-body-id-copy-v1.txt` | Androidの既存COPY課題と同じ449 bytes。アプリが確定した本文IDのコピー遵守だけを測る |
| `fieldExtraction_reference` | `prompts/field-extraction-user-reference-v1.txt` | 利用者指定の3927 bytesをそのまま保存した、評価済み参照条件の記録。新しい実行用には選べない |

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

既存のAndroid/CoreAI fieldExtraction指示には、読める確定本文をPRESENTにする条件の説明不足が見つかっています。ここに保存した利用者参照文はPRESENT/EMPTY等を説明していますが、それだけでモデルの実測品質が合格したとは言えません。本番への指示文の採用とモデル有効化は、別途の実測とレビューが必要です。今回、本番の指示・runtime・モデル・catalogは変更しません。
