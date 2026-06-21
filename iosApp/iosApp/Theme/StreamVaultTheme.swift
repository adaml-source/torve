import SwiftUI

enum SVColor {
    static let amber = Color(red: 0.91, green: 0.66, blue: 0.22)
    static let emerald = Color(red: 0.2, green: 0.78, blue: 0.55)
    static let obsidian = Color(red: 0.08, green: 0.08, blue: 0.10)
    static let surface = Color(red: 0.12, green: 0.12, blue: 0.14)
    static let surfaceVariant = Color(red: 0.16, green: 0.16, blue: 0.18)
    static let onSurface = Color.white
    static let onSurfaceVariant = Color(white: 0.7)
    static let error = Color(red: 0.93, green: 0.29, blue: 0.29)
    static let rating = Color.yellow
}

enum SVFont {
    static let heroTitle = Font.system(size: 28, weight: .bold)
    static let sectionTitle = Font.system(size: 18, weight: .semibold)
    static let cardTitle = Font.system(size: 13, weight: .medium)
    static let caption = Font.system(size: 11, weight: .regular)
    static let pill = Font.system(size: 10, weight: .semibold)
}
