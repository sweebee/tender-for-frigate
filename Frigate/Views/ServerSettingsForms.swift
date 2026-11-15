import SwiftUI

// The sections of a server's settings (snapshots, motion, review, …) as forms over Frigate's config.
// Each form reads its values from /api/config, collects changes, and saves them in one go as
// Frigate's own settings pages do: PUT /api/config/set with the changed keys, the update topic that
// applies them at once, and a restart only where Frigate needs one.

/// One value in a form.
enum ConfigValue: Equatable {
    case bool(Bool)
    case number(Double)
    case list([String])

    /// As JSON for Frigate: whole numbers as integers.
    var json: Any {
        switch self {
        case .bool(let b): return b
        case .number(let n): return n.rounded() == n ? Int(n) as Any : n as Any
        case .list(let l): return l
        }
    }
}

/// A setting in a form, at a dotted path under the section ("retain.default").
struct ConfigField: Identifiable {
    enum Kind {
        case toggle
        /// A whole or decimal number with a unit shown after it ("d", "s", "%").
        case number(unit: String, step: Double, min: Double, max: Double)
        /// A list picked from options (labels, zones).
        case labels(options: [String], display: (String) -> String)
    }
    let path: String
    let title: String
    let kind: Kind
    var footer: String? = nil
    /// Changing it takes a restart of Frigate.
    var restart = false
    /// What Frigate uses when the config does not set it.
    var defaultNumber: Double? = nil
    var id: String { path }
}

struct ConfigGroup: Identifiable {
    var title: String?
    var fields: [ConfigField]
    var id: String { (title ?? "") + fields.map(\.path).joined() }
}

/// Where Frigate applies a section at once, as its web interface sends it.
enum ConfigTopics {
    /// Sections whose global value is every camera's default: a global change goes to all cameras.
    static let cameraDefaults: Set<String> = ["detect", "objects", "motion", "record", "snapshots", "review", "audio", "notifications", "ffmpeg"]
    static let cameraSections: Set<String> = ["detect", "record", "snapshots", "motion", "objects", "review", "audio", "notifications",
                                              "live", "birdseye", "face_recognition", "lpr", "semantic_search", "mqtt", "onvif", "zones", "ui"]

    static func topic(section: String, camera: String?) -> String? {
        if let camera { return cameraSections.contains(section) ? "config/cameras/\(camera)/\(section)" : nil }
        return cameraDefaults.contains(section) ? "config/cameras/*/\(section)" : "config/\(section)"
    }
}

/// A section of the config as a form, for all cameras or one.
struct ConfigFormView: View {
    let serverID: UUID
    /// The config section, e.g. "snapshots".
    let section: String
    let camera: String?
    let title: String
    var intro: String? = nil
    /// Built once the config is loaded (options such as labels and zones come from it).
    let groups: ([String: Any]) -> [ConfigGroup]

    @Environment(AppModel.self) private var model
    @State private var config: [String: Any]?
    @State private var builtGroups: [ConfigGroup] = []
    @State private var original: [String: ConfigValue] = [:]
    @State private var values: [String: ConfigValue] = [:]
    @State private var role: String?
    @State private var saving = false
    @State private var errorMessage: String?

    private var conn: ServerConnection? { model.connection(for: serverID) }
    private var isAdmin: Bool { role == nil || role == "admin" }
    private var fields: [ConfigField] { builtGroups.flatMap(\.fields) }
    private var changed: [ConfigField] { fields.filter { values[$0.path] != original[$0.path] } }
    private var needsRestart: Bool { changed.contains(where: \.restart) }

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ScreenTitle(text: fullTitle, showsBack: true).padding(.top, 8)
                    if let intro { Text(intro).font(.system(size: 13)).foregroundStyle(Theme.textSecondary) }
                    if config == nil {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                    } else {
                        ForEach(builtGroups) { group in
                            if let t = group.title { SectionLabel(text: t).padding(.top, 10) }
                            VStack(spacing: 0) {
                                ForEach(Array(group.fields.enumerated()), id: \.element.id) { i, field in
                                    if i > 0 { Divider().overlay(Theme.cardBorder) }
                                    row(field)
                                }
                            }
                            .card(radius: 12)
                            ForEach(group.fields.compactMap(\.footer), id: \.self) { f in
                                Text(f).font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, changed.isEmpty ? 24 : 150)
            }
            if !changed.isEmpty {
                SaveBar(count: changed.count, restart: needsRestart, saving: saving,
                        undo: { values = original },
                        save: { restart in Task { await save(restart: restart) } })
                    .padding(.horizontal, 12).padding(.bottom, 12)
            }
        }
        .animation(.easeOut(duration: 0.2), value: changed.isEmpty)
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private var fullTitle: String {
        guard let camera else { return title }
        let name = conn?.config?.cameras[camera]?.displayName ?? FrigateFormat.prettyName(camera)
        return "\(title) · \(name)"
    }

    @ViewBuilder
    private func row(_ field: ConfigField) -> some View {
        let isChanged = values[field.path] != original[field.path]
        switch field.kind {
        case .toggle:
            Toggle(isOn: Binding(get: { if case .bool(let b) = values[field.path] { return b }; return false },
                                 set: { values[field.path] = .bool($0) })) {
                Text(field.title).font(.system(size: 15)).foregroundStyle(isChanged ? Theme.accent : Theme.text)
            }
            .tint(Theme.online)
            .disabled(!isAdmin)
            .padding(.horizontal, 14).padding(.vertical, 8)
        case .number(let unit, let step, let lo, let hi):
            let n: Double = { if case .number(let n) = values[field.path] { return n }; return 0 }()
            HStack {
                Text(field.title).font(.system(size: 15))
                Spacer(minLength: 8)
                NumberStepper(value: n, unit: unit, step: step, min: lo, max: hi, highlighted: isChanged, enabled: isAdmin) {
                    values[field.path] = .number($0)
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 8)
        case .labels(let options, let display):
            let selected: [String] = { if case .list(let l) = values[field.path] { return l }; return [] }()
            VStack(alignment: .leading, spacing: 8) {
                Text(field.title).font(.system(size: 15)).foregroundStyle(isChanged ? Theme.accent : Theme.text)
                FlowLayout(spacing: 6) {
                    ForEach(Array(Set(options + selected)).sorted { display($0) < display($1) }, id: \.self) { option in
                        let on = selected.contains(option)
                        Button {
                            values[field.path] = .list(on ? selected.filter { $0 != option } : selected + [option])
                        } label: {
                            Text(display(option))
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(on ? Theme.onColor : Theme.text)
                                .padding(.horizontal, 10).padding(.vertical, 5)
                                .background(on ? Theme.accent : Theme.control, in: Capsule())
                                .overlay(Capsule().stroke(on ? .clear : Theme.controlBorder, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                        .disabled(!isAdmin)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14).padding(.vertical, 10)
        }
    }

    // MARK: Loading and saving

    private var sectionNode: [String: Any]? {
        if let camera {
            return ((config?["cameras"] as? [String: Any])?[camera] as? [String: Any])?[section] as? [String: Any]
        }
        return config?[section] as? [String: Any]
    }

    private func load() async {
        guard let client = conn?.client else { return }
        async let cfg = try? client.rawConfig()
        async let r = client.role()
        guard let c = await cfg else { errorMessage = L("Could not load the settings."); return }
        role = await r
        config = c
        builtGroups = groups(c)
        var v: [String: ConfigValue] = [:]
        let node = sectionNode ?? [:]
        for field in builtGroups.flatMap(\.fields) {
            let raw = node.value(at: field.path)
            switch field.kind {
            case .toggle: v[field.path] = .bool((raw as? Bool) ?? false)
            case .number: v[field.path] = .number((raw as? NSNumber)?.doubleValue ?? field.defaultNumber ?? 0)
            case .labels: v[field.path] = .list((raw as? [String]) ?? [])
            }
        }
        original = v
        values = v
    }

    private func save(restart: Bool) async {
        guard let conn, let client = conn.client else { return }
        saving = true
        defer { saving = false }
        var node: [String: Any] = [:]
        for field in changed { if let v = values[field.path] { node.setNested(field.path, v.json) } }
        let data: [String: Any] = camera.map { ["cameras": [$0: [section: node]]] } ?? [section: node]
        do {
            try await client.setConfig(data, requiresRestart: needsRestart,
                                       updateTopic: needsRestart ? nil : ConfigTopics.topic(section: section, camera: camera))
            if restart { try await client.restart() }
            await load()
            await conn.refresh(reloadConfig: true)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Pieces

/// The bar at the bottom while a form has unsaved changes.
struct SaveBar: View {
    let count: Int
    var restart = false
    let saving: Bool
    let undo: () -> Void
    /// With `true`: save and restart Frigate.
    let save: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(count == 1 ? L("1 change") : L("\(count) changes")).font(.system(size: 14, weight: .semibold))
            Text(restart ? L("Frigate has to restart before this applies.") : L("Applies at once, no restart needed."))
                .font(.system(size: 12)).foregroundStyle(restart ? Theme.warning : Theme.textSecondary)
            HStack(spacing: 8) {
                barButton(L("Undo"), primary: false, action: undo)
                if restart { barButton(L("Save"), primary: false) { save(false) } }
                barButton(restart ? L("Save and restart") : L("Save"), primary: true) { save(restart) }
            }
        }
        .padding(14)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.controlBorder, lineWidth: 1))
        .shadow(color: .black.opacity(0.4), radius: 16, y: 6)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func barButton(_ title: String, primary: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                if primary && saving { ProgressView().tint(Theme.onColor) } else { Text(title).lineLimit(1).minimumScaleFactor(0.8) }
            }
            .font(.system(size: 14, weight: primary ? .bold : .semibold))
            .foregroundStyle(primary ? Theme.onColor : Theme.text)
            .frame(maxWidth: .infinity, minHeight: 36)
            .background(primary ? Theme.accent : Theme.control, in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .disabled(saving)
    }
}

/// − value +, for days, seconds and other whole numbers.
struct NumberStepper: View {
    let value: Double
    let unit: String
    var step: Double = 1
    var min: Double = 0
    var max: Double = .infinity
    var highlighted = false
    var enabled = true
    let set: (Double) -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button { set(Swift.max(min, value - step)) } label: {
                Image(systemName: "minus").frame(width: 36, height: 30).contentShape(Rectangle())
            }
            .disabled(value <= min)
            Text(label)
                .font(.system(size: 14, weight: .semibold).monospacedDigit())
                .foregroundStyle(highlighted ? Theme.accent : Theme.text)
                .frame(minWidth: 64)
            Button { set(Swift.min(max, value + step)) } label: {
                Image(systemName: "plus").frame(width: 36, height: 30).contentShape(Rectangle())
            }
            .disabled(value >= max)
        }
        .font(.system(size: 13, weight: .bold))
        .foregroundStyle(Theme.accent)
        .background(Theme.control, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.controlBorder, lineWidth: 1))
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }

    private var label: String {
        let n = value.rounded() == value ? String(Int(value)) : String(format: "%.2f", value)
        if unit == "d" { return RecordSettingsView.days(value) }
        // Scores are 0…1 in the config, shown as a percentage.
        if unit == "score" { return "\(Int((value * 100).rounded())) %" }
        return unit.isEmpty ? n : "\(n) \(unit)"
    }
}

/// Chips that wrap onto the next line.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += rowHeight + spacing; rowHeight = 0 }
            x += size.width + spacing
            widest = Swift.max(widest, x - spacing)
            rowHeight = Swift.max(rowHeight, size.height)
        }
        return CGSize(width: proposal.width ?? widest, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += rowHeight + spacing; rowHeight = 0 }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = Swift.max(rowHeight, size.height)
        }
    }
}

extension Dictionary where Key == String, Value == Any {
    /// Any value at a dotted path.
    func value(at path: String) -> Any? {
        var node: Any? = self
        for part in path.split(separator: ".") { node = (node as? [String: Any])?[String(part)] }
        return node
    }
}

// MARK: - The sections

/// The forms of a server's settings, for all cameras (camera nil) or one.
enum ServerSections {
    /// Labels to offer: those the server tracks anywhere, and the common ones.
    static func objectLabels(_ config: [String: Any]) -> [String] {
        var labels: Set<String> = ["person", "car", "bicycle", "motorcycle", "bus", "truck", "dog", "cat", "bird", "horse", "package"]
        for cam in (config["cameras"] as? [String: Any])?.values.compactMap({ $0 as? [String: Any] }) ?? [] {
            labels.formUnion(((cam["objects"] as? [String: Any])?["track"] as? [String]) ?? [])
        }
        labels.formUnion(((config["objects"] as? [String: Any])?["track"] as? [String]) ?? [])
        return Array(labels)
    }

    static func audioLabels(_ config: [String: Any]) -> [String] {
        var labels: Set<String> = ["bark", "fire_alarm", "speech", "yell"]
        for cam in (config["cameras"] as? [String: Any])?.values.compactMap({ $0 as? [String: Any] }) ?? [] {
            labels.formUnion(((cam["audio"] as? [String: Any])?["listen"] as? [String]) ?? [])
        }
        return Array(labels)
    }

    static func zones(_ config: [String: Any], camera: String?) -> [String] {
        guard let camera else { return [] }
        let cam = (config["cameras"] as? [String: Any])?[camera] as? [String: Any]
        return ((cam?["zones"] as? [String: Any])?.keys).map(Array.init) ?? []
    }

    private static let label: (String) -> String = { FrigateFormat.labelName($0).capitalizedFirst }
    private static let zoneName: (String) -> String = { FrigateFormat.prettyName($0) }

    static func snapshots(serverID: UUID, camera: String?) -> ConfigFormView {
        ConfigFormView(serverID: serverID, section: "snapshots", camera: camera, title: L("Snapshots"),
                       intro: L("The still pictures Frigate keeps of every tracked object, for Explore and notifications.")) { _ in
            [ConfigGroup(fields: [ConfigField(path: "enabled", title: L("Snapshots"), kind: .toggle)]),
             ConfigGroup(title: L("Keep"), fields: [
                ConfigField(path: "retain.default", title: L("Days"), kind: .number(unit: "d", step: 1, min: 0, max: 365))]),
             ConfigGroup(title: L("Picture"), fields: [
                ConfigField(path: "bounding_box", title: L("Box around the object"), kind: .toggle),
                ConfigField(path: "crop", title: L("Crop to the object"), kind: .toggle),
                ConfigField(path: "timestamp", title: L("Time on the picture"), kind: .toggle),
                ConfigField(path: "quality", title: L("Quality"), kind: .number(unit: "%", step: 5, min: 10, max: 100))])]
        }
    }

    static func detect(serverID: UUID, camera: String?) -> ConfigFormView {
        ConfigFormView(serverID: serverID, section: "detect", camera: camera, title: L("Object detection")) { _ in
            [ConfigGroup(fields: [ConfigField(path: "enabled", title: L("Object detection"), kind: .toggle)]),
             ConfigGroup(title: L("Speed"), fields: [
                ConfigField(path: "fps", title: L("Frames per second"), kind: .number(unit: "fps", step: 1, min: 1, max: 30),
                            footer: L("5 is usually enough; more takes more of the detector."), restart: camera != nil)])]
        }
    }

    static func objects(serverID: UUID, camera: String?) -> ConfigFormView {
        ConfigFormView(serverID: serverID, section: "objects", camera: camera, title: L("Objects"),
                       intro: L("What Frigate tracks, and per object how sure it must be and how large it must look.")) { config in
            let node: [String: Any]? = camera.map { ((config["cameras"] as? [String: Any])?[$0] as? [String: Any])?["objects"] as? [String: Any] }
                ?? config["objects"] as? [String: Any]
            let tracked = (node?["track"] as? [String]) ?? []
            var groups = [ConfigGroup(fields: [ConfigField(path: "track", title: L("Track"), kind: .labels(options: objectLabels(config), display: label))])]
            for l in tracked.sorted(by: { label($0) < label($1) }) {
                groups.append(ConfigGroup(title: label(l), fields: [
                    ConfigField(path: "filters.\(l).min_score", title: L("Minimum score"),
                                kind: .number(unit: "score", step: 0.05, min: 0, max: 1), defaultNumber: 0.5),
                    ConfigField(path: "filters.\(l).threshold", title: L("Score to count"),
                                kind: .number(unit: "score", step: 0.05, min: 0, max: 1), defaultNumber: 0.7),
                    ConfigField(path: "filters.\(l).min_area", title: L("Smallest size"),
                                kind: .number(unit: "px", step: 100, min: 0, max: 1_000_000), defaultNumber: 0),
                ]))
            }
            if !tracked.isEmpty {
                groups[0].fields[0].footer = L("Minimum score: detections below it are ignored. Score to count: the object's median score must reach it before it is tracked. Smallest size: in pixels of the detect resolution.")
            }
            return groups
        }
    }

    static func motion(serverID: UUID, camera: String?) -> ConfigFormView {
        ConfigFormView(serverID: serverID, section: "motion", camera: camera, title: L("Motion detection"),
                       intro: L("Motion decides where Frigate looks for objects and which recordings count as motion.")) { _ in
            [ConfigGroup(fields: [ConfigField(path: "enabled", title: L("Motion detection"), kind: .toggle)]),
             ConfigGroup(title: L("Sensitivity"), fields: [
                ConfigField(path: "threshold", title: L("Threshold"), kind: .number(unit: "", step: 5, min: 1, max: 255),
                            footer: L("Higher means a change in the picture has to be larger to count as motion.")),
                ConfigField(path: "contour_area", title: L("Smallest area"), kind: .number(unit: "", step: 5, min: 1, max: 200)),
                ConfigField(path: "improve_contrast", title: L("Improve contrast"), kind: .toggle)])]
        }
    }

    static func review(serverID: UUID, camera: String?) -> ConfigFormView {
        ConfigFormView(serverID: serverID, section: "review", camera: camera, title: L("Review"),
                       intro: L("Which objects become an alert and which a detection.")) { config in
            let options = objectLabels(config)
            var alerts = [ConfigField(path: "alerts.enabled", title: L("Alerts"), kind: .toggle),
                          ConfigField(path: "alerts.labels", title: L("Alerts for"), kind: .labels(options: options, display: label))]
            var detections = [ConfigField(path: "detections.enabled", title: L("Detections"), kind: .toggle)]
            let zones = zones(config, camera: camera)
            if !zones.isEmpty {
                alerts.append(ConfigField(path: "alerts.required_zones", title: L("Only in zones"),
                                          kind: .labels(options: zones, display: zoneName),
                                          footer: L("None selected: anywhere in the picture.")))
                detections.append(ConfigField(path: "detections.required_zones", title: L("Only in zones"),
                                              kind: .labels(options: zones, display: zoneName)))
            }
            return [ConfigGroup(title: L("Alerts"), fields: alerts), ConfigGroup(title: L("Detections"), fields: detections)]
        }
    }

    static func audio(serverID: UUID, camera: String?) -> ConfigFormView {
        ConfigFormView(serverID: serverID, section: "audio", camera: camera, title: L("Audio detection"),
                       intro: L("Frigate listens for sounds such as barking or a smoke alarm (needs audio in the camera's stream).")) { config in
            [ConfigGroup(fields: [ConfigField(path: "enabled", title: L("Audio detection"), kind: .toggle)]),
             ConfigGroup(fields: [
                ConfigField(path: "listen", title: L("Listen for"), kind: .labels(options: audioLabels(config), display: label)),
                ConfigField(path: "min_volume", title: L("Minimum volume"), kind: .number(unit: "", step: 100, min: 100, max: 5000))])]
        }
    }

    /// How a camera shows in the app and Frigate's dashboard.
    static func cameraUI(serverID: UUID, camera: String) -> ConfigFormView {
        ConfigFormView(serverID: serverID, section: "ui", camera: camera, title: L("Display")) { _ in
            [ConfigGroup(fields: [
                ConfigField(path: "dashboard", title: L("On the dashboard"), kind: .toggle,
                            footer: L("Off: the camera is left out of Live, in this app and in Frigate.")),
                ConfigField(path: "order", title: L("Position"), kind: .number(unit: "", step: 1, min: 0, max: 100),
                            footer: L("Lower comes first. Use Camera order on the server page to drag them instead."))])]
        }
    }

    static func notifications(serverID: UUID, camera: String?) -> ConfigFormView {
        ConfigFormView(serverID: serverID, section: "notifications", camera: camera, title: L("Notifications"),
                       intro: L("Frigate's own push notifications, to browsers and devices registered in its web interface.")) { _ in
            [ConfigGroup(fields: [
                ConfigField(path: "enabled", title: L("Notifications"), kind: .toggle),
                ConfigField(path: "cooldown", title: L("Wait between notifications"), kind: .number(unit: "s", step: 10, min: 0, max: 3600))])]
        }
    }

    /// Semantic search, face and plate recognition: global switches, some needing a restart.
    static func enrichments(serverID: UUID) -> some View {
        EnrichmentsView(serverID: serverID)
    }
}

/// Enrichments live in separate top-level sections; one screen with a form per section.
struct EnrichmentsView: View {
    let serverID: UUID
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                ScreenTitle(text: L("Enrichments"), showsBack: true).padding(.top, 8)
                Text("Extra recognition on top of object detection. Switching one on makes Frigate load a model, which takes memory and a restart.")
                    .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                VStack(spacing: 0) {
                    link(L("Semantic search"), "magnifyingglass", "semantic_search")
                    Divider().overlay(Theme.cardBorder)
                    link(L("Face recognition"), "face.smiling", "face_recognition")
                    Divider().overlay(Theme.cardBorder)
                    link(L("License plate recognition"), "123.rectangle", "lpr")
                }
                .card(radius: 12)
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
    }

    private func link(_ title: String, _ icon: String, _ section: String) -> some View {
        NavigationLink {
            ConfigFormView(serverID: serverID, section: section, camera: nil, title: title) { _ in
                [ConfigGroup(fields: [ConfigField(path: "enabled", title: title, kind: .toggle,
                                                  restart: section != "lpr")])]
            }
        } label: {
            SettingsRow(icon: icon, tint: Theme.accent, title: title)
        }
        .buttonStyle(.plain)
    }
}
