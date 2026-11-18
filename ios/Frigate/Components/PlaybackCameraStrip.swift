import SwiftUI

/// The preview row of Frigate's recording view: the server's other cameras at the playhead's
/// moment, from their previews (as in Review's Motion tab), with a border on those that see motion
/// then. A tap makes that camera the one playing, at the same moment.
struct PlaybackCameraStrip: View {
    let conn: ServerConnection
    /// The camera playing; left out of the row.
    let current: String
    /// Wall-clock time at the playhead.
    let wallTime: Double
    let dayStart: Double
    let dayEnd: Double
    /// The thumbnails' height.
    var height: CGFloat = 52
    /// Names on a dark overlay (full screen) instead of on the page's background.
    var onVideo = false
    /// Room before the first and after the last camera, so the row scrolls to the edge.
    var margin: CGFloat = 16
    let onSelect: (String) -> Void

    @State private var previews: [String: PreviewScrubber] = [:]
    /// Motion buckets per camera, oldest first.
    @State private var activity: [String: [MotionActivity]] = [:]
    @State private var loaded = false
    private let bucket: Double = 30

    private var others: [CameraConfig] { conn.cameras.filter { $0.name != current } }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 8) {
                ForEach(others, id: \.name) { cam in thumb(cam) }
            }
            .padding(.horizontal, margin)
        }
        // Each moment once per second: previews have about one frame a second.
        .onChange(of: Int(wallTime), initial: true) { _, _ in show() }
        .task(id: "\(conn.server.id)|\(conn.cameras.map(\.name))|\(dayStart)") { await load() }
    }

    private func thumb(_ cam: CameraConfig) -> some View {
        let active = hasMotion(cam.name, at: wallTime)
        return Button { onSelect(cam.name) } label: {
            VStack(alignment: .leading, spacing: 3) {
                Color.black
                    .frame(width: height * min(max(cam.aspectRatio, 1), 2.4), height: height)
                    .overlay {
                        if let preview = previews[cam.name], preview.showing {
                            PreviewSurface(preview: preview)
                        } else if loaded {
                            Image(systemName: "video.slash").font(.system(size: 13)).foregroundStyle(.white.opacity(0.45))
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 7))
                    .overlay(RoundedRectangle(cornerRadius: 7)
                        .stroke(active ? Theme.motion : Theme.cardBorder, lineWidth: active ? 2 : 1))
                Text(cam.displayName)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(onVideo ? .white.opacity(0.85) : Theme.textSecondary)
                    .lineLimit(1)
                    .frame(width: height * min(max(cam.aspectRatio, 1), 2.4), alignment: .leading)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(cam.displayName))
        .accessibilityHint("Plays this camera at the same moment")
    }

    private func show() {
        for (_, p) in previews { p.show(wallTime) }
    }

    /// Motion in the bucket around `t` for one camera.
    private func hasMotion(_ cam: String, at t: Double) -> Bool {
        guard let list = activity[cam], !list.isEmpty else { return false }
        var lo = 0, hi = list.count - 1, found = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if list[mid].startTime <= t { found = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        guard found >= 0, t - list[found].startTime < bucket else { return false }
        return (list[found].motion ?? 0) > 0
    }

    private func load() async {
        guard let client = conn.client else { return }
        // One scrubber per camera, the one playing too: picked again, it is ready.
        var made: [String: PreviewScrubber] = [:]
        for cam in conn.cameras { made[cam.name] = previews[cam.name] ?? PreviewScrubber() }
        previews = made
        let names = conn.cameras.map(\.name)
        let start = dayStart - 3600, end = dayEnd
        await withTaskGroup(of: Void.self) { group in
            for (name, preview) in made {
                group.addTask { await preview.prepare(client: client, camera: name, start: start, end: end) }
            }
            group.addTask {
                let byCamera = await client.motionActivityByCamera(names, after: Date(timeIntervalSince1970: dayStart),
                                                                   before: Date(timeIntervalSince1970: end), scale: 30)
                await MainActor.run {
                    activity = byCamera.mapValues { $0.sorted { $0.startTime < $1.startTime } }
                }
            }
        }
        loaded = true
        show()
    }
}
