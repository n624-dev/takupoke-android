# たくポケ Android

時間割・変更・学校行事を確認する非公式Androidアプリです。学校の公式サービスではありません。重要な予定は学校の原資料でも確認してください。

## 機能

- 通常時間割PDF、時間割変更XLSX、試験PDF、試験返却PDFの取り込み・端末内解析。
- Android標準ファイル選択からOneDrive等の提供元を選択。読み取り権限の保持、起動時・手動・バックグラウンドの更新確認。
- 今日と週間の時間割、変更反映、授業詳細、クラスの併合表示、学校行事。
- 学校アカウント経由のリンク・名称・授業時刻の取得。リンク検索、お気に入り、非表示、色変更、ブラウザ選択。
- 更新通知、7色のメインカラー、初期設定、使い方、保存済みPDF閲覧、アプリ更新確認。

Android 10（API 29）以降。OneDrive等の提供元によって、クラウドの更新が読み取り結果へ届くまで遅れる場合があります。アプリは提供元の同期完了を保証できません。元ファイルは編集・削除しません。

## 開発・テスト

Kotlin / Jetpack Compose。JDK 21、Android SDK 36、Gradle Wrapperを使用します。Android Studioでこのディレクトリを開くか、SDKを設定して実行してください。

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease
```

リリースビルドは環境変数 `TKPK_KEYSTORE`、`TKPK_STORE_PASSWORD`、`TKPK_KEY_ALIAS`、`TKPK_KEY_PASSWORD` を設定した場合だけ配布用鍵で署名します。未設定なら未署名です。鍵・パスワードをコミットしないでください。バージョンは `TKPK_VERSION_NAME`、`TKPK_VERSION_CODE` で指定できます。

GitHub Actionsで単体テスト、lint、debug/releaseビルド、API 29 / 36のエミュレータテストを実行します。テスト資料はコードで生成した架空データです。テスト用アプリはHTTP通信を拒否するトランスポートへ置き換え、学校サイト・本番API・実アカウントへ接続しません。

Actionsの永続キャッシュと成果物のアップロードは使用しません。ジョブ中のGradleファイルはrunnerの一時領域に置き、成功・失敗時とも削除します。実行ログとテスト要約はGitHubの標準実行履歴で確認できます。

## 認証

OIDC public client `takupoke-android`、callback `jp.n624.takupoke.android:/oauth/callback`、PKCE S256、scope `openid mapping.read links.read` を使用します。認証サービス側にこのクライアント登録が必要です。アプリにクライアント秘密鍵や学校パスワードを含めません。

## データとライセンス

学校資料・名称・リンク・授業時刻・通知履歴はAndroidバックアップ対象外のアプリ専用領域へ保存し、4月・10月の切り替え後の実行時に削除します。読み取り権限も解除します。個人設定・公開学校行事・提供元の原本は保持します。

学校資料、内部文書、認証情報、署名鍵は公開ソースに含めません。プロジェクト自体のライセンスは未選定です。依存ライブラリのライセンスとNOTICEはアプリの「このアプリについて」で確認できます。
