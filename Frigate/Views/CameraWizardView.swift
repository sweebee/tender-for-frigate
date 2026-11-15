import SwiftUI

/// Adding a camera in Frigate's four steps: connection, what was found, streams and roles, check
/// and add. The camera starts at once; Frigate writes it to the config and starts it without a restart.
struct CameraWizardView: View {
    let serverID: UUID
    let onDone: () -> Void

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    enum Step { case connect, found, roles, review }
    enum Role: String, CaseIterable, Hashable { case detect, record, audio }

    struct Stream: Identifiable, Hashable {
        let id = UUID()
        var url: String
        var probe: StreamProbe?
        var error: String?
        var testing = false
        var roles: Set<Role> = []
        var restream = true
        var use = true
    }

    @State private var step: Step = .connect
    @State private var name = ""
    @State private var host = ""
    @State private var user = ""
    @State private var password = ""
    @State private var useOnvif = true
    @State private var onvifPort = 80
    @State private var brand: CameraBrand = .hikvision
    @State private var customURL = ""
    @State private var searching = false
    @State private var device: OnvifDevice?
    @State private var streams: [Stream] = []
    @State private var extraURL = ""
    @State private var appleCompatibility = true
    @State private var usePTZ = false
    @State private var saving = false
    @State private var errorMessage: String?

    private var conn: ServerConnection? { model.connection(for: serverID) }
    private var client: FrigateClient? { conn?.client }
    private var chosen: [Stream] { streams.filter { $0.use && $0.probe != nil } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("Step \(stepNumber) of 4")).font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.textSecondary)
                switch step {
                case .connect: connectStep
                case .found: foundStep
                case .roles: rolesStep
                case .review: reviewStep
                }
            }
            .padding(16)
            .readableWidth()
        }
        .screenBackground()
        .navigationTitle(L("Add camera"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { closePresented(dismiss) }.disabled(saving) } }
        .interactiveDismissDisabled(saving)
        .alert(L("Not ready yet"), isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private var stepNumber: Int {
        switch step { case .connect: return 1; case .found: return 2; case .roles: return 3; case .review: return 4 }
    }

    // MARK: Step 1

    private var connectStep: some View {
        VStack(alignment: .leading, spacing: 8) {
            card {
                field(L("Name"), text: $name, prompt: L("Driveway"))
                divider
                field(L("Address"), text: $host, prompt: "192.168.1.50", keyboard: .URL)
                divider
                field(L("Username"), text: $user, prompt: "admin")
                divider
                HStack {
                    Text("Password").font(.system(size: 15))
                    SecureField("", text: $password).multilineTextAlignment(.trailing).font(.system(size: 15))
                }
                .padding(.horizontal, 14).padding(.vertical, 11)
            }
            Picker("", selection: $useOnvif) {
                Text("Find it (ONVIF)").tag(true)
                Text("Brand or address").tag(false)
            }
            .pickerStyle(.segmented).padding(.top, 6)
            if useOnvif {
                card {
                    HStack {
                        Text("ONVIF port").font(.system(size: 15))
                        Spacer()
                        TextField("80", value: $onvifPort, format: .number.grouping(.never))
                            .multilineTextAlignment(.trailing).keyboardType(.numberPad).frame(width: 80)
                    }
                    .padding(.horizontal, 14).padding(.vertical, 11)
                }
                note(L("Most cameras answer on port 80; some on 8000 or 8899. ONVIF has to be on in the camera."))
            } else {
                card {
                    HStack {
                        Text("Brand").font(.system(size: 15))
                        Spacer()
                        Picker("", selection: $brand) { ForEach(CameraBrand.allCases) { Text($0.title).tag($0) } }.labelsHidden()
                    }
                    .padding(.horizontal, 14).padding(.vertical, 6)
                    if brand == .other {
                        divider
                        TextField("rtsp://user:password@192.168.1.50:554/stream", text: $customURL)
                            .font(.system(size: 13).monospaced()).autocorrectionDisabled().textInputAutocapitalization(.never)
                            .keyboardType(.URL)
                            .padding(.horizontal, 14).padding(.vertical, 11)
                    }
                }
            }
            primary(searching ? nil : L("Find camera")) { Task { await search() } }
                .padding(.top, 8)
            if searching {
                note(L("Frigate is connecting to the camera and opening its streams; this takes up to half a minute."))
            }
        }
    }

    // MARK: Step 2

    private var foundStep: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let device {
                card {
                    VStack(alignment: .leading, spacing: 3) {
                        Text([device.manufacturer, device.model].filter { !$0.isEmpty }.joined(separator: " ").nonEmpty ?? L("Camera"))
                            .font(.system(size: 15, weight: .semibold))
                        Text([device.firmware.isEmpty ? nil : L("firmware \(device.firmware)"),
                              L("\(device.profiles) profiles"), device.ptz ? L("PTZ") : nil].compactMap { $0 }.joined(separator: " · "))
                            .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(14)
                }
            }
            if let first = streams.first(where: { $0.probe != nil }), let client {
                Color.clear.aspectRatio(16 / 9, contentMode: .fit)
                    .overlay { RemoteImage(url: client.streamSnapshotURL(first.url), session: client.session, contentMode: .fit) }
                    .background(Theme.control)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            SectionLabel(text: L("Streams")).padding(.top, 6)
            card {
                ForEach(Array(streams.enumerated()), id: \.element.id) { i, s in
                    if i > 0 { divider }
                    streamRow(i)
                }
            }
            note(L("Tick the streams to use: usually the main stream for recording and the sub stream for detection."))
            card {
                HStack {
                    TextField(L("Another stream address"), text: $extraURL)
                        .font(.system(size: 13).monospaced()).autocorrectionDisabled().textInputAutocapitalization(.never).keyboardType(.URL)
                    Button { addExtra() } label: { Image(systemName: "plus.circle.fill").font(.system(size: 20)) }
                        .buttonStyle(.plain).foregroundStyle(Theme.accent).disabled(extraURL.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
            }
            HStack(spacing: 10) {
                secondary(L("Back")) { step = .connect }
                primary(L("Next")) {
                    if chosen.isEmpty { errorMessage = L("Tick at least one stream that works.") } else { assignRoles(); step = .roles }
                }
            }
            .padding(.top, 8)
        }
    }

    private func streamRow(_ i: Int) -> some View {
        let s = streams[i]
        return HStack(spacing: 10) {
            Button { streams[i].use.toggle() } label: {
                Image(systemName: s.use && s.probe != nil ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20)).foregroundStyle(s.probe == nil ? Theme.textSecondary : Theme.accent)
            }
            .buttonStyle(.plain).disabled(s.probe == nil)
            VStack(alignment: .leading, spacing: 2) {
                Text(Self.masked(s.url)).font(.system(size: 12).monospaced()).lineLimit(1).truncationMode(.middle)
                if s.testing {
                    Text("Testing…").font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                } else if let p = s.probe {
                    Text(p.summary).font(.system(size: 12)).foregroundStyle(Theme.online)
                } else if let e = s.error {
                    Text(e).font(.system(size: 12)).foregroundStyle(Theme.destructive).lineLimit(2)
                }
            }
            Spacer(minLength: 4)
            if s.testing { ProgressView().controlSize(.small) }
            else if s.probe == nil {
                Button("Test") { Task { await test(i) } }.font(.system(size: 13, weight: .semibold))
                Button { streams.remove(at: i) } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.textSecondary) }
                    .buttonStyle(.plain).accessibilityLabel("Remove")
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
    }

    // MARK: Step 3

    private var rolesStep: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(chosen) { s in
                if let i = streams.firstIndex(where: { $0.id == s.id }) {
                    card {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(s.probe?.summary ?? "").font(.system(size: 14, weight: .semibold))
                            Text(Self.masked(s.url)).font(.system(size: 11).monospaced()).foregroundStyle(Theme.textSecondary)
                                .lineLimit(1).truncationMode(.middle)
                            HStack(spacing: 6) {
                                ForEach(Role.allCases, id: \.self) { role in roleChip(role, index: i) }
                            }
                            Toggle(isOn: $streams[i].restream) { Text("Through go2rtc").font(.system(size: 14)) }.tint(Theme.online)
                        }
                        .padding(14)
                    }
                }
            }
            note(L("Detection takes one stream, recording one, audio one. Through go2rtc the camera is opened once and shared, which most cameras like better."))
            if chosen.contains(where: { $0.roles.contains(.record) && ($0.probe?.isHEVC ?? false) }) {
                card {
                    Toggle(isOn: $appleCompatibility) { Text("Apple compatible (h265)").font(.system(size: 15)) }.tint(Theme.online)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                }
                note(L("Recordings in h265 play on iPhone, iPad and Mac only with this on."))
            }
            if device?.ptz == true {
                card {
                    Toggle(isOn: $usePTZ) { Text("Pan, tilt and zoom (ONVIF)").font(.system(size: 15)) }.tint(Theme.online)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                }
            }
            HStack(spacing: 10) {
                secondary(L("Back")) { step = .found }
                primary(L("Next")) {
                    if !chosen.contains(where: { $0.roles.contains(.detect) }) { errorMessage = L("One stream needs Detection.") }
                    else { step = .review }
                }
            }
            .padding(.top, 8)
        }
    }

    private func roleChip(_ role: Role, index i: Int) -> some View {
        let on = streams[i].roles.contains(role)
        let title: String = switch role { case .detect: L("Detection"); case .record: L("Record"); case .audio: L("Audio") }
        return Button {
            if on { streams[i].roles.remove(role) } else {
                // A role sits on one stream only.
                for j in streams.indices { streams[j].roles.remove(role) }
                streams[i].roles.insert(role)
            }
        } label: {
            Text(title).font(.system(size: 13, weight: .semibold))
                .foregroundStyle(on ? Theme.onColor : Theme.text)
                .padding(.horizontal, 11).padding(.vertical, 6)
                .background(on ? Theme.accent : Theme.control, in: Capsule())
                .overlay(Capsule().stroke(on ? .clear : Theme.controlBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // MARK: Step 4

    private var key: String { Self.key(for: name) }

    private var reviewStep: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let detect = chosen.first(where: { $0.roles.contains(.detect) }), let client {
                Color.clear.aspectRatio(16 / 9, contentMode: .fit)
                    .overlay { RemoteImage(url: client.streamSnapshotURL(detect.url), session: client.session, contentMode: .fit) }
                    .background(Theme.control)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            card {
                summaryRow(L("Name"), name.trimmingCharacters(in: .whitespaces)
                           + (key != name.trimmingCharacters(in: .whitespaces).lowercased() ? "  (\(key))" : ""))
                divider
                if let d = detectSize { summaryRow(L("Detection"), "\(d.0)×\(d.1)") }
                divider
                summaryRow(L("Record"), chosen.first { $0.roles.contains(.record) }?.probe?.resolution ?? L("Off"))
            }
            let found = issues
            if !found.isEmpty {
                SectionLabel(text: L("Check")).padding(.top, 6)
                card {
                    ForEach(Array(found.enumerated()), id: \.offset) { i, issue in
                        if i > 0 { divider }
                        Label(issue.text, systemImage: issue.error ? "exclamationmark.octagon.fill" : "exclamationmark.triangle.fill")
                            .font(.system(size: 13))
                            .foregroundStyle(issue.error ? Theme.destructive : Theme.warning)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 14).padding(.vertical, 10)
                    }
                }
            }
            note(L("Frigate starts the camera at once; no restart needed. The camera's password is kept in Frigate's config, as with Frigate's own wizard."))
            HStack(spacing: 10) {
                secondary(L("Back")) { step = .roles }
                primary(saving ? nil : L("Add camera")) { Task { await save() } }
            }
            .padding(.top, 8)
        }
    }

    private struct Issue { let error: Bool; let text: String }

    /// As Frigate's wizard warns: detection too large or small, recording without (usable) audio.
    private var issues: [Issue] {
        var out: [Issue] = []
        if let d = chosen.first(where: { $0.roles.contains(.detect) })?.probe, let w = d.width, let h = d.height {
            if min(w, h) > 1080 { out.append(Issue(error: false, text: L("Detection uses a large stream; a sub stream takes far less of the server."))) }
            if max(w, h) < 640 { out.append(Issue(error: true, text: L("The detection stream is very small; objects will be hard to see."))) }
        }
        if let r = chosen.first(where: { $0.roles.contains(.record) })?.probe {
            if r.audioCodec == nil { out.append(Issue(error: false, text: L("Recordings will have no sound: the stream has no audio."))) }
            else if r.audioCodec != "aac" { out.append(Issue(error: false, text: L("The audio is not AAC; recordings may play without sound in some players."))) }
        }
        if let a = chosen.first(where: { $0.roles.contains(.audio) }), a.probe?.audioCodec == nil {
            out.append(Issue(error: true, text: L("The audio stream has no audio.")))
        }
        if conn?.config?.cameras[key] != nil { out.append(Issue(error: true, text: L("There is already a camera with this name."))) }
        return out
    }

    /// As Frigate does: the short side at most 720, even sizes, never larger than the stream.
    private var detectSize: (Int, Int)? {
        guard let p = chosen.first(where: { $0.roles.contains(.detect) })?.probe, let w = p.width, let h = p.height, w > 0, h > 0 else { return nil }
        let short = min(w, h), target = min(720, short)
        let scale = Double(target) / Double(short)
        func even(_ v: Double) -> Int { Int(v) / 2 * 2 }
        return (even(Double(w) * scale), even(Double(h) * scale))
    }

    // MARK: Work

    private func search() async {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty, n.count <= 64 else { errorMessage = L("Give the camera a name."); return }
        guard conn?.config?.cameras[Self.key(for: n)] == nil else { errorMessage = L("There is already a camera with this name."); return }
        let h = host.trimmingCharacters(in: .whitespaces)
        guard let client else { return }
        searching = true
        defer { searching = false }
        device = nil
        var urls: [String] = []
        if useOnvif {
            guard !h.isEmpty else { errorMessage = L("Fill in the camera's address."); return }
            do {
                let d = try await client.probeOnvif(host: h, port: onvifPort, user: user, password: password)
                device = d
                urls = d.streams
                if urls.isEmpty { errorMessage = L("The camera answered but reported no streams. Try Brand or address."); return }
            } catch {
                errorMessage = L("No answer over ONVIF: \(error.localizedDescription) Check the address, port and login, or try Brand or address.")
                return
            }
        } else if brand == .other {
            let u = customURL.trimmingCharacters(in: .whitespaces)
            guard u.hasPrefix("rtsp://") || u.hasPrefix("rtsps://") || u.hasPrefix("http") else {
                errorMessage = L("Fill in an address that starts with rtsp://."); return
            }
            urls = [u]
        } else {
            guard !h.isEmpty else { errorMessage = L("Fill in the camera's address."); return }
            var candidates = brand.urls(host: h, user: user, password: password)
            if brand == .reolink {
                // As Frigate: HTTP-FLV up to 5 MP, RTSP above.
                let proto = await client.reolinkProtocol(host: h, user: user, password: password)
                if proto == "rtsp" { candidates.reverse() } else if proto == nil { candidates = [candidates[1]] }
            }
            urls = []
            for c in candidates {
                if (try? await client.probeStream(c)) != nil { urls = [c]; break }
            }
            if urls.isEmpty { urls = [candidates.first ?? ""] }
            if let sub = brand.subURL(main: urls[0]), sub != urls[0] { urls.append(sub) }
        }
        streams = urls.map { Stream(url: $0) }
        step = .found
        await withTaskGroup(of: Void.self) { group in
            for i in streams.indices { group.addTask { await test(i) } }
        }
    }

    private func test(_ i: Int) async {
        guard let client, streams.indices.contains(i) else { return }
        streams[i].testing = true
        streams[i].error = nil
        do {
            streams[i].probe = try await client.probeStream(streams[i].url)
            streams[i].use = true
        } catch {
            streams[i].probe = nil
            streams[i].use = false
            streams[i].error = error.localizedDescription
        }
        streams[i].testing = false
        // Two working streams at most ticked by default: the largest and the smallest.
        let working = streams.indices.filter { streams[$0].probe != nil }
        if working.count > 2 {
            let sorted = working.sorted { streams[$0].probe!.pixels > streams[$1].probe!.pixels }
            for j in working { streams[j].use = j == sorted.first || j == sorted.last }
        }
    }

    private func addExtra() {
        let u = extraURL.trimmingCharacters(in: .whitespaces)
        guard !u.isEmpty else { return }
        streams.append(Stream(url: u))
        extraURL = ""
        let i = streams.count - 1
        Task { await test(i) }
    }

    /// The largest stream records, the smallest detects; one stream does both.
    private func assignRoles() {
        let ids = chosen.sorted { ($0.probe?.pixels ?? 0) > ($1.probe?.pixels ?? 0) }.map(\.id)
        for j in streams.indices { streams[j].roles = [] }
        guard let big = ids.first, let small = ids.last,
              let bi = streams.firstIndex(where: { $0.id == big }), let si = streams.firstIndex(where: { $0.id == small }) else { return }
        streams[bi].roles.insert(.record)
        streams[si].roles.insert(.detect)
    }

    private func save() async {
        guard let conn, let client else { return }
        if let blocking = issues.first(where: \.error) { errorMessage = blocking.text; return }
        saving = true
        defer { saving = false }
        let cam = key
        let used = chosen.filter { !$0.roles.isEmpty || $0.restream }
        let single = used.count == 1
        var inputs: [[String: Any]] = []
        var go2rtc: [String: Any] = [:]
        var live: [String: String] = [:]
        for (i, s) in used.enumerated() {
            let streamName = single ? cam : "\(cam)_\(i + 1)"
            go2rtc[streamName] = [s.url.hasPrefix("http") ? "ffmpeg:\(s.url)" : s.url]
            live[L("Stream \(i + 1)")] = streamName
            guard !s.roles.isEmpty else { continue }
            let roles = Role.allCases.filter(s.roles.contains).map(\.rawValue)
            if s.restream {
                inputs.append(["path": "rtsp://127.0.0.1:8554/\(streamName)", "input_args": "preset-rtsp-restream", "roles": roles])
            } else {
                inputs.append(["path": s.url, "roles": roles])
            }
        }
        var ffmpeg: [String: Any] = ["inputs": inputs]
        if appleCompatibility, chosen.contains(where: { $0.roles.contains(.record) && ($0.probe?.isHEVC ?? false) }) {
            ffmpeg["apple_compatibility"] = true
        }
        var camera: [String: Any] = ["enabled": true, "ffmpeg": ffmpeg, "live": ["streams": live]]
        let title = name.trimmingCharacters(in: .whitespaces)
        if title != cam { camera["friendly_name"] = title }
        if let d = detectSize { camera["detect"] = ["width": d.0, "height": d.1] }
        if usePTZ, device?.ptz == true {
            var onvif: [String: Any] = ["host": host.trimmingCharacters(in: .whitespaces), "port": onvifPort]
            if !user.isEmpty { onvif["user"] = user }
            if !password.isEmpty { onvif["password"] = password }
            camera["onvif"] = onvif
        }
        do {
            // As Frigate's wizard: the camera (started by the "add" topic), then its go2rtc streams.
            try await client.setConfig(["cameras": [cam: camera]], requiresRestart: true, updateTopic: "config/cameras/\(cam)/add")
            try? await client.setConfig(["go2rtc": ["streams": go2rtc]], requiresRestart: false, updateTopic: nil)
            for (n, src) in go2rtc { if let s = (src as? [String])?.first { try? await client.addGo2rtcStream(n, source: s) } }
            await conn.refresh(reloadConfig: true)
            onDone()
            closePresented(dismiss)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Frigate's key for a name: spaces to _, lower case; other characters give "cam_<hash>".
    static func key(for title: String) -> String {
        let t = title.trimmingCharacters(in: .whitespaces)
        let k = t.replacingOccurrences(of: #"\s+"#, with: "_", options: .regularExpression).lowercased()
        if !k.isEmpty, k.range(of: #"^[a-zA-Z0-9_-]+$"#, options: .regularExpression) != nil, !k.allSatisfy(\.isNumber) { return k }
        // FNV-1a 32-bit over the UTF-8 text, as Frigate's web wizard.
        var hash: UInt32 = 0x811C9DC5
        for b in t.utf8 { hash ^= UInt32(b); hash = hash &* 0x0100_0193 }
        var hex = String(format: "%08x", hash)
        if hex.first?.isNumber == true { hex = "a" + hex.dropFirst() }
        return "cam_" + hex
    }

    /// The address with its password hidden, for display.
    static func masked(_ url: String) -> String {
        var s = url.replacingOccurrences(of: #"(://[^:/@]+:)[^@]+@"#, with: "$1••••@", options: .regularExpression)
        s = s.replacingOccurrences(of: #"(password=)[^&]+"#, with: "$1••••", options: .regularExpression)
        return s
    }

    // MARK: Pieces

    private func card<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        VStack(spacing: 0, content: content).card(radius: 12)
    }

    private var divider: some View { Divider().overlay(Theme.cardBorder) }

    private func note(_ text: String) -> some View {
        Text(text).font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
    }

    private func field(_ title: String, text: Binding<String>, prompt: String, keyboard: UIKeyboardType = .default) -> some View {
        HStack {
            Text(title).font(.system(size: 15))
            TextField(prompt, text: text).multilineTextAlignment(.trailing).font(.system(size: 15))
                .autocorrectionDisabled().textInputAutocapitalization(.never).keyboardType(keyboard)
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
    }

    private func summaryRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).font(.system(size: 15))
            Spacer()
            Text(value).font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
    }

    /// nil title: a spinner.
    private func primary(_ title: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group { if let title { Text(title) } else { ProgressView().tint(Theme.onColor) } }
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.onColor)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(title == nil)
    }

    private func secondary(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Theme.control, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.controlBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(saving || searching)
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
