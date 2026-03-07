import SwiftUI

struct CastAvatar: View {
    let name: String
    let character: String?
    let imageUrl: String?

    var body: some View {
        VStack(spacing: 6) {
            AsyncImage(url: URL(string: imageUrl ?? "")) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().scaledToFill()
                default:
                    Circle()
                        .fill(SVColor.surfaceVariant)
                        .overlay {
                            Text(String(name.prefix(1)))
                                .font(.title3)
                                .foregroundColor(SVColor.onSurfaceVariant)
                        }
                }
            }
            .frame(width: 64, height: 64)
            .clipShape(Circle())

            Text(name)
                .font(SVFont.caption)
                .foregroundColor(SVColor.onSurface)
                .lineLimit(1)

            if let character = character {
                Text(character)
                    .font(.system(size: 10))
                    .foregroundColor(SVColor.onSurfaceVariant)
                    .lineLimit(1)
            }
        }
        .frame(width: 80)
    }
}
