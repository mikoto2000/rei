# Fixed holiday fixture

`cabinet-office-selected-years.csv` は2026-10-29に取得した
[内閣府の公式CSV](https://www8.cao.go.jp/chosei/shukujitsu/syukujitsu.csv)から
1972/1973/1985/1986/1987/1988/1998/2006/2007/2009/2019/2020/2021/2026/2027年の
全行を抽出し、UTF-8 BOM付きに変換した固定fixtureです。
元の名称・日付を保持しています。テスト中の取得・更新は行いません。
パーサー単体テストではMS932バイト列も明示的に生成します。
