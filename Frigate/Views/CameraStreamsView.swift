import SwiftUI

// A camera's streams in one list: the go2rtc streams it reads (and those it only shows in live
// view) and the inputs that open the camera directly. A stream can be renamed (its go2rtc
// stream, the camera's restream address and its live view entries change along), get another
// address, other roles, be removed or added. Changes are saved together and Frigate restarts,
// since go2rtc reads its streams only when it starts.

/// One stream as the Streams page shows it.
struct CameraStream: Identifiable, Equatable {
    var id = UUID()
    /// The go2rtc stream it goes through; nil for an input that opens the camera itself.
    var name: String?
    /// go2rtc's sources for it, or the input's own address.
    var sources: [String]
    /// Detect, record, audio. Empty for a go2rtc stream Frigate does not read (live view only).
    var roles: [String] = []
    /// Its place among the camera's inputs as loaded, for the input's own ffmpeg arguments.
    var inputIndex: Int?
    /// What follows the name in the camera's restream address ("?video=copy").
    var restreamSuffix = ""
    /// Live view entries that show it ("Main").
    var liveLabels: [String] = []
    /// Other cameras that read the same go2rtc stream.
    var sharedWith: [String] = []
    /// Its address cannot change here: a map source in go2rtc, or a password from a {FRIGATE_*} variable.
    var locked = false

    /// Frigate reads it as one of the camera's inputs.
    var isInput: Bool { name == nil || !roles.isEmpty }
}

struct CameraStreamsView: View {
    let serverID: UUID
    let camera: String
    @Environment(AppModel.self) private var model

    @State private var saved: [CameraStream] = []
    @State private var streams: [CameraStream] = []
    @State private var extras: [Int: [String: Any]] = [:]
    /// Every go2rtc stream on the server, so a new name does not take one.
    @State private var defined: Set<String> = []
    /// "rtsp://user:password@host:554/": the start of a new stream's address.
    @State private var prefix: String?
    @State private var probes: [UUID: Result<StreamProbe, Error>] = [:]
    @State private var loading = true
    @State private var loadError: String?
    @State private var editing: Editing?
    @State private var phase: Phase = .editing
    @State private var errorMessage: String?
    @State private var notice: String?

    enum Phase { case editing, saving, restarting }
    struct Editing: Identifiable { let id: UUID; let isNew: Bool }

    private var conn: ServerConnection? { model.connection(for: serverID) }
    private var busy: Bool { phase != .editing }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                ScreenTitle(text: L("Streams"), showsBack: true).padding(.top, 8)
                if loading && saved.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
                } else if let loadError {
                    Text(loadError).font(.system(size: 14)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                } else {
                    if let notice {
                        Label(notice, systemImage: "checkmark.circle.fill")
                            .font(.system(size: 13)).foregroundStyle(Theme.online).padding(.horizontal, 4)
                    }
                    VStack(spacing: 0) {
                        ForEach(Array(streams.enumerated()), id: \.element.id) { i, stream in
                            if i > 0 { Divider().overlay(Theme.cardBorder) }
                            row(stream, number: i + 1)
                        }
                        if !streams.isEmpty { Divider().overlay(Theme.cardBorder) }
                        Button { editing = Editing(id: UUID(), isNew: true) } label: {
                            Label("Add stream", systemImage: "plus")
                                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.accent)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 14).padding(.vertical, 12).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    .card(radius: 12)
                    .disabled(busy)
                    Text("Detection, recording and audio each take one stream. Tap a stream to change its name, address or roles.")
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                    if streams != saved || busy { changesCard.padding(.top, 10) }
                }
            }
            .padding(.horizontal, 16).readableWidth()
            .padding(.bottom, 24)
        }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { if saved.isEmpty { await load() } }
        .sheet(item: $editing) { e in
            NavigationStack { editSheet(e) }
                .environment(model)
                .largeSheetOnWideScreens()
        }
    }

    // MARK: Rows

    private func title(_ s: CameraStream, number: Int) -> String { s.name ?? L("Stream \(number)") }

    private func row(_ s: CameraStream, number: Int) -> some View {
        let original = saved.first { $0.id == s.id }
        let changed = original != s
        return Button { editing = Editing(id: s.id, isNew: false) } label: {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(title(s, number: number)).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                        if original == nil {
                            Text("· new").font(.system(size: 12)).foregroundStyle(Theme.warning)
                        } else if changed {
                            Text("· changed").font(.system(size: 12)).foregroundStyle(Theme.warning)
                        }
                    }
                    Text(StreamAddress.masked(s.sources.first ?? "")).font(.system(size: 11).monospaced())
                        .foregroundStyle(Theme.textSecondary).lineLimit(1).truncationMode(.middle)
                    if !changed {
                        switch probes[s.id] {
                        case .success(let p): Text(p.summary).font(.system(size: 12)).foregroundStyle(Theme.online)
                        case .failure(let e): Text(e.localizedDescription).font(.system(size: 12)).foregroundStyle(Theme.destructive).lineLimit(2)
                        case nil: EmptyView()
                        }
                    }
                    FlowChips(chips: chips(s))
                        .padding(.top, 3)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.textTertiary)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func chips(_ s: CameraStream) -> [(String, Bool)] {
        var out = s.roles.map { (Self.roleTitle($0), true) }
        if s.name != nil { out.append(("go2rtc", false)) }
        out += s.liveLabels.map { (L("Live: \($0)"), false) }
        if !s.isInput && s.liveLabels.isEmpty { out.append((L("Not used"), false)) }
        return out
    }

    static func roleTitle(_ role: String) -> String {
        switch role {
        case "detect": return L("Detection")
        case "record": return L("Record")
        case "audio": return L("Audio")
        default: return role
        }
    }

    // MARK: Changes

    private var changeLines: [String] {
        var out: [String] = []
        for (i, old) in saved.enumerated() {
            guard let s = streams.first(where: { $0.id == old.id }) else {
                out.append(L("\(title(old, number: i + 1)) removed")); continue
            }
            if let a = old.name, let b = s.name, a != b { out.append(L("\(a) is now called \(b)")) }
            if old.sources != s.sources { out.append(L("\(title(s, number: i + 1)): address changed")) }
            if old.roles != s.roles { out.append(L("\(title(s, number: i + 1)): roles changed")) }
        }
        for (i, s) in streams.enumerated() where !saved.contains(where: { $0.id == s.id }) {
            out.append(L("\(title(s, number: i + 1)) added"))
        }
        return out
    }

    private func inputs(_ list: [CameraStream]) -> [[String: Any]] {
        list.filter(\.isInput).map { s in
            var input = s.inputIndex.flatMap { extras[$0] } ?? (s.name != nil ? ["input_args": "preset-rtsp-restream"] : [:])
            input["path"] = s.name.map { "rtsp://127.0.0.1:8554/\($0)\(s.restreamSuffix)" } ?? s.sources.first ?? ""
            input["roles"] = s.roles
            return input
        }
    }

    private func inputKey(_ list: [CameraStream]) -> [String] {
        inputs(list).map { "\($0["path"] as? String ?? "") \(($0["roles"] as? [String] ?? []).joined(separator: ","))" }
    }

    private var problem: String? {
        let readers = streams.filter(\.isInput)
        if readers.isEmpty { return L("The camera needs at least one stream with a role.") }
        if readers.filter({ $0.roles.contains("detect") }).count != 1 { return L("One stream needs Detection.") }
        if streams.contains(where: { $0.name == nil && $0.roles.isEmpty }) { return L("A stream without go2rtc needs a role.") }
        if inputKey(streams) != inputKey(saved), streams.contains(where: { $0.name == nil && $0.locked }) {
            return L("A stream's password comes from a variable ({FRIGATE_…}). Change these streams in Configuration (YAML).")
        }
        return nil
    }

    /// The change for /api/config/set: go2rtc streams, the camera's inputs (as a whole), its live view entries.
    private var configChange: [String: Any] {
        var go2rtc: [String: Any] = [:]
        var live: [String: Any] = [:]
        for old in saved {
            let now = streams.first { $0.id == old.id }
            if let name = old.name {
                if let now, let newName = now.name {
                    if newName != name {
                        if old.sharedWith.isEmpty { go2rtc[name] = "" }
                        go2rtc[newName] = now.sources
                        for label in old.liveLabels { live[label] = newName }
                    } else if now.sources != old.sources, !old.locked {
                        go2rtc[name] = now.sources
                    }
                } else if now == nil {
                    // Removed: its go2rtc stream goes too, unless another camera reads it.
                    if old.sharedWith.isEmpty { go2rtc[name] = "" }
                    for label in old.liveLabels { live[label] = "" }
                }
            }
        }
        for s in streams where !saved.contains(where: { $0.id == s.id }) {
            guard let name = s.name else { continue }
            go2rtc[name] = s.sources
            // Without a role it is there for live view.
            if !s.isInput { live[name] = name }
        }
        var data: [String: Any] = [:]
        if !go2rtc.isEmpty { data["go2rtc"] = ["streams": go2rtc] }
        var cam: [String: Any] = [:]
        if inputKey(streams) != inputKey(saved) { cam["ffmpeg"] = ["inputs": inputs(streams)] }
        if !live.isEmpty { cam["live"] = ["streams": live] }
        if !cam.isEmpty { data["cameras"] = [camera: cam] }
        return data
    }

    private var changesCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(busy ? L("Saving…") : L("Not saved yet")).font(.system(size: 15, weight: .semibold))
            ForEach(changeLines, id: \.self) { line in
                Text("• " + line).font(.system(size: 13)).foregroundStyle(Theme.text)
            }
            Label("go2rtc reads its streams when it starts, so Frigate restarts.", systemImage: "arrow.clockwise")
                .font(.system(size: 12)).foregroundStyle(Theme.warning)
            if let problem {
                Text(problem).font(.system(size: 12)).foregroundStyle(Theme.destructive)
            }
            if let errorMessage {
                Text(errorMessage).font(.system(size: 12)).foregroundStyle(Theme.destructive)
            }
            HStack(spacing: 10) {
                Button("Undo") { streams = saved; errorMessage = nil }
                    .buttonStyle(PromptButtonStyle(primary: false))
                    .disabled(busy)
                Spacer()
                Button { Task { await save() } } label: {
                    HStack(spacing: 6) {
                        if busy { ProgressView().tint(Theme.onColor).controlSize(.small) }
                        Text(phase == .restarting ? L("Restarting…") : phase == .saving ? L("Saving…") : L("Save and restart"))
                    }
                }
                .buttonStyle(PromptButtonStyle(primary: true))
                .disabled(busy || problem != nil)
            }
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .card(radius: 12)
    }

    // MARK: Editing

    @ViewBuilder
    private func editSheet(_ e: Editing) -> some View {
        let existing = streams.first { $0.id == e.id }
        let template = existing ?? CameraStream(id: e.id, name: "", sources: [prefix ?? "rtsp://"])
        // Names other streams already have: on the server, and new or renamed ones here.
        let taken = defined.union(streams.compactMap(\.name))
            .subtracting(saved.first { $0.id == e.id }?.name.map { [$0] } ?? [])
            .subtracting(existing?.name.map { [$0] } ?? [])
        StreamEditSheet(stream: template, isNew: existing == nil, takenNames: taken, client: conn?.client) { updated in
            apply(updated)
        } onDelete: {
            streams.removeAll { $0.id == e.id }
        }
    }

    private func apply(_ updated: CameraStream) {
        // A role sits on one stream only.
        for i in streams.indices where streams[i].id != updated.id {
            streams[i].roles.removeAll { updated.roles.contains($0) }
        }
        if let i = streams.firstIndex(where: { $0.id == updated.id }) { streams[i] = updated } else { streams.append(updated) }
    }

    // MARK: Loading and saving

    private func load() async {
        guard let client = conn?.client else { return }
        loading = true
        defer { loading = false }
        do {
            async let rawRequest = client.data("api/config/raw_paths")
            async let configRequest = client.rawConfig()
            async let yamlRequest = client.rawConfigText()
            let raw = (try JSONSerialization.jsonObject(with: try await rawRequest) as? [String: Any]) ?? [:]
            let config = try await configRequest
            let yaml = (try? await yamlRequest) ?? ""
            let loaded = Self.parse(raw: raw, config: config, yaml: yaml, camera: camera)
            saved = loaded.streams
            streams = loaded.streams
            extras = loaded.extras
            defined = loaded.defined
            prefix = loaded.prefix
            loadError = nil
            probes = [:]
            for s in loaded.streams {
                let url = s.name.map { "rtsp://127.0.0.1:8554/\($0)" } ?? s.sources.first ?? ""
                guard StreamAddress.canProbe(url) else { continue }
                Task {
                    do { probes[s.id] = .success(try await client.probeStream(url)) } catch { probes[s.id] = .failure(error) }
                }
            }
        } catch FrigateError.http(404, _) {
            loadError = L("This needs Frigate 0.17 or later. Change the streams in Configuration (YAML).")
        } catch {
            loadError = error.localizedDescription
        }
    }

    private static func parse(raw: [String: Any], config: [String: Any], yaml: String, camera: String)
        -> (streams: [CameraStream], extras: [Int: [String: Any]], defined: Set<String>, prefix: String?) {
        let cameras = config["cameras"] as? [String: Any] ?? [:]
        let cam = cameras[camera] as? [String: Any] ?? [:]
        let rawInputs = (((raw["cameras"] as? [String: Any])?[camera] as? [String: Any])?["ffmpeg"] as? [String: Any])?["inputs"] as? [[String: Any]] ?? []
        let shownInputs = (cam["ffmpeg"] as? [String: Any])?["inputs"] as? [[String: Any]] ?? []
        let definedStreams = (raw["go2rtc"] as? [String: Any])?["streams"] as? [String: Any] ?? [:]
        let live = ((cam["live"] as? [String: Any])?["streams"] as? [String: Any] ?? [:]).compactMapValues { $0 as? String }

        // Who else reads each go2rtc stream.
        var users: [String: [String]] = [:]
        for (name, other) in cameras where name != camera {
            guard let other = other as? [String: Any] else { continue }
            let title = (other["friendly_name"] as? String) ?? FrigateFormat.prettyName(name)
            var names = Set<String>()
            for input in (other["ffmpeg"] as? [String: Any])?["inputs"] as? [[String: Any]] ?? [] {
                if let p = input["path"] as? String, let n = StreamAddress.restreamName(p) { names.insert(n) }
            }
            for case let n as String in ((other["live"] as? [String: Any])?["streams"] as? [String: Any] ?? [:]).values { names.insert(n) }
            for n in names { users[n, default: []].append(title) }
        }

        func sources(_ v: Any?) -> [String]? {
            if let s = v as? String { return [s] }
            if let list = v as? [Any] { return list as? [String] }
            return nil
        }
        func liveLabels(_ name: String) -> [String] { live.filter { $0.value == name }.map(\.key).sorted() }

        var streams: [CameraStream] = []
        var extras: [Int: [String: Any]] = [:]
        for (i, input) in rawInputs.enumerated() {
            let path = input["path"] as? String ?? ""
            let roles = (input["roles"] as? [String] ?? []).sorted { Self.roleOrder($0) < Self.roleOrder($1) }
            if i < shownInputs.count {
                var e: [String: Any] = [:]
                for key in ["global_args", "hwaccel_args", "input_args"] {
                    if let v = shownInputs[i][key] as? String, !v.isEmpty { e[key] = v }
                    if let v = shownInputs[i][key] as? [Any], !v.isEmpty { e[key] = v }
                }
                extras[i] = e
            }
            if let name = StreamAddress.restreamName(path), definedStreams[name] != nil {
                let src = sources(definedStreams[name])
                let suffix = path.range(of: ":8554/\(name)").map { String(path[$0.upperBound...]) } ?? ""
                streams.append(CameraStream(name: name, sources: src ?? [], roles: roles, inputIndex: i, restreamSuffix: suffix,
                                            liveLabels: liveLabels(name), sharedWith: users[name] ?? [], locked: src == nil))
            } else {
                // Frigate fills {FRIGATE_*} variables in inputs; such an address is not in the file as shown.
                let fromVariable = StreamAddress.password(path) != nil && !yaml.contains(path)
                streams.append(CameraStream(name: nil, sources: [path], roles: roles, inputIndex: i, locked: fromVariable))
            }
        }
        for name in Set(live.values).sorted() where definedStreams[name] != nil && !streams.contains(where: { $0.name == name }) {
            let src = sources(definedStreams[name])
            streams.append(CameraStream(name: name, sources: src ?? [], liveLabels: liveLabels(name),
                                        sharedWith: users[name] ?? [], locked: src == nil))
        }

        // A new stream starts at the camera's own address.
        let camURL = streams.flatMap(\.sources).first { StreamAddress.host($0) != nil && !StreamAddress.isLocal($0) && !$0.contains("{") }
        let prefix = camURL.flatMap { url -> String? in
            guard let scheme = url.range(of: "://") else { return nil }
            let rest = url[scheme.upperBound...]
            let end = rest.firstIndex(of: "/") ?? rest.endIndex
            return String(url[..<end]) + "/"
        }
        return (streams, extras, Set(definedStreams.keys), prefix)
    }

    static func roleOrder(_ role: String) -> Int { ["detect", "record", "audio"].firstIndex(of: role) ?? 9 }

    private func save() async {
        guard let conn, let client = conn.client else { return }
        errorMessage = nil
        notice = nil
        phase = .saving
        do {
            try await client.setConfig(configChange, requiresRestart: true, updateTopic: nil)
            phase = .restarting
            try await client.restart()
            try? await Task.sleep(for: .seconds(5))
            for _ in 0..<40 {
                if (try? await client.version(timeout: 3)) != nil { break }
                try? await Task.sleep(for: .seconds(3))
            }
            await conn.refresh(reloadConfig: true)
            await load()
            notice = L("Saved. Frigate has restarted.")
        } catch {
            errorMessage = error.localizedDescription
        }
        phase = .editing
    }
}

/// Small role and info chips that wrap onto a new line when they do not fit.
private struct FlowChips: View {
    let chips: [(String, Bool)]

    var body: some View {
        FlowLayout(spacing: 5) {
            ForEach(Array(chips.enumerated()), id: \.offset) { _, chip in
                Text(chip.0).font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(chip.1 ? Theme.accent : Theme.textSecondary)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(chip.1 ? Theme.accent.opacity(0.16) : Theme.control, in: Capsule())
            }
        }
    }
}

/// Editing one stream, or adding one: its name, address (password hidden), a test, its roles.
struct StreamEditSheet: View {
    let stream: CameraStream
    let isNew: Bool
    let takenNames: Set<String>
    let client: FrigateClient?
    let onDone: (CameraStream) -> Void
    let onDelete: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var texts: [String]
    @State private var roles: Set<String>
    @State private var viaGo2rtc: Bool
    @State private var testing = false
    @State private var test: Result<StreamProbe, Error>?
    @State private var confirmingDelete = false
    @FocusState private var focus: Int?

    init(stream: CameraStream, isNew: Bool, takenNames: Set<String>, client: FrigateClient?,
         onDone: @escaping (CameraStream) -> Void, onDelete: @escaping () -> Void) {
        self.stream = stream
        self.isNew = isNew
        self.takenNames = takenNames
        self.client = client
        self.onDone = onDone
        self.onDelete = onDelete
        _name = State(initialValue: stream.name ?? "")
        _texts = State(initialValue: stream.sources.map(StreamAddress.masked))
        _roles = State(initialValue: Set(stream.roles))
        _viaGo2rtc = State(initialValue: stream.name != nil)
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }
    private var hasName: Bool { isNew ? viaGo2rtc : stream.name != nil }
    private var canRename: Bool { isNew || (stream.sharedWith.isEmpty && !stream.locked) }
    private var addresses: [String] {
        texts.enumerated().map { i, text in
            let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return i < stream.sources.count ? StreamAddress.unmasked(t, original: stream.sources[i]) : t
        }.filter { !$0.isEmpty }
    }

    private var nameProblem: String? {
        guard hasName else { return nil }
        if trimmedName.isEmpty { return L("Give the stream a name.") }
        if trimmedName.wholeMatch(of: /[A-Za-z0-9_\-]+/) == nil { return L("Use letters, digits, - and _ only.") }
        if takenNames.contains(trimmedName) { return L("Another stream already has this name.") }
        return nil
    }

    private var canFinish: Bool {
        nameProblem == nil && !addresses.isEmpty && (hasName || !roles.isEmpty)
            && addresses.allSatisfy { StreamAddress.host($0) != nil || $0.hasPrefix("ffmpeg:") || stream.locked }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                VStack(spacing: 0) {
                    if hasName {
                        HStack {
                            Text("Name").font(.system(size: 15)).foregroundStyle(focus == -1 ? Theme.accent : Theme.text).formLabel()
                            TextField("", text: $name, prompt: Text("driveway_sub").foregroundStyle(Theme.textTertiary))
                                .multilineTextAlignment(formFieldAlignment).font(.system(size: 15))
                                .autocorrectionDisabled().textInputAutocapitalization(.never)
                                .focused($focus, equals: -1).focusEffectDisabled()
                                .disabled(!canRename)
                        }
                        .padding(.horizontal, 14).padding(.vertical, 11)
                        .modifier(FocusRow(on: focus == -1))
                        Divider().overlay(Theme.cardBorder)
                    }
                    ForEach(texts.indices, id: \.self) { i in
                        if i > 0 { Divider().overlay(Theme.cardBorder) }
                        VStack(alignment: .leading, spacing: 6) {
                            Text(texts.count > 1 ? L("Address \(i + 1)") : L("Address"))
                                .font(.system(size: 15)).foregroundStyle(focus == i ? Theme.accent : Theme.text)
                            if stream.locked {
                                Text(texts[i]).font(.system(size: 12.5).monospaced()).foregroundStyle(Theme.textSecondary)
                            } else {
                                TextField("rtsp://…", text: $texts[i], axis: .vertical)
                                    .font(.system(size: 12.5).monospaced())
                                    .autocorrectionDisabled().textInputAutocapitalization(.never).keyboardType(.URL)
                                    .focused($focus, equals: i).focusEffectDisabled()
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 11)
                        .modifier(FocusRow(on: focus == i))
                    }
                    if isNew {
                        Divider().overlay(Theme.cardBorder)
                        Toggle(isOn: $viaGo2rtc) { Text("Through go2rtc").font(.system(size: 15)) }
                            .tint(Theme.online)
                            .padding(.horizontal, 14).padding(.vertical, 8)
                    }
                }
                .card(radius: 12)
                if let nameProblem, !trimmedName.isEmpty || !isNew {
                    note(nameProblem, color: Theme.destructive)
                } else if !stream.sharedWith.isEmpty {
                    note(L("\(ListFormatter.localizedString(byJoining: stream.sharedWith)) also uses this stream; its name and address stay as they are here."))
                } else if stream.locked {
                    note(L("This address uses a variable or a special source. Change it in Configuration (YAML)."))
                } else if !isNew, let n = stream.name, !stream.liveLabels.isEmpty {
                    note(L("Renaming \(n) also changes the camera's input and its live view \(ListFormatter.localizedString(byJoining: stream.liveLabels.map { "“\($0)”" }))."))
                } else if isNew, stream.sources.first.map({ $0 != "rtsp://" }) == true {
                    note(L("The camera's address is filled in; add the path of the stream."))
                }

                testCard.padding(.top, 6)

                SectionLabel(text: L("Roles")).padding(.top, 10)
                HStack(spacing: 6) {
                    ForEach(["detect", "record", "audio"], id: \.self) { role in roleChip(role) }
                }
                note(hasName ? L("Choosing a role takes it from the other stream. Without a role the stream is for live view only.")
                             : L("Choosing a role takes it from the other stream. A stream without go2rtc needs a role."))

                if !isNew {
                    Button { confirmingDelete = true } label: {
                        Text("Remove stream")
                            .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.destructive)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(Theme.destructive.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.destructive.opacity(0.35), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 18)
                }
            }
            .padding(16)
            .readableWidth()
        }
        .screenBackground()
        .navigationTitle(isNew ? L("Add stream") : L("Edit stream"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { closePresented(dismiss) } }
            ToolbarItem(placement: .confirmationAction) {
                Button(isNew ? L("Add") : L("Done")) { finish() }.disabled(!canFinish)
            }
        }
        .onChange(of: texts) { test = nil }
        .alert(L("Remove this stream?"), isPresented: $confirmingDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Remove stream", role: .destructive) { onDelete(); closePresented(dismiss) }
        } message: {
            Text(stream.sharedWith.isEmpty ? L("Frigate stops reading it and its go2rtc stream is removed when you save.")
                                           : L("Frigate stops reading it for this camera when you save."))
        }
    }

    private var testCard: some View {
        HStack(alignment: .top, spacing: 10) {
            Group {
                if testing { ProgressView().controlSize(.small) }
                else if case .success = test { Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.online) }
                else if case .failure = test { Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.destructive) }
                else { Image(systemName: "play.circle").foregroundStyle(Theme.accent) }
            }
            .font(.system(size: 17)).frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text("Test stream").font(.system(size: 15)).foregroundStyle(Theme.text)
                switch test {
                case .success(let p): Text(p.summary).font(.system(size: 12)).foregroundStyle(Theme.online)
                case .failure(let e): Text(e.localizedDescription).font(.system(size: 12)).foregroundStyle(Theme.destructive)
                case nil: EmptyView()
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
        .contentShape(Rectangle())
        .onTapGesture { Task { await runTest() } }
        .card(radius: 12)
        .disabled(testing || addresses.first.map(StreamAddress.canProbe) != true)
        .opacity(addresses.first.map(StreamAddress.canProbe) == true ? 1 : 0.5)
    }

    private func runTest() async {
        guard let client, let url = addresses.first, StreamAddress.canProbe(url), !testing else { return }
        testing = true
        defer { testing = false }
        do { test = .success(try await client.probeStream(url)) } catch { test = .failure(error) }
    }

    private func roleChip(_ role: String) -> some View {
        let on = roles.contains(role)
        return Button { if on { roles.remove(role) } else { roles.insert(role) } } label: {
            Text(CameraStreamsView.roleTitle(role)).font(.system(size: 13, weight: .semibold))
                .foregroundStyle(on ? Theme.onColor : Theme.text)
                .padding(.horizontal, 13).padding(.vertical, 7)
                .background(on ? Theme.accent : Theme.control, in: Capsule())
                .overlay(Capsule().stroke(on ? .clear : Theme.controlBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private func finish() {
        var s = stream
        s.name = hasName ? trimmedName : nil
        if !stream.locked { s.sources = addresses }
        s.roles = roles.sorted { CameraStreamsView.roleOrder($0) < CameraStreamsView.roleOrder($1) }
        onDone(s)
        closePresented(dismiss)
    }

    private func note(_ text: String, color: Color = Theme.textSecondary) -> some View {
        Text(text).font(.system(size: 12)).foregroundStyle(color).padding(.horizontal, 4)
    }
}
