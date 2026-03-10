import SwiftUI

struct GroupListView: View {
    @ObservedObject var viewModel: GroupViewModel
    @State private var showCreateGroup = false

    var body: some View {
        Group {
            if viewModel.groups.isEmpty {
                emptyState
            } else {
                List(viewModel.groups) { group in
                    NavigationLink(value: group.groupId) {
                        GroupRow(group: group)
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Groups")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showCreateGroup = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showCreateGroup) {
            NavigationStack {
                CreateGroupView(viewModel: viewModel)
            }
        }
        .navigationDestination(for: String.self) { groupId in
            GroupChatPlaceholderView(groupId: groupId, viewModel: viewModel)
        }
        .onAppear {
            viewModel.refreshGroups()
        }
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "person.3")
                .font(.system(size: 64))
                .foregroundStyle(.secondary.opacity(0.6))

            Text("No groups yet")
                .font(.title3)
                .foregroundStyle(.secondary)

            Text("Create a group to start messaging with multiple contacts at once.")
                .font(.body)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 48)

            Button {
                showCreateGroup = true
            } label: {
                Label("Create Group", systemImage: "plus.circle.fill")
                    .font(.body.weight(.medium))
            }
            .buttonStyle(.borderedProminent)
            .padding(.top, 8)
        }
    }
}

struct GroupRow: View {
    let group: ChatGroup

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.15))
                    .frame(width: 48, height: 48)

                Image(systemName: "person.3.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(Color.accentColor)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(group.groupName)
                    .font(.headline)
                    .lineLimit(1)

                Text("\(group.memberCount) \(group.memberCount == 1 ? "member" : "members")")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding(.vertical, 4)
    }
}

struct GroupChatPlaceholderView: View {
    let groupId: String
    @ObservedObject var viewModel: GroupViewModel

    private var group: ChatGroup? {
        viewModel.groups.first { $0.groupId == groupId }
    }

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 48))
                .foregroundStyle(.secondary.opacity(0.6))

            Text("Group chat coming soon")
                .font(.title3)
                .foregroundStyle(.secondary)

            Text("Group messaging will be available in a future update.")
                .font(.body)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 48)
        }
        .navigationTitle(group?.groupName ?? "Group")
        .navigationBarTitleDisplayMode(.inline)
    }
}
