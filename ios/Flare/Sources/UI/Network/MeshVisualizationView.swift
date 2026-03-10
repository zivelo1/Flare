import SwiftUI

struct MeshVisualizationView: View {
    let peers: [MeshPeer]
    @State private var phase: Double = 0

    private let timer = Timer.publish(every: 1.0 / 30.0, on: .main, in: .common).autoconnect()

    var body: some View {
        Canvas { context, size in
            drawMesh(context: context, size: size)
        }
        .frame(height: 260)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        .onReceive(timer) { _ in
            phase += 1.0 / 30.0
        }
    }

    private func drawMesh(context: GraphicsContext, size: CGSize) {
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let orbitRadius = min(size.width, size.height) / 2 - 30
        let connected = peers.filter { $0.isConnected }
        let count = max(connected.count, 1)

        drawLines(context: context, center: center, peers: connected, count: count, orbitRadius: orbitRadius)
        drawPeerNodes(context: context, center: center, peers: connected, count: count, orbitRadius: orbitRadius)
        drawCenterNode(context: context, center: center)
    }

    private func drawLines(context: GraphicsContext, center: CGPoint, peers: [MeshPeer], count: Int, orbitRadius: CGFloat) {
        for (index, peer) in peers.enumerated() {
            let peerCenter = peerPosition(index: index, total: count, center: center, radius: orbitRadius)
            let lineWidth = lineThickness(for: peer.rssi)
            let pulseAlpha = 0.3 + 0.3 * sin(phase * 2.0 + Double(index) * 0.8)

            var linePath = Path()
            linePath.move(to: center)
            linePath.addLine(to: peerCenter)

            context.stroke(linePath, with: .color(Constants.flareOrange.opacity(pulseAlpha)), lineWidth: lineWidth)
        }
    }

    private func drawPeerNodes(context: GraphicsContext, center: CGPoint, peers: [MeshPeer], count: Int, orbitRadius: CGFloat) {
        let nodeRadius: CGFloat = 18
        for (index, peer) in peers.enumerated() {
            let pos = peerPosition(index: index, total: count, center: center, radius: orbitRadius)
            let colors = IdenticonGenerator.getColors(deviceId: peer.deviceId)

            let rect = CGRect(x: pos.x - nodeRadius, y: pos.y - nodeRadius, width: nodeRadius * 2, height: nodeRadius * 2)
            context.fill(Path(ellipseIn: rect), with: .color(colors.background.opacity(0.85)))

            let initials = IdenticonGenerator.getInitials(displayName: peer.displayName, deviceId: peer.deviceId)
            context.draw(
                Text(initials).font(.system(size: 12, weight: .semibold)).foregroundColor(.white),
                at: pos
            )
        }
    }

    private func drawCenterNode(context: GraphicsContext, center: CGPoint) {
        let radius: CGFloat = 24
        let scaled = radius * (1.0 + 0.06 * sin(phase * 1.5))
        let rect = CGRect(x: center.x - scaled, y: center.y - scaled, width: scaled * 2, height: scaled * 2)
        context.fill(Path(ellipseIn: rect), with: .color(Constants.flareOrange))
        context.draw(
            Text("You").font(.system(size: 13, weight: .bold)).foregroundColor(.white),
            at: center
        )
    }

    private func peerPosition(index: Int, total: Int, center: CGPoint, radius: CGFloat) -> CGPoint {
        let angle = CGFloat(2.0 * .pi * Double(index) / Double(total) - .pi / 2)
        return CGPoint(x: center.x + cos(angle) * radius, y: center.y + sin(angle) * radius)
    }

    private func lineThickness(for rssi: Int?) -> CGFloat {
        guard let rssi = rssi else { return 1.0 }
        if rssi >= -50 { return 3.5 }
        if rssi >= -65 { return 2.5 }
        if rssi >= -80 { return 1.5 }
        return 1.0
    }
}
