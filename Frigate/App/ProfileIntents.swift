import AppIntents
import Foundation

// "Set Frigate profile" for Shortcuts and automations: switch a server to "Home" when you arrive,
// "Away" when you leave, "Night" at 23:00. Runs without opening the app; it signs in to the server
// the way the app does and uses Frigate's own call for it (PUT /api/camera/*/set/profile).

/// A profile on one of the saved servers, or "No profile" for that server.
struct ProfileEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Frigate profile"
    static let defaultQuery = ProfileQuery()

    /// "<server id>|<profile name>", with "none" for no profile.
    var id: String
    var title: String
    var serverName: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", subtitle: "\(serverName)")
    }

    var serverID: UUID? { UUID(uuidString: String(id.split(separator: "|").first ?? "")) }
    /// nil: no profile.
    var profileName: String? {
        let name = id.split(separator: "|", maxSplits: 1).dropFirst().first.map(String.init) ?? "none"
        return name == "none" ? nil : name
    }
}

struct ProfileQuery: EntityQuery {
    func entities(for identifiers: [ProfileEntity.ID]) async throws -> [ProfileEntity] {
        let all = await IntentServers.allProfiles()
        return identifiers.map { id in
            all.first { $0.id == id } ?? IntentServers.fallback(id)
        }
    }

    func suggestedEntities() async throws -> [ProfileEntity] {
        await IntentServers.allProfiles()
    }
}

struct SetProfileIntent: AppIntent {
    static let title: LocalizedStringResource = "Set Frigate profile"
    static let description = IntentDescription("Switches a Frigate server to one of its profiles, for instance in an automation when you arrive home or leave.")

    @Parameter(title: "Profile")
    var profile: ProfileEntity

    static var parameterSummary: some ParameterSummary {
        Summary("Set Frigate profile to \(\.$profile)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let serverID = profile.serverID, let server = IntentServers.saved().first(where: { $0.id == serverID }) else {
            throw IntentError.message(String(localized: "This server is no longer in the app."))
        }
        let client = try await IntentServers.connect(server)
        try await client.setProfile(profile.profileName)
        return .result(dialog: "\(server.name): \(profile.title)")
    }
}

struct TenderShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: SetProfileIntent(),
                    phrases: ["Set \(.applicationName) profile", "Change the \(.applicationName) profile"],
                    shortTitle: "Set Frigate profile",
                    systemImageName: "person.2.crop.square.stack")
    }
}

enum IntentError: Error, CustomLocalizedStringResourceConvertible {
    case message(String)
    var localizedStringResource: LocalizedStringResource {
        switch self { case .message(let m): return "\(m)" }
    }
}

/// The saved servers, reached outside the app's own connections.
enum IntentServers {
    static func saved() -> [ServerConfig] {
        guard let data = UserDefaults.standard.data(forKey: "servers"),
              let servers = try? JSONDecoder().decode([ServerConfig].self, from: data) else { return [] }
        return servers
    }

    /// Signed in to the server at its home address, else its remote one.
    static func connect(_ server: ServerConfig) async throws -> FrigateClient {
        var urls: [URL] = []
        if let u = ServerConfig.normalizedURL(server.url) { urls.append(u) }
        if let r = server.remoteURL, let u = ServerConfig.normalizedURL(r) { urls.append(u) }
        var last: Error = FrigateError.notFrigate
        for url in urls {
            let client = FrigateClient(baseURL: url)
            do {
                client.version = try await ServerConnection.authenticate(client: client, server: server, password: server.password)
                return client
            } catch { last = error }
        }
        throw last
    }

    /// Every server's profiles, "No profile" first; servers that cannot be reached are left out.
    static func allProfiles() async -> [ProfileEntity] {
        await withTaskGroup(of: [ProfileEntity].self) { group in
            for server in saved() {
                group.addTask {
                    guard let client = try? await connect(server), let p = try? await client.profiles(),
                          !p.profiles.isEmpty else { return [] }
                    return [ProfileEntity(id: "\(server.id.uuidString)|none", title: String(localized: "No profile"), serverName: server.name)]
                        + p.profiles.map { ProfileEntity(id: "\(server.id.uuidString)|\($0.name)", title: $0.title, serverName: server.name) }
                }
            }
            var all: [ProfileEntity] = []
            for await part in group { all += part }
            return all.sorted { ($0.serverName, $0.id.hasSuffix("|none") ? 0 : 1, $0.title) < ($1.serverName, $1.id.hasSuffix("|none") ? 0 : 1, $1.title) }
        }
    }

    /// An entity for a saved id when the server cannot be asked right now.
    static func fallback(_ id: String) -> ProfileEntity {
        let parts = id.split(separator: "|", maxSplits: 1).map(String.init)
        let server = saved().first { $0.id.uuidString == parts.first }
        let name = parts.count > 1 ? parts[1] : "none"
        return ProfileEntity(id: id, title: name == "none" ? String(localized: "No profile") : FrigateFormat.prettyName(name),
                             serverName: server?.name ?? "")
    }
}
