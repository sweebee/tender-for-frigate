import SwiftUI

// Profiles, users, a camera's zones and masks, and MQTT, in a server's settings.

// MARK: - Profiles

/// Frigate 0.18's profiles: switch the whole server to "Home", "Away", … in one tap, and make
/// or change them (what each one does per camera).
struct ProfilesView: View {
    let serverID: UUID
    @Environment(AppModel.self) private var model
    @State private var profiles: [(name: String, title: String)] = []
    @State private var active: String?
    @State private var loaded = false
    @State private var switching: String?
    @State private var role: String?
    @State private var creating = false
    @State private var renaming: String?
    @State private var deleting: String?
    @State private var nameField = ""
    @State private var errorMessage: String?
    /// Creating, renaming or deleting a profile is under way on the server.
    @State private var working = false

    private var conn: ServerConnection? { model.connection(for: serverID) }
    private var isAdmin: Bool { role == nil || role == "admin" }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    ScreenTitle(text: L("Profiles"), showsBack: true)
                    Spacer()
                    if working { ProgressView().padding(.trailing, 4) }
                    if isAdmin {
                        Button { nameField = ""; creating = true } label: { HeaderIconLabel(systemName: "plus") }
                            .buttonStyle(.plain)
                            .accessibilityLabel("New profile")
                    }
                }
                .padding(.top, 8)
                Text("A profile changes several cameras at once, for instance the indoor cameras off while you are home.")
                    .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                if !loaded {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                } else {
                    VStack(spacing: 0) {
                        profileRow(name: nil, title: L("No profile"), subtitle: L("The configuration as it is"))
                        ForEach(profiles, id: \.name) { p in
                            Divider().overlay(Theme.cardBorder)
                            profileRow(name: p.name, title: p.title, subtitle: nil)
                        }
                    }
                    .card(radius: 12)
                    .disabled(working)
                    .opacity(working ? 0.6 : 1)
                    if profiles.isEmpty && isAdmin {
                        Text("Make one with +, then choose per camera what it changes.")
                            .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                    }
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .refreshable { await load() }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .alert("New profile", isPresented: $creating) {
            TextField("Name, e.g. Home or Away", text: $nameField)
            Button("Cancel", role: .cancel) {}
            Button("Create") { Task { await create() } }
        }
        .alert("Rename profile", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $nameField)
            Button("Cancel", role: .cancel) {}
            Button("Rename") { let id = renaming; Task { await rename(id) } }
        }
        .alert("Delete this profile?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { let id = deleting; Task { await delete(id) } }
        } message: { Text("Its changes per camera are removed too.") }
        .alert("Something went wrong", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private func profileRow(name: String?, title: String, subtitle: String?) -> some View {
        let isActive = active == name
        return HStack(spacing: 10) {
            Button { activate(name) } label: {
                HStack(spacing: 12) {
                    Image(systemName: isActive ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 18)).foregroundStyle(isActive ? Theme.accent : Theme.textSecondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.system(size: 15, weight: isActive ? .semibold : .regular)).foregroundStyle(Theme.text)
                        if let subtitle { Text(subtitle).font(.system(size: 12)).foregroundStyle(Theme.textSecondary) }
                    }
                    Spacer(minLength: 0)
                    if switching == (name ?? "") { ProgressView().controlSize(.small) }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(switching != nil)
            if let name, isAdmin {
                Menu {
                    Button { nameField = title; renaming = name } label: { Label("Rename", systemImage: "pencil") }
                    Divider()
                    Button(role: .destructive) { deleting = name } label: { DestructiveMenuLabel(title: "Delete") }
                } label: {
                    Image(systemName: "ellipsis").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.textSecondary)
                        .frame(width: 32, height: 36).contentShape(Rectangle())
                }
                NavigationLink { ProfileEditView(serverID: serverID, profile: name, title: title, active: isActive) } label: {
                    Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.textTertiary)
                        .frame(width: 28, height: 36).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L("Edit \(title)"))
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
    }

    private func activate(_ name: String?) {
        guard let conn, name != active else { return }
        switching = name ?? ""
        let before = active
        active = name
        conn.command("profile/set", name ?? "none")
        Task {
            // Frigate applies the profile to every camera, which takes a few seconds; ask until it
            // reports it (or give up after about ten).
            for _ in 0..<10 {
                try? await Task.sleep(for: .seconds(1))
                if let p = try? await conn.client?.profiles(), p.active == name { break }
            }
            await load()
            if active != name { active = before; errorMessage = L("Frigate did not switch the profile.") }
            await conn.refresh(reloadConfig: true)
            switching = nil
        }
    }

    private func load() async {
        guard let client = conn?.client else { return }
        if let p = try? await client.profiles() {
            profiles = p.profiles
            active = p.active
            // Live's switcher reads the connection's list.
            conn?.profiles = p.profiles.map { FrigateProfile(name: $0.name, title: $0.title) }
            conn?.activeProfile = p.active
        }
        role = await client.role()
        loaded = true
    }

    private func create() async {
        let title = nameField.trimmingCharacters(in: .whitespaces)
        let id = ZoneDraft.key(for: title)
        guard !id.isEmpty, let client = conn?.client else { return }
        guard !profiles.contains(where: { $0.name == id }) else { errorMessage = L("A profile with this name already exists."); return }
        working = true
        defer { working = false }
        do {
            try await client.setConfig(["profiles": [id: ["friendly_name": title]]], requiresRestart: false, updateTopic: nil)
            await settle { $0.contains { $0.name == id } }
        } catch { errorMessage = error.localizedDescription }
    }

    /// Frigate reloads its profiles a moment after the config changed: ask until its list shows
    /// the change (or about ten seconds passed), then show that list.
    private func settle(_ done: ([(name: String, title: String)]) -> Bool) async {
        for _ in 0..<20 {
            if let p = try? await conn?.client?.profiles(), done(p.profiles) { break }
            try? await Task.sleep(for: .milliseconds(500))
        }
        await load()
    }

    private func rename(_ id: String?) async {
        let title = nameField.trimmingCharacters(in: .whitespaces)
        guard let id, !title.isEmpty, let client = conn?.client else { return }
        working = true
        defer { working = false }
        do {
            try await client.setConfig(["profiles": [id: ["friendly_name": title]]], requiresRestart: false, updateTopic: nil)
            await settle { $0.contains { $0.name == id && $0.title == title } }
        } catch { errorMessage = error.localizedDescription }
    }

    private func delete(_ id: String?) async {
        guard let id, let conn, let client = conn.client else { return }
        working = true
        defer { working = false }
        do {
            // An active profile is switched off first, as Frigate's own page does, and only
            // deleted once Frigate reports no profile.
            if active == id || conn.activeProfile == id {
                try await client.setProfile(nil)
                for _ in 0..<20 {
                    if let p = try? await client.profiles(), p.active == nil { break }
                    try? await Task.sleep(for: .milliseconds(500))
                }
            }
            var data: [String: Any] = ["profiles": [id: ""]]
            if let cfg = try? await client.rawConfig(), let cams = cfg["cameras"] as? [String: Any] {
                var camData: [String: Any] = [:]
                for (name, value) in cams where ((value as? [String: Any])?["profiles"] as? [String: Any])?[id] != nil {
                    camData[name] = ["profiles": [id: ""]]
                }
                if !camData.isEmpty { data["cameras"] = camData }
            }
            try await client.setConfig(data, requiresRestart: false, updateTopic: nil)
            await settle { !$0.contains { $0.name == id } }
            if profiles.contains(where: { $0.name == id }) { errorMessage = L("Frigate still lists the profile; try again in a moment.") }
        } catch { errorMessage = error.localizedDescription }
    }
}

/// What a profile changes, per camera: each switch follows the camera's own setting, or is on or
/// off while the profile is active.
struct ProfileEditView: View {
    let serverID: UUID
    let profile: String
    let title: String
    let active: Bool
    @Environment(AppModel.self) private var model
    /// Camera → its overrides under `profiles.<profile>`.
    @State private var overrides: [String: [String: Any]] = [:]
    @State private var cameras: [CameraConfig] = []
    @State private var loaded = false
    @State private var saving: String?
    @State private var errorMessage: String?

    private var conn: ServerConnection? { model.connection(for: serverID) }

    /// The switches a profile can set, as paths inside a camera's profile.
    private static let switches: [(path: String, title: String, icon: String)] = [
        ("enabled", L("Camera"), "video"),
        ("detect.enabled", L("Object detection"), "person.crop.rectangle"),
        ("record.enabled", L("Record"), "record.circle"),
        ("snapshots.enabled", L("Snapshots"), "photo"),
        ("review.alerts.enabled", L("Alerts"), "exclamationmark.triangle"),
        ("notifications.enabled", L("Push notifications"), "bell"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                ScreenTitle(text: title, showsBack: true).padding(.top, 8)
                Text("Per camera what this profile changes. \"Default\" keeps the camera's own setting.")
                    .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                if !loaded {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                } else {
                    ForEach(cameras, id: \.name) { cam in
                        SectionLabel(text: cam.displayName.uppercased()).padding(.top, 10)
                        VStack(spacing: 0) {
                            ForEach(Array(Self.switches.enumerated()), id: \.element.path) { i, sw in
                                if i > 0 { Divider().overlay(Theme.cardBorder) }
                                row(cam.name, sw)
                            }
                        }
                        .card(radius: 12)
                    }
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private func row(_ camera: String, _ sw: (path: String, title: String, icon: String)) -> some View {
        let value = overrides[camera]?.value(at: sw.path) as? Bool
        let label = value == nil ? L("Default") : (value! ? L("On") : L("Off"))
        return HStack(spacing: 12) {
            Image(systemName: sw.icon).font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.accent).frame(width: 22)
            Text(sw.title).font(.system(size: 15)).lineLimit(1).layoutPriority(1)
            Spacer(minLength: 6)
            if saving == camera + sw.path { ProgressView().controlSize(.small) }
            Menu {
                Button { set(camera, sw.path, nil) } label: { choice(L("Default (as configured)"), value == nil) }
                Button { set(camera, sw.path, true) } label: { choice(L("On"), value == true) }
                Button { set(camera, sw.path, false) } label: { choice(L("Off"), value == false) }
            } label: {
                HStack(spacing: 4) {
                    Text(label).font(.system(size: 14, weight: value == nil ? .regular : .semibold))
                    Image(systemName: "chevron.up.chevron.down").font(.system(size: 10, weight: .semibold))
                }
                .foregroundStyle(value == nil ? Theme.textSecondary : (value! ? Theme.online : Theme.warning))
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
    }

    @ViewBuilder
    private func choice(_ title: String, _ selected: Bool) -> some View {
        if selected { Label(title, systemImage: "checkmark") } else { Text(title) }
    }

    private func load() async {
        guard let cfg = try? await conn?.client?.rawConfig(), let cams = cfg["cameras"] as? [String: Any] else { loaded = true; return }
        var o: [String: [String: Any]] = [:]
        for (name, value) in cams {
            o[name] = ((value as? [String: Any])?["profiles"] as? [String: Any])?[profile] as? [String: Any] ?? [:]
        }
        overrides = o
        cameras = (conn?.config?.cameras.values.map { $0 } ?? [])
            .filter { $0.enabledInConfig != false }
            .sorted { ($0.ui?.order ?? 0, $0.name) < ($1.ui?.order ?? 0, $1.name) }
        loaded = true
    }

    private func set(_ camera: String, _ path: String, _ value: Bool?) {
        guard let conn, let client = conn.client else { return }
        saving = camera + path
        var node: [String: Any] = [:]
        // "" removes the override, as Frigate's own page sends it.
        node.setNested(path, value.map { $0 as Any } ?? "")
        Task {
            defer { saving = nil }
            do {
                try await client.setConfig(["cameras": [camera: ["profiles": [profile: node]]]], requiresRestart: false, updateTopic: nil)
                // An active profile takes the change on its next activation.
                if active { conn.command("profile/set", profile) }
                await load()
            } catch { errorMessage = error.localizedDescription }
        }
    }
}

// MARK: - Users

/// The server's user accounts: add, change role or password, remove. Admin only.
struct UsersView: View {
    let serverID: UUID
    @Environment(AppModel.self) private var model
    @State private var users: [FrigateClient.User] = []
    @State private var roles: [String] = ["admin", "viewer"]
    @State private var loaded = false
    @State private var errorMessage: String?
    @State private var adding = false
    @State private var newName = ""
    @State private var newPassword = ""
    @State private var newRole = "viewer"
    @State private var passwordFor: FrigateClient.User?
    @State private var password = ""
    @State private var deleting: FrigateClient.User?
    /// A change to the users is under way on the server.
    @State private var working = false

    private var conn: ServerConnection? { model.connection(for: serverID) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    ScreenTitle(text: L("Users"), showsBack: true)
                    Spacer()
                    if working { ProgressView().padding(.trailing, 4) }
                    Button { newName = ""; newPassword = ""; newRole = "viewer"; adding = true } label: {
                        HeaderIconLabel(systemName: "plus")
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Add user")
                }
                .padding(.top, 8)
                if !loaded {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(users.enumerated()), id: \.element.id) { i, user in
                            if i > 0 { Divider().overlay(Theme.cardBorder) }
                            userRow(user)
                        }
                    }
                    .card(radius: 12)
                    Text("Passwords need at least 12 characters. The built-in admin cannot be removed or get another role.")
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .refreshable { await load() }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .alert("Add user", isPresented: $adding) {
            TextField("Username", text: $newName).textInputAutocapitalization(.never).autocorrectionDisabled()
            SecureField("Password", text: $newPassword)
            ForEach(roles, id: \.self) { role in
                Button(L("Add as \(roleName(role))")) { add(role: role) }
            }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Choose the role with the button.") }
        .alert("New password", isPresented: Binding(get: { passwordFor != nil }, set: { if !$0 { passwordFor = nil } })) {
            SecureField("Password", text: $password)
            Button("Cancel", role: .cancel) {}
            Button("Save") { if let u = passwordFor { setPassword(u) } }
        } message: { Text(passwordFor.map { L("For \($0.username), at least 12 characters.") } ?? "") }
        .alert("Remove this user?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("Cancel", role: .cancel) {}
            Button("Remove", role: .destructive) { if let u = deleting { remove(u) } }
        } message: { Text(deleting.map { L("\($0.username) can no longer sign in.") } ?? "") }
        .alert("Something went wrong", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private func userRow(_ user: FrigateClient.User) -> some View {
        let builtIn = user.username == "admin"
        return HStack(spacing: 12) {
            Text(String(user.username.prefix(1)).uppercased())
                .font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.onColor)
                .frame(width: 32, height: 32)
                .background(user.role == "admin" ? Theme.accent : Theme.textSecondary, in: RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 2) {
                Text(user.username).font(.system(size: 15, weight: .semibold))
                Text(roleName(user.role ?? "viewer")).font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
            }
            Spacer()
            Menu {
                if !builtIn {
                    Section("Role") {
                        ForEach(roles, id: \.self) { role in
                            Button { setRole(user, role) } label: {
                                if user.role == role { Label(roleName(role), systemImage: "checkmark") } else { Text(roleName(role)) }
                            }
                        }
                    }
                }
                Button { password = ""; passwordFor = user } label: { Label("Change password", systemImage: "key") }
                if !builtIn {
                    Divider()
                    Button(role: .destructive) { deleting = user } label: { DestructiveMenuLabel(title: "Remove user") }
                }
            } label: {
                Image(systemName: "ellipsis").font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.textSecondary)
                    .frame(width: 36, height: 36).contentShape(Rectangle())
            }
            .accessibilityLabel(L("Options for \(user.username)"))
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
    }

    private func roleName(_ role: String) -> String {
        switch role {
        case "admin": return L("Admin")
        case "viewer": return L("Viewer")
        default: return FrigateFormat.prettyName(role)
        }
    }

    private func load() async {
        guard let client = conn?.client else { return }
        do {
            users = try await client.users()
            if let cfg = try? await client.rawConfig(),
               let custom = ((cfg["auth"] as? [String: Any])?["roles"] as? [String: Any])?.keys {
                roles = ["admin", "viewer"] + custom.filter { $0 != "admin" && $0 != "viewer" }.sorted()
            }
        } catch {
            errorMessage = error.localizedDescription
        }
        loaded = true
    }

    private func run(_ work: @escaping (FrigateClient) async throws -> Void) {
        guard let client = conn?.client else { return }
        working = true
        Task {
            defer { working = false }
            do { try await work(client); await load() } catch { errorMessage = error.localizedDescription }
        }
    }

    private func add(role: String) {
        let name = newName.trimmingCharacters(in: .whitespaces), pass = newPassword
        guard !name.isEmpty else { return }
        guard pass.count >= 12 else { errorMessage = L("The password needs at least 12 characters."); return }
        run { try await $0.createUser(name, password: pass, role: role) }
    }

    private func setPassword(_ user: FrigateClient.User) {
        let pass = password
        guard pass.count >= 12 else { errorMessage = L("The password needs at least 12 characters."); return }
        run { try await $0.setPassword(user.username, password: pass) }
    }

    private func setRole(_ user: FrigateClient.User, _ role: String) { run { try await $0.setRole(user.username, role: role) } }
    private func remove(_ user: FrigateClient.User) { run { try await $0.deleteUser(user.username) } }
}

// MARK: - Zones and masks

/// A camera's zones and masks on its picture, each to switch on or off (as Frigate's
/// Masks / Zones page does). Drawing them stays in the web interface.
struct ZonesMasksView: View {
    let serverID: UUID
    let camera: String
    @Environment(AppModel.self) private var model
    @State private var shapes: [Shape] = []
    @State private var loaded = false
    @State private var webInterface: ServersView.WebTarget?
    @State private var editing: ZoneDraft?
    /// Shapes switched on or off and not yet confirmed by Frigate.
    @State private var switching: Set<String> = []
    @State private var role: String?
    /// Named masks (Frigate 0.18): older configs keep masks as bare coordinates.
    @State private var namedMasks = true
    private var isAdmin: Bool { role == nil || role == "admin" }

    struct Shape: Identifiable {
        enum Kind: String { case zone, motionMask = "motion_mask", objectMask = "object_mask" }
        let kind: Kind
        let name: String
        let title: String
        /// Corners, 0…1 across the picture.
        let points: [CGPoint]
        var enabled: Bool
        var objects: [String] = []
        var inertia: Double = 3
        var loiteringTime: Double = 0
        var id: String { kind.rawValue + "/" + name }
        var color: Color {
            switch kind {
            case .zone: return Theme.accent
            case .motionMask: return Color.gray
            case .objectMask: return Theme.destructive
            }
        }
    }

    private var conn: ServerConnection? { model.connection(for: serverID) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    ScreenTitle(text: L("Zones and masks"), showsBack: true)
                    Spacer()
                    if isAdmin && loaded {
                        Menu {
                            Button { editing = .new(.zone) } label: { Label("New zone", systemImage: "square.dashed") }
                            if namedMasks {
                                Button { editing = .new(.motionMask) } label: { Label("New motion mask", systemImage: "rectangle.dashed") }
                                Button { editing = .new(.objectMask) } label: { Label("New object mask", systemImage: "rectangle.slash") }
                            }
                        } label: { HeaderIconLabel(systemName: "plus") }
                        .accessibilityLabel("Add")
                    }
                }
                .padding(.top, 8)
                if let client = conn?.client {
                    Color.clear
                        .aspectRatio(conn?.config?.cameras[camera]?.aspectRatio ?? 16 / 9, contentMode: .fit)
                        .overlay {
                            RemoteImage(url: client.latestImageURL(camera: camera, height: 540), session: client.session)
                        }
                        .overlay {
                            Canvas { ctx, size in
                                for s in shapes where s.enabled && s.points.count > 2 {
                                    var path = Path()
                                    path.addLines(s.points.map { CGPoint(x: $0.x * size.width, y: $0.y * size.height) })
                                    path.closeSubpath()
                                    ctx.fill(path, with: .color(s.color.opacity(s.kind == .zone ? 0.25 : 0.45)))
                                    ctx.stroke(path, with: .color(s.color), lineWidth: 2)
                                }
                            }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                if !loaded {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 80)
                } else if shapes.isEmpty {
                    Text(isAdmin ? L("This camera has no zones or masks yet. Tap + to draw one.") : L("This camera has no zones or masks."))
                        .font(.system(size: 14)).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity, minHeight: 60)
                } else {
                    group(L("Zones"), .zone)
                    group(L("Motion masks"), .motionMask)
                    group(L("Object masks"), .objectMask)
                }
                if let web = conn?.client?.baseURL {
                    Button { webInterface = ServersView.WebTarget(url: web, title: conn?.server.name ?? "") } label: {
                        Label("Draw zones and masks in the web interface", systemImage: "safari").font(.system(size: 14, weight: .semibold))
                    }
                    .padding(.top, 8)
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .fullScreenCover(item: $webInterface) { WebInterfaceView(url: $0.url, title: $0.title) }
        .sheet(isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            if let draft = editing {
                ZoneEditorView(serverID: serverID, camera: camera, draft: draft,
                               taken: Set(shapes.filter { $0.kind.rawValue == draft.kind.rawValue }.map(\.name))) { Task { await load() } }
            }
        }
    }

    @ViewBuilder
    private func group(_ title: String, _ kind: Shape.Kind) -> some View {
        let list = shapes.filter { $0.kind == kind }
        if !list.isEmpty {
            SectionLabel(text: title).padding(.top, 10)
            VStack(spacing: 0) {
                ForEach(Array(list.enumerated()), id: \.element.id) { i, s in
                    if i > 0 { Divider().overlay(Theme.cardBorder) }
                    HStack(spacing: 10) {
                        Button {
                            editing = ZoneDraft(kind: s.kind, name: s.name, title: s.title, points: s.points,
                                                enabled: s.enabled, objects: s.objects,
                                                inertia: s.inertia, loiteringTime: s.loiteringTime)
                        } label: {
                            HStack(spacing: 10) {
                                RoundedRectangle(cornerRadius: 3).fill(s.color.opacity(0.7)).frame(width: 14, height: 14)
                                Text(s.title).font(.system(size: 15)).foregroundStyle(Theme.text)
                                if isAdmin {
                                    Image(systemName: "pencil").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.textSecondary)
                                }
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(!isAdmin)
                        if switching.contains(s.id) { ProgressView().controlSize(.small) }
                        Toggle("", isOn: Binding(get: { s.enabled }, set: { set(s, $0) })).labelsHidden().tint(Theme.online)
                            .disabled(switching.contains(s.id))
                    }
                    .padding(.horizontal, 14).padding(.vertical, 8)
                }
            }
            .card(radius: 12)
        }
    }

    private func set(_ shape: Shape, _ on: Bool) {
        guard let conn, let i = shapes.firstIndex(where: { $0.id == shape.id }),
              conn.command("\(camera)/\(shape.kind.rawValue)/\(shape.name)/set", on ? "ON" : "OFF") else { return }
        shapes[i].enabled = on
        switching.insert(shape.id)
        // Wait until Frigate's config shows it, then show what Frigate has.
        Task {
            for _ in 0..<10 {
                try? await Task.sleep(for: .milliseconds(500))
                if await serverState(of: shape) == on { break }
            }
            await load()
            switching.remove(shape.id)
        }
    }

    private func serverState(of shape: Shape) async -> Bool? {
        guard let cfg = try? await conn?.client?.rawConfig(),
              let cam = (cfg["cameras"] as? [String: Any])?[camera] as? [String: Any] else { return nil }
        let node: Any?
        switch shape.kind {
        case .zone: node = cam["zones"]
        case .motionMask: node = (cam["motion"] as? [String: Any])?["mask"]
        case .objectMask: node = (cam["objects"] as? [String: Any])?["mask"]
        }
        return ((node as? [String: Any])?[shape.name] as? [String: Any])?["enabled"] as? Bool
    }

    private func load() async {
        role = await conn?.client?.role()
        guard let client = conn?.client, let cfg = try? await client.rawConfig(),
              let cam = (cfg["cameras"] as? [String: Any])?[camera] as? [String: Any] else { loaded = true; return }
        let detect = cam["detect"] as? [String: Any]
        let size = CGSize(width: (detect?["width"] as? NSNumber)?.doubleValue ?? 1, height: (detect?["height"] as? NSNumber)?.doubleValue ?? 1)
        var out: [Shape] = []
        func add(_ node: Any?, _ kind: Shape.Kind) {
            for (name, value) in (node as? [String: Any] ?? [:]).sorted(by: { $0.key < $1.key }) {
                guard let item = value as? [String: Any] else { continue }
                let title = (item["friendly_name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? FrigateFormat.prettyName(name)
                out.append(Shape(kind: kind, name: name, title: title, points: Self.points(item["coordinates"], size: size),
                                 enabled: item["enabled"] as? Bool ?? true, objects: item["objects"] as? [String] ?? [],
                                 inertia: (item["inertia"] as? NSNumber)?.doubleValue ?? 3,
                                 loiteringTime: (item["loitering_time"] as? NSNumber)?.doubleValue ?? 0))
            }
        }
        let motionMask = (cam["motion"] as? [String: Any])?["mask"]
        namedMasks = motionMask == nil || motionMask is [String: Any]
        add(cam["zones"], .zone)
        add((cam["motion"] as? [String: Any])?["mask"], .motionMask)
        add((cam["objects"] as? [String: Any])?["mask"], .objectMask)
        shapes = out
        loaded = true
    }

    /// Frigate's "x1,y1,x2,y2,…": fractions of the picture, or pixels of the detect size in
    /// older configs.
    static func points(_ raw: Any?, size: CGSize) -> [CGPoint] {
        let text = (raw as? String) ?? (raw as? [String])?.joined(separator: ",") ?? ""
        let n = text.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        let pixels = n.contains { $0 > 1.0001 }
        return stride(from: 0, to: n.count - 1, by: 2).map { i in
            pixels ? CGPoint(x: n[i] / size.width, y: n[i + 1] / size.height) : CGPoint(x: n[i], y: n[i + 1])
        }
    }
}

// MARK: - MQTT

/// The MQTT connection, to read (it is set in the config file).
struct MqttView: View {
    let serverID: UUID
    @Environment(AppModel.self) private var model
    @State private var mqtt: [String: Any]?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                ScreenTitle(text: "MQTT", showsBack: true).padding(.top, 8)
                if let mqtt {
                    VStack(spacing: 0) {
                        info(L("Enabled"), (mqtt["enabled"] as? Bool ?? false) ? L("Yes") : L("No"))
                        info(L("Server"), (mqtt["host"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                                .map { host in [host, (mqtt["port"] as? NSNumber).map { "\($0)" }].compactMap { $0 }.joined(separator: ":") }
                                ?? L("Not set"))
                        info(L("Topic prefix"), mqtt["topic_prefix"] as? String ?? "")
                        info(L("Client ID"), mqtt["client_id"] as? String ?? "")
                        info(L("Statistics every"), (mqtt["stats_interval"] as? NSNumber).map { "\($0) s" } ?? "")
                    }
                    .card(radius: 12)
                    Text("Change these in Frigate's config file; they take effect after a restart.")
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                } else {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { mqtt = (try? await model.connection(for: serverID)?.client?.rawConfig())?["mqtt"] as? [String: Any] ?? [:] }
    }

    @ViewBuilder
    private func info(_ title: String, _ value: String) -> some View {
        if !value.isEmpty {
            HStack {
                Text(title).font(.system(size: 15))
                Spacer()
                Text(value).font(.system(size: 14)).foregroundStyle(Theme.textSecondary).lineLimit(1).textSelection(.enabled)
            }
            .padding(.horizontal, 14).padding(.vertical, 11)
        }
    }
}

// MARK: - Camera order

/// The order of the server's cameras, by dragging, saved as each camera's `ui.order`.
struct CameraOrderView: View {
    let serverID: UUID
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var cameras: [CameraConfig] = []
    @State private var saving = false
    @State private var errorMessage: String?

    private var conn: ServerConnection? { model.connection(for: serverID) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(cameras, id: \.name) { cam in
                        Text(cam.displayName).font(.system(size: 15))
                    }
                    .onMove { cameras.move(fromOffsets: $0, toOffset: $1) }
                } footer: {
                    Text("For Live in this app and Frigate's dashboard.")
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle(L("Camera order"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { closePresented(dismiss) } }
                ToolbarItem(placement: .confirmationAction) {
                    Button { Task { await save() } } label: { if saving { ProgressView() } else { Text("Save") } }
                        .disabled(saving)
                }
            }
        }
        .onAppear {
            cameras = (conn?.config?.cameras.values.map { $0 } ?? [])
                .filter { $0.enabledInConfig != false }
                .sorted { ($0.ui?.order ?? 0, $0.name) < ($1.ui?.order ?? 0, $1.name) }
        }
        .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private func save() async {
        guard let conn, let client = conn.client else { return }
        saving = true
        defer { saving = false }
        var data: [String: Any] = [:]
        for (i, cam) in cameras.enumerated() { data[cam.name] = ["ui": ["order": i + 1]] }
        do {
            try await client.setConfig(["cameras": data], requiresRestart: false, updateTopic: nil)
            await conn.refresh(reloadConfig: true)
            closePresented(dismiss)
        } catch { errorMessage = error.localizedDescription }
    }
}

// MARK: - System status

/// How the server is doing: each camera's frames per second, the detectors, the processors and
/// the disks, and Frigate's logs.
struct SystemStatusView: View {
    let serverID: UUID
    @Environment(AppModel.self) private var model
    @State private var logs: [String] = []
    @State private var logService = "frigate"
    @State private var loadingLogs = false
    @State private var logError: String?

    private var conn: ServerConnection? { model.connection(for: serverID) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                ScreenTitle(text: L("System status"), showsBack: true).padding(.top, 8)
                if let conn, let stats = conn.stats {
                    SectionLabel(text: L("Cameras")).padding(.top, 6)
                    VStack(spacing: 0) {
                        let names = conn.cameras.map(\.name).filter { stats.cameras[$0] != nil }
                        ForEach(Array(names.enumerated()), id: \.element) { i, name in
                            if i > 0 { Divider().overlay(Theme.cardBorder) }
                            cameraRow(conn, name, stats.cameras[name]!)
                        }
                    }
                    .card(radius: 12)
                    Text("Camera: frames coming in per second. Detection: frames the detector looks at. Skipped frames mean the detector cannot keep up.")
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)

                    SectionLabel(text: L("Processing")).padding(.top, 10)
                    VStack(spacing: 0) {
                        ForEach(stats.detectors.sorted(by: { $0.key < $1.key }), id: \.key) { name, d in
                            info(L("Detector \(name)"), d.inferenceSpeed.map { "\(String(format: "%.1f", $0)) ms" } ?? "")
                        }
                        if let cpu = stats.cpuPercent { info("CPU", "\(Int(cpu.rounded())) %") }
                        ForEach(stats.gpus.sorted(by: { $0.key < $1.key }), id: \.key) { name, g in
                            info(name, g.gpu ?? "")
                        }
                    }
                    .card(radius: 12)

                    SectionLabel(text: L("Storage")).padding(.top, 10)
                    VStack(spacing: 0) {
                        ForEach((stats.service?.storage ?? [:]).sorted(by: { $0.key < $1.key }), id: \.key) { path, s in
                            info(path.replacingOccurrences(of: "/media/frigate/", with: ""),
                                 s.used.flatMap { u in s.total.map { "\(ServerSettingsView.size(u)) / \(ServerSettingsView.size($0))" } } ?? "")
                        }
                    }
                    .card(radius: 12)
                }

                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        SectionLabel(text: L("Logs"))
                        Text("Newest first").font(.system(size: 11)).foregroundStyle(Theme.textTertiary)
                    }
                    Spacer()
                    Picker("", selection: $logService) {
                        Text("Frigate").tag("frigate")
                        Text("go2rtc").tag("go2rtc")
                        Text("nginx").tag("nginx")
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 230)
                }
                .padding(.top, 10)
                Group {
                    if loadingLogs && logs.isEmpty {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 80)
                    } else if let logError {
                        Text(logError).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                    } else {
                        VStack(alignment: .leading, spacing: 2) {
                            ForEach(Array(logs.enumerated()), id: \.offset) { _, line in
                                Text(line)
                                    .font(.system(size: 10.5).monospaced())
                                    .foregroundStyle(line.contains("ERROR") || line.contains("error") ? Theme.destructive
                                                     : line.contains("WARN") ? Theme.warning : Theme.textTertiary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                        .textSelection(.enabled)
                    }
                }
                .padding(12)
                .card(radius: 12)
                Text("The last 200 lines, newest at the bottom.").font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .refreshable { await conn?.refresh(); await loadLogs() }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task(id: logService) { logs = []; await loadLogs() }
        .task {
            // Stats every five seconds while open, as Frigate's system page does.
            while !Task.isCancelled {
                await conn?.refresh()
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }

    private func cameraRow(_ conn: ServerConnection, _ name: String, _ s: FrigateStats.CameraStats) -> some View {
        let title = conn.config?.cameras[name]?.displayName ?? FrigateFormat.prettyName(name)
        let down = (s.cameraFps ?? 0) == 0
        let skipping = (s.skippedFps ?? 0) > 0.5
        return HStack(spacing: 10) {
            StatusDot(color: down ? Theme.offline : skipping ? Theme.warning : Theme.online, size: 7)
            Text(title).font(.system(size: 15)).lineLimit(1)
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 1) {
                Text(L("\(fps(s.cameraFps)) fps")).font(.system(size: 13, weight: .semibold).monospacedDigit())
                Text(L("detection \(fps(s.detectionFps)) · skipped \(fps(s.skippedFps))"))
                    .font(.system(size: 11).monospacedDigit()).foregroundStyle(skipping ? Theme.warning : Theme.textSecondary)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 9)
    }

    private func fps(_ v: Double?) -> String { String(format: "%.1f", v ?? 0) }

    @ViewBuilder
    private func info(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).font(.system(size: 15)).lineLimit(1)
            Spacer(minLength: 8)
            Text(value).font(.system(size: 14).monospacedDigit()).foregroundStyle(Theme.textSecondary).lineLimit(1)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
    }

    private struct LogPage: Decodable { var totalLines: Int?; var lines: [String]? }

    private func loadLogs() async {
        guard let client = conn?.client else { return }
        loadingLogs = true
        defer { loadingLogs = false }
        do {
            let page = try await client.get(LogPage.self, "api/logs/\(logService)")
            // Newest first: the page opens on what just happened.
            logs = Array((page.lines ?? []).suffix(200).reversed())
            logError = nil
        } catch FrigateError.unauthorized {
            logError = L("Only an admin can read the logs.")
        } catch {
            logError = error.localizedDescription
        }
    }
}
