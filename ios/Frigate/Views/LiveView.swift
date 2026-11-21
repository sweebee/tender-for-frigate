import SwiftUI

struct LiveView: View {
    @Environment(AppModel.self) private var model
    /// The chosen camera group, `Self.nowActive`, or nil for all cameras.
    @State private var group: String? = nil
    /// The "Now active" chip: cameras where an object moves, across all servers.
    private static let nowActive = "\u{0}active"
    /// When each camera ("<server id>|<camera>") last had a moving object, so it stays a while
    /// after it goes quiet (as Birdseye does) instead of tiles jumping in and out.
    @State private var lastActive: [String: Date] = [:]
    @State private var clock = Date()
    /// Signed-in role per server; nil (no authentication) counts as admin.
    @State private var roles: [UUID: String] = [:]
    @State private var editingGroup: CameraGroupTarget?
    @State private var deletingGroup: CameraGroupTarget?
    /// The group being deleted on the server: its chip shows a spinner.
    @State private var busyGroup: String?
    @State private var groupError: String?

    @Environment(\.horizontalSizeClass) private var sizeClass
    /// Bigger tiles: one column on iPhone, fewer and wider tiles on iPad.
    @AppStorage("liveLargeTiles") private var largeTiles = false
    /// Columns on wide screens; the choices fit the device (a Mac window has room for more).
    @AppStorage("liveWideColumns") private var storedColumns = 0
    private static let columnChoices: [Int] = ProcessInfo.processInfo.isMacCatalystApp ? [3, 4, 5, 6] : [2, 3, 4]
    private var wideColumns: Int {
        Self.columnChoices.contains(storedColumns) ? storedColumns : Self.columnChoices[1]
    }

    /// Two columns on iPhone (or one with large tiles); on iPad and Mac as many as fit.
    private var columns: [GridItem] {
        if sizeClass == .regular {
            return Array(repeating: GridItem(.flexible(), spacing: 12), count: wideColumns)
        }
        return largeTiles
            ? [GridItem(.flexible(), spacing: 10)]
            : [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]
    }

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $model.livePath) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header
                    if model.showRecentAlerts { RecentAlertsStrip() }
                    if !model.scopedConnections.isEmpty { groupChips }
                    content
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .refreshable { await Task { await model.refreshAll(reloadConfig: true) }.value }
            .screenBackground()
            .toolbar(.hidden, for: .navigationBar)
            .appDestinations()
            // Again when a server comes online: at launch they are still connecting.
            .task(id: model.scopedConnections.map { "\($0.id)|\($0.isOnline)" }) {
                for conn in model.scopedConnections where conn.isOnline && roles[conn.server.id] == nil {
                    if let client = conn.client { roles[conn.server.id] = await client.role() ?? "admin" }
                }
            }
            .sheet(item: $editingGroup) { target in
                CameraGroupEditor(target: target) { newName in
                    // A renamed group stays the one shown.
                    if let old = target.name, group == old { group = newName }
                }
                .environment(model)
            }
            .onChange(of: movingNow, initial: true) { _, keys in
                let now = Date()
                for k in keys { lastActive[k] = now }
            }
            // Lets a quiet camera leave "Now active" once its time is up.
            .task {
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(5))
                    clock = Date()
                }
            }
            .alert(L("Delete \(deletingGroup.map { FrigateFormat.prettyName($0.name ?? "") } ?? "")?"),
                   isPresented: Binding(get: { deletingGroup != nil }, set: { if !$0 { deletingGroup = nil } })) {
                Button("Cancel", role: .cancel) {}
                Button("Delete", role: .destructive) { if let t = deletingGroup { Task { await deleteGroup(t) } } }
            } message: { Text("The group is removed from Frigate; its cameras stay.") }
            // The group shown was removed (here or in Frigate): show all cameras again.
            .onChange(of: groupNames) { _, names in
                if let g = group, g != Self.nowActive, !names.contains(g) { group = nil }
            }
            .alert("Not saved", isPresented: Binding(get: { groupError != nil }, set: { if !$0 { groupError = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(groupError ?? "") }
            .task {
                // Keep object badges, status and counts fresh while the tab is visible.
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(10))
                    await model.refreshAll()
                }
            }
        }
    }

    private var header: some View {
        HStack {
            LargeTitle(text: L("Live"))
            Spacer()
            ProfileSwitcher()
            if sizeClass == .regular {
                // Wide screens: how many cameras side by side, as plain numbers so it says
                // exactly what you get.
                HStack(spacing: 8) {
                    Image(systemName: "square.grid.2x2").font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                    Picker("Columns", selection: Binding(get: { wideColumns }, set: { c in
                        withAnimation(.easeOut(duration: 0.2)) { storedColumns = c }
                    })) {
                        ForEach(Self.columnChoices, id: \.self) { n in Text("\(n)").tag(n) }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: CGFloat(Self.columnChoices.count) * 40)
                }
            } else {
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { largeTiles.toggle() }
                } label: { HeaderIconLabel(systemName: largeTiles ? "square.grid.2x2" : "rectangle.grid.1x2") }
                .buttonStyle(.plain)
                .accessibilityLabel(largeTiles ? "Small tiles" : "Large tiles")
            }
        }
        .padding(.top, 8)
    }

    /// Camera groups across the servers in scope, by name.
    private var groupNames: [String] {
        var names: [String] = []
        for conn in model.scopedConnections {
            for g in conn.config?.orderedGroups ?? [] where !names.contains(g.name) { names.append(g.name) }
        }
        return names
    }

    /// Servers where this user may change camera groups (admins, as in Frigate).
    private var groupAdmins: [ServerConnection] {
        model.scopedConnections.filter { $0.isOnline && $0.config != nil && (roles[$0.server.id] ?? "") == "admin" }
    }

    private var groupChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Button { group = nil } label: { Chip(title: L("All"), selected: group == nil) }
                let active = activeCount
                Button { group = Self.nowActive } label: {
                    Chip(title: L("Now active"), selected: group == Self.nowActive, systemImage: "figure.walk.motion")
                }
                .accessibilityLabel(L("Now active") + (active > 0 ? ", \(active)" : ""))
                ForEach(groupNames, id: \.self) { name in
                    Button { group = name } label: {
                        HStack(spacing: 6) {
                            if busyGroup == name { ProgressView().controlSize(.mini) }
                            Chip(title: FrigateFormat.prettyName(name), selected: group == name, lucide: groupIcon(name))
                        }
                    }
                    .contextMenu { groupMenu(name) }
                }
                if !groupAdmins.isEmpty {
                    Button { editingGroup = CameraGroupTarget(name: nil, serverIDs: groupAdmins.map(\.server.id)) } label: { addChip }
                        .accessibilityLabel("New group")
                }
            }
            .padding(.horizontal, 16)
        }
        .padding(.horizontal, -16)
        .buttonStyle(.plain)
    }

    /// Cameras ("<server id>|<camera>") with a moving object right now.
    private var movingNow: Set<String> {
        var keys = Set<String>()
        for conn in model.scopedConnections {
            for (cam, act) in conn.activity where !act.tracking.isEmpty { keys.insert("\(conn.server.id)|\(cam)") }
        }
        return keys
    }

    private func isActive(_ conn: ServerConnection, _ cam: String) -> Bool {
        let key = "\(conn.server.id)|\(cam)"
        if movingNow.contains(key) { return true }
        // As long as Birdseye on that server keeps a camera after its activity ends.
        let linger = conn.config?.inactivityThreshold ?? 30
        return lastActive[key].map { clock.timeIntervalSince($0) < linger } ?? false
    }

    private var activeCount: Int {
        model.scopedConnections.reduce(0) { n, conn in n + conn.cameras.filter { isActive(conn, $0.name) }.count }
    }

    private var addChip: some View {
        Image(systemName: "plus").font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.accent)
            .frame(width: 34, height: 34)
            .background(Theme.card, in: Circle())
            .overlay(Circle().stroke(Theme.controlBorder, lineWidth: 1))
            .padding(1)
    }

    /// The icon Frigate has for a group (a Lucide name, as its web UI shows it).
    private func groupIcon(_ name: String) -> String? {
        for conn in model.scopedConnections {
            if let g = conn.config?.cameraGroups?[name], let icon = g.icon { return icon }
        }
        return nil
    }

    /// Edit and Delete for a group: one group in the app, whichever of your servers have it.
    @ViewBuilder
    private func groupMenu(_ name: String) -> some View {
        let admins = groupAdmins
        if admins.contains(where: { $0.config?.cameraGroups?[name] != nil }) {
            let target = CameraGroupTarget(name: name, serverIDs: admins.map(\.server.id))
            Button { editingGroup = target } label: { Label("Edit", systemImage: "pencil") }
            Button(role: .destructive) { deletingGroup = target } label: { DestructiveMenuLabel(title: "Delete") }
        }
    }

    /// Removes the group from every server (where this user is admin) that has it.
    private func deleteGroup(_ target: CameraGroupTarget) async {
        guard let name = target.name else { return }
        busyGroup = name
        defer { busyGroup = nil }
        for id in target.serverIDs {
            guard let conn = model.connection(for: id), conn.config?.cameraGroups?[name] != nil, let client = conn.client else { continue }
            do {
                try await client.setConfig(["camera_groups": [name: ""]], requiresRestart: false, updateTopic: nil)
                await conn.refresh(reloadConfig: true)
            } catch {
                groupError = L("\(conn.server.name): \(error.localizedDescription)")
                return
            }
        }
    }

    private func cameras(for conn: ServerConnection) -> [CameraConfig] {
        if group == Self.nowActive { return conn.cameras.filter { isActive(conn, $0.name) } }
        guard let group, let members = conn.config?.cameraGroups?[group]?.cameras else {
            return group == nil ? conn.cameras : []
        }
        return conn.cameras.filter { members.contains($0.name) }
    }

    private func tileLink(_ conn: ServerConnection, _ cam: CameraConfig, fill: Bool) -> some View {
        NavigationLink(value: CameraRoute(serverID: conn.server.id, camera: cam.name)) {
            CameraTile(conn: conn, camera: cam, wholePicture: largeTiles && sizeClass != .regular, fill: fill)
        }
        .buttonStyle(.plain)
        .contextMenu { tileMenu(conn, cam) }
    }

    /// Long press on a tile (right click on the Mac).
    @ViewBuilder
    private func tileMenu(_ conn: ServerConnection, _ cam: CameraConfig) -> some View {
        Button { model.livePath.append(CameraRoute(serverID: conn.server.id, camera: cam.name)) } label: {
            Label("Open live", systemImage: "video")
        }
        Button {
            model.livePath.append(PlaybackRoute(serverID: conn.server.id, camera: cam.name, day: Calendar.current.startOfDay(for: Date())))
        } label: {
            Label("Playback", systemImage: "clock.arrow.circlepath")
        }
        CameraSwitches(conn: conn, camera: cam)
    }

    @ViewBuilder
    private var content: some View {
        let all = model.scopedConnections
        // With a group chosen, only the servers that have cameras in it (a group is per server).
        let conns = group == nil ? all : all.filter { !cameras(for: $0).isEmpty }
        if all.count == 1, let conn = all.first {
            serverGrid(conn)
        } else if conns.isEmpty {
            if group == Self.nowActive {
                EmptyStateView(systemImage: "figure.walk.motion", title: L("Nothing is moving right now"),
                               message: L("Cameras show here while Frigate tracks a moving object."))
            } else {
                EmptyStateView(systemImage: "video.slash", title: L("No cameras"), message: L("This group has no cameras."))
            }
        } else {
            ForEach(conns) { conn in
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 8) {
                        ServerBadge(initial: conn.server.initial, tint: conn.server.tint, size: 20)
                        Text(conn.server.name.uppercased())
                            .font(.system(size: 13, weight: .bold)).kerning(0.4)
                            .foregroundStyle(Theme.textTertiary)
                        Text(conn.metaLine).font(.system(size: 12)).foregroundStyle(Theme.textSecondary).lineLimit(1)
                    }
                    .padding(.horizontal, 4)
                    serverGrid(conn)
                }
                .padding(.top, 6)
            }
        }
    }

    @ViewBuilder
    private func serverGrid(_ conn: ServerConnection) -> some View {
        switch conn.status {
        case .connecting where conn.config == nil:
            HStack { Spacer(); ProgressView().padding(40); Spacer() }
        case .offline(let message) where conn.config == nil:
            VStack(alignment: .leading, spacing: 10) {
                Text("\(conn.server.name) is offline").font(.system(size: 15, weight: .semibold))
                Text(message).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                Button("Try again") { Task { await conn.connect() } }
                    .font(.system(size: 14, weight: .semibold))
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.controlBorder, style: StrokeStyle(lineWidth: 1, dash: [4])))
        default:
            let cams = cameras(for: conn)
            // Only when something is wrong; nothing at all when Frigate is fine.
            if !conn.problems.isEmpty { ServerProblemsBanner(conn: conn) }
            if cams.isEmpty {
                EmptyStateView(systemImage: "video.slash", title: L("No cameras"), message: group == nil ? nil : L("This group has no cameras on \(conn.server.name)."))
            } else {
                if model.tileWholePicture && columns.count > 1 {
                    // Frigate's dashboard shapes, in bands of rows so the grid stays lazy.
                    let cells = TileGridPlacement.place(cams.map(\.tileSpan), columns: columns.count)
                    LazyVStack(spacing: 12) {
                        ForEach(TileGridPlacement.bands(cells), id: \.first) { band in
                            let top = cells[band[0]].row
                            TileBand(columns: columns.count, spacing: sizeClass == .regular ? 12 : 10, rowSpacing: 12,
                                     cells: band.map { cells[$0].moved(rows: -top) }) {
                                ForEach(band, id: \.self) { i in
                                    tileLink(conn, cams[i], fill: true)
                                }
                            }
                        }
                    }
                } else {
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(cams, id: \.name) { cam in
                            tileLink(conn, cam, fill: false)
                        }
                    }
                }
            }
        }
    }
}

struct CameraTile: View {
    let conn: ServerConnection
    let camera: CameraConfig
    /// The camera's own shape instead of 16:10, for one tile per row.
    var wholePicture = false
    /// The picture fills the height the grid gives the tile (`TileBand`) and shows the camera's
    /// whole picture inside it.
    var fill = false
    @Environment(AppModel.self) private var model
    @Environment(\.displayScale) private var displayScale
    @State private var autoLiveOn = false
    /// The picture's width in points, to pick a stream sharp enough for it.
    @State private var tileWidth: CGFloat = 0

    /// The stream a live tile plays. The lightest one (a camera's sub stream, often 640 wide)
    /// unless the tile is wider than about 800 pixels on screen and the connection is not metered:
    /// then the main stream, as Frigate's own dashboard plays large tiles.
    private var tileStream: (title: String, name: String)? {
        // Main first and the lightest last, whichever Frigate opens the camera with.
        let streams = conn.liveStreams(camera, inFrigateOrder: false)
        switch model.tileStream {
        case .main: return streams.first
        case .sub: return streams.last
        case .automatic:
            let pixels = tileWidth * displayScale
            if streams.count > 1, pixels > 800, !NetworkStatus.shared.isExpensive { return streams.first }
            return streams.last
        }
    }

    var body: some View {
        let on = conn.isCameraEnabled(camera)
        let online = on && conn.isCameraOnline(camera.name)
        let objects = on ? conn.activeObjects[camera.name] ?? [] : []
        let activity = online ? conn.activity[camera.name] : nil
        let tracking = !(activity?.tracking.isEmpty ?? true)
        let active = activity?.motion == true || tracking
        // Detection or recording switched off: orange, a warning rather than a fault.
        let detectOff = online && !conn.isDetectEnabled(camera)
        let recordOff = online && conn.isRecordSwitchedOff(camera)
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .topLeading) {
                picture
                    .overlay {
                        if !on {
                            // Switched off in Frigate: no picture to show, and not a fault like Offline.
                            Image(systemName: "video.slash")
                                .font(.system(size: 24))
                                .foregroundStyle(Theme.textSecondary)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                                .background(Theme.placeholder)
                        } else {
                        ZStack {
                            RemoteImage(url: conn.client?.latestImageURL(camera: camera.name, height: 270),
                                        session: conn.client?.session ?? .shared,
                                        refresh: online ? (objects.isEmpty ? 6 : 2) * (NetworkStatus.shared.isExpensive ? 2.5 : 1) : nil,
                                        contentMode: fill ? .fit : .fill)
                            if online && (model.showsLiveGrid || (model.autoLive && autoLiveOn)), let client = conn.client,
                               let stream = tileStream {
                                TileLivePlayer(client: client, camera: camera, stream: stream.name, fill: !fill)
                                    // A new stream (the tile grew or shrank past the threshold) starts fresh.
                                    .id(stream.name)
                            }
                        }
                        }
                    }
                    .clipped()
                    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { tileWidth = $0 }
                    .opacity(online || !on ? 1 : 0.4)

                // Only flag cameras that are down; online is the normal state.
                if on && !online {
                    HStack(spacing: 5) {
                        StatusDot(color: Theme.offline, size: 6)
                        Text("OFFLINE")
                    }
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Theme.text)
                    .padding(.horizontal, 7).padding(.vertical, 3)
                    .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 6))
                    .padding(8)
                }
            }
            // Top left, like the label icons on events and recent alerts.
            .overlay(alignment: .topLeading) {
                if model.liveLabels && online && !objects.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(objects.prefix(3), id: \.self) { LabelIcon(label: $0) }
                    }
                    .padding(8)
                }
            }
            .overlay(alignment: .topTrailing) {
                if model.liveLabels && activity?.motion == true { MotionDot().padding(8) }
            }
            .overlay(alignment: .bottomTrailing) {
                if model.liveLabels && (detectOff || recordOff) {
                    HStack(spacing: 4) {
                        if detectOff { SwitchedOffIcon(kind: .detect) }
                        if recordOff { SwitchedOffIcon(kind: .record) }
                    }
                    .padding(7)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12)
                .stroke(tracking ? Theme.recording : Theme.cardBorder, lineWidth: tracking ? 1.5 : 1))
            .animation(.easeOut(duration: 0.2), value: tracking)

            HStack(alignment: .firstTextBaseline) {
                Text(camera.displayName)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
                Spacer(minLength: 4)
                // Redrawn every minute, so "4 h ago" does not freeze.
                TimelineView(.everyMinute) { _ in
                    if detectOff || recordOff {
                        Text(detectOff && recordOff ? L("Detect and record off") : detectOff ? L("Detect off") : L("Recording off"))
                            .foregroundStyle(Theme.warning)
                    } else {
                        Text(on ? meta(online: online) : L("Off")).foregroundStyle(Theme.textSecondary)
                    }
                }
                    .font(.system(size: 12))
                    .lineLimit(1)
            }
            .padding(.horizontal, 2)
        }
        .contentShape(Rectangle())
        // Automatic live view: go live on activity and stay live a little after it ends.
        .task(id: active) {
            if active {
                autoLiveOn = true
            } else if autoLiveOn {
                try? await Task.sleep(for: .seconds(10))
                if !Task.isCancelled { autoLiveOn = false }
            }
        }
    }

    /// The picture's area: the camera's own shape for one tile per row, the grid's cell with
    /// `fill`, and otherwise 16:10 so tiles side by side line up.
    @ViewBuilder
    private var picture: some View {
        if fill {
            Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            Color.clear.aspectRatio(wholePicture ? camera.aspectRatio : 16 / 10, contentMode: .fit)
        }
    }

    private func meta(online: Bool) -> String {
        if !online { return L("Offline") }
        if !(conn.activeObjects[camera.name] ?? []).isEmpty { return L("Now") }
        if let last = conn.lastActivity[camera.name] { return FrigateFormat.relative(last) }
        return ""
    }
}

/// Muted live video of a camera for the overview grid (the stream `CameraTile` picks).
/// The snapshot underneath stays visible until the video plays.
struct TileLivePlayer: View {
    let client: FrigateClient
    let camera: CameraConfig
    let stream: String
    /// Cropped to the tile; off to show the whole picture.
    var fill = true
    @State private var state: LivePlayerState
    @State private var visible = false
    @Environment(\.scenePhase) private var scenePhase

    init(client: FrigateClient, camera: CameraConfig, stream: String, fill: Bool = true) {
        self.client = client
        self.camera = camera
        self.stream = stream
        self.fill = fill
        // The stream from the start: set only on appear, the player first tried the camera's
        // name, which on servers whose go2rtc streams are named otherwise ("bar_1") failed and
        // left the tile on the MJPEG fallback.
        let state = LivePlayerState()
        state.stream = stream
        state.preferred = .mse
        _state = State(initialValue: state)
    }

    var body: some View {
        LivePlayerView(client: client, camera: camera.name, state: state, fill: fill)
            .opacity(state.playing ? 1 : 0)
            .allowsHitTesting(false)
            .onAppear {
                visible = true
                // Without the snapshot fallback: the snapshot underneath already shows.
                state.stream = stream
                state.preferred = .mse
                state.suspended = false
            }
            // The stream's connection is shared (LiveConnection): a camera opened from this tile
            // keeps it, so the tile can stop at once.
            .onDisappear { visible = false; state.suspended = true }
            .onChange(of: scenePhase) { _, phase in state.suspended = phase != .active }
    }
}

/// Pulsing red dot shown while Frigate sees motion, like the web UI.
struct MotionDot: View {
    @State private var dim = false
    var body: some View {
        Circle()
            .fill(Theme.recording)
            .frame(width: 6, height: 6)
            .shadow(color: Theme.recording.opacity(0.8), radius: 2)
            .opacity(dim ? 0.35 : 1)
            .animation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true), value: dim)
            .onAppear { dim = true }
            .accessibilityLabel("Motion")
    }
}


/// Detection or recording switched off in Frigate: its symbol struck through, in orange.
struct SwitchedOffIcon: View {
    enum Kind { case detect, record }
    let kind: Kind
    /// On the video by the clock the chip carries the colour; on a tile the icon does.
    var size: CGFloat = 13

    var body: some View {
        Image(systemName: kind == .detect ? "person.crop.rectangle" : "record.circle")
            .font(.system(size: size, weight: .semibold))
            .overlay {
                Capsule().frame(width: size * 0.14).rotationEffect(.degrees(-45)).padding(-size * 0.12)
            }
            .foregroundStyle(Theme.warning)
            .frame(minWidth: 20, minHeight: 20)
            .background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 6))
            .accessibilityLabel(kind == .detect ? Text("Detect off") : Text("Recording off"))
    }
}

/// "Detection off" and "Recording off" by the clock on the camera's video; a tap offers to
/// switch it on again.
struct SwitchedOffChips: View {
    let conn: ServerConnection
    let camera: CameraConfig

    var body: some View {
        if conn.isCameraEnabled(camera) {
            if !conn.isDetectEnabled(camera) {
                chip("Detect off", kind: .detect, on: "Switch detection on") { conn.setDetectEnabled(camera, true) }
            }
            if conn.isRecordSwitchedOff(camera) {
                chip("Recording off", kind: .record, on: "Switch recording on") { conn.setRecordEnabled(camera, true) }
            }
        }
    }

    private func chip(_ title: LocalizedStringKey, kind: SwitchedOffIcon.Kind, on: LocalizedStringKey,
                      action: @escaping () -> Void) -> some View {
        Menu {
            Button(action: action) { Label(on, systemImage: "power") }
        } label: {
            HStack(spacing: 5) {
                Image(systemName: kind == .detect ? "person.crop.rectangle" : "record.circle")
                    .font(.system(size: 10, weight: .bold))
                    .overlay { Capsule().frame(width: 1.6).rotationEffect(.degrees(-45)).padding(-1.5) }
                Text(title).lineLimit(1).minimumScaleFactor(0.8)
            }
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(Theme.onLabel)
            .padding(.horizontal, 8).frame(height: VideoChip.height)
            .background(Theme.warning, in: RoundedRectangle(cornerRadius: 6))
        }
        .menuStyle(.button).buttonStyle(.plain)
    }
}

/// Frigate's switches for a camera, in its menus: detection and recording (while the camera
/// runs), and switching the camera off or on (Frigate 0.16+, when on in the config).
struct CameraSwitches: View {
    let conn: ServerConnection
    let camera: CameraConfig

    var body: some View {
        let on = conn.isCameraEnabled(camera)
        Section("Frigate") {
            // Switching is for admins, as in Frigate's camera menu; anyone may pause notifications.
            if on && conn.isAdmin {
                Toggle(isOn: Binding(get: { conn.isDetectEnabled(camera) }, set: { conn.setDetectEnabled(camera, $0) })) {
                    Label("Detection", systemImage: "person.crop.rectangle")
                }
                .toggleStyle(.automatic)
                if conn.canSwitchRecording(camera) {
                    Toggle(isOn: Binding(get: { conn.isRecordEnabled(camera) }, set: { conn.setRecordEnabled(camera, $0) })) {
                        Label("Recordings", systemImage: "record.circle")
                    }
                    .toggleStyle(.automatic)
                }
            }
            if on && conn.hasNotifications(camera) {
                NotificationPauseMenu(conn: conn, camera: camera)
            }
            if conn.isAdmin && camera.canSwitchOff {
                Button {
                    conn.setCameraEnabled(camera, !on)
                } label: {
                    Label(on ? "Switch camera off" : "Switch camera on", systemImage: "power")
                }
            }
        }
    }
}


/// "Pause notifications" for 30 minutes, an hour, 8 hours or a day, or "Resume", as Frigate's
/// camera menu offers.
struct NotificationPauseMenu: View {
    let conn: ServerConnection
    let camera: CameraConfig

    var body: some View {
        if let until = conn.notificationsPaused(camera) {
            Button { conn.pauseNotifications(camera, minutes: 0) } label: {
                Label(L("Resume notifications (paused until \(FrigateFormat.time(until)))"), systemImage: "bell")
            }
        } else {
            Menu {
                ForEach([(30, L("30 minutes")), (60, L("1 hour")), (480, L("8 hours")), (1440, L("24 hours"))], id: \.0) { m, title in
                    Button(title) { conn.pauseNotifications(camera, minutes: m) }
                }
            } label: {
                Label("Pause notifications", systemImage: "bell.slash")
            }
        }
    }
}


/// The active Frigate profile as a chip in the Live header; a tap lists the profiles to switch
/// to, per server when several have them. Hidden when no server in view has profiles.
struct ProfileSwitcher: View {
    @Environment(AppModel.self) private var model
    @State private var errorMessage: String?
    /// Waiting for Frigate to switch.
    @State private var switching = false

    private var servers: [ServerConnection] { model.scopedConnections.filter { $0.isOnline && !$0.profiles.isEmpty } }

    var body: some View {
        if !servers.isEmpty {
            Menu {
                ForEach(servers) { conn in
                    Section(servers.count > 1 ? conn.server.name : L("Profile")) {
                        Button { pick(conn, nil) } label: { choice(L("No profile"), conn.activeProfile == nil) }
                        ForEach(conn.profiles) { p in
                            Button { pick(conn, p.name) } label: { choice(p.title, conn.activeProfile == p.name) }
                        }
                    }
                }
            } label: {
                HStack(spacing: 5) {
                    if switching {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: "person.2.crop.square.stack").font(.system(size: 12, weight: .semibold))
                    }
                    Text(label).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                    Image(systemName: "chevron.down").font(.system(size: 9, weight: .bold))
                }
                .foregroundStyle(active ? Theme.accent : Theme.text)
                .padding(.horizontal, 11).frame(height: 32)
                .background(Theme.card, in: Capsule())
                .overlay(Capsule().stroke(active ? Theme.accent : Theme.controlBorder, lineWidth: 1))
            }
            .disabled(switching)
            .accessibilityLabel(L("Profile: \(label)"))
            .alert("Something went wrong", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(errorMessage ?? "") }
        }
    }

    private var active: Bool { servers.contains { $0.activeProfile != nil } }

    /// The active profile; "Several" only when servers run different ones; "No profile" when none
    /// runs (a server without an active profile does not make it "mixed").
    private var label: String {
        let titles = Set(servers.compactMap(\.activeProfileTitle))
        if titles.isEmpty { return L("No profile") }
        return titles.count == 1 ? titles.first! : L("Several")
    }

    @ViewBuilder
    private func choice(_ title: String, _ selected: Bool) -> some View {
        if selected { Label(title, systemImage: "checkmark") } else { Text(title) }
    }

    private func pick(_ conn: ServerConnection, _ name: String?) {
        switching = true
        Task {
            defer { switching = false }
            do {
                try await conn.setProfile(name)
                // Frigate applies it to every camera, which takes a few seconds.
                for _ in 0..<10 {
                    try? await Task.sleep(for: .seconds(1))
                    if let p = try? await conn.client?.profiles(), p.active == name { break }
                }
                await conn.refresh(reloadConfig: true)
            } catch { errorMessage = error.localizedDescription }
        }
    }
}


/// What is wrong with a server (an offline camera, a slow detector, a full disk), as Frigate's
/// status bar shows it; tap for the whole list and the system status.
struct ServerProblemsBanner: View {
    let conn: ServerConnection
    @Environment(AppModel.self) private var model
    @State private var showing = false

    var body: some View {
        let problems = conn.problems
        let isError = problems.first?.severity == .error
        let tint = isError ? Theme.destructive : Theme.warning
        Button { showing = true } label: {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(tint)
                Text(problems.first?.text ?? "").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.text)
                    .lineLimit(1)
                Spacer(minLength: 6)
                if problems.count > 1 {
                    Text(L("+\(problems.count - 1) more")).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                }
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.textTertiary)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(tint.opacity(0.35), lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .sheet(isPresented: $showing) {
            NavigationStack { ServerProblemsSheet(conn: conn) }
                .environment(model)
                .presentationDetents([.medium, .large])
        }
    }
}

private struct ServerProblemsSheet: View {
    let conn: ServerConnection
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Section {
                ForEach(conn.problems) { p in
                    Label {
                        Text(p.text)
                    } icon: {
                        Image(systemName: p.severity == .error ? "exclamationmark.octagon.fill" : "exclamationmark.triangle.fill")
                            .foregroundStyle(p.severity == .error ? Theme.destructive : Theme.warning)
                    }
                    .listRowBackground(Theme.card)
                }
            } footer: {
                if conn.problems.isEmpty { Text("No problems anymore.") }
            }
            Section {
                NavigationLink { SystemStatusView(serverID: conn.server.id) } label: {
                    Label("System status and logs", systemImage: "waveform.path.ecg")
                }
                .listRowBackground(Theme.card)
            }
        }
        // Solid, like the app's other screens: the sheet's glass shows the cameras through the list otherwise.
        .scrollContentBackground(.hidden)
        .background(Theme.background.ignoresSafeArea())
        .presentationBackground(Theme.background)
        .navigationTitle(conn.server.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { closePresented(dismiss) }.plainBarButton() } }
    }
}
