import Foundation

/// 再生位置の記憶（続きから再生）
enum ResumeStore {
    private static let key = "resume"

    static func get(_ item: MediaItem) -> Int64 {
        let dict = UserDefaults.standard.dictionary(forKey: key) as? [String: Int64] ?? [:]
        return dict[item.key] ?? 0
    }

    static func save(_ item: MediaItem, position: Int64, length: Int64) {
        guard length > 0 else { return }
        var dict = UserDefaults.standard.dictionary(forKey: key) as? [String: Int64] ?? [:]
        // 冒頭や最後まで見た場合は覚えない
        if position < 3_000 || position > length - 5_000 {
            dict.removeValue(forKey: item.key)
        } else {
            dict[item.key] = position
        }
        UserDefaults.standard.set(dict, forKey: key)
    }

    static func clear(_ item: MediaItem) {
        var dict = UserDefaults.standard.dictionary(forKey: key) as? [String: Int64] ?? [:]
        dict.removeValue(forKey: item.key)
        UserDefaults.standard.set(dict, forKey: key)
    }
}

/// 再生履歴（新しい順、最大 200 件）
final class HistoryStore: ObservableObject {
    static let shared = HistoryStore()

    struct Entry: Codable, Identifiable, Hashable {
        var item: MediaItem
        var playedAt: Date
        var id: String { item.key }
    }

    @Published private(set) var entries: [Entry] = []
    private let key = "history"

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let list = try? JSONDecoder().decode([Entry].self, from: data) {
            entries = list
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(entries) { UserDefaults.standard.set(data, forKey: key) }
    }

    func add(_ item: MediaItem) {
        entries.removeAll { $0.item.key == item.key }
        entries.insert(Entry(item: item, playedAt: Date()), at: 0)
        if entries.count > 200 { entries.removeLast(entries.count - 200) }
        save()
    }

    func remove(_ entry: Entry) {
        entries.removeAll { $0.id == entry.id }
        save()
    }

    func clear() {
        entries = []
        save()
    }
}

/// 「ファイル」アプリで追加したフォルダ（iCloud Drive・SDカードリーダー・ほかのアプリのフォルダなど）。
/// iPhone ではアプリが勝手に見られる場所が限られるので、選んでもらったフォルダを覚えておく
final class FolderStore: ObservableObject {
    static let shared = FolderStore()

    struct Folder: Identifiable, Hashable {
        var url: URL
        var bookmark: Data
        var id: String { url.absoluteString }
        var name: String { url.lastPathComponent }
    }

    @Published private(set) var folders: [Folder] = []
    private let key = "folders"
    /// 開いたまま使っているファイル（アプリを閉じるまで読めるようにしておく）
    private var accessing: [URL] = []

    private init() {
        let list = UserDefaults.standard.array(forKey: key) as? [Data] ?? []
        for data in list {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else { continue }
            _ = url.startAccessingSecurityScopedResource()
            let bookmark = stale ? ((try? url.bookmarkData()) ?? data) : data
            folders.append(Folder(url: url, bookmark: bookmark))
        }
        save()
    }

    private func save() {
        UserDefaults.standard.set(folders.map(\.bookmark), forKey: key)
    }

    func add(_ url: URL) {
        guard url.startAccessingSecurityScopedResource() || FileManager.default.isReadableFile(atPath: url.path) else { return }
        guard let data = try? url.bookmarkData() else { return }
        folders.removeAll { $0.url == url }
        folders.append(Folder(url: url, bookmark: data))
        save()
    }

    func remove(_ folder: Folder) {
        folder.url.stopAccessingSecurityScopedResource()
        folders.removeAll { $0.id == folder.id }
        save()
    }

    /// 1つずつ選んだファイルは、アプリを閉じるまで読めるようにしておく
    func keepAccess(_ url: URL) {
        if url.startAccessingSecurityScopedResource() { accessing.append(url) }
    }
}
