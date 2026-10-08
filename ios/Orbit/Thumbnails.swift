import AVFoundation
import SwiftUI
import UIKit

/// 動画のサムネイル（iPhone が読める形式だけ。読めない形式はアイコンを出す）
actor ThumbnailCache {
    static let shared = ThumbnailCache()
    private var cache: [URL: UIImage] = [:]
    private var failed: Set<URL> = []

    func image(for url: URL) async -> UIImage? {
        if let img = cache[url] { return img }
        if failed.contains(url) { return nil }
        let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 360, height: 360)
        do {
            let (cg, _) = try await gen.image(at: CMTime(seconds: 1, preferredTimescale: 600))
            let img = UIImage(cgImage: cg)
            if cache.count > 500 { cache.removeAll() }
            cache[url] = img
            return img
        } catch {
            failed.insert(url)
            return nil
        }
    }
}

struct Thumbnail: View {
    let item: MediaItem
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Rectangle().fill(Color(white: 0.16))
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: item.isAudio ? "music.note" : "film")
                    .font(.title2)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: 128, height: 72)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(alignment: .bottom) { ResumeBar(item: item) }
        .task(id: item.url) {
            if !item.isAudio { image = await ThumbnailCache.shared.image(for: item.url) }
        }
    }
}

/// サムネイルの下の、どこまで見たかのバー（長さが分かるときだけ）
struct ResumeBar: View {
    let item: MediaItem
    @State private var fraction: Double = 0

    var body: some View {
        GeometryReader { g in
            if fraction > 0 {
                Rectangle().fill(Theme.accent).frame(width: g.size.width * fraction, height: 3)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }
        }
        .frame(height: 72)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .task(id: item.key) {
            let pos = ResumeStore.get(item)
            guard pos > 0 else { fraction = 0; return }
            let asset = AVURLAsset(url: item.url)
            if let d = try? await asset.load(.duration), d.seconds.isFinite, d.seconds > 0 {
                fraction = min(1, Double(pos) / 1000 / d.seconds)
            }
        }
    }
}

/// 一覧の1行（サムネイル・タイトル・補足）
struct MediaRow: View {
    let item: MediaItem
    var detail: String?

    var body: some View {
        HStack(spacing: 14) {
            Thumbnail(item: item)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(.body)
                    .lineLimit(2)
                if let detail, !detail.isEmpty {
                    Text(detail)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}
