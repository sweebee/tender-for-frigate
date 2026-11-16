import SwiftUI

/// Icons for camera groups. Frigate stores a Lucide icon name (its web UI's icon set); the app
/// offers a handful of those, each drawn with a matching SF Symbol.
enum GroupIcon {
    static let choices: [(lucide: String, symbol: String)] = [
        ("LuHouse", "house"), ("LuCar", "car"), ("LuDoorOpen", "door.left.hand.open"), ("LuTreePine", "tree"),
        ("LuCctv", "web.camera"), ("LuBuilding", "building.2"), ("LuShield", "shield"), ("LuPackage", "shippingbox"),
        ("LuSun", "sun.max"), ("LuDog", "dog"), ("LuBike", "bicycle"), ("LuWarehouse", "building"),
    ]

    /// The SF Symbol for a Frigate icon name, when the app knows it.
    static func symbol(_ lucide: String?) -> String? {
        guard let lucide else { return nil }
        return choices.first { $0.lucide == lucide }?.symbol
    }
}

/// What the editor works on: a new group on a server, or an existing one.
struct CameraGroupTarget: Identifiable {
    let serverID: UUID
    /// nil: a new group.
    let name: String?
    var id: String { serverID.uuidString + "/" + (name ?? "+new") }
}

/// Adding or editing a camera group, as Frigate's group editor in Live: a name, an icon and the
/// cameras in it. Saved in the config at once (no restart).
struct CameraGroupEditor: View {
    let target: CameraGroupTarget
    /// Saved, with the group's (possibly new) name.
    let onDone: (String) -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var icon = "LuHouse"
    @State private var cameras: Set<String> = []
    @State private var saving = false
    @State private var errorMessage: String?
    @FocusState private var nameFocused: Bool

    private var conn: ServerConnection? { model.connection(for: target.serverID) }
    private var existing: [String: CameraGroupConfig] { conn?.config?.cameraGroups ?? [:] }
    private var allCameras: [CameraConfig] { conn?.config?.orderedCameras ?? [] }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }

    /// As Frigate's form allows: a name without a dot, not "default", not taken by another group.
    private var nameProblem: String? {
        let n = trimmedName
        if n.isEmpty { return nil }
        if n.contains(".") { return L("The name cannot contain a period.") }
        if n.lowercased() == "default" { return L("This name is reserved.") }
        if n != target.name && existing[n] != nil { return L("There is already a group with this name.") }
        return nil
    }
    private var canSave: Bool { !trimmedName.isEmpty && nameProblem == nil && !cameras.isEmpty && !saving }

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
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 46), spacing: 8)], spacing: 8) {
                        ForEach(iconChoices, id: \.lucide) { choice in
                            let on = icon == choice.lucide
                            Button { icon = choice.lucide } label: {
                                Image(systemName: choice.symbol)
                                    .font(.system(size: 18, weight: .medium))
                                    .foregroundStyle(on ? Theme.accent : Theme.text)
                                    .frame(width: 46, height: 46)
                                    .background(on ? Theme.accent.opacity(0.16) : Theme.card, in: RoundedRectangle(cornerRadius: 10))
                                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(on ? Theme.accent : Theme.cardBorder, lineWidth: 1))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(choice.lucide.dropFirst(2))
                            .accessibilityAddTraits(on ? .isSelected : [])
                        }
                    }
                    SectionLabel(text: L("Cameras")).padding(.top, 10)
                    VStack(spacing: 0) {
                        ForEach(Array(allCameras.enumerated()), id: \.element.name) { i, cam in
                            Toggle(isOn: Binding(get: { cameras.contains(cam.name) },
                                                 set: { if $0 { cameras.insert(cam.name) } else { cameras.remove(cam.name) } })) {
                                Text(cam.displayName).font(.system(size: 15))
                            }
                            .padding(.horizontal, 14).frame(minHeight: 48)
                            .overlay(alignment: .top) { if i > 0 { Rectangle().fill(Theme.cardBorder).frame(height: 1) } }
                        }
                    }
                    .card(radius: 12)
                    if model.orderedConnections.count > 1, let conn {
                        Text(L("On \(conn.server.name)")).font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                    }
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

    /// The offered icons, plus the group's own when it is one the app does not draw: it stays.
    private var iconChoices: [(lucide: String, symbol: String)] {
        var list = GroupIcon.choices
        if GroupIcon.symbol(icon) == nil { list.insert((icon, "questionmark.square.dashed"), at: 0) }
        return list
    }

    private func fill() {
        guard let old = target.name, let g = existing[old] else {
            if target.name == nil { nameFocused = true }
            return
        }
        name = old
        icon = g.icon ?? icon
        cameras = Set(g.cameras)
    }

    private func save() async {
        guard let client = conn?.client, canSave else { return }
        saving = true
        defer { saving = false }
        let newName = trimmedName
        // A new group goes last, as in Frigate; an edited one keeps its place.
        let order = target.name.flatMap { existing[$0]?.order } ?? ((existing.values.compactMap(\.order).max() ?? existing.count) + 1)
        // Keep Frigate's camera order in the list.
        let members = allCameras.map(\.name).filter(cameras.contains)
            + cameras.filter { c in !allCameras.contains { $0.name == c } }.sorted()
        var groups: [String: Any] = [newName: ["order": order, "icon": icon, "cameras": members]]
        // Renamed: the old key goes, as Frigate's editor does it.
        if let old = target.name, old != newName { groups[old] = "" }
        do {
            try await client.setConfig(["camera_groups": groups], requiresRestart: false, updateTopic: nil)
            await conn?.refresh(reloadConfig: true)
            onDone(newName)
            closePresented(dismiss)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
