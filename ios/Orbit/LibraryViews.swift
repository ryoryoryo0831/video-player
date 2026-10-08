import SwiftUI
import UniformTypeIdentifiers

/// 「動画」タブ：Orbit のフォルダと、追加したフォルダの中のすべての動画（新しい順）
struct VideosView: View {
    @ObservedObject private var folders = FolderStore.shared
    @State private var videos: [(item: MediaItem, modified: Date)] = []
    @State private var loaded = false
    @State private var query = ""

    private var shown: [MediaItem] {
        let list = videos.map(\.item)
        guard !query.isEmpty else { return list }
        return list.filter { $0.title.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if loaded && videos.isEmpty {
                    EmptyHint()
                } else {
                    List {
                        ForEach(Array(shown.enumerated()), id: \.element.id) { i, item in
                            Button { PlayerController.shared.play(shown, startAt: i) } label: {
                                MediaRow(item: item, detail: item.url.deletingLastPathComponent().lastPathComponent)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .listStyle(.plain)
                    .searchable(text: $query, prompt: "動画を検索")
                    .overlay { if !loaded { ProgressView() } }
                }
            }
            .navigationTitle("動画")
            .refreshable { await load() }
            .task { if !loaded { await load() } }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                Task { await load() }
            }
        }
    }

    private func load() async {
        let roots = [MediaFiles.documents] + folders.folders.map(\.url)
        let found = await Task.detached { MediaFiles.allVideos(in: roots) }.value
        videos = found.sorted { $0.modified > $1.modified }
        loaded = true
    }
}

/// 動画が1本もないときの案内
struct EmptyHint: View {
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "film.stack")
                .font(.system(size: 52))
                .foregroundStyle(Theme.gradient)
            Text("動画がまだありません").font(.title3.bold())
            Text("「ファイル」アプリの「このiPhone内 › Orbit」に動画を入れるか、「フォルダ」タブから iCloud Drive などのフォルダを追加してください。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
    }
}

/// 「フォルダ」タブ：Orbit のフォルダ・追加したフォルダ・ファイルを直接開く
struct FoldersView: View {
    @ObservedObject private var store = FolderStore.shared
    @State private var importing = false
    @State private var importFolder = true

    var body: some View {
        NavigationStack {
            List {
                Section("このiPhone") {
                    NavigationLink(value: MediaFiles.documents) {
                        FolderLabel(name: "Orbit のフォルダ", detail: "「ファイル」アプリの「このiPhone内 › Orbit」", icon: "iphone")
                    }
                }
                Section("追加したフォルダ") {
                    ForEach(store.folders) { f in
                        NavigationLink(value: f.url) {
                            FolderLabel(name: f.name, detail: f.url.deletingLastPathComponent().lastPathComponent, icon: "folder.fill")
                        }
                    }
                    .onDelete { idx in idx.map { store.folders[$0] }.forEach(store.remove) }
                    Button {
                        importFolder = true
                        importing = true
                    } label: {
                        FolderLabel(name: "フォルダを追加", detail: "iCloud Drive・USBメモリ・ほかのアプリのフォルダなど", icon: "plus")
                    }
                }
                Section {
                    Button {
                        importFolder = false
                        importing = true
                    } label: {
                        FolderLabel(name: "ファイルを開く", detail: "「ファイル」アプリから動画・音楽を選んで再生", icon: "doc")
                    }
                }
            }
            .navigationTitle("フォルダ")
            .navigationDestination(for: URL.self) { FolderView(dir: $0) }
            .fileImporter(
                isPresented: $importing,
                allowedContentTypes: importFolder ? [.folder] : [.movie, .audio, .item],
                allowsMultipleSelection: !importFolder
            ) { result in
                guard case let .success(urls) = result else { return }
                if importFolder {
                    urls.forEach(store.add)
                } else {
                    urls.forEach(store.keepAccess)
                    let items = urls.map { MediaItem(url: $0) }
                    PlayerController.shared.play(items, startAt: 0)
                }
            }
        }
    }
}

struct FolderLabel: View {
    let name: String
    let detail: String
    let icon: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(Theme.accent)
                .frame(width: 46, height: 46)
                .background(Theme.accent.opacity(0.18), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.body).foregroundStyle(.primary)
                if !detail.isEmpty {
                    Text(detail).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .padding(.vertical, 2)
    }
}

/// フォルダの中身（フォルダと動画・音楽）
struct FolderView: View {
    let dir: URL
    @State private var listing = MediaFiles.Listing(folders: [], media: [])
    @State private var loaded = false

    var body: some View {
        List {
            ForEach(listing.folders, id: \.self) { f in
                NavigationLink(value: f) { FolderLabel(name: f.lastPathComponent, detail: "", icon: "folder.fill") }
            }
            ForEach(Array(listing.media.enumerated()), id: \.element.id) { i, item in
                Button { PlayerController.shared.play(listing.media, startAt: i) } label: { MediaRow(item: item) }
                    .buttonStyle(.plain)
            }
        }
        .listStyle(.plain)
        .overlay {
            if loaded && listing.folders.isEmpty && listing.media.isEmpty {
                Text("このフォルダには動画や音楽がありません").foregroundStyle(.secondary)
            }
        }
        .navigationTitle(dir == MediaFiles.documents ? "Orbit" : dir.lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        let d = dir
        listing = await Task.detached { MediaFiles.list(d) }.value
        loaded = true
    }
}

/// 「履歴」タブ：最近再生したもの
struct HistoryView: View {
    @ObservedObject private var history = HistoryStore.shared
    @State private var confirmClear = false

    var body: some View {
        NavigationStack {
            List {
                ForEach(history.entries) { e in
                    Button { PlayerController.shared.play([e.item], startAt: 0) } label: {
                        MediaRow(item: e.item, detail: e.playedAt.formatted(.relative(presentation: .named)))
                    }
                    .buttonStyle(.plain)
                }
                .onDelete { idx in idx.map { history.entries[$0] }.forEach(history.remove) }
            }
            .listStyle(.plain)
            .overlay {
                if history.entries.isEmpty {
                    Text("再生した動画がここに表示されます").foregroundStyle(.secondary)
                }
            }
            .navigationTitle("履歴")
            .toolbar {
                if !history.entries.isEmpty {
                    Button("すべて削除", role: .destructive) { confirmClear = true }
                }
            }
            .confirmationDialog("再生履歴をすべて削除しますか？", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("削除", role: .destructive) { history.clear() }
            }
        }
    }
}
