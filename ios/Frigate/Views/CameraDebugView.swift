import SwiftUI

/// Frigate's Debug view of one camera: the detect stream as Frigate sees it, with what it draws
/// on it (boxes, zones, masks, motion, regions, paths) switched on or off, and the objects it
/// tracks right now.
struct CameraDebugView: View {
    let serverID: UUID
    let camera: String
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var image: UIImage?
    @State private var failed = false
    enum Tab: Hashable { case debugging, objects }
    @State private var tab: Tab = .debugging
    @AppStorage("debug.bbox") private var bbox = true
    @AppStorage("debug.timestamp") private var timestamp = false
    @AppStorage("debug.zones") private var zones = false
    @AppStorage("debug.mask") private var mask = false
    @AppStorage("debug.motion") private var motion = true
    @AppStorage("debug.regions") private var regions = false
    @AppStorage("debug.paths") private var paths = false

    private var conn: ServerConnection? { model.connection(for: serverID) }
    private var config: CameraConfig? { conn?.config?.cameras[camera] }
    private var objects: [LiveObject] {
        (conn?.activity[camera]?.objects ?? []).sorted { ($0.stationary ? 1 : 0, $0.label) < ($1.stationary ? 1 : 0, $1.label) }
    }

    /// Frigate's debug image options, as its Debug page sends them.
    private var query: [String: String?] {
        func v(_ on: Bool) -> String { on ? "1" : "0" }
        return ["bbox": v(bbox), "timestamp": v(timestamp), "zones": v(zones), "mask": v(mask),
                "motion": v(motion), "regions": v(regions), "paths": v(paths), "height": "720"]
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                ScreenTitle(text: L("Debug"), showsBack: true)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            if sizeClass == .regular {
                // iPad and Mac: the picture beside the options.
                HStack(alignment: .top, spacing: 16) {
                    picture
                    ScrollView { options }.frame(width: 340)
                }
                .padding(16)
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        picture.padding(.horizontal, 16).padding(.top, 8)
                        options.padding(.horizontal, 16)
                    }
                    .padding(.bottom, 24)
                }
            }
        }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        .task(id: query.description) { await refreshLoop() }
    }

    private var picture: some View {
        Color.black
            .aspectRatio(config?.aspectRatio ?? 16 / 9, contentMode: .fit)
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
                } else if failed {
                    Label("No picture", systemImage: "video.slash").foregroundStyle(Theme.textSecondary)
                } else {
                    ProgressView()
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .frame(maxWidth: .infinity, alignment: .top)
    }

    private var options: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(config?.displayName ?? FrigateFormat.prettyName(camera))
                .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 12)
            SegmentedBar(options: [Tab.debugging, .objects], selection: $tab) {
                $0 == .debugging ? L("Debugging") : L("Object List")
            }
            if tab == .debugging {
                VStack(spacing: 0) {
                    toggle(L("Bounding boxes"), L("Show bounding boxes around tracked objects"), $bbox, first: true)
                    toggle(L("Timestamp"), L("Overlay a timestamp on the image"), $timestamp)
                    toggle(L("Zones"), L("Show an outline of any defined zones"), $zones)
                    toggle(L("Motion masks"), L("Show motion mask polygons"), $mask)
                    toggle(L("Motion boxes"), L("Show boxes around areas where motion is detected"), $motion)
                    toggle(L("Regions"), L("Show a box of the region of interest sent to the object detector"), $regions)
                    toggle(L("Paths"), L("Show significant points of the tracked object's path"), $paths)
                }
                .card(radius: 12)
            } else {
                objectList
            }
        }
    }

    private func toggle(_ title: String, _ detail: String, _ on: Binding<Bool>, first: Bool = false) -> some View {
        Toggle(isOn: on) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15)).foregroundStyle(Theme.text)
                Text(detail).font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
            }
        }
        .toggleStyle(RowSwitchStyle())
        .padding(.horizontal, 14).padding(.vertical, 10)
        .overlay(alignment: .top) { if !first { Rectangle().fill(Theme.cardBorder).frame(height: 1) } }
    }

    @ViewBuilder
    private var objectList: some View {
        if objects.isEmpty {
            Text("No objects").font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity, minHeight: 80)
                .card(radius: 12)
        } else {
            VStack(spacing: 8) {
                ForEach(objects) { obj in
                    HStack(spacing: 10) {
                        LabelIcon(label: obj.label)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(FrigateFormat.labelName(obj.label).capitalizedFirst).font(.system(size: 15, weight: .semibold))
                            if let sub = obj.subLabel, !sub.isEmpty {
                                Text(sub).font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                            }
                            if obj.stationary {
                                Text("Stationary").font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                            }
                        }
                        Spacer(minLength: 8)
                        stat(L("Score"), obj.score.map { String(format: "%.1f%%", $0 * 100) } ?? "–")
                        stat(L("Ratio"), obj.ratio.map { String(format: "%.2f", $0) } ?? "–")
                        stat(L("Area"), obj.area.map { String(Int($0)) } ?? "–")
                    }
                    .padding(.horizontal, 12).padding(.vertical, 10)
                    .card(radius: 12)
                    .opacity(obj.stationary ? 0.7 : 1)
                }
            }
        }
    }

    private func stat(_ title: String, _ value: String) -> some View {
        VStack(alignment: .trailing, spacing: 1) {
            Text(title).font(.system(size: 10, weight: .semibold)).foregroundStyle(Theme.textSecondary)
            Text(value).font(.system(size: 13, weight: .semibold).monospacedDigit())
        }
        .frame(minWidth: 44, alignment: .trailing)
    }

    /// Fetches a fresh picture as soon as the last one is in, at most five a second, as
    /// Frigate's own debug image does; a second's pause after an error.
    private func refreshLoop() async {
        guard let client = conn?.client else { return }
        while !Task.isCancelled {
            let started = Date()
            if let data = try? await client.data("api/\(camera)/latest.jpg", query: query, timeout: 10),
               let img = UIImage(data: data) {
                image = img
                failed = false
            } else if !Task.isCancelled {
                failed = image == nil
                try? await Task.sleep(for: .seconds(1))
                continue
            }
            let wait = 0.2 - Date().timeIntervalSince(started)
            if wait > 0 { try? await Task.sleep(for: .seconds(wait)) }
        }
    }
}
