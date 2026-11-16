import SwiftUI

// Frigate's Classification page: custom models that put tracked objects into classes, or tell
// the state of a fixed area. Per model the images Frigate saved recently can be put into a
// class, after which the model is trained again. Calls as Frigate's own page does.

/// The section in the rail (iPad, Mac), with a navigation stack of its own.
struct ClassificationSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $model.classificationPath) {
            ClassificationView(isSection: true).appDestinations()
        }
    }
}

/// The models of every server, as cards; Objects and States apart.
struct ClassificationView: View {
    var isSection = false
    @Environment(AppModel.self) private var model
    @State private var showStates = false
    @State private var models: [UUID: [ClassificationModel]] = [:]
    @State private var covers: [String: URL] = [:]
    @State private var roles: [UUID: String] = [:]
    @State private var loaded = false
    @State private var creating: CreateTarget?
    @State private var editing: ClassificationModel?
    @State private var deleting: ClassificationModel?
    @State private var removing: Set<String> = []
    @State private var errorMessage: String?

    struct CreateTarget: Identifiable { let serverID: UUID; let state: Bool; var id: UUID { serverID } }

    private var connections: [ServerConnection] { model.scopedConnections.filter(\.supportsClassification) }
    private func isAdmin(_ id: UUID) -> Bool { (roles[id] ?? "admin") == "admin" }
    private var adminConnections: [ServerConnection] { connections.filter { isAdmin($0.server.id) } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    if isSection { LargeTitle(text: L("Classification")) } else { ScreenTitle(text: L("Classification"), showsBack: true) }
                    Spacer()
                    addButton
                }
                .padding(.top, 8)
                Text("Teach Frigate to tell what it sees: which delivery service, or whether the gate is open.")
                    .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                Picker("", selection: $showStates) {
                    Text("Objects").tag(false)
                    Text("States").tag(true)
                }
                .pickerStyle(.segmented)
                .padding(.vertical, 4)

                if !loaded {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 160)
                } else if connections.isEmpty {
                    Text("Classification needs Frigate 0.17 or later.")
                        .font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                        .frame(maxWidth: .infinity, minHeight: 120)
                } else {
                    if !anyModels && connections.count > 1 {
                        // Nothing of this kind anywhere yet: what it is, once, then a row per server.
                        Text(showStates ? L("A state model looks at an area of a camera and tells its state, like open or closed.")
                                        : L("An object model puts a tracked object into a class, like which delivery service."))
                            .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                    }
                    ForEach(connections) { conn in serverSection(conn) }
                }
            }
            .padding(.horizontal, 16).readableWidth()
            .padding(.bottom, 24)
        }
        .refreshable { await load() }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(for: ClassificationModel.self) { ClassificationModelView(initial: $0) }
        .task { await load() }
        .sheet(item: $creating) { target in
            NavigationStack {
                ClassificationWizard(serverID: target.serverID, startAsState: target.state) { Task { await load() } }
            }
            .environment(model)
            .largeSheetOnWideScreens()
        }
        .sheet(item: $editing) { m in
            NavigationStack { ClassificationEditSheet(initial: m) { Task { await load() } } }
                .environment(model)
        }
        .alert(L("Delete \(deleting?.name ?? "")?"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { if let m = deleting { Task { await delete(m) } } }
        } message: {
            Text("The model, its classes and their images are removed from Frigate.")
        }
        .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    @ViewBuilder
    private var addButton: some View {
        let admins = adminConnections
        if admins.count == 1, let conn = admins.first {
            Button { creating = CreateTarget(serverID: conn.server.id, state: showStates) } label: { HeaderIconLabel(systemName: "plus") }
                .accessibilityLabel("New model")
        } else if admins.count > 1 {
            Menu {
                ForEach(admins) { conn in
                    Button(conn.server.name) { creating = CreateTarget(serverID: conn.server.id, state: showStates) }
                }
            } label: { HeaderIconLabel(systemName: "plus") }
            .accessibilityLabel("New model")
        }
    }

    @ViewBuilder
    private func serverSection(_ conn: ServerConnection) -> some View {
        let list = (models[conn.server.id] ?? []).filter { $0.isState == showStates }
        if connections.count > 1 { SectionLabel(text: conn.server.name).padding(.top, 8) }
        if list.isEmpty && connections.count == 1 {
            emptyExplanation(conn)
        } else if list.isEmpty {
            emptyRow(conn)
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 10)], spacing: 10) {
                ForEach(list) { m in card(m, conn: conn) }
            }
        }
    }

    /// Any server has a model of the kind shown.
    private var anyModels: Bool { connections.contains { (models[$0.server.id] ?? []).contains { $0.isState == showStates } } }

    private func emptyRow(_ conn: ServerConnection) -> some View {
        HStack {
            Text(showStates ? L("No state models yet") : L("No object models yet"))
                .font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
            Spacer()
            if isAdmin(conn.server.id) {
                Button { creating = CreateTarget(serverID: conn.server.id, state: showStates) } label: {
                    Label("Make a model", systemImage: "plus").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.accent)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .card(radius: 12)
    }

    private func emptyExplanation(_ conn: ServerConnection) -> some View {
        VStack(spacing: 10) {
            Image(systemName: showStates ? "square.dashed" : "square.on.circle")
                .font(.system(size: 26)).foregroundStyle(Theme.textSecondary)
            Text(showStates ? L("No state models yet") : L("No object models yet"))
                .font(.system(size: 15, weight: .semibold))
            Text(showStates ? L("A state model looks at an area of a camera and tells its state, like open or closed.")
                            : L("An object model puts a tracked object into a class, like which delivery service."))
                .font(.system(size: 13)).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
            if isAdmin(conn.server.id) {
                Button { creating = CreateTarget(serverID: conn.server.id, state: showStates) } label: {
                    Text("Make a model").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.onColor)
                        .padding(.horizontal, 16).padding(.vertical, 9)
                        .background(Theme.accent, in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 22).padding(.horizontal, 16)
        .card(radius: 12)
    }

    private func card(_ m: ClassificationModel, conn: ServerConnection) -> some View {
        NavigationLink(value: m) {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    if let url = covers[m.id], let client = conn.client {
                        RemoteImage(url: url, session: client.session)
                    } else {
                        Image(systemName: m.isState ? "square.dashed" : "square.on.circle")
                            .font(.system(size: 30)).foregroundStyle(Theme.textSecondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity).background(Theme.control)
                    }
                }
                .grayscale(m.enabled ? 0 : 1)
                .overlay(alignment: .bottomLeading) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(m.name).font(.system(size: 15, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                        Text(subtitle(m, conn: conn)).font(.system(size: 11)).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.black.opacity(0.55))
                }
                .overlay(alignment: .topTrailing) {
                    if removing.contains(m.id) {
                        ProgressView().padding(8).background(.black.opacity(0.5), in: Circle()).padding(8)
                    } else if !m.enabled {
                        Text("Off").font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.onColor)
                            .padding(.horizontal, 7).padding(.vertical, 3).background(Theme.warning, in: Capsule()).padding(8)
                    } else if conn.modelStates[m.name] == "training" {
                        Label("Training", systemImage: "gearshape.2").font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                            .padding(.horizontal, 7).padding(.vertical, 3).background(Theme.accent, in: Capsule()).padding(8)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.cardBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(removing.contains(m.id))
        .contextMenu {
            if isAdmin(m.serverID) {
                Button { editing = m } label: { Label("Edit", systemImage: "pencil") }
                Button(role: .destructive) { deleting = m } label: { DestructiveMenuLabel(title: "Delete") }
            }
        }
    }

    private func subtitle(_ m: ClassificationModel, conn: ServerConnection) -> String {
        switch m.kind {
        case .object(let label, let attribute):
            return FrigateFormat.labelName(label) + " · " + (attribute ? L("attribute") : L("sub label"))
        case .state(let cameras, _, _):
            return cameras.keys.sorted().map { conn.config?.cameras[$0]?.displayName ?? FrigateFormat.prettyName($0) }.joined(separator: ", ")
        }
    }

    private func load() async {
        await withTaskGroup(of: (UUID, [ClassificationModel], [String: URL], String?).self) { group in
            for conn in connections {
                guard let client = conn.client else { continue }
                let id = conn.server.id
                conn.requestModelStates()
                group.addTask {
                    async let role = client.role()
                    guard let cfg = try? await client.rawConfig() else { return (id, [], [:], await role) }
                    let list = ClassificationModel.all(in: cfg, serverID: id)
                    var covers: [String: URL] = [:]
                    for m in list {
                        guard let set = try? await client.classificationDataset(m.name) else { continue }
                        if let cls = set.classNames.first(where: { $0 != "none" && $0 != "unknown" && !(set.categories[$0] ?? []).isEmpty }),
                           let file = set.categories[cls]?.sorted().first {
                            covers[m.id] = client.datasetImageURL(m.name, cls, file)
                        }
                    }
                    return (id, list, covers, await role)
                }
            }
            for await (id, list, cov, role) in group {
                models[id] = list
                covers.merge(cov) { $1 }
                roles[id] = role ?? "admin"
            }
        }
        loaded = true
    }

    private func delete(_ m: ClassificationModel) async {
        guard let client = model.connection(for: m.serverID)?.client else { return }
        removing.insert(m.id)
        defer { removing.remove(m.id) }
        do {
            try await client.deleteClassificationModel(m.name)
            await load()
        } catch { errorMessage = error.localizedDescription }
    }
}

// MARK: - One model

/// A model's recent images and classes; put images in a class and train the model.
struct ClassificationModelView: View {
    let initial: ClassificationModel
    @Environment(AppModel.self) private var model
    @State private var current: ClassificationModel?
    @State private var dataset = ClassificationDataset()
    @State private var attempts: [ClassificationAttempt] = []
    enum Tab: Hashable { case recent, training }
    @State private var tab: Tab = .recent
    /// The class whose training images show; nil on the Recent tab.
    @State private var library: String?
    /// Recent tab: nil shows all; else only the images the model gave this class, or `unsure`.
    @State private var recentFilter: String?
    private static let unsure = "\u{0}unsure"
    @State private var loaded = false
    @State private var role: String?
    @State private var assigning: AssignTarget?
    @State private var selecting = false
    @State private var selection: Set<String> = []
    @State private var working = false
    /// The class being renamed or deleted on the server: its chip shows a spinner.
    @State private var busyClass: String?
    @State private var moving = false
    @State private var trainingAsked = false
    @State private var notice: String?
    @State private var renaming: String?
    @State private var newName = ""
    @State private var deletingClass: String?
    @State private var deletingModel = false
    @State private var confirmDeleteSelection = false
    @State private var editing = false
    @State private var errorMessage: String?
    @Environment(\.dismiss) private var dismiss

    struct AssignTarget: Identifiable {
        /// The images, all from one object, or one labelled image.
        let files: [String]
        /// nil: recent images; else the class they are in.
        let category: String?
        var id: String { (category ?? "") + files.joined() }
    }

    private var m: ClassificationModel { current ?? initial }
    private var conn: ServerConnection? { model.connection(for: m.serverID) }
    private var client: FrigateClient? { conn?.client }
    private var isAdmin: Bool { (role ?? "admin") == "admin" }
    private var state: String? { conn?.modelStates[m.name] }
    private var training: Bool { state == "training" }
    private var reclassifies: Bool { conn?.version.map { $0.compare("0.18", options: .numeric) != .orderedAscending } ?? false }

    /// Recent images per object (object models keep up to 16 tries of one object), else one by one.
    private var groups: [[ClassificationAttempt]] {
        if m.isState { return attempts.map { [$0] } }
        var order: [String] = []
        var byEvent: [String: [ClassificationAttempt]] = [:]
        for a in attempts {
            if byEvent[a.eventID] == nil { order.append(a.eventID) }
            byEvent[a.eventID, default: []].append(a)
        }
        return order.compactMap { byEvent[$0]?.sorted { $0.score > $1.score } }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                header
                if !loaded {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 160)
                } else {
                    trainCard
                    SegmentedBar(options: [Tab.recent, .training], selection: Binding(get: { tab }, set: { show($0) })) {
                        $0 == .recent ? L("Recent") : L("Training images")
                    }
                    chips
                    if let notice {
                        Label(notice, systemImage: "checkmark.circle.fill").font(.system(size: 13)).foregroundStyle(Theme.online)
                    }
                    grid
                }
            }
            .padding(.horizontal, 16).readableWidth()
            .padding(.bottom, selecting ? 90 : 24)
        }
        .refreshable { await load() }
        .overlay(alignment: .bottom) { if selecting { selectionBar } }
        .screenBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
        .onAppear { conn?.requestModelStates() }
        .onChange(of: state) { old, new in
            guard old == "training" || trainingAsked else { return }
            if new == "complete" || new == "downloaded" {
                trainingAsked = false
                notice = L("Model trained")
                // Frigate writes the training's details just after it reports the model done.
                Task {
                    for _ in 0..<6 {
                        let before = dataset.lastTrained
                        await load()
                        if dataset.hasTrained && dataset.lastTrained != before && !dataset.changed { break }
                        try? await Task.sleep(for: .seconds(1))
                    }
                }
            } else if new == "failed" {
                trainingAsked = false
                errorMessage = L("Training failed. Each class needs images; there must be at least two classes.")
            }
        }
        .sheet(item: $assigning) { target in
            NavigationStack {
                ClassificationAssignSheet(model: m, target: target, classes: dataset.classNames, reclassifies: reclassifies,
                                          isAdmin: isAdmin) {
                    // Closed from here: on the Mac the sheet's own dismiss does not always take.
                    assigning = nil
                    Task { await load() }
                }
            }
            .environment(model)
            .largeSheetOnWideScreens()
        }
        .sheet(isPresented: $editing) {
            NavigationStack { ClassificationEditSheet(initial: m) { Task { await reloadModel() } } }.environment(model)
        }
        .textPrompt(L("Rename class"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } }),
                    text: $newName, placeholder: L("Name"), confirm: L("Rename")) {
            if let old = renaming { Task { await rename(old, to: newName) } }
        }
        .alert(L("Delete \(deletingClass.map(className) ?? "")?"), isPresented: Binding(get: { deletingClass != nil }, set: { if !$0 { deletingClass = nil } })) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { if let c = deletingClass { Task { await deleteClass(c) } } }
        } message: { Text("The class and all its images are removed. Train the model again afterwards.") }
        .alert(selection.count == 1 ? L("Delete 1 image?") : L("Delete \(selection.count) images?"), isPresented: $confirmDeleteSelection) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { Task { await deleteSelection() } }
        } message: {
            Text(library == nil ? L("They are removed from the recent images.") : L("They are removed from the class. Train the model again afterwards."))
        }
        .alert(L("Delete \(m.name)?"), isPresented: $deletingModel) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { Task { await deleteModel() } }
        } message: { Text("The model, its classes and their images are removed from Frigate.") }
        .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                ScreenTitle(text: m.name, showsBack: true)
                Spacer()
                if isAdmin && loaded {
                    Button { selecting.toggle(); selection = [] } label: {
                        HeaderTextLabel(title: selecting ? "Done" : "Select").fixedSize()
                    }
                    .buttonStyle(.plain)
                    if working && !selecting {
                        HeaderIconLabel(systemName: "ellipsis", loading: true)
                    } else {
                        Menu {
                            Button { editing = true } label: { Label("Edit", systemImage: "pencil") }
                            Button(role: .destructive) { deletingModel = true } label: { DestructiveMenuLabel(title: "Delete model") }
                        } label: { HeaderIconLabel(systemName: "ellipsis") }
                    }
                }
            }
            .padding(.top, 8)
            Text(kindLine).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
        }
    }

    private var kindLine: String {
        switch m.kind {
        case .object(let label, let attribute):
            return L("Object") + " · " + FrigateFormat.labelName(label) + " · " + (attribute ? L("attribute") : L("sub label"))
        case .state(let cameras, _, _):
            return L("State") + " · " + cameras.keys.sorted().map { conn?.config?.cameras[$0]?.displayName ?? FrigateFormat.prettyName($0) }.joined(separator: ", ")
        }
    }

    private var trainCard: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(statusTitle).font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(state == "failed" ? Theme.destructive : Theme.text)
                Text(statusDetail).font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
            }
            Spacer(minLength: 8)
            if isAdmin {
                Button { Task { await train() } } label: {
                    HStack(spacing: 6) {
                        if training || working && trainingAsked { ProgressView().tint(Theme.onColor).controlSize(.small) }
                        Text(training ? L("Training…") : L("Train model")).lineLimit(1)
                    }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.onColor)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(Theme.accent.opacity(canTrain || training ? 1 : 0.35), in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .disabled(!canTrain)
            }
        }
        .padding(14)
        .card(radius: 12)
    }

    private var canTrain: Bool { !training && !trainingAsked && (dataset.changed || state == "failed") && dataset.categories.count >= 2 }

    private var statusTitle: String {
        if training { return L("Training") }
        if state == "failed" { return L("Training failed") }
        if !dataset.hasTrained { return L("Not trained yet") }
        // After a move Frigate's record says "trained now, 0 images": its date says nothing then.
        if dataset.lastTrainedImages == 0 { return L("Train again") }
        if let d = dataset.lastTrained { return L("Trained \(FrigateFormat.relative(d).lowercased())") }
        return L("Trained")
    }

    private var statusDetail: String {
        if training { return L("Frigate is training the model; this can take a few minutes.") }
        if dataset.categories.count < 2 { return L("Put images in at least two classes first.") }
        // Moving or renaming makes Frigate count every image as new; then say the classes changed.
        if dataset.newImages > 0 && (dataset.newImages < dataset.totalImages || !dataset.hasTrained) { return dataset.newImages == 1 ? L("1 new image since the last training") : L("\(dataset.newImages) new images since the last training") }
        if dataset.changed { return L("The classes changed since the last training.") }
        return L("Up to date.")
    }

    private func className(_ c: String) -> String { c == "none" ? L("None") : c }

    private func show(_ new: Tab) {
        guard new != tab else { return }
        tab = new
        selection = []
        library = new == .training ? dataset.classNames.first : nil
    }

    /// Whether the model was not sure enough to give the object or image a class.
    private func isUnsure(_ a: ClassificationAttempt) -> Bool { a.score < m.threshold }

    private func matches(_ group: [ClassificationAttempt], _ filter: String?) -> Bool {
        guard let filter, let best = group.first else { return true }
        return filter == Self.unsure ? isUnsure(best) : best.label == filter
    }

    /// The recent groups the chips leave in view.
    private var shownGroups: [[ClassificationAttempt]] { groups.filter { matches($0, recentFilter) } }

    /// The classes the recent images got: the model's classes first, then any other result.
    private var recentLabels: [String] {
        let seen = groups.compactMap { $0.first?.label }
        var labels = dataset.classNames.filter { seen.contains($0) }
        for l in seen where !labels.contains(l) { labels.append(l) }
        if let f = recentFilter, f != Self.unsure, !labels.contains(f) { labels.append(f) }
        return labels
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                if tab == .recent {
                    chip(recentFilter == nil, L("All"), groups.count) { recentFilter = nil }
                    ForEach(recentLabels, id: \.self) { c in
                        chip(recentFilter == c, c == "unknown" ? L("Unknown") : className(c), groups.filter { matches($0, c) }.count) { recentFilter = c }
                    }
                    let unsure = groups.filter { matches($0, Self.unsure) }.count
                    if unsure > 0 || recentFilter == Self.unsure {
                        chip(recentFilter == Self.unsure, L("Unsure"), unsure) { recentFilter = Self.unsure }
                    }
                } else {
                    ForEach(dataset.classNames, id: \.self) { c in
                        chip(library == c, className(c), dataset.categories[c]?.count ?? 0, busy: busyClass == c) { library = c }
                            .contextMenu {
                                if isAdmin && c != "none" {
                                    Button { newName = c; renaming = c } label: { Label("Rename", systemImage: "pencil") }
                                    if dataset.categories.count > 2 {
                                        Button(role: .destructive) { deletingClass = c } label: { DestructiveMenuLabel(title: "Delete") }
                                    }
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

    @ViewBuilder
    private var grid: some View {
        let columns = [GridItem(.adaptive(minimum: 104), spacing: 6)]
        if tab == .training && library == nil {
            empty(L("No classes yet."))
        } else if let cls = library {
            let files = (dataset.categories[cls] ?? []).sorted()
            if files.isEmpty {
                empty(L("No images in this class yet. Put recent images in it."))
            } else {
                LazyVGrid(columns: columns, spacing: 6) {
                    ForEach(files, id: \.self) { file in
                        tile(url: client?.datasetImageURL(m.name, cls, file), key: file, caption: nil, score: nil, count: 1) {
                            assigning = AssignTarget(files: [file], category: cls)
                        }
                    }
                }
            }
        } else if groups.isEmpty {
            empty(m.isState ? L("Frigate keeps the images it classifies here, to put them in the right class.")
                            : L("Frigate keeps the objects it classifies here, to put them in the right class."))
        } else if shownGroups.isEmpty {
            empty(L("No recent images with this result."))
        } else {
            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(shownGroups, id: \.first!.id) { group in
                    let best = group[0]
                    tile(url: client?.attemptImageURL(m.name, best.file), key: best.file, caption: best.label, score: best.score,
                         count: group.count, files: group.map(\.file)) {
                        assigning = AssignTarget(files: group.map(\.file), category: nil)
                    }
                }
            }
        }
    }

    private func empty(_ text: String) -> some View {
        Text(text).font(.system(size: 13)).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 120).padding(.horizontal, 20)
    }

    private func tile(url: URL?, key: String, caption: String?, score: Double?, count: Int, files: [String]? = nil,
                      open: @escaping () -> Void) -> some View {
        let ids = files ?? [key]
        let picked = !ids.isEmpty && ids.allSatisfy(selection.contains)
        return Button {
            if selecting {
                if picked { selection.subtract(ids) } else { selection.formUnion(ids) }
            } else { open() }
        } label: {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay { if let url, let client { RemoteImage(url: url, session: client.session) } }
                .overlay(alignment: .bottom) {
                    if let caption {
                        HStack(spacing: 4) {
                            Text(caption == "unknown" ? "?" : className(caption)).lineLimit(1)
                            Spacer(minLength: 2)
                            if let score, score > 0 {
                                Text("\(Int((score * 100).rounded()))%")
                                    .foregroundStyle(score >= m.threshold ? Theme.online : Theme.destructive)
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

    private var selectionBar: some View {
        HStack(spacing: 10) {
            Text(selection.count == 1 ? L("1 image") : L("\(selection.count) images")).font(.system(size: 14, weight: .semibold))
                .lineLimit(1).minimumScaleFactor(0.8)
            Spacer()
            Button { selectAll() } label: { Text("Select all").font(.system(size: 14, weight: .semibold)).lineLimit(1).fixedSize() }
                .buttonStyle(.plain).foregroundStyle(Theme.accent)
            if !moveTargets.isEmpty {
                // Plain buttons: a Picker in a Menu becomes a submenu on the Mac.
                Menu {
                    ForEach(moveTargets, id: \.self) { c in
                        Button(className(c)) { Task { await moveSelection(to: c) } }
                    }
                } label: {
                    HStack(spacing: 6) {
                        if working && moving { ProgressView().tint(Theme.onColor).controlSize(.small) }
                        Text("Put in").lineLimit(1)
                    }
                    .font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.onColor)
                    .padding(.horizontal, 14).frame(height: 34).fixedSize()
                    .background(Theme.accent.opacity(selection.isEmpty ? 0.4 : 1), in: RoundedRectangle(cornerRadius: 10))
                }
                .menuStyle(.button).buttonStyle(.plain)
                .disabled(selection.isEmpty || working)
            }
            Button { confirmDeleteSelection = true } label: {
                Group {
                    if working && !moving { ProgressView().tint(.white).controlSize(.small) }
                    else { Image(systemName: "trash") }
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

    /// The classes the selection can go to: every class but the one it is in. Moving labelled
    /// images needs Frigate 0.18.
    private var moveTargets: [String] {
        guard isAdmin, library == nil || reclassifies else { return [] }
        return dataset.classNames.filter { $0 != library }
    }

    /// Puts every selected image in one class, as tapping each and choosing it would.
    private func moveSelection(to category: String) async {
        guard let client, !selection.isEmpty else { return }
        working = true
        moving = true
        defer { working = false; moving = false }
        var failed: Error?
        for file in selection.sorted() {
            do {
                if let from = library {
                    try await client.reclassify(m.name, file: file, from: from, to: category)
                } else {
                    try await client.categorize(m.name, file: file, into: category)
                }
            } catch FrigateError.http(404, _) {
                // Already moved or pruned by Frigate.
            } catch { failed = error }
        }
        if let failed { errorMessage = failed.localizedDescription }
        let count = selection.count
        selection = []
        selecting = false
        notice = count == 1 ? L("1 image put in \(className(category))") : L("\(count) images put in \(className(category))")
        await load()
    }

    private func selectAll() {
        if let cls = library { selection = Set(dataset.categories[cls] ?? []) } else { selection = Set(shownGroups.flatMap { $0.map(\.file) }) }
    }

    // MARK: Actions

    private func load() async {
        guard let client else { return }
        async let r = client.role()
        async let set = client.classificationDataset(m.name)
        async let recent = client.classificationAttempts(m.name)
        dataset = (try? await set) ?? ClassificationDataset()
        attempts = (try? await recent) ?? []
        role = await r ?? "admin"
        if tab == .training, library.map({ dataset.categories[$0] == nil }) ?? true { library = dataset.classNames.first }
        loaded = true
    }

    private func reloadModel() async {
        if let client, let cfg = try? await client.rawConfig(),
           let fresh = ClassificationModel.all(in: cfg, serverID: m.serverID).first(where: { $0.name == m.name }) {
            current = fresh
        }
        await load()
    }

    private func train() async {
        guard let client else { return }
        working = true
        trainingAsked = true
        notice = nil
        defer { working = false }
        do {
            try await client.trainClassification(m.name)
            conn?.modelStates[m.name] = "training"
        } catch {
            trainingAsked = false
            errorMessage = error.localizedDescription
        }
    }

    private func deleteSelection() async {
        guard let client, !selection.isEmpty else { return }
        working = true
        defer { working = false }
        do {
            if let cls = library {
                try await client.deleteDatasetImages(m.name, category: cls, files: Array(selection))
            } else {
                try await client.deleteAttempts(m.name, files: Array(selection))
            }
            selection = []
            selecting = false
            await load()
        } catch { errorMessage = error.localizedDescription }
    }

    private func rename(_ old: String, to new: String) async {
        let name = new.trimmingCharacters(in: .whitespaces)
        guard let client, !name.isEmpty, name != old else { return }
        guard ClassificationNames.isValid(name) else { errorMessage = L("Use letters, numbers, spaces, ' _ or -, up to 50."); return }
        guard dataset.categories[name] == nil else { errorMessage = L("There is already a class with this name."); return }
        busyClass = old
        defer { busyClass = nil }
        do {
            try await client.renameCategory(m.name, old, to: name)
            if library == old { library = name }
            await load()
        } catch { errorMessage = error.localizedDescription }
    }

    private func deleteClass(_ c: String) async {
        guard let client else { return }
        busyClass = c
        defer { busyClass = nil }
        do {
            try await client.deleteDatasetImages(m.name, category: c, files: dataset.categories[c] ?? [])
            if library == c { library = dataset.classNames.first { $0 != c } }
            await load()
        } catch { errorMessage = error.localizedDescription }
    }

    private func deleteModel() async {
        guard let client else { return }
        working = true
        defer { working = false }
        do {
            try await client.deleteClassificationModel(m.name)
            dismiss()
        } catch { errorMessage = error.localizedDescription }
    }
}

enum ClassificationNames {
    /// As Frigate's page allows for classes: letters, numbers, spaces, ' _ -, at most 50.
    static func isValid(_ name: String) -> Bool {
        name.count <= 50 && !name.isEmpty
            && name.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) || " '_-".unicodeScalars.contains($0) }
    }
}

// MARK: - Put images in a class

struct ClassificationAssignSheet: View {
    let model: ClassificationModel
    let target: ClassificationModelView.AssignTarget
    let classes: [String]
    let reclassifies: Bool
    let isAdmin: Bool
    let onDone: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var picked: Set<String> = []
    @State private var working: String?
    @State private var addingClass = false
    @State private var newClass = ""
    @State private var errorMessage: String?

    private var client: FrigateClient? { app.connection(for: model.serverID)?.client }
    private var attempts: [ClassificationAttempt] { target.files.compactMap(ClassificationAttempt.init(file:)) }
    private var targets: [String] { classes.filter { $0 != target.category } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                images
                if let best = attempts.max(by: { $0.score < $1.score }), target.category == nil {
                    Text(best.label == "unknown" || best.score == 0 ? L("Frigate could not tell yet.")
                         : L("Frigate thought: \(best.label == "none" ? L("None") : best.label), \(Int((best.score * 100).rounded()))%"))
                        .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                } else if let cls = target.category {
                    Text(L("In class \(cls == "none" ? L("None") : cls)")).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                }
                if isAdmin {
                    if target.category == nil || reclassifies {
                        SectionLabel(text: target.category == nil ? L("Belongs to") : L("Move to"))
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), spacing: 8)], spacing: 8) {
                            ForEach(targets, id: \.self) { c in
                                actionButton(c == "none" ? L("None") : c, key: c) { await assign(to: c) }
                            }
                            actionButton(L("New class"), key: "+new", icon: "plus", tint: Theme.accent) { addingClass = true }
                        }
                    }
                    actionButton(target.files.count > 1 && picked.count > 1 ? L("Delete \(picked.count) images") : L("Delete image"),
                                 key: "-delete", icon: "trash", tint: Theme.destructive) { await delete() }
                        .padding(.top, 6)
                }
            }
            .padding(16)
        }
        .screenBackground()
        .navigationTitle(target.category == nil ? L("Put in a class") : L("Image"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { closePresented(dismiss) }.plainBarButton() } }
        .onAppear { picked = Set(target.files) }
        .textPrompt(L("New class"), isPresented: $addingClass, text: $newClass, placeholder: L("Name"), confirm: L("Add")) {
            Task { await addClass() }
        }
        .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    @ViewBuilder
    private var images: some View {
        if target.files.count == 1, let file = target.files.first {
            // Small enough that the classes stay in view, also in the Mac's sheet.
            image(file).frame(maxWidth: 280, maxHeight: 280).frame(maxWidth: .infinity)
        } else {
            Text("The tries for this object; the ones ticked are put in the class.")
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
                if let client {
                    RemoteImage(url: target.category.map { client.datasetImageURL(model.name, $0, file) } ?? client.attemptImageURL(model.name, file),
                                session: client.session)
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

    private func assign(to category: String) async {
        guard let client else { return }
        do {
            for file in target.files where picked.contains(file) {
                do {
                    if let from = target.category {
                        try await client.reclassify(model.name, file: file, from: from, to: category)
                    } else {
                        try await client.categorize(model.name, file: file, into: category)
                    }
                } catch FrigateError.http(404, _) {
                    // Already moved (a second tap, or Frigate pruned it): nothing left to do for this one.
                }
            }
            // The tries not ticked are of no use once the object is classified.
            if target.category == nil {
                let rest = target.files.filter { !picked.contains($0) }
                if !rest.isEmpty { try? await client.deleteAttempts(model.name, files: rest) }
            }
            onDone()
            closePresented(dismiss)
        } catch { errorMessage = error.localizedDescription }
    }

    private func addClass() async {
        let name = newClass.trimmingCharacters(in: .whitespaces)
        newClass = ""
        guard ClassificationNames.isValid(name) else { errorMessage = L("Use letters, numbers, spaces, ' _ or -, up to 50."); return }
        guard !classes.contains(where: { $0.caseInsensitiveCompare(name) == .orderedSame }), name.lowercased() != "none" else {
            errorMessage = L("There is already a class with this name."); return
        }
        working = "+new"
        defer { working = nil }
        await assign(to: name)
    }

    private func delete() async {
        guard let client else { return }
        do {
            let files = target.files.filter(picked.contains)
            if let cls = target.category {
                try await client.deleteDatasetImages(model.name, category: cls, files: files)
            } else {
                try await client.deleteAttempts(model.name, files: files)
            }
            onDone()
            closePresented(dismiss)
        } catch { errorMessage = error.localizedDescription }
    }
}

// MARK: - Edit a model

struct ClassificationEditSheet: View {
    let initial: ClassificationModel
    let onDone: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var enabled = true
    @State private var label = ""
    @State private var asAttribute = false
    @State private var motion = false
    @State private var interval = 0
    @State private var saveAttempts = 0
    @State private var labels: [String] = []
    @State private var saving = false
    @State private var errorMessage: String?

    private var conn: ServerConnection? { app.connection(for: initial.serverID) }
    private var newer: Bool { conn?.version.map { $0.compare("0.18", options: .numeric) != .orderedAscending } ?? false }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if newer {
                    VStack(spacing: 0) {
                        Toggle(isOn: $enabled) { Text("Model on").font(.system(size: 15)) }.tint(Theme.online)
                            .padding(.horizontal, 14).padding(.vertical, 8)
                    }
                    .card(radius: 12)
                }
                switch initial.kind {
                case .object:
                    SectionLabel(text: L("Object")).padding(.top, 8)
                    VStack(spacing: 0) {
                        HStack {
                            Text("Object").font(.system(size: 15))
                            Spacer()
                            Picker("", selection: $label) {
                                ForEach(labels, id: \.self) { Text(FrigateFormat.labelName($0)).tag($0) }
                            }
                            .labelsHidden()
                        }
                        .padding(.horizontal, 14).padding(.vertical, 6)
                        Divider().overlay(Theme.cardBorder)
                        Picker("", selection: $asAttribute) {
                            Text("Sub label").tag(false)
                            Text("Attribute").tag(true)
                        }
                        .pickerStyle(.segmented).padding(12)
                    }
                    .card(radius: 12)
                    Text("Sub label: the class becomes the object's name, like a face. Attribute: it is added as extra information.")
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                case .state:
                    if newer {
                        SectionLabel(text: L("When")).padding(.top, 8)
                        VStack(spacing: 0) {
                            Toggle(isOn: $motion) { Text("On motion in the area").font(.system(size: 15)) }.tint(Theme.online)
                                .padding(.horizontal, 14).padding(.vertical, 8)
                            Divider().overlay(Theme.cardBorder)
                            HStack {
                                Text("Every").font(.system(size: 15))
                                Spacer()
                                NumberStepper(value: Double(interval), unit: "s", step: 10, min: 0, max: 3600) { interval = Int($0) }
                            }
                            .padding(.horizontal, 14).padding(.vertical, 6)
                        }
                        .card(radius: 12)
                        Text("0 seconds: only on motion.").font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                    }
                }
                if newer {
                    SectionLabel(text: L("Recent images")).padding(.top, 8)
                    VStack(spacing: 0) {
                        HStack {
                            Text("Keep").font(.system(size: 15))
                            Spacer()
                            NumberStepper(value: Double(saveAttempts), unit: "", step: 10, min: 0, max: 1000) { saveAttempts = Int($0) }
                        }
                        .padding(.horizontal, 14).padding(.vertical, 6)
                    }
                    .card(radius: 12)
                    Text("How many recent images Frigate keeps to put in classes.").font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)
                }
            }
            .padding(16)
        }
        .screenBackground()
        .navigationTitle(initial.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { closePresented(dismiss) }.plainBarButton() }
            ToolbarItem(placement: .confirmationAction) {
                Button { Task { await save() } } label: { if saving { ProgressView() } else { Text("Save") } }.disabled(saving)
                    .confirmingBarButton()
            }
        }
        .task { await load() }
        .alert("Not saved", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private func load() async {
        enabled = initial.enabled
        switch initial.kind {
        case .object(let l, let a):
            label = l; asAttribute = a; saveAttempts = initial.saveAttempts ?? 200
        case .state(_, let mo, let iv):
            motion = mo; interval = iv ?? 0; saveAttempts = initial.saveAttempts ?? 100
        }
        if let cfg = try? await conn?.client?.rawConfig() {
            labels = ClassificationWizard.trackedLabels(cfg)
            if !label.isEmpty, !labels.contains(label) { labels.append(label) }
        }
    }

    private func save() async {
        guard let client = conn?.client else { return }
        saving = true
        defer { saving = false }
        var node: [String: Any] = [:]
        switch initial.kind {
        case .object:
            node = ["name": initial.name, "threshold": initial.threshold,
                    "object_config": ["objects": [label], "classification_type": asAttribute ? "attribute" : "sub_label"]]
        case .state:
            if newer { node = ["state_config": ["motion": motion, "interval": interval > 0 ? interval as Any : NSNull()]] }
        }
        if newer { node["enabled"] = enabled; node["save_attempts"] = saveAttempts }
        do {
            try await client.setConfig(["classification": ["custom": [initial.name: node]]], requiresRestart: false,
                                       updateTopic: "config/classification/custom/\(initial.name)")
            onDone()
            closePresented(dismiss)
        } catch { errorMessage = error.localizedDescription }
    }
}
