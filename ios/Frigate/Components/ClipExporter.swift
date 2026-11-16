import Photos
import SwiftUI

/// Downloads a recording range from Frigate and saves it to the photo library.
@Observable @MainActor
final class ClipExporter {
    enum State: Equatable {
        case idle
        case working(String)
        case done(String)
        case failed(String)
    }

    var state: State = .idle
    /// Of the download in progress: bytes received and expected (0 when unknown).
    var received: Int64 = 0
    var expected: Int64 = 0
    /// 0...1 when the size is known.
    var fraction: Double? { expected > 0 ? min(1, Double(received) / Double(expected)) : nil }
    var isWorking: Bool { if case .working = state { return true } else { return false } }

    /// Fetches `start...end` of `camera` as MP4. Returns the local file.
    func download(client: FrigateClient, camera: String, start: Double, end: Double) async throws -> URL {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        let name = "\(camera)_\(f.string(from: Date(timeIntervalSince1970: start))).mp4"
        return try await downloadFile(client: client, url: client.clipURL(camera: camera, start: start, end: end), fileName: name)
    }

    /// Downloads with the progress shown in the pill.
    func downloadFile(client: FrigateClient, url: URL, fileName: String) async throws -> URL {
        received = 0
        expected = 0
        defer { received = 0; expected = 0 }
        return try await client.download(url, fileName: fileName) { got, total in
            Task { @MainActor [weak self] in
                guard let self else { return }
                // Only forward: a late report must not move the bar back.
                // Forward only, and at most every 0.5 MB: each report redraws the pill.
                if got >= self.received + 500_000 || (total > 0 && got >= total) { self.received = got; self.expected = total }
            }
        }
    }

    func saveToPhotos(client: FrigateClient, camera: String, start: Double, end: Double) async {
        state = .working(L("Saving \(FrigateFormat.duration(end - start)) clip…"))
        do {
            guard await LocalSave.allowed() else {
                state = .failed(L("Allow Tender to add photos in Settings to save clips."))
                return
            }
            let file = try await download(client: client, camera: camera, start: start, end: end)
            if let done = try await LocalSave.keep(file) { state = .done(done) } else { state = .idle; return }
        } catch {
            state = .failed(error.localizedDescription)
        }
        try? await Task.sleep(for: .seconds(2.5))
        if case .working = state {} else { state = .idle }
    }
}

extension ClipExporter {
    /// Has Frigate export the span on the server, where it stays (Frigate's Exports page, and
    /// the Exports row in Explore).
    func saveToFrigate(client: FrigateClient, camera: String, start: Double, end: Double, name: String, caseID: String? = nil) async {
        state = .working(L("Saving to Frigate…"))
        do {
            try await client.startExport(camera: camera, start: start, end: end, name: name, caseID: caseID)
            state = .done(L("Saved to Frigate's exports"))
        } catch {
            state = .failed(error.localizedDescription)
        }
        try? await Task.sleep(for: .seconds(2.5))
        if case .working = state {} else { state = .idle }
    }

    /// Several cameras' exports at once, in one case (Frigate 0.18 and later).
    func saveToFrigate(client: FrigateClient, items: [FrigateClient.ExportItem], caseID: String?, newCaseName: String?) async {
        state = .working(L("Saving to Frigate…"))
        do {
            let queued = try await client.startExports(items, caseID: caseID, newCaseName: newCaseName)
            state = .done(queued == items.count ? L("Saved to Frigate's exports")
                                                : L("\(queued) of \(items.count) saved; the others have no recordings"))
        } catch {
            state = .failed(error.localizedDescription)
        }
        try? await Task.sleep(for: .seconds(2.5))
        if case .working = state {} else { state = .idle }
    }

    /// "Voortuin, 13 Nov 15:06": a name for an export made from the app.
    static func exportName(camera: String, displayName: String?, start: Double, label: String? = nil) -> String {
        let date = Date(timeIntervalSince1970: start)
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("d MMM \(FrigateFormat.hourTemplate):mm")
        let cam = displayName ?? FrigateFormat.prettyName(camera)
        if let label { return "\(FrigateFormat.labelName(label).capitalizedFirst) · \(cam), \(f.string(from: date))" }
        return "\(cam), \(f.string(from: date))"
    }
}

/// Small status pill shown while exporting and after it finishes; with a download's progress.
struct ExportToast: View {
    let state: ClipExporter.State
    var exporter: ClipExporter? = nil

    init(state: ClipExporter.State) { self.state = state }
    init(exporter: ClipExporter) { self.state = exporter.state; self.exporter = exporter }

    var body: some View {
        Group {
            switch state {
            case .idle: EmptyView()
            case .working(let text): pill(text, icon: nil)
            case .done(let text): pill(text, icon: "checkmark.circle.fill")
            case .failed(let text): pill(text, icon: "exclamationmark.triangle.fill")
            }
        }
        .animation(.easeOut(duration: 0.2), value: state)
    }

    private func pill(_ text: String, icon: String?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                // Kept from being rebuilt by every progress report: a rebuilt spinner restarts and flickers.
                if let icon { Image(systemName: icon) } else { Stable(key: 0) { ProgressView().tint(.white) }.stable() }
                Text(text).font(.system(size: 14, weight: .semibold)).lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                if icon == nil, let exporter, exporter.received > 0 {
                    // A fixed width, so the growing number does not push the text around.
                    Text(amount(exporter)).font(.system(size: 13, weight: .medium).monospacedDigit())
                        .foregroundStyle(.white.opacity(0.75))
                        .frame(width: 64, alignment: .trailing)
                        .contentTransition(.identity)
                        .transaction { $0.animation = nil }
                }
            }
            // A bar while downloading (Frigate first builds the clip, so it starts after a moment).
            if icon == nil, let exporter, exporter.received > 0 {
                if let f = exporter.fraction {
                    ProgressView(value: f).tint(Theme.accent).frame(width: 220)
                } else {
                    SweepingBar().frame(width: 220, height: 4)
                }
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 20))
        .padding(.top, 8)
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    private func amount(_ e: ClipExporter) -> String {
        let f = ByteCountFormatter()
        f.countStyle = .file
        f.allowedUnits = [.useMB]
        if let fraction = e.fraction { return "\(Int((fraction * 100).rounded()))%" }
        return f.string(fromByteCount: e.received)
    }
}

/// Where "save" puts a clip on this device: Photos on iPhone and iPad, a file on the Mac.
enum LocalSave {
    static let toFiles = ProcessInfo.processInfo.isMacCatalystApp
    static var title: String { toFiles ? L("Save as file…") : L("Save to Photos") }
    static var icon: String { toFiles ? "doc.badge.arrow.up" : "square.and.arrow.down" }

    /// Asks where to keep `file` (the Mac's save panel) and copies it there; false when cancelled.
    @MainActor
    static func saveFile(_ file: URL) async -> Bool {
        let root = UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow?.rootViewController }.first
        guard var top = root else { return false }
        while let next = top.presentedViewController, !next.isBeingDismissed { top = next }
        let picker = UIDocumentPickerViewController(forExporting: [file], asCopy: true)
        let delegate = Delegate()
        picker.delegate = delegate
        return await withCheckedContinuation { cont in
            delegate.done = { cont.resume(returning: $0) }
            objc_setAssociatedObject(picker, &Delegate.key, delegate, .OBJC_ASSOCIATION_RETAIN)
            top.present(picker, animated: true)
            // Should the panel not come up (top was busy dismissing), stop waiting instead of hanging.
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(2))
                if picker.presentingViewController == nil && picker.view.window == nil { delegate.cancel() }
            }
        }
    }

    private final class Delegate: NSObject, UIDocumentPickerDelegate {
        nonisolated(unsafe) static var key = 0
        var done: ((Bool) -> Void)?
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { finish(true) }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finish(false) }
        func cancel() { finish(false) }
        private func finish(_ ok: Bool) { done?(ok); done = nil }
    }

    /// Keeps a downloaded clip: Photos, or on the Mac a file of the user's choosing. The status for the pill.
    @MainActor
    static func keep(_ file: URL) async throws -> String? {
        defer { try? FileManager.default.removeItem(at: file) }
        if toFiles {
            return await saveFile(file) ? L("Saved") : nil
        }

        try await PHPhotoLibrary.shared().performChanges {
            PHAssetCreationRequest.creationRequestForAssetFromVideo(atFileURL: file)
        }
        return L("Saved to Photos")
    }

    /// Photos asks for permission first; files need none.
    static func allowed() async -> Bool {
        if toFiles { return true }
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        return status == .authorized || status == .limited
    }
}

/// A bar for a download of unknown size: a segment sweeping across, timed by the clock, so the
/// many redraws while bytes come in do not restart it (the system's bar flickers then).
struct SweepingBar: View {
    var body: some View {
        TimelineView(.animation) { context in
            GeometryReader { geo in
                let t = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.4) / 1.4
                let w = geo.size.width * 0.3
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.15))
                    Capsule().fill(Theme.accent).frame(width: w)
                        .offset(x: -w + (geo.size.width + w) * t)
                }
                .clipShape(Capsule())
            }
        }
    }
}
