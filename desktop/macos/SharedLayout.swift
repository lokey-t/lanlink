import Foundation

struct SharedLayoutCell: Hashable {
    let x: Int; let y: Int
    func adjacent(_ direction: String) -> SharedLayoutCell? {
        let next = SharedLayoutCell(x: x + (direction == "left" ? -1 : direction == "right" ? 1 : 0), y: y + (direction == "top" ? -1 : direction == "bottom" ? 1 : 0))
        return (-32...32).contains(next.x) && (-32...32).contains(next.y) ? next : nil
    }
}
// Every member uses the same global cells. The viewport follows the extent of
// the whole group, never the local computer or the selected keyboard master.
struct SharedLayoutGeometry: Equatable {
    let card: CGSize; let contentSize: CGSize; let origin: CGPoint; let step: CGSize
    let minX: Int; let maxX: Int; let minY: Int; let maxY: Int
    init(size: CGSize, cells: [SharedLayoutCell]) {
        minX = max(-32, (cells.map(\.x).min() ?? 0) - 1); maxX = min(32, (cells.map(\.x).max() ?? 0) + 1)
        minY = max(-32, (cells.map(\.y).min() ?? 0) - 1); maxY = min(32, (cells.map(\.y).max() ?? 0) + 1)
        let columns = CGFloat(maxX - minX + 1), rows = CGFloat(maxY - minY + 1)
        let scale = max(0.65, min(1, max(1, size.width - 32) / (columns * 212), max(1, size.height - 32) / (rows * 148)))
        card = CGSize(width: 180 * scale, height: 112 * scale); step = CGSize(width: 212 * scale, height: 148 * scale)
        contentSize = CGSize(width: max(size.width, columns * step.width + 32), height: max(size.height, rows * step.height + 32))
        origin = CGPoint(x: contentSize.width / 2 - CGFloat(minX + maxX) * step.width / 2, y: contentSize.height / 2 - CGFloat(minY + maxY) * step.height / 2)
    }
    func point(_ cell: SharedLayoutCell) -> CGPoint { CGPoint(x: origin.x + CGFloat(cell.x) * step.width, y: origin.y + CGFloat(cell.y) * step.height) }
    func cell(_ point: CGPoint) -> SharedLayoutCell? {
        let x = Int(((point.x - origin.x) / step.width).rounded()), y = Int(((point.y - origin.y) / step.height).rounded())
        return (-32...32).contains(x) && (-32...32).contains(y) ? SharedLayoutCell(x: x, y: y) : nil
    }
}

// Connections stop outside the card, including its border and the line cap.
extension SharedLayoutGeometry {
    func connection(_ from: SharedLayoutCell, _ to: SharedLayoutCell, offset: CGFloat = 0) -> (start: CGPoint, end: CGPoint) {
        let a = point(from), b = point(to), horizontal = from.y == to.y
        let sign: CGFloat = (horizontal ? b.x - a.x : b.y - a.y) >= 0 ? 1 : -1
        let inset = (horizontal ? card.width : card.height) / 2 + 3
        return horizontal
            ? (CGPoint(x: a.x + sign * inset, y: a.y + offset), CGPoint(x: b.x - sign * inset, y: b.y + offset))
            : (CGPoint(x: a.x + offset, y: a.y + sign * inset), CGPoint(x: b.x + offset, y: b.y - sign * inset))
    }
}
