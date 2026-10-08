import AVFoundation
import Foundation
import MediaPlayer
import MobileVLCKit
import UIKit

/// 再生を担当する部品（Android 版の PlaybackService にあたる）。
/// 画面を閉じても・画面を消しても再生を続け、ロック画面やイヤホンのボタンから操作できる
final class PlayerController: ObservableObject {
    static let shared = PlayerController()

    let player = VLCMediaPlayer()

    @Published private(set) var items: [MediaItem] = []
    @Published private(set) var index = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var timeMs: Int64 = 0
    @Published private(set) var lengthMs: Int64 = 0
    @Published private(set) var rate: Float = 1
    /// 再生画面を表示しているか
    @Published var isPresented = false
    /// 再生画面に一時的に出すお知らせ（「続きから再生」など）
    @Published var notice: String?

    var current: MediaItem? { items.indices.contains(index) ? items[index] : nil }
    var hasNext: Bool { index < items.count - 1 }

    private var timer: Timer?
    private var lastSave = Date.distantPast
    private var wasPlayingBeforeInterruption = false
    /// 今の曲・動画の終わりを処理済みか（同じ終わりで何度も次へ進まないように）
    private var endHandled = false

    private init() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        setupRemoteCommands()
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(onInterruption(_:)), name: AVAudioSession.interruptionNotification, object: nil)
        nc.addObserver(self, selector: #selector(onRouteChange(_:)), name: AVAudioSession.routeChangeNotification, object: nil)
        nc.addObserver(self, selector: #selector(onBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
    }

    // MARK: - 読み込みと再生

    /// 新しいリストで再生を始めて、再生画面を開く
    func play(_ list: [MediaItem], startAt start: Int) {
        guard list.indices.contains(start) else { return }
        saveNow()
        items = list
        index = start
        playCurrent(resume: true)
        isPresented = true
    }

    private func playCurrent(resume: Bool, from startMs: Int64? = nil) {
        guard let item = current else { return }
        let start = startMs ?? (resume ? ResumeStore.get(item) : 0)
        let media = VLCMedia(url: item.url)
        if start > 0 {
            media.addOption(":start-time=\(Double(start) / 1000)")
            notice = "続きから再生  \(formatTime(start))"
        }
        endHandled = false
        timeMs = start
        lengthMs = 0
        player.media = media
        player.rate = rate
        try? AVAudioSession.sharedInstance().setActive(true)
        player.play()
        HistoryStore.shared.add(item)
        addSubtitles(for: item)
        startTimer()
        updateNowPlaying()
    }

    private func addSubtitles(for item: MediaItem) {
        guard !item.isAudio else { return }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let subs = Subtitles.find(for: item.url).map(Subtitles.prepare)
            DispatchQueue.main.async {
                guard let self, self.current == item else { return }
                for (i, url) in subs.enumerated() {
                    self.player.addPlaybackSlave(url, type: .subtitle, enforce: i == 0)
                }
            }
        }
    }

    func togglePlay() { isPlaying ? pause() : resumePlay() }

    func resumePlay() {
        guard current != nil else { return }
        if player.state == .ended || player.state == .stopped {
            playCurrent(resume: false)
        } else {
            try? AVAudioSession.sharedInstance().setActive(true)
            player.play()
        }
        refresh()
    }

    func pause() {
        player.pause()
        saveNow()
        refresh()
    }

    func seek(to ms: Int64) {
        let max = lengthMs > 0 ? lengthMs : Int64(Int32.max)
        let t = min(Swift.max(ms, 0), max)
        player.time = VLCTime(int: Int32(clamping: t))
        timeMs = t
        updateNowPlaying()
    }

    func seek(by delta: Int64) { seek(to: timeMs + delta) }

    func setRate(_ r: Float) {
        rate = r
        player.rate = r
        updateNowPlaying()
    }

    func next() {
        guard hasNext else { return }
        saveNow()
        index += 1
        playCurrent(resume: true)
    }

    func previous() {
        if timeMs > 3000 || index == 0 {
            seek(to: 0)
            return
        }
        saveNow()
        index -= 1
        playCurrent(resume: true)
    }

    func playAt(_ i: Int) {
        guard items.indices.contains(i) else { return }
        saveNow()
        index = i
        playCurrent(resume: true)
    }

    /// 再生をやめて画面を閉じる
    func stop() {
        saveNow()
        player.stop()
        timer?.invalidate()
        timer = nil
        items = []
        index = 0
        isPlaying = false
        isPresented = false
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - 状態の更新（VLC の状態を 0.5 秒ごとに見る）

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in self?.refresh() }
    }

    private func refresh() {
        let playing = player.isPlaying
        if playing != isPlaying {
            isPlaying = playing
            updateNowPlaying()
        }
        let t = Int64(player.time.intValue)
        if t >= 0 { timeMs = t }
        let media: VLCMedia? = player.media
        if let len = media?.length.intValue, len > 0, Int64(len) != lengthMs {
            lengthMs = Int64(len)
            updateNowPlaying()
        }
        if playing, Date().timeIntervalSince(lastSave) > 5 { saveNow() }

        switch player.state {
        case .ended where !endHandled:
            endHandled = true
            if let item = current { ResumeStore.clear(item) }
            if hasNext { next() } else { isPlaying = false; updateNowPlaying() }
        case .error where !endHandled:
            endHandled = true
            notice = "再生できませんでした：\(current?.title ?? "")"
            if hasNext { next() }
        default:
            break
        }
    }

    func saveNow() {
        lastSave = Date()
        guard let item = current, lengthMs > 0 else { return }
        // 普通の長さの曲は続きから再生しない（10分以上の音声＝ラジオや朗読などだけ）
        if item.isAudio && lengthMs < 10 * 60_000 { return }
        ResumeStore.save(item, position: timeMs, length: lengthMs)
    }

    // MARK: - 字幕・音声トラック

    struct Track: Identifiable, Hashable {
        var id: Int32
        var name: String
    }

    private func tracks(names: [Any]?, indexes: [Any]?) -> [Track] {
        let n = names as? [String] ?? []
        let ids = (indexes as? [NSNumber] ?? []).map(\.int32Value)
        return zip(ids, n).map { Track(id: $0, name: $0 == -1 ? "オフ" : $1) }
    }

    var subtitleTracks: [Track] { tracks(names: player.videoSubTitlesNames, indexes: player.videoSubTitlesIndexes) }
    var audioTracks: [Track] { tracks(names: player.audioTrackNames, indexes: player.audioTrackIndexes) }
    var currentSubtitle: Int32 { player.currentVideoSubTitleIndex }
    var currentAudio: Int32 { player.currentAudioTrackIndex }

    func selectSubtitle(_ id: Int32) { player.currentVideoSubTitleIndex = id; objectWillChange.send() }
    func selectAudio(_ id: Int32) { player.currentAudioTrackIndex = id; objectWillChange.send() }

    /// 字幕のずれ（秒。＋で遅らせる）
    var subtitleDelay: Double { Double(player.currentVideoSubTitleDelay) / 1_000_000 }
    func shiftSubtitle(by seconds: Double) {
        player.currentVideoSubTitleDelay += Int(seconds * 1_000_000)
        notice = String(format: "字幕のタイミング %+.1f 秒", subtitleDelay)
        objectWillChange.send()
    }

    func resetSubtitleDelay() {
        player.currentVideoSubTitleDelay = 0
        notice = "字幕のタイミングを元に戻しました"
        objectWillChange.send()
    }

    // MARK: - 電話・イヤホン

    @objc private func onInterruption(_ n: Notification) {
        guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        DispatchQueue.main.async {
            switch type {
            case .began:
                self.wasPlayingBeforeInterruption = self.isPlaying
                self.pause()
            case .ended:
                let opts = (n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map { AVAudioSession.InterruptionOptions(rawValue: $0) } ?? []
                // 通話が終わって再開してよいと言われたときだけ、もともと再生していたものを再開する
                if self.wasPlayingBeforeInterruption && opts.contains(.shouldResume) { self.resumePlay() }
                self.wasPlayingBeforeInterruption = false
            @unknown default:
                break
            }
        }
    }

    /// イヤホンが抜けたら一時停止
    @objc private func onRouteChange(_ n: Notification) {
        guard let raw = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
        DispatchQueue.main.async { self.pause() }
    }

    @objc private func onBackground() { saveNow() }

    // MARK: - ロック画面・コントロールセンター

    private func setupRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in self?.resumePlay(); return .success }
        c.pauseCommand.addTarget { [weak self] _ in self?.pause(); return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in self?.togglePlay(); return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in self?.next(); return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in self?.previous(); return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seek(to: Int64(e.positionTime * 1000))
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let item = current else { return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: item.title,
            MPMediaItemPropertyPlaybackDuration: Double(lengthMs) / 1000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(timeMs) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(rate) : 0,
        ]
    }
}
