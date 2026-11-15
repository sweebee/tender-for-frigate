import SwiftUI

/// A new classification model in Frigate's three steps: name and classes; for a state model the
/// area on one or more cameras; then examples Frigate picks from past events, sorted per class.
/// The model is written and trained only at the end.
struct ClassificationWizard: View {
    let serverID: UUID
    let startAsState: Bool
    let onDone: () -> Void

    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    enum Step { case define, area, examples, done }
    @State private var step: Step = .define
    @State private var name = ""
    @State private var isState = false
    @State private var label = ""
    @State private var asAttribute = false
    @State private var classes: [String] = ["", ""]
    @State private var labels: [String] = []
    @State private var existing: Set<String> = []
    @State private var cameras: [String: CropArea] = [:]
    @State private var examples: [String] = []
    @State private var generating = false
    @State private var generated = false
    @State private var classIndex = 0
    /// Example file → class.
    @State private var chosen: [String: String] = [:]
    @State private var finishing = false
    @State private var cancelling = false
    @State private var trained = false
    @State private var errorMessage: String?
    /// Which field has the caret: -1 the name, else a class.
    @FocusState private var focus: Int?

    private var conn: ServerConnection? { app.connection(for: serverID) }
    private var client: FrigateClient? { conn?.client }
    private var cleanClasses: [String] { classes.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty } }
    private var cleanName: String { name.trimmingCharacters(in: .whitespaces) }

    var body: some View {
        Group {
            switch step {
            case .define: define
            case .area: area
            case .examples: examplesStep
            case .done: done
            }
        }
        .screenBackground()
        .navigationTitle(L("New model"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if step != .done {
                ToolbarItem(placement: .cancellationAction) {
                    Button { Task { await cancel() } } label: {
                        if cancelling { ProgressView() } else { Text("Cancel") }
                    }
                    .plainBarButton()
                    .disabled(finishing || cancelling)
                }
            }
        }
        .task { await load() }
        .interactiveDismissDisabled(generated || finishing)
        .alert(step == .define || step == .area ? L("Not ready yet") : L("Not saved"),
               isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private func stepLabel(_ n: Int) -> some View {
        Text(L("Step \(n) of \(isState ? 3 : 2)")).font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.textSecondary)
    }

    // MARK: Step 1

    private var define: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                stepLabel(1)
                VStack(spacing: 0) {
                    HStack {
                        Text("Name").font(.system(size: 15)).foregroundStyle(focus == -1 ? Theme.accent : Theme.text)
                        TextField(focus == -1 ? "" : (isState ? L("Gate") : L("Delivery service")), text: $name)
                            .multilineTextAlignment(formFieldAlignment).font(.system(size: 15))
                            .autocorrectionDisabled()
                            .focused($focus, equals: -1)
                            .focusEffectDisabled()
                    }
                    .padding(.horizontal, 14).padding(.vertical, 11)
                    .contentShape(Rectangle()).onTapGesture { focus = -1 }
                    .modifier(FocusRow(on: focus == -1))
                }
                .card(radius: 12)
                Picker("", selection: $isState) {
                    Text("Object").tag(false)
                    Text("State").tag(true)
                }
                .pickerStyle(.segmented).padding(.vertical, 6)
                Text(isState ? L("Looks at an area of a camera and tells its state, like open or closed.")
                             : L("Puts each tracked object of one kind into a class, like the delivery service of a person."))
                    .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)

                if !isState {
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
                }

                SectionLabel(text: isState ? L("States") : L("Classes")).padding(.top, 8)
                VStack(spacing: 0) {
                    ForEach(classes.indices, id: \.self) { i in
                        if i > 0 { Divider().overlay(Theme.cardBorder) }
                        HStack {
                            TextField(isState ? (i == 0 ? L("Open") : L("Closed")) : (i == 0 ? "DHL" : "PostNL"), text: $classes[i])
                                .font(.system(size: 15)).autocorrectionDisabled()
                                .focused($focus, equals: i)
                            .focusEffectDisabled()
                            if classes.count > (isState ? 2 : 1) {
                                Button { classes.remove(at: i) } label: {
                                    Image(systemName: "minus.circle.fill").foregroundStyle(Theme.destructive)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 14).padding(.vertical, 11)
                        .modifier(FocusRow(on: focus == i))
                    }
                    Divider().overlay(Theme.cardBorder)
                    Button { classes.append("") } label: {
                        Label(isState ? L("Add state") : L("Add class"), systemImage: "plus")
                            .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.accent)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 14).padding(.vertical, 11)
                }
                .card(radius: 12)
                Text(isState ? L("At least two. Frigate tells which one it sees.")
                             : L("Objects that fit none of them go to None, which Frigate adds itself."))
                    .font(.system(size: 12)).foregroundStyle(Theme.textSecondary).padding(.horizontal, 4)

                primaryButton(L("Next")) { next() }.padding(.top, 14)
            }
            .padding(16)
            .readableWidth()
        }
    }

    private func next() {
        if let problem = defineProblem { errorMessage = problem; return }
        if isState {
            if cameras.isEmpty, let first = stateCameras.first { cameras[first.name] = CropArea() }
            step = .area
        } else {
            step = .examples
        }
    }

    private var defineProblem: String? {
        let n = cleanName
        if n.isEmpty || n.count > 64 || !ClassificationNames.isValid(String(n.prefix(50))) || n.allSatisfy(\.isNumber) {
            return L("Give the model a name of letters and numbers.")
        }
        if existing.contains(where: { $0.caseInsensitiveCompare(n) == .orderedSame }) { return L("There is already a model with this name.") }
        if !isState && label.isEmpty { return L("Pick the object to classify.") }
        let cls = cleanClasses
        if cls.count < (isState ? 2 : 1) { return isState ? L("Give at least two states.") : L("Give at least one class.") }
        if Set(cls.map { $0.lowercased() }).count != cls.count { return L("Each class needs its own name.") }
        if cls.contains(where: { $0.lowercased() == "none" }) { return L("None is added by Frigate itself; use another name.") }
        if !cls.allSatisfy(ClassificationNames.isValid) { return L("Use letters, numbers, spaces, ' _ or -, up to 50.") }
        return nil
    }

    // MARK: Step 2 (state)

    private var stateCameras: [CameraConfig] {
        (conn?.config?.cameras.values.map { $0 } ?? [])
            .filter { $0.enabledInConfig != false }
            .sorted { ($0.ui?.order ?? 0, $0.name) < ($1.ui?.order ?? 0, $1.name) }
    }

    private var area: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                stepLabel(2)
                Text("Pick the cameras and drag the square over what the state is about, like the gate. Frigate only looks inside it.")
                    .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                VStack(spacing: 0) {
                    ForEach(Array(stateCameras.enumerated()), id: \.element.name) { i, cam in
                        if i > 0 { Divider().overlay(Theme.cardBorder) }
                        Toggle(isOn: Binding(get: { cameras[cam.name] != nil },
                                             set: { cameras[cam.name] = $0 ? CropArea() : nil })) {
                            Text(cam.displayName).font(.system(size: 15))
                        }
                        .tint(Theme.online)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                    }
                }
                .card(radius: 12)
                ForEach(stateCameras.filter { cameras[$0.name] != nil }, id: \.name) { cam in
                    SectionLabel(text: cam.displayName).padding(.top, 8)
                    CropEditor(url: client?.latestImageURL(camera: cam.name, height: 500), session: client?.session ?? .shared,
                               aspect: aspect(cam.name),
                               area: Binding(get: { cameras[cam.name] ?? CropArea() }, set: { cameras[cam.name] = $0 }))
                }
                HStack(spacing: 10) {
                    secondaryButton(L("Back")) { step = .define }
                    primaryButton(L("Next")) {
                        if cameras.isEmpty { errorMessage = L("Pick at least one camera.") } else { step = .examples }
                    }
                }
                .padding(.top, 14)
            }
            .padding(16)
            .readableWidth()
        }
    }

    // MARK: Step 3

    private var currentClass: String? { classIndex < cleanClasses.count ? cleanClasses[classIndex] : nil }

    private var examplesStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                stepLabel(isState ? 3 : 2)
                if generating || !generated {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text("Frigate is picking 24 different examples from past events. This can take a minute.")
                            .font(.system(size: 13)).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, minHeight: 200)
                } else if examples.isEmpty {
                    Text(isState ? L("Frigate found no recordings of these cameras to take examples from.")
                                 : L("Frigate found no events of this object to take examples from."))
                        .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                    primaryButton(L("Make the model without examples"), busy: finishing) { Task { await finish() } }
                } else if let cls = currentClass {
                    Text(L("Tap every image with: \(cls)")).font(.system(size: 17, weight: .semibold))
                    Text(L("Class \(classIndex + 1) of \(cleanClasses.count)") + (isState ? "" : " · " + L("the rest goes to None")))
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 6)], spacing: 6) {
                        ForEach(examples.filter { chosen[$0] == nil || chosen[$0] == cls }, id: \.self) { file in
                            let on = chosen[file] == cls
                            Button { chosen[file] = on ? nil : cls } label: {
                                Color.clear.aspectRatio(1, contentMode: .fit)
                                    .overlay {
                                        if let client {
                                            RemoteImage(url: client.attemptImageURL(cleanName, file), session: client.session)
                                        }
                                    }
                                    .overlay(alignment: .topTrailing) {
                                        Image(systemName: on ? "checkmark.circle.fill" : "circle").font(.system(size: 18))
                                            .foregroundStyle(on ? Theme.accent : .white).shadow(radius: 2).padding(4)
                                    }
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(on ? Theme.accent : Theme.cardBorder, lineWidth: on ? 2 : 1))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    HStack(spacing: 10) {
                        secondaryButton(L("Back")) {
                            if classIndex > 0 { classIndex -= 1 } else { step = isState ? .area : .define }
                        }
                        primaryButton(classIndex + 1 < cleanClasses.count ? L("Next class") : L("Make model"), busy: finishing) {
                            if classIndex + 1 < cleanClasses.count { classIndex += 1 } else { Task { await finish() } }
                        }
                    }
                    .padding(.top, 8)
                }
            }
            .padding(16)
            .readableWidth()
        }
        .task(id: step) { if step == .examples && !generated && !generating { await generate() } }
    }

    private var done: some View {
        VStack(spacing: 12) {
            Image(systemName: "checkmark.circle.fill").font(.system(size: 40)).foregroundStyle(Theme.online)
            Text(L("\(cleanName) is made")).font(.system(size: 18, weight: .semibold))
            Text(trained ? L("Frigate is training it now; that takes a few minutes. Put more images in its classes as they come in.")
                         : L("Put images in its classes as Frigate classifies objects, then train it."))
                .font(.system(size: 13)).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
            primaryButton(L("Done")) { onDone(); closePresented(dismiss) }.frame(maxWidth: 240)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: Pieces

    private func primaryButton(_ title: String, busy: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group { if busy { ProgressView().tint(Theme.onColor) } else { Text(title) } }
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.onColor)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(busy)
    }

    private func secondaryButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Theme.control, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.controlBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(finishing)
    }

    // MARK: Work

    /// What Frigate tracks (attributes such as faces and delivery logos left out), for object models.
    static func trackedLabels(_ cfg: [String: Any]) -> [String] {
        let attributes: Set<String> = ["face", "license_plate", "amazon", "an_post", "canada_post", "dhl", "dpd", "fedex", "gls",
                                       "nzpost", "postnl", "postnord", "purolator", "royal_mail", "ups", "usps"]
        var all = Set(((cfg["objects"] as? [String: Any])?["track"] as? [String]) ?? [])
        for case let cam as [String: Any] in (cfg["cameras"] as? [String: Any] ?? [:]).values where cam["enabled"] as? Bool != false {
            all.formUnion(((cam["objects"] as? [String: Any])?["track"] as? [String]) ?? [])
        }
        return all.subtracting(attributes).sorted { FrigateFormat.labelName($0) < FrigateFormat.labelName($1) }
    }

    private func load() async {
        isState = startAsState
        guard let cfg = try? await client?.rawConfig() else { return }
        labels = Self.trackedLabels(cfg)
        if label.isEmpty { label = labels.contains("person") ? "person" : labels.first ?? "" }
        existing = Set(ClassificationModel.all(in: cfg, serverID: serverID).map(\.name)
                       + ((cfg["classification"] as? [String: Any])?["custom"] as? [String: Any] ?? [:]).keys)
    }

    private func generate() async {
        guard let client else { return }
        generating = true
        defer { generating = false }
        do {
            if isState {
                var crops: [String: [Double]] = [:]
                for (cam, area) in cameras { crops[cam] = area.normalized(aspect: aspect(cam)) }
                try await client.generateStateExamples(cleanName, cameras: crops)
            } else {
                try await client.generateObjectExamples(cleanName, label: label)
            }
            examples = try await client.rawClassificationTrainFiles(cleanName).sorted()
            generated = true
        } catch {
            errorMessage = error.localizedDescription
            step = isState ? .area : .define
        }
    }

    private func aspect(_ camera: String) -> Double { Double(conn?.config?.cameras[camera]?.aspectRatio ?? 16 / 9) }

    private func finish() async {
        guard let client else { return }
        finishing = true
        defer { finishing = false }
        let n = cleanName
        var node: [String: Any] = ["enabled": true, "name": n, "threshold": 0.8]
        if isState {
            var cams: [String: Any] = [:]
            for (cam, area) in cameras {
                cams[cam] = ["crop": area.normalized(aspect: aspect(cam))]
            }
            node["state_config"] = ["cameras": cams, "motion": true]
        } else {
            node["object_config"] = ["objects": [label], "classification_type": asAttribute ? "attribute" : "sub_label"]
        }
        do {
            try await client.setConfig(["classification": ["custom": [n: node]]], requiresRestart: false,
                                       updateTopic: "config/classification/custom/\(n)")
            var assigned = chosen
            if !isState { for file in examples where assigned[file] == nil { assigned[file] = "none" } }
            for (file, cls) in assigned { try await client.categorize(n, file: file, into: cls) }
            if isState {
                let rest = examples.filter { assigned[$0] == nil }
                if !rest.isEmpty { try? await client.deleteAttempts(n, files: rest) }
            }
            let withImages = Set(assigned.values)
            for cls in cleanClasses where !withImages.contains(cls) { try? await client.createCategory(n, cls) }
            // As Frigate's wizard: a state model needs examples of every state, an object model of at least one class.
            let canTrain = isState ? cleanClasses.allSatisfy(withImages.contains) : !withImages.subtracting(["none"]).isEmpty
            if canTrain {
                try await client.trainClassification(n)
                conn?.modelStates[n] = "training"
                trained = true
            }
            generated = false
            step = .done
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func cancel() async {
        // Examples were made under the model's name: remove them; the config was not written yet.
        if generated || generating, let client {
            cancelling = true
            _ = try? await client.sendJSON("DELETE", "api/classification/\(cleanName)", json: nil)
            cancelling = false
        }
        closePresented(dismiss)
    }
}

/// A square area on a camera picture: its centre (0…1) and its side as a share of the picture's short side.
struct CropArea: Equatable {
    var x = 0.5
    var y = 0.5
    var size = 0.3

    /// [x1, y1, x2, y2] in 0…1 of the picture, square in pixels, as Frigate keeps it.
    func normalized(aspect: Double) -> [Double] {
        let w = aspect >= 1 ? size / aspect : size
        let h = aspect >= 1 ? size : size * aspect
        let x1 = min(max(x - w / 2, 0), 1 - w), y1 = min(max(y - h / 2, 0), 1 - h)
        return [x1, y1, x1 + w, y1 + h].map { ($0 * 1000).rounded() / 1000 }
    }
}

/// The camera picture with a square to drag, and a slider for its size.
struct CropEditor: View {
    let url: URL?
    let session: URLSession
    let aspect: Double
    @Binding var area: CropArea
    @State private var dragStart: CropArea?

    var body: some View {
        VStack(spacing: 8) {
            Color.clear
                .aspectRatio(aspect, contentMode: .fit)
                .overlay { RemoteImage(url: url, session: session) }
                .overlay {
                    GeometryReader { geo in
                        let box = area.normalized(aspect: aspect)
                        let rect = CGRect(x: box[0] * geo.size.width, y: box[1] * geo.size.height,
                                          width: (box[2] - box[0]) * geo.size.width, height: (box[3] - box[1]) * geo.size.height)
                        ZStack(alignment: .topLeading) {
                            Rectangle().fill(.black.opacity(0.45))
                                .mask {
                                    Rectangle().overlay { Rectangle().frame(width: rect.width, height: rect.height)
                                        .position(x: rect.midX, y: rect.midY).blendMode(.destinationOut) }
                                    .compositingGroup()
                                }
                            RoundedRectangle(cornerRadius: 3).stroke(Theme.accent, lineWidth: 2)
                                .frame(width: rect.width, height: rect.height)
                                .position(x: rect.midX, y: rect.midY)
                        }
                        .contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                            let start = dragStart ?? area
                            if dragStart == nil { dragStart = area }
                            var next = start
                            next.x = start.x + g.translation.width / geo.size.width
                            next.y = start.y + g.translation.height / geo.size.height
                            let n = next.normalized(aspect: aspect)
                            next.x = (n[0] + n[2]) / 2
                            next.y = (n[1] + n[3]) / 2
                            area = next
                        }.onEnded { _ in dragStart = nil })
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 10))
            HStack(spacing: 10) {
                Image(systemName: "square.resize.down").foregroundStyle(Theme.textSecondary)
                Slider(value: $area.size, in: 0.1...1)
                Image(systemName: "square.resize.up").foregroundStyle(Theme.textSecondary)
            }
            .padding(.horizontal, 4)
        }
    }
}
