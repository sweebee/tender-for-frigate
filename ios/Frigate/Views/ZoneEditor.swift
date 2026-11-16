import SwiftUI

// Drawing a camera's zones and masks on its picture, as Frigate's Masks / Zones settings do: drag the
// corners, tap the + between two corners to add one, long-press a corner to remove it. Saved with
// PUT /api/config/set as Frigate's web interface saves them; they apply without a restart.

/// The shape being edited, or a new one.
struct ZoneDraft {
    var kind: ZonesMasksView.Shape.Kind
    /// The config key; empty for a new one until it has a name.
    var name: String
    var title: String
    /// Corners, 0…1 across the picture.
    var points: [CGPoint]
    var enabled = true
    /// Zones: the objects that count in it (empty: all).
    var objects: [String] = []
    /// Zones: frames an object must be inside before it counts as in the zone.
    var inertia: Double = 3
    /// Zones: seconds an object must stay before it counts as loitering (0: off).
    var loiteringTime: Double = 0
    /// Object masks that hold for one object type only (objects.filters.<label>.mask); nil: every object.
    var label: String? = nil

    var isNew: Bool { name.isEmpty }

    /// A square in the middle of the picture, to drag into shape.
    static func new(_ kind: ZonesMasksView.Shape.Kind) -> ZoneDraft {
        ZoneDraft(kind: kind, name: "", title: "",
                  points: [CGPoint(x: 0.35, y: 0.35), CGPoint(x: 0.65, y: 0.35), CGPoint(x: 0.65, y: 0.65), CGPoint(x: 0.35, y: 0.65)])
    }

    /// The key Frigate uses for a name: lower case, words joined with underscores.
    static func key(for title: String) -> String {
        let folded = title.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).lowercased()
        let parts = folded.split { !($0.isLetter || $0.isNumber) }
        return parts.joined(separator: "_")
    }

    /// "0.123,0.456,…" with three decimals, as Frigate writes them.
    var coordinates: String {
        points.map { p in
            [p.x, p.y].map { String(format: "%.3f", min(max($0, 0), 1)) }.joined(separator: ",")
        }.joined(separator: ",")
    }
}

struct ZoneEditorView: View {
    let serverID: UUID
    let camera: String
    @State var draft: ZoneDraft
    /// Names already used for this kind on this camera: a new one may not take them.
    var taken: Set<String> = []
    let onDone: () -> Void

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var original: ZoneDraft?
    @State private var dragging: Int?
    @State private var saving = false
    @State private var deleting = false
    @State private var confirmDelete = false
    @State private var errorMessage: String?
    @State private var labels: [String] = []
    @FocusState private var nameFocused: Bool

    private var conn: ServerConnection? { model.connection(for: serverID) }
    private var color: Color {
        switch draft.kind {
        case .zone: return Theme.accent
        case .motionMask: return Color(white: 0.75)
        case .objectMask: return Theme.destructive
        }
    }
    private var kindTitle: String {
        switch draft.kind {
        case .zone: return L("Zone")
        case .motionMask: return L("Motion mask")
        case .objectMask: return L("Object mask")
        }
    }
    private var canSave: Bool {
        !draft.title.trimmingCharacters(in: .whitespaces).isEmpty && draft.points.count >= 3 && !saving
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Button { closePresented(dismiss) } label: { HeaderTextLabel(title: "Cancel") }
                        .buttonStyle(.plain)
                    Spacer()
                    Text(draft.isNew ? L("New \(kindTitle.lowercased())") : kindTitle).font(.system(size: 16, weight: .semibold))
                    Spacer()
                    Button { Task { await save() } } label: {
                        HeaderTextLabel(title: "Save", loading: saving && !deleting, prominent: true)
                    }
                    .buttonStyle(.plain)
                    .disabled(!canSave)
                    .opacity(canSave || saving ? 1 : 0.4)
                }
                .padding(.top, 12)

                canvas
                Text("Drag a corner to move it. Tap + between two corners to add one; long-press a corner to remove it.")
                    .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)

                VStack(spacing: 0) {
                    HStack {
                        Text("Name").font(.system(size: 15)).foregroundStyle(nameFocused ? Theme.accent : Theme.text)
                        TextField(nameFocused ? "" : kindTitle, text: $draft.title)
                            .multilineTextAlignment(formFieldAlignment)
                            .font(.system(size: 15))
                            .focused($nameFocused)
                            .focusEffectDisabled()
                    }
                    .padding(.horizontal, 14).padding(.vertical, 11)
                    .contentShape(Rectangle()).onTapGesture { nameFocused = true }
                    .modifier(FocusRow(on: nameFocused))
                    Divider().overlay(Theme.cardBorder)
                    Toggle(isOn: $draft.enabled) { Text("Active").font(.system(size: 15)) }
                        .tint(Theme.online)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                }
                .card(radius: 12)
                if draft.isNew, !draft.title.isEmpty {
                    Text(L("Saved as \(ZoneDraft.key(for: draft.title)) in the config."))
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                }

                if draft.kind == .zone {
                    SectionLabel(text: L("Objects in this zone")).padding(.top, 6)
                    VStack(alignment: .leading, spacing: 8) {
                        FlowLayout(spacing: 6) {
                            ForEach(labels, id: \.self) { label in
                                let on = draft.objects.contains(label)
                                Button {
                                    draft.objects = on ? draft.objects.filter { $0 != label } : draft.objects + [label]
                                } label: {
                                    Text(FrigateFormat.labelName(label).capitalizedFirst)
                                        .font(.system(size: 13, weight: .semibold))
                                        .foregroundStyle(on ? Theme.onColor : Theme.text)
                                        .padding(.horizontal, 10).padding(.vertical, 5)
                                        .background(on ? Theme.accent : Theme.control, in: Capsule())
                                        .overlay(Capsule().stroke(on ? .clear : Theme.controlBorder, lineWidth: 1))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        Text(draft.objects.isEmpty ? L("None selected: every tracked object counts.") : L("Only these objects count in this zone."))
                            .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .card(radius: 12)

                    SectionLabel(text: L("Timing")).padding(.top, 6)
                    VStack(spacing: 0) {
                        HStack {
                            Text("Frames before it counts").font(.system(size: 15))
                            Spacer(minLength: 8)
                            NumberStepper(value: draft.inertia, unit: "", step: 1, min: 1, max: 30) { draft.inertia = $0 }
                        }
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        Divider().overlay(Theme.cardBorder)
                        HStack {
                            Text("Loitering after").font(.system(size: 15))
                            Spacer(minLength: 8)
                            NumberStepper(value: draft.loiteringTime, unit: "s", step: 5, min: 0, max: 3600) { draft.loiteringTime = $0 }
                        }
                        .padding(.horizontal, 14).padding(.vertical, 8)
                    }
                    .card(radius: 12)
                    Text("An object must be in the zone this many frames in a row before it counts; a few avoids short false entries. Loitering: an object that stays longer is marked as loitering (0 is off).")
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                }

                if !draft.isNew {
                    Button(role: .destructive) { confirmDelete = true } label: {
                        HStack(spacing: 8) {
                            if deleting { ProgressView().controlSize(.small) }
                            Text(L("Delete \(kindTitle.lowercased())"))
                        }
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.destructive)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .card(radius: 12)
                    }
                    .buttonStyle(.plain)
                    .disabled(saving)
                    .padding(.top, 8)
                }
            }
            .padding(.horizontal, 16).readableWidth().padding(.bottom, 24)
        }
        // Dragging a corner must not scroll the page.
        .scrollDisabled(dragging != nil)
        .screenBackground()
        .interactiveDismissDisabled(true)
        .task {
            if draft.isNew && draft.title.isEmpty {
                // A ready name ("Zone 2"), which can be changed.
                draft.title = (1...99).lazy.map { "\(kindTitle) \($0)" }.first { !taken.contains(ZoneDraft.key(for: $0)) } ?? kindTitle
            }
            await loadLabels()
        }
        .alert(L("Delete \(draft.title)?"), isPresented: $confirmDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { Task { await delete() } }
        } message: { Text("It is removed from Frigate's config.") }
        .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    // MARK: Canvas

    private var canvas: some View {
        Color.clear
            .aspectRatio(conn?.config?.cameras[camera]?.aspectRatio ?? 16 / 9, contentMode: .fit)
            .overlay {
                if let client = conn?.client {
                    RemoteImage(url: client.latestImageURL(camera: camera, height: 720), session: client.session)
                }
            }
            .overlay {
                GeometryReader { geo in
                    let size = geo.size
                    let pts = draft.points.map { CGPoint(x: $0.x * size.width, y: $0.y * size.height) }
                    ZStack {
                        Path { p in
                            guard let first = pts.first else { return }
                            p.move(to: first); pts.dropFirst().forEach { p.addLine(to: $0) }; p.closeSubpath()
                        }
                        .fill(color.opacity(draft.kind == .zone ? 0.25 : 0.45))
                        Path { p in
                            guard let first = pts.first else { return }
                            p.move(to: first); pts.dropFirst().forEach { p.addLine(to: $0) }; p.closeSubpath()
                        }
                        .stroke(color, lineWidth: 2)
                        // + between each two corners, to add one there.
                        ForEach(0..<pts.count, id: \.self) { i in
                            let a = pts[i], b = pts[(i + 1) % pts.count]
                            Button { insert(after: i) } label: {
                                Image(systemName: "plus").font(.system(size: 10, weight: .bold)).foregroundStyle(color)
                                    .frame(width: 20, height: 20)
                                    .background(.black.opacity(0.6), in: Circle())
                                    .frame(width: 36, height: 36).contentShape(Circle())
                            }
                            .buttonStyle(.plain)
                            .position(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
                            .opacity(dragging == nil ? 1 : 0)
                        }
                        ForEach(0..<pts.count, id: \.self) { i in
                            Circle()
                                .fill(dragging == i ? color : .white)
                                .frame(width: 18, height: 18)
                                .overlay(Circle().stroke(color, lineWidth: 3))
                                .frame(width: 44, height: 44).contentShape(Circle())
                                .position(pts[i])
                                .gesture(DragGesture(minimumDistance: 1, coordinateSpace: .named("zoneCanvas"))
                                    .onChanged { v in
                                        dragging = i
                                        draft.points[i] = CGPoint(x: min(max(v.location.x / size.width, 0), 1),
                                                                  y: min(max(v.location.y / size.height, 0), 1))
                                    }
                                    .onEnded { _ in dragging = nil })
                                .simultaneousGesture(LongPressGesture(minimumDuration: 0.6).onEnded { _ in remove(i) })
                        }
                        // A magnifier above the finger while dragging, so the corner can be placed exactly.
                        if let i = dragging, i < pts.count {
                            magnifier(at: pts[i], in: size)
                        }
                    }
                    .coordinateSpace(name: "zoneCanvas")
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func magnifier(at point: CGPoint, in size: CGSize) -> some View {
        let zoom: CGFloat = 2.5, diameter: CGFloat = 96
        return ZStack {
            if let client = conn?.client {
                RemoteImage(url: client.latestImageURL(camera: camera, height: 720), session: client.session)
                    .frame(width: size.width * zoom, height: size.height * zoom)
                    .offset(x: size.width * zoom / 2 - point.x * zoom, y: size.height * zoom / 2 - point.y * zoom)
            }
            Image(systemName: "plus").font(.system(size: 16, weight: .light)).foregroundStyle(color)
        }
        .frame(width: diameter, height: diameter)
        .clipShape(Circle())
        .overlay(Circle().stroke(.white, lineWidth: 2))
        .shadow(radius: 6)
        .position(x: min(max(point.x, diameter / 2), size.width - diameter / 2),
                  y: point.y > diameter + 20 ? point.y - diameter / 2 - 24 : point.y + diameter / 2 + 24)
        .allowsHitTesting(false)
    }

    private func insert(after i: Int) {
        let a = draft.points[i], b = draft.points[(i + 1) % draft.points.count]
        draft.points.insert(CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2), at: i + 1)
    }

    private func remove(_ i: Int) {
        guard draft.points.count > 3, dragging == nil else { return }
        draft.points.remove(at: i)
    }

    // MARK: Saving

    private func loadLabels() async {
        guard let cfg = try? await conn?.client?.rawConfig() else { return }
        let cam = (cfg["cameras"] as? [String: Any])?[camera] as? [String: Any]
        let tracked = ((cam?["objects"] as? [String: Any])?["track"] as? [String]) ?? []
        labels = Array(Set(tracked + draft.objects)).sorted { FrigateFormat.labelName($0) < FrigateFormat.labelName($1) }
    }

    /// Where the shape sits in a camera's config, and the topic that applies a change at once.
    private func location(_ name: String, value: Any) -> (data: [String: Any], topic: String) {
        switch draft.kind {
        case .zone: return (["zones": [name: value]], "config/cameras/\(camera)/zones")
        case .motionMask: return (["motion": ["mask": [name: value]]], "config/cameras/\(camera)/motion")
        case .objectMask:
            if let label = draft.label {
                return (["objects": ["filters": [label: ["mask": [name: value]]]]], "config/cameras/\(camera)/objects")
            }
            return (["objects": ["mask": [name: value]]], "config/cameras/\(camera)/objects")
        }
    }

    private func save() async {
        guard let client = conn?.client else { return }
        let title = draft.title.trimmingCharacters(in: .whitespaces)
        let name = draft.isNew ? ZoneDraft.key(for: title) : draft.name
        guard !name.isEmpty else { errorMessage = L("Give it a name with letters or numbers."); return }
        guard !draft.isNew || !taken.contains(name) else { errorMessage = L("There is already one with this name."); return }
        saving = true
        defer { saving = false }
        var value: [String: Any] = ["coordinates": draft.coordinates, "enabled": draft.enabled]
        if title != name { value["friendly_name"] = title }
        if draft.kind == .zone {
            value["inertia"] = Int(draft.inertia)
            value["loitering_time"] = Int(draft.loiteringTime)
            if !draft.objects.isEmpty { value["objects"] = draft.objects } else if !(original?.objects.isEmpty ?? true) { value["objects"] = NSNull() }
        }
        let (data, topic) = location(name, value: value)
        do {
            try await client.setConfig(["cameras": [camera: data]], requiresRestart: false, updateTopic: topic)
            await conn?.refresh(reloadConfig: true)
            onDone()
            closePresented(dismiss)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func delete() async {
        guard let client = conn?.client else { return }
        saving = true
        deleting = true
        defer { saving = false; deleting = false }
        let (data, topic) = location(draft.name, value: NSNull())
        do {
            try await client.setConfig(["cameras": [camera: data]], requiresRestart: false, updateTopic: topic)
            // A deleted zone also leaves the review settings that name it, as Frigate's own page does.
            if draft.kind == .zone, let cfg = try? await client.rawConfig(),
               let review = (((cfg["cameras"] as? [String: Any])?[camera] as? [String: Any])?["review"] as? [String: Any]) {
                var update: [String: Any] = [:]
                for part in ["alerts", "detections"] {
                    let zones = ((review[part] as? [String: Any])?["required_zones"] as? [String]) ?? []
                    if zones.contains(draft.name) { update[part] = ["required_zones": zones.filter { $0 != draft.name }] }
                }
                if !update.isEmpty {
                    try await client.setConfig(["cameras": [camera: ["review": update]]], requiresRestart: false,
                                               updateTopic: "config/cameras/\(camera)/review")
                }
            }
            await conn?.refresh(reloadConfig: true)
            onDone()
            closePresented(dismiss)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
