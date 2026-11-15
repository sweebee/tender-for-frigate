import SwiftUI

// Changing a camera's IP address (or its login) in one place. Frigate keeps the address in the
// camera's go2rtc streams and, for cameras without go2rtc, in its ffmpeg inputs; its own UI makes
// you edit each of them. Here the app finds every one on the camera's host and changes them together.

/// Where a camera's address is written, read from the config with the real (unmasked) addresses.
struct CameraAddressPlan {
    let camera: String
    /// The camera's host: the one most of its addresses use.
    let host: String
    let user: String?
    /// The addresses carry a login (so a new username or password can go in).
    let hasLogin: Bool
    /// Every address on `host`, titled by its go2rtc stream or "Stream n".
    let targets: [(title: String, url: String)]
    /// Other cameras that read the same go2rtc streams.
    let sharedWith: [String]
    /// An input on this host gets its password from a {FRIGATE_*} variable; Frigate only shows it filled in.
    let usesVariable: Bool
    private let streams: [String: [String]]
    private let inputs: [[String: Any]]

    /// nil when the camera has no address of its own (or Frigate is older than 0.17).
    static func load(_ client: FrigateClient, camera: String) async throws -> CameraAddressPlan? {
        async let rawRequest = client.data("api/config/raw_paths")
        async let configRequest = client.rawConfig()
        async let yamlRequest = client.rawConfigText()
        let raw = (try JSONSerialization.jsonObject(with: try await rawRequest) as? [String: Any]) ?? [:]
        let config = try await configRequest
        let yaml = (try? await yamlRequest) ?? ""

        let cameras = config["cameras"] as? [String: Any] ?? [:]
        let cam = cameras[camera] as? [String: Any] ?? [:]
        let rawInputs = (((raw["cameras"] as? [String: Any])?[camera] as? [String: Any])?["ffmpeg"] as? [String: Any])?["inputs"] as? [[String: Any]] ?? []
        let shownInputs = (cam["ffmpeg"] as? [String: Any])?["inputs"] as? [[String: Any]] ?? []

        // The inputs as written back: the real address and roles, plus the input's own ffmpeg arguments.
        var inputs: [[String: Any]] = []
        for (i, r) in rawInputs.enumerated() {
            var input: [String: Any] = [:]
            if i < shownInputs.count {
                for key in ["global_args", "hwaccel_args", "input_args"] {
                    if let v = shownInputs[i][key] as? String, !v.isEmpty { input[key] = v }
                    if let v = shownInputs[i][key] as? [Any], !v.isEmpty { input[key] = v }
                }
            }
            input["path"] = r["path"]
            input["roles"] = r["roles"]
            inputs.append(input)
        }

        func streamNames(_ cam: [String: Any], inputs: [[String: Any]]) -> [String] {
            var names: [String] = []
            for input in inputs {
                if let path = input["path"] as? String, let name = StreamAddress.restreamName(path), !names.contains(name) { names.append(name) }
            }
            let live = ((cam["live"] as? [String: Any])?["streams"] as? [String: Any] ?? [:]).values.compactMap { $0 as? String }.sorted()
            for name in live where !names.contains(name) { names.append(name) }
            return names
        }
        let names = streamNames(cam, inputs: inputs)
        let defined = (raw["go2rtc"] as? [String: Any])?["streams"] as? [String: Any] ?? [:]
        var streams: [String: [String]] = [:]
        for name in names {
            // Only plain addresses; a stream with a map source is left alone.
            if let s = defined[name] as? String { streams[name] = [s] }
            else if let list = defined[name] as? [Any], let s = list as? [String] { streams[name] = s }
        }

        var all: [(title: String, url: String)] = []
        for name in names {
            for s in streams[name] ?? [] where StreamAddress.host(s) != nil && !StreamAddress.isLocal(s) { all.append((name, s)) }
        }
        for (i, input) in inputs.enumerated() {
            guard let path = input["path"] as? String, StreamAddress.restreamName(path) == nil,
                  StreamAddress.host(path) != nil, !StreamAddress.isLocal(path) else { continue }
            all.append((inputs.count > 1 ? L("Stream \(i + 1)") : L("Camera"), path))
        }
        let counts = all.reduce(into: [String: Int]()) { $0[StreamAddress.host($1.url) ?? "", default: 0] += 1 }
        guard let host = counts.max(by: { ($0.value, $1.key) < ($1.value, $0.key) })?.key else { return nil }
        let targets = all.filter { StreamAddress.host($0.url) == host }

        let mine = Set(streams.keys)
        var shared: [String] = []
        for (name, other) in cameras where name != camera {
            guard let other = other as? [String: Any] else { continue }
            let otherInputs = (other["ffmpeg"] as? [String: Any])?["inputs"] as? [[String: Any]] ?? []
            if !mine.isDisjoint(with: streamNames(other, inputs: otherInputs)) {
                shared.append((other["friendly_name"] as? String) ?? FrigateFormat.prettyName(name))
            }
        }

        // Frigate fills {FRIGATE_*} variables in camera inputs (not in go2rtc streams); such an
        // address is not in the file as Frigate shows it, and writing it back would put the password in.
        let usesVariable = targets.contains { t in
            !names.contains(t.title) && StreamAddress.password(t.url) != nil && !yaml.contains(t.url)
        }

        return CameraAddressPlan(camera: camera, host: host,
                                 user: targets.lazy.compactMap { StreamAddress.user($0.url) }.first,
                                 hasLogin: targets.contains { StreamAddress.user($0.url) != nil || StreamAddress.password($0.url) != nil },
                                 targets: targets, sharedWith: shared.sorted(), usesVariable: usesVariable,
                                 streams: streams, inputs: inputs)
    }

    private func change(_ url: String, host newHost: String, user: String?, password: String?) -> String {
        guard StreamAddress.host(url) == host else { return url }
        return StreamAddress.replacingLogin(StreamAddress.replacingHost(url, with: newHost), user: user, password: password)
    }

    /// The addresses as they become, in the order of `targets`.
    func newAddresses(host: String, user: String?, password: String?) -> [(title: String, url: String)] {
        targets.map { ($0.title, change($0.url, host: host, user: user, password: password)) }
    }

    /// The config change for /api/config/set: the go2rtc streams that change, and the camera's
    /// inputs (Frigate takes the list as a whole) when one of them has the address itself.
    func configChange(host: String, user: String?, password: String?) -> [String: Any] {
        var data: [String: Any] = [:]
        var changed: [String: Any] = [:]
        for (name, sources) in streams {
            let new = sources.map { change($0, host: host, user: user, password: password) }
            if new != sources { changed[name] = new }
        }
        if !changed.isEmpty { data["go2rtc"] = ["streams": changed] }
        var inputsChanged = false
        let newInputs = inputs.map { input -> [String: Any] in
            var input = input
            if let path = input["path"] as? String, StreamAddress.restreamName(path) == nil {
                let new = change(path, host: host, user: user, password: password)
                if new != path { input["path"] = new; inputsChanged = true }
            }
            return input
        }
        if inputsChanged { data["cameras"] = [camera: ["ffmpeg": ["inputs": newInputs]]] }
        return data
    }
}

/// A new address and login for the camera: tested with ffprobe on the server, then saved and
/// Frigate restarted (go2rtc reads its streams only when it starts).
struct CameraAddressSheet: View {
    let serverID: UUID
    let plan: CameraAddressPlan
    let onSaved: () -> Void

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var host: String
    @State private var user: String
    @State private var password = ""
    @State private var results: [Int: Result<StreamProbe, Error>] = [:]
    @State private var testing: Set<Int> = []
    @State private var phase: Phase = .editing
    @State private var failed = false
    @State private var errorMessage: String?
    @FocusState private var focus: Field?

    enum Phase { case editing, testing, saving, restarting }
    enum Field: Hashable { case host, user, password }

    init(serverID: UUID, plan: CameraAddressPlan, onSaved: @escaping () -> Void) {
        self.serverID = serverID
        self.plan = plan
        self.onSaved = onSaved
        _host = State(initialValue: plan.host)
        _user = State(initialValue: plan.user ?? "")
    }

    private var conn: ServerConnection? { model.connection(for: serverID) }
    private var newHost: String { host.trimmingCharacters(in: .whitespaces) }
    private var newUser: String? {
        let u = user.trimmingCharacters(in: .whitespaces)
        return u != (plan.user ?? "") && !u.isEmpty ? u : nil
    }
    private var newPassword: String? { password.isEmpty ? nil : password }
    /// A first login needs a username; a password alone has nowhere to go.
    private var loginComplete: Bool { plan.hasLogin || newPassword == nil || newUser != nil }
    private var hasChange: Bool { newHost != plan.host || newUser != nil || newPassword != nil }
    private var busy: Bool { phase != .editing }
    private var canSave: Bool { hasChange && !newHost.isEmpty && !plan.usesVariable && loginComplete }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                VStack(spacing: 0) {
                    row(L("IP address or name"), id: .host) {
                        TextField(focus == .host ? "" : "192.168.1.50", text: $host)
                            .keyboardType(.URL)
                    }
                    Divider().overlay(Theme.cardBorder)
                    row(L("Username"), id: .user) {
                        TextField("", text: $user, prompt: plan.hasLogin ? nil : Text("None").foregroundStyle(Theme.textTertiary))
                    }
                    Divider().overlay(Theme.cardBorder)
                    row(L("Password"), id: .password) {
                        RevealableField(text: $password, prompt: Text(plan.hasLogin ? "Unchanged" : "None").foregroundStyle(Theme.textTertiary))
                    }
                }
                .card(radius: 12)
                .disabled(busy)
                note(L("Changes the address in \(ListFormatter.localizedString(byJoining: plan.targets.map(\.title).uniqued())).")
                     + (plan.hasLogin ? " " + L("Leave the password empty to keep it.") : ""))
                if !plan.sharedWith.isEmpty {
                    note(L("\(ListFormatter.localizedString(byJoining: plan.sharedWith)) uses the same streams and changes along."), color: Theme.warning)
                }
                if plan.usesVariable {
                    note(L("This camera's password comes from a variable ({FRIGATE_…}). Change the address in Configuration (YAML)."), color: Theme.warning)
                }
                if !results.isEmpty || !testing.isEmpty { resultsCard.padding(.top, 6) }
                if let errorMessage {
                    Text(errorMessage).font(.system(size: 12)).foregroundStyle(Theme.destructive).padding(.horizontal, 4)
                }
                Button { Task { await save(force: failed) } } label: {
                    HStack(spacing: 8) {
                        if busy { ProgressView().tint(Theme.onColor).controlSize(.small) }
                        Text(buttonTitle)
                    }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(canSave || busy ? Theme.onColor : Theme.textTertiary)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(canSave || busy ? (failed ? Theme.warning : Theme.accent) : Theme.control, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .disabled(busy || !canSave)
                .padding(.top, 8)
                note(failed ? L("Not every stream answered at the new address. Save anyway if the camera is not reachable yet, for example because it still has to move.")
                            : L("Frigate restarts to use the new address; live view and recording stop for a moment."))
            }
            .padding(16)
            .readableWidth()
        }
        .screenBackground()
        .navigationTitle(L("Camera address"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { closePresented(dismiss) }.disabled(busy).plainBarButton() } }
        .interactiveDismissDisabled(busy)
        .onChange(of: [host, user, password]) { failed = false; results = [:] }
    }

    private var buttonTitle: String {
        switch phase {
        case .editing: return failed ? L("Save anyway") : L("Test and save")
        case .testing: return L("Testing…")
        case .saving: return L("Saving…")
        case .restarting: return L("Restarting…")
        }
    }

    private var resultsCard: some View {
        let addresses = plan.newAddresses(host: newHost, user: newUser, password: newPassword)
        return VStack(spacing: 0) {
            ForEach(Array(addresses.enumerated()), id: \.offset) { i, target in
                if i > 0 { Divider().overlay(Theme.cardBorder) }
                HStack(alignment: .top, spacing: 10) {
                    Group {
                        if testing.contains(i) { ProgressView().controlSize(.small) }
                        else if case .success = results[i] { Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.online) }
                        else if case .failure = results[i] { Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.destructive) }
                        else { Image(systemName: "minus.circle").foregroundStyle(Theme.textTertiary) }
                    }
                    .font(.system(size: 16)).frame(width: 20)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(target.title).font(.system(size: 14, weight: .semibold))
                        Text(StreamAddress.masked(target.url)).font(.system(size: 11).monospaced()).foregroundStyle(Theme.textSecondary)
                            .lineLimit(1).truncationMode(.middle)
                        switch results[i] {
                        case .success(let probe): Text(probe.summary).font(.system(size: 12)).foregroundStyle(Theme.online)
                        case .failure(let error): Text(error.localizedDescription).font(.system(size: 12)).foregroundStyle(Theme.destructive)
                        case nil:
                            if !testing.contains(i) && !StreamAddress.canProbe(target.url) {
                                Text("Not tested").font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                            }
                        }
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
            }
        }
        .card(radius: 12)
    }

    private func save(force: Bool) async {
        guard let conn, let client = conn.client else { return }
        focus = nil
        errorMessage = nil
        if !force {
            phase = .testing
            results = [:]
            let addresses = plan.newAddresses(host: newHost, user: newUser, password: newPassword)
            testing = Set(addresses.indices.filter { StreamAddress.canProbe(addresses[$0].url) })
            await withTaskGroup(of: (Int, Result<StreamProbe, Error>).self) { group in
                for i in testing {
                    let url = addresses[i].url
                    group.addTask {
                        do { return (i, .success(try await client.probeStream(url))) } catch { return (i, .failure(error)) }
                    }
                }
                for await (i, result) in group {
                    results[i] = result
                    testing.remove(i)
                }
            }
            if results.values.contains(where: { if case .failure = $0 { return true } else { return false } }) {
                failed = true
                phase = .editing
                return
            }
        }
        phase = .saving
        do {
            try await client.setConfig(plan.configChange(host: newHost, user: newUser, password: newPassword),
                                       requiresRestart: true, updateTopic: nil)
            phase = .restarting
            try await client.restart()
            try? await Task.sleep(for: .seconds(5))
            for _ in 0..<40 {
                if (try? await client.version(timeout: 3)) != nil { break }
                try? await Task.sleep(for: .seconds(3))
            }
            await conn.refresh(reloadConfig: true)
            onSaved()
            closePresented(dismiss)
        } catch {
            errorMessage = error.localizedDescription
            phase = .editing
        }
    }

    private func row<F: View>(_ title: String, id: Field, @ViewBuilder field: () -> F) -> some View {
        HStack {
            Text(title).font(.system(size: 15)).foregroundStyle(focus == id ? Theme.accent : Theme.text).formLabel()
            field()
                .multilineTextAlignment(formFieldAlignment).font(.system(size: 15))
                .tint(Theme.accent)
                .autocorrectionDisabled().textInputAutocapitalization(.never)
                .focused($focus, equals: id)
                .focusEffectDisabled()
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
        .contentShape(Rectangle())
        .onTapGesture { focus = id }
        .modifier(FocusRow(on: focus == id))
    }

    private func note(_ text: String, color: Color = Theme.textSecondary) -> some View {
        Text(text).font(.system(size: 12)).foregroundStyle(color).padding(.horizontal, 4)
    }
}

private extension Array where Element == String {
    func uniqued() -> [String] { reduce(into: []) { if !$0.contains($1) { $0.append($1) } } }
}
