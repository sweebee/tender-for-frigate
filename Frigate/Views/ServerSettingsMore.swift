import SwiftUI

// Profiles, users, a camera's zones and masks, and MQTT, in a server's settings.

// MARK: - Profiles

/// Frigate 0.18's profiles: switch the whole server to "Home", "Away", … in one tap.
struct ProfilesView: View {
    let serverID: UUID
    @Environment(AppModel.self) private var model
    @State private var profiles: [(name: String, title: String)] = []
    @State private var active: String?
    @State private var loaded = false
    @State private var switching: String?
    @State private var webInterface: ServersView.WebTarget?

    private var conn: ServerConnection? { model.connection(for: serverID) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                ScreenTitle(text: L("Profiles"), showsBack: true).padding(.top, 8)
                Text("A profile changes several cameras at once, for instance the indoor cameras off while you are home.")
                    .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                if !loaded {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                } else if profiles.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("This server has no profiles yet.").font(.system(size: 15, weight: .semibold))
                        Text("Profiles are made in Frigate's web interface (Settings → Profiles); here you switch between them.")
                            .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                        if let web = conn?.client?.baseURL {
                            Button { webInterface = ServersView.WebTarget(url: web, title: conn?.server.name ?? "") } label: {
                                Label("Open the web interface", systemImage: "safari").font(.system(size: 14, weight: .semibold))
                            }
                        }
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .card(radius: 12)
                } else {
                    VStack(spacing: 0) {
                        profileRow(name: nil, title: L("No profile"), subtitle: L("The configuration as it is"))
                        ForEach(profiles, id: \.name) { p in
                            Divider().overlay(Theme.cardBorder)
                            profileRow(name: p.name, title: p.title, subtitle: nil)
                        }
                    }
                    .card(radius: 12)
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .refreshable { await load() }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .fullScreenCover(item: $webInterface) { WebInterfaceView(url: $0.url, title: $0.title) }
    }

    private func profileRow(name: String?, title: String, subtitle: String?) -> some View {
        let isActive = active == name
        return Button { activate(name) } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 15, weight: isActive ? .semibold : .regular)).foregroundStyle(Theme.text)
                    if let subtitle { Text(subtitle).font(.system(size: 12)).foregroundStyle(Theme.textSecondary) }
                }
                Spacer()
                if switching == (name ?? "") {
                    ProgressView().controlSize(.small)
                } else if isActive {
                    Image(systemName: "checkmark").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.accent)
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(switching != nil)
    }

    private func activate(_ name: String?) {
        guard let conn, name != active else { return }
        switching = name ?? ""
        conn.command("profile/set", name ?? "none")
        Task {
            // Frigate applies the profile to every camera; ask again after a moment.
            try? await Task.sleep(for: .seconds(1.5))
            await load()
            await conn.refresh(reloadConfig: true)
            switching = nil
        }
    }

    private func load() async {
        guard let client = conn?.client else { return }
        if let p = try? await client.profiles() {
            profiles = p.profiles
            active = p.active
        }
        loaded = true
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

    private var conn: ServerConnection? { model.connection(for: serverID) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    ScreenTitle(text: L("Users"), showsBack: true)
                    Spacer()
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
        Task {
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

    struct Shape: Identifiable {
        enum Kind: String { case zone, motionMask = "motion_mask", objectMask = "object_mask" }
        let kind: Kind
        let name: String
        let title: String
        /// Corners, 0…1 across the picture.
        let points: [CGPoint]
        var enabled: Bool
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
                ScreenTitle(text: L("Zones and masks"), showsBack: true).padding(.top, 8)
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
                    Text("This camera has no zones or masks.").font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
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
    }

    @ViewBuilder
    private func group(_ title: String, _ kind: Shape.Kind) -> some View {
        let list = shapes.filter { $0.kind == kind }
        if !list.isEmpty {
            SectionLabel(text: title).padding(.top, 10)
            VStack(spacing: 0) {
                ForEach(Array(list.enumerated()), id: \.element.id) { i, s in
                    if i > 0 { Divider().overlay(Theme.cardBorder) }
                    Toggle(isOn: Binding(get: { s.enabled }, set: { set(s, $0) })) {
                        HStack(spacing: 10) {
                            RoundedRectangle(cornerRadius: 3).fill(s.color.opacity(0.7)).frame(width: 14, height: 14)
                            Text(s.title).font(.system(size: 15))
                        }
                    }
                    .tint(Theme.online)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                }
            }
            .card(radius: 12)
        }
    }

    private func set(_ shape: Shape, _ on: Bool) {
        guard let conn, let i = shapes.firstIndex(where: { $0.id == shape.id }) else { return }
        shapes[i].enabled = on
        conn.command("\(camera)/\(shape.kind.rawValue)/\(shape.name)/set", on ? "ON" : "OFF")
    }

    private func load() async {
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
                                 enabled: item["enabled"] as? Bool ?? true))
            }
        }
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
                        info(L("Server"), [mqtt["host"] as? String, (mqtt["port"] as? NSNumber).map { "\($0)" }].compactMap { $0 }.joined(separator: ":"))
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
