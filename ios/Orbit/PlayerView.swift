import MobileVLCKit
import SwiftUI
import UIKit

/// VLC が映像を描く面
struct VideoSurface: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .black
        PlayerController.shared.player.drawable = v
        return v
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// 動画の再生画面（Android 版と同じく、中央に大きな再生ボタン・下にシークバーと道具ボタン）
struct PlayerView: View {
    @ObservedObject private var pc = PlayerController.shared
    @State private var showControls = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrubbing = false
    @State private var scrubMs: Double = 0
    @State private var info: String?
    @State private var infoTask: Task<Void, Never>?
    @State private var locked = false

    private let skipMs: Int64 = 10_000

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()
                if pc.current?.isAudio == true {
                    AudioArtwork(title: pc.current?.title ?? "")
                } else {
                    VideoSurface().ignoresSafeArea()
                }

                // タップ：操作パネルの表示/非表示。ダブルタップ：左で戻る・右で進む・真ん中で再生/一時停止
                Color.clear
                    .contentShape(Rectangle())
                    .ignoresSafeArea()
                    .gesture(
                        SpatialTapGesture(count: 2).onEnded { v in doubleTap(x: v.location.x, width: geo.size.width) }
                            .exclusively(before: SpatialTapGesture(count: 1).onEnded { _ in toggleControls() })
                    )

                if showControls && !locked { controls }
                if locked { lockOverlay }

                if let info {
                    Text(info)
                        .font(.title3.weight(.semibold))
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 12))
                        .foregroundStyle(.white)
                        .transition(.opacity)
                }
            }
        }
        .statusBarHidden(!showControls || locked)
        .persistentSystemOverlays(showControls && !locked ? .automatic : .hidden)
        .preferredColorScheme(.dark)
        .onAppear { scheduleHide() }
        .onChange(of: pc.notice) { n in
            if let n {
                showInfo(n)
                pc.notice = nil
            }
        }
        .onChange(of: pc.isPlaying) { playing in
            if playing { scheduleHide() } else { hideTask?.cancel() }
        }
    }

    // MARK: - 操作パネル

    private var controls: some View {
        ZStack {
            // 上下を少し暗くしてボタンを見やすく
            VStack {
                LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom).frame(height: 140)
                Spacer()
                LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom).frame(height: 200)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: 0) {
                topBar
                Spacer()
                bottomBar
            }

            centerButtons
        }
        .foregroundStyle(.white)
        .transition(.opacity)
    }

    private var topBar: some View {
        HStack(spacing: 4) {
            IconButton(system: "chevron.down") {
                pc.isPresented = false
            }
            Text(pc.current?.title ?? "")
                .font(.headline)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            moreMenu
        }
        .padding(.horizontal, 8)
        .padding(.top, 4)
    }

    private var centerButtons: some View {
        HStack(spacing: 36) {
            RoundButton(system: "backward.end.fill", size: 64) { pc.previous(); scheduleHide() }
            RoundButton(system: pc.isPlaying ? "pause.fill" : "play.fill", size: 84) { pc.togglePlay(); scheduleHide() }
            RoundButton(system: "forward.end.fill", size: 64) { pc.next(); scheduleHide() }
                .opacity(pc.hasNext ? 1 : 0.4)
        }
    }

    private var bottomBar: some View {
        VStack(spacing: 4) {
            HStack(spacing: 10) {
                Text(formatTime(scrubbing ? Int64(scrubMs) : pc.timeMs))
                    .font(.footnote.monospacedDigit())
                    .frame(minWidth: 56)
                Slider(
                    value: Binding(
                        get: { scrubbing ? scrubMs : Double(pc.timeMs) },
                        set: { scrubMs = $0 }
                    ),
                    in: 0...Double(max(pc.lengthMs, 1)),
                    onEditingChanged: { editing in
                        if editing {
                            scrubbing = true
                            scrubMs = Double(pc.timeMs)
                            hideTask?.cancel()
                        } else {
                            pc.seek(to: Int64(scrubMs))
                            scrubbing = false
                            scheduleHide()
                        }
                    }
                )
                Text(formatTime(pc.lengthMs))
                    .font(.footnote.monospacedDigit())
                    .frame(minWidth: 56)
            }
            HStack {
                ToolButton(system: "lock.open") {
                    locked = true
                    showInfo("画面をロックしました")
                }
                subtitleMenu
                audioMenu
                speedMenu
                ToolButton(system: "gobackward.10") { pc.seek(by: -skipMs); showInfo("⏪  10秒") }
                ToolButton(system: "goforward.10") { pc.seek(by: skipMs); showInfo("10秒  ⏩") }
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 6)
    }

    // MARK: - メニュー（字幕・音声・速度・その他）

    private var subtitleMenu: some View {
        Menu {
            Section("字幕") {
                ForEach(pc.subtitleTracks) { t in
                    Button { pc.selectSubtitle(t.id) } label: {
                        if t.id == pc.currentSubtitle { Label(t.name, systemImage: "checkmark") } else { Text(t.name) }
                    }
                }
            }
            Section(String(format: "タイミング（現在 %+.1f 秒）", pc.subtitleDelay)) {
                Button { pc.shiftSubtitle(by: -0.5) } label: { Label("0.5 秒早める", systemImage: "minus.circle") }
                Button { pc.shiftSubtitle(by: 0.5) } label: { Label("0.5 秒遅らせる", systemImage: "plus.circle") }
                Button { pc.resetSubtitleDelay() } label: { Label("元に戻す", systemImage: "arrow.uturn.backward") }
            }
        } label: {
            ToolIcon(system: "captions.bubble")
        }
    }

    private var audioMenu: some View {
        Menu {
            Section("音声トラック") {
                ForEach(pc.audioTracks) { t in
                    Button { pc.selectAudio(t.id) } label: {
                        if t.id == pc.currentAudio { Label(t.name, systemImage: "checkmark") } else { Text(t.name) }
                    }
                }
            }
        } label: {
            ToolIcon(system: "waveform")
        }
    }

    private var speedMenu: some View {
        Menu {
            Section("再生速度") {
                ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0] as [Float], id: \.self) { r in
                    Button { pc.setRate(r); showInfo("速度 \(formatRate(r))") } label: {
                        if r == pc.rate { Label(formatRate(r), systemImage: "checkmark") } else { Text(formatRate(r)) }
                    }
                }
            }
        } label: {
            Text(formatRate(pc.rate))
                .font(.subheadline.bold())
                .frame(maxWidth: .infinity, minHeight: 52)
                .contentShape(Rectangle())
        }
    }

    private var moreMenu: some View {
        Menu {
            Section(pc.current?.title ?? "") {
                Button { pc.seek(to: 0); showInfo("最初から再生") } label: { Label("最初から再生", systemImage: "backward.end") }
                if pc.items.count > 1 {
                    Menu {
                        ForEach(Array(pc.items.enumerated()), id: \.element.id) { i, item in
                            Button { pc.playAt(i) } label: {
                                if i == pc.index { Label(item.title, systemImage: "play.fill") } else { Text(item.title) }
                            }
                        }
                    } label: { Label("再生キュー（\(pc.index + 1) / \(pc.items.count)）", systemImage: "list.bullet") }
                }
                Button(role: .destructive) { pc.stop() } label: { Label("再生を終了", systemImage: "xmark.circle") }
            }
        } label: {
            ToolIcon(system: "ellipsis.circle").frame(width: 48)
        }
    }

    // MARK: - ロック中

    private var lockOverlay: some View {
        VStack {
            Spacer()
            HStack {
                RoundButton(system: "lock.fill", size: 60) {
                    locked = false
                    showControls = true
                    scheduleHide()
                }
                .padding(24)
                Spacer()
            }
            Spacer()
        }
    }

    // MARK: - 動作

    private func toggleControls() {
        guard !locked else { return }
        withAnimation(.easeInOut(duration: 0.2)) { showControls.toggle() }
        if showControls { scheduleHide() }
    }

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled, pc.isPlaying, !scrubbing else { return }
            await MainActor.run { withAnimation(.easeInOut(duration: 0.25)) { showControls = false } }
        }
    }

    private func doubleTap(x: CGFloat, width: CGFloat) {
        guard !locked else { return }
        if x < width / 3 {
            pc.seek(by: -skipMs)
            showInfo("⏪  10秒")
        } else if x > width * 2 / 3 {
            pc.seek(by: skipMs)
            showInfo("10秒  ⏩")
        } else {
            pc.togglePlay()
        }
    }

    private func showInfo(_ text: String) {
        withAnimation { info = text }
        infoTask?.cancel()
        infoTask = Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run { withAnimation { info = nil } }
        }
    }
}

// MARK: - 部品

/// 音楽を再生しているときの真ん中の絵
struct AudioArtwork: View {
    let title: String

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "music.note")
                .font(.system(size: 80))
                .foregroundStyle(.white)
                .frame(width: 220, height: 220)
                .background(Theme.gradient, in: RoundedRectangle(cornerRadius: 28))
            Text(title).font(.title3.bold()).foregroundStyle(.white).lineLimit(2).padding(.horizontal, 32)
        }
    }
}

/// 上のバーのボタン（48pt 四方）
struct IconButton: View {
    let system: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.title2.weight(.semibold))
                .frame(width: 48, height: 48)
                .contentShape(Rectangle())
        }
    }
}

/// 中央の丸いボタン
struct RoundButton: View {
    let system: String
    let size: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.system(size: size * 0.36, weight: .bold))
                .frame(width: size, height: size)
                .background(.black.opacity(0.45), in: Circle())
                .foregroundStyle(.white)
        }
    }
}

/// 下の道具ボタン（画面の幅に合わせて均等に並ぶ）
struct ToolButton: View {
    let system: String
    let action: () -> Void

    var body: some View {
        Button(action: action) { ToolIcon(system: system) }
    }
}

struct ToolIcon: View {
    let system: String

    var body: some View {
        Image(systemName: system)
            .font(.title3)
            .frame(maxWidth: .infinity, minHeight: 52)
            .contentShape(Rectangle())
    }
}
