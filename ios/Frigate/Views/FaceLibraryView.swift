import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// The section in the rail (iPad, Mac), with a navigation stack of its own.
struct FaceLibrarySection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $model.facesPath) {
            FaceLibraryView(isSection: true).appDestinations()
        }
    }
}

/// Frigate's Face Library: recent recognitions to train as a person, and each person's face
/// images. One server at a time; with several, a chip picks which.
struct FaceLibraryView: View {
    var isSection = false
    @Environment(AppModel.self) private var model
    @State private var serverID: UUID?
    @State private var faces: [String: [String]] = [:]
    @State private var loaded = false
    @State private var role: String?
    enum Tab: Hashable { case recent, faces }
    @State private var tab: Tab = .recent
    /// Faces tab: the name whose images show.
    @State private var person: String?
    /// Recent tab: nil shows all; else only the attempts that got this name.
    @State private var recentFilter: String?
    @State private var selecting = false
    @State private var selection: Set<String> = []
    @State private var working = false
    @State private var opened: FaceSheetTarget?
    @State private var busyName: String?
    @State private var addingFace = false
    @State private var newName = ""
    @State private var renaming: String?
    @State private var deletingName: String?
    @State private var confirmDeleteSelection = false
    @State private var choosingPhoto = false
    @State private var choosingFile = false
    @State private var photoItem: PhotosPickerItem?
    @State private var uploading = false
    @State private var notice: String?
    @State private var errorMessage: String?

    private var connections: [ServerConnection] { model.faceConnections }
    private var conn: ServerConnection? {
        connections.first { $0.server.id == serverID } ?? connections.first
    }
    private var client: FrigateClient? { conn?.client }
    private var isAdmin: Bool { (role ?? "admin") == "admin" }
    private var names: [String] {
        faces.keys.filter { $0 != "train" }.sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }
    private var attempts: [ClassificationAttempt] {
        (faces["train"] ?? []).compactMap(ClassificationAttempt.init(file:)).sorted { $0.time > $1.time }
    }
    private var recognition: Double { conn?.config?.faceRecognitionThreshold ?? 0.9 }
    private var unknownScore: Double { conn?.config?.faceUnknownScore ?? 0.8 }

    /// Recent attempts per tracked object, the best try first; newest object first.
    private var groups: [[ClassificationAttempt]] {
        var order: [String] = []
        var byEvent: [String: [ClassificationAttempt]] = [:]
        for a in attempts {
            if byEvent[a.eventID] == nil { order.append(a.eventID) }
            byEvent[a.eventID, default: []].append(a)
        }
        return order.compactMap { byEvent[$0]?.sorted { $0.score > $1.score } }
    }

    private var shownGroups: [[ClassificationAttempt]] {
        guard let recentFilter else { return groups }
        return groups.filter { $0.first?.label == recentFilter }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                header
                if connections.count > 1 { serverPicker }
                if !loaded {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 160)
                } else if conn == nil {
                    empty(L("Face recognition is off on your servers."))
                } else {
                    SegmentedBar(options: [Tab.recent, .faces], selection: Binding(get: { tab }, set: { show($0) })) {
                        $0 == .recent ? L("Recent Recognitions") : L("Faces")
                    }
                    if tab == .faces || !groups.isEmpty { chips }
                    if let notice {
                        Label(notice, systemImage: "checkmark.circle.fill").font(.system(size: 13)).foregroundStyle(Theme.online)
                    }
                    grid
                    if tab == .faces, isAdmin, person != nil { uploadButton }
                }
            }
            .padding(.horizontal, 16).readableWidth()
            .padding(.bottom, selecting ? 90 : 24)
        }
        .refreshable { await Task { await load() }.value }
        .overlay(alignment: .bottom) { if selecting { selectionBar } }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task(id: conn?.server.id) { await load() }
        .sheet(item: $opened) { target in
            NavigationStack {
                FaceSheet(serverID: target.serverID, target: target, names: names, isAdmin: isAdmin,
                          recognition: recognition, unknownScore: unknownScore) { message in
                    opened = nil
                    if let message { notice = message }
                    Task { await load() }
                }
            }
            .environment(model)
            .largeSheetOnWideScreens()
        }
        .textPrompt(L("Add Face"), isPresented: $addingFace, text: $newName, placeholder: L("Name"), confirm: L("Add")) {
            Task { await createFace() }
        }
        .textPrompt(L("Rename Face"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } }),
                    text: $newName, placeholder: L("Name"), confirm: L("Rename")) {
            if let old = renaming { Task { await rename(old) } }
        }
        .alert(L("Delete \(deletingName ?? "")?"), isPresented: Binding(get: { deletingName != nil }, set: { if !$0 { deletingName = nil } })) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { if let n = deletingName { Task { await deleteName(n) } } }
        } message: { Text("This will permanently delete all associated faces.") }
        .alert(selection.count == 1 ? L("Delete 1 face?") : L("Delete \(selection.count) faces?"), isPresented: $confirmDeleteSelection) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { Task { await deleteSelection() } }
        } message: { Text("This action cannot be undone.") }
        .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
        .photosPicker(isPresented: $choosingPhoto, selection: $photoItem, matching: .images)
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            photoItem = nil
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                await upload(data)
            }
        }
        .fileImporter(isPresented: $choosingFile, allowedContentTypes: [.image]) { result in
            guard case .success(let url) = result else { return }
            let access = url.startAccessingSecurityScopedResource()
            let data = try? Data(contentsOf: url)
            if access { url.stopAccessingSecurityScopedResource() }
            Task { await upload(data) }
        }
    }

    // MARK: Header

    private var header: some View {
        HStack {
            if isSection { LargeTitle(text: L("Face Library")) } else { ScreenTitle(text: L("Face Library"), showsBack: true) }
            Spacer()
            if isAdmin && loaded && conn != nil {
                // Only when there is something to select on this tab.
                if selecting || (tab == .recent ? !groups.isEmpty : !(person.flatMap { faces[$0] } ?? []).isEmpty) {
                    Button { selecting.toggle(); selection = [] } label: {
                        HeaderTextLabel(title: selecting ? "Done" : "Select").fixedSize()
                    }
                    .buttonStyle(.plain)
                }
                Button { newName = ""; addingFace = true } label: { HeaderIconLabel(systemName: "plus", loading: busyName == "+new") }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Add Face")
            }
        }
        .padding(.top, 8)
    }

    private var serverPicker: some View {
        Menu {
            ForEach(connections) { c in
                Button { serverID = c.server.id } label: {
                    if c.server.id == conn?.server.id { Label(c.server.name, systemImage: "checkmark") } else { Text(c.server.name) }
                }
            }
        } label: {
            Chip(title: conn?.server.name ?? "", systemImage: "server.rack", trailingChevron: true)
        }
        .buttonStyle(.plain)
    }

    private func show(_ new: Tab) {
        guard new != tab else { return }
        tab = new
        selection = []
        if new == .faces, person.map({ faces[$0] == nil }) ?? true { person = names.first }
    }

    // MARK: Chips

    /// The names the recent attempts got: the library's names first, then others ("unknown").
    private var recentNames: [String] {
        let seen = groups.compactMap { $0.first?.label }
        var list = names.filter { seen.contains($0) }
        for n in seen where !list.contains(n) { list.append(n) }
        return list
    }

    private func displayName(_ n: String) -> String { n == "unknown" ? L("Unknown") : n }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                if tab == .recent {
                    chip(recentFilter == nil, L("All"), groups.count) { recentFilter = nil }
                    ForEach(recentNames, id: \.self) { n in
                        chip(recentFilter == n, displayName(n), groups.filter { $0.first?.label == n }.count) { recentFilter = n }
                    }
                } else {
                    ForEach(names, id: \.self) { n in
                        chip(person == n, n, faces[n]?.count ?? 0, busy: busyName == n) { person = n }
                            .contextMenu {
                                if isAdmin {
                                    Button { newName = n; renaming = n } label: { Label("Rename", systemImage: "pencil") }
                                    Button(role: .destructive) { deletingName = n } label: { DestructiveMenuLabel(title: "Delete") }
                                }
                            }
                    }
                }
            }
        }
    }

    private func chip(_ on: Bool, _ title: String, _ count: Int, busy: Bool = false, pick: @escaping () -> Void) -> some View {
        Button { pick(); selection = [] } label: {
            HStack(spacing: 5) {
                if busy { ProgressView().controlSize(.mini).tint(on ? Theme.onColor : Theme.text) }
                Text(title).lineLimit(1)
                Text("\(count)").foregroundStyle(on ? Theme.onColor.opacity(0.7) : Theme.textSecondary)
            }
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(on ? Theme.onColor : Theme.text)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(on ? Theme.accent : Theme.control, in: Capsule())
            .overlay(Capsule().stroke(on ? .clear : Theme.controlBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // MARK: Grid

    @ViewBuilder
    private var grid: some View {
        let columns = [GridItem(.adaptive(minimum: 104), spacing: 6)]
        if tab == .faces {
            if let p = person {
                let files = (faces[p] ?? []).sorted()
                if files.isEmpty {
                    empty(L("No faces available"))
                } else {
                    LazyVGrid(columns: columns, spacing: 6) {
                        ForEach(files, id: \.self) { file in
                            tile(url: client?.faceImageURL(p, file), files: [file], caption: nil, score: nil, count: 1) {
                                if let id = conn?.server.id { opened = FaceSheetTarget(serverID: id, files: [file], name: p) }
                            }
                        }
                    }
                }
            } else {
                empty(L("You must add at least one face to the library for face recognition to function."))
            }
        } else if groups.isEmpty {
            empty(names.isEmpty ? L("You must add at least one face to the library for face recognition to function.")
                                : L("There are no recent face recognition attempts"))
        } else if shownGroups.isEmpty {
            empty(L("No recent images with this result."))
        } else {
            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(shownGroups, id: \.first!.id) { group in
                    let best = group[0]
                    tile(url: client?.faceImageURL("train", best.file), files: group.map(\.file), caption: best.label,
                         score: best.score, count: group.count) {
                        if let id = conn?.server.id { opened = FaceSheetTarget(serverID: id, files: group.map(\.file), name: nil) }
                    }
                }
            }
        }
    }

    private func empty(_ text: String) -> some View {
        Text(text).font(.system(size: 13)).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 120).padding(.horizontal, 20)
    }

    private func tile(url: URL?, files: [String], caption: String?, score: Double?, count: Int, open: @escaping () -> Void) -> some View {
        let picked = !files.isEmpty && files.allSatisfy(selection.contains)
        return Button {
            if selecting {
                if picked { selection.subtract(files) } else { selection.formUnion(files) }
            } else { open() }
        } label: {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay { if let url, let client { RemoteImage(url: url, session: client.session) } }
                .overlay(alignment: .bottom) {
                    if let caption {
                        HStack(spacing: 4) {
                            Text(displayName(caption)).lineLimit(1)
                            Spacer(minLength: 2)
                            if let score, score > 0 {
                                Text("\(Int((score * 100).rounded()))%").foregroundStyle(FaceScore.color(score, recognition, unknownScore))
                            }
                        }
                        .font(.system(size: 11, weight: .semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 6).padding(.vertical, 4)
                        .background(.black.opacity(0.6))
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if selecting {
                        Image(systemName: picked ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 20)).foregroundStyle(picked ? Theme.accent : .white)
                            .shadow(radius: 2).padding(5)
                    } else if count > 1 {
                        Text("\(count)").font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                            .padding(.horizontal, 6).padding(.vertical, 2).background(.black.opacity(0.6), in: Capsule()).padding(5)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(picked ? Theme.accent : Theme.cardBorder, lineWidth: picked ? 2 : 1))
        }
        .buttonStyle(.plain)
    }

    private var uploadButton: some View {
        Menu {
            Button { choosingPhoto = true } label: { Label("Photo Library", systemImage: "photo.on.rectangle") }
            Button { choosingFile = true } label: { Label("Files", systemImage: "folder") }
        } label: {
            HStack(spacing: 8) {
                if uploading { ProgressView().controlSize(.small) } else { Image(systemName: "square.and.arrow.up") }
                Text("Upload Image")
            }
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Theme.text)
            .frame(maxWidth: .infinity).frame(height: 44)
            .card(border: Theme.controlBorder, radius: 12)
        }
        .menuStyle(.button).buttonStyle(.plain)
        .disabled(uploading)
        .padding(.top, 6)
    }

    private var selectionBar: some View {
        HStack(spacing: 10) {
            Text(selection.count == 1 ? L("1 face") : L("\(selection.count) faces")).font(.system(size: 14, weight: .semibold))
                .lineLimit(1).minimumScaleFactor(0.8)
            Spacer()
            Button { selectAll() } label: { Text("Select all").font(.system(size: 14, weight: .semibold)).lineLimit(1).fixedSize() }
                .buttonStyle(.plain).foregroundStyle(Theme.accent)
            Button { confirmDeleteSelection = true } label: {
                Group {
                    if working { ProgressView().tint(.white).controlSize(.small) } else { Image(systemName: "trash") }
                }
                .font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 40, height: 34)
                .background(Theme.destructive.opacity(selection.isEmpty ? 0.4 : 1), in: RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Delete")
            .disabled(selection.isEmpty || working)
        }
        .padding(14)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.controlBorder, lineWidth: 1))
        .padding(.horizontal, 12).padding(.bottom, 12)
        .readableWidth()
    }

    private func selectAll() {
        if tab == .faces, let p = person { selection = Set(faces[p] ?? []) } else { selection = Set(shownGroups.flatMap { $0.map(\.file) }) }
    }

    // MARK: Actions

    private func load() async {
        guard let client else { loaded = true; return }
        async let r = client.role()
        async let f = client.faces()
        faces = (try? await f) ?? [:]
        role = await r ?? "admin"
        if tab == .faces, person.map({ faces[$0] == nil }) ?? true { person = names.first }
        if let rf = recentFilter, !groups.contains(where: { $0.first?.label == rf }) { recentFilter = nil }
        loaded = true
    }

    private func createFace() async {
        let name = newName.trimmingCharacters(in: .whitespaces)
        guard let client, !name.isEmpty else { return }
        guard ClassificationNames.isValid(name) else { errorMessage = L("Use letters, numbers, spaces, ' _ or -, up to 50."); return }
        guard !names.contains(where: { $0.caseInsensitiveCompare(name) == .orderedSame }) else {
            errorMessage = L("There is already a face with this name."); return
        }
        busyName = "+new"
        defer { busyName = nil }
        do {
            try await client.createFace(name)
            await load()
            // Frigate stores the name with underscores for spaces.
            tab = .faces
            person = names.first { $0 == name.replacingOccurrences(of: " ", with: "_") } ?? names.first
        } catch { errorMessage = error.localizedDescription }
    }

    private func rename(_ old: String) async {
        let name = newName.trimmingCharacters(in: .whitespaces)
        guard let client, !name.isEmpty, name != old else { return }
        guard ClassificationNames.isValid(name) else { errorMessage = L("Use letters, numbers, spaces, ' _ or -, up to 50."); return }
        guard faces[name] == nil else { errorMessage = L("There is already a face with this name."); return }
        busyName = old
        defer { busyName = nil }
        do {
            try await client.renameFace(old, to: name)
            await load()
            person = names.first { $0 == name.replacingOccurrences(of: " ", with: "_") } ?? names.first
        } catch { errorMessage = error.localizedDescription }
    }

    private func deleteName(_ n: String) async {
        guard let client else { return }
        busyName = n
        defer { busyName = nil }
        do {
            try await client.deleteFaces(n, files: faces[n] ?? [])
            faces[n] = nil
            if person == n { person = names.first }
            await load()
        } catch { errorMessage = error.localizedDescription }
    }

    private func deleteSelection() async {
        guard let client, !selection.isEmpty else { return }
        working = true
        defer { working = false }
        do {
            try await client.deleteFaces(tab == .faces ? (person ?? "train") : "train", files: Array(selection))
            selection = []
            selecting = false
            await load()
        } catch { errorMessage = error.localizedDescription }
    }

    /// Sends a picture for the name on screen. Photos may be HEIC, which Frigate cannot read: it
    /// goes as JPEG.
    private func upload(_ data: Data?) async {
        guard let client, let p = person, let data else { return }
        guard let jpeg = UIImage(data: data)?.jpegData(compressionQuality: 0.9) else {
            errorMessage = L("This file is not a picture.")
            return
        }
        uploading = true
        defer { uploading = false }
        do {
            try await client.registerFace(p, jpeg: jpeg)
            notice = L("Successfully uploaded image.")
            await load()
        } catch { errorMessage = error.localizedDescription }
    }
}

/// Colours of face scores, as Frigate's face library shows them.
enum FaceScore {
    static func color(_ score: Double, _ recognition: Double, _ unknown: Double) -> Color {
        if score >= recognition { return Theme.online }
        if score >= unknown { return Theme.warning }
        return Theme.destructive
    }
}

struct FaceSheetTarget: Identifiable {
    let serverID: UUID
    /// The tries of one tracked object, or one image of a name.
    let files: [String]
    /// nil: recent attempts; else the name the image belongs to.
    let name: String?
    var id: String { (name ?? "") + files.joined() }
}

// MARK: - Train or move faces

/// Recent attempts of one object: train them as a person, reprocess or delete them. One image
/// of a person: move it to another person or delete it.
struct FaceSheet: View {
    let serverID: UUID
    let target: FaceSheetTarget
    let names: [String]
    let isAdmin: Bool
    let recognition: Double
    let unknownScore: Double
    /// Closes the sheet, with a message to show on the library when there is one.
    let onDone: (String?) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var picked: Set<String> = []
    @State private var working: String?
    @State private var addingFace = false
    @State private var newName = ""
    @State private var errorMessage: String?

    private var client: FrigateClient? { app.connection(for: serverID)?.client }
    private var attempts: [ClassificationAttempt] { target.files.compactMap(ClassificationAttempt.init(file:)) }
    private var targets: [String] { names.filter { $0 != target.name } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                images
                if target.name == nil, let best = attempts.max(by: { $0.score < $1.score }) {
                    Text(best.label == "unknown" ? L("Frigate did not recognize this face.")
                         : L("Frigate thought: \(best.label), \(Int((best.score * 100).rounded()))%"))
                        .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                }
                if isAdmin {
                    SectionLabel(text: target.name == nil ? L("Train Face as:") : L("Reclassify Face as:"))
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), spacing: 8)], spacing: 8) {
                        ForEach(targets, id: \.self) { n in
                            actionButton(n, key: n) { await assign(to: n) }
                        }
                        actionButton(L("New face"), key: "+new", icon: "plus", tint: Theme.accent) { addingFace = true }
                    }
                    if target.name == nil {
                        actionButton(L("Reprocess Face"), key: "-reprocess", icon: "arrow.clockwise") { await reprocess() }
                            .padding(.top, 6)
                    }
                    actionButton(picked.count > 1 ? L("Delete \(picked.count) faces") : L("Delete face"),
                                 key: "-delete", icon: "trash", tint: Theme.destructive) { await delete() }
                }
            }
            .padding(16)
        }
        .screenBackground()
        .navigationTitle(target.name ?? L("Recent Recognitions"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { closePresented(dismiss) }.plainBarButton() } }
        .onAppear { picked = Set(target.files) }
        .textPrompt(L("New face"), isPresented: $addingFace, text: $newName, placeholder: L("Name"), confirm: L("Add")) {
            Task { await addFace() }
        }
        .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    @ViewBuilder
    private var images: some View {
        if target.files.count == 1, let file = target.files.first {
            image(file).frame(maxWidth: 280, maxHeight: 280).frame(maxWidth: .infinity)
        } else {
            Text("The tries for this object; the ones ticked are used.")
                .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 90), spacing: 6)], spacing: 6) {
                ForEach(target.files, id: \.self) { file in
                    let on = picked.contains(file)
                    Button { if on { picked.remove(file) } else { picked.insert(file) } } label: {
                        image(file)
                            .overlay(alignment: .topTrailing) {
                                Image(systemName: on ? "checkmark.circle.fill" : "circle").font(.system(size: 18))
                                    .foregroundStyle(on ? Theme.accent : .white).shadow(radius: 2).padding(4)
                            }
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(on ? Theme.accent : .clear, lineWidth: 2))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func image(_ file: String) -> some View {
        Color.clear.aspectRatio(1, contentMode: .fit)
            .overlay {
                if let client { RemoteImage(url: client.faceImageURL(target.name ?? "train", file), session: client.session) }
            }
            .overlay(alignment: .bottom) {
                if target.name == nil, let a = ClassificationAttempt(file: file) {
                    HStack(spacing: 4) {
                        Text(a.label == "unknown" ? L("Unknown") : a.label).lineLimit(1)
                        Spacer(minLength: 2)
                        Text("\(Int((a.score * 100).rounded()))%").foregroundStyle(FaceScore.color(a.score, recognition, unknownScore))
                    }
                    .font(.system(size: 11, weight: .semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 6).padding(.vertical, 4)
                    .background(.black.opacity(0.6))
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func actionButton(_ title: String, key: String, icon: String? = nil, tint: Color = Theme.text,
                              action: @escaping () async -> Void) -> some View {
        Button { Task { working = key; await action(); working = nil } } label: {
            HStack(spacing: 6) {
                if working == key { ProgressView().controlSize(.small) } else if let icon { Image(systemName: icon) }
                Text(title).lineLimit(1)
            }
            .font(.system(size: 14, weight: .semibold)).foregroundStyle(tint)
            .frame(maxWidth: .infinity, minHeight: 40)
            .background(Theme.control, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.controlBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(working != nil || (picked.isEmpty && key != "+new"))
    }

    private func assign(to name: String) async {
        guard let client else { return }
        do {
            for file in target.files where picked.contains(file) {
                if let from = target.name {
                    try await client.reclassifyFace(file, from: from, to: name)
                } else {
                    try await client.trainFace(file, as: name)
                }
            }
            // Frigate moves a trained try into the name's images; the tries not ticked stay, as on its page.
            onDone(target.name == nil ? L("Successfully trained face.") : L("Successfully reclassified face."))
        } catch { errorMessage = error.localizedDescription }
    }

    private func addFace() async {
        let name = newName.trimmingCharacters(in: .whitespaces)
        newName = ""
        guard ClassificationNames.isValid(name) else { errorMessage = L("Use letters, numbers, spaces, ' _ or -, up to 50."); return }
        guard !names.contains(where: { $0.caseInsensitiveCompare(name) == .orderedSame }) else {
            errorMessage = L("There is already a face with this name."); return
        }
        working = "+new"
        defer { working = nil }
        do {
            try await client?.createFace(name)
            await assign(to: name.replacingOccurrences(of: " ", with: "_"))
        } catch { errorMessage = error.localizedDescription }
    }

    private func reprocess() async {
        guard let client else { return }
        do {
            for file in target.files where picked.contains(file) { try await client.reprocessFace(file) }
            onDone(nil)
        } catch { errorMessage = error.localizedDescription }
    }

    private func delete() async {
        guard let client else { return }
        do {
            try await client.deleteFaces(target.name ?? "train", files: target.files.filter(picked.contains))
            onDone(nil)
        } catch { errorMessage = error.localizedDescription }
    }
}
