import SwiftUI

/// Pinch to zoom (1x–6x) on the spot between the fingers, drag to pan while zoomed, double-tap
/// to zoom in on the tapped spot or reset.
/// The content stays clipped to its frame, so overlays outside it are not affected.
struct Zoomable: ViewModifier {
    var maxScale: CGFloat = 6

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    func body(content: Content) -> some View {
        GeometryReader { geo in
            content
                .frame(width: geo.size.width, height: geo.size.height)
                .scaleEffect(scale)
                .offset(offset)
                .frame(width: geo.size.width, height: geo.size.height)
                .clipped()
                .contentShape(Rectangle())
                .gesture(magnify(in: geo.size))
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
                offset = clamp(CGSize(width: lastOffset.width + v.translation.width,
                                      height: lastOffset.height + v.translation.height), size: size)
            }
            .onEnded { _ in lastOffset = offset }
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

extension View {
    func zoomable(maxScale: CGFloat = 6) -> some View { modifier(Zoomable(maxScale: maxScale)) }
}
