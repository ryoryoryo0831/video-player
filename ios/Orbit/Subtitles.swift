import Foundation

/// 外部字幕ファイルの検索と、文字コードの変換（Android 版と同じ考え方）。
/// 日本語の字幕は Shift_JIS で作られていることが多く、そのままだと文字化けするので UTF-8 に変換してから渡す
enum Subtitles {
    static let textExtensions: Set<String> = ["srt", "ass", "ssa", "vtt", "smi", "sami"]
    static let allExtensions: Set<String> = textExtensions.union(["sub", "idx"])
    private static let maxTextSize = 10 * 1024 * 1024

    /// 動画と同じフォルダにある、ファイル名が同じ字幕（movie.srt・movie.ja.srt）。日本語らしいものを先頭に
    static func find(for video: URL) -> [URL] {
        guard video.isFileURL else { return [] }
        let dir = video.deletingLastPathComponent()
        let base = video.deletingPathExtension().lastPathComponent.lowercased()
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        let found = files.filter { f in
            guard allExtensions.contains(f.pathExtension.lowercased()) else { return false }
            // movie2.srt のように別の動画の字幕は対象外
            let stem = f.deletingPathExtension().lastPathComponent.lowercased()
            return stem == base || stem.hasPrefix(base + ".")
        }
        // VobSub は .idx を読めば .sub も一緒に読まれるので .sub 単体は除く
        let idxStems = Set(found.filter { $0.pathExtension.lowercased() == "idx" }.map { $0.deletingPathExtension().lastPathComponent })
        return found
            .filter { !($0.pathExtension.lowercased() == "sub" && idxStems.contains($0.deletingPathExtension().lastPathComponent)) }
            .sorted { a, b in
                let sa = languageScore(a.lastPathComponent), sb = languageScore(b.lastPathComponent)
                return sa != sb ? sa > sb : a.lastPathComponent.count < b.lastPathComponent.count
            }
    }

    private static func languageScore(_ name: String) -> Int {
        let n = name.lowercased()
        if [".ja.", ".jp.", ".jpn.", "japanese", "日本語"].contains(where: n.contains) { return 2 }
        if [".en.", ".eng.", "english"].contains(where: n.contains) { return 0 }
        return 1
    }

    /// VLC に渡せる字幕ファイルを返す。文字コードの変換が必要ならキャッシュに UTF-8 版を作る
    static func prepare(_ file: URL) -> URL {
        guard textExtensions.contains(file.pathExtension.lowercased()),
              let data = try? Data(contentsOf: file), data.count <= maxTextSize,
              let converted = toUTF8IfNeeded(data) else { return file }
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("subtitles")
            .appendingPathComponent(stableHash(file.path))
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let out = dir.appendingPathComponent(file.lastPathComponent)
        return (try? converted.write(to: out)) != nil ? out : file
    }

    /// UTF-8 でなければ、日本語（Shift_JIS・EUC-JP）か欧米の文字コードとして読み直して UTF-8 にする。変換不要なら nil
    static func toUTF8IfNeeded(_ data: Data) -> Data? {
        let bytes = [UInt8](data.prefix(3))
        if bytes.starts(with: [0xEF, 0xBB, 0xBF]) || bytes.starts(with: [0xFF, 0xFE]) || bytes.starts(with: [0xFE, 0xFF]) { return nil }
        if String(data: data, encoding: .utf8) != nil { return nil }
        for enc in [String.Encoding.shiftJIS, .japaneseEUC] {
            if let text = String(data: data, encoding: enc), looksJapanese(text) { return text.data(using: .utf8) }
        }
        if let text = String(data: data, encoding: .windowsCP1252) { return text.data(using: .utf8) }
        return String(data: data, encoding: .shiftJIS)?.data(using: .utf8)
    }

    /// 起動のたびに変わらない短い目印（キャッシュのフォルダ名に使う）
    private static func stableHash(_ s: String) -> String {
        var h: UInt64 = 5381
        for b in s.utf8 { h = (h &* 33) &+ UInt64(b) }
        return String(h, radix: 16)
    }

    private static func looksJapanese(_ text: String) -> Bool {
        let kana = text.unicodeScalars.filter { (0x3040...0x30FF).contains($0.value) }.count
        return kana >= 10 || (!text.isEmpty && kana * 50 >= text.count)
    }
}
