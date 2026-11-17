import SwiftUI

/// Pinch to zoom (1x–6x) on the spot between the fingers, drag to pan while zoomed, double-tap
/// to zoom in on the tapped spot or reset. On iOS 18 and later the picture follows the fingers
/// while pinching, as in Photos: moving both fingers pans while zooming.
/// The content stays clipped to its frame, so overlays outside it are not affected.
struct Zoomable: ViewModifier {
    var maxScale: CGFloat = 6

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    /// While two fingers pinch, the one-finger drag leaves the offset alone.
    @State private var pinching = false
    /// Where the current drag started from, so a drag that goes on after a pinch does not jump.
    @State private var dragBase: CGSize?
    /// The pinch's first centroid (from the frame's centre), scale and offset.
    @State private var pinchStart: (location: CGPoint, scale: CGFloat, offset: CGSize)?

    func body(content: Content) -> some View {
        GeometryReader { geo in
            content
                .frame(width: geo.size.width, height: geo.size.height)
                .scaleEffect(scale)
                .offset(offset)
                .frame(width: geo.size.width, height: geo.size.height)
                .clipped()
                .contentShape(Rectangle())
                .pinch(magnify: magnify(in: geo.size)) { state, factor, location in
                    pinch(state, factor: factor, location: location, size: geo.size)
                }
                .simultaneousGesture(scale > 1 ? pan(in: geo.size) : nil)
                .onTapGesture(count: 2) { location in
                    withAnimation(.easeOut(duration: 0.2)) {
                        if scale > 1 { reset() } else {
                            let anchor = CGPoint(x: location.x - geo.size.width / 2, y: location.y - geo.size.height / 2)
                            let target = zoomed(around: anchor, to: 2.5)
                            scale = 2.5
                            lastScale = 2.5
                            offset = clamp(target, size: geo.size)
                            lastOffset = offset
                        }
                    }
                }
        }
    }

    private func magnify(in size: CGSize) -> some Gesture {
        MagnifyGesture()
            .onChanged { v in
                scale = min(max(lastScale * v.magnification, 1), maxScale)
                // The spot between the fingers stays under them.
                let anchor = CGPoint(x: (v.startAnchor.x - 0.5) * size.width, y: (v.startAnchor.y - 0.5) * size.height)
                offset = clamp(zoomed(around: anchor, to: scale), size: size)
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= 1.02 { withAnimation(.easeOut(duration: 0.15)) { reset() } }
                offset = clamp(offset, size: size)
                lastOffset = offset
            }
    }

    private func pan(in size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 4)
            .onChanged { v in
                guard !pinching else { dragBase = nil; return }
                let base = dragBase ?? CGSize(width: offset.width - v.translation.width, height: offset.height - v.translation.height)
                dragBase = base
                offset = clamp(CGSize(width: base.width + v.translation.width,
                                      height: base.height + v.translation.height), size: size)
            }
            .onEnded { _ in
                dragBase = nil
                if !pinching { lastOffset = offset }
            }
    }

    /// A UIKit pinch (iOS 18+): zooms around the fingers and follows them as they move.
    private func pinch(_ state: UIGestureRecognizer.State, factor: CGFloat, location: CGPoint, size: CGSize) {
        let point = CGPoint(x: location.x - size.width / 2, y: location.y - size.height / 2)
        switch state {
        case .began:
            pinching = true
            pinchStart = (point, scale, offset)
        case .changed:
            guard let start = pinchStart else { return }
            scale = min(max(start.scale * factor, 1), maxScale)
            // The content that was under the fingers at the start stays under them.
            let content = CGPoint(x: (start.location.x - start.offset.width) / start.scale,
                                  y: (start.location.y - start.offset.height) / start.scale)
            offset = clamp(CGSize(width: point.x - content.x * scale, height: point.y - content.y * scale), size: size)
        default:
            pinching = false
            pinchStart = nil
            lastScale = scale
            if scale <= 1.02 { withAnimation(.easeOut(duration: 0.15)) { reset() } }
            offset = clamp(offset, size: size)
            lastOffset = offset
        }
    }

    /// The offset that keeps the content under `anchor` (from the frame's centre) in place when
    /// the scale goes from `lastScale` to `newScale`.
    private func zoomed(around anchor: CGPoint, to newScale: CGFloat) -> CGSize {
        let factor = newScale / lastScale
        return CGSize(width: anchor.x - (anchor.x - lastOffset.width) * factor,
                      height: anchor.y - (anchor.y - lastOffset.height) * factor)
    }

    /// Keeps the zoomed content covering the frame.
    private func clamp(_ o: CGSize, size: CGSize) -> CGSize {
        let maxX = size.width * (scale - 1) / 2
        let maxY = size.height * (scale - 1) / 2
        return CGSize(width: min(max(o.width, -maxX), maxX), height: min(max(o.height, -maxY), maxY))
    }

    private func reset() {
        scale = 1; lastScale = 1
        offset = .zero; lastOffset = .zero
    }
}

private extension View {
    /// A pinch that follows the fingers on iOS 18 and later; SwiftUI's fixed-anchor magnify before.
    @ViewBuilder
    func pinch(magnify: some Gesture,
               _ action: @escaping (UIGestureRecognizer.State, CGFloat, CGPoint) -> Void) -> some View {
        if #available(iOS 18.0, *) {
            gesture(PinchRecognizer(action: action))
        } else {
            gesture(magnify)
        }
    }
}

@available(iOS 18.0, *)
private struct PinchRecognizer: UIGestureRecognizerRepresentable {
    let action: (UIGestureRecognizer.State, CGFloat, CGPoint) -> Void

    func makeUIGestureRecognizer(context: Context) -> UIPinchGestureRecognizer { UIPinchGestureRecognizer() }

    func handleUIGestureRecognizerAction(_ recognizer: UIPinchGestureRecognizer, context: Context) {
        action(recognizer.state, recognizer.scale, context.converter.localLocation)
    }
}

extension View {
    func zoomable(maxScale: CGFloat = 6) -> some View { modifier(Zoomable(maxScale: maxScale)) }
}
