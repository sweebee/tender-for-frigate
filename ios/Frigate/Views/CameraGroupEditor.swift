import SwiftUI

/// What the editor works on: a new group, or an existing one by name. A group with one name is
/// one group in the app, whichever servers have it.
struct CameraGroupTarget: Identifiable {
    /// nil: a new group.
    let name: String?
    /// The servers it may change (where this user is admin).
    let serverIDs: [UUID]
    var id: String { name ?? "+new" }
}

/// Adding or editing a camera group, as Frigate's group editor in Live: a name, an icon and the
/// cameras in it, here from all your servers at once. Frigate keeps groups per server, so on
/// saving every server with cameras chosen gets a group of this name and icon with its own
/// cameras, and a server left without cameras loses it. Saved at once (no restart).
struct CameraGroupEditor: View {
    let target: CameraGroupTarget
    /// Saved, with the group's (possibly new) name.
    let onDone: (String) -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var icon = "LuHouse"
    /// Chosen cameras per server.
    @State private var cameras: [UUID: Set<String>] = [:]
    /// Every camera per server in the order of the list; the chosen ones are saved in this order.
    @State private var order: [UUID: [String]] = [:]
    /// The row being dragged by its handle, where it started and how far it is from its slot.
    @State private var dragging: (server: UUID, camera: String, from: Int)?
    @State private var dragOffset: CGFloat = 0
    private let rowHeight: CGFloat = 48
    @State private var saving = false
    @State private var errorMessage: String?
    @FocusState private var nameFocused: Bool

    private var conns: [ServerConnection] {
        target.serverIDs.compactMap { model.connection(for: $0) }.filter { $0.config != nil }
    }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }
    private var chosenCount: Int { cameras.values.reduce(0) { $0 + $1.count } }

    /// As Frigate's form allows: a name without a dot, not "default", not taken by another group.
    private var nameProblem: String? {
        let n = trimmedName
        if n.isEmpty { return nil }
        if n.contains(".") { return L("The name cannot contain a period.") }
        if n.lowercased() == "default" { return L("This name is reserved.") }
        if n != target.name && conns.contains(where: { $0.config?.cameraGroups?[n] != nil }) {
            return L("There is already a group with this name.")
        }
        return nil
    }
    private var canSave: Bool { !trimmedName.isEmpty && nameProblem == nil && chosenCount > 0 && !saving }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    TextField("", text: $name, prompt: Text("Name").foregroundStyle(Theme.textSecondary))
                        .focused($nameFocused)
                        .autocorrectionDisabled()
                        .focusEffectDisabled()
                        .font(.system(size: 16))
                        .padding(.horizontal, 14).frame(height: 46)
                        // On the Mac a field in a column is only as wide as its text (none yet) otherwise.
                        .frame(maxWidth: .infinity)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(nameFocused ? Theme.accent : Theme.cardBorder, lineWidth: 1))
                    if let nameProblem {
                        Text(nameProblem).font(.system(size: 12)).foregroundStyle(Theme.destructive).padding(.horizontal, 4)
                    }
                    SectionLabel(text: L("Icon")).padding(.top, 10)
                    NavigationLink { LucideIconPicker(selection: $icon) } label: {
                        HStack(spacing: 12) {
                            LucideIcon(name: icon, size: 22).foregroundStyle(Theme.accent)
                                .frame(width: 40, height: 40)
                                .background(Theme.accent.opacity(0.16), in: RoundedRectangle(cornerRadius: 10))
                            Text(LucideIcons.words(icon).capitalizedFirst).font(.system(size: 15)).foregroundStyle(Theme.text)
                            Spacer()
                            Text("Change").font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                            Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.textSecondary)
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .card(radius: 12)
                    }
                    .buttonStyle(.plain)
                    ForEach(conns) { conn in serverSection(conn) }
                }
                .padding(16)
                .readableWidth(560)
            }
            .screenBackground()
            .navigationTitle(target.name == nil ? L("New group") : L("Edit group"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { closePresented(dismiss) }.plainBarButton() }
                ToolbarItem(placement: .confirmationAction) {
                    Button { Task { await save() } } label: {
                        if saving { ProgressView() } else { Text("Save") }
                    }
                    .confirmingBarButton()
                    .disabled(!canSave)
                }
            }
            .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(errorMessage ?? "") }
        }
        .onAppear(perform: fill)
        .largeSheetOnWideScreens()
    }

    /// One server's cameras; with one server just "Cameras", as before.
    private func serverSection(_ conn: ServerConnection) -> some View {
        let id = conn.server.id
        let cams = listed(conn)
        return VStack(alignment: .leading, spacing: 8) {
            if conns.count > 1 {
                HStack(spacing: 8) {
                    ServerBadge(initial: conn.server.initial, tint: conn.server.tint)
                    SectionLabel(text: conn.server.name)
                }
                .padding(.top, 10)
            } else {
                SectionLabel(text: L("Cameras")).padding(.top, 10)
            }
            VStack(spacing: 0) {
                ForEach(Array(cams.enumerated()), id: \.element.name) { i, cam in
                    let lifted = dragging?.server == id && dragging?.camera == cam.name
                    HStack(spacing: 4) {
                        dragHandle(id, cam, index: i, count: cams.count)
                        Toggle(isOn: Binding(get: { cameras[id]?.contains(cam.name) == true },
                                         set: { on in
                                             var set = cameras[id] ?? []
                                             if on { set.insert(cam.name) } else { set.remove(cam.name) }
                                             cameras[id] = set
                                         })) {
                        Text(cam.displayName).font(.system(size: 15))
                    }
                        // Explicitly: in a sheet the Mac drew the app-wide style as checkboxes.
                        .toggleStyle(RowSwitchStyle())
                    }
                    .padding(.leading, 2).padding(.trailing, 14).frame(height: rowHeight)
                    .frame(maxWidth: .infinity)
                    .overlay(alignment: .top) { if i > 0 && !lifted { Rectangle().fill(Theme.cardBorder).frame(height: 1) } }
                    .background { if lifted { RoundedRectangle(cornerRadius: 12).fill(Theme.card).shadow(color: .black.opacity(0.4), radius: 8, y: 2) } }
                    .offset(y: lifted ? dragOffset : 0)
                    .zIndex(lifted ? 1 : 0)
                }
            }
            .coordinateSpace(.named("group-cameras"))
            .card(radius: 12)
        }
    }

    /// The cameras of a server in the order of the list: as set here, else the group's order
    /// with the other cameras after it in Frigate's order.
    private func listed(_ conn: ServerConnection) -> [CameraConfig] {
        let cams = conn.config?.orderedCameras ?? []
        guard let names = order[conn.server.id] else { return cams }
        let byName = Dictionary(cams.map { ($0.name, $0) }, uniquingKeysWith: { a, _ in a })
        return names.compactMap { byName[$0] } + cams.filter { !names.contains($0.name) }
    }

    /// Drag the row up or down by this handle; it takes the place of the rows it passes.
    private func dragHandle(_ id: UUID, _ cam: CameraConfig, index: Int, count: Int) -> some View {
        Image(systemName: "line.3.horizontal")
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(Theme.textSecondary)
            .frame(width: 40, height: rowHeight)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .named("group-cameras"))
                    .onChanged { v in
                        if dragging == nil { dragging = (id, cam.name, index) }
                        guard let d = dragging, d.camera == cam.name else { return }
                        let target = min(max(d.from + Int((v.translation.height / rowHeight).rounded()), 0), count - 1)
                        if target != index { withAnimation(.easeOut(duration: 0.15)) { move(id, cam.name, to: target) } }
                        dragOffset = v.translation.height - CGFloat(target - d.from) * rowHeight
                    }
                    .onEnded { _ in
                        withAnimation(.easeOut(duration: 0.15)) { dragging = nil; dragOffset = 0 }
                    }
            )
            .accessibilityLabel(L("Reorder"))
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: move(id, cam.name, to: min(index + 1, count - 1))
                case .decrement: move(id, cam.name, to: max(index - 1, 0))
                @unknown default: break
                }
            }
    }

    private func move(_ id: UUID, _ camera: String, to target: Int) {
        guard let conn = conns.first(where: { $0.server.id == id }) else { return }
        var names = listed(conn).map(\.name)
        guard let from = names.firstIndex(of: camera) else { return }
        names.remove(at: from)
        names.insert(camera, at: min(target, names.count))
        order[id] = names
    }

    private func fill() {
        guard let old = target.name else { nameFocused = true; return }
        name = old
        var foundIcon = false
        for conn in conns {
            guard let g = conn.config?.cameraGroups?[old] else { continue }
            cameras[conn.server.id] = Set(g.cameras)
            let all = conn.config?.orderedCameras.map(\.name) ?? []
            order[conn.server.id] = g.cameras.filter(all.contains) + all.filter { !g.cameras.contains($0) }
            if !foundIcon, let i = g.icon { icon = i; foundIcon = true }
        }
    }

    /// Writes the group to every server it is on now or gets cameras on; one at a time, so a
    /// failure says which server.
    private func save() async {
        guard canSave else { return }
        saving = true
        defer { saving = false }
        let newName = trimmedName
        for conn in conns {
            guard let client = conn.client else { continue }
            let existing = conn.config?.cameraGroups ?? [:]
            let had = target.name.flatMap { existing[$0] }
            let chosen = cameras[conn.server.id] ?? []
            let all = listed(conn).map(\.name)
            // The order of the list (Tender shows the group in it), then any member the app does
            // not list, kept as it was.
            let members = all.filter(chosen.contains) + (had?.cameras ?? chosen.sorted()).filter { chosen.contains($0) && !all.contains($0) }
            var groups: [String: Any] = [:]
            if !members.isEmpty {
                // A group new on this server goes last, as in Frigate; an existing one keeps its place.
                let order = had?.order ?? ((existing.values.compactMap(\.order).max() ?? existing.count) + 1)
                groups[newName] = ["order": order, "icon": icon, "cameras": members]
            }
            // Renamed, or no cameras left here: the old key goes, as Frigate's editor does it.
            if let old = target.name, had != nil, old != newName || members.isEmpty { groups[old] = "" }
            if groups.isEmpty { continue }
            do {
                try await client.setConfig(["camera_groups": groups], requiresRestart: false, updateTopic: nil)
                await conn.refresh(reloadConfig: true)
            } catch {
                errorMessage = L("\(conn.server.name): \(error.localizedDescription)")
                return
            }
        }
        onDone(newName)
        closePresented(dismiss)
    }
}

/// All of Frigate's group icons, searchable by name as in Frigate's icon picker.
struct LucideIconPicker: View {
    @Binding var selection: String
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @FocusState private var focused: Bool

    private var shown: [String] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return LucideIcons.names }
        let compact = q.replacingOccurrences(of: " ", with: "")
        return LucideIcons.names.filter { $0.lowercased().contains(compact) || LucideIcons.words($0).contains(q) }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(Theme.textSecondary)
                    TextField("", text: $query, prompt: Text("Search icons").foregroundStyle(Theme.textSecondary))
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                        .focused($focused)
                    if !query.isEmpty {
                        Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.textSecondary) }
                            .buttonStyle(.plain).accessibilityLabel("Clear search")
                    }
                }
                .font(.system(size: 16))
                .padding(.horizontal, 12).frame(height: 40)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(focused ? Theme.accent : Theme.controlBorder, lineWidth: 1))
                let list = shown
                if list.isEmpty {
                    Text("No icons found").font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                        .frame(maxWidth: .infinity, minHeight: 100)
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 46), spacing: 8)], spacing: 8) {
                    ForEach(list, id: \.self) { name in
                        let on = selection == name
                        Button { selection = name; dismiss() } label: {
                            LucideIcon(name: name, size: 22)
                                .foregroundStyle(on ? Theme.accent : Theme.text)
                                .frame(width: 46, height: 46)
                                .background(on ? Theme.accent.opacity(0.16) : Theme.card, in: RoundedRectangle(cornerRadius: 10))
                                .overlay(RoundedRectangle(cornerRadius: 10).stroke(on ? Theme.accent : Theme.cardBorder, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
            }
            .padding(16)
            .readableWidth(560)
        }
        .scrollDismissesKeyboard(.immediately)
        .screenBackground()
        .navigationTitle(L("Icon"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
