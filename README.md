# たくポケ Android

時間割・変更・学校行事を確認する非公式Androidアプリです。学校の公式サービスではありません。重要な予定は学校の原資料でも確認してください。

## 機能

全機能とiOSとの差分・変更内容は[機能一覧とiOSとの対応](FEATURES.md)を参照してください。比較対象はiOSの `fced0fe` です。

- 通常時間割PDF、時間割変更XLSX、試験PDF、試験返却PDFの取り込み・端末内解析。
- Android標準ファイル選択からOneDrive等の提供元を選択。読み取り権限の保持、起動時・手動・バックグラウンドの更新確認。
- クラス別の今日の予定、授業中表示、週間時間割、連続授業と複数レーン、変更前授業を含む詳細、対象クラス別の変更一覧、学校行事。
- 学校アカウント経由のリンク・名称・授業時刻の取得。リンク検索、お気に入り、非表示、色変更、ブラウザ選択。
- 更新通知、OS標準と7色のメインカラー、初期設定、使い方、保存済みPDF閲覧、アプリ更新確認。

Android 10（API 29）以降。OneDrive等の提供元によって、クラウドの更新が読み取り結果へ届くまで遅れる場合があります。アプリは提供元の同期完了を保証できません。元ファイルは編集・削除しません。

取得・確認・解析の日時は日本時間で表示します。留学生向け授業を隠した場合も変更前の授業へ戻さず、変更一覧にも同じ表示条件を使います。ホームのリンク編集、検索結果のカテゴリ・同点順序、解析結果の確認画面と補完年度の保存もiOSの動作へ合わせています。ファイル選択は処理中でも待ち行列へ入れて取り込みます。元の記載のコピーとPDF閲覧方法は今回の修正対象に含めていません。

## インストール・更新

[Android 0.1.1の配布ページ](https://github.com/n624-dev/takupoke-android/releases/tag/v0.1.1)から署名済みAPKを取得し、端末で開いて案内に従ってください。Android 10以降に対応します。前版0.1.0と同じ署名で、versionCodeは1から2へ更新しています。配布ページにはSHA-256確認用ファイルもあります。

配布元はコミット`ba80526`です。[配布前検証](https://github.com/n624-dev/takupoke-android/actions/runs/37019813932)は全3ジョブが成功し、core45件・API 29／36各35件、lintとdebug／releaseビルドを確認しました。[署名・配布](https://github.com/n624-dev/takupoke-android/actions/runs/37020802155)も成功しています。公開APKのバージョン・署名の前版との一致・SHA-256を確認しました。学校アカウント・実資料・OneDrive・通知・バックグラウンド更新の実機確認は継続中です。

## 開発・テスト

Kotlin / Jetpack Compose。JDK 21、Android SDK 36、Gradle Wrapperを使用します。Android Studioでこのディレクトリを開くか、SDKを設定して実行してください。

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease
```

リリースビルドは環境変数 `TKPK_KEYSTORE`、`TKPK_STORE_PASSWORD`、`TKPK_KEY_ALIAS`、`TKPK_KEY_PASSWORD` を設定した場合だけ配布用鍵で署名します。未設定なら未署名です。鍵・パスワードをコミットしないでください。バージョンは `TKPK_VERSION_NAME`、`TKPK_VERSION_CODE` で指定できます。

手動の「Signed Android release」Actionsは、同じコミットの全検証成功を確認してから署名APKとSHA-256をGitHub Releasesへ公開します。署名情報はGitHub Secretsで管理し、実行後の一時ファイルを削除します。配布鍵は将来の更新にも必要なので、リポジトリ外で安全にバックアップしてください。

GitHub Actionsで単体テスト、lint、debug/releaseビルド、API 29 / 36のエミュレータテストを実行します。テスト資料はコードで生成した架空データです。テスト用アプリはHTTP通信を拒否するトランスポートへ置き換え、学校サイト・本番API・実アカウントへ接続しません。

Actionsの永続キャッシュと成果物のアップロードは使用しません。ジョブ中のGradleファイルはrunnerの一時領域に置き、成功・失敗時とも削除します。実行ログとテスト要約はGitHubの標準実行履歴で確認できます。

手動の「Android screenshots」Actionsで、API 29 / 36とライト／ダークテーマを選び、初期設定・ホーム・一覧・時間割・設定・資料の実画面を撮影できます。新規インストールしたオフラインテスト用アプリを使うため、学校アカウントや学校資料は映りません。ログイン・資料選択前の画面であり、学校データの実動作確認ではありません。

画像はチェックサム付きの分割Base64として実行ログだけに出力します。永続キャッシュやartifactを作らず、runner上の画像も終了時に削除します。画像の復元は撮影ジョブのログを取得して `node scripts/screenshot-transfer.mjs decode JOB_LOG PRIVATE_OUTPUT_DIRECTORY` で行えます。画像と取得ログはリポジトリ外またはGit除外した `private/` 以下へ保存してください。

## 認証

OIDC public client `takupoke-android`、callback `jp.n624.takupoke.android:/oauth/callback`、PKCE S256、scope `openid mapping.read links.read` を使用します。認証サービス側にこのクライアント登録が必要です。アプリにクライアント秘密鍵や学校パスワードを含めません。

## データとライセンス

学校資料・名称・リンク・授業時刻・通知履歴はAndroidバックアップ対象外のアプリ専用領域へ保存し、4月・10月の切り替え後の実行時に削除します。読み取り権限も解除します。個人設定・公開学校行事・提供元の原本は保持します。

学校資料、内部文書、認証情報、署名鍵は公開ソースに含めません。プロジェクト自体のライセンスは未選定です。依存ライブラリのライセンスとNOTICEはアプリの「このアプリについて」で確認できます。
