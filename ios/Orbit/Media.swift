import Foundation

/// 再生する1件分（端末内のファイルや、「ファイル」アプリで選んだファイル）
struct MediaItem: Identifiable, Hashable, Codable {
    var url: URL
    var title: String

    /// 続きから再生・履歴などで使う、ファイルごとの目印
    var key: String { url.isFileURL ? url.standardizedFileURL.path : url.absoluteString }
    var id: String { key }
    var isAudio: Bool { MediaFiles.isAudio(url) }

    init(url: URL, title: String? = nil) {
        self.url = url
        self.title = title ?? url.lastPathComponent
    }
}

/// 端末内の動画・音楽ファイルの判定とフォルダの一覧
enum MediaFiles {
    static let videoExtensions: Set<String> = [
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "f4v", "mpg", "mpeg", "m2v",
        "ts", "m2ts", "mts", "3gp", "3g2", "ogv", "vob", "rm", "rmvb", "asf", "divx", "xvid",
    ]
    static let audioExtensions: Set<String> = [
        "mp3", "m4a", "m4b", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ape", "aif", "aiff",
        "mka", "wv", "tta", "dsf", "dff", "ac3", "dts", "amr", "mpc",
    ]

    static func isVideo(_ url: URL) -> Bool { videoExtensions.contains(url.pathExtension.lowercased()) }
    static func isAudio(_ url: URL) -> Bool { audioExtensions.contains(url.pathExtension.lowercased()) }
    static func isMedia(_ url: URL) -> Bool { isVideo(url) || isAudio(url) }

    /// Orbit 専用のフォルダ（「ファイル」アプリの「このiPhone内 › Orbit」）
    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    struct Listing {
        var folders: [URL]
        var media: [MediaItem]
    }

    /// フォルダの中身（フォルダと動画・音楽。「2話」が「10話」より前になる並び順）
    static func list(_ dir: URL) -> Listing {
        let fm = FileManager.default
        let children = (try? fm.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]
        )) ?? []
        var folders: [URL] = []
        var media: [MediaItem] = []
        for url in children {
            let isDir = (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            if isDir { folders.append(url) } else if isMedia(url) { media.append(MediaItem(url: url)) }
        }
        folders.sort { naturalLess($0.lastPathComponent, $1.lastPathComponent) }
        media.sort { naturalLess($0.title, $1.title) }
        return Listing(folders: folders, media: media)
    }

    /// フォルダの中の動画を、サブフォルダもたどってすべて集める
    static func allVideos(in roots: [URL], limit: Int = 20000) -> [(item: MediaItem, modified: Date)] {
        var result: [(MediaItem, Date)] = []
        let keys: [URLResourceKey] = [.isRegularFileKey, .contentModificationDateKey]
        for root in roots {
            guard let e = FileManager.default.enumerator(
                at: root, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles, .skipsPackageDescendants]
            ) else { continue }
            for case let url as URL in e {
                guard isVideo(url) else { continue }
                let values = try? url.resourceValues(forKeys: Set(keys))
                guard values?.isRegularFile == true else { continue }
                result.append((MediaItem(url: url), values?.contentModificationDate ?? .distantPast))
                if result.count >= limit { return result }
            }
        }
        return result
    }

    static func naturalLess(_ a: String, _ b: String) -> Bool {
        a.localizedStandardCompare(b) == .orderedAscending
    }
}

/// 時間の表示（1:05・1:00:00）
func formatTime(_ ms: Int64) -> String {
    let total = max(ms, 0) / 1000
    let h = total / 3600, m = total % 3600 / 60, s = total % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

/// 再生速度の表示（1x・1.25x）
func formatRate(_ r: Float) -> String {
    var s = String(format: "%.2f", r)
    while s.hasSuffix("0") { s.removeLast() }
    if s.hasSuffix(".") { s.removeLast() }
    return s + "x"
}
