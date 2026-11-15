import SwiftUI

// Settings of a Frigate server itself, after the settings pages of Frigate 0.18's web interface:
// a server overview, per-camera switches and the recording retention. Changes are sent as Frigate's
// own pages send them (PUT /api/config/set) and apply without a restart where Frigate allows it.

/// A server's settings: status, cameras, global configuration and maintenance.
struct ServerSettingsView: View {
    let serverID: UUID
    @Environment(AppModel.self) private var model
    @State private var config: [String: Any]?
    @State private var role: String?
    @State private var loadError: String?
    @State private var confirmRestart = false
    @State private var restarting = false
    @State private var errorMessage: String?
    @State private var webInterface: ServersView.WebTarget?
    @State private var reordering = false
    @State private var editingConnection = false
    @Environment(\.dismiss) private var dismiss

    private var conn: ServerConnection? { model.connection(for: serverID) }

    /// "Semantic search, LPR" for the enrichments that are on.
    private var enrichmentSummary: String {
        let on: [(String, String)] = [("semantic_search", L("Search")), ("face_recognition", L("Faces")), ("lpr", L("Plates"))]
        let names = on.filter { ((config?[$0.0] as? [String: Any])?["enabled"] as? Bool) == true }.map(\.1)
        return names.isEmpty ? L("Off") : names.joined(separator: ", ")
    }
    /// Without authentication Frigate treats everyone as admin.
    private var isAdmin: Bool { role == nil || role == "admin" }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                ScreenTitle(text: conn?.server.name ?? L("Server"), showsBack: true).padding(.top, 8)
                if let conn {
                    statusCard(conn)
                    // The connection is the app's own setting for this server, and must be reachable when it is offline.
                    Button { editingConnection = true } label: {
                        SettingsRow(icon: "link", tint: Theme.accent, title: L("Connection"),
                                    value: ServerConfig.normalizedURL(conn.server.url).map { u in (u.host() ?? "") + (u.port.map { ":\($0)" } ?? "") } ?? conn.server.url)
                    }
                    .buttonStyle(.plain)
                    .card(radius: 12)
                    if !isAdmin {
                        Text("You are signed in as a viewer: settings can be seen but only an admin can change them.")
                            .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                            .padding(.vertical, 4)
                    }
                    if conn.version.map({ $0.compare("0.18", options: .numeric) != .orderedAscending }) == true {
                        SectionLabel(text: L("Profile")).padding(.top, 10)
                        NavigationLink { ProfilesView(serverID: serverID) } label: {
                            SettingsRow(icon: "person.2.crop.square.stack", tint: Theme.accent, title: L("Profiles"),
                                        value: conn.activeProfileTitle ?? (conn.profiles.isEmpty ? L("None") : L("None active")))
                        }
                        .buttonStyle(.plain)
                        .card(radius: 12)
                    }
                    cameraSection(conn)
                    SectionLabel(text: L("Global configuration")).padding(.top, 10)
                    VStack(spacing: 0) {
                        NavigationLink {
                            RecordSettingsView(serverID: serverID, camera: nil)
                        } label: {
                            SettingsRow(icon: "record.circle", tint: Theme.recording, title: L("Record"),
                                        value: retentionSummary(config?["record"] as? [String: Any]))
                        }
                        .buttonStyle(.plain)
                        SectionLinks(serverID: serverID, camera: nil, config: config)
                    }
                    .card(radius: 12)
                    SectionLabel(text: L("Enrichments")).padding(.top, 10)
                    NavigationLink { EnrichmentsView(serverID: serverID) } label: {
                        SettingsRow(icon: "sparkles", tint: Theme.accent, title: L("Enrichments"), value: enrichmentSummary)
                    }
                    .buttonStyle(.plain)
                    .card(radius: 12)
                    SectionLabel(text: L("System")).padding(.top, 10)
                    VStack(spacing: 0) {
                        if isAdmin {
                            NavigationLink { UsersView(serverID: serverID) } label: {
                                SettingsRow(icon: "person.crop.circle", tint: Theme.accent, title: L("Users"))
                            }
                            .buttonStyle(.plain)
                            Divider().overlay(Theme.cardBorder)
                        }
                        NavigationLink { SystemStatusView(serverID: serverID) } label: {
                            SettingsRow(icon: "gauge.with.dots.needle.33percent", tint: Theme.accent, title: L("System status"))
                        }
                        .buttonStyle(.plain)
                        Divider().overlay(Theme.cardBorder)
                        NavigationLink { MqttView(serverID: serverID) } label: {
                            SettingsRow(icon: "antenna.radiowaves.left.and.right", tint: Theme.accent, title: "MQTT",
                                        value: ((config?["mqtt"] as? [String: Any])?["enabled"] as? Bool ?? false) ? L("On") : L("Off"))
                        }
                        .buttonStyle(.plain)
                        Divider().overlay(Theme.cardBorder)
                        if let web = conn.client?.baseURL {
                            Button { webInterface = ServersView.WebTarget(url: web, title: conn.server.name) } label: {
                                SettingsRow(icon: "safari", tint: Theme.accent, title: L("Open the web interface"),
                                            value: L("All settings"), chevron: false)
                            }
                            .buttonStyle(.plain)
                        }
                        if isAdmin {
                            Divider().overlay(Theme.cardBorder)
                            Button { confirmRestart = true } label: {
                                HStack {
                                    Text(restarting ? L("Restarting…") : L("Restart Frigate"))
                                        .font(.system(size: 15)).foregroundStyle(Theme.destructive)
                                    Spacer()
                                    if restarting { ProgressView().controlSize(.small) }
                                }
                                .padding(.horizontal, 14).padding(.vertical, 12)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(restarting)
                        }
                    }
                    .card(radius: 12)
                } else {
                    EmptyStateView(systemImage: "server.rack", title: L("Server unavailable"), message: nil)
                }
                if let loadError {
                    Text(loadError).font(.system(size: 13)).foregroundStyle(Theme.destructive).padding(.top, 8)
                }
            }
            .padding(.horizontal, 16).readableWidth()
            .padding(.bottom, 24)
        }
        .refreshable { await load() }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .fullScreenCover(item: $webInterface) { WebInterfaceView(url: $0.url, title: $0.title) }
        .sheet(isPresented: $reordering) { CameraOrderView(serverID: serverID).environment(model) }
        .sheet(isPresented: $editingConnection) {
            if let server = conn?.server { NavigationStack { ConnectView(existing: server) }.environment(model) }
        }
        // Removed in the connection sheet: nothing left to show here.
        .onChange(of: conn == nil) { _, gone in if gone { dismiss() } }
        .alert("Restart Frigate?", isPresented: $confirmRestart) {
            Button("Cancel", role: .cancel) {}
            Button("Restart", role: .destructive) { Task { await restart() } }
        } message: {
            Text("Live view, recording and detection stop for about half a minute.")
        }
        .alert("Something went wrong", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    // MARK: Status

    private func statusCard(_ conn: ServerConnection) -> some View {
        let stats = conn.stats
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                StatusDot(color: conn.isOnline ? Theme.online : Theme.offline, size: 8)
                Text(conn.isOnline ? (conn.usingRemote ? L("Connected · remote") : L("Connected · local")) : L("Offline"))
                    .font(.system(size: 15, weight: .semibold))
            }
            Text(versionLine(stats)).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
            if let disk = stats?.recordingStorage, let total = disk.total, total > 0, let used = disk.used {
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text("Storage").font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                        Spacer()
                        Text("\(Self.size(used)) / \(Self.size(total))").font(.system(size: 12).monospacedDigit())
                            .foregroundStyle(Theme.textSecondary)
                    }
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Theme.control)
                            Capsule().fill(used / total > 0.9 ? Theme.warning : Theme.accent)
                                .frame(width: geo.size.width * min(1, used / total))
                        }
                    }
                    .frame(height: 6)
                }
            }
            HStack(spacing: 8) {
                if let speed = stats?.detectors.values.compactMap(\.inferenceSpeed).first {
                    statTile(L("Detector"), "\(Int(speed.rounded())) ms")
                }
                if let cpu = stats?.cpuPercent { statTile(L("CPU"), "\(Int(cpu.rounded())) %") }
                statTile(L("Cameras"), "\(conn.config?.cameras.count ?? 0)")
            }
        }
        .padding(14)
        .card(radius: 12)
    }

    private func statTile(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title.uppercased()).font(.system(size: 10, weight: .bold)).foregroundStyle(Theme.textSecondary)
            Text(value).font(.system(size: 15, weight: .semibold).monospacedDigit())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func versionLine(_ stats: FrigateStats?) -> String {
        var parts = [conn?.version.map { "Frigate " + $0.split(separator: "-").first.map(String.init)! } ?? "Frigate"]
        if let up = stats?.service?.uptime { parts.append(L("online for \(Self.uptime(up))")) }
        if let latest = stats?.service?.latestVersion, let current = conn?.version?.split(separator: "-").first,
           !latest.isEmpty, latest != "disabled", latest.compare(String(current), options: .numeric) == .orderedDescending {
            parts.append(L("\(latest) available"))
        }
        return parts.joined(separator: " · ")
    }

    /// "3 d 4 h", "9 h 12 min" or "12 min".
    static func uptime(_ t: Double) -> String {
        let f = DateComponentsFormatter()
        f.unitsStyle = .abbreviated
        f.allowedUnits = t >= 86400 ? [.day, .hour] : t >= 3600 ? [.hour, .minute] : [.minute]
        f.maximumUnitCount = 2
        return f.string(from: t) ?? ""
    }

    /// Frigate reports disk sizes in MB.
    static func size(_ mb: Double) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(mb * 1_048_576), countStyle: .binary)
    }

    // MARK: Cameras

    private func cameraSection(_ conn: ServerConnection) -> some View {
        let cams = (conn.config?.cameras.values.map { $0 } ?? [])
            .filter { $0.enabledInConfig != false }
            .sorted { ($0.ui?.order ?? 0, $0.name) < ($1.ui?.order ?? 0, $1.name) }
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                SectionLabel(text: L("Cameras"))
                Spacer()
                if isAdmin && cams.count > 1 {
                    Button { reordering = true } label: {
                        Image(systemName: "arrow.up.arrow.down").font(.system(size: 14, weight: .semibold))
                            .frame(width: 32, height: 28).contentShape(Rectangle())
                    }
                    .accessibilityLabel("Camera order")
                }
            }
            .padding(.top, 10)
            VStack(spacing: 0) {
                ForEach(Array(cams.enumerated()), id: \.element.name) { i, cam in
                    if i > 0 { Divider().overlay(Theme.cardBorder) }
                    NavigationLink {
                        CameraSettingsView(serverID: serverID, camera: cam.name)
                    } label: {
                        SettingsRow(icon: conn.isCameraEnabled(cam) ? "video" : "video.slash",
                                    tint: conn.isCameraEnabled(cam) ? Theme.accent : Theme.textSecondary,
                                    title: cam.displayName, value: cameraSummary(conn, cam))
                    }
                    .buttonStyle(.plain)
                }
            }
            .card(radius: 12)
        }
    }

    private func cameraSummary(_ conn: ServerConnection, _ cam: CameraConfig) -> String {
        if !conn.isCameraEnabled(cam) { return L("Off") }
        var off: [String] = []
        if !conn.isDetectEnabled(cam) { off.append(L("Detection off")) }
        if conn.isRecordSwitchedOff(cam) { off.append(L("Recording off")) }
        return off.joined(separator: ", ")
    }

    /// "30 days" for the longest kept recordings.
    private func retentionSummary(_ record: [String: Any]?) -> String {
        guard let record else { return "" }
        if record["enabled"] as? Bool == false { return L("Off") }
        let days = [record.number("continuous.days"), record.number("motion.days"),
                    record.number("alerts.retain.days"), record.number("detections.retain.days")].compactMap { $0 }.max()
        return days.map { RecordSettingsView.days($0) } ?? ""
    }

    // MARK: Actions

    private func load() async {
        guard let conn, let client = conn.client else { return }
        do {
            async let cfg = client.rawConfig()
            async let r = client.role()
            config = try await cfg
            role = await r
            loadError = nil
        } catch {
            loadError = error.localizedDescription
        }
        await conn.refresh()
    }

    private func restart() async {
        guard let client = conn?.client else { return }
        restarting = true
        defer { restarting = false }
        do {
            try await client.restart()
            // Back once it answers again.
            for _ in 0..<30 {
                try? await Task.sleep(for: .seconds(3))
                if (try? await client.version(timeout: 3)) != nil { break }
            }
            await conn?.refresh(reloadConfig: true)
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Camera

/// One camera's switches (as in its menus) and its own recording retention.
struct CameraSettingsView: View {
    let serverID: UUID
    let camera: String
    @Environment(AppModel.self) private var model

    private var conn: ServerConnection? { model.connection(for: serverID) }
    private var cam: CameraConfig? { conn?.config?.cameras[camera] }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                ScreenTitle(text: cam?.displayName ?? FrigateFormat.prettyName(camera), showsBack: true).padding(.top, 8)
                if let conn, let cam {
                    let on = conn.isCameraEnabled(cam)
                    VStack(spacing: 0) {
                        if cam.canSwitchOff {
                            toggleRow(L("Camera"), isOn: on, pending: conn.isSwitchPending(cam, "enabled")) { conn.setCameraEnabled(cam, $0) }
                        }
                        if on {
                            Divider().overlay(Theme.cardBorder)
                            toggleRow(L("Object detection"), isOn: conn.isDetectEnabled(cam), pending: conn.isSwitchPending(cam, "detect")) { conn.setDetectEnabled(cam, $0) }
                            if conn.canSwitchRecording(cam) {
                                Divider().overlay(Theme.cardBorder)
                                toggleRow(L("Record"), isOn: conn.isRecordEnabled(cam), pending: conn.isSwitchPending(cam, "recordings")) { conn.setRecordEnabled(cam, $0) }
                            }
                        }
                    }
                    .card(radius: 12)
                    if on && conn.hasNotifications(cam) {
                        VStack(spacing: 0) {
                            HStack {
                                Text("Notifications").font(.system(size: 15))
                                Spacer()
                                if let until = conn.notificationsPaused(cam) {
                                    Text(L("Paused until \(FrigateFormat.time(until))")).font(.system(size: 13)).foregroundStyle(Theme.warning)
                                }
                                Menu {
                                    NotificationPauseMenu(conn: conn, camera: cam)
                                } label: {
                                    Image(systemName: conn.notificationsPaused(cam) == nil ? "bell" : "bell.slash")
                                        .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.accent)
                                        .frame(width: 36, height: 30)
                                }
                            }
                            .padding(.horizontal, 14).padding(.vertical, 8)
                        }
                        .card(radius: 12)
                    }
                    Text("Switched at once, as in the camera's menu; Frigate keeps it after a restart.")
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                    SectionLabel(text: L("Configuration")).padding(.top, 10)
                    VStack(spacing: 0) {
                        NavigationLink {
                            RecordSettingsView(serverID: serverID, camera: camera)
                        } label: {
                            SettingsRow(icon: "record.circle", tint: Theme.recording, title: L("Record"), value: "")
                        }
                        .buttonStyle(.plain)
                        SectionLinks(serverID: serverID, camera: camera, config: nil)
                        Divider().overlay(Theme.cardBorder)
                        NavigationLink { ZonesMasksView(serverID: serverID, camera: camera) } label: {
                            SettingsRow(icon: "square.dashed", tint: Theme.accent, title: L("Zones and masks"))
                        }
                        .buttonStyle(.plain)
                        Divider().overlay(Theme.cardBorder)
                        NavigationLink { ServerSections.cameraUI(serverID: serverID, camera: camera) } label: {
                            SettingsRow(icon: "rectangle.grid.2x2", tint: Theme.accent, title: L("Display"),
                                        value: cam.ui?.dashboard == false ? L("Hidden") : "")
                        }
                        .buttonStyle(.plain)
                    }
                    .card(radius: 12)
                    Text("Settings here apply to this camera only and override the global configuration.")
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                }
            }
            .padding(.horizontal, 16).readableWidth()
            .padding(.bottom, 24)
        }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
    }

    private func toggleRow(_ title: String, isOn: Bool, pending: Bool = false, set: @escaping (Bool) -> Void) -> some View {
        Toggle(isOn: Binding(get: { isOn }, set: set)) {
            HStack(spacing: 8) {
                Text(title).font(.system(size: 15))
                if pending { ProgressView().controlSize(.small) }
            }
        }
        .tint(Theme.online)
        .disabled(pending)
        .padding(.horizontal, 14).padding(.vertical, 8)
    }
}

// MARK: - Recording

/// How long recordings are kept and how much is kept around events, for all cameras or one.
struct RecordSettingsView: View {
    let serverID: UUID
    /// nil: the global settings, which every camera follows unless it has its own.
    let camera: String?
    @Environment(AppModel.self) private var model
    @State private var original: [String: Double] = [:]
    @State private var values: [String: Double] = [:]
    @State private var enabled: Bool?
    @State private var originalEnabled: Bool?
    @State private var role: String?
    @State private var saving = false
    @State private var errorMessage: String?
    @State private var loaded = false

    private var conn: ServerConnection? { model.connection(for: serverID) }
    private var isAdmin: Bool { role == nil || role == "admin" }

    /// Keys under `record`, in display order per group.
    private static let keep = ["continuous.days", "motion.days", "alerts.retain.days", "detections.retain.days"]
    private static let alertsAround = ["alerts.pre_capture", "alerts.post_capture"]
    private static let detectionsAround = ["detections.pre_capture", "detections.post_capture"]

    private var changes: [String] {
        values.keys.filter { values[$0] != original[$0] }.sorted()
    }
    private var dirty: Bool { !changes.isEmpty || enabled != originalEnabled }

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ScreenTitle(text: title, showsBack: true).padding(.top, 8)
                    if camera == nil {
                        Text("For all cameras that have no recording settings of their own.")
                            .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                    }
                    if !loaded {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                    } else {
                        if let on = enabled {
                            VStack(spacing: 0) {
                                Toggle(isOn: Binding(get: { on }, set: { enabled = $0 })) {
                                    Text("Record").font(.system(size: 15))
                                }
                                .tint(Theme.online)
                                .disabled(!isAdmin)
                                .padding(.horizontal, 14).padding(.vertical, 8)
                            }
                            .card(radius: 12)
                        }
                        SectionLabel(text: L("Keep")).padding(.top, 10)
                        group([("continuous.days", L("Everything (continuous)")), ("motion.days", L("With motion")),
                               ("alerts.retain.days", L("Alerts")), ("detections.retain.days", L("Detections"))], unit: .days)
                        SectionLabel(text: L("Around an alert")).padding(.top, 10)
                        group([("alerts.pre_capture", L("Before")), ("alerts.post_capture", L("After"))], unit: .seconds)
                        SectionLabel(text: L("Around a detection")).padding(.top, 10)
                        group([("detections.pre_capture", L("Before")), ("detections.post_capture", L("After"))], unit: .seconds)
                    }
                }
                .padding(.horizontal, 16).readableWidth()
                .padding(.bottom, dirty ? 140 : 24)
            }
            if dirty { saveBar.padding(.horizontal, 12).readableWidth().padding(.bottom, 12) }
        }
        .animation(.easeOut(duration: 0.2), value: dirty)
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private var title: String {
        guard let camera else { return L("Record") }
        let name = conn?.config?.cameras[camera]?.displayName ?? FrigateFormat.prettyName(camera)
        return L("Recording · \(name)")
    }

    enum Unit { case days, seconds }

    private func group(_ rows: [(String, String)], unit: Unit) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.element.0) { i, row in
                if i > 0 { Divider().overlay(Theme.cardBorder) }
                HStack {
                    Text(row.1).font(.system(size: 15))
                    Spacer()
                    stepper(row.0, unit: unit)
                }
                .padding(.horizontal, 14).padding(.vertical, 8)
            }
        }
        .card(radius: 12)
    }

    private func stepper(_ key: String, unit: Unit) -> some View {
        let value = values[key] ?? 0
        let changed = values[key] != original[key]
        return HStack(spacing: 0) {
            Button { values[key] = max(0, value - 1) } label: {
                Image(systemName: "minus").frame(width: 36, height: 30).contentShape(Rectangle())
            }
            .disabled(value <= 0)
            Text(unit == .days ? Self.days(value) : "\(Int(value)) s")
                .font(.system(size: 14, weight: .semibold).monospacedDigit())
                .foregroundStyle(changed ? Theme.accent : Theme.text)
                .frame(minWidth: 64)
            Button { values[key] = value + 1 } label: {
                Image(systemName: "plus").frame(width: 36, height: 30).contentShape(Rectangle())
            }
        }
        .font(.system(size: 13, weight: .bold))
        .foregroundStyle(Theme.accent)
        .background(Theme.control, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.controlBorder, lineWidth: 1))
        .disabled(!isAdmin)
        .opacity(isAdmin ? 1 : 0.5)
    }

    static func days(_ d: Double) -> String {
        let n = d.rounded() == d ? String(Int(d)) : String(format: "%.1f", d)
        return d == 1 ? L("1 day") : L("\(n) days")
    }

    private var saveBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            let count = changes.count + (enabled != originalEnabled ? 1 : 0)
            Text(count == 1 ? L("1 change") : L("\(count) changes")).font(.system(size: 14, weight: .semibold))
            Text("Applies at once, no restart needed.").font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
            HStack(spacing: 8) {
                Button { values = original; enabled = originalEnabled } label: {
                    Text("Undo").font(.system(size: 14, weight: .semibold)).frame(maxWidth: .infinity, minHeight: 36)
                        .background(Theme.control, in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                Button { Task { await save() } } label: {
                    Group {
                        if saving { ProgressView().tint(Theme.onColor) } else { Text("Save") }
                    }
                    .font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.onColor)
                    .frame(maxWidth: .infinity, minHeight: 36)
                    .background(Theme.accent, in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .disabled(saving)
            }
        }
        .padding(14)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.controlBorder, lineWidth: 1))
        .shadow(color: .black.opacity(0.4), radius: 16, y: 6)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func load() async {
        guard let client = conn?.client else { return }
        async let cfg = try? client.rawConfig()
        async let r = client.role()
        let config = await cfg
        role = await r
        let record: [String: Any]?
        if let camera {
            record = ((config?["cameras"] as? [String: Any])?[camera] as? [String: Any])?["record"] as? [String: Any]
        } else {
            record = config?["record"] as? [String: Any]
        }
        var v: [String: Double] = [:]
        for key in Self.keep + Self.alertsAround + Self.detectionsAround {
            if let n = record?.number(key) { v[key] = n }
        }
        original = v
        values = v
        originalEnabled = record?["enabled"] as? Bool
        enabled = originalEnabled
        loaded = true
    }

    private func save() async {
        guard let conn, let client = conn.client else { return }
        saving = true
        defer { saving = false }
        var record: [String: Any] = [:]
        for key in changes { record.setNested(key, values[key] ?? 0) }
        if enabled != originalEnabled, let enabled { record["enabled"] = enabled }
        let data: [String: Any]
        let topic: String
        if let camera {
            data = ["cameras": [camera: ["record": record]]]
            topic = "config/cameras/\(camera)/record"
        } else {
            data = ["record": record]
            topic = "config/cameras/*/record"
        }
        do {
            try await client.setConfig(data, requiresRestart: false, updateTopic: topic)
            await load()
            await conn.refresh(reloadConfig: true)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Pieces

/// A row in a settings card: an icon, a title, a value on the right and a chevron.
struct SettingsRow: View {
    let icon: String
    let tint: Color
    let title: String
    var value: String = ""
    var chevron = true

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 14, weight: .semibold)).foregroundStyle(tint)
                .frame(width: 30, height: 30)
                .background(Theme.control, in: RoundedRectangle(cornerRadius: 8))
            Text(title).font(.system(size: 15)).foregroundStyle(Theme.text).lineLimit(1)
            Spacer(minLength: 8)
            Text(value).font(.system(size: 13)).foregroundStyle(Theme.textSecondary).lineLimit(1)
            if chevron {
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.textTertiary)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .contentShape(Rectangle())
    }
}

extension Dictionary where Key == String, Value == Any {
    /// A number at a dotted path ("alerts.retain.days").
    func number(_ path: String) -> Double? {
        var node: Any? = self
        for part in path.split(separator: ".") { node = (node as? [String: Any])?[String(part)] }
        return (node as? NSNumber)?.doubleValue
    }

    /// Sets a value at a dotted path, creating the levels in between.
    mutating func setNested(_ path: String, _ value: Any) {
        let parts = path.split(separator: ".").map(String.init)
        guard let first = parts.first else { return }
        if parts.count == 1 { self[first] = value; return }
        var child = self[first] as? [String: Any] ?? [:]
        child.setNested(parts.dropFirst().joined(separator: "."), value)
        self[first] = child
    }
}


/// The rows that open the config forms (snapshots, detection, …), under Recording.
struct SectionLinks: View {
    let serverID: UUID
    let camera: String?
    let config: [String: Any]?

    var body: some View {
        row("photo", L("Snapshots"), on("snapshots")) { ServerSections.snapshots(serverID: serverID, camera: camera) }
        row("person.crop.rectangle", L("Object detection"), on("detect")) { ServerSections.detect(serverID: serverID, camera: camera) }
        row("tag", L("Objects"), nil) { ServerSections.objects(serverID: serverID, camera: camera) }
        row("waveform.path", L("Motion detection"), on("motion")) { ServerSections.motion(serverID: serverID, camera: camera) }
        row("eye", L("Review"), nil) { ServerSections.review(serverID: serverID, camera: camera) }
        row("ear", L("Audio detection"), on("audio")) { ServerSections.audio(serverID: serverID, camera: camera) }
        row("bell", L("Notifications"), on("notifications")) { ServerSections.notifications(serverID: serverID, camera: camera) }
    }

    /// "On" or "Off" for a section's switch, when the config is at hand.
    private func on(_ section: String) -> String? {
        guard let enabled = (config?[section] as? [String: Any])?["enabled"] as? Bool else { return nil }
        return enabled ? L("On") : L("Off")
    }

    @ViewBuilder
    private func row<Destination: View>(_ icon: String, _ title: String, _ value: String?,
                                        @ViewBuilder destination: @escaping () -> Destination) -> some View {
        Divider().overlay(Theme.cardBorder)
        NavigationLink { destination() } label: {
            SettingsRow(icon: icon, tint: Theme.accent, title: title, value: value ?? "")
        }
        .buttonStyle(.plain)
    }
}
