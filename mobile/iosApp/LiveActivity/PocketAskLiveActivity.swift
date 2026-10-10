import ActivityKit
import SwiftUI
import WidgetKit

@main
struct PocketAskLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: PocketActivityAttributes.self) { context in
            HStack(spacing: 14) {
                VoiceSymbol(state: context.state, stale: context.isStale)
                    .font(.title2).frame(width: 36)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Locune").font(.caption).foregroundStyle(.secondary)
                    Text(context.isStale ? "Open app for status" : context.state.phase.title).font(.headline)
                }
                Spacer(minLength: 8)
                VoiceTimer(state: context.state, stale: context.isStale)
                VoiceAction(context: context)
            }
            .padding(16)
            .activityBackgroundTint(Color(UIColor.secondarySystemBackground))
            .activitySystemActionForegroundColor(.primary)
            .widgetURL(URL(string: "pocketask://voice"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    VoiceSymbol(state: context.state, stale: context.isStale).font(.title2).padding(.top, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    VoiceTimer(state: context.state, stale: context.isStale).padding(.top, 6)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Locune").font(.caption).foregroundStyle(.secondary)
                            Text(context.isStale ? "Open app for status" : context.state.phase.title).font(.headline)
                        }
                        Spacer()
                        VoiceAction(context: context)
                    }.padding(.vertical, 6)
                }
            } compactLeading: {
                VoiceSymbol(state: context.state, stale: context.isStale)
            } compactTrailing: {
                VoiceTimer(state: context.state, stale: context.isStale).frame(maxWidth: 54)
            } minimal: {
                VoiceSymbol(state: context.state, stale: context.isStale)
            }
            .widgetURL(URL(string: "pocketask://voice"))
            .keylineTint(context.state.phase == .recording ? .red : .mint)
        }
    }
}

private struct VoiceSymbol: View {
    let state: VoiceActivityState
    let stale: Bool
    var body: some View {
        Image(systemName: stale ? "ellipsis" : state.phase.symbol)
            .foregroundStyle(stale ? .secondary : state.phase == .recording ? Color.red : Color.mint)
            .accessibilityLabel(stale ? "Open app for status" : state.phase.title)
    }
}

private struct VoiceTimer: View {
    let state: VoiceActivityState
    let stale: Bool
    var body: some View {
        if stale { Image(systemName: "ellipsis") }
        else if let start = state.startedAt {
            Text(timerInterval: start...state.expiresAt, countsDown: false)
                .monospacedDigit().font(.caption).accessibilityLabel("Elapsed time")
                .accessibilityValue(Text(timerInterval: start...state.expiresAt, countsDown: false))
        } else {
            ProgressView().controlSize(.small).accessibilityLabel(state.phase.title)
        }
    }
}

private struct VoiceAction: View {
    let context: ActivityViewContext<PocketActivityAttributes>
    var body: some View {
        if !context.isStale && context.state.phase.canStop {
            Button(intent: StopVoiceIntent(sessionID: context.attributes.sessionID)) {
                Label(context.state.phase.actionTitle, systemImage: "stop.fill")
                    .font(.caption.weight(.semibold)).padding(.vertical, 6)
            }
            .buttonStyle(.bordered).tint(context.state.phase == .recording ? .red : .mint)
            .accessibilityLabel(context.state.phase == .recording ? "Finish recording" : context.state.phase == .preparingVoice ? "Cancel voice preparation" : "Stop reading aloud")
        }
    }
}
