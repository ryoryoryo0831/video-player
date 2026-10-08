import SwiftUI

@main
struct OrbitApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
    }
}

enum Theme {
    static let accent = Color(red: 0.31, green: 0.55, blue: 1.0)
    static let gradient = LinearGradient(
        colors: [Color(red: 0.24, green: 0.48, blue: 1.0), Color(red: 0.42, green: 0.36, blue: 1.0), Color(red: 0.64, green: 0.33, blue: 0.95)],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )
}

/// 下のタブ（動画・フォルダ・履歴）と、再生中のミニプレイヤー
struct ContentView: View {
    @ObservedObject private var player = PlayerController.shared

    var body: some View {
        TabView {
            VideosView()
                .withMiniPlayer()
                .tabItem { Label("動画", systemImage: "film") }
            FoldersView()
                .withMiniPlayer()
                .tabItem { Label("フォルダ", systemImage: "folder") }
            HistoryView()
                .withMiniPlayer()
                .tabItem { Label("履歴", systemImage: "clock.arrow.circlepath") }
        }
        .fullScreenCover(isPresented: $player.isPresented) {
            PlayerView()
        }
    }
}

extension View {
    /// 各タブの一番下（タブバーのすぐ上）に、再生中ならミニプレイヤーを出す
    func withMiniPlayer() -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) { MiniPlayerIfPlaying() }
    }
}

struct MiniPlayerIfPlaying: View {
    @ObservedObject private var player = PlayerController.shared

    var body: some View {
        if player.current != nil && !player.isPresented { MiniPlayer() }
    }
}

/// 画面の下に出る、再生中の動画・曲（タップで再生画面へ）
struct MiniPlayer: View {
    @ObservedObject private var player = PlayerController.shared

    var body: some View {
        VStack(spacing: 0) {
            ProgressView(value: player.lengthMs > 0 ? Double(player.timeMs) / Double(player.lengthMs) : 0)
                .progressViewStyle(.linear)
                .tint(Theme.accent)
            HStack(spacing: 12) {
                Image(systemName: player.current?.isAudio == true ? "music.note" : "film")
                    .font(.title3)
                    .frame(width: 46, height: 46)
                    .background(Theme.gradient, in: RoundedRectangle(cornerRadius: 8))
                    .foregroundStyle(.white)
                Text(player.current?.title ?? "")
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button { player.togglePlay() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title3)
                        .frame(width: 48, height: 48)
                        .background(Theme.accent, in: Circle())
                        .foregroundStyle(.white)
                }
                Button { player.stop() } label: {
                    Image(systemName: "xmark").font(.title3).frame(width: 44, height: 44)
                }
                .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(.ultraThinMaterial)
        .contentShape(Rectangle())
        .onTapGesture { player.isPresented = true }
    }
}
