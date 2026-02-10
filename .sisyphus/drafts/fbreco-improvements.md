# Draft: FBReco Improvements (v2)

## Requirements (confirmed from user)
1. **走行時間の表示**: 一番下の桁が1秒ずつ増えるようにする。1分こいでも数値が増えないバグの確認と修正。
2. **ホーム画面のリアルタイム反映**: 通知はリアルタイムなのにアプリUI側は不定期。リアルタイムに反映する。
3. **マップ: 現在位置をスタートに**: 東京固定→現在位置に変更。
4. **マップ: 目的地検索機能**: 可能であれば検索で目的地を設定したい。
5. **マップ: リアルタイム走行反映**: 距離の反映が不定期→リアルタイムにし、今どのあたりかを表示する。
6. **目的地の再設定機能**: 現在は目的地完了時のみリセット。途中変更ができない。

## Root Cause Analysis

### Issue 1: 走行時間が1秒ずつ増えない & 1分こいでも増えないバグ
**原因分析**: 
- `RideAccumulator.onBikeData()` (line 28): `accumulatedTimeSeconds += clampedIntervalMs / 1000L`
  - 整数除算なので、intervalMs < 1000 の場合は0になる！
  - BLEの通知間隔はバイクにより異なるが、多くのFTMSバイクは約500ms~1000msで通知する
  - 例: 500msの通知間隔 → 500/1000 = 0 → 永遠に時間が増えない!
- ホーム画面はDBの `todayRecord` のみ表示しており、DB更新は30秒ごとのflushのみ
  - つまりUI上で時間が増えるのは最速でも30秒おき
- 通知側は `accumulator.accumulatedTimeSeconds` を直接参照して1秒ごとに更新
  - しかし上記の整数除算バグにより accumulator の値自体が正しくない可能性

**修正方針**:
- RideAccumulator: ミリ秒単位で蓄積し、flush/表示時に秒に変換
- あるいは余りを記録しておくアプローチ

### Issue 2: ホーム画面がリアルタイムでない
**原因分析**:
- HomeViewModel は `dailyRecordRepository.observeToday()` (Room Flow) のみを監視
- DB更新は BikeForegroundService の `flushAccumulatedData()` (30秒ごと) のみ
- 通知は `todayTotalTimeSeconds + accumulator.accumulatedTimeSeconds` で1秒更新
- UIは DB値のみ = 最大30秒遅延

**修正方針**:
- BikeForegroundServiceからリアルタイムの走行データ (accumulator含む) をStateFlowで公開
- HomeViewModelがそのFlowを監視して、DBの値 + accumulator の値をマージ表示

### Issue 3: マップのスタート位置が東京固定
**原因分析**:
- DestinationViewModel.kt line 30: `val startPoint: Position = Position(longitude = 139.6503, latitude = 35.6762)`
- DestinationScreen.kt line 45: カメラもこの座標で初期化

**修正方針**:
- FusedLocationProviderClient で現在位置を一度取得
- ACCESS_FINE_LOCATION は既にmanifestに宣言済み＆パーミッション取得済み
- play-services-location を依存に追加、Hiltで注入
- 取得できない場合は東京をフォールバック

### Issue 4: 目的地検索
**修正方針**:
- Photon (Komoot) API: 無料、APIキー不要、OSMベース、タイポ耐性あり
  - エンドポイント: `https://photon.komoot.io/api/?q={query}&limit=5`
  - GeoJSON形式のレスポンス
  - debounce 300msで検索
- OkHttp/Retrofitで実装（Retrofitは既に間接的に使えるか確認要）
- SearchBar UI → 結果リスト → 選択で地図にピン

### Issue 5: マップのリアルタイム走行反映
**原因分析**:
- Destination の accumulatedDistanceMeters はDB経由のみ更新 (30秒間隔のflush時)
- マップにはスタート地点と目的地の2点のみ表示、中間位置マーカーなし

**修正方針**:
- Issue 2と同様、リアルタイムの累計距離をFlowで公開
- 「現在位置」マーカー: スタート地点から目的地への直線上に、累計距離の割合で配置
- progress比率でスタート〜目的地を線形補間した座標に動くマーカーを表示

### Issue 6: 目的地の再設定
**原因分析**:
- 現在は `completeAndReset()` のみ（到達時のダイアログからのみ呼べる）
- アクティブな目的地がある間は長押しも無効化されている

**修正方針**:
- 目的地カード or メニューに「目的地をリセット」ボタンを追加
- 確認ダイアログ → DestinationRepository.completeDestination(id) で無効化 → 新規設定可能に

## Technical Decisions
- **位置取得**: FusedLocationProviderClient (play-services-location 21.3.0)
  - ACCESS_FINE_LOCATION は既存パーミッションフローに含まれている
- **目的地検索**: Photon API (photon.komoot.io)
  - 無料、APIキー不要
  - OkHttp直接 or Retrofit
- **リアルタイムデータ公開**: BikeForegroundService に StateFlow 追加
  - `data class RideSnapshot(val sessionTime: Long, val sessionDistance: Double, val totalTime: Long, val totalDistance: Double)`
  - 1秒間隔で更新（通知更新と同タイミング）
- **時間蓄積バグ**: ミリ秒残り蓄積方式に変更

## Open Questions
- Photon API の使用に際して特別なアトリビューション要件があるか → OSMクレジットのみ
- OkHttp vs Retrofit → プロジェクトに既にどちらがあるか確認要
- 位置のアニメーション → スムーズに動かすか、離散的に更新するか

## Scope Boundaries
- INCLUDE: 上記6つの改善すべて
- EXCLUDE: オフライン地図キャッシュ、ルート表示（直線のみ）、GPSトラッキング（BLE距離のみ）
