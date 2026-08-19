import SwiftUI
import WidgetKit

private let appGroup = "group.dev.aluo.shinsoux"

struct WidgetManga: Codable, Identifiable, Hashable {
    let id: Int64
    let title: String
    let coverURL: String?
    let unreadCount: Int
    let lastReadChapterID: Int64?
    let updatedAt: Int64
}

struct ShinsouWidgetEntry: TimelineEntry {
    let date: Date
    let manga: [WidgetManga]
}

struct ShinsouTimelineProvider: TimelineProvider {
    func placeholder(in context: Context) -> ShinsouWidgetEntry {
        ShinsouWidgetEntry(
            date: .now,
            manga: [WidgetManga(id: 0, title: "Shinsou X", coverURL: nil, unreadCount: 3, lastReadChapterID: nil, updatedAt: 0)]
        )
    }

    func getSnapshot(in context: Context, completion: @escaping (ShinsouWidgetEntry) -> Void) {
        completion(ShinsouWidgetEntry(date: .now, manga: loadManga()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ShinsouWidgetEntry>) -> Void) {
        let entry = ShinsouWidgetEntry(date: .now, manga: loadManga())
        completion(Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(30 * 60))))
    }

    private func loadManga() -> [WidgetManga] {
        guard let defaults = UserDefaults(suiteName: appGroup),
              let data = defaults.data(forKey: "widget.library") else { return [] }
        return (try? JSONDecoder().decode([WidgetManga].self, from: data)) ?? []
    }
}

struct WidgetCover: View {
    let manga: WidgetManga

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            if let rawURL = manga.coverURL, let url = URL(string: rawURL) {
                AsyncImage(url: url) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        coverFallback
                    }
                }
            } else {
                coverFallback
            }

            LinearGradient(
                colors: [.clear, .black.opacity(0.78)],
                startPoint: .center,
                endPoint: .bottom
            )

            VStack(alignment: .leading, spacing: 3) {
                Text(manga.title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                if manga.unreadCount > 0 {
                    Text("\(manga.unreadCount) 未讀")
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.82))
                }
            }
            .padding(9)
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .widgetURL(URL(string: "shinsou://manga/\(manga.id)"))
    }

    private var coverFallback: some View {
        ZStack {
            LinearGradient(
                colors: [Color.indigo.opacity(0.85), Color.purple.opacity(0.65)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Image(systemName: "books.vertical.fill")
                .font(.title)
                .foregroundStyle(.white.opacity(0.9))
        }
    }
}

struct RecentUpdatesWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: ShinsouWidgetEntry

    @ViewBuilder
    var body: some View {
        if entry.manga.isEmpty {
            if #available(iOSApplicationExtension 17.0, *) {
                emptyState
                    .containerBackground(.fill.tertiary, for: .widget)
            } else {
                emptyState
                    .background(Color(uiColor: .tertiarySystemFill))
            }
        } else {
            if #available(iOSApplicationExtension 17.0, *) {
                mangaGrid
                    .containerBackground(.background, for: .widget)
            } else {
                mangaGrid
                    .background(Color(uiColor: .systemBackground))
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "books.vertical")
                .font(.title2)
            Text("尚無最近更新")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var mangaGrid: some View {
        let columns = family == .systemMedium ? 3 : 1
        return HStack(spacing: 8) {
            ForEach(Array(entry.manga.prefix(columns))) { manga in
                WidgetCover(manga: manga)
            }
        }
        .padding(8)
    }
}

struct RecentUpdatesWidget: Widget {
    let kind = "dev.aluo.shinsoux.recent-updates"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: ShinsouTimelineProvider()) { entry in
            RecentUpdatesWidgetView(entry: entry)
        }
        .configurationDisplayName("最近更新")
        .description("快速查看最近更新並繼續閱讀。")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct ShinsouWidgetBundle: WidgetBundle {
    var body: some Widget {
        RecentUpdatesWidget()
    }
}
