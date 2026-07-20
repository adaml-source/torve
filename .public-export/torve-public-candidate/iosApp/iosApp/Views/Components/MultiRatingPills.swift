import SwiftUI

struct MultiRatingPills: View {
    var imdb: Double?
    var tmdb: Double?
    var rt: Int?
    var rtAudience: Int?

    var body: some View {
        HStack(spacing: 6) {
            if let imdb = imdb {
                RatingPill(label: "IMDb", value: String(format: "%.1f", imdb), color: .yellow)
            }
            if let tmdb = tmdb {
                RatingPill(label: "TMDB", value: String(format: "%.1f", tmdb), color: SVColor.emerald)
            }
            if let rt = rt {
                RatingPillWithIcon(
                    iconName: rtCriticsIconName(score: rt),
                    value: "\(rt)%",
                    color: rt >= 60 ? Color(red: 0x67/255.0, green: 0xB3/255.0, blue: 0x46/255.0) : Color(red: 0xFA/255.0, green: 0x32/255.0, blue: 0x0A/255.0)
                )
            }
            if let rtAudience = rtAudience {
                RatingPillWithIcon(
                    iconName: rtAudienceIconName(score: rtAudience),
                    value: "\(rtAudience)%",
                    color: rtAudience >= 60 ? Color(red: 0x67/255.0, green: 0xB3/255.0, blue: 0x46/255.0) : Color(red: 0xFA/255.0, green: 0x32/255.0, blue: 0x0A/255.0)
                )
            }
        }
    }

    private func rtCriticsIconName(score: Int) -> String {
        if score >= 75 { return "ic_rt_certified_fresh" }
        if score >= 60 { return "ic_rt_fresh" }
        return "ic_rt_rotten"
    }

    private func rtAudienceIconName(score: Int) -> String {
        return score >= 60 ? "ic_rt_audience_fresh" : "ic_rt_audience_rotten"
    }
}

private struct RatingPill: View {
    let label: String
    let value: String
    let color: Color

    var body: some View {
        HStack(spacing: 3) {
            Text(label)
                .font(SVFont.pill)
                .foregroundColor(color)
            Text(value)
                .font(SVFont.pill)
                .foregroundColor(SVColor.onSurface)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(color.opacity(0.15))
        .cornerRadius(6)
    }
}

private struct RatingPillWithIcon: View {
    let iconName: String
    let value: String
    let color: Color

    var body: some View {
        HStack(spacing: 3) {
            Image(iconName)
                .resizable()
                .scaledToFit()
                .frame(width: 14, height: 14)
            Text(value)
                .font(SVFont.pill)
                .foregroundColor(SVColor.onSurface)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(color.opacity(0.15))
        .cornerRadius(6)
    }
}
