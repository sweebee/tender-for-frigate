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
                        .font(.system(size: 16))
                        .padding(.horizontal, 14).frame(height: 46)
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
        let cams = conn.config?.orderedCameras ?? []
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
                    Toggle(isOn: Binding(get: { cameras[id]?.contains(cam.name) == true },
                                         set: { on in
                                             var set = cameras[id] ?? []
                                             if on { set.insert(cam.name) } else { set.remove(cam.name) }
                                             cameras[id] = set
                                         })) {
                        Text(cam.displayName).font(.system(size: 15))
                    }
                    .padding(.horizontal, 14).frame(minHeight: 48)
                    .overlay(alignment: .top) { if i > 0 { Rectangle().fill(Theme.cardBorder).frame(height: 1) } }
                }
            }
            .card(radius: 12)
        }
    }

    private func fill() {
        guard let old = target.name else { nameFocused = true; return }
        name = old
        var foundIcon = false
        for conn in conns {
            guard let g = conn.config?.cameraGroups?[old] else { continue }
            cameras[conn.server.id] = Set(g.cameras)
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
            let all = conn.config?.orderedCameras.map(\.name) ?? []
            // Frigate's camera order, then any member the app does not list (kept as it was).
            let members = all.filter(chosen.contains) + chosen.filter { !all.contains($0) }.sorted()
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
